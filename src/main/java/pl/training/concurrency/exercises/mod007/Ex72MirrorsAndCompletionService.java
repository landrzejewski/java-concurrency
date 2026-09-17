package pl.training.concurrency.exercises.mod007;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

/**
 * Exercise 7.2 — Fastest mirror and completion order.
 *
 * <p>Related: Mod007 §5, §6
 */
public final class Ex72MirrorsAndCompletionService {

    private Ex72MirrorsAndCompletionService() {}

    static Callable<String> mirror(String name, double failProbability, long maxLatencyMillis) {
        return () -> {
            long latency = ThreadLocalRandom.current().nextLong(100, maxLatencyMillis);
            Thread.sleep(latency);
            if (ThreadLocalRandom.current().nextDouble() < failProbability) {
                throw new IOException(name + " unavailable");
            }
            return name + " answered in " + latency + " ms";
        };
    }

    public static void main(String[] args) throws InterruptedException {
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            System.out.println("[1] invokeAny — first successful mirror wins");
            var mirrors = List.of(mirror("eu", 0.3, 600), mirror("us", 0.3, 600), mirror("asia", 0.3, 600));
            try {
                System.out.println("  " + pool.invokeAny(mirrors)); // cancels the others once one succeeds
            } catch (ExecutionException e) {
                System.out.println("  all mirrors failed: " + e.getCause());
            }
            try {
                pool.invokeAny(List.of(mirror("a", 1.0, 200), mirror("b", 1.0, 200)));
            } catch (ExecutionException e) {
                System.out.println("  all failing mirrors -> ExecutionException: " + e.getCause());
            }

            System.out.println("[2] invokeAll with a 1 s timeout");
            var slow = List.of(mirror("fast", 0, 300), mirror("medium", 0, 900), mirror("slow", 0, 3_000));
            var futures = pool.invokeAll(slow, 1, TimeUnit.SECONDS); // returns when all done or timeout; late ones are cancelled
            for (int i = 0; i < futures.size(); i++) {
                var future = futures.get(i);
                if (future.isCancelled()) {
                    System.out.println("  task " + i + " cancelled by the timeout");
                } else {
                    try {
                        System.out.println("  task " + i + ": " + future.get());
                    } catch (ExecutionException e) {
                        System.out.println("  task " + i + " failed: " + e.getCause());
                    }
                }
            }

            System.out.println("[3] ExecutorCompletionService — results in completion order");
            var downloads = new ArrayList<Callable<String>>();
            for (int i = 1; i <= 10; i++) {
                downloads.add(mirror("resource-" + i, 0, 1_000));
            }

            long t0 = System.nanoTime();
            var all = pool.invokeAll(downloads); // submission order: nothing can be consumed before everything is done
            for (Future<String> future : all) {
                consume(future.get());
            }
            long invokeAllMillis = (System.nanoTime() - t0) / 1_000_000;

            t0 = System.nanoTime();
            var completion = new ExecutorCompletionService<String>(pool);
            downloads.forEach(completion::submit);
            for (int i = 0; i < downloads.size(); i++) {
                String result = completion.take().get(); // whichever finished first
                System.out.println("  " + result);
                consume(result);
            }
            long completionMillis = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf(Locale.ROOT, "  invokeAll + consume: %d ms, CompletionService + consume: %d ms%n",
                    invokeAllMillis, completionMillis);
            // With invokeAll the 10 x 100 ms of consumer work starts only after the slowest download; with the
            // completion service the consumer processes early results while the slow ones are still downloading.
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
        System.out.println("Ex72MirrorsAndCompletionService finished");
    }

    /** Simulates 100 ms of work per result. */
    private static void consume(String result) throws InterruptedException {
        Thread.sleep(100);
    }
}
