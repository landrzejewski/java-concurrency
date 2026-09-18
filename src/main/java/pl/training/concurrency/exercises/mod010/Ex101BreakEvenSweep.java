package pl.training.concurrency.exercises.mod010;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.ToDoubleFunction;
import java.util.stream.IntStream;

/**
 * Exercise 10.1 — Break-even sweep: when parallel actually pays.
 *
 * <p>Related: Mod010 §1, §2, §3, §8
 *
 * <p>Every number printed here is an ORDER-OF-MAGNITUDE ILLUSTRATION, not a benchmark. Each
 * measurement is preceded by a warm-up because a single timed run on a cold JIT systematically
 * flatters whichever variant happens to run second. For numbers you would act on, use JMH.
 */
public final class Ex101BreakEvenSweep {

    private Ex101BreakEvenSweep() {}

    record LogEntry(String level, String service, long latencyMs) {}

    private static final int[] SIZES = { 1_000, 10_000, 100_000, 1_000_000 };
    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURED_ROUNDS = 5;

    static List<LogEntry> generateLogs(int n) {
        var random = new Random(7);
        String[] levels = { "INFO", "WARN", "ERROR" };
        String[] services = { "auth", "billing", "search", "feed", "notify" };
        var out = new ArrayList<LogEntry>(n);
        for (int i = 0; i < n; i++) {
            out.add(new LogEntry(levels[random.nextInt(levels.length)],
                    services[random.nextInt(services.length)],
                    random.nextInt(500)));
        }
        return out;
    }

    /** Cheap: one field read and an add. */
    static double cheap(LogEntry entry) {
        return entry.latencyMs();
    }

    /** Costly: ~40 transcendental operations — a stand-in for parsing, hashing or scoring. */
    static double costly(LogEntry entry) {
        double acc = 0;
        for (int i = 1; i <= 40; i++) {
            acc += Math.log1p(entry.latencyMs() + i) / Math.sqrt(i);
        }
        return acc;
    }

    /** A mid-weight per-element computation, used to compare SOURCES rather than costs. */
    static double work(int value) {
        double acc = 0;
        for (int i = 1; i <= 20; i++) {
            acc += Math.log1p(value + i) / Math.sqrt(i);
        }
        return acc;
    }

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        System.out.println("[1] break-even sweep: size x per-element cost");
        System.out.printf(Locale.ROOT, "  %-10s %-8s %12s %12s   %s%n",
                "size", "cost", "sequential", "parallel", "verdict");
        for (int size : SIZES) {
            var logs = generateLogs(size);
            sweepRow(size, "cheap ", logs, Ex101BreakEvenSweep::cheap);
            sweepRow(size, "costly", logs, Ex101BreakEvenSweep::costly);
        }
        System.out.println("  → there is no single break-even size — it MOVES with the per-element cost.");
        System.out.println("    Costly work already pays at ~1k elements; the cheap sum needs ~100x more");
        System.out.println("    before N x per-element-cost finally outweighs the split/schedule/combine overhead.");

        System.out.println("[2] the source, at two different per-element costs");
        int n = 200_000;
        var boxed = IntStream.range(0, n).boxed().toList();
        var linked = new LinkedList<>(boxed);
        System.out.printf(Locale.ROOT, "  %-20s %-34s %s%n", "source", "cheap (one add)", "costly (20 log1p)");
        compareSources("IntStream.range",
                () -> IntStream.range(0, n).asLongStream().sum(),
                () -> IntStream.range(0, n).parallel().asLongStream().sum(),
                () -> IntStream.range(0, n).mapToDouble(Ex101BreakEvenSweep::work).sum(),
                () -> IntStream.range(0, n).parallel().mapToDouble(Ex101BreakEvenSweep::work).sum());
        compareSources("List<Integer>",
                () -> boxed.stream().mapToLong(Integer::longValue).sum(),
                () -> boxed.parallelStream().mapToLong(Integer::longValue).sum(),
                () -> boxed.stream().mapToDouble(Ex101BreakEvenSweep::work).sum(),
                () -> boxed.parallelStream().mapToDouble(Ex101BreakEvenSweep::work).sum());
        compareSources("LinkedList<Integer>",
                () -> linked.stream().mapToLong(Integer::longValue).sum(),
                () -> linked.parallelStream().mapToLong(Integer::longValue).sum(),
                () -> linked.stream().mapToDouble(Ex101BreakEvenSweep::work).sum(),
                () -> linked.parallelStream().mapToDouble(Ex101BreakEvenSweep::work).sum());

