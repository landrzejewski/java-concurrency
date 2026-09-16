package pl.training.concurrency.exercises.mod001;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exercise 1.3 — Waiting for workers with join(Duration) and a shutdown hook.
 *
 * <p>Related: Mod001 §5, §7, §9
 */
public final class Ex13JoinDeadlineAndShutdownHook {

    private Ex13JoinDeadlineAndShutdownHook() {}

    /** Set to false to see the JVM wait for the stragglers before it exits. */
    static final boolean STRAGGLERS_AS_DAEMONS = true;
    static final int WORKERS = 6;
    static final Duration DEADLINE = Duration.ofSeconds(1);

    public static void main(String[] args) throws InterruptedException {
        List<Thread> workers = new ArrayList<>();
        for (int i = 1; i <= WORKERS; i++) {
            long millis = ThreadLocalRandom.current().nextLong(100, 2000);
            var worker = Thread.ofPlatform().name("worker-" + i).daemon(STRAGGLERS_AS_DAEMONS).unstarted(() -> {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            System.out.printf(Locale.ROOT, "  %s will take %d ms%n", worker.getName(), millis);
            workers.add(worker);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            var alive = workers.stream().filter(Thread::isAlive).map(Thread::getName).toList();
            System.out.println("[hook] still alive at shutdown: " + alive);
        }, "shutdown-hook"));

        workers.forEach(Thread::start);

        long deadlineNanos = System.nanoTime() + DEADLINE.toNanos();
        var finished = new ArrayList<String>();
        var stragglers = new ArrayList<String>();
        for (var worker : workers) {
            long remaining = deadlineNanos - System.nanoTime();
            // join(Duration) returns true when the thread terminated and false on timeout (Java 19+).
            // A zero/negative duration means "do not wait" — it just reports whether the thread is done.
            boolean done = worker.join(Duration.ofNanos(Math.max(remaining, 0)));
            (done ? finished : stragglers).add(worker.getName());
        }
        System.out.println("finished in time: " + finished);
        System.out.println("still running:    " + stragglers);

        // Daemon stragglers: the JVM exits as soon as main returns and the hook lists them as alive.
        // User-thread stragglers (STRAGGLERS_AS_DAEMONS = false): the JVM keeps running until every worker
        // terminates, so the hook runs later and reports an empty list.
        System.out.println("Ex13JoinDeadlineAndShutdownHook finished (main)");
    }
}
