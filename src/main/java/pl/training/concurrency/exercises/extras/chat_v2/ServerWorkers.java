package pl.training.concurrency.exercises.extras.chat_v2;

import java.util.List;

interface ServerWorkers {

    void add(Worker worker);

    void remove(Worker worker);

    /** A point-in-time copy, so callers can iterate without holding any lock. */
    List<Worker> snapshot();

    void broadcast(String text);
}
