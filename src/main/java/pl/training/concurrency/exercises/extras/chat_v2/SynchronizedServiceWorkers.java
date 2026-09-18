package pl.training.concurrency.exercises.extras.chat_v2;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A thread-safe decorator around a plain {@link ServerWorkers} — separating "what the collection does" from "how it
 * is made safe" is the point of the exercise.
 *
 * <p>Two fixes over the naive version:
 * <ul>
 *   <li>Every {@code lock()} has a matching {@code finally { unlock(); }}. Previously an exception inside the
 *       delegate (a broken socket, for instance) left the lock held and froze the server permanently.</li>
 *   <li>{@code broadcast} no longer runs socket writes under the read lock — see {@link ServerWorkers#snapshot()}.
 *       Holding a lock across blocking I/O lets one stalled client block every other operation.</li>
 * </ul>
 */
class SynchronizedServiceWorkers implements ServerWorkers {

    private final ServerWorkers serverWorkers;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    SynchronizedServiceWorkers(ServerWorkers serverWorkers) {
        this.serverWorkers = serverWorkers;
    }

    @Override
    public void add(Worker worker) {
        lock.writeLock().lock();
        try {
            serverWorkers.add(worker);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(Worker worker) {
        lock.writeLock().lock();
        try {
            serverWorkers.remove(worker);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public java.util.List<Worker> snapshot() {
        lock.readLock().lock();
        try {
            return serverWorkers.snapshot();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void broadcast(String text) {
        // The lock covers only the snapshot; the writes happen outside it.
        for (var worker : snapshot()) {
            worker.send(text);
        }
    }
}
