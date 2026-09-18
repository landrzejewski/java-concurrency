package pl.training.concurrency.exercises.mod014;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exercise 14.2 — Livelock and starvation under measurement.
 *
 * <p>Related: Mod014 §4, §5
 *
 * <p>Both experiments are time-boxed rather than iteration-boxed; the numbers are illustrations of a
 * ratio, not benchmarks.
 */
public final class Ex142LivelockAndStarvation {

    private Ex142LivelockAndStarvation() {}

    private static final long BUDGET_NANOS = 300_000_000L;   // 300 ms per experiment

    // ---------------------------------------------------------------- livelock

    /** The shared resource. Ownership is guarded by the object's own monitor. */
    private static final class Spoon {
        private Diner owner;
        Spoon(Diner owner) { this.owner = owner; }
        synchronized Diner owner() { return owner; }
        synchronized void passTo(Diner diner) { owner = diner; }
    }

    private static final class Diner {
        private final String name;
        private volatile boolean hungry = true;
        Diner(String name) { this.name = name; }

        /**
         * Eat, deferring to a still-hungry partner. With {@code breakSymmetry == false} both diners defer
         * unconditionally and neither ever eats — that is the livelock.
         */
        void eatWith(Spoon spoon, Diner partner, boolean breakSymmetry,
                     AtomicInteger passes, AtomicInteger meals, long deadlineNanos) {
            while (hungry && System.nanoTime() < deadlineNanos) {
                if (spoon.owner() != this) {
                    Thread.onSpinWait();               // not my turn — spin politely
                    continue;
                }
                if (partner.hungry && (!breakSymmetry || ThreadLocalRandom.current().nextBoolean())) {
                    passes.incrementAndGet();          // "no, you first"
                    spoon.passTo(partner);
                    continue;
                }
                meals.incrementAndGet();
                hungry = false;
                spoon.passTo(partner);
            }
        }

        @Override public String toString() { return name; }
    }

    private record DinnerResult(int meals, int passes, String threadStates) {}

    private static DinnerResult dine(boolean breakSymmetry) throws InterruptedException {
        var alphonse = new Diner("Alphonse");
        var gaston = new Diner("Gaston");
        var spoon = new Spoon(alphonse);
        var passes = new AtomicInteger();
        var meals = new AtomicInteger();
        long deadline = System.nanoTime() + BUDGET_NANOS;

        var t1 = Thread.ofPlatform().name("Alphonse")
                .start(() -> alphonse.eatWith(spoon, gaston, breakSymmetry, passes, meals, deadline));
        var t2 = Thread.ofPlatform().name("Gaston")
                .start(() -> gaston.eatWith(spoon, alphonse, breakSymmetry, passes, meals, deadline));

        Thread.sleep(100);                              // sample the states mid-run
        String states = t1.getName() + "=" + t1.getState() + ", " + t2.getName() + "=" + t2.getState();

        t1.join(); t2.join();
        return new DinnerResult(meals.get(), passes.get(), states);
    }

    // ---------------------------------------------------------------- starvation

    private static Map<String, Integer> contendFor(ReentrantLock lock) throws InterruptedException {
        var counts = new ConcurrentHashMap<String, Integer>();
        long deadline = System.nanoTime() + BUDGET_NANOS;
        var workers = new Thread[6];
        for (int i = 0; i < workers.length; i++) {
            String name = "w-" + i;
            counts.put(name, 0);
            workers[i] = new Thread(() -> {
                while (System.nanoTime() < deadline) {
                    lock.lock();
                    try { counts.merge(Thread.currentThread().getName(), 1, Integer::sum); }
                    finally { lock.unlock(); }
                }
            }, name);
        }
        for (Thread worker : workers) worker.start();
        for (Thread worker : workers) worker.join();
        return new TreeMap<>(counts);
    }

    private static void report(String label, Map<String, Integer> counts) {
        int min = counts.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        long total = counts.values().stream().mapToInt(Integer::intValue).sum();
        System.out.printf(Locale.ROOT, "  %s total=%,10d  min=%,8d  max=%,8d  max/min=%.1fx%n",
                label, total, min, max, min == 0 ? Double.POSITIVE_INFINITY : (double) max / min);
        System.out.println("      " + counts);
    }

    public static void main(String[] args) throws InterruptedException {

        System.out.println("[1] livelock: both diners always defer");
        DinnerResult stuck = dine(false);
        System.out.printf(Locale.ROOT, "  meals eaten = %d/2, courtesy hand-offs = %,d in 300 ms%n",
                stuck.meals(), stuck.passes());
        System.out.println("  thread states mid-run: " + stuck.threadStates());
        System.out.println("  → RUNNABLE, not BLOCKED. A deadlocked JVM announces itself: jstack prints");
        System.out.println("    \"Found one Java-level deadlock\" and findDeadlockedThreads() returns the cycle.");
        System.out.println("    A livelocked one looks like a busy, healthy process burning 100% CPU — no tool");
        System.out.println("    reports it, because every individual step really is making local progress.");

        System.out.println("[2] break the symmetry: defer only on a coin flip");
        DinnerResult cured = dine(true);
        System.out.printf(Locale.ROOT, "  meals eaten = %d/2, courtesy hand-offs = %,d%n",
                cured.meals(), cured.passes());
        System.out.println("  → the essential ingredient of livelock is SYMMETRY: both parties answer the same");
        System.out.println("    condition with the same concession. Randomness breaks the tie within a few rounds.");
        // The identical shape appears with locks: two workers that tryLock a pair of resources, release
        // everything on failure and retry after the SAME fixed back-off will keep colliding in lockstep
        // forever. The cure is the same one — randomised jitter on the back-off (Exercise 4.2).

        System.out.println("[3] starvation: 6 threads, 300 ms window, unfair vs fair");
        report("unfair (default, barging)", contendFor(new ReentrantLock(false)));
        report("fair    (FIFO hand-off)  ", contendFor(new ReentrantLock(true)));
        System.out.println("  → barging maximises THROUGHPUT: the thread that just released the lock is usually");
        System.out.println("    the one that wins the race to re-acquire it — its data is still in cache and it");
        System.out.println("    never had to park. A handful of threads monopolise the lock and the rest starve.");
        System.out.println("    Fairness maximises EVENNESS: FIFO hand-off, but every hand-off is a park/unpark");
        System.out.println("    pair, so total throughput drops by an order of magnitude. Pick per workload.");

        // Why the window must be time-boxed: give each worker a fixed ITERATION COUNT instead and the counts
        // come out equal BY CONSTRUCTION — every thread does exactly N acquisitions before it exits, so the
        // unfair run and the fair run print the same distribution and the effect vanishes from the output.
        // Starvation is about who gets the lock PER UNIT TIME, so time has to be the thing held constant.
        // Other cures: Semaphore(N, true), partitioning so contention is local, or removing the shared lock
        // entirely (Mod002 atomics, Mod005 concurrent collections).
        System.out.println("Ex142LivelockAndStarvation finished");
    }
}
