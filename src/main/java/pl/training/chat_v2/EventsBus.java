package pl.training.chat_v2;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.Collections.synchronizedSet;

class EventsBus {

    private final Set<Consumer<ServerEvent>> consumers = synchronizedSet(new HashSet<>());

    void addConsumer(Consumer<ServerEvent> consumer) {
        consumers.add(consumer);
    }

    void publish(ServerEvent event) {
        consumers.forEach(consumer -> consumer.accept(event));
    }

}
