package pl.training.concurrency.exercises.mod014;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exercise 14.3 — Race-condition checklist and testing concurrent code.
 *
 * <p>Related: the race-shape table in the Mod014 header, and Mod014 §6
 *
 * <pre>
 *   Shape                    Example                                  Fix
 *   ------------------------ ---------------------------------------- --------------------------------------
 *   Read-modify-write        count++, total += x                      Atomic* / synchronized
 *   Check-then-act           if (!map.containsKey(k)) map.put(k, v)   putIfAbsent / computeIfAbsent
 *   Get-then-put on counter  m.put(k, m.getOrDefault(k, 0) + 1)       merge(k, 1L, Long::sum)
 *   Compound state           two fields must stay consistent          one lock around the WHOLE update
 * </pre>
 */
public final class Ex143RaceChecklist {

    private Ex143RaceChecklist() {}

    private static final int THREADS = 8;
    private static final int ITERATIONS = 50_000;

    public static void main(String[] args) throws InterruptedException {

        System.out.println("[1] read-modify-write: count++");
        var broken = new PlainCounter();
        runConcurrently(() -> { for (int i = 0; i < ITERATIONS; i++) broken.increment(); });
        long expected = (long) THREADS * ITERATIONS;
        System.out.printf(Locale.ROOT, "  broken: %,d of %,d  (lost %,d updates — count++ is read, add, write)%n",
                broken.value(), expected, expected - broken.value());
        var fixed = new AtomicLong();
        runConcurrently(() -> { for (int i = 0; i < ITERATIONS; i++) fixed.incrementAndGet(); });
        System.out.printf(Locale.ROOT, "  fixed : %,d of %,d  (AtomicLong.incrementAndGet)%n",
                fixed.get(), expected);

        System.out.println("[2] check-then-act: containsKey followed by put");
        var registrations = new ConcurrentHashMap<String, String>();
        var brokenWinners = new ConcurrentHashMap<String, Long>();
        runConcurrentlyIndexed(id -> {
            for (int i = 0; i < 200; i++) {
                String key = "session-" + i;
                if (!registrations.containsKey(key)) {       // gap: another thread can slip in right here
                    registrations.put(key, "owner-" + id);
                    brokenWinners.merge(key, 1L, Long::sum); // how many threads believed THEY created it
                }
            }
        });
        long doubleCreated = brokenWinners.values().stream().filter(v -> v > 1).count();
        System.out.printf(Locale.ROOT, "  broken: %,d of %,d keys were \"created\" by more than one thread%n",
                doubleCreated, brokenWinners.size());

        var safeRegistrations = new ConcurrentHashMap<String, String>();
        var safeWinners = new ConcurrentHashMap<String, Long>();
        runConcurrentlyIndexed(id -> {
            for (int i = 0; i < 200; i++) {
                String key = "session-" + i;
                if (safeRegistrations.putIfAbsent(key, "owner-" + id) == null) {
                    safeWinners.merge(key, 1L, Long::sum);
                }
            }
        });
        long safeDouble = safeWinners.values().stream().filter(v -> v > 1).count();
        System.out.printf(Locale.ROOT, "  fixed : %,d keys with more than one creator (putIfAbsent is atomic)%n",
                safeDouble);
        if (safeDouble != 0) throw new AssertionError("putIfAbsent must elect exactly one winner");

        System.out.println("[3] get-then-put on a counter");
        var brokenCounts = new ConcurrentHashMap<String, Long>();
        runConcurrently(() -> {
            for (int i = 0; i < ITERATIONS; i++) {
                String key = "k-" + (i % 4);
                brokenCounts.put(key, brokenCounts.getOrDefault(key, 0L) + 1L);   // three separate operations
            }
        });
        long brokenTotal = brokenCounts.values().stream().mapToLong(Long::longValue).sum();
        System.out.printf(Locale.ROOT, "  broken: %,d of %,d  (a ConcurrentHashMap does NOT make get+put atomic)%n",
                brokenTotal, expected);

        var mergedCounts = new ConcurrentHashMap<String, Long>();
        runConcurrently(() -> {
            for (int i = 0; i < ITERATIONS; i++) {
                mergedCounts.merge("k-" + (i % 4), 1L, Long::sum);
            }
        });
        long mergedTotal = mergedCounts.values().stream().mapToLong(Long::longValue).sum();
        System.out.printf(Locale.ROOT, "  fixed : %,d of %,d  (merge does it in one atomic bin operation)%n",
                mergedTotal, expected);

        System.out.println("[4] compound state: two fields that must agree");
        var brokenRange = new UnsafeRange();
        var brokenViolations = new AtomicLong();
        runConcurrently(() -> {
            for (int i = 0; i < ITERATIONS; i++) {
                brokenRange.set(i, i + 10);
                if (!brokenRange.isConsistent()) brokenViolations.incrementAndGet();
            }
        });
        System.out.printf(Locale.ROOT, "  broken: %,d reads saw lower > upper (each setter is atomic, the PAIR is not)%n",
                brokenViolations.get());

        var safeRange = new SafeRange();
        var safeViolations = new AtomicLong();
        runConcurrently(() -> {
            for (int i = 0; i < ITERATIONS; i++) {
                safeRange.set(i, i + 10);
                if (!safeRange.isConsistent()) safeViolations.incrementAndGet();
            }
        });
        System.out.printf(Locale.ROOT, "  fixed : %,d violations (one lock around the WHOLE update and the read)%n",
                safeViolations.get());
        if (safeViolations.get() != 0) throw new AssertionError("the invariant must hold");
        System.out.println("  → note the fix is NOT 'make each field volatile/atomic'. The invariant spans two");
        System.out.println("    fields, so the critical section has to span both writes and both reads.");

        System.out.println("[5] testing this");
        System.out.println("  JUnit  : src/test/java/pl/training/concurrency/extras/tests/LostUpdateTest.java");
        System.out.println("  jcstress: src/jcstress/java/pl/training/concurrency/CounterTest.java");
        System.out.println("    ./mvnw -P jcstress package");
        System.out.println("    java --enable-preview -jar target/jcstress.jar \\");
        System.out.println("         -jvmArgs \"--enable-preview\" -t CounterTest");
        // A plain JUnit test runs the racy code once, on whatever schedule the OS happens to produce. It can
        // OBSERVE a lost update — [1] above is exactly that test, and it fails loudly — but it can never prove
        // the fixed version is race-free: passing only means this particular interleaving did not break, and
        // the bug you are hunting may need a schedule that a JUnit run never produces (and that a strongly
        // ordered x86 never produces at all, while an ARM machine produces it on the first try).
        // jcstress attacks the problem from the other side: it generates the interleavings deliberately, runs
        // the actor methods millions of times in specially controlled threads, and enumerates every OUTCOME it
        // observed — so "the (1, 0) result never appeared in 200 million runs" is evidence of a kind no
        // functional test can produce.

        // Priority inversion, and why priorities must not carry correctness: a high-priority thread waits for a
        // lock held by a low-priority one, while a medium-priority thread that neither holds nor needs the lock
        // keeps preempting the holder — the high-priority thread effectively runs at the medium priority. A
        // real-time OS fixes this with priority INHERITANCE (the holder temporarily inherits the waiter's
        // priority). HotSpot does not implement it: Thread.setPriority() is a hint that the OS scheduler is free
        // to ignore entirely, and on several platforms it does. Use bounded queues, fair locks and explicit
        // deadlines to express what must happen first.
        System.out.println("Ex143RaceChecklist finished");
    }

