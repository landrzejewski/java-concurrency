package pl.training.concurrency.exercises.mod004;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Exercise 4.1 — Read-mostly cache with ReentrantReadWriteLock.
 *
 * <p>Related: Mod004 §1, §5
 */
public final class Ex41ReadWriteCache {

    private Ex41ReadWriteCache() {}

    interface Cache<K, V> {
        V get(K key);
        void put(K key, V value);
        V getOrLoad(K key, Function<K, V> loader);
    }

    /** Simulated cost of a lookup inside the critical section — a plain HashMap.get is too cheap to show contention. */
    static final int LOOKUP_COST = 1_000;

    static void busyWork(int iterations) {
        long acc = 0;
        for (int i = 0; i < iterations; i++) {
            acc += (acc ^ i) * 31L;
        }
        if (acc == 42) { // consume the result so the JIT cannot drop the loop as dead code
            System.out.println("unlikely");
        }
    }

    static final class ReadWriteCache<K, V> implements Cache<K, V> {
        private final Map<K, V> map = new HashMap<>();
        private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        private final Lock read = rw.readLock();
        private final Lock write = rw.writeLock();

        @Override public V get(K key) {
            read.lock();
            try {
                busyWork(LOOKUP_COST);
                return map.get(key);
            } finally {
                read.unlock();
            }
        }

        @Override public void put(K key, V value) {
            write.lock();
            try {
                busyWork(LOOKUP_COST);
                map.put(key, value);
            } finally {
                write.unlock();
            }
        }

        @Override public V getOrLoad(K key, Function<K, V> loader) {
            read.lock();
            try {
                busyWork(LOOKUP_COST);
                V value = map.get(key);
                if (value != null) {
                    return value;
                }
            } finally {
                read.unlock(); // must release: there is no read -> write upgrade (see below)
            }
            V value;
            write.lock();
            try {
                value = map.get(key); // re-check: another writer may have loaded it between our unlock and lock
                if (value == null) {
                    value = loader.apply(key);
                    map.put(key, value);
                }
                read.lock();   // downgrade: acquire the read lock while still holding the write lock ...
            } finally {
                write.unlock(); // ... then release the write lock; readers may now proceed, writers still wait
            }
            try {
                return value; // we return holding the read lock, so no writer could replace the entry meanwhile
            } finally {
                read.unlock();
            }
        }
        // Why upgrade is impossible: if two readers both tried to take the write lock while holding their read
        // locks, each would wait for the other's read lock to be released — a guaranteed deadlock. So
        // ReentrantReadWriteLock simply never grants the write lock to a thread that holds the read lock;
        // writeLock().lock() in that situation blocks forever.
    }

    static final class SingleLockCache<K, V> implements Cache<K, V> {
        private final Map<K, V> map = new HashMap<>();
        private final Lock lock = new ReentrantLock();

        @Override public V get(K key) {
            lock.lock();
            try {
                busyWork(LOOKUP_COST);
                return map.get(key);
            } finally {
                lock.unlock();
            }
        }

        @Override public void put(K key, V value) {
            lock.lock();
            try {
                busyWork(LOOKUP_COST);
                map.put(key, value);
            } finally {
                lock.unlock();
            }
        }

        @Override public V getOrLoad(K key, Function<K, V> loader) {
            lock.lock();
            try {
                busyWork(LOOKUP_COST);
                return map.computeIfAbsent(key, loader);
            } finally {
                lock.unlock();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[1] functional check");
        var cache = new ReadWriteCache<Integer, String>();
        var loads = new LongAdder();
        Function<Integer, String> loader = key -> { loads.increment(); return "value-" + key; };
        var threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int n = 0; n < 10_000; n++) {
                    cache.getOrLoad(n % 100, loader);
                }
            });
            threads[i].start();
        }
        for (var thread : threads) {
            thread.join();
        }
        System.out.printf(Locale.ROOT, "  100 distinct keys, loader called %d times (expected 100)%n", loads.sum());

        System.out.println("[2] throughput, 8 readers + 1 writer, 2 s each");
        benchmark("ReentrantReadWriteLock", new ReadWriteCache<>());
        benchmark("ReentrantLock", new SingleLockCache<>());
        // Read/write locks pay off only when the read section is long enough for the extra bookkeeping to be
        // amortised and readers vastly outnumber writers. Try LOOKUP_COST = 0 to see the plain lock win.
        System.out.println("Ex41ReadWriteCache finished");
    }

    private static void benchmark(String label, Cache<Integer, String> cache) throws InterruptedException {
        for (int key = 0; key < 1_000; key++) {
            cache.put(key, "v" + key);
        }
        var stop = new AtomicBoolean();
        var reads = new LongAdder();
        var writes = new LongAdder();
        var workers = new Thread[9];
        for (int i = 0; i < 8; i++) {
            workers[i] = new Thread(() -> {
                var random = ThreadLocalRandom.current();
                while (!stop.get()) {
                    cache.getOrLoad(random.nextInt(1_000), key -> "loaded-" + key);
                    reads.increment();
                }
            }, "reader-" + i);
        }
        workers[8] = new Thread(() -> {
            var random = ThreadLocalRandom.current();
            try {
                while (!stop.get()) {
                    cache.put(random.nextInt(1_000), "updated");
                    writes.increment();
                    Thread.sleep(1); // read-mostly workload: about a thousand writes per second
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "writer");
        for (var worker : workers) {
            worker.start();
        }
        Thread.sleep(2_000);
        stop.set(true);
        for (var worker : workers) {
            worker.join();
        }
        System.out.printf(Locale.ROOT, "  %-24s reads=%,d writes=%,d%n", label, reads.sum(), writes.sum());
    }
}
