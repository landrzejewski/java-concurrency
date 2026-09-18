package pl.training.concurrency.exercises.mod012;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;

/**
 * Exercise 12.1 — Dashboard with StructuredTaskScope.
 *
 * <p>Related: Mod012 §2, §3, §8
 *
 * <p>Compile and run with {@code --enable-preview} — StructuredTaskScope is still preview in Java 26.
 */
public final class Ex121DashboardScope {

    private Ex121DashboardScope() {}

    static String fetchProfile()      { sleep(80);  return "alice"; }
    static List<String> fetchOrders() { sleep(120); return List.of("o-1", "o-2"); }
    static List<String> fetchRecos()  { sleep(70);  return List.of("r-1", "r-2", "r-3"); }

    public static void main(String[] args) throws InterruptedException {

        System.out.println("[1] heterogeneous fan-out with the no-arg open()");
        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.open()) {                 // = Joiner.awaitAllSuccessfulOrThrow()
            Subtask<String> profile       = scope.fork(Ex121DashboardScope::fetchProfile);
            Subtask<List<String>> orders  = scope.fork(Ex121DashboardScope::fetchOrders);
            Subtask<List<String>> recos   = scope.fork(Ex121DashboardScope::fetchRecos);

            scope.join();                                              // returns Void: read the Subtasks

            System.out.println("  user=" + profile.get() + ", orders=" + orders.get()
                    + ", recommendations=" + recos.get());
        }
        System.out.println("  wall time = " + millisSince(t0) + " ms (≈ slowest branch 120 ms, not the sum 270 ms)");
        System.out.println("  → the no-arg open() is the right choice when the branches have DIFFERENT types");

        System.out.println("[2] get() before join() is illegal");
        try (var scope = StructuredTaskScope.open()) {
            Subtask<String> profile = scope.fork(Ex121DashboardScope::fetchProfile);
            try {
                profile.get();
                throw new AssertionError("should not reach here");
            } catch (IllegalStateException e) {
                System.out.println("  subtask.get() before join() threw " + e.getClass().getSimpleName());
            }
            scope.join();
            System.out.println("  after join(): " + profile.get());
        }
        // The API forbids it because before join() returns there is no happens-before edge between the
        // sub-task's write of its result and the owner's read of it, and the sub-task may not even have a
        // result yet. join() is the synchronisation point: it establishes the edge for every fork at once,
        // which is why a Subtask carries no blocking get() of its own.

        System.out.println("[3] homogeneous fan-out: allSuccessfulOrThrow returns values in FORK order");
        try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
            scope.fork(() -> { Thread.sleep(150); return "shard-1 (slowest, forked first)"; });
            scope.fork(() -> { Thread.sleep(120); return "shard-2"; });
            scope.fork(() -> { Thread.sleep(90);  return "shard-3"; });
            scope.fork(() -> { Thread.sleep(60);  return "shard-4"; });
            scope.fork(() -> { Thread.sleep(20);  return "shard-5 (fastest, forked last)"; });
            List<String> results = scope.join();      // Java 26: List<T>, the VALUES, already unwrapped
            results.forEach(r -> System.out.println("    " + r));
        }
        System.out.println("  → completion order was 5,4,3,2,1; the result list is 1..5 — deterministic");

        System.out.println("[4] one branch fails: the siblings are cancelled, not awaited");
        var slowBlocked = new CountDownLatch(1);
        long t1 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
            scope.fork(() -> {
                try {
                    slowBlocked.countDown();
                    Thread.sleep(500);
                    return "never";
                } catch (InterruptedException e) {
                    System.out.println("    slow branch interrupted at " + millisSince(t1)
                            + " ms — cancellation propagated");
                    throw e;
                }
            });
            scope.fork(() -> {
                slowBlocked.await();      // make sure the slow branch is provably blocked first
                Thread.sleep(40);
                throw new IllegalStateException("profile service down");
            });
            scope.join();
            throw new AssertionError("should not reach here");
        } catch (StructuredTaskScope.FailedException e) {
            System.out.println("  join() threw FailedException, cause = " + e.getCause());
        }
        System.out.println("  wall time = " + millisSince(t1)
                + " ms ≈ first-failure time (40 ms), NOT the slow sibling's 500 ms");
        // A sub-task parked in a JDK blocking call (sleep, wait, a BlockingQueue op, socket read) is woken with
        // InterruptedException, as above. A purely CPU-bound branch is never parked, so nothing can wake it: it
        // must poll Thread.currentThread().isInterrupted() in its loop and return early. Cancellation in Java is
        // cooperative all the way down — the scope only delivers the signal.

        System.out.println("Ex121DashboardScope finished");
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
