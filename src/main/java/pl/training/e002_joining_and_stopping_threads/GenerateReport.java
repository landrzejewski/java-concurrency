package pl.training.e002_joining_and_stopping_threads;

import static pl.training.common.Printing.printWithThreadName;

public class GenerateReport implements Runnable {

    private static final long SLEEP_TIME = 3_000;

    private boolean isFinished = false;

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted() && !isFinished) {
            printWithThreadName("Generating report...");
            try {
                Thread.sleep(SLEEP_TIME);
                printWithThreadName("Printing report...");
            } catch (InterruptedException e) {
                printWithThreadName("Interrupted");
            } finally {
                isFinished = true;
                printWithThreadName("Cleaning...");
            }
        }
    }

}
