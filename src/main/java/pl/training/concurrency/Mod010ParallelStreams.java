package pl.training.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class Mod010ParallelStreams {

    private Mod010ParallelStreams() {}

    // 1M records is enough to show every effect in this module and keeps the heap footprint modest.
    // At 5M the ArrayList alone runs into several hundred MB and can blow the default max heap.
    private static final int LOG_COUNT = 1_000_000;

    record LogEntry(String level, String service, long latencyMs) {}

    private static List<LogEntry> generateLogs(int n) {
        var rnd = new java.util.Random(7);
        String[] levels = { "INFO", "WARN", "ERROR" };
        String[] services = { "auth", "billing", "search", "feed", "notify" };
        var out = new ArrayList<LogEntry>(n);
        for (int i = 0; i < n; i++) {
            out.add(new LogEntry(
                    levels[rnd.nextInt(levels.length)],
                    services[rnd.nextInt(services.length)],
                    rnd.nextInt(500)));
        }
        return out;
    }

    /*
    Sequential vs parallel

    - A sequential stream processes elements one at a time on the calling thread.
    - A parallel stream splits the data across multiple threads, processes the chunks independently, and combines the
      partial results.
    - The demo below runs the SAME pipeline shape over the SAME data twice, changing only how much work each element
      costs. Cheap work: parallel loses. Costly work: parallel wins roughly in proportion to the core count. That
      single variable — per-element cost — decides more than anything else you can tune.
    - Two ways to ask for parallelism: collection.parallelStream() or collection.stream().parallel(). Both produce the
      same kind of stream.
    - Going parallel is not free — there is split, schedule, and combine overhead. The win comes only when the
      per-element work is large enough to outweigh that overhead, and the data structure can be split efficiently.
    - Every timing printed in this module is an ILLUSTRATION OF AN ORDER OF MAGNITUDE, not a benchmark. A single
      timed run on a cold JIT systematically flatters whichever variant runs second, so each measurement below is
      preceded by a warm-up. For numbers you would act on, use JMH.
    */
    static void sequentialVsParallel(List<LogEntry> logs) {
        System.out.println("[Section 1] sequential vs parallel");

        // (a) Trivial per-element work: one field read and an add.
        for (int i = 0; i < 3; i++) {                       // warm-up
            logs.stream().mapToLong(LogEntry::latencyMs).sum();
            logs.parallelStream().mapToLong(LogEntry::latencyMs).sum();
        }
        long t0 = System.nanoTime();
        long seq = logs.stream().mapToLong(LogEntry::latencyMs).sum();
        long seqUs = (System.nanoTime() - t0) / 1_000;
        long t1 = System.nanoTime();
        long par = logs.parallelStream().mapToLong(LogEntry::latencyMs).sum();
        long parUs = (System.nanoTime() - t1) / 1_000;
        System.out.printf(Locale.ROOT, "  cheap  (sum a field): sequential %,7d µs | parallel %,7d µs  → %s%n",
                seqUs, parUs, verdict(seqUs, parUs));
        if (seq != par) throw new AssertionError("both must agree");

        // (b) Non-trivial per-element work — a stand-in for parsing, hashing or scoring.
        for (int i = 0; i < 3; i++) {                       // warm-up
            logs.stream().mapToDouble(Mod010ParallelStreams::score).sum();
            logs.parallelStream().mapToDouble(Mod010ParallelStreams::score).sum();
        }
        long t2 = System.nanoTime();
        double seqScore = logs.stream().mapToDouble(Mod010ParallelStreams::score).sum();
        long seqScoreUs = (System.nanoTime() - t2) / 1_000;
        long t3 = System.nanoTime();
        double parScore = logs.parallelStream().mapToDouble(Mod010ParallelStreams::score).sum();
        long parScoreUs = (System.nanoTime() - t3) / 1_000;
        System.out.printf(Locale.ROOT, "  costly (score each) : sequential %,7d µs | parallel %,7d µs  → %s%n",
                seqScoreUs, parScoreUs, verdict(seqScoreUs, parScoreUs));
        System.out.printf(Locale.ROOT, "  (checksums agree: %.3f ≈ %.3f)%n", seqScore, parScore);
        System.out.println("  → same data, same pipeline shape; only the per-element cost changed");
    }

    /** A deliberately non-trivial per-element computation — the kind of work that pays for parallelism. */
    private static double score(LogEntry entry) {
        double acc = 0;
        for (int i = 1; i <= 40; i++) {
            acc += Math.log1p(entry.latencyMs() + i) / Math.sqrt(i);
        }
        return acc;
    }

    private static String verdict(long sequentialUs, long parallelUs) {
        if (parallelUs == 0) return "n/a";
        double ratio = (double) sequentialUs / parallelUs;
        return ratio >= 1.2 ? String.format(Locale.ROOT, "parallel %.1fx faster", ratio)
             : ratio <= 0.83 ? String.format(Locale.ROOT, "parallel %.1fx SLOWER", 1 / ratio)
             : "a wash";
    }

    /*
    What runs where

    - Parallel streams run on ForkJoinPool.commonPool() by default. The same pool backs
      CompletableFuture.supplyAsync (Mod009) and any custom fork-join workload (Mod008).
    - Default size is availableProcessors() - 1. The exact thread name pattern is ForkJoinPool.commonPool-worker-N.
    - Because the pool is shared, long-running or blocking parallel-stream stages contend with everything else on the
      pool. For blocking I/O, do not use parallel streams; use a dedicated executor and CompletableFuture (Mod009) or
      virtual threads (Mod011).
    */
    static void whatRunsWhere(List<LogEntry> logs) {
        System.out.println("[Section 2] what runs where");

        var threadNames = new ConcurrentSkipListSet<String>();
        long total = logs.parallelStream()
                .peek(e -> threadNames.add(Thread.currentThread().getName()))
                .mapToLong(LogEntry::latencyMs)
                .sum();
        System.out.println("  total = " + total);
        System.out.println("  parallelism = " + ForkJoinPool.commonPool().getParallelism());
        System.out.println("  workers observed = " + threadNames.size() + " " + threadNames);
    }

    /*
    When parallel pays off

    The break-even point depends on N × per-element-cost:

    - Trivial per-element work (a single arithmetic op): parallel is almost always slower because the constant
      overhead per split dominates.
    - Heavy per-element work (parsing, hashing, decryption): parallel scales near-linearly with cores up to the
      parallelism limit.
    - Variable per-element work: work-stealing inside the fork-join pool absorbs imbalance, so parallel can still win
      even if elements have wildly different costs.

    Always measure on the actual workload — the tipping point depends on JIT, cache behavior, GC, and the source's
    spliterator quality.
    */
    static void parallelDoesNotAlwaysWin() {
        System.out.println("[Section 3] when parallel does not win");

        var nums = IntStream.rangeClosed(1, 1_000_000).boxed().toList();

        for (int i = 0; i < 3; i++) {                       // warm-up
            nums.stream().mapToLong(Integer::longValue).sum();
            nums.parallelStream().mapToLong(Integer::longValue).sum();
        }

        long t0 = System.nanoTime();
        nums.stream().mapToLong(Integer::longValue).sum();
        long seqUs = (System.nanoTime() - t0) / 1_000;

        long t1 = System.nanoTime();
        nums.parallelStream().mapToLong(Integer::longValue).sum();
        long parUs = (System.nanoTime() - t1) / 1_000;

        System.out.printf(Locale.ROOT, "  trivial sum over a boxed List: sequential = %,d µs, parallel = %,d µs%n",
                seqUs, parUs);
        System.out.println("  → the per-element work is one unboxing and one add; splitting, scheduling and");
        System.out.println("    chasing pointers through boxed Integers costs more than it saves");
    }

    /*
    Order-sensitive operations

    - Parallel streams preserve the encounter order of the source unless you explicitly opt out (.unordered()).
    - findFirst() must search left-to-right and is more expensive on parallel streams than findAny(). Pick findAny
      when any matching element will do.
    - forEach on a PARALLEL stream does not respect encounter order — it is free to hand elements to the action in
      whatever order the chunks finish. On a sequential stream it does process elements in encounter order. Use
      forEachOrdered when you need source order from a parallel stream, and accept that it costs buffering.
    - limit and skip on parallel ordered streams are also expensive — they need to wait for elements to be processed
      in the right sequence to know which N you keep.
    */
    static void orderSensitiveOps() {
        System.out.println("[Section 4] order-sensitive operations");

        var range = IntStream.rangeClosed(1, 1_000_000).boxed().toList();
        // findFirst respects encounter order — the answer is deterministic.
        int first = range.parallelStream().filter(x -> x > 100).findFirst().orElseThrow();
        // findAny is allowed to return any matching element.
        int any   = range.parallelStream().filter(x -> x > 100).findAny().orElseThrow();
        System.out.println("  findFirst = " + first + ", findAny = " + any);

        // forEach on a parallel stream: order is not preserved.
        System.out.print("  forEach (parallel, unordered): ");
        Stream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).parallel().forEach(n -> System.out.print(n + " "));
        System.out.println();

        // forEachOrdered: source order restored, at a cost.
        System.out.print("  forEachOrdered (parallel):     ");
        Stream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).parallel().forEachOrdered(n -> System.out.print(n + " "));
        System.out.println();

        // unordered(): explicitly drop the ordering constraint. The runtime may then use the cheaper
        // findAny-style shortcut for findFirst, and limit/distinct/skip stop having to buffer.
        int anyAfterUnordered = range.parallelStream().unordered()
                .filter(x -> x > 100).findFirst().orElseThrow();
        System.out.println("  findFirst() on an unordered parallel stream = " + anyAfterUnordered
                + " (no longer required to be 101 — unordered() waives the guarantee)");
    }

    /*
    collect and concurrent collectors

    - collect(Collectors.toList()) on a parallel stream still gives correct results, but the collector merges
      per-thread accumulators sequentially, which limits speedup.
    - A concurrent collector (toConcurrentMap, groupingByConcurrent) writes into a single shared concurrent
      container, skipping the merge step. It must also be marked UNORDERED (or used on an unordered stream) so the
      runtime is free to write into the container in any order.
    - "Skipping the merge" sounds like a free win. It is not, and the folklore that groupingByConcurrent is the
      faster choice on multi-core machines does not survive measurement. Two costs work against it:
      - Contention. Every worker writes into one shared ConcurrentHashMap. With few keys they all hammer the same
        handful of bins and serialise on them; the run below is roughly 40x SLOWER than plain groupingBy.
      - Accumulator cost. groupingBy builds a plain HashMap per chunk, and a parallel stream only produces about
        `parallelism` chunks — so the merge it "saves" is a couple of dozen cheap map unions, while every single
        element pays the higher price of a concurrent write.
    - The measurements below sweep key cardinality from 5 to a few thousand, plus an expensive downstream collector.
      On a typical machine groupingByConcurrent loses every one of them.
    - Practical rule: default to groupingBy. Reach for groupingByConcurrent only when a profiler shows the merge
      step itself is the bottleneck — very high cardinality combined with downstream containers that are costly to
      combine — and then confirm with a measurement on your data, not on this advice.
    */
    static void concurrentCollectors(List<LogEntry> logs) {
        System.out.println("[Section 7] concurrent collectors — measure, do not assume");

        // Cardinality sweep. 5 services; 500 distinct latencies; 5 * 500 = 2500 combinations.
        compareCollectors("     5 keys, counting", logs, LogEntry::service);
        compareCollectors("   500 keys, counting", logs, e -> Long.toString(e.latencyMs()));
        compareCollectors("  2500 keys, counting", logs, e -> e.service() + "-" + e.latencyMs());
        // Expensive downstream: each group accumulates a real list, so the merge groupingBy has to
        // perform is genuinely costly — the case most favourable to the concurrent collector.
        compareCollectors("  2500 keys, toList  ", logs, e -> e.service() + "-" + e.latencyMs(),
                Collectors.toList());

        System.out.println("  → groupingByConcurrent lost every round here; treat it as a profiler-driven");
        System.out.println("    optimisation, never as the default for parallel grouping");
    }

    private static void compareCollectors(String label, List<LogEntry> logs,
                                          java.util.function.Function<LogEntry, String> keyFn) {
        compareCollectors(label, logs, keyFn, Collectors.counting());
    }

    private static <D> void compareCollectors(String label, List<LogEntry> logs,
                                              java.util.function.Function<LogEntry, String> keyFn,
                                              java.util.stream.Collector<LogEntry, ?, D> downstream) {
        for (int i = 0; i < 2; i++) {                       // warm-up
            logs.parallelStream().collect(Collectors.groupingBy(keyFn, downstream));
            logs.parallelStream().unordered().collect(Collectors.groupingByConcurrent(keyFn, downstream));
        }

        long t1 = System.nanoTime();
        Map<String, D> grouped = logs.parallelStream()
                .collect(Collectors.groupingBy(keyFn, downstream));
        long groupingUs = (System.nanoTime() - t1) / 1_000;

        long t2 = System.nanoTime();
        Map<String, D> groupedConcurrent = logs.parallelStream().unordered()
                .collect(Collectors.groupingByConcurrent(keyFn, downstream));
        long concurrentUs = (System.nanoTime() - t2) / 1_000;

        if (grouped.size() != groupedConcurrent.size()) throw new AssertionError("collectors must agree");
        System.out.printf(Locale.ROOT, "%s (%,5d groups): groupingBy %,7d µs | groupingByConcurrent %,7d µs  → %s%n",
                label, grouped.size(), groupingUs, concurrentUs,
                concurrentUs < groupingUs
                        ? String.format(Locale.ROOT, "concurrent %.1fx faster", (double) groupingUs / concurrentUs)
                        : String.format(Locale.ROOT, "concurrent %.1fx SLOWER", (double) concurrentUs / groupingUs));
    }

    /*
    Custom pool for parallel streams

    - Stream.parallel() does not let you pass an executor — the JVM hardcodes the common pool.
    - The standard workaround is to wrap the entire stream pipeline inside
      ForkJoinPool.submit(() -> stream.parallel()...).get(). That submission runs on the custom pool, and ForkJoin
      tasks created from inside (which is what the spliterator does) inherit that pool.
    - Use this when:
      - The stream might block briefly (you do not want to block the common pool).
      - You want a different parallelism than the default.
      - You want the work isolated for monitoring.
    */
    static void customPool(List<LogEntry> logs) throws InterruptedException, ExecutionException {
        System.out.println("[Section 8] custom pool");

        try (var pool = new ForkJoinPool(4)) {
            var threadNames = new ConcurrentSkipListSet<String>();
            long total = pool.submit(() -> logs.parallelStream()
                    .peek(e -> threadNames.add(Thread.currentThread().getName()))
                    .mapToLong(LogEntry::latencyMs)
                    .sum()).get();
            System.out.println("  total = " + total);
            System.out.println("  workers observed = " + threadNames.size() + " (custom pool, NOT common)");
            System.out.println("  one of them: " + threadNames.iterator().next());
        }
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        var logs = generateLogs(LOG_COUNT);

        sequentialVsParallel(logs);
        whatRunsWhere(logs);
        parallelDoesNotAlwaysWin();
        orderSensitiveOps();
        concurrentCollectors(logs);
        customPool(logs);
        System.out.println("Mod010ParallelStreams finished");
    }
}
