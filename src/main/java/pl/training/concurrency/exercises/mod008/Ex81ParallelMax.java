package pl.training.concurrency.exercises.mod008;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Exercise 8.1 — Parallel max with RecursiveTask.
 *
 * <p>Related: Mod008 §2, §4–§5, §7
 */
public final class Ex81ParallelMax {

    private Ex81ParallelMax() {}

    /** 20M longs = 160 MB; the exercise says 50M, lowered so the default heap of a small machine copes. */
    static final int SIZE = 20_000_000;

    static final class MaxTask extends RecursiveTask<Long> {
        private final long[] data;
        private final int from;
        private final int to;
        private final int threshold;

        MaxTask(long[] data, int from, int to, int threshold) {
            this.data = data;
            this.from = from;
            this.to = to;
            this.threshold = threshold;
        }

        @Override protected Long compute() {
            if (to - from <= threshold) {
                long max = Long.MIN_VALUE;
                for (int i = from; i < to; i++) {
                    max = Math.max(max, data[i]);
                }
                return max;
            }
            int mid = (from + to) >>> 1;
            var left = new MaxTask(data, from, mid, threshold);
            var right = new MaxTask(data, mid, to, threshold);
            left.fork();                     // hand the left half to the pool (our own deque, LIFO for us)
            long rightMax = right.compute(); // do the right half ourselves — no idle waiting
            long leftMax = left.join();      // by now it is either done or stolen; join runs it if still queued
            return Math.max(leftMax, rightMax);
        }
    }

    public static void main(String[] args) {
        var data = new long[SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = ThreadLocalRandom.current().nextLong();
        }
        int parallelism = ForkJoinPool.commonPool().getParallelism();
        int threshold = Math.max(SIZE / (parallelism * 4), 1_000);
        System.out.printf(Locale.ROOT, "common pool parallelism=%d, threshold=%d%n", parallelism, threshold);

        long sequential = measure("sequential loop", () -> sequentialMax(data));
        long forkJoin = measure("fork/join", () -> ForkJoinPool.commonPool().invoke(new MaxTask(data, 0, data.length, threshold)));
        long stream = measure("parallel stream", () -> Arrays.stream(data).parallel().max().orElseThrow());
        System.out.println(sequential == forkJoin && forkJoin == stream ? "all three agree" : "MISMATCH");

        // Which threads take part: the common pool's workers (parallelism = cores - 1 by default) plus the
        // thread that calls invoke()/join() — the caller does not idle, it helps run queued tasks. That is why
        // the default parallelism is one less than the CPU count: the submitting thread fills the last core.
        System.out.println("Ex81ParallelMax finished");
    }

    static long sequentialMax(long[] data) {
        long max = Long.MIN_VALUE;
        for (long value : data) {
            max = Math.max(max, value);
        }
        return max;
    }

    /** Warms up the JIT with a few runs, then reports the best of five timed runs. */
    static long measure(String label, LongSupplier computation) {
        long result = 0;
        for (int i = 0; i < 3; i++) {
            result = computation.getAsLong();
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            result = computation.getAsLong();
            best = Math.min(best, System.nanoTime() - t0);
        }
        System.out.printf(Locale.ROOT, "  %-16s %6.1f ms (max=%d)%n", label, best / 1e6, result);
        return result;
    }
}
