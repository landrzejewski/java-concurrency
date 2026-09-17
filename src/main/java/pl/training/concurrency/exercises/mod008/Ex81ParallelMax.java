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
           return 0L;
        }
    }

    public static void main(String[] args) {
        var data = new long[SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = ThreadLocalRandom.current().nextLong();
        }

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
