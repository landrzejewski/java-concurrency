package pl.training.solution6;

import pl.training.common.ThreadUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;

public class Application {

    public static void main(String[] args) {
        TaxiManager taxiManager = new TaxiManager();
        Set<Thread> allThreads = new HashSet<>();

        for (int i = 0; i < 10; i++) {
            Thread thread = new Thread(() -> {
                try {
                    taxiManager.seatFirstGroup();
                } catch (InterruptedException | BrokenBarrierException ie) {
                    System.out.println("We have a problem");
                }
            });
            thread.setName("First group " + (i + 1));
            allThreads.add(thread);
            ThreadUtils.sleep(50);
        }

        for (int i = 0; i < 14; i++) {
            Thread thread = new Thread(() -> {
                try {
                    taxiManager.seatSecondGroup();
                } catch (InterruptedException | BrokenBarrierException ie) {
                    System.out.println("We have a problem");

                }
            });
            thread.setName("Second group " + (i + 1));
            allThreads.add(thread);
            ThreadUtils.sleep(20);
        }

        allThreads.forEach(Thread::start);
        ThreadUtils.sleep(10_000);
    }

}
