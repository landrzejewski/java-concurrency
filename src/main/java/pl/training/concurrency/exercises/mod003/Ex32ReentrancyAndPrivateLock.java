package pl.training.concurrency.exercises.mod003;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exercise 3.2 — Reentrancy and the private-lock idiom.
 *
 * <p>Related: Mod003 §2, §3
 */
public final class Ex32ReentrancyAndPrivateLock {

    private Ex32ReentrancyAndPrivateLock() {}

    /** The exercise says 5 s; shortened to keep the demo quick — the effect is the same. */
    static final long HOSTILE_HOLD_MS = 1_000;

    /** Locks on 'this': the monitor is public, so any client holding a reference can hold it too. */
    static class Counters {
        private final Map<String, Integer> counts = new HashMap<>();

        Counters(List<String> names) {
            names.forEach(name -> counts.put(name, 0));
        }

        synchronized void increment(String name) {
            System.out.printf(Locale.ROOT, "    increment(%s) holdsLock(this)=%b%n", name, Thread.holdsLock(this));
            counts.merge(name, 1, Integer::sum);
        }

        synchronized void incrementAll() {
            System.out.printf(Locale.ROOT, "  incrementAll holdsLock(this)=%b%n", Thread.holdsLock(this));
            counts.keySet().forEach(this::increment); // re-enters the monitor we already own — no deadlock
        }

        synchronized int get(String name) {
            return counts.get(name);
        }
    }

    /** Same behaviour, but the monitor is a private object nobody outside can reach. */
    static final class SafeCounters {
        private final Object lock = new Object();
        private final Map<String, Integer> counts = new HashMap<>();

        SafeCounters(List<String> names) {
            names.forEach(name -> counts.put(name, 0));
        }

        void increment(String name) {
            synchronized (lock) {
                counts.merge(name, 1, Integer::sum);
            }
        }

        void incrementAll() {
            synchronized (lock) {
                counts.keySet().forEach(this::increment); // reentrant on the private lock as well
            }
        }

        int get(String name) {
            synchronized (lock) {
                return counts.get(name);
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        var names = List.of("a", "b");

        System.out.println("[1] reentrancy");
        var counters = new Counters(names);
        counters.incrementAll();
        System.out.println("  a=" + counters.get("a") + " b=" + counters.get("b"));

        System.out.println("[2] hostile client vs synchronized(this)");
        long blocked = measureBlocking(
                () -> { synchronized (counters) { sleep(HOSTILE_HOLD_MS); } },
                () -> counters.increment("a"));
        System.out.printf(Locale.ROOT, "  increment() was blocked for %d ms%n", blocked);

        System.out.println("[3] hostile client vs private lock");
        var safe = new SafeCounters(names);
        blocked = measureBlocking(
                () -> { synchronized (safe) { sleep(HOSTILE_HOLD_MS); } },
                () -> safe.increment("a"));
        System.out.printf(Locale.ROOT, "  increment() was blocked for %d ms%n", blocked);

        // Exposing the monitor (synchronized methods / synchronized(this)) makes the lock part of the public API:
        // any code with a reference can block every method of the object for as long as it likes, or drag the
        // object into a lock-ordering cycle it knows nothing about. A private lock object keeps the locking
        // policy encapsulated inside the class that owns the state.
        System.out.println("Ex32ReentrancyAndPrivateLock finished");
    }

    private static long measureBlocking(Runnable hostile, Runnable victim) throws InterruptedException {
        var hostileThread = new Thread(hostile, "hostile");
        hostileThread.start();
        Thread.sleep(100); // make sure the hostile thread holds the monitor before the victim arrives
        long t0 = System.nanoTime();
        var victimThread = new Thread(victim, "victim");
        victimThread.start();
        victimThread.join();
        long millis = (System.nanoTime() - t0) / 1_000_000;
        hostileThread.join();
        return millis;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
