package pl.training.concurrency.exercises.mod009;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercise 9.3 — Dashboard fan-out and the cancellation gap.
 *
 * <p>Related: Mod009 §8, §9
 */
public final class Ex93FanOutAndCancellation {

    private Ex93FanOutAndCancellation() {}

    static String fetchProfile()      { sleep(80);  return "alice"; }
    static List<String> fetchOrders() { sleep(120); return List.of("o-1", "o-2"); }
    static List<String> fetchRecos()  { sleep(70);  return List.of("r-1", "r-2", "r-3"); }

    public static void main(String[] args) throws InterruptedException {

        System.out.println("[1] fan-out on the common pool (wrong for blocking I/O)");
        long t0 = System.nanoTime();
        var profile = CompletableFuture.supplyAsync(Ex93FanOutAndCancellation::fetchProfile);
        var orders  = CompletableFuture.supplyAsync(Ex93FanOutAndCancellation::fetchOrders);
        var recos   = CompletableFuture.supplyAsync(Ex93FanOutAndCancellation::fetchRecos);
        CompletableFuture.allOf(profile, orders, recos).join();
        System.out.println("  " + render(profile.join(), orders.join(), recos.join()));
        long commonMillis = (System.nanoTime() - t0) / 1_000_000;
        // The common pool is sized availableProcessors()-1 and is SHARED with every parallel stream and every
        // other supplyAsync in the JVM. Three sleeping branches occupy three of those workers doing nothing,
        // so unrelated CPU-bound code elsewhere loses parallelism. Blocking work needs its own executor.

        System.out.println("[2] same fan-out on a virtual-thread executor (right)");
        long t1 = System.nanoTime();
        String dashboard;
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            var p = CompletableFuture.supplyAsync(Ex93FanOutAndCancellation::fetchProfile, io);
            var o = CompletableFuture.supplyAsync(Ex93FanOutAndCancellation::fetchOrders, io);
            var r = CompletableFuture.supplyAsync(Ex93FanOutAndCancellation::fetchRecos, io);
            dashboard = CompletableFuture.allOf(p, o, r)
                    .thenApplyAsync(v -> render(p.join(), o.join(), r.join()), io)
                    .join();
        }
        long virtualMillis = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("  " + dashboard);
        System.out.printf(Locale.ROOT,
                "  common pool %d ms | virtual threads %d ms | sum of branch latencies = 270 ms%n",
                commonMillis, virtualMillis);
        System.out.println("  → both ≈ the slowest branch (120 ms); the executor choice is about isolation, not speed");

        System.out.println("[3] one branch fails — the siblings keep running and keep burning resources");
        var finished = new AtomicInteger();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            var failing = CompletableFuture.supplyAsync(() -> {
                sleep(40);
                throw new IllegalStateException("profile service down");
            }, io);
            var survivor1 = CompletableFuture.runAsync(() -> { sleep(300); finished.incrementAndGet(); }, io);
            var survivor2 = CompletableFuture.runAsync(() -> { sleep(350); finished.incrementAndGet(); }, io);
            try {
                CompletableFuture.allOf(failing, survivor1, survivor2).join();
            } catch (Exception e) {
                System.out.println("  allOf reported: " + e.getCause());
            }
            // close() waits for the still-running siblings — proof that nothing cancelled them.
        }
        System.out.println("  siblings that ran to completion after the failure = " + finished.get() + "/2");

        System.out.println("[4] cancel(true) on a RUNNING task does not interrupt it");
        var ran = new AtomicInteger();
        var interrupted = new AtomicInteger();
        // Hand-off: without this latch cancel() can beat the pool to the task, which would never run at all —
        // the counter would then look as if cancellation had worked, the exact opposite of the point.
        var started = new CountDownLatch(1);
        var target = CompletableFuture.runAsync(() -> {
            started.countDown();
            try { Thread.sleep(200); }
            catch (InterruptedException e) { interrupted.incrementAndGet(); Thread.currentThread().interrupt(); return; }
            ran.incrementAndGet();
        });
        var sibling = CompletableFuture.runAsync(() -> { sleep(250); ran.incrementAndGet(); });

        started.await();                 // the task is provably running now
        boolean cancelled = target.cancel(true);
        Thread.sleep(400);               // give both tasks time to finish
        System.out.println("  cancel(true) returned " + cancelled
                + ", future.isCancelled() = " + target.isCancelled());
        System.out.println("  tasks interrupted = " + interrupted.get()
                + ", tasks that ran to the end anyway = " + ran.get() + "/2 (the cancelled one included)");
        System.out.println("  sibling untouched: isDone = " + sibling.isDone());

        // With StructuredTaskScope (Mod012 §3, §8) all three points invert:
        //   - the scope owns every sub-task thread, so closing it cancels what is still running;
        //   - Joiner.allSuccessfulOrThrow() cancels the siblings the moment one branch throws, so [3] would
        //     print 0/2 and the wall time would be the first-failure time, not the slowest branch;
        //   - the cancellation in [4] arrives as a real InterruptedException in the sub-task.
        System.out.println("Ex93FanOutAndCancellation finished");
    }

    private static String render(String profile, List<String> orders, List<String> recos) {
        return "user=" + profile + ", orders=" + orders + ", recommendations=" + recos;
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
