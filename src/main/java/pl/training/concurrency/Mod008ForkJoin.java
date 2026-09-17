package pl.training.concurrency;

import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RecursiveTask;

/*
The work-stealing model

- A ForkJoinPool keeps each worker thread's tasks in a private deque.
- Workers push new sub-tasks on top of their own deque (LIFO — best for cache locality) and pop from the top when
  they need work.
- When a worker's deque empties, it steals a task from the bottom of another worker's deque (FIFO — the oldest,
  biggest piece). The asymmetry keeps stealing cheap and avoids contention with the owner.
- For divide-and-conquer workloads (recursive splits, balanced trees, embarrassingly parallel data), work-stealing
  fills cores well even when split sizes are uneven.
*/

final class IncrementAction extends RecursiveAction {
    private final int[] data;
    private final int from, to, threshold;
    IncrementAction(int[] data, int from, int to, int threshold) {
        this.data = data; this.from = from; this.to = to; this.threshold = threshold;
    }
    @Override protected void compute() {
        if (to - from <= threshold) {
            for (int i = from; i < to; i++) data[i]++;
        } else {
            int mid = (from + to) >>> 1;
            invokeAll(
                    new IncrementAction(data, from, mid, threshold),
                    new IncrementAction(data, mid, to, threshold));
        }
    }
}

final class SumTask extends RecursiveTask<Long> {
    private final long[] data;
    private final int from, to, threshold;
    SumTask(long[] data, int from, int to, int threshold) {
        this.data = data; this.from = from; this.to = to; this.threshold = threshold;
    }
    @Override protected Long compute() {
        if (to - from <= threshold) {
            long s = 0;
            for (int i = from; i < to; i++) s += data[i];
            return s;
        }
        int mid = (from + to) >>> 1;
        var left = new SumTask(data, from, mid, threshold);
        var right = new SumTask(data, mid, to, threshold);
        left.fork();                       // schedule left asynchronously
        long rightResult = right.compute();// run right on this thread
        long leftResult = left.join();     // wait for left's result
        return leftResult + rightResult;
    }
}

public final class Mod008ForkJoin {

    private Mod008ForkJoin() {}

    /*
    ForkJoinPool and the common pool

    - The JVM exposes a singleton ForkJoinPool.commonPool() shared across the process. It is also the default backing
      pool for parallel streams (Mod010) and CompletableFuture async stages (Mod009).
    - Default size is Runtime.getRuntime().availableProcessors() - 1.
    - Sharing the common pool is fine for short, CPU-bound tasks. For long-running or blocking work, allocate a
      dedicated new ForkJoinPool(N) instead — long tasks on the common pool starve everyone else.
    */
    static void commonPool() {
        System.out.println("[Section 2] common pool");

        var pool = ForkJoinPool.commonPool();
        System.out.println("  parallelism = " + pool.getParallelism()
                + ", availableProcessors = " + Runtime.getRuntime().availableProcessors());
    }

    /*
    RecursiveAction (no result)

    - Subclass RecursiveAction and implement compute() to perform a side-effecting parallel decomposition (e.g., bulk
      update of an array).
    - The compute() method either does the work for a small enough chunk (the base case), or splits into sub-actions
      and forks them.
    - Submit with pool.invoke(action) (blocking) or pool.submit(action) (returns a ForkJoinTask).
    */
    static void recursiveAction() {
        System.out.println("[Section 3] RecursiveAction");

        int[] data = new int[1_000_000];
        ForkJoinPool.commonPool().invoke(new IncrementAction(data, 0, data.length, 10_000));
        System.out.println("  data[0]=" + data[0] + ", data[last]=" + data[data.length - 1]
                + " (each incremented exactly once)");
    }

