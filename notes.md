# Concurrency

## Mod001 — Threads

### Exercise 1.1 — Thread builder and lifecycle logger
Write a program that creates three threads with the `Thread.ofPlatform()` builder and observes their life cycle.
- Give each thread a distinct name; make exactly one of them a daemon thread
- Create the threads with `unstarted(...)` and print `getState()` right after creation (expect `NEW`)
- Start them; each thread sleeps for 300 ms, so the main thread can print `TIMED_WAITING` while they sleep
- After `join()` print the final state (`TERMINATED`)
- Add a fourth thread on which you call `run()` instead of `start()`. Print `Thread.currentThread().getName()` from inside the task and explain the difference in the output

### Exercise 1.2 — Cooperative cancellation of a downloader
Implement a `Downloader` task that simulates downloading a file in chunks (loop, `Thread.sleep(50)` per chunk, print progress).
- The task must stop within 100 ms after `interrupt()` is called on its thread, regardless of which chunk it is processing
- On interruption the task must restore the interrupt flag and release its "resources" (print a cleanup message in a `finally` block)
- The main thread starts the downloader, waits 500 ms, interrupts it and joins; it must never wait for the whole file
- Second variant: replace the interrupt protocol with an `AtomicBoolean stopRequested` flag and a `stop()` method. Explain what breaks in this variant when the task is blocked in `sleep`

### Exercise 1.3 — Waiting for workers with join and a shutdown hook
Start `N` worker threads with random durations (100–2000 ms) and wait for them with a global deadline.
- Use `join(Duration)` (Java 19+) so the main thread waits at most 1 second in total for all workers (compute the remaining time for each join)
- Print which workers finished in time and which are still running when the deadline passes
- Register a JVM shutdown hook that prints the names of all worker threads that are still alive at shutdown
- Make the stragglers daemon threads so the JVM can exit; then make them user threads and explain what changes

---

## Mod002 — Memory Model and Atomics

### Exercise 2.1 — Visibility bug hunt
You are given a `Worker` with a plain `boolean running = true` field, a `run()` loop `while (running) { count++; }` and a `stop()` method that sets `running = false`.
- Write a test that starts the worker, sleeps 200 ms, calls `stop()` and joins with a 2-second timeout; report whether the worker stopped
- Fix the visibility problem with `volatile` and show that the worker now always stops
- Show that `count++` is still not atomic even with `volatile int count`: run 8 threads incrementing it 100 000 times each and print the lost updates
- Explain (in a comment) why replacing `volatile` with a `synchronized` getter/setter would also fix visibility, and which of the two fixes would additionally fix `count++`

### Exercise 2.2 — Lock-free statistics
Implement a thread-safe `Stats` class with `record(int value)` and `count()`, `sum()`, `min()`, `max()` — without using `synchronized` or any `Lock`.
- Use `AtomicLong`/`AtomicInteger` for `count` and `sum`
- Implement `min` and `max` with a hand-written CAS loop (`get` → compute candidate → `compareAndSet`, retry on failure); count the number of CAS retries in a separate counter
- Then reimplement `min`/`max` with `accumulateAndGet(value, Math::min)` and compare the code
- Verify with 8 threads × 100 000 random values that `count`, `sum`, `min`, `max` equal the values computed sequentially from the same input

### Exercise 2.3 — Immutable config snapshot
Implement `ConfigHolder` that publishes an immutable `Config` record (`String host`, `int port`, `List<String> features`) to many reader threads.
- Store the current config in an `AtomicReference<Config>`
- Provide `update(UnaryOperator<Config> change)` that applies the change atomically even when several threads update concurrently (no lost updates)
- Provide `current()` for readers; readers must never observe a partially constructed or "torn" config
- Run 4 updater threads (each adds its own feature 1 000 times, e.g. `"f1-<i>"`) and 4 reader threads; at the end verify that all 4 000 features are present
- In a comment explain which JMM rule guarantees that a reader sees a fully constructed `Config` (final-field freeze) and which rule guarantees it sees the *latest* reference

---

## Mod003 — Intrinsic Locks

