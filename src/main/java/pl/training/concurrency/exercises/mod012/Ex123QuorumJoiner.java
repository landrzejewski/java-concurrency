package pl.training.concurrency.exercises.mod012;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercise 12.3 — Custom quorum Joiner.
 *
 * <p>Related: Mod012 §6
 *
 * <p>Compile and run with {@code --enable-preview}.
 */
public final class Ex123QuorumJoiner {

    private Ex123QuorumJoiner() {}

    /**
     * Short-circuits as soon as {@code quorum} sub-tasks have succeeded, and also as soon as enough of them
     * have failed that reaching the quorum has become arithmetically impossible.
     *
     * <p>Thread-safety: {@code onComplete} runs on the COMPLETING SUB-TASK's thread and may be invoked
     * concurrently by several of them, while {@code onFork} and {@code onTimeout} run on the scope owner's
     * thread. Every field below is therefore either a concurrent collection or an atomic, and
     * {@code onComplete} decides using the value it got back from its own atomic update rather than a
     * separate read — two sub-tasks finishing at once must not both believe they were the q-th success.
     *
     * @param <T> the sub-task result type
     */
    static final class QuorumJoiner<T> implements Joiner<T, List<T>> {

        private final int quorum;
        private final ConcurrentLinkedQueue<T> successes = new ConcurrentLinkedQueue<>();
        private final AtomicInteger succeeded = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger forked = new AtomicInteger();
        private volatile boolean timedOut;

        QuorumJoiner(int quorum) {
            if (quorum < 1) throw new IllegalArgumentException("quorum must be >= 1");
            this.quorum = quorum;
        }

        @Override
        public boolean onFork(Subtask<T> subtask) {
            forked.incrementAndGet();
            return false;               // never short-circuit at fork time
        }

        @Override
        public boolean onComplete(Subtask<T> subtask) {
            if (subtask.state() == Subtask.State.SUCCESS) {
                successes.add(subtask.get());
                return succeeded.incrementAndGet() == quorum;   // exactly one caller sees == quorum
            }
            int failures = failed.incrementAndGet();
            // Impossible once the survivors can no longer reach the quorum.
            boolean hopeless = forked.get() - failures < quorum;
            if (hopeless) {
                System.out.println("    quorum became unreachable after " + failures + " failures");
            }
            return hopeless;
        }

        @Override
        public void onTimeout() {
            // The default implementation throws TimeoutException. Overriding it without throwing lets join()
            // fall through to result() and hand back whatever arrived before the deadline. The runtime has
            // ALREADY cancelled the scope at this point — this does not let the sub-tasks run on.
            timedOut = true;
        }

        @Override
        public List<T> result() {
            if (!timedOut && succeeded.get() < quorum) {
                throw new IllegalStateException("quorum of " + quorum + " not reached: "
                        + succeeded.get() + " succeeded, " + failed.get() + " failed");
            }
            return new ArrayList<>(successes);
        }

        int cancelledCount() { return forked.get() - succeeded.get() - failed.get(); }
    }

    /** Replica latencies in ms; a negative value means "fails after |latency| ms". */
    private static final int[] REPLICAS = { 40, -50, 60, 80, 100, -120, 200 };

    public static void main(String[] args) throws InterruptedException {
        int quorum = 4;

        System.out.println("[1] 7 replicas, quorum of " + quorum);
        var joiner = new QuorumJoiner<String>(quorum);
        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(joiner)) {
            for (int latency : REPLICAS) {
                scope.fork(() -> replica(latency));
            }
            List<String> values = scope.join();
            long millis = millisSince(t0);
            System.out.println("  values    = " + values);
            System.out.printf(Locale.ROOT, "  cancelled = %d, wall time = %,d ms%n",
                    joiner.cancelledCount(), millis);
            // Successes in latency order: 40, 60, 80, 100 -> the 4th-fastest SUCCESS is at 100 ms.
            System.out.println("  → ≈ the 4th-fastest successful replica (100 ms), not the slowest (200 ms)");
            if (values.size() != quorum) throw new AssertionError("expected exactly the quorum");
        }

        System.out.println("[2] too many failures — the joiner gives up early instead of waiting");
        long t1 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(new QuorumJoiner<String>(4))) {
            scope.fork(() -> replica(30));
            scope.fork(() -> replica(-40));
            scope.fork(() -> replica(-50));
            scope.fork(() -> replica(-60));
            scope.fork(() -> replica(5_000));      // would take 5 seconds
            scope.join();
            throw new AssertionError("should not reach here");
        } catch (StructuredTaskScope.FailedException e) {
            // Whatever result() throws is delivered by join() wrapped in FailedException, exactly like a
            // sub-task's own failure — so a caller only ever has to handle the one exception type.
            System.out.println("  " + e.getCause().getMessage() + " after " + millisSince(t1) + " ms (NOT 5 000 ms)");
        }

        System.out.println("[3] a deadline yields the partial quorum instead of throwing");
        var partial = new QuorumJoiner<String>(5);
        long t2 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(partial,
                config -> config.withName("quorum-read").withTimeout(Duration.ofMillis(120)))) {
            for (int latency : REPLICAS) {
                scope.fork(() -> replica(latency));
            }
            List<String> values = scope.join();    // onTimeout() does not throw, so we get here
            System.out.println("  after " + millisSince(t2) + " ms: " + values.size()
                    + " of 5 required replicas answered -> " + values);
        }

        // allUntil(predicate) (Exercise 12.2) covers the happy half of this: stop after N successes. What it
        // cannot express is the other two rules — give up early once the quorum is unreachable, and downgrade
        // to a partial answer on timeout — because its predicate can only say "stop", not "stop and fail" or
        // "stop and salvage", and it has nowhere to put the aggregation. Write a custom joiner when the
        // stopping rule and the result shape belong together; otherwise allUntil is less code.
        System.out.println("Ex123QuorumJoiner finished");
    }

    private static String replica(int latency) throws InterruptedException {
        Thread.sleep(Math.abs(latency));
        if (latency < 0) throw new IllegalStateException("replica(" + latency + ") down");
        return "replica-" + latency + "ms";
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
