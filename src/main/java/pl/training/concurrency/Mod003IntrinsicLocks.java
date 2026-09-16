package pl.training.concurrency;

import java.util.ArrayDeque;
import java.util.Deque;

final class BoundedQueue<T> {
    private final Deque<T> items = new ArrayDeque<>();
    private final int capacity;
    BoundedQueue(int capacity) { this.capacity = capacity; }

    public synchronized void put(T item) throws InterruptedException {
        // while-not-if: re-check predicate after every wakeup
        while (items.size() == capacity) wait();
        items.addLast(item);
        notifyAll(); // wake any consumer that was waiting on "not empty"
    }

    public synchronized T take() throws InterruptedException {
        while (items.isEmpty()) wait();
        T item = items.removeFirst();
        notifyAll(); // wake any producer that was waiting on "not full"
        return item;
    }

    public synchronized int size() { return items.size(); }
}

public final class Mod003IntrinsicLocks {

    private Mod003IntrinsicLocks() {}

    /*
    The intrinsic lock (monitor)

    - Every Java object has an associated intrinsic lock (a monitor). Acquiring that monitor is the only thing the
      synchronized keyword does.
    - A monitor is binary: at most one thread holds it at any moment. Other threads that try to acquire it wait in the
      BLOCKED state.
    - Holding a monitor establishes a happens-before edge: changes made under one acquire/release of monitor m are
      visible to any thread that subsequently acquires m. This is the visibility half of synchronization.
    - Releasing the monitor happens automatically when the synchronized block exits — even via an exception. There is
      no way to forget to unlock an intrinsic monitor.
    */
    static void intrinsicLock() throws InterruptedException {
        System.out.println("[Section 1] intrinsic lock");

        var lock = new Object();
        Runnable critical = () -> {
            synchronized (lock) {
                System.out.println("  enter " + Thread.currentThread().getName());
                try { Thread.sleep(50); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                System.out.println("  exit  " + Thread.currentThread().getName());
            }
        };
        var t1 = new Thread(critical, "A");
        var t2 = new Thread(critical, "B");
        t1.start(); t2.start();
        t1.join(); t2.join();
    }

    /*
    synchronized blocks vs methods

    - synchronized on an instance method acquires the monitor of this. On a static method, it acquires the monitor of
      the class object (MyClass.class). These are two different monitors.
    - A synchronized (obj) { ... } block lets you choose any object as the monitor. This gives finer granularity than
      method-level synchronization and is the modern recommendation: the lock object is named, the protected region is
      small, and you can use multiple monitors to break contention.
    - Lock objects should be private final and have a single purpose. Never lock on public objects (String literals,
      boxed integers, the class itself in a public API) — anyone can lock the same object and accidentally deadlock
      with you.
    */
    static void blocksVsMethods() {
        System.out.println("[Section 2] blocks vs methods");

        // Two independent locks per instance — finer granularity than `synchronized` on `this`.
        class Account {
            private final Object balanceLock = new Object();
            private final Object historyLock = new Object();
            private long balance;
            private int historyCount;

            void deposit(long n) { synchronized (balanceLock) { balance += n; } }
            long getBalance()    { synchronized (balanceLock) { return balance; } }
            void recordEvent()   { synchronized (historyLock) { historyCount++; } }
            int getEventCount()  { synchronized (historyLock) { return historyCount; } }
        }
        var a = new Account();
        a.deposit(100);
        a.recordEvent();
        System.out.println("  balance=" + a.getBalance() + ", events=" + a.getEventCount());
    }

    /*
    Reentrancy

    - Java intrinsic locks are reentrant: the same thread can re-acquire a monitor it already holds. The JVM tracks an
      acquisition count and only releases the monitor after the matching number of exits.
    - Without reentrancy, a synchronized method calling another synchronized method on the same object would deadlock
      with itself. With it, recursion and helper-method chains are safe.
    - ReentrantLock and ReentrantReadWriteLock are reentrant for the same reason. StampedLock is not, and neither
      are the Lock views it hands out via asReadLock() / asWriteLock() — see Mod004 §6.
    */
    static void reentrancy() {
        System.out.println("[Section 3] reentrancy");

        class Outer {
            private final Object lock = new Object();
            void outer() {
                synchronized (lock) {
                    System.out.println("  outer holds the monitor");
                    inner(); // re-enters the same monitor
                }
            }
            void inner() {
                synchronized (lock) {
                    System.out.println("  inner re-entered the monitor (count is 2)");
                }
            }
        }
        new Outer().outer();
    }

    /*
    wait, notify, notifyAll

    - Object.wait() releases the monitor and parks the calling thread on the object's wait set. The thread can only
      call wait() while it actually holds the monitor — otherwise it gets IllegalMonitorStateException.
    - Another thread holding the same monitor calls notify() to wake one waiter or notifyAll() to wake all of them.
      The waiters then have to re-acquire the monitor before returning from wait().
    - A typical scaffold:
    synchronized (lock) {
        while (!condition) lock.wait();
        // ... act on the condition, then notify if appropriate
        lock.notifyAll();
    }
    - notify wakes an arbitrary waiter; notifyAll wakes all of them. Use notifyAll whenever multiple threads might be
      waiting on different conditions on the same monitor — picking only one of them can cause missed signals.

    The while-not-if rule

    - A wait must always be re-tested in a while loop, never an if. Reasons:
      1. Spurious wakeups — the JVM is allowed to wake a waiting thread even if no one called notify. The condition
         might still be false.
      2. Stolen wakeups — between notify and re-acquisition of the monitor, another thread can change the state, so
         the condition that was true at notify time might be false again when our thread runs.
    - Therefore: re-evaluate the predicate after each wait() returns. The cost of an extra evaluation is far below the
      cost of a subtle bug.

    Producer–consumer with monitor

    - The bounded buffer pattern: producers put items, consumers take items, the buffer has a maximum capacity.
    - One monitor (the buffer's intrinsic lock) protects access; producers wait while the buffer is full and consumers
      wait while it is empty. Both sides call notifyAll after every change because the same monitor serves two
      predicates ("not full" and "not empty").
    - This is the canonical example for wait/notifyAll. In real code today you would use a BlockingQueue (Mod005) or a
      ReentrantLock with two Conditions (Mod004), both of which are simpler.
    */
    static void producerConsumer() throws InterruptedException {
        System.out.println("[Section 4-6] producer–consumer with monitor");

        var queue = new BoundedQueue<String>(3);

        var producer = Thread.ofPlatform().name("producer").start(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    queue.put("doc-" + i);
                    System.out.println("  produced doc-" + i + ", size=" + queue.size());
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        var consumer = Thread.ofPlatform().name("consumer").start(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(50);                 // consumer is slower → buffer fills
                    String doc = queue.take();
                    System.out.println("  consumed " + doc + ", size=" + queue.size());
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        producer.join();
        consumer.join();
    }

    /*
    Limitations of the intrinsic lock

    - No tryLock — you cannot ask "is the monitor available, or shall I do something else?". Threads can only block.
    - No timeout — there is no "wait until lock available for at most 100 ms".
    - No interruptible acquisition — a thread blocked on entering a synchronized block ignores interrupts. Once it
      gets in, it can be interrupted while in wait, but not while it is waiting to acquire the monitor.
    - A single condition queue — every monitor has exactly one wait set. If you need separate "not full" and "not
      empty" queues you must use notifyAll and re-check, or move to ReentrantLock with multiple Conditions.
    - No fairness — the order in which queued threads acquire the monitor is unspecified. Long-running threads can
      starve.
    - Less observability — no getOwner(), getQueuedThreads(), etc.

    These limitations motivate java.util.concurrent.locks.ReentrantLock covered in Mod004.
    */
    static void interruptIgnoredAtMonitor() throws InterruptedException {
        System.out.println("[Section 7] synchronized acquisition ignores interrupt");

        var lock = new Object();
        var holder = Thread.ofPlatform().name("holder").start(() -> {
            synchronized (lock) {
                try { Thread.sleep(300); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        Thread.sleep(20);

        var contender = Thread.ofPlatform().name("contender").start(() -> {
            // Tries to enter the synchronized block; interrupt is ignored until the
            // monitor is acquired.
            synchronized (lock) {
                System.out.println("  contender finally got the monitor; " +
                        "flag still set: " + Thread.currentThread().isInterrupted());
            }
        });
        Thread.sleep(20);
        contender.interrupt();   // does NOT wake the contender from the monitor queue
        System.out.println("  contender state while interrupt pending: " + contender.getState());

        holder.join();
        contender.join();
    }

    public static void main(String[] args) throws InterruptedException {
        intrinsicLock();
        blocksVsMethods();
        reentrancy();
        producerConsumer();
        interruptIgnoredAtMonitor();
        System.out.println("Mod003IntrinsicLocks finished");
    }
}
