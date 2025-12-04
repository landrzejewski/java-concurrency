package pl.training.e004_locks;

import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import static pl.training.common.Utils.printWithThreadName;
import static pl.training.common.Utils.silentSleep;

public class Employee implements Runnable {

    private static final int MAX_SLEEP_TIME = 500;

    private final Queue<String> printingQueue;
    private final int taskLimit;
    private final Lock lock;
    private final Condition queueIsEmpty;
    private final Condition queueIsFull;
    private final Random random = new Random();

    public Employee(Queue<String> printingQueue, int taskLimit, Lock lock, Condition queueIsEmpty, Condition queueIsFull) {
        this.printingQueue = printingQueue;
        this.taskLimit = taskLimit;
        this.lock = lock;
        this.queueIsEmpty = queueIsEmpty;
        this.queueIsFull = queueIsFull;
    }

    @Override
    public void run() {
        while (true) {
            lock.lock();
            try {
                waitIfQueueIdFull();
                addDocument();
                queueIsEmpty.signal();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            lock.unlock();
            silentSleep(MAX_SLEEP_TIME);
        }
    }

    private void waitIfQueueIdFull() throws InterruptedException {
        while (taskLimit == printingQueue.size()) {
            printWithThreadName("Queue limit reached");
            queueIsFull.await();
        }
    }

    private void addDocument() throws InterruptedException {
        Thread.sleep(random.nextInt(MAX_SLEEP_TIME));
        var document = UUID.randomUUID().toString();
        printingQueue.add(document);
        printWithThreadName("Adding document " + document);
    }

}
