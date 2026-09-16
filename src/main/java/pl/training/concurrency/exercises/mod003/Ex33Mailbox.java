package pl.training.concurrency.exercises.mod003;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercise 3.3 — One-slot mailbox with wait/notify.
 *
 * <p>Related: Mod003 §4–§6
 */
public final class Ex33Mailbox {

    private Ex33Mailbox() {}

    static final class Mailbox<T> {
        private T slot; // null == empty

        synchronized void put(T item) throws InterruptedException {
            while (slot != null) {
                wait();      // while, not if: wakeups may be spurious, or another producer may have refilled the slot
            }
            slot = item;
            notifyAll();     // one wait set for producers and consumers, so wake everybody
        }

        synchronized T take() throws InterruptedException {
            while (slot == null) {
                wait();
            }
            T item = slot;
            slot = null;
            notifyAll();
            return item;
        }

        synchronized Optional<T> take(long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (slot == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return Optional.empty();
                }
                wait(remaining); // may return early (spurious wakeup, notifyAll meant for a producer) → recompute
            }
            T item = slot;
            slot = null;
            notifyAll();
            return Optional.of(item);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        var mailbox = new Mailbox<Integer>();
        final int producers = 2;
        final int consumers = 3;
        final int perProducer = 1_000;
        final int total = producers * perProducer;
        var received = new ConcurrentHashMap<Integer, Integer>();
        var receivedCount = new AtomicInteger();
        var threads = new ArrayList<Thread>();

        for (int p = 0; p < producers; p++) {
            int base = p * perProducer;
            threads.add(new Thread(() -> {
                try {
                    for (int i = 0; i < perProducer; i++) {
                        mailbox.put(base + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "producer-" + p));
        }
        for (int c = 0; c < consumers; c++) {
            threads.add(new Thread(() -> {
                try {
                    while (receivedCount.get() < total) {
                        var item = mailbox.take(100);
                        if (item.isEmpty()) {
                            continue; // timed out — re-check the termination condition
                        }
                        received.merge(item.get(), 1, Integer::sum);
                        receivedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "consumer-" + c));
        }
        threads.forEach(Thread::start);
        for (var thread : threads) {
            thread.join();
        }

        long duplicates = received.values().stream().filter(n -> n > 1).count();
        System.out.printf(Locale.ROOT, "received %d distinct messages of %d, duplicates=%d, total=%d -> %s%n",
                received.size(), total, duplicates, receivedCount.get(),
                received.size() == total && duplicates == 0 ? "OK" : "BROKEN");
        System.out.println("take(50) on an empty mailbox: " + mailbox.take(50));

        // Why 'if' breaks it: a put() calls notifyAll(), which wakes all three consumers. The first one to
        // re-acquire the monitor empties the slot. The second, having used 'if' instead of 'while', skips the
        // re-check, reads slot == null and returns null (or throws) — a stolen wakeup. Spurious wakeups cause
        // the same failure with no producer involved at all. 'while' simply goes back to waiting.
        System.out.println("Ex33Mailbox finished");
    }
}
