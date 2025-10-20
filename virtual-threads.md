# Complete Guide: Java Virtual Threads & Structured Concurrency

## Table of Contents
1. [Why Virtual Threads?](#why-virtual-threads)
2. [Creating Virtual Threads](#creating-virtual-threads)
3. [How Virtual Threads Work](#how-virtual-threads-work)
4. [Scheduler and Cooperative Scheduling](#scheduler-and-cooperative-scheduling)
5. [Pinned Virtual Threads](#pinned-virtual-threads)
6. [ThreadLocal and Thread Pools](#threadlocal-and-thread-pools)
7. [Virtual Threads Internals](#virtual-threads-internals)
8. [Structured Concurrency](#structured-concurrency)
9. [Shutdown Policies](#shutdown-policies)
10. [Custom Policies](#custom-policies)
11. [Parent-Children Relationship](#parent-children-relationship)

---

## Why Virtual Threads?

### The Problem with Platform Threads

**Platform threads** (traditional Java threads) are expensive:

1. **Memory overhead**: Each thread requires megabytes of stack memory (not resizable)
2. **Creation cost**: Expensive to create and destroy
3. **Context switching**: Moving large stack frames is costly
4. **Limited scalability**: Easy to hit OutOfMemoryError

**Example demonstrating the limit:**
```java
private static void stackOverFlowErrorExample() {
    for (int i = 0; i < 100_000; i++) {
        new Thread(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(1L));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).start();
    }
}
```

**Output:**
```
[0.949s][warning][os,thread] Failed to start thread "Unknown thread" 
  - pthread_create failed (EAGAIN)
Exception in thread "main" java.lang.OutOfMemoryError: 
  unable to create native thread
```

### One Thread Per Task Model

The simplest concurrent programming model is **one task per thread**:
- Easy to understand and reason about
- Each thread has its own local variables
- Reduces need for shared mutable state
- BUT: Can't scale with platform threads

### Previous Solutions and Their Problems

**1. Callbacks (Callback Hell)**
```java
doSomethingAsync(result1 -> {
    doSomethingElse(result1, result2 -> {
        doAnotherThing(result2, result3 -> {
            // Callback hell - hard to read and maintain
        });
    });
});
```

**2. Reactive Programming**
- Requires learning complex DSL
- Loses simplicity of sequential code

**3. Async/Await (Kotlin Coroutines)**
- Must divide program into blocking vs non-blocking IO
- Not all tasks expressible as non-blocking IO
- No native JVM support (compiler-generated code)

### Virtual Threads Solution

Virtual threads are an **alternate implementation** of `java.lang.Thread`:
- Store stack frames in **heap** (garbage-collected memory) instead of stack
- **Small initial footprint**: Few hundred bytes instead of megabytes
- **Resizable stacks**: Can grow/shrink as needed
- **Cheap to create**: Can have millions of virtual threads
- **Same API**: No new learning required

---

## Creating Virtual Threads

### Method 1: Using Thread.ofVirtual()

**Creating a single virtual thread:**
```java
static Thread virtualThread(String name, Runnable runnable) {
    return Thread.ofVirtual()
        .name(name)
        .unstarted(runnable);
}
```

**Example: Morning Routine**
```java
static Thread bathTime() {
    return virtualThread(
        "Bath time",
        () -> {
            log("I'm going to take a bath");
            sleep(Duration.ofMillis(500L));
            log("I'm done with the bath");
        });
}

static Thread boilingWater() {
    return virtualThread(
        "Boil some water",
        () -> {
            log("I'm going to boil some water");
            sleep(Duration.ofSeconds(1L));
            log("I'm done with the water");
        });
}

@SneakyThrows
static void concurrentMorningRoutine() {
    var bathTime = bathTime();
    var boilingWater = boilingWater();
    bathTime.join();
    boilingWater.join();
}
```

**Output:**
```
08:34:46.217 [boilWater] INFO - VirtualThread[#21,boilWater]/runnable@ForkJoinPool-1-worker-1 
  | I'm going to take a bath
08:34:46.218 [boilWater] INFO - VirtualThread[#23,boilWater]/runnable@ForkJoinPool-1-worker-2 
  | I'm going to boil some water
08:34:46.732 [bath-time] INFO - VirtualThread[#21,boilWater]/runnable@ForkJoinPool-1-worker-2 
  | I'm done with the bath
08:34:47.231 [boilWater] INFO - VirtualThread[#23,boilWater]/runnable@ForkJoinPool-1-worker-2 
  | I'm done with the water
```

### Method 2: Using ExecutorService

**Creating virtual thread executor (unnamed threads):**
```java
@SneakyThrows
static void concurrentMorningRoutineWithExecutor() {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var bathTime = executor.submit(() -> {
            log("I'm going to take a bath");
            sleep(Duration.ofMillis(500L));
            log("I'm done with the bath");
        });
        
        var boilingWater = executor.submit(() -> {
            log("I'm going to boil some water");
            sleep(Duration.ofSeconds(1L));
            log("I'm done with the water");
        });
        
        bathTime.get();
        boilingWater.get();
    }
}
```

**Creating named virtual thread executor:**
```java
@SneakyThrows
static void concurrentMorningRoutineWithNamedExecutor() {
    ThreadFactory factory = Thread.ofVirtual()
        .name("routine-", 0)
        .factory();
        
    try (var executor = Executors.newThreadPerTaskExecutor(factory)) {
        var bathTime = executor.submit(() -> {
            log("I'm going to take a bath");
            sleep(Duration.ofMillis(500L));
            log("I'm done with the bath");
        });
        
        var boilingWater = executor.submit(() -> {
            log("I'm going to boil some water");
            sleep(Duration.ofSeconds(1L));
            log("I'm done with the water");
        });
        
        bathTime.get();
        boilingWater.get();
    }
}
```

**Output:**
```
08:44:35.390 [routine-1] INFO - VirtualThread[#23,routine-1]/runnable@ForkJoinPool-1-worker-2 
  | I'm going to boil some water
08:44:35.390 [routine-0] INFO - VirtualThread[#21,routine-0]/runnable@ForkJoinPool-1-worker-1 
  | I'm going to take a bath
```

---

## How Virtual Threads Work

### Architecture

```
┌─────────────────────────────────────────┐
│         Virtual Threads                 │
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐      │
│  │ VT1 │ │ VT2 │ │ VT3 │ │ VT4 │ ...  │
│  └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘      │
│     │       │       │       │          │
│     └───┬───┴───┬───┴───┬───┘          │
│         │       │       │              │
│         ▼       ▼       ▼              │
│    ┌────────────────────────┐          │
│    │   ForkJoinPool         │          │
│    │  (Carrier Threads)     │          │
│    └────────────────────────┘          │
│         │       │       │              │
│         ▼       ▼       ▼              │
│    ┌─────┐ ┌─────┐ ┌─────┐            │
│    │ PT1 │ │ PT2 │ │ PT3 │            │
│    └─────┘ └─────┘ └─────┘            │
│   Platform Threads (OS Threads)        │
└─────────────────────────────────────────┘
```

### Key Concepts

1. **Carrier Thread**: Platform thread that executes a virtual thread
2. **Mounting**: Copying virtual thread stack from heap to carrier thread stack
3. **Unmounting**: Copying virtual thread stack back to heap when blocking

### Default Scheduler Configuration

```java
// From java.lang.VirtualThread class
private static final ForkJoinPool DEFAULT_SCHEDULER = 
    new ForkJoinPool(
        Math.max(1, Runtime.getRuntime().availableProcessors()),  // parallelism
        pool -> new CarrierThread(pool),
        null, 
        false, 
        0, 
        256,  // maxPoolSize
        1, 
        null, 
        30, 
        SECONDS
    );
```

**System properties to configure:**
- `jdk.virtualThreadScheduler.parallelism` - Default: Number of CPU cores
- `jdk.virtualThreadScheduler.maxPoolSize` - Default: 256
- `jdk.virtualThreadScheduler.minRunnable` - Default: parallelism / 2

### Verifying Carrier Thread Reuse

```java
private static int numberOfCores() {
    return Runtime.getRuntime().availableProcessors();
}

private static void testCarrierThreadReuse() {
    ThreadFactory factory = Thread.ofVirtual()
        .name("routine-", 0)
        .factory();
        
    try (var executor = Executors.newThreadPerTaskExecutor(factory)) {
        // Create more virtual threads than available cores
        for (int i = 0; i < numberOfCores() + 1; i++) {
            int threadNumber = i;
            executor.submit(() -> 
                log("Hello, I'm a virtual thread number " + threadNumber)
            );
        }
    }
}
```

**Output (on 4-core machine):**
```
08:44:54.849 [routine-0] INFO - VirtualThread[#21,routine-0]/runnable@ForkJoinPool-1-worker-1 
  | Hello, I'm a virtual thread number 0
08:44:54.849 [routine-1] INFO - VirtualThread[#23,routine-1]/runnable@ForkJoinPool-1-worker-2 
  | Hello, I'm a virtual thread number 1
08:44:54.849 [routine-2] INFO - VirtualThread[#24,routine-2]/runnable@ForkJoinPool-1-worker-3 
  | Hello, I'm a virtual thread number 2
08:44:54.855 [routine-4] INFO - VirtualThread[#26,routine-4]/runnable@ForkJoinPool-1-worker-4 
  | Hello, I'm a virtual thread number 4
08:44:54.849 [routine-3] INFO - VirtualThread[#25,routine-3]/runnable@ForkJoinPool-1-worker-4 
  | Hello, I'm a virtual thread number 3
```

Notice `ForkJoinPool-1-worker-4` is reused twice!

---

## Scheduler and Cooperative Scheduling

### Cooperative vs Preemptive Scheduling

**Virtual threads use COOPERATIVE scheduling:**
- Virtual thread decides when to yield execution
- Yields control when reaching a **blocking operation**
- Not suitable for CPU-intensive tasks (use parallel streams instead)

**Scheduler:**
- Uses FIFO queue
- Consumed by dedicated ForkJoinPool
- Virtual thread unmounted from carrier when blocking
- Scheduler decides which virtual thread to mount next

### Example 1: Without Blocking (Thread Starvation)

```java
static Thread workingHard() {
    return virtualThread(
        "Working hard",
        () -> {
            log("I'm working hard");
            while (alwaysTrue()) {
                // No blocking operation - never yields!
            }
            sleep(Duration.ofMillis(100L));
            log("I'm done with working hard");
        });
}

static Thread takeABreak() {
    return virtualThread(
        "Take a break",
        () -> {
            log("I'm going to take a break");
            sleep(Duration.ofSeconds(1L));
            log("I'm done with the break");
        });
}

@SneakyThrows
static void workingHardRoutine() {
    var workingHard = workingHard();
    var takeABreak = takeABreak();
    workingHard.join();
    takeABreak.join();
}
```

**Run with single carrier thread:**
```
-Djdk.virtualThreadScheduler.parallelism=1
-Djdk.virtualThreadScheduler.maxPoolSize=1
-Djdk.virtualThreadScheduler.minRunnable=1
```

**Output:**
```
21:28:35.702 [Working hard] INFO - VirtualThread[#21,Working hard]/runnable@ForkJoinPool-1-worker-1 
  | I'm working hard
--- Running forever ---
```

The "Take a break" thread never runs because "Working hard" never yields!

### Example 2: With Blocking (Cooperative)

```java
static Thread workingConsciousness() {
    return virtualThread(
        "Working consciousness",
        () -> {
            log("I'm working hard");
            while (alwaysTrue()) {
                sleep(Duration.ofMillis(100L));  // Blocking operation!
            }
            log("I'm done with working hard");
        });
}

@SneakyThrows
static void workingConsciousnessRoutine() {
    var workingConsciousness = workingConsciousness();
    var takeABreak = takeABreak();
    workingConsciousness.join();
    takeABreak.join();
}
```

**Output:**
```
21:30:51.677 [Working consciousness] INFO - VirtualThread[#21,Working consciousness]/runnable@ForkJoinPool-1-worker-1 
  | I'm working hard
21:30:51.682 [Take a break] INFO - VirtualThread[#23,Take a break]/runnable@ForkJoinPool-1-worker-1 
  | I'm going to take a break
21:30:52.688 [Take a break] INFO - VirtualThread[#23,Take a break]/runnable@ForkJoinPool-1-worker-1 
  | I'm done with the break
--- Running forever ---
```

Both threads share the same carrier thread successfully!

### Example 3: Multiple Carrier Threads

**Configuration:**
```
-Djdk.virtualThreadScheduler.parallelism=2
-Djdk.virtualThreadScheduler.maxPoolSize=2
-Djdk.virtualThreadScheduler.minRunnable=2
```

**Output:**
```
21:33:43.641 [Working hard] INFO - VirtualThread[#21,Working hard]/runnable@ForkJoinPool-1-worker-1 
  | I'm working hard
21:33:43.641 [Take a break] INFO - VirtualThread[#24,Take a break]/runnable@ForkJoinPool-1-worker-2 
  | I'm going to take a break
21:33:44.655 [Take a break] INFO - VirtualThread[#24,Take a break]/runnable@ForkJoinPool-1-worker-2 
  | I'm done with the break
--- Running forever ---
```

Now both run truly concurrently on different carrier threads!

---

## Pinned Virtual Threads

### What is Pinning?

**Pinning** occurs when a virtual thread **cannot be unmounted** from its carrier thread during blocking operations. This blocks the carrier thread.

### Two Cases of Pinning

1. **synchronized blocks/methods**
2. **Native methods or foreign functions (JNI)**

### Example: Pinning with synchronized

```java
static class Bathroom {
    synchronized void useTheToilet() {
        log("I'm going to use the toilet");
        sleep(Duration.ofSeconds(1L));
        log("I'm done with the toilet");
    }
}

static Bathroom bathroom = new Bathroom();

static Thread goToTheToilet() {
    return virtualThread(
        "Go to the toilet",
        () -> bathroom.useTheToilet()
    );
}

@SneakyThrows
static void twoEmployeesInTheOffice() {
    var riccardo = goToTheToilet();
    var daniel = takeABreak();
    riccardo.join();
    daniel.join();
}
```

**Configuration (single carrier thread):**
```
-Djdk.virtualThreadScheduler.parallelism=1
-Djdk.virtualThreadScheduler.maxPoolSize=1
-Djdk.virtualThreadScheduler.minRunnable=1
```

**Output:**
```
16:29:05.548 [Go to the toilet] INFO - VirtualThread[#21,Go to the toilet]/runnable@ForkJoinPool-1-worker-1 
  | I'm going to use the toilet
16:29:06.558 [Go to the toilet] INFO - VirtualThread[#21,Go to the toilet]/runnable@ForkJoinPool-1-worker-1 
  | I'm done with the toilet
16:29:06.559 [Take a break] INFO - VirtualThread[#23,Take a break]/runnable@ForkJoinPool-1-worker-1 
  | I'm going to take a break
16:29:07.563 [Take a break] INFO - VirtualThread[#23,Take a break]/runnable@ForkJoinPool-1-worker-1 
  | I'm done with the break
```

Tasks are linearized - no concurrency!

### Detecting Pinned Threads

**Add JVM property:**
```
-Djdk.tracePinnedThreads=full  // or 'short'
```

**Output:**
```
Thread[#22,ForkJoinPool-1-worker-1,5,CarrierThreads]
    java.base/java.lang.VirtualThread$VThreadContinuation.onPinned(VirtualThread.java:183)
    ...
```

### Solution: Using ReentrantLock

```java
static class Bathroom {
    private final Lock lock = new ReentrantLock();
    
    @SneakyThrows
    void useTheToiletWithLock() {
        if (lock.tryLock(10, TimeUnit.SECONDS)) {
            try {
                log("I'm going to use the toilet");
                sleep(Duration.ofSeconds(1L));
                log("I'm done with the toilet");
            } finally {
                lock.unlock();
            }
        }
    }
}

static Thread goToTheToiletWithLock() {
    return virtualThread(
        "Go to the toilet", 
        () -> bathroom.useTheToiletWithLock()
    );
}

@SneakyThrows
static void twoEmployeesInTheOfficeWithLock() {
    var riccardo = goToTheToiletWithLock();
    var daniel = takeABreak();
    riccardo.join();
    daniel.join();
}
```

**Output (with single carrier thread):**
```
16:35:58.921 [Take a break] INFO - VirtualThread[#23,Take a break]/runnable@ForkJoinPool-1-worker-2 
  | I'm going to take a break
16:35:58.921 [Go to the toilet] INFO - VirtualThread[#21,Go to the toilet]/runnable@ForkJoinPool-1-worker-1 
  | I'm going to use the toilet
16:35:59.932 [Take a break] INFO - VirtualThread[#23,Take a break]/runnable@ForkJoinPool-1-worker-1 
  | I'm done with the break
16:35:59.933 [Go to the toilet] INFO - VirtualThread[#21,Go to the toilet]/runnable@ForkJoinPool-1-worker-2 
  | I'm done with the toilet
```

Now concurrent execution works properly!

### Allowing Additional Carrier Threads

**Alternative configuration:**
```
-Djdk.virtualThreadScheduler.parallelism=1
-Djdk.virtualThreadScheduler.maxPoolSize=2
-Djdk.virtualThreadScheduler.minRunnable=1
```

JVM can add a new carrier thread when one is pinned:

**Output:**
```
16:32:05.235 [Go to the toilet] INFO - VirtualThread[#21,Go to the toilet]/runnable@ForkJoinPool-1-worker-1 
  | I'm going to use the toilet
16:32:05.235 [Take a break] INFO - VirtualThread[#23,Take a break]/runnable@ForkJoinPool-1-worker-2 
  | I'm going to take a break
```

---

## ThreadLocal and Thread Pools

### Virtual Threads and Thread Pools

**DON'T use thread pools with virtual threads!**

Reasons:
- Virtual threads are cheap to create
- Designed for one-thread-per-request
- Thread pools are for expensive platform threads

### ThreadLocal Example

```java
static ThreadLocal<String> context = new ThreadLocal<>();

static void threadLocalExample() {
    Thread thread1 = new Thread(() -> {
        context.set("thread-1");
        log("Hey, my name is " + context.get());
    });
    thread1.setName("thread-1");
    
    Thread thread2 = new Thread(() -> {
        context.set("thread-2");
        log("Hey, my name is " + context.get());
    });
    thread2.setName("thread-2");
    
    thread1.start();
    thread2.start();
}
```

**Output:**
```
14:57:05.334 [thread-2] INFO - Thread[#22,thread-2,5,main] | Hey, my name is thread-2
14:57:05.334 [thread-1] INFO - Thread[#21,thread-1,5,main] | Hey, my name is thread-1
```

### ThreadLocal with Virtual Threads

```java
static void threadLocalWithVirtualThreadsExample() {
    Thread thread1 = virtualThread("thread-1", () -> {
        context.set("thread-1");
        log("Hey, my name is " + context.get());
    });
    
    Thread thread2 = virtualThread("thread-2", () -> {
        context.set("thread-2");
        log("Hey, my name is " + context.get());
    });
    
    thread1.start();
    thread2.start();
}
```

**Output:**
```
15:08:37.142 [thread-1] INFO - VirtualThread[#21,thread-1]/runnable@ForkJoinPool-1-worker-1 
  | Hey, my name is thread-1
15:08:37.142 [thread-2] INFO - VirtualThread[#23,thread-2]/runnable@ForkJoinPool-1-worker-2 
  | Hey, my name is thread-2
```

### Problems with ThreadLocal and Virtual Threads

1. **Memory footprint**: Millions of virtual threads = millions of ThreadLocals
2. **One-thread-per-request**: Data not shared between requests anyway
3. **Better alternative**: Scoped Values (Java 20+)

---

## Virtual Threads Internals

### Continuations

**Virtual thread** = **Continuation** + **Scheduler**

**Continuation**: A pointer to the state of an execution that can be:
- Yielded (suspended)
- Resumed later

### Virtual Thread States

```
┌──────────────────────────────────────────────┐
│                                              │
│         ┌─────────┐                          │
│         │   NEW   │                          │
│         └────┬────┘                          │
│              │                               │
│              ▼                               │
│         ┌─────────┐                          │
│         │ STARTED │                          │
│         └────┬────┘                          │
│              │                               │
│              ▼                               │
│   ┌──────────────────────┐                  │
│   │      RUNNABLE        │◄─────┐           │
│   └──────────┬───────────┘      │           │
│              │                   │           │
│      ┌───────┴───────┐          │           │
│      ▼               ▼           │           │
│  ┌────────┐      ┌────────┐     │           │
│  │ PARKING│      │YIELDING│     │           │
│  └───┬────┘      └───┬────┘     │           │
│      │               │           │           │
│      ▼               │           │           │
│  ┌────────┐          │           │           │
│  │ PARKED │──────────┴───────────┘           │
│  └────────┘                                  │
│      │                                       │
│      ▼                                       │
│  ┌────────┐                                  │
│  │ PINNED │                                  │
│  └────────┘                                  │
│                                              │
│  Green = Mounted                             │
│  Light Blue = Unmounted                      │
│  Violet = Pinned                             │
└──────────────────────────────────────────────┘
```

### VirtualThread Constructor

```java
// JDK core code
VirtualThread(Executor scheduler, String name, int characteristics, Runnable task) {
    super(name, characteristics, /*bound*/false);
    Objects.requireNonNull(task);
    
    // choose scheduler if not specified
    if (scheduler == null) {
        Thread parent = Thread.currentThread();
        if (parent instanceof VirtualThread vparent) {
            scheduler = vparent.scheduler;
        } else {
            scheduler = DEFAULT_SCHEDULER;
        }
    }
    
    this.scheduler = scheduler;
    this.cont = new VThreadContinuation(this, task);
    this.runContinuation = this::runContinuation;
}
```

### VThreadContinuation

```java
// JDK core code
private static class VThreadContinuation extends Continuation {
    VThreadContinuation(VirtualThread vthread, Runnable task) {
        super(VTHREAD_SCOPE, () -> vthread.run(task));
    }
    
    @Override
    protected void onPinned(Continuation.Pinned reason) {
        boolean printAll = tracePinning > 0;
        PinnedThreadPrinter.printStackTrace(System.out, printAll);
    }
}
```

### Starting and Running

```java
// JDK core code
@Override
public void start() {
    if (!compareAndSetState(NEW, STARTED)) {
        throw new IllegalThreadStateException("Already started");
    }
    submitRunContinuation();
}

// Mounting and running
public final void run() {
    while (true) {
        mount();
        // Execute continuation
        // ...
    }
}
```

### Parking (Yielding)

```java
// JDK core code
void park() {
    assert Thread.currentThread() == this;
    
    // complete immediately if parking permit available or interrupted
    if (parkPermit || interrupted) {
        if (parkPermit) parkPermit = false;
        return;
    }
    
    // park the thread
    setState(PARKING);
    try {
        if (!yieldContinuation()) {
            parkOnCarrierThread(false, 0);
        }
    } finally {
        assert (Thread.currentThread() == this) && (state() == RUNNING);
    }
}

private boolean yieldContinuation() {
    boolean notifyJvmti = notifyJvmtiEvents;
    
    // unmount
    if (notifyJvmti) notifyJvmtiUnmountBegin(false);
    unmount();
    try {
        return Continuation.yield(VTHREAD_SCOPE);
    } finally {
        // re-mount
        mount();
        if (notifyJvmti) notifyJvmtiMountEnd(false);
    }
}
```

---

## Structured Concurrency

### The Problem with Unstructured Concurrency

**Example: GitHub User Retrieval**

```java
record GitHubUser(User user, List<Repository> repositories) {}
record User(UserId userId, UserName name, Email email) {}
record UserId(long value) {}
record UserName(String value) {}
record Email(String value) {}
record Repository(String name, Visibility visibility, URI uri) {}
enum Visibility { PUBLIC, PRIVATE }

// Simulated GitHub API clients
interface FindUserByIdPort { 
    User findUser(UserId userId) throws InterruptedException;
}

interface FindRepositoriesByUserIdPort { 
    List<Repository> findRepositories(UserId userId) throws InterruptedException;
}

class GitHubRepository implements FindUserByIdPort, FindRepositoriesByUserIdPort {
    @Override 
    public User findUser(UserId userId) throws InterruptedException {
        LOGGER.info("Finding user with id '{}'", userId);
        delay(Duration.ofMillis(500L));
        LOGGER.info("User '{}' found", userId);
        return new User(userId, new UserName("rcardin"), new Email("[[email protected]](/cdn-cgi/l/email-protection)"));
    }

    @Override 
    public List<Repository> findRepositories(UserId userId) throws InterruptedException {
        LOGGER.info("Finding repositories for user with id '{}'", userId);
        delay(Duration.ofSeconds(1L));
        LOGGER.info("Repositories found for user '{}'", userId);
        return List.of(
            new Repository("raise4s", Visibility.PUBLIC, 
                URI.create("https://github.com/rcardin/raise4s")),
            new Repository("sus4s", Visibility.PUBLIC, 
                URI.create("https://github.com/rcardin/sus4s"))
        );
    }
    
    void delay(Duration duration) throws InterruptedException {
        Thread.sleep(duration);
    }
}
```

### Sequential Version (Good Properties)

```java
class FindGitHubUserSequentialService implements FindGitHubUserUseCase {
    private final FindUserByIdPort findUserByIdPort;
    private final FindRepositoriesByUserIdPort findRepositoriesByUserIdPort;

    public FindGitHubUserSequentialService(
            FindUserByIdPort findUserByIdPort,
            FindRepositoriesByUserIdPort findRepositoriesByUserIdPort) {
        this.findUserByIdPort = findUserByIdPort;
        this.findRepositoriesByUserIdPort = findRepositoriesByUserIdPort;
    }

    @Override
    public GitHubUser findGitHubUser(UserId userId) 
            throws InterruptedException {
        var user = findUserByIdPort.findUser(userId);
        var repositories = findRepositoriesByUserIdPort.findRepositories(userId);
        return new GitHubUser(user, repositories);
    }
}
```

**Good properties:**
1. **Clear scope**: Computation has well-defined boundaries
2. **Automatic cleanup**: JVM cleans resources when method completes
3. **Exception handling**: If first call fails, second never starts
4. **Easy to understand**: Code flow is obvious

### Concurrent Version (Problems)

```java
@Override
public GitHubUser findGitHubUser(UserId userId) 
        throws InterruptedException, ExecutionException {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var user = executor.submit(() -> findUserByIdPort.findUser(userId));
        var repositories = executor.submit(() -> 
            findRepositoriesByUserIdPort.findRepositories(userId));
        return new GitHubUser(user.get(), repositories.get());
    }
}
```

**Output (normal execution):**
```
08:50:34.159 [virtual-22] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=1]'
08:50:34.159 [virtual-20] INFO GitHubApp -- Finding user with id 'UserId[value=1]'
08:50:34.679 [virtual-20] INFO GitHubApp -- User 'UserId[value=1]' found
08:50:35.179 [virtual-22] INFO GitHubApp -- Repositories found for user 'UserId[value=1]'
08:50:35.183 [main] INFO GitHubApp -- GitHub user: GitHubUser[...]
```

**Problem 1: Thread Leak on Exception**

When `findUser` throws exception:
```java
@Override
public User findUser(UserId userId) throws InterruptedException {
    LOGGER.info("Finding user with id '{}'", userId);
    delay(Duration.ofMillis(100L));
    throw new RuntimeException("Socket timeout");
}
```

**Output:**
```
08:39:41.945 [virtual-20] INFO GitHubApp -- Finding user with id 'UserId[value=1]'
08:39:41.947 [virtual-22] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=1]'
08:39:42.969 [virtual-22] INFO GitHubApp -- Repositories found for user 'UserId[value=1]'
Exception in thread "main" java.util.concurrent.ExecutionException: 
    java.lang.RuntimeException: Socket timeout
```

**The repository task completes even though user task failed!** This wastes resources - a **thread leak**.

**Problem 2: Exception After Submission**

```java
@Override
public GitHubUser findGitHubUser(UserId userId) 
        throws InterruptedException, ExecutionException {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var user = executor.submit(() -> findUserByIdPort.findUser(userId));
        var repositories = executor.submit(() -> 
            findRepositoriesByUserIdPort.findRepositories(userId));
        throw new RuntimeException("Something went wrong");
    }
}
```

**Output:**
```
08:52:18.455 [virtual-20] INFO GitHubApp -- Finding user with id 'UserId[value=1]'
08:52:18.455 [virtual-22] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=1]'
08:52:18.975 [virtual-20] INFO GitHubApp -- User 'UserId[value=1]' found
08:52:19.476 [virtual-22] INFO GitHubApp -- Repositories found for user 'UserId[value=1]'
Exception in thread "main" java.lang.RuntimeException: Something went wrong
```

**Both tasks complete despite parent thread failing!** No parent-child relationship.

### What is Structured Concurrency?

**Definition**: Programming paradigm where the **syntactic structure** of code reflects the **semantic structure** of concurrency.

**Key principle**: Tasks form a tree where:
- Parent task completes only when all children complete
- If parent fails/stops, all children are stopped
- No child task can outlive its parent

**Inspired by:**
- Martin Sústrik's blog post "Structured Concurrency"
- Nathaniel J. Smith's "Go statement considered harmful"

**Already implemented in:**
- Kotlin Coroutines
- Scala Cats Effect Fibers
- Scala ZIO Fibers
- Java Project Loom (since Java 19)

---

## Shutdown Policies

### StructuredTaskScope (Base Class)

**Basic usage:**
```java
@Override
public GitHubUser findGitHubUser(UserId userId) throws ExecutionException {
    try (var scope = new StructuredTaskScope<>()) {
        var user = scope.fork(() -> findUserByIdPort.findUser(userId));
        var repositories = scope.fork(() -> 
            findRepositoriesByUserIdPort.findRepositories(userId));
        
        scope.join();  // MUST call join() before exiting try block!
        LOGGER.info("Both forked task completed");
        
        return new GitHubUser(user.get(), repositories.get());
    }
}
```

**Key concepts:**
- `fork()`: Creates child task, returns `Subtask<T>` (pointer to computation)
- `join()`: Waits for all forked tasks to complete
- `get()`: Retrieves result from `Subtask<T>` (only after join!)
- Try-with-resources: Ensures proper cleanup

**Output:**
```
11:06:36.350 [virtual-22] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=1]'
11:06:36.350 [virtual-20] INFO GitHubApp -- Finding user with id 'UserId[value=1]'
11:06:36.874 [virtual-20] INFO GitHubApp -- User 'UserId[value=1]' found
11:06:37.374 [virtual-22] INFO GitHubApp -- Repositories found for user 'UserId[value=1]'
11:06:37.377 [main] INFO GitHubApp -- Both forked task completed
```

**IMPORTANT:** Must call `join()` before exiting try block!

```java
// This will throw IllegalStateException!
try (var scope = new StructuredTaskScope<>()) {
    var user = scope.fork(() -> findUserByIdPort.findUser(userId));
    var repositories = scope.fork(() -> 
        findRepositoriesByUserIdPort.findRepositories(userId));
    
    // Missing scope.join()!
    return new GitHubUser(user.get(), repositories.get());
}
```

**Error:**
```
Exception in thread "main" java.lang.IllegalStateException: 
    Owner did not join after forking subtasks
```

### ShutdownOnFailure Policy

**Behavior**: Shuts down scope when **first task fails**.

```java
@Override
public GitHubUser findGitHubUser(UserId userId) 
        throws ExecutionException, InterruptedException {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        var user = scope.fork(() -> findUserByIdPort.findUser(userId));
        var repositories = scope.fork(() -> 
            findRepositoriesByUserIdPort.findRepositories(userId));
        
        scope.join();
        LOGGER.info("Both forked task completed");
        
        return new GitHubUser(user.get(), repositories.get());
    }
}
```

**When first task fails:**
```
08:22:42.466 [virtual-22] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=1]'
08:22:42.466 [virtual-20] INFO GitHubApp -- Finding user with id 'UserId[value=1]'
08:22:42.590 [main] INFO GitHubApp -- Both forked task completed
Exception in thread "main" java.lang.IllegalStateException: 
    Result is unavailable or subtask did not complete successfully
```

**Second task was canceled!** No thread leak.

#### Using throwIfFailed()

```java
@Override
public GitHubUser findGitHubUser(UserId userId) 
        throws ExecutionException, InterruptedException {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        var user = scope.fork(() -> findUserByIdPort.findUser(userId));
        var repositories = scope.fork(() -> 
            findRepositoriesByUserIdPort.findRepositories(userId));
        
        scope.join().throwIfFailed();  // Throws original exception!
        LOGGER.info("Both forked task completed");
        
        return new GitHubUser(user.get(), repositories.get());
    }
}
```

**Output:**
```
08:34:53.701 [virtual-20] INFO GitHubApp -- Finding user with id 'UserId[value=1]'
08:34:53.701 [virtual-22] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=1]'
Exception in thread "main" java.util.concurrent.ExecutionException: 
    java.lang.RuntimeException: Socket timeout
Caused by: java.lang.RuntimeException: Socket timeout
```

#### Remapping Exceptions

```java
scope.join().throwIfFailed(Function.identity());  // Re-throw original
```

**Signature:**
```java
public <X extends Throwable> void throwIfFailed(
    Function<Throwable, ? extends X> esf) throws X
```

**WARNING**: Function receives `Throwable` (includes `Error` like `OutOfMemoryError`). Handle carefully:

```java
scope.join().throwIfFailed(throwable -> {
    if (throwable instanceof Exception) {
        // Handle the exception
        return new MyException(throwable);
    } else {
        throw (Error) throwable;  // Don't catch Errors!
    }
});
```

#### Implementing par() Function

```java
record Pair<T1, T2>(T1 first, T2 second) {}

static <T1, T2> Pair<T1, T2> par(Callable<T1> first, Callable<T2> second) 
        throws InterruptedException, ExecutionException {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        var firstTask = scope.fork(first);
        var secondTask = scope.fork(second);
        scope.join().throwIfFailed();
        return new Pair<>(firstTask.get(), secondTask.get());
    }
}
```

**Using par():**
```java
@Override
public GitHubUser findGitHubUser(UserId userId) 
        throws ExecutionException, InterruptedException {
    var result = par(
        () -> findUserByIdPort.findUser(userId),
        () -> findRepositoriesByUserIdPort.findRepositories(userId)
    );
    return new GitHubUser(result.first(), result.second());
}
```

### ShutdownOnSuccess Policy

**Behavior**: Shuts down scope when **first task succeeds**.

**Use case: Cache with fallback**

```java
class FindRepositoriesByUserIdCache implements FindRepositoriesByUserIdPort {
    private final Map<UserId, List<Repository>> cache = new HashMap<>();

    public FindRepositoriesByUserIdCache() {
        cache.put(
            new UserId(42L),
            List.of(new Repository(
                "rockthejvm.github.io", 
                Visibility.PUBLIC,
                URI.create("https://github.com/rockthejvm/rockthejvm.github.io")
            ))
        );
    }

    @Override
    public List<Repository> findRepositories(UserId userId) 
            throws InterruptedException {
        delay(Duration.ofMillis(100L));  // Simulate network latency
        
        final List<Repository> repositories = cache.get(userId);
        if (repositories == null) {
            LOGGER.info("No cached repositories found for user with id '{}'", userId);
            throw new NoSuchElementException(
                "No cached repositories found for user with id '%s'".formatted(userId)
            );
        }
        return repositories;
    }

    public void addToCache(UserId userId, List<Repository> repositories) 
            throws InterruptedException {
        delay(Duration.ofMillis(100L));
        cache.put(userId, repositories);
    }
}
```

**Cache + API fallback:**
```java
static class GitHubCachedRepository implements FindRepositoriesByUserIdPort {
    private final FindRepositoriesByUserIdPort repository;
    private final FindRepositoriesByUserIdCache cache;

    GitHubCachedRepository(
            FindRepositoriesByUserIdPort repository,
            FindRepositoriesByUserIdCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    @Override
    public List<Repository> findRepositories(UserId userId) 
            throws InterruptedException, ExecutionException {
        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<List<Repository>>()) {
            scope.fork(() -> cache.findRepositories(userId));
            scope.fork(() -> {
                final List<Repository> repositories = 
                    repository.findRepositories(userId);
                cache.addToCache(userId, repositories);
                return repositories;
            });
            
            return scope.join().result();  // Returns first success!
        }
    }
}
```

**When cache misses (UserId=1):**
```
09:43:21.679 [virtual-22] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=1]'
09:43:21.779 [virtual-20] INFO GitHubApp -- No cached repositories found for user with id 'UserId[value=1]'
09:43:22.702 [virtual-22] INFO GitHubApp -- Repositories found for user 'UserId[value=1]'
09:43:22.812 [main] INFO GitHubApp -- GitHub user's repositories: [Repository[name=raise4s, ...]]
```

Cache fails, API succeeds, API task result returned!

**When cache hits (UserId=42):**
```
21:36:32.901 [virtual-22] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=42]'
21:36:33.014 [main] INFO GitHubApp -- GitHub user's repositories: [Repository[name=rockthejvm.github.io, ...]]
```

Cache succeeds fast, API task canceled!

**When both fail:**
```
16:18:21.615 [virtual-22] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=1]'
16:18:21.714 [virtual-20] INFO GitHubApp -- No cached repositories found for user with id 'UserId[value=1]'
Exception in thread "main" java.util.concurrent.ExecutionException: 
    java.util.NoSuchElementException: No cached repositories found for user with id 'UserId[value=1]'
```

First exception thrown (cache), second suppressed.

#### Remapping Exceptions

```java
scope.join().result(Function.identity());  // Re-throw original
```

**Signature:**
```java
public <X extends Throwable> T result(
    Function<Throwable, ? extends X> esf) throws X
```

#### Implementing raceAll() Function

```java
static <T> T raceAll(Callable<T> first, Callable<T> second) 
        throws InterruptedException, ExecutionException {
    try (var scope = new StructuredTaskScope.ShutdownOnSuccess<T>()) {
        scope.fork(first);
        scope.fork(second);
        return scope.join().result();
    }
}
```

**Using raceAll():**
```java
@Override
public List<Repository> findRepositories(UserId userId) 
        throws InterruptedException, ExecutionException {
    return raceAll(
        () -> cache.findRepositories(userId),
        () -> {
            final List<Repository> repositories = 
                repository.findRepositories(userId);
            cache.addToCache(userId, repositories);
            return repositories;
        }
    );
}
```

---

## Custom Policies

### Implementing race() Function

**Goal**: Return result of first completed task (success OR failure).

Different from `raceAll()` which waits for first success!

### Step 1: Extend StructuredTaskScope

```java
class ShutdownOnResult<T> extends StructuredTaskScope<T> {
    private final Lock lock = new ReentrantLock();
    private T firstResult;
    private Throwable firstException;
    
    // Implementation follows...
}
```

### Step 2: Override handleComplete()

```java
@Override
protected void handleComplete(Subtask<? extends T> subtask) {
    switch (subtask.state()) {
        case FAILED -> {
            lock.lock();
            try {
                if (firstException == null) {
                    firstException = subtask.exception();
                    shutdown();  // Stop other tasks!
                }
            } finally {
                lock.unlock();
            }
        }
        case SUCCESS -> {
            lock.lock();
            try {
                if (firstResult == null) {
                    firstResult = subtask.get();
                    shutdown();  // Stop other tasks!
                }
            } finally {
                lock.unlock();
            }
        }
        case UNAVAILABLE -> super.handleComplete(subtask);
    }
}
```

**Subtask states:**
- `SUCCESS`: Task completed successfully
- `FAILED`: Task threw exception
- `UNAVAILABLE`: Task not completed yet or was canceled

### Step 3: Override join()

```java
@Override
public ShutdownOnResult<T> join() throws InterruptedException {
    super.join();
    return this;  // For method chaining
}
```

### Step 4: Add resultOrThrow()

```java
public T resultOrThrow() throws ExecutionException {
    ensureOwnerAndJoined();  // Check: correct thread and joined
    
    if (firstException != null) {
        throw new ExecutionException(firstException);
    }
    return firstResult;
}
```

**Why `ensureOwnerAndJoined()`?**
- Ensures current thread owns the scope
- Ensures `join()` was called
- Prevents scope from escaping structured concurrency context
- Throws `WrongThreadException` if violated

### Complete ShutdownOnResult Implementation

```java
static class ShutdownOnResult<T> extends StructuredTaskScope<T> {
    private final Lock lock = new ReentrantLock();
    private T firstResult;
    private Throwable firstException;

    @Override
    protected void handleComplete(Subtask<? extends T> subtask) {
        switch (subtask.state()) {
            case FAILED -> {
                lock.lock();
                try {
                    if (firstException == null) {
                        firstException = subtask.exception();
                        shutdown();
                    }
                } finally {
                    lock.unlock();
                }
            }
            case SUCCESS -> {
                lock.lock();
                try {
                    if (firstResult == null) {
                        firstResult = subtask.get();
                        shutdown();
                    }
                } finally {
                    lock.unlock();
                }
            }
            case UNAVAILABLE -> super.handleComplete(subtask);
        }
    }

    @Override
    public ShutdownOnResult<T> join() throws InterruptedException {
        super.join();
        return this;
    }

    public T resultOrThrow() throws ExecutionException {
        ensureOwnerAndJoined();
        if (firstException != null) {
            throw new ExecutionException(firstException);
        }
        return firstResult;
    }
}
```

### Using ShutdownOnResult: Timeout Example

```java
static class FindRepositoriesByUserIdWithTimeout {
    final FindRepositoriesByUserIdPort delegate;
    
    FindRepositoriesByUserIdWithTimeout(FindRepositoriesByUserIdPort delegate) {
        this.delegate = delegate;
    }
    
    List<Repository> findRepositories(UserId userId, Duration timeout) 
            throws InterruptedException, ExecutionException {
        try (var scope = new ShutdownOnResult<List<Repository>>()) {
            scope.fork(() -> delegate.findRepositories(userId));
            scope.fork(() -> {
                delay(timeout);
                throw new TimeoutException("Timeout of %s reached".formatted(timeout));
            });
            
            return scope.join().resultOrThrow();
        }
    }
}
```

**Testing with 500ms timeout (API takes 1s):**
```java
public static void main() throws ExecutionException, InterruptedException {
    final GitHubRepository gitHubRepository = new GitHubRepository();
    final FindRepositoriesByUserIdWithTimeout findRepositoriesWithTimeout = 
        new FindRepositoriesByUserIdWithTimeout(gitHubRepository);

    final List<Repository> repositories = 
        findRepositoriesWithTimeout.findRepositories(
            new UserId(1L), 
            Duration.ofMillis(500L)
        );

    LOGGER.info("GitHub user's repositories: {}", repositories);
}
```

**Output:**
```
09:13:08.611 [virtual-20] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=1]'
Exception in thread "main" java.util.concurrent.ExecutionException: 
    java.util.concurrent.TimeoutException: Timeout of PT0.5S reached
```

Timeout wins, repository task canceled!

**Testing with 1.5s timeout:**
```
09:15:42.083 [virtual-20] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=1]'
09:15:43.100 [virtual-20] INFO GitHubApp -- Repositories found for user 'UserId[value=1]'
09:15:43.122 [main] INFO GitHubApp -- GitHub user's repositories: [Repository[name=raise4s, ...]]
```

Repository task wins, timeout task canceled!

### Implementing race() Function

```java
static <T> T race(Callable<T> first, Callable<T> second) 
        throws InterruptedException, ExecutionException {
    try (var scope = new ShutdownOnResult<T>()) {
        scope.fork(first);
        scope.fork(second);
        return scope.join().resultOrThrow();
    }
}
```

### Implementing timeout() Function

```java
static <T> T timeout(Duration timeout, Callable<T> task) 
        throws InterruptedException, ExecutionException {
    return race(
        task,
        () -> {
            delay(timeout);
            throw new TimeoutException("Timeout of %s reached".formatted(timeout));
        }
    );
}
```

**Usage:**
```java
public static void main() throws ExecutionException, InterruptedException {
    final GitHubRepository gitHubRepository = new GitHubRepository();
    
    final List<Repository> repositories = timeout(
        Duration.ofMillis(500L),
        () -> gitHubRepository.findRepositories(new UserId(1L))
    );

    LOGGER.info("GitHub user's repositories: {}", repositories);
}
```

### Built-in Timeout: joinUntil()

```java
static <T> T timeout2(Duration timeout, Callable<T> task) 
        throws InterruptedException, TimeoutException {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        var result = scope.fork(task);
        scope.joinUntil(Instant.now().plus(timeout));
        return result.get();
    }
}
```

---

## Parent-Children Relationship

### Understanding shutdown()

All policies call `shutdown()` to stop pending subtasks:

**ShutdownOnFailure:**
```java
// Java SDK
@Override
protected void handleComplete(Subtask<?> subtask) {
    if (subtask.state() == Subtask.State.FAILED 
            && firstException == null 
            && FIRST_EXCEPTION.compareAndSet(this, null, subtask.exception())) {
        super.shutdown();
    }
}
```

**ShutdownOnSuccess:**
```java
// Java SDK
@Override
protected void handleComplete(Subtask<? extends T> subtask) {
    if (firstResult != null) {
        return;
    }
    if (subtask.state() == Subtask.State.SUCCESS) {
        T result = subtask.get();
        Object r = (result != null) ? result : RESULT_NULL;
        if (FIRST_RESULT.compareAndSet(this, null, r)) {
            super.shutdown();
        }
    } else if (firstException == null) {
        FIRST_EXCEPTION.compareAndSet(this, null, subtask.exception());
    }
}
```

### Scope States

```java
// Java SDK
// states: OPEN -> SHUTDOWN -> CLOSED
private static final int OPEN = 0;     // initial state
private static final int SHUTDOWN = 1;
private static final int CLOSED = 2;

private volatile int state;
```

### shutdown() Implementation

```java
// Java SDK
public void shutdown() {
    ensureOwnerOrContainsThread();
    int s = ensureOpen();  // throws ISE if closed
    if (s < SHUTDOWN && implShutdown())
        flock.wakeup();
}

private boolean implShutdown() {
    shutdownLock.lock();
    try {
        if (state < SHUTDOWN) {
            flock.shutdown();
            state = SHUTDOWN;
            interruptAll();  // Key: interrupts all threads!
            return true;
        } else {
            return false;
        }
    } finally {
        shutdownLock.unlock();
    }
}

private void implInterruptAll() {
    flock.threads()
        .filter(t -> t != Thread.currentThread())
        .forEach(t -> {
            try {
                t.interrupt();
            } catch (Throwable ignore) { }
        });
}
```

**Key**: `shutdown()` interrupts all forked threads!

### close() Method

```java
// Java SDK
@Override
public void close() {
    ensureOwner();
    int s = state;
    if (s == CLOSED) return;
    
    try {
        if (s < SHUTDOWN) 
            implShutdown();  // Shutdown if not already shut down
        flock.close();
    } finally {
        state = CLOSED;
    }
    
    if (forkRound > lastJoinAttempted) {
        lastJoinCompleted = forkRound;
        throw newIllegalStateExceptionNoJoin();
    }
}
```

`close()` is the **last line of defense** - ensures all children are stopped.

### Interruption is Cooperative!

**Problem: CPU-intensive task**
```java
record Bitcoin(String hash) {}

static Bitcoin mineBitcoin() {
    LOGGER.info("Mining Bitcoin...");
    while (alwaysTrue()) {
        // Empty body - no interruption checkpoint!
    }
    LOGGER.info("Bitcoin mined!");
    return new Bitcoin("bitcoin-hash");
}

public static void main() throws ExecutionException, InterruptedException {
    final GitHubRepository gitHubRepository = new GitHubRepository();

    var repositories = race(
        () -> gitHubRepository.findRepositories(new UserId(42L)),
        () -> mineBitcoin()
    );

    LOGGER.info("GitHub user's repositories: {}", repositories);
}
```

**Output:**
```
08:49:09.118 [virtual-22] INFO GitHubApp -- Mining Bitcoin...
08:49:09.118 [virtual-20] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=42]'
08:49:10.135 [virtual-20] INFO GitHubApp -- Repositories found for user 'UserId[value=42]'
(infinite waiting)
```

**Race never completes!** Bitcoin mining has no interruption checkpoint.

**Solution: Add interruption checks**
```java
static Bitcoin mineBitcoinWithConsciousness() throws InterruptedException {
    LOGGER.info("Mining Bitcoin...");
    while (alwaysTrue()) {
        if (Thread.currentThread().isInterrupted()) {
            LOGGER.info("Bitcoin mining interrupted");
            throw new InterruptedException();
        }
    }
    LOGGER.info("Bitcoin mined!");
    return new Bitcoin("bitcoin-hash");
}
```

**Output:**
```
09:02:10.116 [virtual-22] INFO GitHubApp -- Mining Bitcoin...
09:02:10.133 [virtual-20] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=42]'
09:02:11.156 [virtual-20] INFO GitHubApp -- Repositories found for user 'UserId[value=42]'
09:02:11.164 [virtual-22] INFO GitHubApp -- Bitcoin mining interrupted
09:02:11.165 [main] INFO GitHubApp -- GitHub user's repositories: [...]
```

Now it works correctly!

### Preventing New Forks After Shutdown

```java
public static void main() throws ExecutionException, InterruptedException {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        scope.shutdown();
        scope.fork(() -> {
            LOGGER.info("Hello, structured concurrency!");
            return null;
        });
        scope.join().throwIfFailed();
    }
    LOGGER.info("Completed");
}
```

**Output:**
```
// No "Hello, structured concurrency!" message
// Task never starts because scope is already shut down
```

**Important**: Forked tasks that were not completed before `shutdown()` will **never trigger** `handleComplete()`.

### Complex Example: Nested Scopes

**Use case**: Retrieve information for two users with timeout.

```java
interface FindGitHubUserUseCase {
    List<GitHubUser> findGitHubUsers(UserId first, UserId second, Duration timeout) 
        throws InterruptedException, ExecutionException;
}
```

**Implementation using nested scopes:**
```java
@Override
public List<GitHubUser> findGitHubUsers(UserId first, UserId second, Duration timeout) 
        throws InterruptedException, ExecutionException {
    
    var gitHubUsers = timeout(
        timeout, 
        () -> par(
            () -> findGitHubUser(first), 
            () -> findGitHubUser(second)
        )
    );
    
    return List.of(gitHubUsers.first, gitHubUsers.second);
}

// Where findGitHubUser uses par() internally:
@Override
public GitHubUser findGitHubUser(UserId userId) 
        throws ExecutionException, InterruptedException {
    var result = par(
        () -> findUserByIdPort.findUser(userId),
        () -> findRepositoriesByUserIdPort.findRepositories(userId)
    );
    return new GitHubUser(result.first(), result.second());
}
```

**Task tree structure:**
```
                    findGitHubUsers (timeout=700ms)
                            |
                    ┌───────┴───────┐
                    |               |
            findGitHubUser(42)  findGitHubUser(1)
                    |               |
            ┌───────┴───────┐   ┌──┴──────┐
            |               |   |         |
        findUser(42)  findRepos(42) findUser(1) findRepos(1)
        (500ms)       (1000ms)     (500ms)     (1000ms)
```

**Testing with 700ms timeout:**
```java
public static void main() throws ExecutionException, InterruptedException {
    var repository = new GitHubRepository();
    var service = new FindGitHubUserStructuredConcurrencyService(
        repository, repository
    );

    final List<GitHubUser> gitHubUsers = service.findGitHubUsers(
        new UserId(42L), 
        new UserId(1L), 
        Duration.ofMillis(700L)
    );
}
```

**Output:**
```
08:15:10.955 [virtual-32] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=1]'
08:15:10.955 [virtual-30] INFO GitHubApp -- Finding repositories for user with id 'UserId[value=42]'
08:15:10.955 [virtual-31] INFO GitHubApp -- Finding user with id 'UserId[value=1]'
08:15:10.954 [virtual-29] INFO GitHubApp -- Finding user with id 'UserId[value=42]'
08:15:11.480 [virtual-31] INFO GitHubApp -- User 'UserId[value=1]' found
08:15:11.481 [virtual-29] INFO GitHubApp -- User 'UserId[value=42]' found
Exception in thread "main" java.util.concurrent.ExecutionException: 
    java.util.concurrent.TimeoutException: Timeout of PT0.7S reached
```

**What happened:**
1. All 4 leaf tasks started concurrently (4 virtual threads)
2. After ~500ms: Both user info tasks completed
3. Repository tasks still running (need 1000ms total)
4. At 700ms: Timeout triggered
5. **All pending tasks canceled across all scope levels!**

No thread leak - structured concurrency ensured cleanup!

### How Cascading Shutdown Works

**Sequence of events:**

1. **Timeout expires** in outer `race()` scope:
```java
delay(timeout);
throw new TimeoutException("Timeout of %s reached".formatted(timeout));
```

2. **ShutdownOnResult.handleComplete()** called:
```java
case FAILED -> {
    lock.lock();
    try {
        if (firstException == null) {
            firstException = subtask.exception();
            shutdown();  // ← Interrupts all threads in this scope
        }
    } finally {
        lock.unlock();
    }
}
```

3. **Outer scope interrupts** thread running `par()`:
```java
private void implInterruptAll() {
    flock.threads()
        .filter(t -> t != Thread.currentThread())
        .forEach(t -> t.interrupt());  // ← Interrupts par() thread
}
```

4. **par() scope's join()** throws `InterruptedException`:
```java
static <T1, T2> Pair<T1, T2> par(Callable<T1> first, Callable<T2> second) 
        throws InterruptedException, ExecutionException {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        var firstTask = scope.fork(first);
        var secondTask = scope.fork(second);
        scope.join().throwIfFailed();  // ← Throws InterruptedException
        return new Pair<>(firstTask.get(), secondTask.get());
    }
}
```

5. **par() scope's close()** shuts down its children:
```java
@Override
public void close() {
    // ...
    if (s < SHUTDOWN) 
        implShutdown();  // ← Interrupts findUser() and findRepos() tasks
    // ...
}
```

6. **Each findGitHubUser() scope** also closes, shutting down its children
7. **All 4 leaf tasks** eventually interrupted

**Sequence diagram (simplified):**
```
timeout()          par()          findGitHubUser(42)     findUser(42)
   |                |                    |                    |
   |--- fork() ---->|                    |                    |
   |                |--- fork() -------->|                    |
   |                |                    |--- fork() -------->|
   |                |                    |                  [running]
   |                |                  [waiting]              |
   |              [waiting]              |                    |
 [timeout]          |                    |                    |
   |                |                    |                    |
   |--shutdown()    |                    |                    |
   |--interrupt()-->|                    |                    |
   |                |                    |                    |
   |             [InterruptedException]  |                    |
   |                |                    |                    |
   |                |--close()           |                    |
   |                |--shutdown()        |                    |
   |                |--interrupt()------>|                    |
   |                |                    |                    |
   |                |                 [InterruptedException]  |
   |                |                    |                    |
   |                |                    |--close()           |
   |                |                    |--shutdown()        |
   |                |                    |--interrupt()------>|
   |                |                    |                    |
   |                |                    |                [interrupted]
```

### Key Takeaways

1. **close() is the guardian**: Always ensures children are shut down, even if custom policy forgets to call `shutdown()`

2. **Interruption cascades**: Parent interruption flows down through all scope levels

3. **No thread leaks**: Impossible for child task to outlive parent in structured concurrency

4. **Tree structure enforced**: Code structure mirrors concurrency structure

---

## Best Practices and Anti-Patterns

### ✅ DO: Use Virtual Threads

**Good - One thread per task:**
```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (Request request : requests) {
        executor.submit(() -> handleRequest(request));
    }
}
```

### ❌ DON'T: Pool Virtual Threads

**Bad - Pooling virtual threads:**
```java
// DON'T DO THIS!
ExecutorService pool = Executors.newFixedThreadPool(100, 
    Thread.ofVirtual().factory());
```

**Why?** Virtual threads are cheap to create. Pooling defeats the purpose.

### ✅ DO: Use Structured Concurrency

**Good - Structured:**
```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var task1 = scope.fork(() -> doWork1());
    var task2 = scope.fork(() -> doWork2());
    scope.join().throwIfFailed();
    return new Result(task1.get(), task2.get());
}
```

### ❌ DON'T: Use Unstructured Concurrency

**Bad - Unstructured:**
```java
// Thread leaks possible!
Future<Result1> future1 = executor.submit(() -> doWork1());
Future<Result2> future2 = executor.submit(() -> doWork2());
// What if exception thrown here?
return new Result(future1.get(), future2.get());
```

### ✅ DO: Use ReentrantLock

**Good - No pinning:**
```java
private final Lock lock = new ReentrantLock();

void criticalSection() {
    lock.lock();
    try {
        // Critical section
    } finally {
        lock.unlock();
    }
}
```

### ❌ DON'T: Use synchronized

**Bad - Pins virtual thread:**
```java
synchronized void criticalSection() {
    // Pins virtual thread to carrier!
}
```

### ✅ DO: Add Interruption Checkpoints

**Good - Interruptible:**
```java
void cpuIntensiveWork() throws InterruptedException {
    while (hasMoreWork()) {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        // Do work
    }
}
```

### ❌ DON'T: Ignore Interruption

**Bad - Not interruptible:**
```java
void cpuIntensiveWork() {
    while (true) {
        // No interruption checkpoint!
        // Structured concurrency can't stop this
    }
}
```

### ✅ DO: Always Call join()

**Good:**
```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var task = scope.fork(() -> doWork());
    scope.join().throwIfFailed();  // ← Always call join()
    return task.get();
}
```

### ❌ DON'T: Skip join()

**Bad:**
```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var task = scope.fork(() -> doWork());
    return task.get();  // ← IllegalStateException!
}
```

### ✅ DO: Handle InterruptedException

**Good - Forward interruption:**
```java
void doWork() throws InterruptedException {
    try {
        Thread.sleep(1000);
    } catch (InterruptedException e) {
        // Clean up resources
        throw e;  // ← Re-throw!
    }
}
```

### ❌ DON'T: Swallow InterruptedException

**Bad - Loses interruption:**
```java
void doWork() {
    try {
        Thread.sleep(1000);
    } catch (InterruptedException e) {
        // Swallowed! Parent can't cancel this task
    }
}
```

### ✅ DO: Be Careful with Errors

**Good - Don't catch Errors:**
```java
scope.join().throwIfFailed(throwable -> {
    if (throwable instanceof Exception) {
        return new MyException(throwable);
    } else {
        throw (Error) throwable;  // ← Let Errors through
    }
});
```

### ❌ DON'T: Catch Everything

**Bad:**
```java
scope.join().throwIfFailed(throwable -> 
    new MyException(throwable)  // ← Wraps OutOfMemoryError too!
);
```

### ✅ DO: Use Appropriate Scope

**ShutdownOnFailure** - When all tasks must succeed:
```java
// Both user and repos needed
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var user = scope.fork(() -> findUser(id));
    var repos = scope.fork(() -> findRepos(id));
    scope.join().throwIfFailed();
    return new Result(user.get(), repos.get());
}
```

**ShutdownOnSuccess** - When any task success is enough:
```java
// Either cache or API is fine
try (var scope = new StructuredTaskScope.ShutdownOnSuccess<Data>()) {
    scope.fork(() -> cache.get(key));
    scope.fork(() -> api.fetch(key));
    return scope.join().result();
}
```

**Custom** - When you need specific behavior:
```java
// First to complete (success or failure)
try (var scope = new ShutdownOnResult<Data>()) {
    scope.fork(() -> source1.get());
    scope.fork(() -> source2.get());
    return scope.join().resultOrThrow();
}
```

---

## Common Patterns

### Pattern 1: Parallel Execution with Failure Handling

```java
<T1, T2> Pair<T1, T2> par(Callable<T1> first, Callable<T2> second) 
        throws InterruptedException, ExecutionException {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        var t1 = scope.fork(first);
        var t2 = scope.fork(second);
        scope.join().throwIfFailed();
        return new Pair<>(t1.get(), t2.get());
    }
}
```

### Pattern 2: Race (First Success)

```java
<T> T raceAll(Callable<T> first, Callable<T> second) 
        throws InterruptedException, ExecutionException {
    try (var scope = new StructuredTaskScope.ShutdownOnSuccess<T>()) {
        scope.fork(first);
        scope.fork(second);
        return scope.join().result();
    }
}
```

### Pattern 3: Race (First Completed)

```java
<T> T race(Callable<T> first, Callable<T> second) 
        throws InterruptedException, ExecutionException {
    try (var scope = new ShutdownOnResult<T>()) {
        scope.fork(first);
        scope.fork(second);
        return scope.join().resultOrThrow();
    }
}
```

### Pattern 4: Timeout

```java
<T> T timeout(Duration timeout, Callable<T> task) 
        throws InterruptedException, ExecutionException {
    return race(
        task,
        () -> {
            Thread.sleep(timeout.toMillis());
            throw new TimeoutException("Timeout of %s reached".formatted(timeout));
        }
    );
}
```

### Pattern 5: Cache with Fallback

```java
<T> T cacheOrFetch(Callable<T> cache, Callable<T> fetch) 
        throws InterruptedException, ExecutionException {
    try (var scope = new StructuredTaskScope.ShutdownOnSuccess<T>()) {
        scope.fork(cache);
        scope.fork(fetch);
        return scope.join().result();
    }
}
```

### Pattern 6: Retry with Timeout

```java
<T> T retryWithTimeout(Callable<T> task, int maxAttempts, Duration timeout) 
        throws ExecutionException, InterruptedException {
    for (int i = 0; i < maxAttempts; i++) {
        try {
            return timeout(timeout, task);
        } catch (TimeoutException e) {
            if (i == maxAttempts - 1) throw e;
            LOGGER.warn("Attempt {} timed out, retrying...", i + 1);
        }
    }
    throw new IllegalStateException("Should not reach here");
}
```

### Pattern 7: Scatter-Gather

```java
<T> List<T> scatterGather(List<Callable<T>> tasks) 
        throws InterruptedException, ExecutionException {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        List<Subtask<T>> subtasks = tasks.stream()
            .map(scope::fork)
            .toList();
        
        scope.join().throwIfFailed();
        
        return subtasks.stream()
            .map(Subtask::get)
            .toList();
    }
}
```

### Pattern 8: First N Successes

```java
class FirstNSuccesses<T> extends StructuredTaskScope<T> {
    private final int n;
    private final List<T> results = new CopyOnWriteArrayList<>();
    
    FirstNSuccesses(int n) {
        this.n = n;
    }
    
    @Override
    protected void handleComplete(Subtask<? extends T> subtask) {
        if (subtask.state() == Subtask.State.SUCCESS) {
            results.add(subtask.get());
            if (results.size() >= n) {
                shutdown();
            }
        }
    }
    
    public List<T> results() {
        ensureOwnerAndJoined();
        return List.copyOf(results);
    }
}

// Usage:
<T> List<T> firstNSuccesses(List<Callable<T>> tasks, int n) 
        throws InterruptedException {
    try (var scope = new FirstNSuccesses<T>(n)) {
        for (var task : tasks) {
            scope.fork(task);
        }
        scope.join();
        return scope.results();
    }
}
```

---

## Performance Considerations

### Virtual Threads Are Cheap

**Memory:**
- Platform thread: ~2 MB stack
- Virtual thread: ~1 KB initial stack

**Can create:**
- Platform threads: Thousands
- Virtual threads: Millions

### When to Use Virtual Threads

✅ **Good for:**
- I/O-bound operations
- Network calls
- Database queries
- File operations
- High concurrency (many requests)

❌ **Not ideal for:**
- CPU-bound operations (use parallel streams)
- Tasks that never block
- Tasks requiring synchronized blocks (use ReentrantLock)

### Carrier Thread Pool Size

**Default**: Number of CPU cores

**Can configure with:**
```
-Djdk.virtualThreadScheduler.parallelism=N
```

**Recommendation**: Usually keep default unless specific needs

### Monitoring and Debugging

**Enable pinned thread detection:**
```
-Djdk.tracePinnedThreads=full
```

**Output shows:**
- Which virtual thread is pinned
- Stack trace showing synchronized block/method
- Carrier thread details

---

## Migration Guide

### From ExecutorService to Virtual Threads

**Before (platform threads):**
```java
ExecutorService executor = Executors.newFixedThreadPool(10);
try {
    Future<String> future = executor.submit(() -> {
        Thread.sleep(1000);
        return "Done";
    });
    String result = future.get();
} finally {
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);
}
```

**After (virtual threads):**
```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<String> future = executor.submit(() -> {
        Thread.sleep(1000);
        return "Done";
    });
    String result = future.get();
}
```

### From CompletableFuture to Structured Concurrency

**Before:**
```java
CompletableFuture<User> userFuture = 
    CompletableFuture.supplyAsync(() -> findUser(id));
CompletableFuture<List<Repo>> reposFuture = 
    CompletableFuture.supplyAsync(() -> findRepos(id));

CompletableFuture<Result> result = userFuture.thenCombine(
    reposFuture,
    (user, repos) -> new Result(user, repos)
);

return result.get();
```

**After:**
```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var user = scope.fork(() -> findUser(id));
    var repos = scope.fork(() -> findRepos(id));
    scope.join().throwIfFailed();
    return new Result(user.get(), repos.get());
}
```

### From synchronized to ReentrantLock

**Before:**
```java
private final Object lock = new Object();

public void doSomething() {
    synchronized(lock) {
        // Critical section
    }
}
```

**After:**
```java
private final Lock lock = new ReentrantLock();

public void doSomething() {
    lock.lock();
    try {
        // Critical section
    } finally {
        lock.unlock();
    }
}
```

---

## Troubleshooting

### Issue 1: IllegalStateException - No join()

**Error:**
```
java.lang.IllegalStateException: Owner did not join after forking subtasks
```

**Solution:** Always call `join()` before exiting try block:
```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var task = scope.fork(() -> doWork());
    scope.join().throwIfFailed();  // ← Don't forget!
    return task.get();
}
```

### Issue 2: Task Never Completes

**Problem:** Task has no interruption checkpoint

**Solution:** Add interruption checks:
```java
void cpuIntensiveWork() throws InterruptedException {
    while (hasWork()) {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        // Do work chunk
    }
}
```

### Issue 3: Performance Degradation

**Problem:** Using synchronized blocks

**Symptom:** Tasks run slower than expected

**Detection:**
```
-Djdk.tracePinnedThreads=short
```

**Solution:** Replace with ReentrantLock

### Issue 4: OutOfMemoryError with ThreadLocal

**Problem:** Too many ThreadLocals with millions of virtual threads

**Solution:**
- Avoid ThreadLocal with virtual threads
- Use method parameters or scoped values (Java 20+)
- Consider if you really need millions of threads

### Issue 5: Nested Scope Complexity

**Problem:** Hard to track which scope does what

**Solution:** Extract to named methods:
```java
// Instead of deeply nested scopes:
try (var scope1 = ...) {
    try (var scope2 = ...) {
        try (var scope3 = ...) {
            // Hard to read
        }
    }
}

// Extract to methods:
Result processUsers(List<UserId> ids) throws ... {
    return timeout(Duration.ofSeconds(5), () -> 
        par(() -> processUser(ids.get(0)),
            () -> processUser(ids.get(1)))
    );
}

User processUser(UserId id) throws ... {
    return par(() -> findUser(id),
               () -> findRepos(id));
}
```

---

## Summary

### Key Concepts

1. **Virtual Threads**: Lightweight threads stored in heap, not stack
2. **Structured Concurrency**: Code structure reflects concurrency structure
3. **Cooperative Scheduling**: Thread yields on blocking operations
4. **No Pinning**: Use ReentrantLock instead of synchronized
5. **Parent-Children**: Children can't outlive parent

### Main Benefits

✅ Simplicity: Sequential-looking concurrent code  
✅ Scalability: Millions of threads possible  
✅ Safety: No thread leaks with structured concurrency  
✅ Performance: Cheap to create and switch  
✅ Compatibility: Same Thread API

### Remember

- Always call `join()` before exiting scope
- Use ReentrantLock, not synchronized
- Add interruption checkpoints for CPU-intensive work
- Choose appropriate shutdown policy
- Virtual threads != thread pools
- Structured concurrency prevents resource leaks

---

## Additional Resources

**JEPs:**
- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [JEP 480: Structured Concurrency (Third Preview)](https://openjdk.org/jeps/480)

**Articles:**
- [The Ultimate Guide to Java Virtual Threads](https://rockthejvm.com/articles/the-ultimate-guide-to-java-virtual-threads)
- [Structured Concurrency in Java](https://rockthejvm.com/articles/structured-concurrency-in-java)

Videos
- https://www.youtube.com/watch?v=nnpeH92QpeY
- https://www.youtube.com/watch?v=smZayMmPsKw
- https://www.youtube.com/watch?v=kz_R6-fdSZc
- https://www.youtube.com/watch?v=rcrA4SOdhzA&list=PLRsbF2sD7JVrgzHNkX4wUHmoGICMaE446&index=90

**Related Projects:**
- Project Loom (Virtual Threads)
- Project Valhalla (Value Types)
- Scoped Values (Java 20+)