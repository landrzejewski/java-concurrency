package pl.training.concurrency;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

final class BoundedQueueWithConditions<T> {
    private final Deque<T> items = new ArrayDeque<>();
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    BoundedQueueWithConditions(int capacity) { this.capacity = capacity; }

    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (items.size() == capacity) notFull.await();
            items.addLast(item);
            notEmpty.signal(); // signal exactly one consumer
        } finally { lock.unlock(); }
    }

    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (items.isEmpty()) notEmpty.await();
            T item = items.removeFirst();
            notFull.signal(); // signal exactly one producer
            return item;
        } finally { lock.unlock(); }
    }
}

/*
A StampedLock guards a small block of MUTABLE FIELDS. That restriction is not stylistic — see the comment on
distanceFromOrigin() below for why an optimistic read may never touch a non-thread-safe data structure.
*/
final class MutablePoint {

    private double x, y;
    private final StampedLock lock = new StampedLock();

    void move(double dx, double dy) {
        long stamp = lock.writeLock();
        try { x += dx; y += dy; }
        finally { lock.unlockWrite(stamp); }
    }

    /*
    The optimistic-read idiom: snapshot the fields into locals, THEN validate.

    Between tryOptimisticRead() and validate() we hold no lock at all, so the values we copied may be torn — x from
    before a writer ran and y from after it. That is fine here because the copies are plain doubles and we simply
    discard them when validate() says a writer intervened.

    This is exactly why the body of an optimistic read must only copy simple fields. Doing the same thing over a
    HashMap (value = map.get(key)) would be a bug: a concurrent put that rehashes the table can make get() return a
    wrong value, throw, or loop forever, and validate() runs too late to prevent any of that. If you need optimistic
    reads over a map, use ConcurrentHashMap (Mod005) — it is already lock-free for readers.
    */
    double distanceFromOrigin() {
        long stamp = lock.tryOptimisticRead();
        double currentX = x, currentY = y;          // plain reads, no lock held
        if (!lock.validate(stamp)) {                // a writer ran while we were reading
            stamp = lock.readLock();                // fall back to a real read lock
            try { currentX = x; currentY = y; }
            finally { lock.unlockRead(stamp); }
        }
        return Math.sqrt(currentX * currentX + currentY * currentY);
    }

    /*
    Read lock upgraded to a write lock via tryConvertToWriteLock. The conversion returns 0 when it cannot be done
    without releasing (another reader holds the lock); then we release and take the write lock the long way, and
    re-check the condition because the state may have changed in the gap.
    */
    boolean moveIfAtOrigin(double newX, double newY) {
        long stamp = lock.readLock();
        try {
            while (x == 0.0 && y == 0.0) {
                long writeStamp = lock.tryConvertToWriteLock(stamp);
                if (writeStamp != 0L) {
                    stamp = writeStamp;
                    x = newX; y = newY;
                    return true;
                }
                lock.unlockRead(stamp);
                stamp = lock.writeLock();           // loop re-checks the predicate
            }
            return false;
        } finally {
            lock.unlock(stamp);                     // unlock() works for either kind of stamp
        }
    }

    @Override public String toString() { return "(" + x + ", " + y + ")"; }
}

public final class Mod004ExplicitLocks {

    private Mod004ExplicitLocks() {}

    /*
    ReentrantLock basics

    - ReentrantLock is the explicit, library-level alternative to the synchronized keyword. The semantics are similar
      — same thread can re-enter, monitor-style mutual exclusion, happens-before on unlock — but the API is much
      richer.
    - The mandatory shape is lock(); try { ... } finally { unlock(); }. If you forget the finally, an exception in the
      critical section leaks the lock and every other thread blocks forever.
    - Pros over synchronized: timeout, interruptible acquisition, multiple condition queues per lock, fairness, and
      rich introspection (getOwner, getQueuedThreads, getQueueLength).
    - Cons: more verbose; failure to call unlock is silent; you cannot use try-with-resources directly. In practice,
      prefer synchronized for simple mutual exclusion and ReentrantLock only when you need a feature it provides.
    */
    static void reentrantLockBasics() throws InterruptedException {
        System.out.println("[Section 1] ReentrantLock basics");

        var lock = new ReentrantLock();
        Runnable critical = () -> {
            lock.lock();
            try {
                System.out.println("  enter " + Thread.currentThread().getName()
                        + ", hold count=" + lock.getHoldCount()
                        + ", queueLen=" + lock.getQueueLength());
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                lock.unlock();
            }
        };
        var t1 = new Thread(critical, "A");
        var t2 = new Thread(critical, "B");
        t1.start(); t2.start();
        t1.join(); t2.join();
    }

