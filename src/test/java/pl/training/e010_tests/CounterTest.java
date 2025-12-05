package pl.training.e010_tests;

import org.junit.jupiter.api.Test;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@JCStressTest
@Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "Oba wątki przed inkrementacją")
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Wątek 1 dodał 1")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "Wątek 2 dodał 1")
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Wątek 1 i 2 dodały 1")
@Outcome(id = "2, 2", expect = Expect.ACCEPTABLE, desc = "Wątek 2 i 2 dodały 2")
@Outcome(expect = Expect.ACCEPTABLE_INTERESTING, desc = "Race condition")
@State
public class CounterTest {

    int counter = 0;

    @Actor
    public void actor1(II_Result r) {
        counter++;
        r.r1 = counter;
    }

    @Actor
    public void actor2(II_Result r) {
        counter++;
        r.r2 = counter;
    }


    /*
      [OK] CounterRaceTest
      (JVM args: [-server])
       Observed state   Occurrences   Expectation  Interpretation
            0, 0             1   ACCEPTABLE   Oba wątki przed incrementem
            1, 0        12,345   ACCEPTABLE   Wątek 1 zinkrementował
            0, 1        11,234   ACCEPTABLE   Wątek 2 zinkrementował
            1, 1       123,456   ACCEPTABLE_INTERESTING   Race condition!  ← PROBLEM!
            2, 2        45,678   ACCEPTABLE   Oba zrobiły dwa inkrementy
     */

   /* @Test
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
    }*/

}