        System.out.println("  → with cheap work the SOURCE decides: IntStream.range splits arithmetically over");
        System.out.println("    primitives and gains the most, while the boxed sources barely break even because");
        System.out.println("    chasing Integer pointers costs more than the arithmetic. With costly work every");
        System.out.println("    source converges on the core count — the spliterator has stopped mattering.");
        // Read those ratios as "is parallel worth it for THIS source", not as a ranking of sources: each column
        // compares a source against ITSELF. In absolute terms the three are far apart — a LinkedList cannot be
        // split by index at all, so its spliterator has to walk the chain and buffer elements into arrays before
        // any parallelism starts, and it is the slowest of the three sequentially too.
        // Practical rule: prefer an array-backed or range-based source, and if the elements are primitives keep
        // them in an IntStream/LongStream instead of boxing them.

        System.out.println("[3] who runs it");
        System.out.println("  availableProcessors           = " + Runtime.getRuntime().availableProcessors());
        System.out.println("  commonPool().getParallelism() = " + ForkJoinPool.commonPool().getParallelism()
                + "  (= processors - 1; the CALLING thread also helps, so processors threads work in total)");

        System.out.println("[4] the same costly pipeline on a dedicated pool of 2");
        var logs = generateLogs(1_000_000);
        for (int i = 0; i < WARMUP_ROUNDS; i++) logs.parallelStream().mapToDouble(Ex101BreakEvenSweep::costly).sum();
        long commonMicros = timeMicros(() -> logs.parallelStream().mapToDouble(Ex101BreakEvenSweep::costly).sum());
        long dedicatedMicros;
        try (var pool = new ForkJoinPool(2)) {
            pool.submit(() -> logs.parallelStream().mapToDouble(Ex101BreakEvenSweep::costly).sum()).get();
            dedicatedMicros = timeMicros(() -> {
                try {
                    return pool.submit(() -> logs.parallelStream()
                            .mapToDouble(Ex101BreakEvenSweep::costly).sum()).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        System.out.printf(Locale.ROOT, "  common pool %,d µs | ForkJoinPool(2) %,d µs%n",
                commonMicros, dedicatedMicros);
        System.out.println("  → Stream.parallel() takes no executor; wrapping the pipeline in pool.submit(...).get()");
        System.out.println("    works because the spliterator's ForkJoinTasks inherit the pool of the calling task");

        System.out.println("Ex101BreakEvenSweep finished");
    }

    private static void sweepRow(int size, String label, List<LogEntry> logs, ToDoubleFunction<LogEntry> work) {
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            logs.stream().mapToDouble(work).sum();
            logs.parallelStream().mapToDouble(work).sum();
        }
        long sequential = timeMicros(() -> logs.stream().mapToDouble(work).sum());
        long parallel   = timeMicros(() -> logs.parallelStream().mapToDouble(work).sum());
        System.out.printf(Locale.ROOT, "  %,-10d %-8s %,10d µs %,10d µs   %s%n",
                size, label, sequential, parallel, verdict(sequential, parallel));
    }

    private static void compareSources(String label,
                                       Measured cheapSequential, Measured cheapParallel,
                                       Measured costlySequential, Measured costlyParallel) {
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            cheapSequential.run(); cheapParallel.run(); costlySequential.run(); costlyParallel.run();
        }
        System.out.printf(Locale.ROOT, "  %-20s %-34s %s%n", label,
                verdict(timeMicros(cheapSequential), timeMicros(cheapParallel)),
                verdict(timeMicros(costlySequential), timeMicros(costlyParallel)));
    }

    @FunctionalInterface
    private interface Measured { double run(); }

    /**
     * Best of {@value #MEASURED_ROUNDS} runs. A single timed run is dominated by GC pauses and by common-pool
     * workers that still have to be woken up; the minimum is the least noisy summary a non-JMH harness can give.
     */
    private static long timeMicros(Measured op) {
        long best = Long.MAX_VALUE;
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            long t0 = System.nanoTime();
            double sink = op.run();
            best = Math.min(best, (System.nanoTime() - t0) / 1_000);
            if (Double.isNaN(sink)) throw new AssertionError("keep the JIT from eliminating the work");
        }
        return best;
    }

    private static String verdict(long sequentialMicros, long parallelMicros) {
        if (parallelMicros == 0) return "n/a";
        double ratio = (double) sequentialMicros / parallelMicros;
        return ratio >= 1.2 ? String.format(Locale.ROOT, "parallel %.1fx faster", ratio)
             : ratio <= 0.83 ? String.format(Locale.ROOT, "parallel %.1fx SLOWER", 1 / ratio)
             : "a wash";
    }
}
