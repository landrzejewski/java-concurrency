package pl.training.concurrency.exercises.mod009;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exercise 9.1 — Order pipeline with thenCompose and thenCombine.
 *
 * <p>Related: Mod009 §2, §3, §4
 */
public final class Ex91OrderPipeline {

    private Ex91OrderPipeline() {}

    record User(long id, String name) {}
    record Cart(List<String> items) {}
    record Order(String user, long total, String shipping, String coupon) {}

    // Four fake services. Each sleeps, so each belongs on an I/O executor, never on the common pool.
    static User findUser(long id)                { sleep(60);  return new User(id, "alice"); }
    static Cart loadCart(User user)              { sleep(120); return new Cart(List.of("book", "mug")); }
    static long price(Cart cart)                 { sleep(40);  return cart.items().size() * 4900L; }
    static String loadShippingQuote(User user)   { sleep(90);  return "standard/4.90"; }
    static String loadCoupon(User user)          { sleep(70);  return "SPRING-10"; }

    public static void main(String[] args) {
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {

        }

        // thenApply is map: it applies a plain function to the resolved value, so a function that itself
        // returns a future produces CompletableFuture<CompletableFuture<T>>. Every downstream stage would
        // then have to unwrap twice, and an exception inside the INNER future would not propagate to the
        // outer chain at all — exceptionally/handle attached to the outer future would never see it.
        // thenCompose is flatMap: it subscribes to the inner future and completes with its result, so the
        // chain stays one level deep and failures propagate normally.
        System.out.println("Ex91OrderPipeline finished");
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
