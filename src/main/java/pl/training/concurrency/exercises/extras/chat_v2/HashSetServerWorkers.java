package pl.training.concurrency.exercises.extras.chat_v2;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deliberately NOT thread-safe — it is the plain collection that
 * {@link SynchronizedServiceWorkers} decorates.
 */
class HashSetServerWorkers implements ServerWorkers {

    private final Set<Worker> workers = new HashSet<>();

    @Override
    public void add(Worker worker) {
        workers.add(worker);
    }

    @Override
    public void remove(Worker worker) {
        workers.remove(worker);
    }

    @Override
    public List<Worker> snapshot() {
        return List.copyOf(workers);
    }

    @Override
    public void broadcast(String text) {
        workers.forEach(worker -> worker.send(text));
    }
}
