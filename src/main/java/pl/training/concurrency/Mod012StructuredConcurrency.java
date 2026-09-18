package pl.training.concurrency;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.atomic.AtomicInteger;

/*
Mental model

The shape of a structured fan-out:

parent task                      open scope ─┐
  │                                          │
  ├─ fork sub-task A (virtual thread)        │ all children's lifetimes
  ├─ fork sub-task B (virtual thread)        │ are bounded by the scope
  ├─ fork sub-task C (virtual thread)        │
  │                                          │
  └─ scope.join()  ←──── waits for all ──────┘
       (or until the joiner short-circuits)

Closing the scope (try-with-resources) cancels any still-running children. If the parent itself is cancelled
(because its parent scope cancelled it), the cancellation propagates downwards. The whole tree is structurally
enclosed — there are no orphans.

API note (Java 26)

StructuredTaskScope is still a preview API in Java 26 (JEP 505 lineage), and the Joiner API changed relative to
Java 25 — the examples below use the Java 26 shape:

  Java 25                                    Java 26
  ------------------------------------------ --------------------------------------------------
  Joiner.anySuccessfulResultOrThrow()        Joiner.anySuccessfulOrThrow()
  allSuccessfulOrThrow() -> Stream<Subtask>  allSuccessfulOrThrow() -> List<T>   (the VALUES)
  allUntil(p)            -> Stream<Subtask>  allUntil(p)            -> List<Subtask<T>>
  onFork/onComplete(Subtask<? extends T>)    onFork/onComplete(Subtask<T>)
  —                                          Joiner.onTimeout()  (new callback)

Compile and run with --enable-preview.
*/

final class BestEffortJoiner<T> implements Joiner<T, List<T>> {
    private final ConcurrentLinkedQueue<T> ok = new ConcurrentLinkedQueue<>();

    // Java 26 signature: Subtask<T>, not Subtask<? extends T>.
    @Override public boolean onComplete(Subtask<T> subtask) {
        if (subtask.state() == Subtask.State.SUCCESS) ok.add(subtask.get());
        return false; // never short-circuit
    }

    // New in Java 26: called when the scope's configured timeout elapses. The DEFAULT implementation throws
    // TimeoutException. The scope is cancelled by the runtime either way, before this is ever called — so what
    // overriding it buys is not "let the sub-tasks run on", it is "do not throw", which lets join() fall through
    // to result() and return whatever arrived before the deadline.
    @Override public void onTimeout() {
        System.out.println("    (BestEffortJoiner: timeout reached, keeping partial results)");
    }

    @Override public List<T> result() { return new ArrayList<>(ok); }
}

public final class Mod012StructuredConcurrency {

    private Mod012StructuredConcurrency() {}

    // Simulated downstream services for the dashboard scenario.
    private static String fetchProfile()         { sleep(80);  return "alice"; }
    private static List<String> fetchOrders()    { sleep(120); return List.of("o-1", "o-2"); }
    private static List<String> fetchRecos()     { sleep(70);  return List.of("r-1", "r-2", "r-3"); }
    private static String fetchProfileFailing()  { sleep(40);  throw new RuntimeException("profile down"); }

    /*
    The unstructured-concurrency problem

    - A CompletableFuture chain that fans out to N services has no enclosing "task" object. Each branch lives
      independently:
      - If one branch throws, the others keep running and continue to consume threads, sockets, DB rows.
      - Cancelling one future does not propagate; siblings cannot be told to stop.
      - Stack traces of inner stages do not point back to the call site that submitted them — they show the executor
        worker, period.
    - This pattern leaks resources and makes failure handling fragile. Structured concurrency attaches a
      parent–child relationship between a coordinating task and its sub-tasks, so the lifetime of the children is
      bounded by the lifetime of the parent.
    - Note the latch below: we must wait until the first task has actually STARTED before cancelling it. Without
      that, cancel() can win the race against the pool and the task never runs at all — which would look like
      cancellation working, the exact opposite of the point being made.
    */
    static void unstructuredProblem() throws InterruptedException {
        System.out.println("[Section 1] unstructured-concurrency problem");

        var ran = new AtomicInteger();
        var firstStarted = new CountDownLatch(1);

        var first = CompletableFuture.runAsync(() -> {
            firstStarted.countDown();
            sleep(80);
            ran.incrementAndGet();
        });
        var siblings = new ArrayList<>(List.of(first));
        for (long delay : new long[] { 200L, 60L }) {
            siblings.add(CompletableFuture.runAsync(() -> { sleep(delay); ran.incrementAndGet(); }));
        }

        firstStarted.await();       // the task is definitely running now
        first.cancel(true);         // ... and cancel(true) still does not interrupt it
        sleep(300);
        System.out.println("  with CompletableFuture, siblings that still ran = " + ran.get() + "/3"
                + " (cancellation neither interrupted the target nor stopped the siblings)");
    }