    // ---------------------------------------------------------------- subjects

    static final class PlainCounter {
        private long count;                                  // no volatile, no atomic, no lock
        void increment() { count++; }                        // read, add, write — three steps
        long value() { return count; }
    }

    /** Two fields with an invariant (lower <= upper) and no lock spanning them. */
    static final class UnsafeRange {
        private volatile int lower;
        private volatile int upper = 10;
        void set(int lower, int upper) { this.lower = lower; this.upper = upper; }
        boolean isConsistent() { return lower <= upper; }
    }

    static final class SafeRange {
        private final Object lock = new Object();
        private int lower;
        private int upper = 10;
        void set(int lower, int upper) {
            synchronized (lock) { this.lower = lower; this.upper = upper; }
        }
        boolean isConsistent() {
            synchronized (lock) { return lower <= upper; }
        }
    }

    // ---------------------------------------------------------------- harness

    private static void runConcurrently(Runnable task) throws InterruptedException {
        runConcurrentlyIndexed(id -> task.run());
    }

    /** Starts every thread from the same start gun so the contention window is as wide as possible. */
    private static void runConcurrentlyIndexed(java.util.function.IntConsumer task) throws InterruptedException {
        var startGun = new CountDownLatch(1);
        var done = new CountDownLatch(THREADS);
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                final int id = i;
                pool.execute(() -> {
                    try {
                        startGun.await();
                        task.accept(id);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGun.countDown();
            done.await();
        }
    }
}
