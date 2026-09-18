package pl.training.concurrency.exercises.mod011;

import java.util.Locale;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exercise 11.2 — Carriers, pinning and JFR diagnostics.
 *
 * <p>Related: Mod011 §1, §4
 */
public final class Ex112CarriersAndPinning {

    private Ex112CarriersAndPinning() {}

    public static void main(String[] args) throws InterruptedException {

        System.out.println("[1] 1000 virtual threads multiplexed onto a handful of carriers");
        int virtualThreads = 1_000;
        var carriers = new ConcurrentSkipListSet<String>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < virtualThreads; i++) {
                exec.execute(() -> {
                    carriers.add(currentCarrierName());
                    sleep(10); // a JDK blocking call: the scheduler unmounts us and frees the carrier
                });
            }
        }
        int processors = Runtime.getRuntime().availableProcessors();
        System.out.printf(Locale.ROOT, "  %,d virtual threads ran on %d distinct carriers (availableProcessors = %d)%n",
                virtualThreads, carriers.size(), processors);
        System.out.println("  carriers: " + carriers);

        System.out.println("[2] identifying a virtual thread");
        Thread.ofVirtual().name("vt-greeter").start(() -> {
            Thread self = Thread.currentThread();
            System.out.println("  virtual : name=" + self.getName() + ", isVirtual=" + self.isVirtual()
                    + ", toString=" + self);
        }).join();
        Thread.ofPlatform().name("pt-greeter").start(() -> {
            Thread self = Thread.currentThread();
            System.out.println("  platform: name=" + self.getName() + ", isVirtual=" + self.isVirtual()
                    + ", toString=" + self);
        }).join();
        System.out.println("  → names given to a virtual thread show up in thread dumps and in JFR events,");
        System.out.println("    which is the only reason to bother naming a thread you never look up by name");

        System.out.println("[3] recording pinning with JFR");
        System.out.println("  java -XX:StartFlightRecording=filename=vt.jfr --enable-preview \\");
        System.out.println("       -cp target/classes pl.training.concurrency.exercises.mod011.Ex112CarriersAndPinning");
        System.out.println("  jfr print --events jdk.VirtualThreadPinned vt.jfr");
        System.out.println("  companion events: jdk.VirtualThreadStart, jdk.VirtualThreadEnd,");
        System.out.println("                    jdk.VirtualThreadSubmitFailed");
        System.out.println("  expect ZERO jdk.VirtualThreadPinned events on Java 24+, including from [4] below —");
        System.out.println("  that empty result IS the result: it is what JEP 491 changed");
        // -Djdk.tracePinnedThreads was REMOVED in Java 24 together with the pinning it diagnosed. JEP 491 made
        // the JVM unmount a virtual thread from inside a synchronized block, so the classic "replace every
        // synchronized with a ReentrantLock" advice no longer applies on Java 24+. What can still pin a virtual
        // thread to its carrier is a JNI call (and a few JVM-internal critical sections), which is rare in
        // application code. jdk.VirtualThreadPinned is enabled in JFR's default profile and records a stack
        // trace for every pinned interval, so it points straight at the frame responsible.

        System.out.println("[4] a synchronized block no longer blocks the carrier pool (Java 24+)");
        var lock = new Object();
        var carriersUnderLock = new ConcurrentSkipListSet<String>();
        long t0 = System.nanoTime();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                exec.execute(() -> {
                    synchronized (lock) {
                        carriersUnderLock.add(currentCarrierName());
                        sleep(5); // blocking WHILE holding a monitor — the classic pinning shape
                    }
                });
            }
        }
        System.out.printf(Locale.ROOT, "  200 virtual threads slept 5 ms each inside a synchronized block: %,d ms%n",
                (System.nanoTime() - t0) / 1_000_000);
        System.out.println("  (they serialise on the monitor — that is the lock, not pinning. On Java <24 the");
        System.out.println("   carriers would ALSO have been held hostage; JFR would have shown 200 pinned events)");

        System.out.println("Ex112CarriersAndPinning finished");
    }

    /**
     * There is no public "which carrier am I on" API. A virtual thread's toString happens to render as
     * {@code VirtualThread[#id]/state@carrierName}, so we scrape the part after the last '@'.
     *
     * <p>That format is UNDOCUMENTED and may change between releases — fine for a classroom demo, never for
     * production code. Use the JFR events from [3] instead.
     */
    private static String currentCarrierName() {
        return Thread.currentThread().toString().replaceAll(".*@", "");
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