### Exercise 3.1 — Bank transfers with fine-grained locks
Implement `Account` (`id`, `balance`) and `Bank.transfer(Account from, Account to, long amount)` guarded by intrinsic locks.
- Each account has its own private final lock object; do not lock on the `Account` instance itself and do not use one global lock
- `transfer` must lock both accounts; order the lock acquisition by account `id` so that two opposite transfers cannot deadlock
- Run 10 threads doing 10 000 random transfers between 5 accounts; the total sum of all balances must be unchanged at the end
- Remove the lock ordering and demonstrate the deadlock (use a thread dump or `ThreadMXBean.findDeadlockedThreads()` to detect it)

### Exercise 3.2 — Reentrancy and the private-lock idiom
Write a `Counters` class with `synchronized void increment(String name)` and `synchronized void incrementAll()` that calls `increment` for every known name.
- Demonstrate that `incrementAll` does not deadlock on its own monitor (reentrancy) and print `Thread.holdsLock(this)` from both methods
- Write a "hostile" client that does `synchronized (counters) { Thread.sleep(5000); }` and show that all other threads are blocked for 5 seconds
- Rewrite `Counters` to use a private final lock object instead of `this` and show that the hostile client can no longer block the object
- Explain in a comment why exposing the lock (public monitor) is a design problem

### Exercise 3.3 — One-slot mailbox with wait/notify
Implement `Mailbox<T>` with capacity 1 and blocking `put(T)` / `take()` using only `synchronized`, `wait()` and `notifyAll()`.
- `put` blocks while the slot is full, `take` blocks while it is empty; both use a `while` loop around `wait()`
- Add `Optional<T> take(long timeoutMillis)` that returns `Optional.empty()` when nothing arrives in time (account for spurious wakeups — recompute the remaining time)
- Run 2 producers and 3 consumers exchanging 1 000 messages; every message must be received exactly once
- Change `while` to `if` in one of the methods and produce a run (or a reasoning in a comment) where an invariant is broken

---

## Mod004 — Explicit Locks

### Exercise 4.1 — Read-mostly cache with ReentrantReadWriteLock
Implement `Cache<K, V>` with `get(K)`, `put(K, V)` and `getOrLoad(K, Function<K, V> loader)` on top of a `HashMap` guarded by a `ReentrantReadWriteLock`.
- `get` takes only the read lock; `put` takes the write lock; always use the `lock(); try { … } finally { unlock(); }` shape
- `getOrLoad`: take the read lock; on a miss release it, take the write lock, re-check, load and insert, then **downgrade** to the read lock before releasing the write lock, and return while holding the read lock
- Explain in a comment why upgrading (read → write while holding the read lock) is not possible with this lock
- Measure throughput with 8 readers and 1 writer and compare with a version that uses a plain `ReentrantLock` for everything

### Exercise 4.2 — Deadlock-free resource pair with tryLock
Two workers each need two `ReentrantLock`s (`A` and `B`) but acquire them in opposite order.
- First show the deadlock with plain `lock()`
- Fix it with `tryLock(timeout)`: if the second lock cannot be acquired, release the first, back off for a random short time and retry; count the retries and print them
- Second variant: use `lockInterruptibly()` and a supervisor thread that detects that a worker has been waiting longer than 1 second and interrupts it; the worker must release everything it holds and report that it was cancelled
- Compare in a comment with what happens in the same scenario using `synchronized` (Mod003 §7)

### Exercise 4.3 — Turnstile with two Conditions
Implement `Turnstile` that allows at most `N` threads inside a section at the same time, using one `ReentrantLock` and two `Condition`s.
- `enter()` blocks while `N` threads are inside (`notFull` condition); `leave()` decrements the counter and signals
- `awaitEmpty()` blocks until nobody is inside (`empty` condition) — used by a "maintenance" thread
- Use `signal()` instead of `signalAll()` where it is safe and explain why in a comment
- Bonus: expose `currentCount()` implemented with a `StampedLock` optimistic read (`tryOptimisticRead` → copy → `validate` → fallback to `readLock`)

---

## Mod005 — Concurrent Collections

