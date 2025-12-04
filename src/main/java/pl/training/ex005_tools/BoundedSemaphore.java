package pl.training.ex005_tools;

public class BoundedSemaphore {

    private final int maxPermits;
    private int usedPermits;

    public BoundedSemaphore(int maxPermits) {
        this.maxPermits = maxPermits;
    }

    public BoundedSemaphore(int maxPermits, int initialPermits) {
        this.maxPermits = maxPermits;
        this.usedPermits = this.maxPermits - initialPermits;
    }

    public synchronized void acquire() throws InterruptedException {
        while (usedPermits == maxPermits) {
            wait();
        }
        notify();
        usedPermits++;
    }

    public synchronized void release() throws InterruptedException {
        if (usedPermits > 0) {
            usedPermits--;
        }
        notify();
    }

}
