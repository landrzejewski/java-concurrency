package pl.training.concurrency.exercises.mod012;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercise 12.2 — Races, partial results and a scope deadline.
 *
 * <p>Related: Mod012 §4, §5, §7
 *
 * <p>Compile and run with {@code --enable-preview}.
 */
public final class Ex122RacesAndDeadlines {

    private Ex122RacesAndDeadlines() {}

    public static void main(String[] args) throws InterruptedException {

        System.out.println("[1] anySuccessfulOrThrow — first success wins, the rest are cancelled");
        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
            Subtask<String> primary = scope.fork(() -> { Thread.sleep(200); return "primary"; });
            scope.fork(() -> { Thread.sleep(40); return "mirror-1"; });
            scope.fork(() -> { Thread.sleep(90); return "mirror-2"; });

            String winner = scope.join();               // returns the value directly, typed T
            System.out.println("  winner = " + winner + " after " + millisSince(t0) + " ms");
            System.out.println("  losing primary state = " + primary.state()
                    + ", scope.isCancelled() = " + scope.isCancelled());
        }

        System.out.println("[2] every mirror fails");
        try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
            scope.fork(() -> { Thread.sleep(20);  throw new IllegalStateException("mirror-1 down"); });
            scope.fork(() -> { Thread.sleep(60);  throw new IllegalStateException("mirror-2 down"); });
            scope.fork(() -> { Thread.sleep(100); throw new IllegalStateException("mirror-3 down"); });
            scope.join();
            throw new AssertionError("should not reach here");
        } catch (StructuredTaskScope.FailedException e) {
            System.out.println("  join() threw " + e.getClass().getSimpleName() + ", cause = " + e.getCause());
            System.out.println("  → the cause is ONE of the failures, in practice the first to fail:");
            System.out.println("    a later failure never displaces the stored one");
        }

        System.out.println("[3] allUntil(predicate) — stop after the first 3 successes of 10");
        int threshold = 3;
        var successes = new AtomicInteger();   // touched from every completing sub-task's thread
        long t1 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(Joiner.<Integer>allUntil(subtask -> {
            if (subtask.state() == Subtask.State.SUCCESS) successes.incrementAndGet();
            return successes.get() >= threshold;
        }))) {
            for (int i = 0; i < 10; i++) {
                final int latency = 30 + i * 40;
                scope.fork(() -> { Thread.sleep(latency); return latency; });
            }
            List<Subtask<Integer>> subtasks = scope.join();   // Java 26: List<Subtask<T>>, ALL of them
            var values = subtasks.stream()
                    .filter(s -> s.state() == Subtask.State.SUCCESS)
                    .map(Subtask::get)
                    .toList();
            long cancelled = subtasks.stream()
                    .filter(s -> s.state() == Subtask.State.UNAVAILABLE)
                    .count();
            System.out.println("  first " + threshold + " latencies = " + values
                    + ", cancelled mid-flight = " + cancelled
                    + ", wall time = " + millisSince(t1) + " ms");
            System.out.println("  → filter on state() before get(): get() on an UNAVAILABLE subtask throws");
        }
        // The predicate runs every time a sub-task FINISHES, on that sub-task's own thread — so several
        // invocations can overlap. A plain int counter here would lose increments and the scope would either
        // stop too late or never stop at all; hence the AtomicInteger.

        System.out.println("[4] a deadline for the whole fan-out");
        long t2 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow(),
                config -> config.withName("dashboard").withTimeout(Duration.ofMillis(100)))) {
            scope.fork(() -> { Thread.sleep(30); return "fast"; });
            scope.fork(() -> { Thread.sleep(5_000); return "way too slow"; });
            scope.join();
            throw new AssertionError("should not reach here");
        } catch (StructuredTaskScope.TimeoutException e) {
            System.out.println("  TimeoutException after " + millisSince(t2)
                    + " ms (NOT 5 000 ms) — the slow sub-task was cancelled, not abandoned");
        }

        System.out.println("[5] a named virtual-thread factory makes dumps readable");
        ThreadFactory named = Thread.ofVirtual().name("mirror-", 0).factory();
        try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow(),
                config -> config.withName("mirrors").withThreadFactory(named))) {
            scope.fork(() -> Thread.currentThread().getName());
            scope.fork(() -> Thread.currentThread().getName());
            System.out.println("  sub-task threads: " + scope.join());
        }

        // Why a scope deadline beats a per-future orTimeout (Mod009 §6):
        //  - orTimeout bounds ONE stage. The work behind it keeps running, holding its socket and its memory,
        //    and a fan-out of five calls needs five separate timeouts that can each fire independently.
        //  - withTimeout bounds the WHOLE fan-out with one number, and when it fires the runtime cancels every
        //    still-running sub-task — so the request stops paying for results it will throw away.
        //  - The deadline is also the thing you actually promised your caller. Five 100 ms stage timeouts do not
        //    add up to a 100 ms request.
        System.out.println("Ex122RacesAndDeadlines finished");
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
