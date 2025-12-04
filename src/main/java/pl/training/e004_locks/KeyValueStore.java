package pl.training.e004_locks;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class KeyValueStore<K, V> {

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<K, V> store = new HashMap<>();

    public V get(K key) {
        lock.readLock().lock();
        V value = store.get(key);
        lock.readLock().unlock();
        return value;
    }

    public void put(K key, V value) {
        lock.writeLock().lock();
        store.put(key, value);
        lock.writeLock().unlock();
    }

}
