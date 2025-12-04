package pl.training.e003_monitor;

import java.util.Queue;
import java.util.Random;
import java.util.UUID;

import static pl.training.common.Utils.printWithThreadName;
import static pl.training.common.Utils.silentSleep;

public class Employee implements Runnable {

    private static final int MAX_SLEEP_TIME = 500;

    private final Queue<String> printingQueue;
    private final int taskLimit;
    private final Random random = new Random();

    public Employee(Queue<String> printingQueue, int taskLimit) {
        this.printingQueue = printingQueue;
        this.taskLimit = taskLimit;
    }

    @Override
    public void run() {
        while (true) {
            synchronized (printingQueue) {
                try {
                    waitIfQueueIdFull();
                    addDocument();
                    printingQueue.notify();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            silentSleep(MAX_SLEEP_TIME);
        }
    }

    private void waitIfQueueIdFull() throws InterruptedException {
        while (taskLimit == printingQueue.size()) {
            printWithThreadName("Queue limit reached");
            printingQueue.wait();
        }
    }

    private void addDocument() throws InterruptedException {
        Thread.sleep(random.nextInt(MAX_SLEEP_TIME));
        var document = UUID.randomUUID().toString();
        printingQueue.add(document);
        printWithThreadName("Adding document " + document);
    }

}
