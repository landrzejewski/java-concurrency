package pl.training.concurrency.exercises.mod004;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;

/**
 * Exercise 4.3 — Turnstile with two Conditions (bonus: StampedLock optimistic read).
 *
 * <p>Related: Mod004 §2, §6
 */
public final class Ex43Turnstile {

    private Ex43Turnstile() {}

    record Snapshot(int inside, long totalPassed) {}

    static final class Turnstile {
        private final int capacity;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notFull = lock.newCondition();
        private final Condition empty = lock.newCondition();

        // Read by snapshot() without the ReentrantLock: guarded by the StampedLock so that two fields can be
        // read consistently. (For one field a volatile would do; the optimistic read pays off for several.)
        private final StampedLock counters = new StampedLock();
        private int inside;
        private long totalPassed;

        Turnstile(int capacity) { this.capacity = capacity; }

        void enter() throws InterruptedException {
            lock.lock();
            try {
                while (inside == capacity) {
                    notFull.await();
                }
                updateCounters(+1);
            } finally {
                lock.unlock();
            }
        }

        void leave() {
            lock.lock();
            try {
                updateCounters(-1);
                // signal(): exactly one slot was freed and every notFull waiter waits for the same predicate,
                // so waking one is enough — the others would only re-check and go back to sleep.
                notFull.signal();
                if (inside == 0) {
                    empty.signalAll(); // several maintenance threads may wait; all of them must learn
                }
            } finally {
                lock.unlock();
            }
        }

        void awaitEmpty() throws InterruptedException {
            lock.lock();
            try {
                while (inside > 0) {
                    empty.await();
                }
            } finally {
                lock.unlock();
            }
        }

        private void updateCounters(int delta) {
            long stamp = counters.writeLock();
            try {
                inside += delta;
                if (delta > 0) {
                    totalPassed++;
                }
            } finally {
                counters.unlockWrite(stamp);
            }
        }

        Snapshot snapshot() {
            long stamp = counters.tryOptimisticRead();
            int insideCopy = inside;            // copy plain fields only — no method calls, no allocation
            long passedCopy = totalPassed;
            if (!counters.validate(stamp)) {   // a writer intervened: fall back to a real read lock
                stamp = counters.readLock();
                try {
                    insideCopy = inside;
                    passedCopy = totalPassed;
                } finally {
                    counters.unlockRead(stamp);
                }
            }
            return new Snapshot(insideCopy, passedCopy);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        var turnstile = new Turnstile(3);
        var stop = new AtomicBoolean();
        var maxInside = new int[1];
        var monitor = Thread.ofPlatform().name("monitor").start(() -> {
            while (!stop.get()) {
                var snapshot = turnstile.snapshot();
                maxInside[0] = Math.max(maxInside[0], snapshot.inside());
                Thread.onSpinWait();
            }
        });

        var visitors = new ArrayList<Thread>();
        for (int i = 1; i <= 10; i++) {
            visitors.add(Thread.ofPlatform().name("visitor-" + i).unstarted(() -> {
                try {
                    turnstile.enter();
                    Thread.sleep(ThreadLocalRandom.current().nextInt(50, 150));
                    turnstile.leave();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        visitors.forEach(Thread::start);

        Thread.sleep(100);
        var maintenance = Thread.ofPlatform().name("maintenance").start(() -> {
            try {
                turnstile.awaitEmpty();
                System.out.println("  maintenance: turnstile is empty, cleaning");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        for (var visitor : visitors) {
            visitor.join();
        }
        maintenance.join();
        stop.set(true);
        monitor.join();

        var snapshot = turnstile.snapshot();
        System.out.printf(Locale.ROOT, "  max inside observed=%d (capacity 3), total passed=%d, inside now=%d -> %s%n",
                maxInside[0], snapshot.totalPassed(), snapshot.inside(),
                maxInside[0] <= 3 && snapshot.totalPassed() == 10 && snapshot.inside() == 0 ? "OK" : "BROKEN");
        System.out.println("Ex43Turnstile finished");
    }
}
