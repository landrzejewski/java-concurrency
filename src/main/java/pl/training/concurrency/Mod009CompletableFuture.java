package pl.training.concurrency;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

/*
Promise vs Future

- A Future<V> represents a value that will be available later. Java 5's Future exposes get(), cancel(), and isDone()
  — that is, you can wait, but you cannot chain. Composing two Futures requires a thread that blocks on the first one.
- CompletableFuture<V> is a promise: a future you can complete from the outside (complete, completeExceptionally) and
  chain (thenApply, thenCompose, thenCombine, ...). Continuations are pushed; no thread sits blocked.
- This unlocks declarative async pipelines: you describe the data flow once and the runtime schedules each stage on a
  worker as soon as its inputs are ready.
*/

public final class Mod009CompletableFuture {

    private Mod009CompletableFuture() {}

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(1, r -> Thread.ofPlatform().daemon(true).unstarted(r));

    /*
    Creating async pipelines

    - CompletableFuture.supplyAsync(supplier) runs the supplier on the common ForkJoinPool and returns a future for
      its result.
    - CompletableFuture.runAsync(runnable) is the void variant.
    - Both have overloads that take an explicit Executor. Always pass a custom executor for blocking I/O — running
      blocking code on the common pool starves parallel streams and CompletableFuture chains used elsewhere in the
      JVM.
    */
    static void asyncPipeline() {
        System.out.println("[Section 2] async pipeline");

        var result = CompletableFuture
                .supplyAsync(() -> {
                    sleep(50);
                    return "data";
                })
                .thenApply(String::toUpperCase)
                .join();
        System.out.println("  result = " + result);
    }

    /*
    Mapping vs flat-mapping (thenApply vs thenCompose)

    - thenApply(Function<T, U>) is the map operator — applies a synchronous function to the resolved value.
    - thenCompose(Function<T, CompletableFuture<U>>) is the flat-map operator — the function returns another future,
      and thenCompose flattens the result so you do not end up with CompletableFuture<CompletableFuture<U>>.
    - Rule of thumb: if the next step is itself an async call, use thenCompose. If it is a pure transformation, use
      thenApply.
    */
    static void applyVsCompose() {
        System.out.println("[Section 3] thenApply vs thenCompose");

        // thenApply with an async-returning function — produces a NESTED future.
        CompletableFuture<CompletableFuture<String>> nested = CompletableFuture
                .supplyAsync(() -> 42L)
                .thenApply(Mod009CompletableFuture::fetchUser);
        System.out.println("  nested needs two joins: " + nested.join().join());

        // thenCompose flattens it.
        CompletableFuture<String> flat = CompletableFuture
                .supplyAsync(() -> 42L)
                .thenCompose(Mod009CompletableFuture::fetchUser);
        System.out.println("  flat = " + flat.join());
    }

    /*
    Combining

    - thenCombine(other, BiFunction) waits for both futures and combines their results. Like zip.
    - allOf(f1, f2, ...) returns CompletableFuture<Void> that completes when all inputs do. Read each individual
      f.join() afterwards to get the values.
    - anyOf(...) completes when the first input does, with that input's value.
    - applyToEither(other, fn) is the typed equivalent of anyOf for two futures of the same type.
    */
    static void combining() {
        System.out.println("[Section 4] combining");

        var profile = CompletableFuture.supplyAsync(() -> { sleep(40); return "alice"; });
        var orders  = CompletableFuture.supplyAsync(() -> { sleep(60); return List.of("o-1", "o-2"); });

        // Two-future zip.
        var combined = profile.thenCombine(orders, (p, o) -> p + " -> " + o);
        System.out.println("  thenCombine: " + combined.join());

        // Many-future barrier.
        var f1 = CompletableFuture.supplyAsync(() -> "A");
        var f2 = CompletableFuture.supplyAsync(() -> "B");
        var f3 = CompletableFuture.supplyAsync(() -> "C");
        CompletableFuture.allOf(f1, f2, f3).join();
        System.out.println("  allOf: " + f1.join() + ", " + f2.join() + ", " + f3.join());

        // Race, typed: applyToEither needs both futures to have the same type.
        var fast = CompletableFuture.supplyAsync(() -> { sleep(20); return "fast"; });
        var slow = CompletableFuture.supplyAsync(() -> { sleep(200); return "slow"; });
        System.out.println("  applyToEither: " + fast.applyToEither(slow, x -> x).join());

        // Race, untyped: anyOf accepts a mixed array and gives back CompletableFuture<Object>,
        // so you have to cast the winner yourself. Note that BOTH losers keep running — see §9.
        var quickString = CompletableFuture.supplyAsync(() -> { sleep(150); return "a string"; });
        var quickNumber = CompletableFuture.supplyAsync(() -> { sleep(30); return 42; });
        Object winner = CompletableFuture.anyOf(quickString, quickNumber).join();
        System.out.println("  anyOf winner:  " + winner + " (" + winner.getClass().getSimpleName() + ")");
    }

