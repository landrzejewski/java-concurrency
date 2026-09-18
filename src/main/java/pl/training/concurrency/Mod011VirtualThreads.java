package pl.training.concurrency;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/*
The thread-per-request model and its limit

- The classic Java server design is one platform thread per in-flight request. The thread reads the request, calls a
  service, awaits the result, writes the response. Code is straightforward, but each thread carries a fixed-size
  stack (commonly 1 MB) and an OS-level thread structure.
- Result: a few thousand threads is fine; tens of thousands push memory and context-switch costs to the limit;
  hundreds of thousands is impossible.
- The traditional workaround was async / reactive: stop blocking, write callbacks, propagate CompletableFuture chains
  everywhere. The code becomes non-linear, debugging gets harder, and exception/cancellation propagation gets
  fragile.
- Virtual threads are the JDK's answer: keep the simple "thread-per-request" code, but make threads cheap enough to
  have hundreds of thousands of them.

Pinning

- A virtual thread is pinned to its carrier when it cannot be unmounted. The two classic reasons:
  1. The thread is inside a synchronized block (legacy native monitors).
  2. The thread is in a JNI call.
- While pinned, the carrier cannot be reused. Many pinned virtual threads exhaust the carrier pool and block
  everyone else.
- Java 24 (JEP 491) removes the synchronized-pinning case: the JVM now unmounts virtual threads from synchronized
  blocks. Modern code therefore mostly only has to worry about JNI pinning, which is rare. Older tutorials still
  recommend "replace synchronized with ReentrantLock" — that advice is no longer required on Java 24+.
- The old -Djdk.tracePinnedThreads system property was REMOVED together with the pinning it diagnosed (JEP 491,
  Java 24). Diagnose what is left with JFR instead — the jdk.VirtualThreadPinned event is enabled in the default
  profile and records a stack trace for every pinned interval:
      java -XX:StartFlightRecording=filename=vt.jfr ...
      jfr print --events jdk.VirtualThreadPinned vt.jfr
  The companion events jdk.VirtualThreadStart / jdk.VirtualThreadEnd / jdk.VirtualThreadSubmitFailed round out the
  picture.

When NOT to use virtual threads

- CPU-bound work. Virtual threads do not give you more CPUs; they give you more waiters. For a tight loop computing
  a hash or running ML inference, a fixed-size thread pool of size N-cores is the right shape.
- Tasks that must be pinned to a specific resource (e.g., a UI thread, an OS thread that owns a native handle).
  Virtual threads can move between carriers; explicit single-thread executors do not.
- Existing async/reactive pipelines that already work and are tuned. Virtual threads do not magically convert
  reactive code; mixing the two paradigms tends to confuse rather than help.
- Bottom line: virtual threads excel at I/O-bound workloads with high concurrency. They are not a universal speedup.

Migration recipe

1. Replace Executors.newCachedThreadPool() (and bounded thread pools used for I/O) with
   Executors.newVirtualThreadPerTaskExecutor().
2. Audit ThreadLocal usage; replace context-propagation use cases with ScopedValue (Mod013).
3. Audit synchronized blocks in hot paths. On Java <24 replace with ReentrantLock to avoid pinning. On Java 24+ this
   is no longer required.
4. Watch out for libraries with their own thread pools (DB drivers, HTTP clients): switching application code to
   virtual threads will not change their threading model.
5. Benchmark before claiming a win — virtual threads change scaling, not single-request latency.
*/

public final class Mod011VirtualThreads {

    private Mod011VirtualThreads() {}

    /*
    What is a virtual thread

    - A virtual thread is a Thread instance whose execution is multiplexed onto a small pool of platform threads
      called carrier threads. The JVM's scheduler mounts a virtual thread on a carrier when it is runnable, and
      unmounts it whenever it would block on I/O, Thread.sleep, locks, etc.
    - A virtual thread has a tiny initial stack that grows as needed. Millions of them fit comfortably in heap
      memory.
    - Identical API: Thread.ofVirtual().start(...), Thread.startVirtualThread(...), or
      Executors.newVirtualThreadPerTaskExecutor(). Anything that takes a Runnable works the same way — including
      Thread.sleep, synchronized, ReentrantLock, BlockingQueue, and so on.
    - Thread.currentThread().isVirtual() distinguishes them from platform threads.
    */
    static void singleVirtualThread() throws InterruptedException {
        System.out.println("[Section 2] single virtual thread");

        var t = Thread.ofVirtual().name("vt-greeter").start(() -> {
            System.out.println("  hello from " + Thread.currentThread()
                    + " (isVirtual=" + Thread.currentThread().isVirtual() + ")");
        });
        t.join();
    }