    /*
    Multiple Conditions on one lock

    - An intrinsic monitor has only one wait set; you must notifyAll and let waiters re-check their predicates. Under
      heavy load, this wakes too many threads.
    - lock.newCondition() creates a separate wait queue tied to the same lock. You can give producers and consumers
      their own conditions ("not full", "not empty"), and signal only the relevant side.
    - Same scaffold as wait/notify: await() releases the lock and parks; signal() or signalAll() wakes one or all
      waiters. Just like wait, awaits must be in a while loop because of spurious wakeups.
    */
    static void twoConditionsPC() throws InterruptedException {
        System.out.println("[Section 2] producer–consumer with two Conditions");

        var queue = new BoundedQueueWithConditions<String>(2);
        var producer = Thread.ofPlatform().name("producer").start(() -> {
            try {
                for (int i = 0; i < 4; i++) {
                    queue.put("doc-" + i);
                    System.out.println("  put doc-" + i);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        var consumer = Thread.ofPlatform().name("consumer").start(() -> {
            try {
                Thread.sleep(40);
                for (int i = 0; i < 4; i++) {
                    System.out.println("  got " + queue.take());
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.join();
        consumer.join();
    }

    /*
    tryLock and tryLock(timeout)

    - lock.tryLock() attempts to acquire the lock and returns true on success or false immediately if it is held by
      someone else. Use it when you want a fallback path instead of blocking — for example, "skip this update if a
      snapshot is in progress".
    - lock.tryLock(timeout, unit) waits up to timeout. Returns false on timeout, throws InterruptedException if
      interrupted. This is the building block for deadlock-resistant code: acquire each lock with a small timeout and
      back off if both locks cannot be obtained.
    - lock.lockInterruptibly() blocks like lock() but responds to interruption. This is the feature a synchronized
      block cannot offer at all (Mod003 §7): a thread queued on a monitor stays queued no matter how often you
      interrupt it. Use lockInterruptibly whenever a waiter must remain cancellable.
    */
    static void tryLockWithTimeout() throws InterruptedException {
        System.out.println("[Section 3] tryLock with timeout");

        var lock = new ReentrantLock();
        // Holder grabs the lock for 200ms.
        var holder = Thread.ofPlatform().name("holder").start(() -> {
            lock.lock();
            try { Thread.sleep(200); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { lock.unlock(); }
        });
        Thread.sleep(20);

        // Contender waits up to 50ms — fails, takes the fallback path.
        boolean acquiredFast = lock.tryLock(50, TimeUnit.MILLISECONDS);
        System.out.println("  tryLock(50ms) -> " + acquiredFast + " (fallback path)");
        if (acquiredFast) lock.unlock();

        // Contender waits up to 500ms — succeeds.
        boolean acquiredSlow = lock.tryLock(500, TimeUnit.MILLISECONDS);
        System.out.println("  tryLock(500ms) -> " + acquiredSlow);
        if (acquiredSlow) lock.unlock();
        holder.join();

        // (c) lockInterruptibly — a waiter that CAN be cancelled. Compare with Mod003 §7, where a
        // thread queued on a synchronized block ignores interrupts entirely.
        var busy = new ReentrantLock();
        busy.lock();
        try {
            var waiter = Thread.ofPlatform().name("interruptible-waiter").start(() -> {
                try {
                    busy.lockInterruptibly();
                    try { System.out.println("  waiter acquired the lock"); }
                    finally { busy.unlock(); }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("  lockInterruptibly() threw InterruptedException — waiter gave up");
                }
            });
            Thread.sleep(50);
            waiter.interrupt();   // this DOES wake the waiter, unlike a synchronized block
            waiter.join();
        } finally {
            busy.unlock();
        }
    }

    /*
    Fairness

    - A fair lock grants the lock in FIFO order: the longest-waiting thread always wins. Construct one with
      new ReentrantLock(true).
    - A non-fair lock (the default) lets a newly arriving thread "barge" past the queue if it happens to find the lock
      free. This is faster on average — barging keeps cores busy — but a thread can starve indefinitely under
      contention.
    - Use fairness when starvation is observable and the throughput cost is acceptable; use the default everywhere
      else.
    */
    static void fairness() throws InterruptedException {
        System.out.println("[Section 4] fairness");

        var fair = new ReentrantLock(true);
        var order = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        Runnable body = () -> {
            for (int i = 0; i < 3; i++) {
                fair.lock();
                try {
                    order.add(Thread.currentThread().getName());
                    Thread.sleep(5);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { fair.unlock(); }
            }
        };
        var a = new Thread(body, "A");
        var b = new Thread(body, "B");
        var c = new Thread(body, "C");
        a.start(); b.start(); c.start();
        a.join(); b.join(); c.join();
        System.out.println("  fair acquisition order: " + order);
    }

    /*
    ReentrantReadWriteLock

    - A read–write lock has two underlying locks: any number of threads can hold the read lock simultaneously, but the
      write lock is exclusive against both readers and other writers.
    - Best for read-heavy workloads. Under read-mostly traffic, readers run in parallel; under write-heavy traffic the
      read–write protocol adds overhead with no payoff.
    - Same lock()/unlock() shape, but you must take the right side: readLock() or writeLock(). A reader cannot upgrade
      to a writer atomically (you must release the read lock first), but a writer holding the write lock can downgrade
      by acquiring the read lock and then releasing the write lock.
    - The API is older than StampedLock and lacks optimistic reads — see §6.
    */
    static void readWriteLock() throws InterruptedException {
        System.out.println("[Section 5] ReentrantReadWriteLock");

        var rwLock = new ReentrantReadWriteLock();
        var store = new HashMap<String, Integer>();
        store.put("a", 1);

        Runnable reader = () -> {
            rwLock.readLock().lock();
            try { System.out.println("  reader sees a=" + store.get("a")); }
            finally { rwLock.readLock().unlock(); }
        };
        Runnable writer = () -> {
            rwLock.writeLock().lock();
            try { store.put("a", store.get("a") + 1); System.out.println("  writer incremented a"); }
            finally { rwLock.writeLock().unlock(); }
        };

        // Concurrent readers + occasional writers.
        Thread r1 = new Thread(reader), r2 = new Thread(reader), w = new Thread(writer);
        r1.start(); r2.start(); w.start();
        r1.join(); r2.join(); w.join();

        System.out.println("  final a=" + store.get("a"));
    }

    /*
    StampedLock and optimistic reads

    - StampedLock (Java 8+) provides three modes: write, read, and optimistic read. Each lock/unlock returns a long
      stamp used to release or validate.
    - Optimistic read: call tryOptimisticRead(), snapshot the fields, then call validate(stamp). If no writer has
      acquired the lock in between, your snapshot is consistent and you skipped the read-lock cost entirely. Otherwise
      you fall back to the conventional readLock().
    - StampedLock is not reentrant and does not support Condition. Treat it as a high-performance specialist for
      read-mostly hot paths, not a drop-in replacement for ReentrantReadWriteLock.
    - tryConvertToWriteLock(stamp) lets you upgrade a read stamp to a write stamp without releasing first; it can
      return 0 if conversion is impossible, in which case you release and acquire the write lock conventionally.
    - Hard rule for optimistic reads: the body between tryOptimisticRead() and validate() runs with NO lock held,
      so it may only copy plain fields into local variables. Never call into a data structure that is not itself
      safe under concurrent mutation — validate() tells you afterwards that your snapshot was dirty, which is too
      late if the read has already thrown or looped forever.
    */
    static void stampedLock() throws InterruptedException {
        System.out.println("[Section 6] StampedLock");

        var point = new MutablePoint();

        // 8 readers running optimistic reads + 1 writer mutating the point.
        var readers = new Thread[8];
        var reads = new java.util.concurrent.atomic.AtomicLong();
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                long start = System.nanoTime();
                while (System.nanoTime() - start < 100_000_000L) { // 100ms
                    if (point.distanceFromOrigin() >= 0) reads.incrementAndGet();
                }
            });
        }
        var writer = new Thread(() -> {
            long start = System.nanoTime();
            while (System.nanoTime() - start < 100_000_000L) {
                point.move(1, 1);
                try { Thread.sleep(2); } catch (InterruptedException e) { return; }
            }
        });

        for (var r : readers) r.start();
        writer.start();
        for (var r : readers) r.join();
        writer.join();
        System.out.println("  " + reads.get() + " optimistic reads while a writer was mutating "
                + point + " concurrently");

        // Read lock upgraded to a write lock.
        var fresh = new MutablePoint();
        System.out.println("  moveIfAtOrigin on a fresh point  -> " + fresh.moveIfAtOrigin(3, 4)
                + " (now " + fresh + ")");
        System.out.println("  moveIfAtOrigin on a moved point  -> " + fresh.moveIfAtOrigin(9, 9)
                + " (still " + fresh + ")");
    }

    public static void main(String[] args) throws InterruptedException {
        reentrantLockBasics();
        twoConditionsPC();
        tryLockWithTimeout();
        fairness();
        readWriteLock();
        stampedLock();
        System.out.println("Mod004ExplicitLocks finished");
    }
}
