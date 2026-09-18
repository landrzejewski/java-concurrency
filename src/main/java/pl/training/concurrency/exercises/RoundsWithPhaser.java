package pl.training.concurrency.exercises;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

public final class RoundsWithPhaser {

    private RoundsWithPhaser() {
    }

    private static final int PARTIES = 3;
    private static final int ROUNDS = 3;

    public static void main(String[] args) throws InterruptedException {

        var phaser = new Phaser(PARTIES);
        var completionLog = new ConcurrentLinkedQueue<Integer>();

        List<Thread> workers = IntStream.rangeClosed(1, PARTIES)
                .mapToObj(id -> ThreadUtils.asyncRun("worker-" + id, () -> runRounds(id, phaser, completionLog)))
                .toList();

        System.out.println("[1] " + PARTIES + " workers x " + ROUNDS + " rounds, synchronised by one phaser");
        ThreadUtils.startAndJoin(workers);

        System.out.println("[2] the phaser kept the rounds apart");
        verifyRoundsAreGrouped(new ArrayList<>(completionLog));

        System.out.printf(Locale.ROOT, "  final phase = %d (one advance per completed round), terminated = %b%n",
                phaser.getPhase(), phaser.isTerminated());
        System.out.println("  → the phaser is NOT terminated: every party is still registered. It terminates");
        System.out.println("    only once the registered count drops to zero, which is what arriveAndDeregister()");
        System.out.println("    in TaskWithPhases is for.");

        System.out.println("RoundsWithPhaser finished");
    }

    private static void runRounds(int id, Phaser phaser, ConcurrentLinkedQueue<Integer> completionLog)
            throws InterruptedException {
        for (int round = 1; round <= ROUNDS; round++) {
            Thread.sleep(ThreadLocalRandom.current().nextInt(100, 301));    // the "work"

            completionLog.add(round);
            System.out.printf(Locale.ROOT, "  worker-%d finished round %d (phase = %d)%n",
                    id, round, phaser.getPhase());

            phaser.arriveAndAwaitAdvance();     // arrive, then block until the other two have arrived too
        }
    }

    private static void verifyRoundsAreGrouped(List<Integer> completionLog) {
        if (completionLog.size() != PARTIES * ROUNDS) {
            throw new AssertionError("expected " + PARTIES * ROUNDS + " entries, got " + completionLog.size());
        }
        for (int index = 0; index < completionLog.size(); index++) {
            int expectedRound = index / PARTIES + 1;
            if (completionLog.get(index) != expectedRound) {
                throw new AssertionError("entry " + index + " is round " + completionLog.get(index)
                        + ", expected " + expectedRound + " — a worker ran ahead of the others");
            }
        }
        System.out.println("  completion log = " + completionLog
                + "  ← all " + PARTIES + " entries of a round before any entry of the next");
    }
}