### Exercise 5.1 — Word frequency with ConcurrentHashMap
Count word frequencies of a large text split across 8 threads.
- First implement it with `Collections.synchronizedMap(new HashMap<>())` and a `get` + `put` sequence; show that the totals are wrong
- Reimplement with `ConcurrentHashMap` and `merge(word, 1, Integer::sum)`; the total count must equal the number of words
- Print the top 10 words using `ConcurrentHashMap` bulk operations (`reduceEntries` / `forEachEntry` with a parallelism threshold) instead of copying the map
- Show what happens when you try to store a `null` key or value in `ConcurrentHashMap`

### Exercise 5.2 — Event bus with CopyOnWriteArrayList
Implement `EventBus` with `subscribe(Listener)`, `unsubscribe(Listener)` and `publish(Event)`.
- A listener must be able to unsubscribe itself (and subscribe a new listener) from inside its own `onEvent` callback while `publish` is iterating
- Implement the listener list first with `ArrayList` + `synchronized` and demonstrate the `ConcurrentModificationException`
- Then switch to `CopyOnWriteArrayList` and show that iteration works on a snapshot (a listener subscribed during `publish` does not receive the current event)
- Run 1 publisher thread and 4 threads that constantly subscribe/unsubscribe; no exception may occur

### Exercise 5.3 — Three-stage pipeline with the BlockingQueue family
Build a pipeline: `Reader` → `Parser` → `Writer`, where stages are threads connected by queues.
- Reader → Parser: `ArrayBlockingQueue` of capacity 10; the reader uses `offer(item, 100 ms)` and counts how many times it had to wait (back-pressure report)
- Parser → Writer: `PriorityBlockingQueue` so that messages marked `urgent` are written before others
- Shut the pipeline down with a poison-pill message that is propagated through every stage; all threads must terminate and no message may be lost
- Explain in a comment which of the three `BlockingQueue` method styles (throw / special value / block) each stage uses and why

---

## Mod006 — Synchronization Utilities

### Exercise 6.1 — Connection pool with Semaphore
Implement `ConnectionPool` with at most `N` open `Connection` objects (fake objects with an `id`).
- `Optional<Connection> acquire(Duration timeout)` uses `tryAcquire(timeout)` and returns `Optional.empty()` on timeout
- `release(Connection)` returns the connection to the pool; it must work when called from a thread different from the one that acquired it
- Keep the free connections in a thread-safe collection; the semaphore only counts permits
- Run 20 workers competing for 3 connections and print how many acquisitions succeeded, timed out, and the maximum number of connections in use at any moment (must never exceed `N`)

### Exercise 6.2 — Parallel benchmark harness with CountDownLatch
Write `runConcurrently(int threads, Runnable task)` that starts all threads at exactly the same moment and measures the wall time of the whole batch.
- Use a start-gun `CountDownLatch(1)` so that every thread is created and ready before any of them starts the task
- Use a finish `CountDownLatch(threads)` and `await(timeout)`; if the batch does not finish within the timeout, report which threads are still running
- Return the elapsed time and use the harness to compare a `synchronized` counter with an `AtomicLong` counter under 16 threads
- Explain in a comment why a latch cannot be reused for a second batch and what you would use instead

### Exercise 6.3 — Iterative simulation with CyclicBarrier and Phaser
Simulate `R` rounds of a computation over an `int[]` array split into `K` slices, one thread per slice; each round every cell becomes the average of itself and its neighbours (read the previous round, write the next one).
- Use a `CyclicBarrier(K, barrierAction)` where the barrier action swaps the buffers and prints the round number and the checksum of the array
- Handle `BrokenBarrierException`: make one worker throw in round 3 and show how the others react
- Phaser variant: a worker whose slice becomes "stable" (no change in a round) calls `arriveAndDeregister()` and leaves; the simulation must continue with the remaining parties and terminate via `onAdvance` when `R` rounds are done
- Bonus: replace the two buffers with a single `Exchanger<int[]>` between a producer and a consumer thread (double buffering)

---

## Mod007 — Executors

