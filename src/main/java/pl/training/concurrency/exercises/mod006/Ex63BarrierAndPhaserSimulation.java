package pl.training.concurrency.exercises.mod006;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

/**
 * Exercise 6.3 — Iterative simulation with CyclicBarrier and Phaser (bonus: Exchanger double buffering).
 *
 * <p>Related: Mod006 §3, §4, §5
 */
public final class Ex63BarrierAndPhaserSimulation {

    private Ex63BarrierAndPhaserSimulation() {}

    static final int CELLS = 64;
    static final int SLICES = 4;
    static final int ROUNDS = 5;

    /** Double buffer: workers read 'current' and write 'next'; the barrier action swaps them between rounds. */
    static final class Grid {
        int[] current;
        int[] next;

        Grid(int[] initial) {
            current = initial.clone();
            next = new int[initial.length];
        }

        /** Each cell becomes the average of itself and its neighbours. Returns true if the slice changed. */
        boolean step(int from, int to) {
            boolean changed = false;
            for (int i = from; i < to; i++) {
                int left = current[Math.max(i - 1, 0)];
                int right = current[Math.min(i + 1, current.length - 1)];
                next[i] = (left + current[i] + right) / 3;
                changed |= next[i] != current[i];
            }
            return changed;
        }

        void swap() {
            int[] tmp = current;
            current = next;
            next = tmp;
        }

        long checksum() {
            return Arrays.stream(current).asLongStream().sum();
        }
    }

    static int[] randomCells() {
        var cells = new int[CELLS];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = ThreadLocalRandom.current().nextInt(0, 1_000);
        }
        return cells;
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[1] CyclicBarrier, " + ROUNDS + " rounds");
        runWithBarrier(new Grid(randomCells()), -1);

        System.out.println("[2] CyclicBarrier, worker 0 fails in round 3");
        runWithBarrier(new Grid(randomCells()), 3);

        System.out.println("[3] Phaser with a worker that deregisters once its slice is stable");
        runWithPhaser();

        System.out.println("[4] Exchanger double buffering");
        runWithExchanger();

        System.out.println("Ex63BarrierAndPhaserSimulation finished");
    }

    private static void runWithBarrier(Grid grid, int failingRound) throws InterruptedException {
        int[] round = {0};
        var barrier = new CyclicBarrier(SLICES, () -> {   // barrier action runs once per round, by the last arriver
            grid.swap();
            round[0]++;
            System.out.printf(Locale.ROOT, "  round %d checksum=%d%n", round[0], grid.checksum());
        });
        var workers = new ArrayList<Thread>();
        for (int s = 0; s < SLICES; s++) {
            int slice = s;
            workers.add(Thread.ofPlatform().name("slice-" + s).unstarted(() -> {
                try {
                    for (int r = 1; r <= ROUNDS; r++) {
                        if (slice == 0 && r == failingRound) {
                            throw new IllegalStateException("slice-0 crashed in round " + r);
                        }
                        grid.step(slice * CELLS / SLICES, (slice + 1) * CELLS / SLICES);
                        barrier.await(1, TimeUnit.SECONDS); // timed, so a missing party does not hang us forever
                    }
                } catch (TimeoutException e) {
                    System.out.println("  " + Thread.currentThread().getName() + ": TimeoutException — my await timed out, barrier is now broken");
                } catch (BrokenBarrierException e) {
                    System.out.println("  " + Thread.currentThread().getName() + ": BrokenBarrierException — another party broke it");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException e) {
                    System.out.println("  " + Thread.currentThread().getName() + ": " + e.getMessage());
                }
            }));
        }
        workers.forEach(Thread::start);
        for (var worker : workers) {
            worker.join();
        }
        System.out.println("  barrier broken: " + barrier.isBroken());
    }

    private static void runWithPhaser() throws InterruptedException {
        var cells = randomCells();
        // make the last slice (and the neighbouring cell) constant, so that it is stable from round 1
        Arrays.fill(cells, CELLS - CELLS / SLICES - 1, CELLS, 500);
        var grid = new Grid(cells);

        var phaser = new Phaser() {
            @Override protected boolean onAdvance(int phase, int registeredParties) {
                grid.swap();
                System.out.printf(Locale.ROOT, "  round %d done, checksum=%d, parties left=%d%n",
                        phase + 1, grid.checksum(), registeredParties);
                return phase + 1 >= ROUNDS || registeredParties == 0; // true terminates the phaser
            }
        };

        var workers = new ArrayList<Thread>();
        for (int s = 0; s < SLICES; s++) {
            int slice = s;
            phaser.register(); // one party per worker, registered before the worker starts
            workers.add(Thread.ofPlatform().name("slice-" + s).unstarted(() -> {
                while (!phaser.isTerminated()) {
                    boolean changed = grid.step(slice * CELLS / SLICES, (slice + 1) * CELLS / SLICES);
                    if (!changed) {
                        System.out.println("  " + Thread.currentThread().getName() + " is stable, deregistering");
                        phaser.arriveAndDeregister(); // leaves; the others no longer wait for it
                        return;
                    }
                    phaser.arriveAndAwaitAdvance();
                }
            }));
        }
        workers.forEach(Thread::start);
        for (var worker : workers) {
            worker.join();
        }
        System.out.println("  phaser terminated: " + phaser.isTerminated());
    }

    private static void runWithExchanger() throws InterruptedException {
        var exchanger = new Exchanger<int[]>();
        var producer = Thread.ofPlatform().name("producer").unstarted(() -> {
            int[] buffer = new int[8];
            try {
                for (int round = 1; round <= 3; round++) {
                    Arrays.fill(buffer, round);            // fill "our" buffer ...
                    buffer = exchanger.exchange(buffer);   // ... swap it for the consumer's empty one
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var consumer = Thread.ofPlatform().name("consumer").unstarted(() -> {
            int[] buffer = new int[8];
            try {
                for (int round = 1; round <= 3; round++) {
                    buffer = exchanger.exchange(buffer);   // hand over the drained buffer, receive a full one
                    System.out.printf(Locale.ROOT, "  consumer got round %d, sum=%d%n", round, Arrays.stream(buffer).sum());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        List.of(producer, consumer).forEach(Thread::start);
        producer.join();
        consumer.join();
    }
}
