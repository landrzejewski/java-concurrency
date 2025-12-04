package pl.training.e005_util_concurrent_tools;

import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Service implements Runnable {

    private final CyclicBarrier cyclicBarrier;
    private final Logger logger = Logger.getLogger(getClass().getName());

    public Service(CyclicBarrier cyclicBarrier) {
        this.cyclicBarrier = cyclicBarrier;
    }

    @Override
    public void run() {
        String id = UUID.randomUUID().toString();
        logger.log(Level.INFO, "Service " + id + " started...");
        try {
            cyclicBarrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            logger.log(Level.INFO, "Service error " + e.getMessage());
        }
        logger.log(Level.INFO, "Service " + id + " stopped...");
    }

}
