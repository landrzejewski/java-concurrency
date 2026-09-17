package pl.training.concurrency.exercises.mod009;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Exercise 9.2 — Resilient service client.
 *
 * <p>Related: Mod009 §5, §6, §7
 */
public final class Ex92ResilientClient {

    private Ex92ResilientClient() {}

    /** Daemon so a pending backoff timer can never keep the JVM alive. */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(1, r -> Thread.ofPlatform().daemon(true).unstarted(r));

    private static final AtomicInteger CALLS = new AtomicInteger();

    /** Fails on the first two invocations, then succeeds. Latency 20–300 ms. */
    static CompletableFuture<String> remoteCall() {
        return CompletableFuture.supplyAsync(() -> {
            int attempt = CALLS.incrementAndGet();
            sleep(ThreadLocalRandom.current().nextLong(20, 300));
            if (attempt <= 2) throw new IllegalStateException("remote unavailable (attempt " + attempt + ")");
            return "payload (attempt " + attempt + ")";
        });
    }

    public static void main(String[] args) throws InterruptedException {
        var failing = CompletableFuture.<String>supplyAsync(() -> {
            throw new IllegalStateException("downstream is down");
        });

        System.out.println("[1] exceptionally / handle / whenComplete");
        System.out.println("  exceptionally: " + failing.exceptionally(t -> "fallback:" + t.getMessage()).join());
        System.out.println("  handle:        "
                + failing.handle((value, error) -> error != null ? "handled-error" : value.toUpperCase()).join());
        try {
            // whenComplete is a LISTENER: it observes, it cannot substitute a value, and the failure sails past it.
            failing.whenComplete((value, error) -> System.out.println("  whenComplete saw: " + error.getMessage()))
                    .join();
        } catch (CompletionException e) {
            System.out.println("  whenComplete could not change the outcome: " + e.getCause());
        }

        System.out.println("[2] get() vs join() on the same failed future");
        try {
            failing.get();
        } catch (ExecutionException e) {
            System.out.println("  get()  throws " + e.getClass().getSimpleName()
                    + " (checked)   cause=" + e.getCause());
        }
        try {
            failing.join();
        } catch (CompletionException e) {
            System.out.println("  join() throws " + e.getClass().getSimpleName()
                    + " (unchecked) cause=" + e.getCause());
        }

        System.out.println("[3] a timeout completes the FUTURE, never the WORK");
        var slowRan = new AtomicInteger();
        Supplier<String> slowTask = () -> {
            sleep(400);
            slowRan.incrementAndGet();
            System.out.println("    (the slow task is still alive and just finished — nobody is waiting for it)");
            return "late";
        };
        System.out.println("  orTimeout:         "
                + CompletableFuture.supplyAsync(slowTask)
                        .orTimeout(100, TimeUnit.MILLISECONDS)
                        .exceptionally(t -> "fallback (" + t.getClass().getSimpleName() + ")")
                        .join());
        System.out.println("  completeOnTimeout: "
                + CompletableFuture.supplyAsync(slowTask)
                        .completeOnTimeout("cached", 100, TimeUnit.MILLISECONDS)
                        .join());
        Thread.sleep(600); // let both abandoned tasks run to completion so they can print
        System.out.println("  abandoned tasks that ran to the end anyway = " + slowRan.get() + "/2");

        System.out.println("[4] retry with exponential backoff, without blocking a thread");
        long t0 = System.nanoTime();
        String result = retry(Ex92ResilientClient::remoteCall, 5, Duration.ofMillis(50)).join();
        System.out.printf(Locale.ROOT, "  %s after %d ms (backoff 50 -> 100 ms, both waits on the scheduler)%n",
                result, (System.nanoTime() - t0) / 1_000_000);

        // orTimeout/completeOnTimeout only decide when the DOWNSTREAM stage stops waiting; the supplier keeps
        // running on its pool thread, holding its socket, its DB row and its memory. CompletableFuture has no
        // handle on the running task — cancel(true) does not interrupt it either (see Ex93). To cancel the work
        // you need something that owns the task's thread: a StructuredTaskScope with withTimeout (Mod012 §7)
        // interrupts every still-running sub-task when the deadline elapses.
        SCHEDULER.shutdown();
        System.out.println("Ex92ResilientClient finished");
    }

    /**
     * Retries {@code op} up to {@code attemptsLeft} times, doubling {@code delay} after each failure.
     * The wait happens on a scheduler callback, so no thread is parked between attempts.
     */
    static CompletableFuture<String> retry(Supplier<CompletableFuture<String>> op,
                                           int attemptsLeft, Duration delay) {
        return op.get()
                .thenApply(CompletableFuture::completedFuture)
                .exceptionally(t -> {
                    if (attemptsLeft <= 1) return CompletableFuture.failedFuture(t);
                    System.out.println("  " + t.getCause().getMessage()
                            + " -> retrying in " + delay.toMillis() + " ms");
                    var next = new CompletableFuture<String>();
                    SCHEDULER.schedule(() -> retry(op, attemptsLeft - 1, delay.multipliedBy(2))
                            .whenComplete((value, error) -> {
                                if (error == null) next.complete(value);
                                else next.completeExceptionally(error);
                            }), delay.toMillis(), TimeUnit.MILLISECONDS);
                    return next;
                })
                .thenCompose(future -> future); // unwrap the CompletableFuture<CompletableFuture<String>>
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
