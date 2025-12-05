package pl.training.chat_v3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class Connections {

    private final List<Connection> connections = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    void add(Connection connection) {
        lock.writeLock().lock();
        connections.add(connection);
        lock.writeLock().unlock();
    }

    void broadcast(String message) {
        lock.readLock().lock();
        connections.forEach(worker -> worker.send(message));
        lock.readLock().unlock();
    }

    void remove(Connection connection) {
        lock.writeLock().lock();
        connections.remove(connection);
        lock.writeLock().unlock();
    }

}
