package pl.training.concurrency.exercises.mod001;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exercise 1.2 — Cooperative cancellation of a downloader.
 *
 * <p>Related: Mod001 §6, §8
 */
public final class Ex12CooperativeDownloader {

    private Ex12CooperativeDownloader() {}

    static final int CHUNKS = 100;

    /** Variant A: the standard interrupt protocol. */
    static final class Downloader implements Runnable {
        volatile long stoppedAt;

        @Override public void run() {
            try {
                for (int chunk = 1; chunk <= CHUNKS; chunk++) {
                    Thread.sleep(50); // the interruption point
                    if (chunk % 5 == 0) {
                        System.out.printf(Locale.ROOT, "  downloaded %d/%d%n", chunk, CHUNKS);
                    }
                }
                System.out.println("  download complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // restore the flag for whoever runs us
                System.out.println("  download cancelled");
            } finally {
                System.out.println("  connection closed, temp file removed");
                stoppedAt = System.nanoTime();
            }
        }
    }

    /** Variant B: a flag instead of the interrupt. Works only as long as the loop polls the flag often enough. */
    static final class FlagDownloader implements Runnable {
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        volatile long stoppedAt;

        void stop() { stopRequested.set(true); }

        @Override public void run() {
            try {
                int chunk = 0;
                while (chunk < CHUNKS && !stopRequested.get()) {
                    Thread.sleep(50);
                    chunk++;
                }
                System.out.printf(Locale.ROOT, "  flag downloader stopped after %d chunks, stopRequested=%b%n",
                        chunk, stopRequested.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stoppedAt = System.nanoTime();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[A] interrupt protocol");
        var downloader = new Downloader();
        var thread = Thread.ofPlatform().name("downloader").start(downloader);
        Thread.sleep(500);
        long t0 = System.nanoTime();
        thread.interrupt();
        thread.join();
        System.out.printf(Locale.ROOT, "  stopped %.1f ms after interrupt()%n", (downloader.stoppedAt - t0) / 1e6);

        System.out.println("[B] AtomicBoolean flag");
        var flagged = new FlagDownloader();
        var flagThread = Thread.ofPlatform().name("flag-downloader").start(flagged);
        Thread.sleep(500);
        t0 = System.nanoTime();
        flagged.stop();
        flagThread.join();
        System.out.printf(Locale.ROOT, "  stopped %.1f ms after stop()%n", (flagged.stoppedAt - t0) / 1e6);
        // What breaks with a flag: it is only observed when the loop gets around to reading it. Here every chunk
        // sleeps 50 ms, so the latency is bounded by 50 ms — but if a chunk blocked in a long sleep, a socket read
        // or a lock, stop() would have no effect until that call returned on its own. interrupt() wakes the thread
        // out of sleep/wait/join immediately; a flag cannot.

        System.out.println("Ex12CooperativeDownloader finished");
    }
}
