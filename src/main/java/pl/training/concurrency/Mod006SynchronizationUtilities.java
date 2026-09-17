package pl.training.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/*
Decision matrix

  Need                                                          Pick
  ------------------------------------------------------------- ----------------------
  Bound concurrent access to N resources                        Semaphore
  One-shot "wait until K events have happened"                  CountDownLatch
  Repeated rendezvous of a fixed set of threads                 CyclicBarrier
  Multi-phase rendezvous, parties added/removed between phases  Phaser
  Two threads need to swap data                                 Exchanger
  Bounded handoff between many producers and many consumers     BlockingQueue (Mod005)
*/

public final class Mod006SynchronizationUtilities {

    private Mod006SynchronizationUtilities() {}

    /*
    Semaphore

    - A Semaphore holds a non-negative count of permits. acquire() blocks until a permit is available and then takes
      one; release() returns one.
    - Use it to bound concurrent access to a finite resource: a connection pool of N connections, a printer that
      allows K simultaneous jobs, an outbound API that rate-limits to R parallel calls.
    - A semaphore with a single permit is essentially a non-reentrant mutex. Unlike a ReentrantLock, the same thread
      cannot re-acquire it without releasing first, and a permit can be released by a different thread than the one
      that acquired it — useful for hand-off patterns.
    - tryAcquire() is the non-blocking variant; tryAcquire(timeout, unit) waits at most the given time. Both should be
      paired with release() in finally.
    */
    static void semaphore() throws InterruptedException {
        System.out.println("[Section 1] Semaphore — bounded printer");

        var printerCapacity = new Semaphore(2); // 2 printers in the room
        Runnable job = () -> {
            try {
                printerCapacity.acquire();
                try {
                    System.out.println("  " + Thread.currentThread().getName()
                            + " printing (active=" + (2 - printerCapacity.availablePermits()) + ")");
                    Thread.sleep(50);
                } finally {
                    printerCapacity.release();
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        var ts = new ArrayList<Thread>();
        for (int i = 0; i < 6; i++) ts.add(Thread.ofPlatform().name("worker-" + i).unstarted(job));
        for (var t : ts) t.start();
        for (var t : ts) t.join();
    }

    /*
    CountDownLatch

    - A CountDownLatch(N) is a one-shot counter that waiters can await() until N calls of countDown() have happened.
      The count cannot be reset.
    - Use it for one-time rendezvous: "wait for K services to come up", "wait for N preload tasks to finish before
      serving traffic", "wait for the start signal".
    - await(timeout, unit) returns false instead of throwing if the latch has not fired in time.
    - For repeating rendezvous use CyclicBarrier (§3); for dynamic, multi-phase coordination use Phaser (§4).
    */
    static void countDownLatch() throws InterruptedException {
        System.out.println("[Section 2] CountDownLatch — start signal + completion");

        var startGun = new CountDownLatch(1);     // fires once to release racers
        var finishLine = new CountDownLatch(4);   // fires when last racer finishes

        for (int i = 0; i < 4; i++) {
            final int id = i;
            Thread.ofPlatform().name("runner-" + id).start(() -> {
                try {
                    startGun.await();
                    Thread.sleep(20 + ThreadLocalRandom.current().nextInt(60));
                    System.out.println("  runner-" + id + " finished");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { finishLine.countDown(); }
            });
        }
        Thread.sleep(50);
        System.out.println("  --- start! ---");
        startGun.countDown();
        finishLine.await();
        System.out.println("  all runners home");
    }

    /*
    CyclicBarrier

    - A CyclicBarrier(parties) makes parties threads wait for each other at a common point. Once they all arrive, the
      barrier trips: all threads are released and the barrier can be reused for the next round.
    - Optional barrier action runs in one of the trip threads each round — useful for end-of-phase aggregation in
      iterative algorithms.
    - If any thread leaves the barrier abnormally (interrupt, timeout), the barrier becomes broken and other parties
      get BrokenBarrierException. Call reset() to put it back in a usable state.
    - Typical use: parallel iterative simulations where every step must finish before the next begins.
    */
    static void cyclicBarrier() throws InterruptedException {
        System.out.println("[Section 3] CyclicBarrier — iterative simulation");

        int parties = 3, rounds = 3;
        var barrier = new CyclicBarrier(parties,
                () -> System.out.println("  --- barrier action: round complete ---"));

        Runnable worker = () -> {
            for (int r = 0; r < rounds; r++) {
                try {
                    Thread.sleep(10 + ThreadLocalRandom.current().nextInt(30));
                    System.out.println("  " + Thread.currentThread().getName() + " finished round " + r);
                    barrier.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        };
        var ts = new ArrayList<Thread>();
        for (int i = 0; i < parties; i++) ts.add(Thread.ofPlatform().name("sim-" + i).unstarted(worker));
        for (var t : ts) t.start();
        for (var t : ts) t.join();
    }

    /*
    Phaser

    - Phaser generalises both CountDownLatch and CyclicBarrier:
      - The number of registered parties can change at runtime (register/arriveAndDeregister).
      - There can be many phases.
    - arriveAndAwaitAdvance() is the analogue of CyclicBarrier.await(). Phase numbers (getPhase()) increase on each
      advance.
    - A party can drop out without breaking the rest by calling arriveAndDeregister.
    - Override onAdvance(phase, registeredParties) to inject a barrier action and to terminate the phaser when it
      returns true.
    - Use Phaser when the number of participants varies between phases (e.g., recursion-style parallel decompositions,
      multi-stage pipelines with optional stages).
    */
    static void phaser() throws InterruptedException {
        System.out.println("[Section 4] Phaser — pipeline with dynamic parties");

        var phaser = new Phaser(1) { // start with 1 (the orchestrator)
            @Override protected boolean onAdvance(int phase, int registeredParties) {
                // registeredParties is the number of parties REGISTERED at the moment the phase advanced
                // (deregistrations that happened during the phase are already reflected).
                System.out.println("  >> phase " + phase + " complete, registered parties = " + registeredParties);
                return registeredParties == 0; // terminate when everyone leaves
            }
        };

        // 4 workers, but worker[3] drops out after phase 1.
        for (int i = 0; i < 4; i++) {
            final int id = i;
            phaser.register();
            Thread.ofPlatform().name("worker-" + id).start(() -> {
                System.out.println("  worker-" + id + " phase " + phaser.getPhase() + ": working");
                phaser.arriveAndAwaitAdvance(); // end of phase 0

                if (id == 3) {
                    System.out.println("  worker-3 dropping out");
                    phaser.arriveAndDeregister();
                    return;
                }

                System.out.println("  worker-" + id + " phase " + phaser.getPhase() + ": working");
                phaser.arriveAndAwaitAdvance(); // end of phase 1

                System.out.println("  worker-" + id + " phase " + phaser.getPhase() + ": final flush");
                phaser.arriveAndDeregister();
            });
        }

        // Orchestrator participates in every phase too.
        phaser.arriveAndAwaitAdvance();
        phaser.arriveAndAwaitAdvance();
        phaser.arriveAndDeregister();

        // Wait for the phaser to terminate.
        while (!phaser.isTerminated()) Thread.sleep(20);
    }

    /*
    Exchanger

    - An Exchanger<V> is a synchronous data swap point for a pair of threads. Each thread calls exchange(v); both
      calls block until the other arrives, then each receives the partner's value.
    - Use cases are narrow: pipeline buffer hand-off, double buffering between a producer and a consumer where each
      party reuses a structure that was filled (or emptied) by the other.
    - For more than two threads use a BlockingQueue; for synchronization without data movement use a barrier.
    */
    static void exchanger() throws InterruptedException {
        System.out.println("[Section 5] Exchanger — double buffering");

        var swap = new Exchanger<List<String>>();

        var producer = Thread.ofPlatform().name("producer").start(() -> {
            List<String> buffer = new ArrayList<>();
            try {
                for (int round = 0; round < 3; round++) {
                    while (buffer.size() < 3) buffer.add("item-r" + round + "-" + buffer.size());
                    System.out.println("  producer offering: " + buffer);
                    buffer = swap.exchange(buffer); // get the consumer's empty buffer back
                    buffer.clear();
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        var consumer = Thread.ofPlatform().name("consumer").start(() -> {
            List<String> emptyBuf = new ArrayList<>();
            try {
                for (int round = 0; round < 3; round++) {
                    var full = swap.exchange(emptyBuf);
                    System.out.println("  consumer received: " + full);
                    // Hand this same buffer back next round. It still holds the items we just read —
                    // the PRODUCER clears it immediately after receiving it, which is what makes the
                    // pair of buffers alternate without either side allocating.
                    emptyBuf = full;
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        producer.join();
        consumer.join();
    }

    public static void main(String[] args) throws InterruptedException {
        semaphore();
        countDownLatch();
        cyclicBarrier();
        phaser();
        exchanger();
        System.out.println("Mod006SynchronizationUtilities finished");
    }
}