    /*
    Error handling

    - exceptionally(Function<Throwable, T>) recovers from a failure by producing a fallback value of the same type.
      Subsequent stages do not see the failure.
    - handle(BiFunction<T, Throwable, U>) runs in either case (success or failure) and lets you decide what to
      produce.
    - whenComplete(BiConsumer<T, Throwable>) is the listener form — it cannot change the value; useful for logging or
      metric emission.
    - An unhandled exception inside any stage propagates downstream wrapped in a CompletionException. Calling get()
      then throws ExecutionException; calling join() throws CompletionException (unchecked) — the latter is more
      pleasant for chaining.
    */
    static void errorHandling() {
        System.out.println("[Section 5] error handling");

        var failing = CompletableFuture.<String>supplyAsync(() -> {
            throw new RuntimeException("downstream is down");
        });

        // exceptionally — recover with a fallback.
        var recovered = failing.exceptionally(t -> "fallback:" + t.getMessage());
        System.out.println("  exceptionally: " + recovered.join());

        // handle — runs always, lets us decide what to produce.
        var handled = failing.handle((value, error) ->
                error != null ? "handled-error" : value.toUpperCase());
        System.out.println("  handle:        " + handled.join());

        // whenComplete — listener-only, cannot change the value.
        try {
            failing.whenComplete((value, error) -> {
                if (error != null) System.out.println("  whenComplete saw: " + error.getMessage());
            }).join();
        } catch (Exception e) {
            System.out.println("  whenComplete propagated: " + e.getClass().getSimpleName());
        }
    }

    /*
    Timeouts

    - orTimeout(t, unit) (Java 9+) completes the future exceptionally with TimeoutException if it has not finished in
      time.
    - completeOnTimeout(value, t, unit) completes successfully with a fallback value instead.
    - Pre-Java 9 you had to wire your own timer via a ScheduledExecutorService:

        var slow  = CompletableFuture.supplyAsync(() -> blockingCall());
        var timer = new CompletableFuture<String>();
        SCHEDULER.schedule(() -> timer.completeExceptionally(new TimeoutException()), 100, MILLISECONDS);
        slow.applyToEither(timer, x -> x).exceptionally(t -> "fallback").join();

      Today, prefer the built-ins. Note what NEITHER version does: the slow task keeps running in both cases.
      A timeout on a CompletableFuture completes the FUTURE, it does not cancel the WORK — for that you need a
      scope-level deadline (Mod012 §7).
    */
    static void timeouts() {
        System.out.println("[Section 6] timeouts");

        // (a) orTimeout — fails with TimeoutException.
        var slow = CompletableFuture.supplyAsync(() -> { sleep(200); return "late"; })
                .orTimeout(50, TimeUnit.MILLISECONDS)
                .exceptionally(t -> "fallback (orTimeout: " + t.getClass().getSimpleName() + ")");
        System.out.println("  " + slow.join());

        // (b) completeOnTimeout — succeeds with a fallback value.
        var slow2 = CompletableFuture.supplyAsync(() -> { sleep(200); return "late"; })
                .completeOnTimeout("cached", 50, TimeUnit.MILLISECONDS);
        System.out.println("  " + slow2.join());
    }

    /*
    Retry with exponential backoff

    - A common real-world need: retry an idempotent failing call a few times, doubling the delay between attempts.
    - The pattern: call the supplier; on exceptionally, schedule the next attempt on a ScheduledExecutorService and
      thenCompose to its result. Recurse until attempts are exhausted, then propagate the last failure.
    */
    static void retryWithBackoff() {
        System.out.println("[Section 7] retry with exponential backoff");

        var attempts = new AtomicInteger();
        Supplier<CompletableFuture<String>> flaky = () -> CompletableFuture.supplyAsync(() -> {
            int n = attempts.incrementAndGet();
            if (n < 3) throw new RuntimeException("attempt " + n + " failed");
            return "attempt " + n + " succeeded";
        });

        var result = retry(flaky, 5, Duration.ofMillis(20));
        System.out.println("  " + result.join());
    }

    static CompletableFuture<String> retry(Supplier<CompletableFuture<String>> op,
                                           int attemptsLeft, Duration delay) {
        return op.get().thenApply(CompletableFuture::completedFuture)
                .exceptionally(t -> {
                    if (attemptsLeft <= 1) {
                        return CompletableFuture.failedFuture(t);
                    }
                    var next = new CompletableFuture<String>();
                    SCHEDULER.schedule(
                            () -> retry(op, attemptsLeft - 1, delay.multipliedBy(2))
                                    .whenComplete((v, e) -> {
                                        if (e == null) next.complete(v);
                                        else next.completeExceptionally(e);
                                    }),
                            delay.toMillis(), TimeUnit.MILLISECONDS);
                    return next;
                })
                .thenCompose(x -> x);
    }

