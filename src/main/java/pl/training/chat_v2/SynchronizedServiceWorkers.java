package pl.training.chat_v2;

import lombok.RequiredArgsConstructor;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@RequiredArgsConstructor
class SynchronizedServiceWorkers implements ServerWorkers {

    private final ServerWorkers serverWorkers;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public void add(Worker worker) {
        lock.writeLock().lock();
        serverWorkers.add(worker);
        lock.writeLock().unlock();
    }

    @Override
    public void remove(Worker worker) {
        lock.writeLock().lock();
        serverWorkers.remove(worker);
        lock.writeLock().unlock();
    }

    @Override
    public void broadcast(String text) {
        lock.readLock().lock();
        serverWorkers.broadcast(text);
        lock.readLock().unlock();
    }

}
