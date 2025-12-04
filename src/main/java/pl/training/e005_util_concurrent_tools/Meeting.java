package pl.training.e005_util_concurrent_tools;

import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Meeting implements Runnable {

    private final CountDownLatch countDownLatch;
    private final Logger logger = Logger.getLogger(getClass().getName());

    public Meeting(CountDownLatch countDownLatch) {
        this.countDownLatch = countDownLatch;
    }

    public void addParticipant(String name) {
        logger.log(Level.INFO, name + " has joined meeting\n");
        countDownLatch.countDown();
    }

    @Override
    public void run() {
        logger.log(Level.INFO, "Waiting for participants\n");
        try {
            countDownLatch.await();
            logger.log(Level.INFO, "Starting meeting\n");
        } catch (InterruptedException e) {
            logger.log(Level.INFO, "Starting meeting failed\n");
        }
    }

}