    /*
    Real-world fan-out

    - Frontend dashboards typically call several services in parallel and combine the answers. The natural shape is
      supplyAsync per service, then either thenCombine for two of them or allOf + f.join() for many.
    - Every supplyAsync/thenApplyAsync overload takes an optional Executor. Pass one whenever the stage blocks:
      the default is the common ForkJoinPool, which is sized for CPU-bound work and shared with parallel streams,
      so one slow HTTP call there degrades unrelated code elsewhere in the JVM.
    - Caveat: CompletableFuture does NOT propagate cancellation. Cancelling one sibling does not stop the others.
      Mod012 (StructuredTaskScope) is the modern fix.
    */
    static void dashboardFanOut() {
        System.out.println("[Section 8] dashboard fan-out");

        long t0 = System.nanoTime();

        // Every branch simulates BLOCKING I/O, so none of them belongs on the common pool.
        // A virtual-thread executor is the modern choice here: unlimited concurrency, no tuning,
        // and no contention with parallel streams or other CompletableFuture chains (Mod011).
        try (var io = Executors.newVirtualThreadPerTaskExecutor()) {
            var profile = CompletableFuture.supplyAsync(() -> { sleep(80); return "alice"; }, io);
            var orders  = CompletableFuture.supplyAsync(() -> { sleep(120); return List.of("o-1", "o-2"); }, io);
            var recos   = CompletableFuture.supplyAsync(() -> { sleep(70); return List.of("r-1", "r-2", "r-3"); }, io);

            var dashboard = CompletableFuture.allOf(profile, orders, recos)
                    .thenApplyAsync(v -> "User=" + profile.join()
                            + ", orders=" + orders.join()
                            + ", recommendations=" + recos.join(), io);

            System.out.println("  " + dashboard.join());
        }
        System.out.println("  total wall time = "
                + ((System.nanoTime() - t0) / 1_000_000) + " ms (≈ slowest branch)");
    }

    /*
    Limitations and forward-link to structured concurrency

    - cancel(true) on a CompletableFuture only marks the future as cancelled — the running task is not interrupted,
      and pending parallel siblings continue.
    - A failure in one branch of a fan-out does not cancel the rest, so you keep paying for work whose result you
      will discard.
    - Chaining is opaque to thread dumps: a stage running on a common-pool worker does not show the call site that
      scheduled it.
    - For coordinated fan-out with all-or-nothing semantics, prefer StructuredTaskScope (Mod012). Use
      CompletableFuture for async-event plumbing (UI callbacks, message buses) where there is no natural parent task.
    */
    static void cancellationDoesNotPropagate() {
        System.out.println("[Section 9] cancellation does NOT propagate");

        var ran = new AtomicInteger();
        // The first task announces that it has started. Without this hand-off, cancel() can win the
        // race against the pool: the task would then never run at all and the counter would look as
        // if cancellation had worked — the exact opposite of the point.
        var firstStarted = new java.util.concurrent.CountDownLatch(1);
        var first = CompletableFuture.runAsync(() -> {
            firstStarted.countDown();
            sleep(80);
            ran.incrementAndGet();
        });
        var siblings = Stream.concat(Stream.of(first),
                Stream.of(120, 60).map(delay ->
                        CompletableFuture.runAsync(() -> {
                            sleep(delay);
                            ran.incrementAndGet();
                        }))).toList();

        try { firstStarted.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Cancel the first one — it IS running, and it is still NOT interrupted; the others run on.
        siblings.get(0).cancel(true);
        try {
            CompletableFuture.allOf(siblings.toArray(CompletableFuture[]::new)).join();
        } catch (Exception e) {
            // expected: the cancelled future causes a CancellationException in the allOf composite.
        }
        sleep(150); // wait for the still-running tasks to finish
        System.out.println("  tasks that actually finished = " + ran.get() + "/3"
                + " (cancel(true) neither interrupted its own task nor stopped the siblings; see Mod012)");
    }

    private static CompletableFuture<String> fetchUser(long id) {
        return CompletableFuture.supplyAsync(() -> "user-" + id);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        try {
            asyncPipeline();
            applyVsCompose();
            combining();
            errorHandling();
            timeouts();
            retryWithBackoff();
            dashboardFanOut();
            cancellationDoesNotPropagate();
        } catch (Exception e) {
            // surface unexpected failures
            e.printStackTrace();
        }
        SCHEDULER.shutdown();
        System.out.println("Mod009CompletableFuture finished");
    }

}
