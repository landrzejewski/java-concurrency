package pl.training.solution3;

import lombok.RequiredArgsConstructor;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

@RequiredArgsConstructor
public class BarberShop {

    private final Semaphore waitForCustomerToEnter = new Semaphore(0);
    private final Semaphore waitForBarberToGetReady = new Semaphore(0);
    private final Semaphore waitForCustomerToLeave = new Semaphore(0);
    private final Semaphore waitForBarberToCutHair = new Semaphore(0);
    private final ReentrantLock lock = new ReentrantLock();
    private final int chairs;

    private int waitingCustomers = 0;
    private int hairCutsGiven = 0;


    void customerWalksIn() throws InterruptedException {
        lock.lock();
        if (waitingCustomers == chairs) {
            System.out.println("Customer walks out, all chairs occupied");
            lock.unlock();
            return;
        }
        waitingCustomers++;
        lock.unlock();

        // Let the barber know client is here
        waitForCustomerToEnter.release();
        // Wait for client turn
        waitForBarberToGetReady.acquire();

        // The chair in the waiting area becomes available
        lock.lock();
        waitingCustomers--;
        lock.unlock();

        // Wait for haircut is done
        waitForBarberToCutHair.acquire();
        // Leave barber shop
        waitForCustomerToLeave.release();
    }

    void barber() throws InterruptedException {
        while (true) {
            // Wait for customer
            waitForCustomerToEnter.acquire();
            // Invite customer
            waitForBarberToGetReady.release();
            hairCutsGiven++;
            System.out.println("Barber cutting hair..." + hairCutsGiven);
            Thread.sleep(1_000);
            // Let customer know that haircut is done
            waitForBarberToCutHair.release();
            // Wait for customer to leave
            waitForCustomerToLeave.acquire();
        }
    }

}