    /*
    Executors.newVirtualThreadPerTaskExecutor

    - The recommended factory for production code on Java 21+. It creates a fresh virtual thread per submitted task —
      no pooling, because virtual threads are cheap to create.
    - Compatible with try-with-resources (Java 19+). The executor's close() waits for in-flight tasks to finish,
      exactly like shutdown + awaitTermination.
    - Replace Executors.newCachedThreadPool() with this almost everywhere — the behavior is similar (no fixed limit),
      but each task gets its own dedicated context with no thread-pool worker reuse and therefore no ThreadLocal
      leakage between tasks.
    */
    static void virtualThreadExecutor() throws InterruptedException {
        System.out.println("[Section 3] virtual-thread executor — fan out 10000 tasks");

        var counter = new AtomicLong();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10_000; i++) {
                exec.execute(counter::incrementAndGet);
            }
        }
        System.out.println("  counter = " + counter.get());
    }

    /*
    Carrier threads and mounting

    - The carrier pool is a ForkJoinPool sized to the number of available CPUs. That is enough because virtual
      threads spend most of their time unmounted, parked while waiting for I/O.
    - Mounting: when a virtual thread becomes runnable, the scheduler picks an idle carrier and runs it.
    - Unmounting: when the virtual thread enters a JDK blocking call (InputStream.read, HttpClient.send,
      Thread.sleep, etc.), the JDK signals the scheduler, which unmounts the virtual thread and frees the carrier for
      someone else.
    - The whole thing is invisible to your code — you keep writing synchronous blocking style, and the JDK does the
      trampolining.
    */
    static void carrierThreadsAndMounting() throws InterruptedException {
        System.out.println("[Section 4] carrier threads + unmount-on-sleep");

        int n = 1000;
        var carrierNames = new java.util.concurrent.ConcurrentSkipListSet<String>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                exec.execute(() -> {
                    // There is no public API for "which carrier am I on". A virtual thread's toString
                    // happens to render as VirtualThread[#id]/state@carrierName, so we scrape the part
                    // after the last '@'. That format is UNDOCUMENTED and may change — this is fine for
                    // a classroom demo, never for production code. Use the JFR events instead.
                    carrierNames.add(Thread.currentThread().toString().replaceAll(".*@", ""));
                    try { Thread.sleep(10); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            }
        }
        System.out.println("  " + n + " virtual threads ran on " + carrierNames.size()
                + " distinct carrier threads (availableProcessors = "
                + Runtime.getRuntime().availableProcessors() + ")");
        System.out.println("  carriers: " + carrierNames);
    }

    /*
    ThreadLocal with virtual threads

    - ThreadLocal continues to work — each virtual thread has its own slot. But because there can be millions of
      virtual threads, careless use blows up memory.
    - Common anti-pattern: a per-thread caching ThreadLocal<StringBuilder> — saves allocation in the platform-thread
      world (a few hundred entries), but becomes a memory hog with virtual threads (millions of builders held alive
      during the request lifetime).
    - For propagating immutable per-request context (user, trace id, locale) prefer ScopedValue (Mod013). A binding
      allocates only a small immutable carrier for the duration of the scope — far less than a per-thread map entry
      that lives as long as the thread — it propagates automatically into structured concurrency scopes, and it
      cannot be mutated.
    - Keep ThreadLocal only when you genuinely need a mutable per-thread cache that is reset between requests, and
      benchmark the memory cost.
    */
    static void threadLocalWithVirtualThreads() throws InterruptedException {
        System.out.println("[Section 5] ThreadLocal with virtual threads");

        var requestId = new ThreadLocal<Long>();
        var seenIds = new java.util.concurrent.ConcurrentSkipListSet<Long>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long i = 0; i < 5; i++) {
                final long id = i;
                exec.execute(() -> {
                    requestId.set(id);
                    seenIds.add(requestId.get()); // independent slot per virtual thread
                });
            }
        }
        System.out.println("  per-thread ids seen = " + seenIds);
        System.out.println("  see Mod013 for ScopedValue — the modern alternative");
    }

    /*
    Performance demo (1 000 sleeping tasks)

    - We submit 1 000 tasks each of which sleeps for 50 ms (a stand-in for a network call). On a fixed pool of 16
      platform threads that takes 1000 * 50 ms / 16 ≈ 3.1 s, because only 16 sleeps can be in flight at once. On a
      virtual-thread executor all 1 000 sleeps overlap and the wall-clock time is roughly 50 ms.
    - Deliberately modest numbers: 10 000 tasks x 100 ms would make the fixed-pool leg alone run for over a minute,
      which is useless in a classroom. The ratio is what matters, and it scales the way you would expect — the
      platform-thread leg is linear in tasks/poolSize, the virtual-thread leg is flat.
    - This is a measurement, not a benchmark; JIT warm-up and OS noise move the numbers. The order of magnitude is
      the lesson.
    */
    static void performanceDemo() throws InterruptedException {
        System.out.println("[Section 6] performance demo");

        int tasks = 1_000;
        Duration sleep = Duration.ofMillis(50);

        // (a) Fixed platform-thread pool.
        long t0 = System.nanoTime();
        try (ExecutorService fixed = Executors.newFixedThreadPool(16)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < tasks; i++) {
                futures.add(fixed.submit(() -> {
                    try { Thread.sleep(sleep); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }));
            }
            for (var f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        }
        long fixedMs = (System.nanoTime() - t0) / 1_000_000;

        // (b) Virtual-thread-per-task executor.
        long t1 = System.nanoTime();
        try (ExecutorService virt = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                virt.execute(() -> {
                    try { Thread.sleep(sleep); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            }
        }
        long virtMs = (System.nanoTime() - t1) / 1_000_000;

        System.out.printf(Locale.ROOT, "  fixed(16)        : %,5d ms (≈ tasks * sleep / poolSize = %d ms)%n",
                fixedMs, tasks * sleep.toMillis() / 16);
        System.out.printf(Locale.ROOT, "  virtual-per-task : %,5d ms (≈ one sleep, all %,d overlapped)%n", virtMs, tasks);
        System.out.printf(Locale.ROOT, "  speedup ≈ %.0fx — and it grows linearly with the task count%n",
                (double) fixedMs / Math.max(virtMs, 1));
    }

    public static void main(String[] args) throws InterruptedException {
        long t0 = Instant.now().toEpochMilli();
        singleVirtualThread();
        virtualThreadExecutor();
        carrierThreadsAndMounting();
        threadLocalWithVirtualThreads();
        performanceDemo();
        System.out.println("Mod011VirtualThreads finished in "
                + (Instant.now().toEpochMilli() - t0) + " ms");
    }
}
