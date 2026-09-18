package pl.training.concurrency.exercises.mod011;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Exercise 11.1 — The thread-per-request scaling wall.
 *
 * <p>Related: Mod011 §3, §6
 *
 * <p>Timings are order-of-magnitude illustrations, not benchmarks; JIT warm-up and OS noise move them.
 */
public final class Ex111ScalingWall {

    private Ex111ScalingWall() {}

    /**
     * The exercise says 50 ms. Lowered to 10 ms so the fixed(16) leg of the 10 000-task row takes ~6 s
     * instead of ~31 s — the RATIO between the executors is the lesson, and it is unchanged.
     */
    private static final Duration IO_LATENCY = Duration.ofMillis(10);
    private static final int POOL_SIZE = 16;
    private static final int[] TASK_COUNTS = { 100, 1_000, 10_000 };

    public static void main(String[] args) throws InterruptedException {

        System.out.println("[1] N blocking tasks x " + IO_LATENCY.toMillis() + " ms on three executors");
        System.out.printf(Locale.ROOT, "  %-8s %14s %14s %14s %16s%n",
                "tasks", "fixed(16)", "cached", "virtual/task", "fixed predicted");
        for (int tasks : TASK_COUNTS) {
            String fixed   = timeBlockingBatch(() -> Executors.newFixedThreadPool(POOL_SIZE), tasks);
            String cached  = timeBlockingBatch(Executors::newCachedThreadPool, tasks);
            String virtual = timeBlockingBatch(Executors::newVirtualThreadPerTaskExecutor, tasks);
            long predicted = (long) Math.ceil((double) tasks / POOL_SIZE) * IO_LATENCY.toMillis();
            System.out.printf(Locale.ROOT, "  %,-8d %14s %14s %14s %13d ms%n",
                    tasks, fixed, cached, virtual, predicted);
        }
        System.out.println("  → fixed(16) tracks tasks x latency / poolSize; the virtual column stays flat at");
        System.out.println("    ≈ one latency because all N sleeps overlap. cached() keeps up only by creating");
        System.out.println("    N real OS threads, which is exactly the wall probed in [2]");

        System.out.println("[2] how many threads can you actually create?");
        System.out.println("  platform: " + probePlatformThreads(20_000));
        System.out.println("  virtual : " + probeVirtualThreads(100_000));

        System.out.println("[3] close() vs shutdownNow()");
        var completed = new AtomicInteger();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                exec.execute(() -> { sleep(100); completed.incrementAndGet(); });
            }
            // close() is shutdown() + awaitTermination(forever): it stops accepting new tasks and BLOCKS here
            // until every submitted task has finished. Only if the calling thread is interrupted while waiting
            // does it escalate to shutdownNow(). It is the graceful option, and it is why try-with-resources
            // on an ExecutorService (Java 19+) is safe: no task is ever dropped on the floor.
        }
        System.out.println("  after close():       completed = " + completed.get() + "/100 (close waited for all)");

        var interrupted = new AtomicInteger();
        ExecutorService abrupt = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < 100; i++) {
            abrupt.execute(() -> {
                try { Thread.sleep(2_000); }
                catch (InterruptedException e) { interrupted.incrementAndGet(); }
            });
        }
        Thread.sleep(100);
        List<Runnable> neverStarted = abrupt.shutdownNow(); // interrupts the running ones, returns the queued ones
        abrupt.awaitTermination(2, TimeUnit.SECONDS);
        System.out.println("  after shutdownNow(): interrupted = " + interrupted.get()
                + ", returned as never-started = " + neverStarted.size());
        System.out.println("  → a virtual-thread executor has no queue: every task already had its own thread,");
        System.out.println("    so shutdownNow() interrupts all of them and returns an empty list");

        System.out.println("[4] the same fan-out, CPU-bound instead of blocking");
        // 64 long tasks, not 10 000 short ones. With short tasks the fixed pool's single shared queue becomes
        // the bottleneck and the virtual executor "wins" for a reason that has nothing to do with CPU work —
        // which would answer a different question than the one being asked here.
        int cpuTasks = 64;
        Supplier<ExecutorService> fixedFactory =
                () -> Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        // Warm up BOTH legs first. Without this the executor that runs first pays for compiling hash() and
        // looks several times slower — the exact mistake this module warns about in Mod010 §1.
        for (int i = 0; i < 2; i++) {
            timeCpuBatch(fixedFactory, cpuTasks);
            timeCpuBatch(Executors::newVirtualThreadPerTaskExecutor, cpuTasks);
        }
        long fixedCpu   = timeCpuBatch(fixedFactory, cpuTasks);
        long virtualCpu = timeCpuBatch(Executors::newVirtualThreadPerTaskExecutor, cpuTasks);
        System.out.printf(Locale.ROOT, "  %,d hashing tasks: fixed(processors) %,d ms | virtual-per-task %,d ms%n",
                cpuTasks, fixedCpu, virtualCpu);
        System.out.println("  → a wash at best. Virtual threads do not give you more CPUs, they give you more");
        System.out.println("    WAITERS: the win comes from threads spending their time UNMOUNTED, blocked on I/O.");
        System.out.println("    A task that never blocks never unmounts, so the carrier pool — already sized to");
        System.out.println("    the core count — does exactly the same work, plus mount/unmount bookkeeping.");

        System.out.println("Ex111ScalingWall finished");
    }

    /** Submits {@code tasks} sleeping tasks to a fresh executor and returns the wall time, or the failure. */
    private static String timeBlockingBatch(Supplier<ExecutorService> factory, int tasks) {
        long t0 = System.nanoTime();
        try (ExecutorService exec = factory.get()) {
            for (int i = 0; i < tasks; i++) {
                exec.execute(() -> sleep(IO_LATENCY.toMillis()));
            }
        } catch (OutOfMemoryError e) {
            return "OOM"; // newCachedThreadPool needs one OS thread per concurrent task
        }
        return String.format(Locale.ROOT, "%,d ms", (System.nanoTime() - t0) / 1_000_000);
    }

    private static long timeCpuBatch(Supplier<ExecutorService> factory, int tasks) {
        var sink = new AtomicLong();
        long t0 = System.nanoTime();
        try (ExecutorService exec = factory.get()) {
            for (int i = 0; i < tasks; i++) {
                final int seed = i;
                exec.execute(() -> sink.addAndGet(hash(seed)));
            }
        }
        long millis = (System.nanoTime() - t0) / 1_000_000;
        if (sink.get() == 0) throw new AssertionError("keep the JIT from eliminating the work");
        return millis;
    }

    /** A tight, allocation-free hashing loop — genuinely CPU-bound, never blocks, never unmounts. */
    private static long hash(int seed) {
        long h = seed;
        for (int i = 0; i < 3_000_000; i++) {
            h = h * 6364136223846793005L + 1442695040888963407L;
            h ^= (h >>> 29);
        }
        return h | 1;
    }

    /**
     * The exercise says 100 000; capped at 20 000 so a machine that CAN create that many does not spend
     * minutes on it. Every thread here parks on the same latch, so they are all alive simultaneously.
     */
    private static String probePlatformThreads(int target) {
        var threads = new ArrayList<Thread>();
        var stop = new CountDownLatch(1);
        int created = 0;
        try {
            for (; created < target; created++) {
                threads.add(Thread.ofPlatform().daemon(true).start(() -> {
                    try { stop.await(); } catch (InterruptedException ignored) { }
                }));
            }
            return String.format(Locale.ROOT,
                    "%,d live threads, ~%,d MB of stacks reserved — no wall hit at this cap",
                    created, created * 1L /* ~1 MB default stack each */);
        } catch (OutOfMemoryError | InternalError e) {
            // Typically "java.lang.OutOfMemoryError: unable to create native thread". Each platform thread
            // reserves a stack (commonly 1 MB) plus an OS thread structure, so the ceiling is set by memory
            // and by the OS thread limit — not by anything the JVM could tune away.
            return String.format(Locale.ROOT, "gave up after %,d threads: %s: %s",
                    created, e.getClass().getSimpleName(), e.getMessage());
        } finally {
            stop.countDown();
            threads.forEach(Thread::interrupt);
        }
    }

    private static String probeVirtualThreads(int target) {
        var ran = new AtomicInteger();
        long t0 = System.nanoTime();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < target; i++) {
                exec.execute(() -> { sleep(1_000); ran.incrementAndGet(); });
            }
        }
        return String.format(Locale.ROOT,
                "%,d threads, each sleeping 1 s, all ran; total %,d ms — they live on the heap, not on stacks",
                ran.get(), (System.nanoTime() - t0) / 1_000_000);
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
