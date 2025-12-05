package pl.training.chat_v2;

import java.util.HashSet;
import java.util.Set;

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
    public void broadcast(String text) {
        workers.forEach(worker -> worker.send(text));
    }

}
