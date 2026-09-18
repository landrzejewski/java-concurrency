package pl.training.concurrency.exercises.mod010;

import java.util.ArrayList;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Exercise 10.2 — Correctness traps: stateful lambdas, reduce and encounter order.
 *
 * <p>Related: Mod010 §4, §5, §6
 */
public final class Ex102CorrectnessTraps {

    private Ex102CorrectnessTraps() {}

    private static final int ELEMENTS = 100_000;

    public static void main(String[] args) {

        System.out.println("[1] stateful lambda: writing into an ArrayList from a parallel forEach");
        int lost = 0;
        int threw = 0;
        for (int run = 0; run < 10; run++) {
            var bag = new ArrayList<Integer>();
            try {
                IntStream.range(0, ELEMENTS).parallel().forEach(bag::add);
                if (bag.size() != ELEMENTS) lost++;
            } catch (Exception e) {
                threw++;
            }
        }
        System.out.printf(Locale.ROOT, "  10 runs: %d lost elements, %d threw, %d happened to look correct%n",
                lost, threw, 10 - lost - threw);

        var safe = IntStream.range(0, ELEMENTS).parallel().boxed().toList();
        var alsoSafe = IntStream.range(0, ELEMENTS).parallel().boxed().collect(Collectors.toList());
        System.out.println("  toList() = " + safe.size() + ", collect(toList()) = " + alsoSafe.size()
                + " — both always correct");
        try {
            alsoSafe.add(-1);
            System.out.println("  collect(toList()) returned a MUTABLE ArrayList (the add succeeded)");
        } catch (UnsupportedOperationException e) {
            System.out.println("  collect(toList()) was unmodifiable on this JDK");
        }
        try {
            safe.add(-1);
        } catch (UnsupportedOperationException e) {
            System.out.println("  toList() returned an UNMODIFIABLE list (the add threw "
                    + e.getClass().getSimpleName() + "); it also permits nulls, which Collectors.toList() does too");
        }
        // The fix is not "use a thread-safe list". It is to stop writing to shared state at all: the terminal
        // operation gives every chunk its own container and merges them, so there is nothing to synchronise.

        System.out.println("[2] reduce with a combiner that is not an associative extension of the accumulator");
        // The result is deterministic for a given input, but it tracks the SPLIT, not the data: widen the
        // list by one element and the answer changes shape completely, because a different number of chunks
        // means a different number of multiplications.
        for (int size : new int[] { 4, 6, 8, 9 }) {
            var nums = IntStream.rangeClosed(1, size).boxed().toList();
            int sequential = nums.stream().reduce(0, Integer::sum, (a, b) -> a * b);
            int parallel = nums.parallelStream().reduce(0, Integer::sum, (a, b) -> a * b);
            System.out.printf(Locale.ROOT, "  1..%d  sequential = %,d (the combiner is never called) | parallel = %,d%n",
                    size, sequential, parallel);
        }
        // reduce(identity, accumulator, combiner) requires combiner(accumulator(u, t), ...) to agree with
        // accumulator — formally: combiner must be associative AND compatible with the accumulator, so that
        // combiner(u, accumulator(identity, t)) == accumulator(u, t). Here sum and product violate that, so the
        // answer depends on how the spliterator happened to split the input: not a race, an unmet contract.

        System.out.println("[3] a legal reduce that is still the wrong tool");
        var words = IntStream.range(0, ELEMENTS).mapToObj(i -> "x").toList();
        long t0 = System.nanoTime();
        String concatenated = words.stream().reduce("", String::concat);
        long concatMillis = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        String joined = words.stream().collect(Collectors.joining());
        long joinMillis = (System.nanoTime() - t1) / 1_000_000;
        System.out.printf(Locale.ROOT, "  reduce(\"\", String::concat) %,6d ms | Collectors.joining() %,4d ms "
                + "(equal results: %b)%n", concatMillis, joinMillis, concatenated.equals(joined));
        // Concatenation IS associative and "" really IS its identity, so the reduce satisfies the contract.
        // It is still O(n^2): every step allocates a new String holding everything accumulated so far.
        // joining() uses one StringBuilder per chunk and merges those — linear. A reduce can be correct and wrong.

        System.out.println("[4] encounter order");
        var range = IntStream.rangeClosed(1, 1_000_000).boxed().toList();
        System.out.println("  findFirst (ordered)   = " + range.parallelStream().filter(x -> x > 100)
                .findFirst().orElseThrow() + " — always 101, the runtime must search left to right");
        System.out.println("  findAny               = " + range.parallelStream().filter(x -> x > 100)
                .findAny().orElseThrow() + " — any match will do, so the first chunk to answer wins");
        System.out.print("  forEach (parallel)        : ");
        Stream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).parallel().forEach(n -> System.out.print(n + " "));
        System.out.print("\n  forEachOrdered (parallel) : ");
        Stream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).parallel().forEachOrdered(n -> System.out.print(n + " "));
        System.out.println("  ← source order restored, paid for with buffering");
        System.out.println("  findFirst after unordered() = "
                + range.parallelStream().unordered().filter(x -> x > 100).findFirst().orElseThrow());
        System.out.println("  → unordered() waives the encounter-order guarantee: findFirst may now return any");
        System.out.println("    match, and limit/skip/distinct stop having to buffer to honour a source order");

        // "Non-interfering" means the lambda must not modify the stream's SOURCE while the pipeline runs — not
        // even from a sequential stream. Adding to the backing list inside a map() gives a
        // ConcurrentModificationException at best and a silently truncated result at worst. It is a separate
        // requirement from statelessness ([1]): a lambda can be stateless and still interfere, and vice versa.
        System.out.println("Ex102CorrectnessTraps finished");
    }
}
