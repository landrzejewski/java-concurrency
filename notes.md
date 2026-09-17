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
