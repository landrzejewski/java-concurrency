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