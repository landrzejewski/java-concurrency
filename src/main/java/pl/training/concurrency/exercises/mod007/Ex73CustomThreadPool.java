package pl.training.concurrency.exercises.mod007;

import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercise 7.3 — Custom ThreadPoolExecutor with back-pressure and graceful shutdown.
 *
 * <p>Related: Mod007 §7, §8, §9
 */
public final class Ex73CustomThreadPool {

    private Ex73CustomThreadPool() {}

    static ThreadFactory namedFactory(String prefix) {
        var counter = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + "-" + counter.incrementAndGet());
    }

    static ThreadPoolExecutor newPool(RejectedExecutionHandler handler) {
        return new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10), namedFactory("worker"), handler);
    }

    static Runnable task(int id, long millis) {
        return () -> {
            System.out.printf(Locale.ROOT, "  task %2d on %s%n", id, Thread.currentThread().getName());
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.printf(Locale.ROOT, "  task %2d interrupted%n", id);
            }
        };
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[1] CallerRunsPolicy: 30 tasks x 300 ms");
        var pool = newPool(new ThreadPoolExecutor.CallerRunsPolicy());
        for (int id = 1; id <= 30; id++) {
            pool.execute(task(id, 300));
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
        // Order of decisions for each execute(): (1) fewer than core threads -> create worker-1, worker-2;
        // (2) core full -> queue, so tasks 3..12 fill the 10 slots; (3) queue full and fewer than max threads ->
        // worker-3 and worker-4 are created for tasks 13 and 14; (4) queue full and max reached -> rejection
        // handler; CallerRunsPolicy runs task 15 on the main thread, which cannot submit meanwhile — back-pressure.

        System.out.println("[2] AbortPolicy: count rejections");
        pool = newPool(new ThreadPoolExecutor.AbortPolicy());
        int rejected = 0;
        for (int id = 1; id <= 30; id++) {
            try {
                pool.execute(task(id, 300));
            } catch (RejectedExecutionException e) {
                rejected++;
            }
        }
        System.out.printf(Locale.ROOT, "  rejected %d of 30 (2 running + 10 queued + 2 extra workers = 14 accepted)%n", rejected);
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);

        System.out.println("[3] graceful shutdown");
        var slowPool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), namedFactory("slow"));
        for (int id = 1; id <= 20; id++) {
            slowPool.execute(task(id, 300));
        }
        slowPool.shutdown();                                        // stop accepting, let queued tasks run ...
        if (!slowPool.awaitTermination(1, TimeUnit.SECONDS)) {      // ... but only for so long
            var neverStarted = slowPool.shutdownNow();              // interrupt workers, drain the queue
            System.out.printf(Locale.ROOT, "  shutdownNow: %d tasks never started%n", neverStarted.size());
        }
        slowPool.awaitTermination(1, TimeUnit.SECONDS);
        System.out.println("  terminated: " + slowPool.isTerminated());

        System.out.println("[4] scheduled heartbeat");
        try (var scheduler = Executors.newSingleThreadScheduledExecutor(namedFactory("heartbeat"))) {
            var runs = new AtomicInteger();
            ScheduledFuture<?> fragile = scheduler.scheduleAtFixedRate(() -> {
                int n = runs.incrementAndGet();
                System.out.println("  fragile beat " + n);
                if (n == 3) {
                    throw new IllegalStateException("beat 3 failed");
                }
            }, 0, 200, TimeUnit.MILLISECONDS);
            Thread.sleep(1_200);
            System.out.printf(Locale.ROOT, "  fragile: runs=%d isDone=%b (an exception cancels the whole schedule)%n",
                    runs.get(), fragile.isDone());

            runs.set(0);
            ScheduledFuture<?> robust = scheduler.scheduleAtFixedRate(() -> {
                try {
                    int n = runs.incrementAndGet();
                    System.out.println("  robust beat " + n);
                    if (n == 3) {
                        throw new IllegalStateException("beat 3 failed");
                    }
                } catch (RuntimeException e) {
                    System.out.println("  robust: logged and swallowed: " + e.getMessage());
                }
            }, 0, 200, TimeUnit.MILLISECONDS);
            Thread.sleep(1_200);
            System.out.printf(Locale.ROOT, "  robust: runs=%d isDone=%b%n", runs.get(), robust.isDone());
            robust.cancel(false);
        }
        System.out.println("Ex73CustomThreadPool finished");
    }
}
