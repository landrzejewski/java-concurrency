package pl.training.concurrency.exercises;

import java.util.Locale;

/**
 * Small helpers shared by the examples in this package.
 *
 * <p>Note how both methods RESTORE the interrupt flag instead of swallowing it. A helper that catches
 * InterruptedException and does nothing (or only prints a stack trace) silently disables cancellation for every
 * caller — see Mod001 §8 on the cooperative interrupt protocol. Callers that loop must therefore check
 * {@link Thread#isInterrupted()} themselves.
 */
public final class ThreadUtils {

    private ThreadUtils() {
    }

    /**
     * Wraps a task that may throw InterruptedException in an unstarted platform thread.
     * A cancelled task leaves the thread's interrupt flag set.
     */
    public static Thread asyncRun(Task task) {
        return new Thread(() -> {
            try {
                task.run();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Same as {@link #asyncRun(Task)} but names the thread — always name threads you may have to debug. */
    public static Thread asyncRun(String name, Task task) {
        var thread = asyncRun(task);
        thread.setName(name);
        return thread;
    }

    /**
     * Sleeps, restoring the interrupt flag if interrupted.
     *
     * @return true if the full duration elapsed, false if the sleep was cut short by an interrupt
     */
    public static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void printWithThreadName(String text) {
        System.out.printf(Locale.ROOT, "%s (%s)%n", text, Thread.currentThread().getName());
    }

    /** Starts every thread, then waits for all of them. */
    public static void startAndJoin(Iterable<Thread> threads) throws InterruptedException {
        threads.forEach(Thread::start);
        for (var thread : threads) {
            thread.join();
        }
    }
}