### Exercise 7.1 — Image resizer on a fixed thread pool
Simulate resizing 20 images (`Callable<Result>` sleeping 200–3000 ms; every fifth image throws `IOException`).
- Use `Executors.newFixedThreadPool(4)` in a try-with-resources block
- Submit all tasks, keep the `Future`s, then collect results: handle `ExecutionException` (print the cause) and cancel with `cancel(true)` any task that has not finished within 2 seconds of its submission
- Print a summary: succeeded / failed / cancelled
- Submit one throwing task with `execute` instead of `submit` and observe where the exception goes; explain in a comment

### Exercise 7.2 — Fastest mirror and completion order
You have three "mirrors" (`Callable<String>`) with random latency; some of them fail randomly.
- Use `invokeAny` to fetch a resource from the first mirror that succeeds; handle the case when all of them fail
- Use `invokeAll` with a timeout of 1 second and print which calls were cancelled
- Use `ExecutorCompletionService` to download 10 resources and print the results in the order they complete (not the order they were submitted)
- Measure and compare the total time of the `invokeAll` approach and the `CompletionService` approach when the consumer does 100 ms of work per result

### Exercise 7.3 — Custom ThreadPoolExecutor with back-pressure and graceful shutdown
Build a `ThreadPoolExecutor` by hand and observe its behaviour.
- core 2, max 4, `ArrayBlockingQueue(10)`, a `ThreadFactory` naming threads `worker-N`, and `CallerRunsPolicy`
- Submit 30 tasks of 300 ms each and log for each task which thread ran it; explain in a comment when the 3rd and 4th worker were created and when the caller ran a task itself
- Replace the handler with `AbortPolicy` and count the `RejectedExecutionException`s
- Shut down gracefully: `shutdown()` → `awaitTermination(2 s)` → `shutdownNow()`; print how many tasks were never started
- Add a `ScheduledExecutorService` heartbeat with `scheduleAtFixedRate` every 500 ms; make the 3rd run throw, observe that the heartbeat stops, then fix it so a failing run does not kill the schedule

---

## Mod008 — Fork/Join

### Exercise 8.1 — Parallel max with RecursiveTask
Find the maximum of a `long[]` of 50 000 000 random elements with the Fork/Join framework.
- Implement `MaxTask extends RecursiveTask<Long>` with a threshold; below it compute sequentially, above it split in half using `left.fork(); long r = right.compute(); long l = left.join();`
- Compare the time with a plain sequential loop and with `Arrays.stream(array).parallel().max()` (warm up the JIT first)
- Print `ForkJoinPool.commonPool().getParallelism()` and explain in a comment which threads (including the caller) take part in the computation

### Exercise 8.2 — In-place normalization with RecursiveAction
Normalize a `double[]` in place (divide each element by the global maximum) using `RecursiveAction`.
- Step 1 (max) may reuse Exercise 8.1; step 2 updates the array in place using `invokeAll(left, right)`
- Explain in a comment why the two tasks do not need any synchronization even though they write to the same array
- Sweep the threshold (100, 1 000, 10 000, 100 000, 1 000 000) and print the time for each; explain the shape of the results

### Exercise 8.3 — Directory size on a dedicated pool
Compute the total size in bytes of a directory tree using `RecursiveTask<Long>`.
- Fork one subtask per subdirectory and sum the file sizes of the current directory in the same task
- Run the task on a dedicated `new ForkJoinPool(n)` instead of the common pool and explain in a comment why blocking I/O should not run on the common pool
- Handle unreadable directories (`AccessDeniedException`) without failing the whole computation
- Compare the result and the time with `Files.walk(...).mapToLong(...).sum()`

---

## Mod009 — CompletableFuture

### Exercise 9.1 — Order pipeline with thenCompose and thenCombine
Build an asynchronous "place order" pipeline over four fake services: `findUser(id)`, `loadCart(user)`, `price(cart)` and `loadShippingQuote(user)`, each of which sleeps 40–120 ms and returns a value.
- Chain `findUser` → `loadCart` → `price` with `thenCompose`; first write the same chain with `thenApply` and show that the result type collapses into `CompletableFuture<CompletableFuture<…>>` and needs two `join()`s
- Run `loadShippingQuote` in parallel with the cart branch and merge both with `thenCombine` into an `Order` record
- Add a fourth independent call and wait for everything with `allOf(...)`, reading each value afterwards with `f.join()`
- Measure the wall time of the whole pipeline and show that it is ≈ the longest *path* through the graph, not the sum of all four calls
- Explain in a comment why `thenApply` is the wrong operator when the function itself returns a future

