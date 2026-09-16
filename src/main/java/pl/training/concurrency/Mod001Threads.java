package pl.training.concurrency;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/*
What is a thread

- A process is an isolated unit of execution managed by the operating system, with its own address space and resources.
- A thread is the smallest unit the operating system can schedule. Multiple threads share the address space of the
  process they belong to: heap, loaded classes, file descriptors, etc.
- Every JVM starts with one foreground thread named main. The JVM exits when all non-daemon threads have terminated.
- Threads enable concurrency (progress on multiple tasks logically at once) and parallelism (actual simultaneous
  execution on multiple CPU cores).
- The JVM exposes platform threads as a thin wrapper over OS threads. They are preemptively scheduled by the OS — your
  code does not control when a context switch happens, so any shared state read or written without synchronization can
  be observed in arbitrary order.
*/

final class HeartbeatTask implements Runnable {
    private final int beats;
    HeartbeatTask(int beats) { this.beats = beats; }

    @Override public void run() {
        for (int i = 1; i <= beats; i++) {
            System.out.printf(Locale.ROOT, "heartbeat %d/%d on %s%n",
                    i, beats, Thread.currentThread().getName());
            try { Thread.sleep(100); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}

final class CancellableReportJob implements Runnable {
    @Override public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                System.out.println("  generating report fragment...");
                Thread.sleep(300); // sleep is the typical interruption point
            }
        } catch (InterruptedException e) {
            // Restore the flag — we caught it but consumers above may want to know.
            Thread.currentThread().interrupt();
            System.out.println("  report cancelled, winding down");
        } finally {
            System.out.println("  releasing report resources");
        }
    }
}

public final class Mod001Threads {

    private Mod001Threads() {}

    /*
    Creating threads

    - The idiomatic way to define a task is to implement Runnable (or Callable<V> if the task returns a value) and pass
      it to a Thread. This separates what the task does from how it is run.
    - Subclassing Thread and overriding run() is legal but discouraged: it couples the task with the thread, prevents
      extending another class, and confuses readers about the lifetime of the task.
    - Java 21 added a fluent builder via Thread.ofPlatform(). It supports .name(...), .daemon(...), .priority(...),
      and .start(Runnable). There is also Thread.ofVirtual() for virtual threads — covered in Mod011VirtualThreads.
    - A Thread instance can be started exactly once. Calling start() on an already started thread throws
      IllegalThreadStateException.
    */
    static void creatingThreads() throws InterruptedException {
        System.out.println("[Section 2] creating threads");

        // (a) Runnable + Thread (preferred, decouples task from thread)
        var task = new HeartbeatTask(3);
        var t1 = new Thread(task, "heartbeat-runnable");
        t1.start();
        t1.join();

        // (b) Same task as a lambda — Runnable is a functional interface.
        var t2 = new Thread(() -> System.out.println("hello from " + Thread.currentThread().getName()),
                "heartbeat-lambda");
        t2.start();
        t2.join();

        // (c) Modern fluent builder (Java 21+).
        var t3 = Thread.ofPlatform()
                .name("heartbeat-builder")
                .priority(Thread.NORM_PRIORITY)
                .start(() -> System.out.println("hello from builder thread"));
        t3.join();
    }

    /*
    Thread.start vs Thread.run

    - start() schedules the thread for execution by the JVM/OS. The new thread is created, and the JVM eventually calls
      run() on it.
    - Calling run() directly is just an ordinary method call on the current thread. No new thread is created. Beginners
      who write t.run() instead of t.start() get sequential execution and miss any concurrency at all.
    */
    static void startVsRun() throws InterruptedException {
        System.out.println("[Section 3] start vs run");

        Runnable identify = () ->
                System.out.println("  running on " + Thread.currentThread().getName());

        // start(): schedules a new thread; identify runs there.
        var t = new Thread(identify, "spawned");
        t.start();
        t.join();

        // run(): plain method call on the CURRENT thread; no new thread.
        new Thread(identify, "not-spawned").run();
    }

