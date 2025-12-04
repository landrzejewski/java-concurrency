package pl.training.e004_locks;

import java.util.LinkedList;
import java.util.concurrent.locks.ReentrantLock;

public class Application {

    private static final int THREADS_COUNT = 30;
    private static final int PRINTING_QUEUE_LIMIT = 20;

    void main() {
        var lock = new ReentrantLock();
        var queueIsEmpty = lock.newCondition();
        var queueIsFull = lock.newCondition();
        var printingQueue = new LinkedList<String>();
        var printingThread = new Thread(new Printer(printingQueue, PRINTING_QUEUE_LIMIT, lock, queueIsEmpty, queueIsFull));
        printingThread.setName("Printer");
        printingThread.start();
        for (int index = 0; index < THREADS_COUNT; index++) {
            var employeeThread = new Thread(new Employee(printingQueue, PRINTING_QUEUE_LIMIT, lock,  queueIsEmpty, queueIsFull));
            employeeThread.setName("Employee-" + index);
            employeeThread.start();
        }
    }

}