### Exercise 9.2 — Resilient service client
Wrap a flaky `Callable<String> remoteCall()` (fails on the first two invocations, then succeeds; latency 20–300 ms) in a client that never lets a failure escape.
- Recover with `exceptionally`, then with `handle`, then attach a `whenComplete` listener; show that `whenComplete` cannot change the value and that the exception still propagates past it
- Print what `get()` throws versus what `join()` throws for the same failed future and name both wrapper types
- Add `orTimeout(100 ms)` to one variant and `completeOnTimeout("cached", 100 ms)` to another; show that in **both** cases the slow task keeps running (print a line from inside the task after the future has already completed)
- Implement `retry(Supplier<CompletableFuture<String>> op, int attempts, Duration delay)` with exponential backoff on a `ScheduledExecutorService`, without blocking any thread while waiting between attempts
- Explain in a comment why a timeout on a `CompletableFuture` completes the *future* and not the *work*, and what it takes to cancel the work (Mod012 §7)

### Exercise 9.3 — Dashboard fan-out and the cancellation gap
Fetch `profile`, `orders` and `recommendations` concurrently and render a dashboard string.
- Run the fan-out first on the common pool, then on `Executors.newVirtualThreadPerTaskExecutor()` passed explicitly to every `supplyAsync`/`thenApplyAsync`; explain in a comment why blocking I/O must not go to the common pool
- Make one branch fail and show that the other two still run to completion and still consume their threads (count the finished branches)
- Take a started branch — use a `CountDownLatch` so it has provably begun — call `cancel(true)` on it, and show that it is **not** interrupted and that its siblings are unaffected
- Print the wall time and compare it with the sum of the branch latencies
- **Bonus:** sketch in a comment how the same three points look with `StructuredTaskScope` (Mod012 §3, §8)

---

## Mod010 — Parallel Streams

### Exercise 10.1 — Break-even sweep: when parallel actually pays
Find, by measurement, where `parallelStream()` starts to win on your machine.
- Generate `List<LogEntry>` records (`level`, `service`, `latencyMs`) and run the same pipeline shape sequentially and in parallel for a cheap per-element operation (sum one field) and for a costly one (≈40 `Math.log1p`/`Math.sqrt` operations per element)
- Sweep the input size (1 000, 10 000, 100 000, 1 000 000) for both costs and print a table; warm the JIT up with at least three untimed rounds before every measurement
- Repeat the cheap case over `IntStream.range(...)`, over a boxed `List<Integer>` and over a `LinkedList<Integer>`; explain in a comment what the spliterator can do in each case and why the `LinkedList` is the worst source
- Print `ForkJoinPool.commonPool().getParallelism()` and `Runtime.getRuntime().availableProcessors()`, then rerun the costly case inside `new ForkJoinPool(2).submit(...).get()` and compare
- State in a comment why a single timed run without warm-up systematically flatters whichever variant runs second

### Exercise 10.2 — Correctness traps: stateful lambdas, reduce and encounter order
Reproduce and then fix the three classic ways a parallel stream produces a wrong answer.
- Fill an `ArrayList` from `forEach` on a parallel stream of 100 000 elements; run it 10 times and report how many runs lost elements or threw; then fix it with `toList()` and `collect(Collectors.toList())` and explain in a comment the difference between the two results
- Call `reduce(0, Integer::sum, (a, b) -> a * b)` sequentially and in parallel over the same list and show that only the parallel result changes; explain which clause of the `reduce` contract is violated
- Concatenate 100 000 strings with `reduce("", String::concat)` and with `Collectors.joining()`; both are correct — measure them and explain in a comment why the legal one is still the wrong tool
- Compare `findFirst` with `findAny` on a parallel stream, then `forEach` with `forEachOrdered`, then add `.unordered()` and show which guarantee you just waived
- Explain in a comment what "non-interfering" means for a stream lambda

