package pl.training.e003_monitor;

import java.util.LinkedList;

public class Application {

    private static final int THREADS_COUNT = 30;
    private static final int PRINTING_QUEUE_LIMIT = 20;

    void main() {
        var printingQueue = new LinkedList<String>();
        var printingThread = new Thread(new Printer(printingQueue, PRINTING_QUEUE_LIMIT));
        printingThread.setName("Printer");
        printingThread.start();
        for (int index = 0; index < THREADS_COUNT; index++) {
            var employeeThread = new Thread(new Employee(printingQueue, PRINTING_QUEUE_LIMIT));
            employeeThread.setName("Employee-" + index);
            employeeThread.start();
        }
    }

}
