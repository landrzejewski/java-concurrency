package pl.training.e005_util_concurrent;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public class Application {

    private static final int THREADS_COUNT = 20;
    private static final int SLEEP_TIME = 200;

    void main() throws ExecutionException, InterruptedException {
       // ThreadLocal
       /*
       var counter = new Counter();
        for (int index = 0; index < THREADS_COUNT; index++) {
            var employeeThread = new Thread(counter);
            employeeThread.setName("Counter-" + index);
            employeeThread.start();
            silentSleep(SLEEP_TIME);
        }
        */

        // Callable
        /*
        var sum = new Sum(1, 5);
        var task = new FutureTask<>(sum);
        new Thread(task).start();
        while (!task.isDone()) {
            System.out.println("Waiting for task");
            silentSleep(SLEEP_TIME);
        }
        System.out.println("Result: " + task.get());
        */

        // Semaphore
        /*
        var semaphore = new Semaphore(5);
        // var semaphore = new BoundedSemaphore(5);
        var printingQueue = new PrintingQueue(semaphore);
        for (int threadIndex = 0; threadIndex < THREADS_COUNT; threadIndex++) {
            new Thread(() -> printingQueue.print(UUID.randomUUID().toString())).start();
        }
        */

        // CyclicBarrier
        /*
        var cyclicBarrier = new CyclicBarrier(5);
        for (int threadIndex = 0; threadIndex < THREADS_COUNT; threadIndex++) {
            new Thread(new Service(cyclicBarrier)).start();
            Thread.sleep(3_000);
        }
        */

        // CountDownLatch
        /*
        var countDownLatch = new CountDownLatch(5);
        var meeting = new Meeting(countDownLatch);
        new Thread(meeting).start();
        meeting.addParticipant("Jan");
        Thread.sleep(2_000);
        meeting.addParticipant("Marek");
        meeting.addParticipant("Anna");
        meeting.addParticipant("Michał");
        Thread.sleep(1_000);
        meeting.addParticipant("Adam");
        */

        // Exchanger
        /*
        Exchanger<List<String>> listExchanger = new Exchanger<>();
        new Thread(new Consumer(listExchanger)).start();
        new Thread(new Producer(listExchanger)).start();
        */

        // Phaser
       /* var phaser = new Phaser(3);
        var path = Paths.get(".");
        Predicate<File> filePredicate = file -> file.length() > 1024;
        List.of(
                new Search(path, "java", filePredicate, phaser),
                new Search(path, "txt", filePredicate, phaser),
                new Search(path, "class", filePredicate, phaser)
        ).forEach(searchFiles -> new Thread(searchFiles).start());*/

        // Atomic types
        /*var value = new AtomicLong();
        value.getAndDecrement();*/


    }

}
