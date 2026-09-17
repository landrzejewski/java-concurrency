package pl.training.concurrency.exercises.mod006;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exercise 6.2 — Parallel benchmark harness with CountDownLatch.
 *
 * <p>Related: Mod006 §2
 */
public final class Ex62LatchHarness {

    private Ex62LatchHarness() {}

    /**
     * Runs task on 'threads' threads that all start at the same instant and returns the wall time of the batch.
     * Returns -1 (and prints the stragglers) when the batch does not finish within the timeout.
     */
    static long runConcurrently(int threads, Runnable task, Duration timeout) throws InterruptedException {
        var ready = new CountDownLatch(threads);   // every worker is created and waiting
        var startGun = new CountDownLatch(1);      // fired once by the harness
        var finished = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            workers.add(Thread.ofPlatform().name("bench-" + i).daemon(true).start(() -> {
                ready.countDown();
                try {
                    startGun.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }));
        }
        ready.await();
        long t0 = System.nanoTime();
        startGun.countDown();
        if (!finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            var alive = workers.stream().filter(Thread::isAlive).map(Thread::getName).toList();
            System.out.println("  timeout after " + timeout.toMillis() + " ms, still running: " + alive);
            return -1;
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    static final class SynchronizedCounter {
        private long value;
        synchronized void increment() { value++; }
        synchronized long get() { return value; }
    }

    public static void main(String[] args) throws InterruptedException {
        final int threads = 16;
        final int increments = 1_000_000;

        System.out.println("[1] synchronized vs AtomicLong, 16 threads x 1M increments");
        var sync = new SynchronizedCounter();
        long syncMillis = runConcurrently(threads, () -> {
            for (int i = 0; i < increments; i++) {
                sync.increment();
            }
        }, Duration.ofSeconds(30));
        var atomic = new AtomicLong();
        long atomicMillis = runConcurrently(threads, () -> {
            for (int i = 0; i < increments; i++) {
                atomic.incrementAndGet();
            }
        }, Duration.ofSeconds(30));
        System.out.printf(Locale.ROOT, "  synchronized: %d ms (value %d)%n", syncMillis, sync.get());
        System.out.printf(Locale.ROOT, "  AtomicLong:   %d ms (value %d)%n", atomicMillis, atomic.get());

        System.out.println("[2] timeout handling");
        long result = runConcurrently(4, () -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Duration.ofMillis(500));
        System.out.println("  result: " + result);

        // A CountDownLatch counts down to zero and stays there — there is no reset, so a second batch needs a
        // fresh latch. For repeated rounds with the same parties use a CyclicBarrier (auto-reset when all
        // parties arrive) or a Phaser (reusable and with dynamic party registration).
        System.out.println("Ex62LatchHarness finished");
    }
}