    /*
    RecursiveTask<V> (with result)

    - Subclass RecursiveTask<V> for parallel reductions that compute and return a value.
    - Same divide-and-conquer skeleton, but compute() returns a V and the parent combines the children's values.
    - Use the canonical pattern below: fork() the first child to run async, then compute() the second on the current
      thread, then join() the first. This is one fewer fork than fork(); fork(); join(); join(); (the simpler-looking
      pattern) and avoids one wasted thread switch.
    - Summing longs is deliberately CHEAP per element, so do not be surprised if the parallel run below is no faster
      than the sequential one — memory bandwidth, not CPU, is the limit. That is the lesson, and it is the same one
      Mod010 §3 makes for parallel streams.

    fork vs compute vs invoke

    - fork() schedules the task on the current pool's deque and returns immediately. Use it on at most N-1 of N
      children — the last child is more efficient as a compute() because it stays on the current thread (no enqueue,
      no steal).
    - compute() runs the task synchronously on the current thread. It is the body of the recursion.
    - invoke() begins executing the task on the CURRENT thread and waits for its result — think compute() plus a
      join, not fork() plus join(). Nothing is pushed onto the deque, which is precisely why the "last child as a
      compute()" advice above holds.
    - invokeAll(t1, t2, ...) is the convenient form for RecursiveAction. For results, prefer the explicit
      fork() + compute() + join() pattern.
    */
    static void recursiveTask() {
        System.out.println("[Section 4-5] RecursiveTask + fork/compute/join");

        long[] data = new long[10_000_000];
        for (int i = 0; i < data.length; i++) data[i] = 1;

        // Warm up both paths so the JIT has compiled them before anything is timed.
        for (int i = 0; i < 3; i++) {
            ForkJoinPool.commonPool().invoke(new SumTask(data, 0, data.length, 50_000));
            long warm = 0;
            for (long v : data) warm += v;
            if (warm == Long.MIN_VALUE) System.out.print(""); // keep the loop alive
        }

        long t0 = System.nanoTime();
        long parallel = ForkJoinPool.commonPool()
                .invoke(new SumTask(data, 0, data.length, 50_000));
        long parallelUs = (System.nanoTime() - t0) / 1_000;

        long t1 = System.nanoTime();
        long serial = 0;
        for (long v : data) serial += v;
        long serialUs = (System.nanoTime() - t1) / 1_000;

        System.out.printf(Locale.ROOT, "  parallel sum = %d (%,d µs)%n", parallel, parallelUs);
        System.out.printf(Locale.ROOT, "  serial   sum = %d (%,d µs)%n", serial, serialUs);
    }

    /*
    Threshold tuning

    - Splitting has overhead: forks, deque operations, possibly steals. Below some chunk size the per-element work is
      dominated by bookkeeping and parallel is slower than serial.
    - The right threshold depends on per-element cost. Common heuristic: pick the smallest threshold at which the
      parallel run is no slower than the sequential one — bigger thresholds waste cores; smaller thresholds waste
      overhead.
    - Measure on the actual workload. Nothing else is reliable — and the single timed runs printed here are an
      illustration of an order of magnitude, not a benchmark. Use JMH for real numbers.
    */
    static void thresholdSweep() {
        System.out.println("[Section 6] threshold tuning");

        long[] data = new long[5_000_000];
        for (int i = 0; i < data.length; i++) data[i] = i;

        int[] thresholds = { 100, 1_000, 50_000, 500_000, 5_000_000 };
        for (int th : thresholds) {
            ForkJoinPool.commonPool().invoke(new SumTask(data, 0, data.length, th)); // warm-up
            long t0 = System.nanoTime();
            for (int rep = 0; rep < 5; rep++) {
                ForkJoinPool.commonPool().invoke(new SumTask(data, 0, data.length, th));
            }
            long us = (System.nanoTime() - t0) / 1_000 / 5;
            System.out.printf(Locale.ROOT, "  threshold=%,9d → %,6d µs%s%n", th, us,
                    th >= data.length ? "   (one task: effectively sequential)" : "");
        }
    }

    /*
    Bridge to parallel streams

    - Parallel streams are a high-level facade over the common ForkJoinPool. They hide all the threshold and split
      logic in a Spliterator, so the code looks sequential but runs on multiple cores.
    - Use a parallel stream when:
      - The data structure has an efficient spliterator (ArrayList, arrays, ConcurrentHashMap.values() — yes;
        LinkedList — no).
      - The per-element cost is non-trivial (microseconds, not nanoseconds).
      - The combining function is associative (a Stream::reduce requirement).
    - See Mod010 for the full treatment, including the pitfalls.
    */
    static void parallelStreamUsesCommonPool() {
        System.out.println("[Section 7] parallel streams ride on the common pool");

        var threadNames = new java.util.concurrent.ConcurrentSkipListSet<String>();
        long total = java.util.stream.IntStream.range(0, 1_000_000).boxed().toList()
                .parallelStream()
                .peek(x -> threadNames.add(Thread.currentThread().getName()))
                .mapToLong(Integer::longValue)
                .sum();
        System.out.println("  total = " + total + ", threads observed = " + threadNames);
        // Note "main" in that list: the thread that STARTS a parallel stream does not just wait —
        // it joins the pool and processes chunks itself. So the effective width of a parallel stream
        // is commonPool parallelism + 1, and blocking the calling thread blocks a worker too.
    }

    public static void main(String[] args) {
        commonPool();
        recursiveAction();
        recursiveTask();
        thresholdSweep();
        parallelStreamUsesCommonPool();
        System.out.println("Mod008ForkJoin finished");
    }
}
