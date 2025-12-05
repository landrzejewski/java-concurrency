package pl.training.solution5;

import pl.training.e005_util_concurrent.BoundedSemaphore;

public class SemaphoreQueue<T> {

    private final T[] array;
    private final int capacity;
    private final BoundedSemaphore lock = new BoundedSemaphore(1, 1);
    private final BoundedSemaphore producer;
    private final BoundedSemaphore consumer;

    private int size = 0;
    private int head = 0;
    private int tail = 0;

    @SuppressWarnings("unchecked")
    public SemaphoreQueue(int capacity) {
        array = (T[]) new Object[capacity];
        this.capacity = capacity;
        this.producer = new BoundedSemaphore(capacity, capacity);
        this.consumer = new BoundedSemaphore(capacity, 0);
    }

    public T dequeue() throws InterruptedException {
        T item;
        consumer.acquire();
        lock.acquire();
        if (head == capacity) {
            head = 0;
        }
        item = array[head];
        array[head] = null;
        head++;
        size--;
        lock.release();
        producer.release();
        return item;
    }

    public void enqueue(T item) throws InterruptedException {
        producer.acquire();
        lock.acquire();
        if (tail == capacity) {
            tail = 0;
        }
        array[tail] = item;
        size++;
        tail++;
        lock.release();
        consumer.release();
    }

}
