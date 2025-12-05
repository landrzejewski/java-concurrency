package pl.training.solution5;

public class MonitorBlockingQueue<T> {

    private final T[] array;
    private final Object lock = new Object();
    private final int capacity;

    private int size = 0;
    private int head = 0;
    private int tail = 0;

    @SuppressWarnings("unchecked")
    public MonitorBlockingQueue(int capacity) {
        array = (T[]) new Object[capacity];
        this.capacity = capacity;
    }

    public void enqueue(T item) throws InterruptedException {
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            if (tail == capacity) {
                tail = 0;
            }
            array[tail] = item;
            size++;
            tail++;
            lock.notifyAll();
        }
    }

    public T dequeue() throws InterruptedException {
        T item;
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            if (head == capacity) {
                head = 0;
            }
            item = array[head];
            array[head] = null;
            head++;
            size--;
            lock.notifyAll();
        }
        return item;
    }

}
