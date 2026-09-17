package pl.training.concurrency.exercises.mod008;

import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exercise 8.2 — In-place normalization with RecursiveAction and a threshold sweep.
 *
 * <p>Related: Mod008 §3, §6
 */
public final class Ex82NormalizeAction {

    private Ex82NormalizeAction() {}

    static final int SIZE = 10_000_000;
    static final int[] THRESHOLDS = {100, 1_000, 10_000, 100_000, 1_000_000};

    static final class MaxTask extends RecursiveTask<Double> {
        private final double[] data;
        private final int from;
        private final int to;
        private final int threshold;

        MaxTask(double[] data, int from, int to, int threshold) {
            this.data = data;
            this.from = from;
            this.to = to;
            this.threshold = threshold;
        }

        @Override protected Double compute() {
            if (to - from <= threshold) {
                double max = Double.NEGATIVE_INFINITY;
                for (int i = from; i < to; i++) {
                    max = Math.max(max, data[i]);
                }
                return max;
            }
            int mid = (from + to) >>> 1;
            var left = new MaxTask(data, from, mid, threshold);
            var right = new MaxTask(data, mid, to, threshold);
            left.fork();
            double rightMax = right.compute();
            return Math.max(left.join(), rightMax);
        }
    }

    /** Divides every element of its range by max, in place. */
    static final class NormalizeAction extends RecursiveAction {
        private final double[] data;
        private final int from;
        private final int to;
        private final double max;
        private final int threshold;

        NormalizeAction(double[] data, int from, int to, double max, int threshold) {
            this.data = data;
            this.from = from;
            this.to = to;
            this.max = max;
            this.threshold = threshold;
        }

        @Override protected void compute() {
            if (to - from <= threshold) {
                for (int i = from; i < to; i++) {
                    data[i] /= max;
                }
                return;
            }
            int mid = (from + to) >>> 1;
            invokeAll(new NormalizeAction(data, from, mid, max, threshold),
                      new NormalizeAction(data, mid, to, max, threshold));
        }
        // No synchronization needed: the two halves write to disjoint index ranges, so there is no data race,
        // and invokeAll/join establish happens-before from each subtask's writes to the parent's return —
        // the caller of invoke() sees the fully normalized array.
    }

    public static void main(String[] args) {
        var original = new double[SIZE];
        for (int i = 0; i < original.length; i++) {
            original[i] = ThreadLocalRandom.current().nextDouble(0, 1_000);
        }
        var pool = ForkJoinPool.commonPool();

        // warm-up
        for (int i = 0; i < 3; i++) {
            normalize(pool, original.clone(), 100_000);
        }

        System.out.printf(Locale.ROOT, "%d elements, parallelism %d%n", SIZE, pool.getParallelism());
        for (int threshold : THRESHOLDS) {
            var data = original.clone();
            long t0 = System.nanoTime();
            normalize(pool, data, threshold);
            long millis = (System.nanoTime() - t0) / 1_000_000;
            double maxAfter = pool.invoke(new MaxTask(data, 0, data.length, 100_000));
            System.out.printf(Locale.ROOT, "  threshold %,9d -> %4d ms (max after normalization = %.3f)%n",
                    threshold, millis, maxAfter);
        }
        // Shape of the results: a tiny threshold creates millions of task objects and the split/fork/join
        // bookkeeping dominates; a huge threshold creates too few tasks to keep every worker busy (and to let
        // work-stealing balance the load). The sweet spot is a few thousand elements — a few hundred tasks per core.
        System.out.println("Ex82NormalizeAction finished");
    }

    static void normalize(ForkJoinPool pool, double[] data, int threshold) {
        double max = pool.invoke(new MaxTask(data, 0, data.length, threshold));
        pool.invoke(new NormalizeAction(data, 0, data.length, max, threshold));
    }
}