    /*
    StructuredTaskScope.open() — basic shape

    try (var scope = StructuredTaskScope.open()) {
        var a = scope.fork(() -> fetchProfile());
        var b = scope.fork(() -> fetchOrders());
        scope.join();                   // waits for ALL forks; throws if any of them failed
        var profile = a.get();
        var orders  = b.get();
    }

    - fork(Callable) returns a Subtask<T>. There is also a fork(Runnable) overload for side-effecting sub-tasks.
      Reading subtask.get() before scope.join() returns is illegal.
    - The no-argument open() is shorthand for open(Joiner.awaitAllSuccessfulOrThrow()): it waits for every subtask
      and throws StructuredTaskScope.FailedException if any of them failed. join() itself returns null (Void), so
      you read the individual results from the Subtask handles — which is exactly what you want when the sub-tasks
      have DIFFERENT result types, as in a dashboard.
    - The try-with-resources close() makes sure that even if join() is skipped (e.g., an exception jumps over it),
      every still-running subtask is cancelled before the try block exits.
    - Each fork creates a virtual thread by default — see §9.
    */
    static void basicScope() throws InterruptedException {
        System.out.println("[Section 2] StructuredTaskScope.open() — heterogeneous dashboard fan-out");

        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.open()) {
            Subtask<String> profile = scope.fork(Mod012StructuredConcurrency::fetchProfile);
            Subtask<List<String>> orders = scope.fork(Mod012StructuredConcurrency::fetchOrders);
            Subtask<List<String>> recos  = scope.fork(Mod012StructuredConcurrency::fetchRecos);

            scope.join();

            System.out.println("  profile=" + profile.get()
                    + ", orders=" + orders.get()
                    + ", recommendations=" + recos.get());
        }
        System.out.println("  wall time = " + ((System.nanoTime() - t0) / 1_000_000)
                + " ms (≈ slowest branch, not the sum)");
    }

    /*
    Joiner.allSuccessfulOrThrow — homogeneous fan-out

    - All sub-tasks must succeed; the first failure cancels the rest.
    - In Java 26 join() returns List<T> — the RESULT VALUES, already unwrapped, in the order the sub-tasks were
      FORKED (not the order they completed). That makes it the natural choice when every branch produces the same
      type: query N shards, hit N mirrors, price N line items.
    - When the branches have different types, use the no-arg open() / awaitAllSuccessfulOrThrow() from §2 instead
      and read each Subtask individually.
    - Joiner.awaitAll() is the fourth variant: wait for everything, never throw, inspect the Subtasks yourself.
    */
    static void allSuccessfulOrThrow() throws InterruptedException {
        System.out.println("[Section 3] Joiner.allSuccessfulOrThrow → List<T>");

        // (a) happy path — note the result order matches the FORK order, not completion order.
        try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
            scope.fork(() -> { Thread.sleep(120); return "shard-1 (slow, forked first)"; });
            scope.fork(() -> { Thread.sleep(60);  return "shard-2"; });
            scope.fork(() -> { Thread.sleep(20);  return "shard-3 (fast, forked last)"; });
            List<String> results = scope.join();
            results.forEach(r -> System.out.println("    " + r));
        }

        // (b) one branch fails → the whole scope fails fast and cancels the siblings.
        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
            scope.fork(Mod012StructuredConcurrency::fetchProfileFailing); // fails at ~40 ms
            scope.fork(() -> {                                            // would take 500 ms
                try { Thread.sleep(500); }
                catch (InterruptedException e) {
                    System.out.println("    long subtask was interrupted (cancellation propagated)");
                    throw e;
                }
                return "never";
            });
            scope.join();
            throw new IllegalStateException("should not reach here");
        } catch (StructuredTaskScope.FailedException e) {
            System.out.println("  join() rethrew FailedException (cause = " + e.getCause() + ")");
        }
        System.out.println("  wall time = " + ((System.nanoTime() - t0) / 1_000_000)
                + " ms (≈ first-failure time, NOT max sibling time)");
    }

    /*
    Joiner.anySuccessfulOrThrow — race / first wins

    - Renamed in Java 26 (it was anySuccessfulResultOrThrow in Java 25).
    - Several sub-tasks compute the same answer in parallel; the first to return wins, the others are cancelled
      immediately and their state() becomes UNAVAILABLE.
    - join() returns the value directly (typed T).
    - If every sub-task fails, join() throws FailedException with the exception of ONE of the failed sub-tasks as
      the cause — in practice the first one to fail, since a later failure never displaces the stored one.
    - Use case: redundant providers (two DNS resolvers, several mirror caches), adaptive timeouts, or fastest-replica
      reads.
    */
    static void anySuccessfulRace() throws InterruptedException {
        System.out.println("[Section 4] Joiner.anySuccessfulOrThrow");

        try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
            var primary = scope.fork(() -> { Thread.sleep(120); return "primary"; });
            scope.fork(() -> { Thread.sleep(40);  return "mirror-1"; });
            scope.fork(() -> { Thread.sleep(80);  return "mirror-2"; });

            String winner = scope.join();
            System.out.println("  winner = " + winner
                    + ", loser state = " + primary.state()
                    + ", scope cancelled = " + scope.isCancelled());
        }
    }

    /*
    Joiner.allUntil(predicate) — partial fan-out

    - Wait for sub-tasks until a user predicate returns true. A common case: "send 10 mirror queries, stop and
      return as soon as 3 of them return".
    - In Java 26 join() returns List<Subtask<T>> (a List now, not a Stream) covering every sub-task — the ones that
      finished and the ones that were cancelled. Filter on state() before calling get(); get() on a subtask whose
      state is UNAVAILABLE throws IllegalStateException.
    - The predicate is called every time a sub-task finishes; the running siblings are cancelled when it first
      returns true. It can be invoked from several carrier threads, so any state it touches must be thread-safe —
      hence the AtomicInteger below.
    */
    static void allUntilPredicate() throws InterruptedException {
        System.out.println("[Section 5] Joiner.allUntil(predicate) → List<Subtask<T>>");

        var threshold = 3;
        var done = new AtomicInteger();
        try (var scope = StructuredTaskScope.open(Joiner.<Integer>allUntil(s -> {
            if (s.state() == Subtask.State.SUCCESS) done.incrementAndGet();
            return done.get() >= threshold; // stop once we have N successes
        }))) {
            for (int i = 0; i < 10; i++) {
                final int delay = 30 + i * 30;
                scope.fork(() -> { Thread.sleep(delay); return delay; });
            }
            List<Subtask<Integer>> subtasks = scope.join();
            var successful = subtasks.stream()
                    .filter(s -> s.state() == Subtask.State.SUCCESS)
                    .map(Subtask::get)
                    .toList();
            long cancelled = subtasks.stream()
                    .filter(s -> s.state() == Subtask.State.UNAVAILABLE)
                    .count();
            System.out.println("  first " + threshold + " successful results = " + successful
                    + ", cancelled mid-flight = " + cancelled);
        }
    }

    /*
    Custom joiner

    - Joiners are an open SPI: implement Joiner<T, R>:
      - onFork(Subtask<T>) — called when a sub-task is forked; return true to short-circuit immediately.
      - onComplete(Subtask<T>) — called when a sub-task ends. Return true to short-circuit (cancel the scope),
        false to keep collecting. NOTE the Java 26 signature: Subtask<T>, invariant.
      - onTimeout() — new in Java 26; invoked when the scope's configured timeout (§7) elapses. The default
        implementation throws TimeoutException. The scope has already been cancelled by the runtime at that point;
        an override that does not throw simply lets join() proceed to result().
      - result() — produces the final aggregated value returned by scope.join().
    - onComplete runs on the completing sub-task's thread and may be invoked concurrently; onFork and onTimeout run
      on the scope owner's thread. Either way, any state a joiner touches must be thread-safe.
    - The example below is BestEffortJoiner<T>: it collects every successful sub-task and silently ignores failures.
      The shape mirrors how a "fan-out, accept what we got" aggregation stage is built.
    */
    static void customBestEffortJoiner() throws InterruptedException {
        System.out.println("[Section 6] custom BestEffortJoiner");

        try (var scope = StructuredTaskScope.open(new BestEffortJoiner<String>())) {
            scope.fork(() -> { Thread.sleep(20); return "A"; });
            scope.fork(() -> { throw new RuntimeException("flaky"); });
            scope.fork(() -> { Thread.sleep(50); return "C"; });
            List<String> results = scope.join();
            System.out.println("  successful results only: " + results + " (the failure was swallowed)");
        }
    }

    /*
    Configuration: names, timeouts, thread factories

    - The two-argument open(joiner, UnaryOperator<Configuration>) customises the scope:
      - withName(String)          — the scope's name; shows up in thread dumps and JFR events.
      - withTimeout(Duration)     — a deadline for the whole scope. When it elapses the runtime cancels the scope
                                    and then calls the joiner's onTimeout(), whose default implementation throws
                                    StructuredTaskScope.TimeoutException out of join().
      - withThreadFactory(tf)     — how sub-task threads are created; the default is a virtual-thread factory.
    - A scope-level timeout is strictly better than a per-future orTimeout (Mod009 §6): it bounds the ENTIRE fan-out
      and cancels the in-flight children instead of just completing a downstream stage exceptionally.
    - scope.isCancelled() tells you whether the scope has been cancelled (by a short-circuiting joiner, a timeout,
      or the owner thread being interrupted).
    */
    static void configurationAndTimeout() throws InterruptedException {
        System.out.println("[Section 7] Configuration — withName / withTimeout / withThreadFactory");

        // (a) The whole fan-out is bounded by 100 ms.
        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow(),
                cf -> cf.withName("dashboard").withTimeout(Duration.ofMillis(100)))) {
            scope.fork(() -> { Thread.sleep(30);   return "fast"; });
            scope.fork(() -> { Thread.sleep(5_000); return "way too slow"; });
            scope.join();
        } catch (StructuredTaskScope.TimeoutException e) {
            System.out.println("  scope timed out after " + ((System.nanoTime() - t0) / 1_000_000)
                    + " ms (NOT 5 000 ms) — the slow subtask was cancelled");
        }

        // (b) A custom thread factory — here still virtual, but named, so thread dumps are readable.
        ThreadFactory named = Thread.ofVirtual().name("reco-", 0).factory();
        try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow(),
                cf -> cf.withName("recos").withThreadFactory(named))) {
            scope.fork(() -> Thread.currentThread().getName());
            scope.fork(() -> Thread.currentThread().getName());
            System.out.println("  sub-task threads: " + scope.join());
        }
    }

    /*
    Cancellation propagation

    - When the joiner short-circuits (e.g., one branch threw under allSuccessfulOrThrow) or the scope times out, the
      scope sends an interrupt to every still-running sub-task.
    - A sub-task that is currently in Thread.sleep, Object.wait, a BlockingQueue operation, or any other JDK blocking
      call is woken up with InterruptedException. A pure CPU-bound sub-task is responsible for checking
      Thread.currentThread().isInterrupted().
    - This is the single biggest qualitative win over CompletableFuture: failure in one branch immediately stops
      every other branch, so the request returns quickly and stops paying for work whose result will be thrown away.
    */
    static void cancellationPropagation() throws InterruptedException {
        System.out.println("[Section 8] cancellation propagation");

        long t0 = System.nanoTime();
        // The long sub-task signals that it is actually blocked before the other one fails.
        // Without that hand-off the scope can cancel it before its body ever runs, and we
        // would not observe the interrupt we are trying to demonstrate.
        var longTaskBlocked = new CountDownLatch(1);
        try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
            scope.fork(() -> {
                try {
                    longTaskBlocked.countDown();
                    Thread.sleep(10_000); // would block 10 seconds
                    return "long";
                } catch (InterruptedException e) {
                    System.out.println("  long subtask received interrupt at "
                            + ((System.nanoTime() - t0) / 1_000_000) + " ms");
                    throw e;
                }
            });
            scope.fork(() -> {
                longTaskBlocked.await();
                throw new RuntimeException("immediate");
            });
            scope.join();
        } catch (StructuredTaskScope.FailedException ignored) {
            // expected — the second branch blew up
        }
        System.out.println("  total wall time = " + ((System.nanoTime() - t0) / 1_000_000) + " ms (NOT 10 000 ms)");
    }

    /*
    Composition with virtual threads

    - scope.fork(...) creates a virtual thread for the sub-task by default — the same kind covered in
      Mod011VirtualThreads.
    - That makes structured concurrency the natural API for a server that wants to fan out N parallel calls per
      request: each call gets its own (cheap) virtual thread, the scope manages their lifetime, and cancellation is
      implicit.
    - Platform threads are available through withThreadFactory (§7), but the default is right for almost every
      workload — the whole design assumes sub-task threads are cheap.
    */
    static void virtualThreadComposition() throws InterruptedException {
        System.out.println("[Section 9] virtual-thread composition");

        var virtualCount = new AtomicInteger();
        try (var scope = StructuredTaskScope.open(Joiner.<Integer>allSuccessfulOrThrow())) {
            for (int i = 0; i < 1000; i++) {
                final int id = i;
                scope.fork(() -> {
                    if (Thread.currentThread().isVirtual()) virtualCount.incrementAndGet();
                    Thread.sleep(20);
                    return id;
                });
            }
            List<Integer> ids = scope.join();
            System.out.println("  " + ids.size() + " sub-tasks joined, "
                    + virtualCount.get() + " of them ran on virtual threads");
        }
    }

    /*
    Comparison with CompletableFuture

      Aspect                       CompletableFuture               StructuredTaskScope
      ---------------------------- ------------------------------- ----------------------------
      Fan-out / fan-in             allOf + join() per branch       fork + join()
      Cancellation propagation     NO (Mod009 §9)                  YES — sibling-aware
      Deadline for the whole job   per-stage orTimeout only        Configuration.withTimeout
      Exception aggregation        manual                          automatic via joiner
      Stack traces                 severed at executor boundary    preserved (synchronous shape)
      Best for                     event-driven, callbacks         request-bounded fan-out
    */
    static void comparisonHeadToHead() throws InterruptedException {
        System.out.println("[Section 10] CompletableFuture vs StructuredTaskScope");

        // A: CompletableFuture-based dashboard.
        long t1 = System.nanoTime();
        var profileF = CompletableFuture.supplyAsync(Mod012StructuredConcurrency::fetchProfile);
        var ordersF  = CompletableFuture.supplyAsync(Mod012StructuredConcurrency::fetchOrders);
        var recosF   = CompletableFuture.supplyAsync(Mod012StructuredConcurrency::fetchRecos);
        var dashboardA = CompletableFuture.allOf(profileF, ordersF, recosF)
                .thenApply(v -> "user=" + profileF.join()
                        + ", orders=" + ordersF.join()
                        + ", recos=" + recosF.join())
                .join();
        long aMs = (System.nanoTime() - t1) / 1_000_000;

        // B: Same dashboard with StructuredTaskScope.
        long t2 = System.nanoTime();
        String dashboardB;
        try (var scope = StructuredTaskScope.open()) {
            var p = scope.fork(Mod012StructuredConcurrency::fetchProfile);
            var o = scope.fork(Mod012StructuredConcurrency::fetchOrders);
            var r = scope.fork(Mod012StructuredConcurrency::fetchRecos);
            scope.join();
            dashboardB = "user=" + p.get() + ", orders=" + o.get() + ", recos=" + r.get();
        }
        long bMs = (System.nanoTime() - t2) / 1_000_000;

        System.out.println("  CompletableFuture       (" + aMs + " ms): " + dashboardA);
        System.out.println("  StructuredTaskScope     (" + bMs + " ms): " + dashboardB);
        System.out.println("  → both equivalent on the happy path; STS adds cancellation and deadline correctness");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void main(String[] args) throws InterruptedException {
        unstructuredProblem();
        basicScope();
        allSuccessfulOrThrow();
        anySuccessfulRace();
        allUntilPredicate();
        customBestEffortJoiner();
        configurationAndTimeout();
        cancellationPropagation();
        virtualThreadComposition();
        comparisonHeadToHead();
        System.out.println("Mod012StructuredConcurrency finished");
    }
}
