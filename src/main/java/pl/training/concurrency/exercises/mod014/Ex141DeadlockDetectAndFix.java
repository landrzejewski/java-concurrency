package pl.training.concurrency.exercises.mod014;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exercise 14.1 — Deadlock: reproduce, detect, prevent.
 *
 * <p>Related: Mod014 §2, §3
 */
public final class Ex141DeadlockDetectAndFix {

    private Ex141DeadlockDetectAndFix() {}

    static final class Account {
        final long id;
        final Object lock = new Object();
        long balance;
        Account(long id, long balance) { this.id = id; this.balance = balance; }
    }

    /** Broken by tie-breaking hash collisions only; see {@link #transfer}. */
    private static final Object TIE_BREAKER = new Object();

    public static void main(String[] args) throws InterruptedException {

        System.out.println("[1] reproduce: two threads, two monitors, opposite order");
        var lockA = new Object();
        var lockB = new Object();
        // Daemon threads: they stay stuck forever, and main must still be able to finish.
        var t1 = Thread.ofPlatform().name("T1-A-then-B").daemon(true).start(() -> {
            synchronized (lockA) {
                sleep(50);
                synchronized (lockB) { throw new AssertionError("unreachable"); }
            }
        });
        var t2 = Thread.ofPlatform().name("T2-B-then-A").daemon(true).start(() -> {
            synchronized (lockB) {
                sleep(50);
                synchronized (lockA) { throw new AssertionError("unreachable"); }
            }
        });
        Thread.sleep(300);
        System.out.println("  " + t1.getName() + " state = " + t1.getState());
        System.out.println("  " + t2.getName() + " state = " + t2.getState());

        System.out.println("[2] detect it from inside the JVM");
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] deadlocked = threads.findDeadlockedThreads();     // monitors AND ownable synchronizers
        if (deadlocked == null) {
            System.out.println("  findDeadlockedThreads() returned null — the cycle had not formed yet");
        } else {
            for (ThreadInfo info : threads.getThreadInfo(deadlocked, true, true)) {
                LockInfo waitingOn = info.getLockInfo();
                System.out.printf(Locale.ROOT, "  \"%s\" is %s waiting on %s, held by \"%s\"%n",
                        info.getThreadName(), info.getThreadState(), waitingOn, info.getLockOwnerName());
            }
            System.out.println("  → findDeadlockedThreads() also covers ReentrantLock etc.;");
            System.out.println("    findMonitorDeadlockedThreads() sees only intrinsic monitors");
        }

        System.out.println("[3] the same cycle from outside the JVM");
        long pid = ProcessHandle.current().pid();
        System.out.println("  PID = " + pid);
        System.out.println("  jstack " + pid + "        # or: kill -3 " + pid + "   (dump goes to stdout)");
        System.out.println("  jstack prints the header line:  Found one Java-level deadlock:");
        System.out.println("  followed by \"Thread-N\": waiting to lock monitor 0x…, which is held by \"Thread-M\"");

        System.out.println("[4] prevent it: one global lock order");
        var accounts = new Account[] {
                new Account(1, 1_000), new Account(2, 1_000), new Account(3, 1_000),
                new Account(4, 1_000), new Account(5, 1_000)
        };
        long expectedTotal = total(accounts);
        var collisions = new AtomicLong();
        int transfersPerThread = 10_000;

        var workers = new Thread[10];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = Thread.ofPlatform().name("transfer-" + i).unstarted(() -> {
                var random = ThreadLocalRandom.current();
                for (int n = 0; n < transfersPerThread; n++) {
                    Account from = accounts[random.nextInt(accounts.length)];
                    Account to   = accounts[random.nextInt(accounts.length)];
                    if (from != to) transfer(from, to, random.nextInt(1, 50), collisions);
                }
            });
        }
        long t0 = System.nanoTime();
        for (Thread worker : workers) worker.start();
        for (Thread worker : workers) worker.join();
        long millis = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf(Locale.ROOT, "  %,d opposite-direction transfers in %,d ms, no deadlock%n",
                (long) workers.length * transfersPerThread, millis);
        System.out.printf(Locale.ROOT, "  total before = %,d, after = %,d (%s), hash collisions = %d%n",
                expectedTotal, total(accounts),
                expectedTotal == total(accounts) ? "unchanged" : "CORRUPTED", collisions.get());
        if (expectedTotal != total(accounts)) throw new AssertionError("money was created or destroyed");
        long[] stillDeadlocked = threads.findDeadlockedThreads();
        System.out.println("  ordered transfers contributed no new cycle (the only deadlocked threads are still "
                + (stillDeadlocked == null ? 0 : stillDeadlocked.length) + ": the two from [1])");

        // Why you cannot rescue [1] once it has happened: a thread blocked on ENTERING a synchronized block is
        // not in a waiting state the JVM can interrupt — interrupt() just sets the flag and the thread stays
        // BLOCKED (Mod003 §7). Thread.stop() was removed. Nothing inside the JVM can break the cycle, which is
        // why the only real answer is prevention.
        // ReentrantLock.tryLock(timeout) (Mod004 §5) changes that: the acquisition can FAIL, so a worker that
        // cannot get the second lock releases the first, backs off by a random interval and retries. That
        // converts a permanent deadlock into a bounded retry loop — at the cost of having to write the
        // release/back-off/retry logic and to handle the give-up case.
        System.out.println("Ex141DeadlockDetectAndFix finished");
    }

    /**
     * Locks both accounts in one global order — by {@code System.identityHashCode} — so no cycle can form,
     * whatever order the caller passes them in.
     */
    private static void transfer(Account from, Account to, long amount, AtomicLong collisions) {
        int fromHash = System.identityHashCode(from.lock);
        int toHash = System.identityHashCode(to.lock);

        if (fromHash < toHash) {
            lockBothAndMove(from.lock, to.lock, from, to, amount);
        } else if (fromHash > toHash) {
            lockBothAndMove(to.lock, from.lock, from, to, amount);
        } else {
            // Identity hashes tied: there is no order to impose, so serialise these rare transfers on a
            // third, global mutex. Rare enough not to matter for throughput, necessary for correctness.
            collisions.incrementAndGet();
            synchronized (TIE_BREAKER) {
                lockBothAndMove(from.lock, to.lock, from, to, amount);
            }
        }
    }

    private static void lockBothAndMove(Object first, Object second, Account from, Account to, long amount) {
        synchronized (first) {
            synchronized (second) {
                if (from.balance >= amount) {
                    from.balance -= amount;
                    to.balance += amount;
                }
            }
        }
    }

    private static long total(Account[] accounts) {
        long sum = 0;
        for (Account account : accounts) {
            synchronized (account.lock) { sum += account.balance; }
        }
        return sum;
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
