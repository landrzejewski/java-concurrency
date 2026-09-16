package pl.training.concurrency.exercises.mod003;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * Exercise 3.1 — Bank transfers with fine-grained locks.
 *
 * <p>Related: Mod003 §1, §2
 */
public final class Ex31BankTransfers {

    private Ex31BankTransfers() {}

    static final class Account {
        final int id;
        private long balance;
        private final Object lock = new Object(); // private: no outside code can ever lock on it

        Account(int id, long balance) {
            this.id = id;
            this.balance = balance;
        }

        Object lock() { return lock; }

        long balance() {
            synchronized (lock) {
                return balance;
            }
        }

        /** Caller must hold lock(). */
        void adjust(long delta) { balance += delta; }

        long unsafeBalance() { return balance; }
    }

    static final class Bank {
        private final boolean ordered;

        Bank(boolean ordered) { this.ordered = ordered; }

        boolean transfer(Account from, Account to, long amount) {
            // Lock ordering: always take the lock of the account with the smaller id first. Two opposite
            // transfers (A->B and B->A) then acquire in the same order and cannot wait for each other.
            Account first = from;
            Account second = to;
            if (ordered && from.id > to.id) {
                first = to;
                second = from;
            }
            synchronized (first.lock()) {
                synchronized (second.lock()) {
                    if (from.unsafeBalance() < amount) {
                        return false;
                    }
                    from.adjust(-amount);
                    to.adjust(amount);
                    return true;
                }
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[1] ordered lock acquisition");
        runTransfers(new Bank(true));
        System.out.println("[2] unordered lock acquisition — expect a deadlock");
        runTransfers(new Bank(false));
        System.out.println("Ex31BankTransfers finished");
    }

    private static void runTransfers(Bank bank) throws InterruptedException {
        List<Account> accounts = IntStream.range(0, 5).mapToObj(i -> new Account(i, 1_000)).toList();
        long totalBefore = accounts.stream().mapToLong(Account::balance).sum();

        // tellers are daemons so a deadlocked run cannot keep the JVM alive
        List<Thread> tellers = IntStream.range(0, 10)
                .mapToObj(i -> Thread.ofPlatform().name("teller-" + i).daemon(true).unstarted(() -> {
                    var random = ThreadLocalRandom.current();
                    for (int n = 0; n < 10_000; n++) {
                        var from = accounts.get(random.nextInt(accounts.size()));
                        var to = accounts.get(random.nextInt(accounts.size()));
                        if (from != to) {
                            bank.transfer(from, to, random.nextLong(1, 50));
                        }
                    }
                }))
                .toList();
        tellers.forEach(Thread::start);

        var threadBean = ManagementFactory.getThreadMXBean();
        long deadline = System.currentTimeMillis() + 5_000;
        while (tellers.stream().anyMatch(Thread::isAlive) && System.currentTimeMillis() < deadline) {
            long[] deadlocked = threadBean.findDeadlockedThreads();
            if (deadlocked != null) {
                System.out.printf(Locale.ROOT, "  DEADLOCK detected, %d threads involved:%n", deadlocked.length);
                for (var info : threadBean.getThreadInfo(deadlocked)) {
                    System.out.printf(Locale.ROOT, "    %s waits for %s held by %s%n",
                            info.getThreadName(), info.getLockName(), info.getLockOwnerName());
                }
                return; // balances cannot be read safely now — the locks are held forever
            }
            Thread.sleep(50);
        }
        for (var teller : tellers) {
            teller.join();
        }
        long totalAfter = accounts.stream().mapToLong(Account::balance).sum();
        System.out.printf(Locale.ROOT, "  total before=%d after=%d -> %s%n",
                totalBefore, totalAfter, totalBefore == totalAfter ? "OK" : "BROKEN");
    }
}
