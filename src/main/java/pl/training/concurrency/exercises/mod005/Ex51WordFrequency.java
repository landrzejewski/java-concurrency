package pl.training.concurrency.exercises.mod005;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Exercise 5.1 — Word frequency with ConcurrentHashMap.
 *
 * <p>Related: Mod005 §1, §2
 */
public final class Ex51WordFrequency {

    private Ex51WordFrequency() {}

    static final String[] VOCABULARY = {
            "java", "thread", "lock", "map", "queue", "atomic", "volatile", "executor",
            "future", "stream", "pool", "barrier", "latch", "phaser", "monitor", "semaphore"
    };
    static final int CHUNKS = 8;
    static final int WORDS_PER_CHUNK = 100_000;

    static List<List<String>> generateText() {
        var chunks = new ArrayList<List<String>>();
        var random = ThreadLocalRandom.current();
        for (int c = 0; c < CHUNKS; c++) {
            var chunk = new ArrayList<String>(WORDS_PER_CHUNK);
            for (int i = 0; i < WORDS_PER_CHUNK; i++) {
                double skew = random.nextDouble();
                chunk.add(VOCABULARY[(int) (skew * skew * VOCABULARY.length)]); // skewed: low indexes are frequent
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    /** Broken: get + put are individually synchronized, but the pair is not atomic — updates get lost. */
    static Map<String, Integer> countBroken(List<List<String>> chunks) throws InterruptedException {
        Map<String, Integer> counts = Collections.synchronizedMap(new HashMap<>());
        runInParallel(chunks, chunk -> {
            for (var word : chunk) {
                Integer old = counts.get(word);
                counts.put(word, old == null ? 1 : old + 1);
            }
        });
        return counts;
    }

    /** Correct: merge is a single atomic compound operation per key. */
    static ConcurrentHashMap<String, Integer> countCorrect(List<List<String>> chunks) throws InterruptedException {
        var counts = new ConcurrentHashMap<String, Integer>();
        runInParallel(chunks, chunk -> {
            for (var word : chunk) {
                counts.merge(word, 1, Integer::sum);
            }
        });
        return counts;
    }

    private static void runInParallel(List<List<String>> chunks, Consumer<List<String>> work)
            throws InterruptedException {
        var threads = new ArrayList<Thread>();
        for (var chunk : chunks) {
            threads.add(new Thread(() -> work.accept(chunk)));
        }
        threads.forEach(Thread::start);
        for (var thread : threads) {
            thread.join();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        var text = generateText();
        long total = (long) CHUNKS * WORDS_PER_CHUNK;

        System.out.println("[1] synchronizedMap with get + put");
        var broken = countBroken(text);
        long brokenTotal = broken.values().stream().mapToLong(Integer::longValue).sum();
        System.out.printf(Locale.ROOT, "  counted %d of %d words (lost %d)%n", brokenTotal, total, total - brokenTotal);

        System.out.println("[2] ConcurrentHashMap.merge");
        var counts = countCorrect(text);
        // bulk op: sum all values in parallel (threshold 1 = use as many threads as the common pool allows)
        long correctTotal = counts.reduceValuesToLong(1, Integer::longValue, 0L, Long::sum);
        System.out.printf(Locale.ROOT, "  counted %d of %d words -> %s%n", correctTotal, total,
                correctTotal == total ? "OK" : "BROKEN");

        System.out.println("[3] top 10 via reduceEntries (no copy of the map)");
        // each entry becomes a one-element "top list"; the reducer merges two top lists and keeps the best 10
        Comparator<Map.Entry<String, Integer>> byCountDesc = Map.Entry.<String, Integer>comparingByValue().reversed();
        List<Map.Entry<String, Integer>> top = counts.reduceEntries(1,
                entry -> List.of(Map.entry(entry.getKey(), entry.getValue())),
                (left, right) -> {
                    var merged = new ArrayList<>(left);
                    merged.addAll(right);
                    merged.sort(byCountDesc);
                    return merged.size() > 10 ? List.copyOf(merged.subList(0, 10)) : merged;
                });
        top.forEach(entry -> System.out.printf(Locale.ROOT, "  %-10s %d%n", entry.getKey(), entry.getValue()));
        counts.forEachEntry(1, entry -> {
            if (entry.getValue() > WORDS_PER_CHUNK * 2) {
                System.out.printf(Locale.ROOT, "  (forEachEntry) very frequent: %s%n", entry.getKey());
            }
        });

        System.out.println("[4] nulls are not allowed");
        try {
            counts.put(null, 1);
        } catch (NullPointerException e) {
            System.out.println("  put(null, 1) -> NullPointerException");
        }
        try {
            counts.put("x", null);
        } catch (NullPointerException e) {
            System.out.println("  put(\"x\", null) -> NullPointerException");
        }
        // Reason: get() returning null must mean "absent" unambiguously — a concurrent map cannot let you do a
        // containsKey() + get() pair atomically, so null values would be indistinguishable from missing keys.
        System.out.println("Ex51WordFrequency finished");
    }
}
