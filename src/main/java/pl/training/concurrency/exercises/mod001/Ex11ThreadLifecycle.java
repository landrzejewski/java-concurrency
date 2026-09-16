package pl.training.concurrency.exercises.mod001;

import java.util.List;
import java.util.Locale;

/**
 * Exercise 1.1 — Thread builder and lifecycle logger.
 *
 * <p>Related: Mod001 §2, §3, §4, §5
 */
public final class Ex11ThreadLifecycle {

    private Ex11ThreadLifecycle() {}

    static final class Sleeper implements Runnable {
        @Override public void run() {
            var current = Thread.currentThread();
            System.out.printf(Locale.ROOT, "  task running on %s (daemon=%b)%n", current.getName(), current.isDaemon());
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                current.interrupt();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        var task = new Sleeper();
        var threads = List.of(
                Thread.ofPlatform().name("alpha").unstarted(task),
                Thread.ofPlatform().name("beta").unstarted(task),
                Thread.ofPlatform().name("gamma").daemon(true).unstarted(task));

        System.out.println("[1] after creation");
        threads.forEach(Ex11ThreadLifecycle::printState);

        threads.forEach(Thread::start);
        Thread.sleep(100); // let them reach Thread.sleep
        System.out.println("[2] while sleeping");
        threads.forEach(Ex11ThreadLifecycle::printState);

        for (var thread : threads) {
            thread.join();
        }
        System.out.println("[3] after join");
        threads.forEach(Ex11ThreadLifecycle::printState);

        System.out.println("[4] run() vs start()");
        var delta = Thread.ofPlatform().name("delta").unstarted(
                () -> System.out.println("  executing on: " + Thread.currentThread().getName()));
        delta.run();   // ordinary method call: the Runnable executes on the caller (main), no new thread
        delta.start(); // asks the JVM for a new OS thread and invokes run() there
        delta.join();
        // run() is just a method of the Runnable. Only start() creates a thread; it may be called once per Thread
        // object (a second start() throws IllegalThreadStateException).

        System.out.println("Ex11ThreadLifecycle finished");
    }

    private static void printState(Thread thread) {
        System.out.printf(Locale.ROOT, "  %-6s %s%n", thread.getName(), thread.getState());
    }
}