### Exercise 10.3 — Grouping a log stream: groupingBy vs groupingByConcurrent
Group 1 000 000 log records by key and count them.
- Group with `Collectors.groupingBy(key, counting())` and with `groupingByConcurrent` on an `.unordered()` parallel stream; assert both produce identical maps
- Sweep the key cardinality (5 keys, 500 keys, 2 500 keys) and repeat with an expensive downstream collector (`toList()`); print a comparison table with a warm-up before every measurement
- Explain in a comment why the concurrent collector usually loses: one shared `ConcurrentHashMap` under contention versus a handful of cheap per-chunk `HashMap` merges
- Show what `groupingByConcurrent` needs from the stream (the `UNORDERED` characteristic) and what happens to the result ordering
- Run the winning version inside a dedicated `ForkJoinPool` and confirm from `Thread.currentThread().getName()` inside the classifier that no common-pool worker took part

---

## Mod011 — Virtual Threads

### Exercise 11.1 — The thread-per-request scaling wall
Submit `N` tasks that each sleep 50 ms (a stand-in for a network call) and compare three executors.
- Run `newFixedThreadPool(16)`, `newCachedThreadPool()` and `newVirtualThreadPerTaskExecutor()` for `N` = 100, 1 000 and 10 000; print a table of wall times
- Show that the fixed-pool time is ≈ `N × 50 ms / poolSize` while the virtual-thread time stays flat at ≈ 50 ms
- Try to start 100 000 platform threads that each sleep 1 s and report what happens (`OutOfMemoryError: unable to create native thread`, or the count at which your machine gives up); then do the same with virtual threads and print how many actually ran
- Use try-with-resources on every executor and explain in a comment what `close()` does and how it differs from `shutdownNow()`
- Repeat the 10 000-task run with a CPU-bound task (a tight hashing loop) instead of a sleep and explain in a comment why virtual threads no longer help

### Exercise 11.2 — Carriers, pinning and JFR diagnostics
Observe how virtual threads are multiplexed onto carrier threads.
- Start 1 000 virtual threads that each sleep 10 ms; collect the distinct carrier names and show that the count is ≈ `availableProcessors()`
- There is no public "which carrier am I on" API — scrape it from `Thread.currentThread().toString()` (`VirtualThread[#id]/state@carrier`) and add a comment stating that the format is undocumented and unfit for production
- Print `Thread.currentThread().isVirtual()` from inside both a virtual and a platform thread, and show that `Thread.ofVirtual().name(...)` names work in a thread dump
- Record the run with `-XX:StartFlightRecording=filename=vt.jfr` and read the pinning events with `jfr print --events jdk.VirtualThreadPinned vt.jfr`; put the two commands in a comment
- Explain in a comment why the old `-Djdk.tracePinnedThreads` property is gone and what JEP 491 (Java 24) changed about `synchronized` pinning

### Exercise 11.3 — Migrating a service, and where virtual threads do not help
Take a small "order service" built on `Executors.newCachedThreadPool()` and migrate it.
- Swap the executor for `newVirtualThreadPerTaskExecutor()` and show that no other line of the request-handling code changes
- Audit the `ThreadLocal<SimpleDateFormat>`-style cache in the service: run 100 000 requests and compare retained size (or at least allocation count) before and after; explain in a comment why a per-thread cache that was harmless with 200 platform threads is not harmless with 100 000 virtual ones
- Replace the read-only part of that context with a `ScopedValue` (Mod013) and keep only the genuinely mutable buffer in a `ThreadLocal`
- Add a CPU-bound report generator to the same service and keep it on a `newFixedThreadPool(availableProcessors())`; explain in a comment why mixing the two executors is the right answer
- Write the migration checklist you actually used as a numbered comment at the top of the file

---

## Mod012 — Structured Concurrency

