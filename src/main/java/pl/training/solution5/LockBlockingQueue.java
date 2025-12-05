package pl.training.solution5;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class LockBlockingQueue<T> {

    private final T[] array;
    private final Lock lock = new ReentrantLock();
    private final Condition isEmpty = lock.newCondition();
    private final Condition isFull = lock.newCondition();
    private final int capacity;
    private int size = 0;
    private int head = 0;
    private int tail = 0;

    @SuppressWarnings("unchecked")
    public LockBlockingQueue(int capacity) {
        array = (T[]) new Object[capacity];
        this.capacity = capacity;
    }

    public T dequeue() throws InterruptedException {
        T item = null;
        lock.lock();
        while (size == 0) {
            isEmpty.await();
        }
        if (head == capacity) {
            head = 0;
        }
        item = array[head];
        array[head] = null;
        head++;
        size--;
        lock.unlock();
        isFull.signal();
        return item;
    }

    public void enqueue(T item) throws InterruptedException {
        lock.lock();
        while (size == capacity) {
            isFull.await();
        }
        if (tail == capacity) {
            tail = 0;
        }
        array[tail] = item;
        size++;
        tail++;
        lock.unlock();
        isEmpty.signal();
    }

}
