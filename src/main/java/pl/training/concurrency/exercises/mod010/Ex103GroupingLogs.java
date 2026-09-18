package pl.training.concurrency.exercises.mod010;

import pl.training.concurrency.exercises.mod010.Ex101BreakEvenSweep.LogEntry;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Exercise 10.3 — Grouping a log stream: groupingBy vs groupingByConcurrent.
 *
 * <p>Related: Mod010 §7, §8
 *
 * <p>The timings are order-of-magnitude illustrations, warmed up but not benchmarks.
 */
public final class Ex103GroupingLogs {

    private Ex103GroupingLogs() {}

    private static final int LOG_COUNT = 1_000_000;
    private static final int WARMUP_ROUNDS = 2;

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        var logs = Ex101BreakEvenSweep.generateLogs(LOG_COUNT);

        System.out.println("[1] both collectors agree on the result");
        Map<String, Long> byService = logs.parallelStream()
                .collect(Collectors.groupingBy(LogEntry::service, Collectors.counting()));
        Map<String, Long> byServiceConcurrent = logs.parallelStream().unordered()
                .collect(Collectors.groupingByConcurrent(LogEntry::service, Collectors.counting()));
        if (!byService.equals(byServiceConcurrent)) throw new AssertionError("collectors must agree");
        System.out.println("  " + byService + "  (identical from both collectors)");

        System.out.println("[2] cardinality sweep");
        System.out.printf(Locale.ROOT, "  %-26s %7s %14s %20s   %s%n",
                "case", "groups", "groupingBy", "groupingByConcurrent", "verdict");
        compare("5 keys, counting          ", logs, LogEntry::service, Collectors.counting());
        compare("500 keys, counting        ", logs, e -> Long.toString(e.latencyMs()), Collectors.counting());
        compare("2500 keys, counting       ", logs, e -> e.service() + "-" + e.latencyMs(), Collectors.counting());
        compare("2500 keys, toList         ", logs, e -> e.service() + "-" + e.latencyMs(), Collectors.toList());
        System.out.println("  → default to groupingBy; reach for the concurrent variant only when a profiler");
        System.out.println("    shows the MERGE step itself is the bottleneck, then confirm on your own data");
        // Why the concurrent collector usually loses:
        //  - Contention. Every worker writes into ONE shared ConcurrentHashMap. With few keys they all hammer the
        //    same handful of bins and serialise on them.
        //  - Accumulator cost. groupingBy builds a plain HashMap per chunk, and a parallel stream produces only
        //    about `parallelism` chunks — so the merge it "saves" is a couple of dozen cheap map unions, while
        //    every single element pays the higher price of a concurrent write.

        System.out.println("[3] what groupingByConcurrent needs from the stream");
        System.out.println("  characteristics = " + Collectors.groupingByConcurrent(
                LogEntry::service, Collectors.counting()).characteristics());
        System.out.println("  → CONCURRENT + UNORDERED. The single shared container is only usable when the");
        System.out.println("    runtime may write into it in ANY order, so the stream must be unordered too —");
        System.out.println("    either from .unordered() or because the source has no encounter order.");
        // On an ORDERED parallel stream the runtime silently falls back to the per-chunk accumulate-and-merge
        // strategy, so you pay for the ConcurrentHashMap and get none of its supposed benefit.
        Map<String, List<LogEntry>> grouped = logs.parallelStream().unordered()
                .collect(Collectors.groupingByConcurrent(LogEntry::service, Collectors.toList()));
        System.out.println("  map type = " + grouped.getClass().getSimpleName()
                + " (no key order), and within a group the element order is NOT the source order");

        System.out.println("[4] the winning version on a dedicated pool");
        var threadNames = new ConcurrentSkipListSet<String>();
        try (var pool = new ForkJoinPool(4)) {
            Map<String, Long> onDedicatedPool = pool.submit(() -> logs.parallelStream()
                    .collect(Collectors.groupingBy(entry -> {
                        threadNames.add(Thread.currentThread().getName());
                        return entry.service();
                    }, Collectors.counting()))).get();
            System.out.println("  result = " + onDedicatedPool);
        }
        long commonPoolWorkers = threadNames.stream().filter(n -> n.startsWith("ForkJoinPool.commonPool")).count();
        System.out.println("  threads seen inside the classifier = " + threadNames.size() + " " + threadNames);
        System.out.println("  common-pool workers among them    = " + commonPoolWorkers + " (must be 0)");
        if (commonPoolWorkers != 0) throw new AssertionError("the common pool must not take part");

        System.out.println("Ex103GroupingLogs finished");
    }

    private static <D> void compare(String label, List<LogEntry> logs,
                                    Function<LogEntry, String> keyFn,
                                    Collector<LogEntry, ?, D> downstream) {
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            logs.parallelStream().collect(Collectors.groupingBy(keyFn, downstream));
            logs.parallelStream().unordered().collect(Collectors.groupingByConcurrent(keyFn, downstream));
        }

        long t0 = System.nanoTime();
        Map<String, D> grouped = logs.parallelStream().collect(Collectors.groupingBy(keyFn, downstream));
        long groupingMicros = (System.nanoTime() - t0) / 1_000;

        long t1 = System.nanoTime();
        Map<String, D> concurrent = logs.parallelStream().unordered()
                .collect(Collectors.groupingByConcurrent(keyFn, downstream));
        long concurrentMicros = (System.nanoTime() - t1) / 1_000;

        if (grouped.size() != concurrent.size()) throw new AssertionError("collectors must agree");
        System.out.printf(Locale.ROOT, "  %-26s %,7d %,11d µs %,17d µs   %s%n",
                label, grouped.size(), groupingMicros, concurrentMicros,
                concurrentMicros < groupingMicros
                        ? String.format(Locale.ROOT, "concurrent %.1fx faster", (double) groupingMicros / concurrentMicros)
                        : String.format(Locale.ROOT, "concurrent %.1fx SLOWER", (double) concurrentMicros / groupingMicros));
    }
}
