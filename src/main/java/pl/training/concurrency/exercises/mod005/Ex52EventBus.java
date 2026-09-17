package pl.training.concurrency.exercises.mod005;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercise 5.2 — Event bus with CopyOnWriteArrayList.
 *
 * <p>Related: Mod005 §3, §8
 */
public final class Ex52EventBus {

    private Ex52EventBus() {}

    interface Listener {
        void onEvent(String event);
    }

    interface EventBus {
        void subscribe(Listener listener);
        void unsubscribe(Listener listener);
        void publish(String event);
    }

    /** ArrayList + synchronized: safe against other threads, but not against re-entrant modification. */
    static final class SynchronizedListBus implements EventBus {
        private final List<Listener> listeners = new ArrayList<>();

        @Override public synchronized void subscribe(Listener listener) { listeners.add(listener); }
        @Override public synchronized void unsubscribe(Listener listener) { listeners.remove(listener); }

        @Override public synchronized void publish(String event) {
            for (var listener : listeners) {        // fail-fast iterator ...
                listener.onEvent(event);            // ... a listener that unsubscribes re-enters the monitor and
            }                                       //     modifies the list -> ConcurrentModificationException
        }
    }

    /** CopyOnWriteArrayList: every mutation copies the array, iteration walks the snapshot it started with. */
    static final class CopyOnWriteBus implements EventBus {
        private final List<Listener> listeners = new CopyOnWriteArrayList<>();

        @Override public void subscribe(Listener listener) { listeners.add(listener); }
        @Override public void unsubscribe(Listener listener) { listeners.remove(listener); }

        @Override public void publish(String event) {
            for (var listener : listeners) {
                listener.onEvent(event);
            }
        }
    }

    /** Unsubscribes itself and registers a replacement while the bus is publishing to it. */
    static final class OneShotListener implements Listener {
        private final EventBus bus;
        final CountingListener replacement = new CountingListener("replacement");

        OneShotListener(EventBus bus) { this.bus = bus; }

        @Override public void onEvent(String event) {
            System.out.println("  one-shot got '" + event + "', unsubscribing and adding a replacement");
            bus.unsubscribe(this);
            bus.subscribe(replacement);
        }
    }

    static final class CountingListener implements Listener {
        final String name;
        final AtomicInteger received = new AtomicInteger();

        CountingListener(String name) { this.name = name; }

        @Override public void onEvent(String event) { received.incrementAndGet(); }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[1] ArrayList + synchronized");
        try {
            var bus = new SynchronizedListBus();
            bus.subscribe(new OneShotListener(bus));
            bus.subscribe(new CountingListener("plain"));
            bus.publish("e1");
        } catch (ConcurrentModificationException e) {
            System.out.println("  publish threw ConcurrentModificationException");
        }

        System.out.println("[2] CopyOnWriteArrayList");
        var bus = new CopyOnWriteBus();
        var oneShot = new OneShotListener(bus);
        var plain = new CountingListener("plain");
        bus.subscribe(oneShot);
        bus.subscribe(plain);
        bus.publish("e1");
        bus.publish("e2");
        System.out.printf(Locale.ROOT, "  plain received %d, replacement received %d (subscribed during e1, so it missed it)%n",
                plain.received.get(), oneShot.replacement.received.get());

        System.out.println("[3] 1 publisher + 4 subscribe/unsubscribe churn threads");
        var stop = new AtomicBoolean();
        var failures = new AtomicInteger();
        var churn = new ArrayList<Thread>();
        for (int i = 0; i < 4; i++) {
            churn.add(Thread.ofPlatform().name("churn-" + i).start(() -> {
                var mine = new CountingListener(Thread.currentThread().getName());
                try {
                    while (!stop.get()) {
                        bus.subscribe(mine);
                        bus.unsubscribe(mine);
                    }
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                }
            }));
        }
        try {
            for (int n = 0; n < 2_000; n++) {
                bus.publish("event-" + n);
            }
        } catch (RuntimeException e) {
            failures.incrementAndGet();
        }
        stop.set(true);
        for (var thread : churn) {
            thread.join();
        }
        System.out.printf(Locale.ROOT, "  plain received %d of 2002 events, failures=%d -> %s%n",
                plain.received.get(), failures.get(), plain.received.get() == 2_002 && failures.get() == 0 ? "OK" : "BROKEN");
        // Iteration semantics: ArrayList iterators are fail-fast (they detect structural changes and throw);
        // CopyOnWriteArrayList iterators are snapshot iterators — they never throw, never see later changes and
        // do not support remove(). The cost is a full array copy per mutation, so it fits listener registries
        // (rare writes, frequent reads) and nothing else.
        System.out.println("Ex52EventBus finished");
    }
}
