package pl.training.concurrency.exercises.mod013;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exercise 13.3 — Migrating a ThreadLocal filter chain.
 *
 * <p>Related: Mod013 §1, §6, §7
 *
 * <p>Before / after, side by side:
 * <pre>
 * // before
 * static final ThreadLocal&lt;String&gt; USER = new ThreadLocal&lt;&gt;();
 * void handle(Request request) {
 *     USER.set(request.user());
 *     try { chain.proceed(); }
 *     finally { USER.remove(); }      // easy to forget — and fatal on a pooled thread
 * }
 * String currentUser() { return USER.get(); }
 *
 * // after
 * static final ScopedValue&lt;String&gt; USER = ScopedValue.newInstance();
 * void handle(Request request) {
 *     ScopedValue.where(USER, request.user()).run(chain::proceed);
 * }
 * String currentUser() { return USER.get(); }   // throws if unbound
 * </pre>
 *
 * <p>The migrated version gains exactly three properties:
 * <ol>
 *   <li><b>Exception safety</b> — the binding is unwound by the runtime, so there is no try/finally to forget.</li>
 *   <li><b>Immutability</b> — no setter exists, so the value cannot be rewritten mid-request by code the
 *       request handler never looked at.</li>
 *   <li><b>No cross-request leakage</b> — the value cannot outlive the run() call, so a pooled worker can
 *       never carry it into the next request.</li>
 * </ol>
 */
public final class Ex133ThreadLocalMigration {

    private Ex133ThreadLocalMigration() {}

    record Request(long id, String user) {}

    private static final ThreadLocal<String> TL_USER = new ThreadLocal<>();
    private static final ScopedValue<String> SV_USER = ScopedValue.newInstance();

    public static void main(String[] args) throws InterruptedException {
        int requests = 200;

        System.out.println("[1] the leak: ThreadLocal without remove() on a POOLED thread");
        var leaked = new ConcurrentLinkedQueue<String>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {   // 4 workers, 200 requests -> heavy reuse
            for (long i = 0; i < requests; i++) {
                var request = new Request(i, "user-" + i);
                pool.execute(() -> {
                    String inherited = TL_USER.get();          // whatever the PREVIOUS request left behind
                    if (inherited != null && !inherited.equals(request.user())) {
                        leaked.add(request.user() + " started out seeing " + inherited);
                    }
                    TL_USER.set(request.user());
                    // no remove() — the value survives on this worker into the next request
                });
            }
        }
        System.out.printf(Locale.ROOT, "  %,d of %,d requests began by seeing the PREVIOUS request's user%n",
                leaked.size(), requests);
        System.out.println("  e.g. " + leaked.peek());

        System.out.println("[2] after the migration: the same test cannot leak");
        var leakedAfter = new ConcurrentLinkedQueue<String>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (long i = 0; i < requests; i++) {
                var request = new Request(i, "user-" + i);
                pool.execute(() -> ScopedValue.where(SV_USER, request.user()).run(() -> {
                    if (!SV_USER.get().equals(request.user())) {
                        leakedAfter.add(request.user() + " saw " + SV_USER.get());
                    }
                }));
            }
        }
        System.out.println("  leaks = " + leakedAfter.size() + ", and there is no try/finally anywhere");
        System.out.println("  outside every binding, SV_USER.isBound() = " + SV_USER.isBound()
                + " — the worker carries nothing between requests");

        System.out.println("[3] how long each one stays reachable after the request body ends");
        var report = new ConcurrentLinkedQueue<String>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.execute(() -> {
                TL_USER.set("user-1");
                requestBody();                                  // the "request scope" ends here
                report.add("  ThreadLocal after the body returned: TL_USER.get() = " + TL_USER.get()
                        + "  ← still reachable; only remove() or thread death releases it");
                TL_USER.remove();
                report.add("  ThreadLocal after remove()         : TL_USER.get() = " + TL_USER.get());
            });
            pool.execute(() -> {
                ScopedValue.where(SV_USER, "user-2").run(Ex133ThreadLocalMigration::requestBody);
                report.add("  ScopedValue after run() returned   : SV_USER.isBound() = " + SV_USER.isBound()
                        + "  ← the binding is already gone, with no cleanup call");
            });
        }
        report.forEach(System.out::println);
        System.out.println("  → that difference is deterministic, and it is the one worth designing around:");
        System.out.println("    a ThreadLocal value is released when someone REMEMBERS to release it, a binding");
        System.out.println("    when the block exits. With virtual threads the thread IS the request, so every");
        System.out.println("    in-flight request holds its whole ThreadLocalMap for its entire lifetime, and the");
        System.out.println("    cost scales with a concurrency that is now hundreds of thousands, not hundreds.");
        // What this deliberately does NOT claim is a byte count. Comparing Runtime.totalMemory()-freeMemory()
        // for 50 000 parked virtual threads under each scheme was tried and thrown away: the ranking FLIPPED
        // when the two measurements swapped order, because at that scale the reading is dominated by whatever
        // the collector happened to have done, not by the thing being measured. If you want that number, take
        // a real heap dump, or measure with JFR's TLAB/allocation events — not with System.gc() and a subtraction.

        System.out.println("[4] the one thing ScopedValue cannot replace");
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long i = 0; i < 3; i++) {
                var request = new Request(i, "user-" + i);
                pool.execute(() -> ScopedValue.where(SV_USER, request.user())
                        .run(() -> System.out.println("  " + renderReceipt(request.id()))));
            }
        }
        // BUFFER below is MUTATED in place — setLength(0), append, append. A ScopedValue has no setter and its
        // value is frozen for the lifetime of the binding, so it cannot express "reusable scratch space that
        // methods write into". That is exactly what ThreadLocal is still for: a mutable, thread-affine cache.
        // The rule: read-only request context -> ScopedValue; mutable per-thread buffer -> ThreadLocal.

        System.out.println("Ex133ThreadLocalMigration finished");
    }

    /** Mutable per-thread scratch space — kept as a ThreadLocal on purpose. */
    private static final ThreadLocal<StringBuilder> BUFFER = ThreadLocal.withInitial(StringBuilder::new);

    private static String renderReceipt(long orderId) {
        StringBuilder buffer = BUFFER.get();
        buffer.setLength(0);
        buffer.append("receipt order=").append(orderId).append(" user=").append(SV_USER.get());
        return buffer.toString();
    }

    /** Stands in for the rest of the request: filters, service, repository. */
    private static void requestBody() {
        sleep(5);
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

}
