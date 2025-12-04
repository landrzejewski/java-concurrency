package pl.training.ex005_tools;

import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PrintingQueue {

    private static final int MAX_TIMEOUT = 10_000;

    private final Random random = new Random();
    private final Semaphore semaphore;
    //private final BoundedSemaphore semaphore;
    private final Logger logger = Logger.getLogger(getClass().getName());

    public PrintingQueue(Semaphore semaphore) {
        this.semaphore = semaphore;
    }

   /* public PrintingQueue(BoundedSemaphore semaphore) {
        this.semaphore = semaphore;
    }*/

    public void print(String text) {
        try {
            logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " is waiting for lock");
            semaphore.acquire();
            logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " acquired lock");
            Thread.sleep(random.nextInt(MAX_TIMEOUT));
            System.out.println(text);
            semaphore.release();
            logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " released lock");
        } catch (InterruptedException e) {
            logger.log(Level.INFO, "Thread interrupted: " + e.getMessage());
        }
    }

}