### Exercise 12.1 — Dashboard with StructuredTaskScope
Rebuild Exercise 9.3 with `StructuredTaskScope` (compile and run with `--enable-preview`).
- Use the no-argument `StructuredTaskScope.open()` for the heterogeneous fan-out (`String`, `List<String>`, `List<String>`) and read each value from its `Subtask` after `join()`
- Show that reading `subtask.get()` before `join()` returns throws, and explain in a comment why the API forbids it
- Add a homogeneous variant with `Joiner.allSuccessfulOrThrow()` over five "shards" and show that `join()` returns the values in **fork** order, not completion order
- Make one branch fail after 40 ms while another would take 500 ms; catch `FailedException`, print the cause, and show from the wall time that the slow sibling was cancelled rather than awaited
- Have the slow branch print when it receives its `InterruptedException`, and explain in a comment what a purely CPU-bound branch would have to do instead

### Exercise 12.2 — Races, partial results and a scope deadline
Query several redundant mirrors under a deadline.
- Use `Joiner.anySuccessfulOrThrow()` over one slow primary and two fast mirrors; print the winner, the losing subtask's `state()` and `scope.isCancelled()`
- Make every mirror fail and show what `join()` throws and which failure ends up as the cause
- Fan out to 10 mirrors with `Joiner.allUntil(predicate)` that stops after the first 3 successes; from the returned `List<Subtask<T>>` print the successful values and the count of `UNAVAILABLE` ones, and explain in a comment why the predicate's state must be thread-safe
- Configure a scope with `withName("dashboard")` and `withTimeout(Duration.ofMillis(100))` around a 5-second subtask; catch `TimeoutException` and show from the wall time that the deadline bounded the whole fan-out
- Explain in a comment why a scope deadline is strictly better than a per-future `orTimeout` (Mod009 §6)

### Exercise 12.3 — Custom quorum Joiner
Implement `QuorumJoiner<T> implements Joiner<T, List<T>>` that returns as soon as `q` sub-tasks have succeeded, and fails if more than `n - q` have failed.
- Implement `onComplete(Subtask<T>)` (note the Java 26 invariant `Subtask<T>` signature) so that it short-circuits on the `q`-th success and also short-circuits once success has become arithmetically impossible
- Implement `result()` to return the successful values, and `onTimeout()` so that a timed-out scope yields the partial quorum instead of throwing
- Remember that `onComplete` runs on the completing sub-task's thread and may be called concurrently, while `onFork` and `onTimeout` run on the owner thread — keep every field thread-safe and say so in a comment
- Exercise it with 7 replicas (`q = 4`), some slow, some failing; print the values, the number of cancelled replicas and the wall time, and check that the wall time is the 4th-fastest latency
- Compare in a comment with `allUntil(predicate)` from Exercise 12.2 and say when a custom joiner is worth the code

---

## Mod013 — Scoped Values

### Exercise 13.1 — Request context without parameter passing
Propagate `currentUser`, `tenantId` and `traceId` through a `controller → service → repository` chain without adding a single parameter.
- Declare three `static final ScopedValue<String>` keys and bind all three with a chained `where(...).where(...).where(...)`
- Read them from the repository, three call levels down, and print them
- Show that `get()` outside the binding throws `NoSuchElementException`, then demonstrate `isBound()`, `orElse("anonymous")` and `orElseThrow(MyException::new)`
- Use the value-returning `call(...)` form for a method that throws a checked exception and show that the exception is not wrapped
- Explain in a comment why a `ScopedValue` is easier to audit than a `ThreadLocal` holding the same data

### Exercise 13.2 — Rebinding and inheritance into a scope
Show how bindings nest and how they cross into sub-tasks.
- Bind `CURRENT_USER` to `"alice"`, then run an audit-log call inside a nested `where(CURRENT_USER, "system")` binding; print the value before, inside and after and confirm the outer value is restored
- Inside a `TRACE_ID` binding, open a `StructuredTaskScope` and fork three sub-tasks; each prints the inherited trace id and its own thread — no setter, no parameter passing
- Rebind `TRACE_ID` inside one sub-task and show that the change is invisible to its siblings and to the parent
- Build the same propagation with an `InheritableThreadLocal`, then have the parent change its value **after** forking, and show that the children keep the stale copy
- Explain in a comment the structural difference: `InheritableThreadLocal` copies the parent map into every child, a scoped-value binding is one immutable node shared by parent and children

