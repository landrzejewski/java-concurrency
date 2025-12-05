package pl.training.e010_tests;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CounterTest {

    @Test
    public void testConcurrentIncrements() throws InterruptedException {
        CounterWithRaceCondition counter = new CounterWithRaceCondition();
        int numberOfThreads = 10;
        int incrementsPerThread = 1_000;
        
        var executor = Executors.newFixedThreadPool(numberOfThreads);
        var latch = new CountDownLatch(numberOfThreads);
        
        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    counter.increment();
                }
                latch.countDown();
            });
        }
        
        latch.await();
        executor.shutdown();
        
        // Powinno być 10,000 ale często będzie mniej!
        System.out.println("Oczekiwane: " + (numberOfThreads * incrementsPerThread));
        System.out.println("Rzeczywiste: " + counter.getCount());
        
        assertEquals(numberOfThreads * incrementsPerThread, counter.getCount());
    }

}
