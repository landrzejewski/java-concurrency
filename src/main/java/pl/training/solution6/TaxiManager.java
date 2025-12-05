package pl.training.solution6;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

public class TaxiManager {

    private final CyclicBarrier barrier = new CyclicBarrier(4);
    private final ReentrantLock lock = new ReentrantLock();
    private final Semaphore firstGroup = new Semaphore(0);
    private final Semaphore secondGroup = new Semaphore(0);

    private int firstGroupCount = 0;
    private int secondGroupCount = 0;

    public void seatFirstGroup() throws InterruptedException, BrokenBarrierException {
        boolean rideLeader = false;
        lock.lock();
        firstGroupCount++;
        if (firstGroupCount == 4) {
            // Seat all from firstGroup in the Uber ride.
            firstGroup.release(3);
            firstGroupCount -= 4;
            rideLeader = true;
        } else if (firstGroupCount == 2 && secondGroupCount >= 2) {
            // Seat 2 from firstGroup & 2 secondGroup
            firstGroup.release(1);
            secondGroup.release(2);
            rideLeader = true;
            firstGroupCount -= 2;
            secondGroupCount -= 2;
        } else {
            lock.unlock();
            firstGroup.acquire();
        }
        seated();
        barrier.await();
        if (rideLeader) {
            drive();
            lock.unlock();
        }
    }

    public void seatSecondGroup() throws InterruptedException, BrokenBarrierException {
        boolean rideLeader = false;
        lock.lock();
        secondGroupCount++;
        if (secondGroupCount == 4) {
            // Seat all from secondGroup in the Uber ride.
            secondGroup.release(3);
            rideLeader = true;
            secondGroupCount -= 4;
        } else if (secondGroupCount == 2 && firstGroupCount >= 2) {
            // Seat 2 from firstGroup & 2 secondGroup
            secondGroup.release(1);
            firstGroup.release(2);
            rideLeader = true;
            secondGroupCount -= 2;
            firstGroupCount -= 2;
        } else {
            lock.unlock();
            secondGroup.acquire();
        }
        seated();
        barrier.await();
        if (rideLeader) {
            drive();
            lock.unlock();
        }
    }

    private void seated() {
        System.out.println(Thread.currentThread().getName() + " seated");
    }

    private void drive() {
        System.out.println("Ridding... ");
    }

}
