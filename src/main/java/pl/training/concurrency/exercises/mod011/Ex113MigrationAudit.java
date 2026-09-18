package pl.training.concurrency.exercises.mod011;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exercise 11.3 — Migrating a service, and where virtual threads do not help.
 *
 * <p>Related: Mod011 §5 and the migration recipe in the module header
 *
 * <p>The migration checklist actually used here:
 * <ol>
 *   <li>Replace {@code newCachedThreadPool()} (and bounded pools used for I/O) with
 *       {@code newVirtualThreadPerTaskExecutor()}. Nothing else in the request path changes.</li>
 *   <li>Audit every {@code ThreadLocal}: a per-thread cache that was harmless at 200 platform threads
 *       is not harmless at 100 000 virtual ones.</li>
 *   <li>Move read-only request context to {@code ScopedValue} (Mod013); keep {@code ThreadLocal} only
 *       for genuinely mutable per-thread scratch space.</li>
 *   <li>Leave CPU-bound work on a fixed pool sized to the core count.</li>
 *   <li>Check the libraries: a DB driver or HTTP client with its own pool does not change threading
 *       model just because your code did.</li>
 *   <li>Benchmark. Virtual threads change scaling, not single-request latency.</li>
 * </ol>
 */
public final class Ex113MigrationAudit {

    private Ex113MigrationAudit() {}

    record Order(long id, String placedAt) {}

    /** Counts how many per-thread caches were ever allocated — the proxy for retained memory. */
    private static final AtomicLong FORMATTERS_ALLOCATED = new AtomicLong();

    /** SimpleDateFormat is not thread-safe, which is the usual excuse for a ThreadLocal cache. */
    private static final ThreadLocal<SimpleDateFormat> FORMATTER = ThreadLocal.withInitial(() -> {
        FORMATTERS_ALLOCATED.incrementAndGet();
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
    });

    /** The request path. Not one line of it changes between the two executors. */
    static Order handleRequest(long id) {
        sleep(5); // a blocking downstream call
        return new Order(id, FORMATTER.get().format(new Date()));
    }

    public static void main(String[] args) throws InterruptedException {
        int requests = 100_000;

        System.out.println("[1] before: newCachedThreadPool");
        FORMATTERS_ALLOCATED.set(0);
        long t0 = System.nanoTime();
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            for (long i = 0; i < 2_000; i++) {           // 2 000, not 100 000 — see the comment below
                final long id = i;
                pool.execute(() -> handleRequest(id));
            }
        }
        System.out.printf(Locale.ROOT, "  2,000 requests in %,d ms, formatters allocated = %,d%n",
                (System.nanoTime() - t0) / 1_000_000, FORMATTERS_ALLOCATED.get());
        System.out.println("  (only 2 000 requests: a cached pool needs one OS thread per CONCURRENT request,");
        System.out.println("   and 100 000 of those is exactly the wall from Exercise 11.1)");

        System.out.println("[2] after: newVirtualThreadPerTaskExecutor — same request path, one line changed");
        FORMATTERS_ALLOCATED.set(0);
        long t1 = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long i = 0; i < requests; i++) {
                final long id = i;
                pool.execute(() -> handleRequest(id));
            }
        }
        long virtualMillis = (System.nanoTime() - t1) / 1_000_000;
        long allocated = FORMATTERS_ALLOCATED.get();
        System.out.printf(Locale.ROOT, "  %,d requests in %,d ms, formatters allocated = %,d%n",
                requests, virtualMillis, allocated);
        System.out.printf(Locale.ROOT, "  → one SimpleDateFormat PER REQUEST (%,d of them), not one per pooled%n",
                allocated);
        System.out.println("    worker. The cache that saved allocations with 200 reused platform threads now");
        System.out.println("    allocates on every single request AND holds each instance alive for the whole");
        System.out.println("    request — a ThreadLocal is never cleaned up early because the thread IS the request.");

        System.out.println("[3] split the context: ScopedValue for the read-only part, ThreadLocal for the buffer");
        long t2 = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long i = 0; i < 1_000; i++) {
                final long id = i;
                pool.execute(() -> ScopedValue.where(TRACE_ID, "trace-" + id).run(() -> renderReceipt(id)));
            }
        }
        System.out.printf(Locale.ROOT, "  1,000 receipts in %,d ms; sample = %s%n",
                (System.nanoTime() - t2) / 1_000_000,
                ScopedValue.where(TRACE_ID, "trace-sample").call(() -> renderReceipt(7)));
        System.out.println("  → TRACE_ID is immutable and inherited by any StructuredTaskScope opened inside the");
        System.out.println("    binding (Mod013 §5); BUFFER stays a ThreadLocal because it is genuinely mutated");

        System.out.println("[4] the CPU-bound report generator stays on a fixed pool");
        long t3 = System.nanoTime();
        var checksum = new AtomicLong();
        try (ExecutorService reports = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors())) {
            for (int i = 0; i < 64; i++) {
                final int seed = i;
                reports.execute(() -> checksum.addAndGet(generateReport(seed)));
            }
        }
        System.out.printf(Locale.ROOT, "  64 reports in %,d ms (checksum %d)%n",
                (System.nanoTime() - t3) / 1_000_000, checksum.get());
        System.out.println("  → mixing the two executors is the right answer, not a compromise: the virtual-thread");
        System.out.println("    executor bounds nothing, so 64 CPU-bound tasks there would all become runnable at");
        System.out.println("    once and thrash the carriers. A fixed pool of N-cores is the natural admission");
        System.out.println("    control for work that never blocks.");

        // Library caveat (step 5 of the checklist): if handleRequest called a JDBC driver with its own
        // 10-connection pool, every virtual thread would still queue behind those 10 connections. Switching
        // application code to virtual threads does not change a library's threading model.
        System.out.println("Ex113MigrationAudit finished");
    }

    private static final ScopedValue<String> TRACE_ID = ScopedValue.newInstance();

    /** Mutable per-thread scratch space — the one case ScopedValue genuinely cannot cover. */
    private static final ThreadLocal<StringBuilder> BUFFER = ThreadLocal.withInitial(StringBuilder::new);

    private static String renderReceipt(long orderId) {
        StringBuilder buffer = BUFFER.get();
        buffer.setLength(0);                       // reused, mutated in place
        buffer.append("order=").append(orderId).append(" trace=").append(TRACE_ID.get());
        return buffer.toString();
    }

    private static long generateReport(int seed) {
        long h = seed;
        for (int i = 0; i < 2_000_000; i++) {
            h = h * 6364136223846793005L + 1442695040888963407L;
            h ^= (h >>> 29);
        }
        return h | 1;
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
