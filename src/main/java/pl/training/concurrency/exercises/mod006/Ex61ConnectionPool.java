package pl.training.concurrency.exercises.mod006;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercise 6.1 — Connection pool with Semaphore.
 *
 * <p>Related: Mod006 §1
 */
public final class Ex61ConnectionPool {

    private Ex61ConnectionPool() {}

    record Connection(int id) {}

    static final class ConnectionPool {
        private final Semaphore permits;                                  // counts free connections
        private final ConcurrentLinkedQueue<Connection> free = new ConcurrentLinkedQueue<>(); // holds them

        ConnectionPool(int size) {
            permits = new Semaphore(size, true);
            for (int i = 1; i <= size; i++) {
                free.add(new Connection(i));
            }
        }

        Optional<Connection> acquire(Duration timeout) throws InterruptedException {
            if (!permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
            return Optional.of(free.poll()); // never null: a permit guarantees a free connection is queued
        }

        /** Semaphores have no owner — any thread may release, which is exactly what a hand-off needs. */
        void release(Connection connection) {
            free.add(connection);   // put it back first ...
            permits.release();      // ... then publish the permit, so acquire() always finds a connection
        }
    }

    public static void main(String[] args) throws InterruptedException {
        final int poolSize = 3;
        var pool = new ConnectionPool(poolSize);
        var inUse = new AtomicInteger();
        var maxInUse = new AtomicInteger();
        var succeeded = new AtomicInteger();
        var timedOut = new AtomicInteger();

        // connections are released by a different thread than the one that acquired them
        try (ExecutorService releaser = Executors.newSingleThreadExecutor(r -> new Thread(r, "releaser"))) {
            var workers = new ArrayList<Thread>();
            for (int i = 1; i <= 20; i++) {
                workers.add(Thread.ofPlatform().name("worker-" + i).unstarted(() -> {
                    try {
                        var connection = pool.acquire(Duration.ofMillis(500));
                        if (connection.isEmpty()) {
                            timedOut.incrementAndGet();
                            return;
                        }
                        maxInUse.accumulateAndGet(inUse.incrementAndGet(), Math::max);
                        Thread.sleep(ThreadLocalRandom.current().nextInt(100, 300)); // "use" the connection
                        inUse.decrementAndGet();
                        succeeded.incrementAndGet();
                        releaser.execute(() -> pool.release(connection.get()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            workers.forEach(Thread::start);
            for (var worker : workers) {
                worker.join();
            }
        }

        System.out.printf(Locale.ROOT, "succeeded=%d timedOut=%d maxInUse=%d (limit %d) -> %s%n",
                succeeded.get(), timedOut.get(), maxInUse.get(), poolSize,
                maxInUse.get() <= poolSize && succeeded.get() + timedOut.get() == 20 ? "OK" : "BROKEN");
        System.out.println("Ex61ConnectionPool finished");
    }
}