### Exercise 13.3 — Migrating a ThreadLocal filter chain
You are given a servlet-style filter chain that does `USER.set(request.user())` at the top and `USER.remove()` in a `finally` block.
- Reproduce the leak: drop the `remove()`, run the chain on a **pooled** platform-thread executor and show that the next request on the same worker sees the previous user's value
- Migrate the chain to `ScopedValue.where(USER, request.user()).run(...)` and show that the same test can no longer leak, with no `try`/`finally` anywhere
- On `newVirtualThreadPerTaskExecutor()`, show the deterministic difference in **lifetime**: after the request body returns, `USER.get()` still answers until someone calls `remove()`, while `USER.isBound()` is already `false` once `run()` has returned. Explain in a comment why this matters more once the thread *is* the request (Mod011 §5), and why comparing `totalMemory() - freeMemory()` between the two versions is not evidence
- Keep one genuinely mutable per-request value (a reusable `StringBuilder`) in a `ThreadLocal` and explain in a comment why `ScopedValue` cannot replace it
- Write the before/after code pair as a comment and list the three properties the migrated version gains (exception safety, immutability, no cross-request leakage)

---

## Mod014 — Threading Problems

### Exercise 14.1 — Deadlock: reproduce, detect, prevent
Take two locks in opposite order from two threads and work through the whole life cycle of the bug.
- Reproduce the deadlock with `synchronized`; make both threads **daemon** so `main` can keep running, and print each thread's `getState()` (expect `BLOCKED`)
- Detect it from inside the JVM with `ManagementFactory.getThreadMXBean().findDeadlockedThreads()` and print the participating thread names and the monitors they wait on
- Print the PID and, in a comment, the `jstack <pid>` / `kill -3 <pid>` commands and the exact line `jstack` prints for a cycle
- Fix it with the lock-ordering rule: sort the two locks by `System.identityHashCode` before acquiring, with a tie-breaker mutex for the (rare) hash collision; run 10 000 opposite-direction transfers and show that none deadlock
- Explain in a comment why a thread blocked on entering a `synchronized` block cannot be interrupted out of it, and what `tryLock(timeout)` (Mod004 §5) buys you instead

### Exercise 14.2 — Livelock and starvation under measurement
Turn two qualitative problems into numbers.
- Implement the two-diners-one-spoon livelock: both diners always defer to a still-hungry partner. Time-box the run to 300 ms and report meals eaten and courtesy hand-offs performed — expect 0 meals and a very large hand-off count
- Print the thread states during the livelock and explain in a comment why `RUNNABLE` makes livelock harder to spot than deadlock
- Break the symmetry (defer only on a coin flip) and show that both diners now eat almost immediately; explain in a comment why randomised jitter is also the cure for two workers retrying a `tryLock` pair after the same back-off
- Measure starvation: six threads contend for a `ReentrantLock` for a fixed 300 ms window, once unfair and once fair. Print total acquisitions, min, max and the max/min ratio per worker
- Explain in a comment why a fixed iteration count per worker instead of a fixed time window would hide the effect entirely, and state the trade-off fairness buys

### Exercise 14.3 — Race-condition checklist and testing concurrent code
Work through the four recurring race shapes and then try to actually catch one.
- For each shape — read-modify-write, check-then-act, get-then-put on a counter, compound state across two fields — write a broken version, run it with 8 threads until it visibly breaks, and print the size of the error
- Fix each one with the idiomatic tool: `AtomicLong`, `ConcurrentHashMap.putIfAbsent`/`computeIfAbsent`, `merge(k, 1L, Long::sum)` and a single lock around the whole compound update
- Write a plain JUnit test for the read-modify-write case and explain in a comment why it can observe a lost update but can never prove that the fixed version is race-free
- Write a jcstress test for the same counter, modelled on `src/jcstress/java/pl/training/concurrency/CounterTest.java`, and run it with `./mvnw -P jcstress package` followed by the `java -jar target/jcstress.jar -t …` command; put both commands in a comment
- Explain in a comment why the JVM offers no priority inheritance and why thread priorities must never carry correctness
