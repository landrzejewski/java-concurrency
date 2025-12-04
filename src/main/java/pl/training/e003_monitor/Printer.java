package pl.training.e003_monitor;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;

import static pl.training.common.Utils.printWithThreadName;
import static pl.training.common.Utils.silentSleep;

public class Printer implements Runnable {

    private static final int MAX_SLEEP_TIME = 600;
    private static final double MAX_QUEUE_FILL = 0.75;

    private final Queue<String> printingQueue;
    private final int taskLimit;
    private final Random random = new Random();

    public Printer(LinkedList<String> printingQueue, int taskLimit) {
        this.printingQueue = printingQueue;
        this.taskLimit = taskLimit;
    }

    @Override
    public void run() {
        while (true) {
            synchronized (printingQueue) {
                try {
                    waitIfQueueIsEmpty();
                    printDocument();
                    var queueFillState = (double) printingQueue.size() / taskLimit;
                    if (queueFillState < MAX_QUEUE_FILL) {
                        printWithThreadName("Printer status: " + queueFillState);
                        printingQueue.notifyAll();
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            silentSleep(MAX_SLEEP_TIME);
        }
    }

    private void waitIfQueueIsEmpty() throws InterruptedException {
        while (printingQueue.isEmpty()) {
            printingQueue.wait();
        }
    }

    private void printDocument() throws InterruptedException {
        Thread.sleep(random.nextInt(MAX_SLEEP_TIME));
        var document = printingQueue.poll();
        printWithThreadName("Printing document: " + document);
    }

}