    /*
    Thread states

    The Thread.State enum has six values:

    - NEW — created but not yet started.
    - RUNNABLE — eligible to run; either executing or waiting for CPU.
    - BLOCKED — waiting to acquire an intrinsic lock held by another thread.
    - WAITING — waiting indefinitely for another thread (Object.wait, Thread.join, LockSupport.park).
    - TIMED_WAITING — waiting for a bounded amount of time (Thread.sleep, Object.wait(timeout), Thread.join(timeout)).
    - TERMINATED — the run method has returned (normally or by exception).

    Thread.getState() is a snapshot, intended for monitoring and diagnostics. It must not be used to make
    synchronization decisions because the state can change between the read and the next instruction.
    */
    static void threadStates() throws InterruptedException {
        System.out.println("[Section 4] thread states");

        var lock = new Object();
        Runnable holdLockBriefly = () -> {
            synchronized (lock) {
                try { Thread.sleep(150); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };

        var holder = new Thread(holdLockBriefly, "lock-holder");
        var contender = new Thread(holdLockBriefly, "lock-contender");

        System.out.println("  before start: " + holder.getState());     // NEW
        holder.start();
        Thread.sleep(20);
        contender.start();
        Thread.sleep(20);

        // holder is sleeping while still holding the monitor.
        System.out.println("  holder while sleeping:    " + holder.getState());     // TIMED_WAITING
        // contender is parked at the synchronized boundary.
        System.out.println("  contender waiting on lock: " + contender.getState()); // BLOCKED

        holder.join();
        contender.join();
        System.out.println("  after join: holder=" + holder.getState()
                + ", contender=" + contender.getState());                            // TERMINATED
    }

    /*
    Daemon vs user threads

    - A user (foreground) thread keeps the JVM alive — it will not exit while any user thread is still running.
    - A daemon (background) thread does not. When only daemon threads remain, the JVM shuts down without waiting for
      them.
    - setDaemon(true) must be called before start(). Calling it on a running thread throws IllegalThreadStateException.
    - A daemon thread inherits its daemon status when forking another thread. Daemon threads are cut off abruptly at
      JVM exit — do not use them for work that must complete (writing files, flushing buffers).
    - Typical uses: heartbeats, telemetry pumps, GC-like background workers — anything where being terminated mid-task
      is acceptable.
    */
    static void daemonVsUser() throws InterruptedException {
        System.out.println("[Section 5] daemon vs user threads");

        // The daemon thread runs forever; the JVM does not wait for it on exit.
        var heartbeat = Thread.ofPlatform()
                .name("heartbeat-daemon")
                .daemon(true)
                .unstarted(() -> {
                    while (true) {
                        System.out.println("  ...heartbeat tick");
                        try { Thread.sleep(50); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                });
        heartbeat.start();

        // We let the daemon tick a few times. The JVM would happily exit while it is still
        // running — that is the whole point — but we interrupt it here anyway so the rest of
        // this demo's output stays readable. In a real program you would just let it go.
        Thread.sleep(180);
        System.out.println("  leaving section while daemon=" + heartbeat.isDaemon()
                + " is still running (the JVM would not wait for it)");
        heartbeat.interrupt();
        heartbeat.join();
    }

    /*
    Thread.sleep and InterruptedException

    - Thread.sleep(millis) parks the current thread for at least the given duration (it can sleep longer; precision is
      OS-dependent). The thread does not release any locks it holds — never sleep while holding a contended monitor.
    - Thread.sleep declares the checked InterruptedException. When another thread calls t.interrupt() on a sleeping
      thread, the JVM wakes it up and throws InterruptedException, clearing the interrupt flag.
    - If you cannot propagate InterruptedException (e.g., inside a Runnable.run), you should restore the flag with
      Thread.currentThread().interrupt() so callers higher up in the stack still see the cancellation request.
    - Swallowing InterruptedException silently is the single most common cancellation bug in Java code.
    */
    static void sleepAndInterrupt() throws InterruptedException {
        System.out.println("[Section 6] sleep + InterruptedException");

        var sleeper = new Thread(() -> {
            try {
                Thread.sleep(10_000); // long sleep, will be cut short
            } catch (InterruptedException e) {
                // The flag has been CLEARED by the throw — restore it for callers.
                Thread.currentThread().interrupt();
                System.out.println("  sleeper woken up; isInterrupted()="
                        + Thread.currentThread().isInterrupted());
            }
        }, "sleeper");

        sleeper.start();
        Thread.sleep(100);
        sleeper.interrupt();
        sleeper.join();
    }

    /*
    Joining threads

    - t.join() blocks the calling thread until t has terminated. It establishes a happens-before edge: every action in
      t is visible to the caller after join() returns.
    - t.join(millis) waits at most millis milliseconds and then returns void — check t.isAlive() afterwards if you
      need to know whether it actually finished.
    - Since Java 19 there is also t.join(Duration), which RETURNS a boolean: true if the thread has terminated,
      false if the timeout expired first. Prefer it — the result is impossible to forget to check.
    - join does not request termination — it only waits. To stop a worker that is still running you must signal it
      (interrupt or set a flag). See section 8.
    */
    static void joiningThreads() throws InterruptedException {
        System.out.println("[Section 7] joining threads");

        var slow = new Thread(() -> {
            try { Thread.sleep(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            System.out.println("  slow worker done");
        }, "slow-worker");

        slow.start();

        // (a) bounded join — returns after the timeout regardless of completion.
        boolean finishedInTime = slow.join(Duration.ofMillis(100));
        System.out.println("  after 100ms join: finished=" + finishedInTime + ", alive=" + slow.isAlive());

        // (b) unbounded join — blocks until termination.
        slow.join();
        System.out.println("  after final join: alive=" + slow.isAlive());
    }

    /*
    Cooperative cancellation (the interrupt protocol)

    - Java has no safe forced stop. Thread.stop() could leave shared state in an inconsistent, half-modified shape,
      so it was degraded to throw UnsupportedOperationException in Java 20 and removed from the API entirely in
      Java 26. There is no replacement — and that is deliberate.
    - Cancellation is cooperative: the requester sets the interrupt flag with t.interrupt(), and the worker is
      responsible for noticing it and exiting.
    - The worker checks Thread.currentThread().isInterrupted() in its main loop, and catches InterruptedException from
      blocking calls (sleep, wait, join, queue operations) — both are signals to wind down.
    - Thread.interrupted() is a static method that clears the flag while isInterrupted() does not. Use interrupted()
      only when you intend to handle the cancellation in the current method and not let it propagate.
    - A robust cancellable worker also runs cleanup in finally.
    */
    static void cooperativeCancellation() throws InterruptedException {
        System.out.println("[Section 8] cooperative cancellation");

        var worker = new Thread(new CancellableReportJob(), "report-worker");
        worker.start();

        Thread.sleep(800);     // let it produce a few fragments
        worker.interrupt();    // cancellation request
        worker.join();         // wait until the worker has cleaned up

        // Flag-based cancellation — the alternative when the worker never blocks and therefore
        // never reaches an interruption point. AtomicBoolean is used here rather than a plain
        // volatile field only because a local variable cannot be declared volatile; for this
        // one-way flag the two give exactly the same visibility guarantee (Mod002 §3).
        var stop = new AtomicBoolean(false);
        var spinner = Thread.ofPlatform().name("flag-worker").start(() -> {
            long ticks = 0;
            while (!stop.get()) ticks++;
            System.out.println("  flag-worker stopped after " + ticks + " ticks");
        });
        Thread.sleep(50);
        stop.set(true);
        spinner.join();
    }

    /*
    Shutdown hooks

    - A shutdown hook is a Thread registered with Runtime.getRuntime().addShutdownHook(...) that the JVM runs when the
      process is about to exit (normal exit, System.exit, or an external SIGTERM).
    - Hooks run concurrently in unspecified order. They must finish quickly — a hook that blocks indefinitely will
      block JVM exit.
    - Typical uses: flush logs, close database connections, write a final metric, send a "going away" message to a
      coordinator.
    - Hooks are not invoked on Runtime.halt() or a JVM crash, so do not rely on them for correctness-critical cleanup.
    */
    static void shutdownHook() {
        System.out.println("[Section 9] shutdown hook");

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> System.out.println("  shutdown hook fired (JVM exiting)"),
                "shutdown-hook"));

        System.out.println("  hook registered; it will fire when main() returns");
    }

    public static void main(String[] args) throws InterruptedException {
        creatingThreads();
        startVsRun();
        threadStates();
        daemonVsUser();
        sleepAndInterrupt();
        joiningThreads();
        cooperativeCancellation();
        shutdownHook();
        System.out.println("Mod001Threads finished");
    }
}
