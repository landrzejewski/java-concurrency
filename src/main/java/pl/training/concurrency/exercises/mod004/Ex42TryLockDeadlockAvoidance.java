package pl.training.concurrency.exercises.mod004;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exercise 4.2 — Deadlock-free resource pair with tryLock and lockInterruptibly.
 *
 * <p>Related: Mod004 §1, §3 (contrast with Mod003 §7)
 */
public final class Ex42TryLockDeadlockAvoidance {

    private Ex42TryLockDeadlockAvoidance() {}

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[1] plain lock() in opposite order");
        var a = new ReentrantLock();
        var b = new ReentrantLock();
        var w1 = Thread.ofPlatform().name("w1").daemon(true).start(() -> plainLock(a, b));
        var w2 = Thread.ofPlatform().name("w2").daemon(true).start(() -> plainLock(b, a));
        w1.join(1_000);
        w2.join(1_000);
        if (w1.isAlive() && w2.isAlive()) {
            System.out.printf(Locale.ROOT, "  deadlock: w1 queued on B=%b, w2 queued on A=%b (both locks held: %b)%n",
                    b.hasQueuedThread(w1), a.hasQueuedThread(w2), a.isLocked() && b.isLocked());
        }
        // a and b stay locked forever by the daemon workers — every next demo uses fresh locks

        System.out.println("[2] tryLock(timeout) with back-off");
        var c = new ReentrantLock();
        var d = new ReentrantLock();
        var retries1 = new AtomicInteger();
        var retries2 = new AtomicInteger();
        var t1 = Thread.ofPlatform().name("t1").start(() -> tryLockWithBackoff(c, d, retries1));
        var t2 = Thread.ofPlatform().name("t2").start(() -> tryLockWithBackoff(d, c, retries2));
        t1.join();
        t2.join();
        System.out.printf(Locale.ROOT, "  retries: t1=%d t2=%d%n", retries1.get(), retries2.get());

        System.out.println("[3] lockInterruptibly() with a supervisor");
        var e = new ReentrantLock();
        var f = new ReentrantLock();
        var i1 = Thread.ofPlatform().name("i1").start(() -> interruptibleLock(e, f));
        var i2 = Thread.ofPlatform().name("i2").start(() -> interruptibleLock(f, e));
        supervise(List.of(i1, i2), List.of(e, f), 1_000);
        i1.join();
        i2.join();
        // With synchronized the same scenario is hopeless: a thread stuck at a monitor entry is BLOCKED, ignores
        // interrupt() completely (Mod003 §7) and there is no timed variant — only the owner releasing the monitor
        // can free it. ReentrantLock gives us tryLock(timeout) and lockInterruptibly() precisely for this case.
        System.out.println("Ex42TryLockDeadlockAvoidance finished");
    }

    static void plainLock(ReentrantLock first, ReentrantLock second) {
        first.lock();
        try {
            sleep(100); // give the other worker time to grab its first lock
            second.lock();
            try {
                System.out.println("  " + Thread.currentThread().getName() + " got both");
            } finally {
                second.unlock();
            }
        } finally {
            first.unlock();
        }
    }

    static void tryLockWithBackoff(ReentrantLock first, ReentrantLock second, AtomicInteger retries) {
        var name = Thread.currentThread().getName();
        try {
            while (true) {
                if (first.tryLock(50, TimeUnit.MILLISECONDS)) {
                    try {
                        sleep(20);
                        if (second.tryLock(50, TimeUnit.MILLISECONDS)) {
                            try {
                                System.out.printf(Locale.ROOT, "  %s got both after %d retries%n", name, retries.get());
                                return;
                            } finally {
                                second.unlock();
                            }
                        }
                    } finally {
                        first.unlock(); // give up what we hold so the other side can make progress
                    }
                }
                retries.incrementAndGet();
                Thread.sleep(ThreadLocalRandom.current().nextInt(1, 20)); // random back-off breaks the lockstep
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    static void interruptibleLock(ReentrantLock first, ReentrantLock second) {
        var name = Thread.currentThread().getName();
        try {
            first.lockInterruptibly();
            try {
                Thread.sleep(100);
                second.lockInterruptibly(); // blocks in the deadlock — but responds to interrupt()
                try {
                    System.out.println("  " + name + " got both");
                } finally {
                    second.unlock();
                }
            } finally {
                first.unlock();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.out.println("  " + name + " cancelled by the supervisor, released everything");
        }
    }

    /** Interrupts the first worker that has been queued on a lock for longer than maxWaitMillis. */
    static void supervise(List<Thread> workers, List<ReentrantLock> locks, long maxWaitMillis)
            throws InterruptedException {
        long[] waitingSince = new long[workers.size()];
        while (workers.stream().anyMatch(Thread::isAlive)) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < workers.size(); i++) {
                var worker = workers.get(i);
                boolean queued = locks.stream().anyMatch(lock -> lock.hasQueuedThread(worker));
                if (!queued) {
                    waitingSince[i] = 0;
                } else if (waitingSince[i] == 0) {
                    waitingSince[i] = now;
                } else if (now - waitingSince[i] > maxWaitMillis) {
                    System.out.println("  supervisor: " + worker.getName() + " stuck > 1 s, interrupting");
                    worker.interrupt();
                    return;
                }
            }
            Thread.sleep(50);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
