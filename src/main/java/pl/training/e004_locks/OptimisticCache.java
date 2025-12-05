package pl.training.e004_locks;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.StampedLock;

public class OptimisticCache<K, V> {
    
    private final Map<K, V> cache = new HashMap<>();
    private final StampedLock lock = new StampedLock();
    
    public V get(K key) {
        long stamp = lock.tryOptimisticRead();
        V value = cache.get(key);
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                value = cache.get(key);
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return value;
    }
    
    public void put(K key, V value) {
        long stamp = lock.writeLock();
        try {
            cache.put(key, value);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    public V remove(K key) {
        long stamp = lock.writeLock();
        try {
            return cache.remove(key);
        } finally {
            lock.unlockWrite(stamp);
        }
    }
    
    // Compute if absent - pokazuje wzorzec read -> convert to write
    public V computeIfAbsent(K key, java.util.function.Function<K, V> mappingFunction) {
        // Najpierw sprawdzamy z read lock czy wartość istnieje
        long stamp = lock.readLock();
        try {
            V value = cache.get(key);
            if (value != null) {
                return value;
            }
            
            // Wartości nie ma - próbujemy konwersji do write lock
            long writeStamp = lock.tryConvertToWriteLock(stamp);
            
            if (writeStamp != 0L) {
                // Konwersja udana
                stamp = writeStamp;
                // Double-check (ktoś mógł dodać w międzyczasie)
                value = cache.get(key);
                if (value == null) {
                    value = mappingFunction.apply(key);
                    cache.put(key, value);
                }
                return value;
            } else {
                // Konwersja nie powiodła się - zwalniamy i bierzemy write lock
                lock.unlockRead(stamp);
                stamp = lock.writeLock();
                
                value = cache.get(key);
                if (value == null) {
                    value = mappingFunction.apply(key);
                    cache.put(key, value);
                }
                return value;
            }
        } finally {
            lock.unlock(stamp);
        }
    }
    
    // Test wydajnościowy
    void main() throws InterruptedException {
        OptimisticCache<String, Integer> cache = new OptimisticCache<>();
        
        // Wstępne wypełnienie
        for (int i = 0; i < 100; i++) {
            cache.put("key" + i, i);
        }
        
        System.out.println("=== Test wydajnościowy: 90% odczytów, 10% zapisów ===\n");
        
        // 10 wątków odczytu
        Thread[] readers = new Thread[10];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                int operations = 0;
                long startTime = System.currentTimeMillis();
                
                while (System.currentTimeMillis() - startTime < 2000) {
                    String key = "key" + (int) (Math.random() * 100);
                    Integer value = cache.get(key);
                    operations++;
                }
                
                System.out.println(Thread.currentThread().getName() + 
                                 ": " + operations + " odczytów");
            }, "Reader-" + i);
        }
        
        // 2 wątki zapisu
        Thread[] writers = new Thread[2];
        for (int i = 0; i < writers.length; i++) {
            writers[i] = new Thread(() -> {
                int operations = 0;
                long startTime = System.currentTimeMillis();
                
                while (System.currentTimeMillis() - startTime < 2000) {
                    String key = "key" + (int) (Math.random() * 100);
                    cache.put(key, (int) (Math.random() * 1000));
                    operations++;
                    
                    try {
                        Thread.sleep(10); // Zapisy rzadsze
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                
                System.out.println(Thread.currentThread().getName() + 
                                 ": " + operations + " zapisów");
            }, "Writer-" + i);
        }
        
        // Start
        for (Thread reader : readers) reader.start();
        for (Thread writer : writers) writer.start();
        
        // Join
        for (Thread reader : readers) reader.join();
        for (Thread writer : writers) writer.join();
        
        System.out.println("\nTest zakończony");
    }
}