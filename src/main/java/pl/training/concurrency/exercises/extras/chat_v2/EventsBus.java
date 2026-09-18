package pl.training.concurrency.exercises.extras.chat_v2;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * A minimal synchronous event bus.
 *
 * <p>It used to hold a {@code Collections.synchronizedSet(new HashSet<>())}. That is a classic trap: the wrapper
 * synchronises each individual method, but NOT iteration. {@code consumers.forEach(...)} therefore ran unguarded
 * and could throw {@link java.util.ConcurrentModificationException} the moment a consumer registered while an
 * event was being published — exactly the failure mode Mod005 §1 and §8 describe.
 *
 * <p>{@link CopyOnWriteArraySet} is the right structure for a listener registry: writes are rare, reads are
 * constant, iteration is lock-free and never throws (Mod005 §3).
 *
 * <p>Note that publish() is synchronous: consumers run on the calling thread. A slow consumer therefore slows down
 * the worker that produced the event. Making it asynchronous means adding a queue and deciding what to do when it
 * fills up (Mod005 §4) — a deliberate design choice, not an oversight.
 */
class EventsBus {

    private final Set<Consumer<ServerEvent>> consumers = new CopyOnWriteArraySet<>();

    void addConsumer(Consumer<ServerEvent> consumer) {
        consumers.add(consumer);
    }

    void removeConsumer(Consumer<ServerEvent> consumer) {
        consumers.remove(consumer);
    }

    void publish(ServerEvent event) {
        consumers.forEach(consumer -> consumer.accept(event));
    }
}
