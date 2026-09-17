package pl.training.concurrency.exercises.mod007;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

/**
 * Exercise 7.1 — Image resizer on a fixed thread pool.
 *
 * <p>Related: Mod007 §2, §3, §4
 */
public final class Ex71ImageResizer {

    private Ex71ImageResizer() {}

    record Result(int imageId, int width, int height, long millis) {}

    static Callable<Result> resize(int imageId) {
        return () -> {
            long millis = ThreadLocalRandom.current().nextLong(200, 3_000);
            Thread.sleep(millis); // interruptible, so cancel(true) really stops it
            if (imageId % 5 == 0) {
                throw new IOException("corrupt image " + imageId);
            }
            return new Result(imageId, 800, 600, millis);
        };
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[1] 20 images on 4 threads, 2 s deadline per task");
        int succeeded = 0;
        int failed = 0;
        int cancelled = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<Result>> futures = new ArrayList<>();
            List<Long> deadlines = new ArrayList<>();
            for (int id = 1; id <= 20; id++) {
                futures.add(pool.submit(resize(id)));
                deadlines.add(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)); // 2 s from submission
            }
            for (int i = 0; i < futures.size(); i++) {
                var future = futures.get(i);
                long remaining = Math.max(deadlines.get(i) - System.nanoTime(), 0);
                try {
                    var result = future.get(remaining, TimeUnit.NANOSECONDS);
                    System.out.printf(Locale.ROOT, "  image %2d resized in %d ms%n", result.imageId(), result.millis());
                    succeeded++;
                } catch (ExecutionException e) {
                    System.out.printf(Locale.ROOT, "  image %2d failed: %s%n", i + 1, e.getCause());
                    failed++;
                } catch (TimeoutException e) {
                    future.cancel(true); // interrupts the worker if the task is running, drops it if still queued
                    System.out.printf(Locale.ROOT, "  image %2d cancelled (deadline)%n", i + 1);
                    cancelled++;
                } catch (CancellationException e) {
                    cancelled++;
                }
            }
        } // close(): waits for the remaining (non-cancelled) tasks
        System.out.printf(Locale.ROOT, "  summary: succeeded=%d failed=%d cancelled=%d%n", succeeded, failed, cancelled);

        System.out.println("[2] execute vs submit for a throwing task");
        try (ExecutorService pool = Executors.newFixedThreadPool(1, runnable -> {
            var thread = new Thread(runnable, "resizer");
            thread.setUncaughtExceptionHandler((t, ex) ->
                    System.out.println("  [uncaught handler on " + t.getName() + "] " + ex));
            return thread;
        })) {
            pool.execute(() -> { throw new IllegalStateException("boom via execute"); });
            Thread.sleep(100);
            var future = pool.submit(() -> { throw new IllegalStateException("boom via submit"); });
            Thread.sleep(100);
            System.out.println("  submit: nothing printed so far; future.isDone=" + future.isDone());
            try {
                future.get();
            } catch (ExecutionException e) {
                System.out.println("  submit: exception surfaces only in get(): " + e.getCause());
            }
        }
        // execute() runs a bare Runnable: an exception escapes run(), goes to the thread's uncaught-exception
        // handler and kills the worker (the pool replaces it). submit() wraps the task in a FutureTask, which
        // catches the exception and stores it — nothing is logged unless somebody calls get().
        System.out.println("Ex71ImageResizer finished");
    }
}
