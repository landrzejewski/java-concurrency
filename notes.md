## Mod001 — Threads

### Exercise 1.1 — Thread builder and lifecycle logger
Write a program that creates three threads with the `Thread.ofPlatform()` builder and observes their life cycle.
- Give each thread a distinct name; make exactly one of them a daemon thread
- Create the threads with `unstarted(...)` and print `getState()` right after creation (expect `NEW`)
- Start them; each thread sleeps for 300 ms, so the main thread can print `TIMED_WAITING` while they sleep
- After `join()` print the final state (`TERMINATED`)
- Add a fourth thread on which you call `run()` instead of `start()`. Print `Thread.currentThread().getName()` from inside the task and explain the difference in the output
