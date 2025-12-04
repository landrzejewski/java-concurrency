package pl.training.e004_locks;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import static pl.training.common.Utils.printWithThreadName;
import static pl.training.common.Utils.silentSleep;

public class Printer implements Runnable {

    private static final int MAX_SLEEP_TIME = 600;
    private static final double MAX_QUEUE_FILL = 0.75;

    private final Queue<String> printingQueue;
    private final int taskLimit;
    private final Lock lock;
    private final Condition queueIsEmpty;
    private final Condition queueIsFull;
    private final Random random = new Random();

    public Printer(LinkedList<String> printingQueue, int taskLimit, Lock lock, Condition queueIsEmpty, Condition queueIsFull) {
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
                waitIfQueueIsEmpty();
                printDocument();
                var queueFillState = (double) printingQueue.size() / taskLimit;
                if (queueFillState < MAX_QUEUE_FILL) {
                    printWithThreadName("Printer status: " + queueFillState);
                    queueIsFull.signalAll();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            lock.unlock();
            silentSleep(MAX_SLEEP_TIME);
        }
    }

    private void waitIfQueueIsEmpty() throws InterruptedException {
        while (printingQueue.isEmpty()) {
            queueIsEmpty.await();
        }
    }

    private void printDocument() throws InterruptedException {
        Thread.sleep(random.nextInt(MAX_SLEEP_TIME));
        var document = printingQueue.poll();
        printWithThreadName("Printing document: " + document);
    }

}
