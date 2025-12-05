package pl.training.e010_tests;

import java.util.concurrent.locks.Lock;

public class TaskWithLock implements Runnable {

    private static final int TIMEOUT = 500;

    private final Lock lock;

    public TaskWithLock(Lock lock) {
        this.lock = lock;
    }

    @Override
    public void run() {
        for (int index = 0; index < 10; index++) {
            lock.lock();
            //System.out.println("After lock " + Thread.currentThread().getName());
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            lock.unlock();
        }
    }

}
