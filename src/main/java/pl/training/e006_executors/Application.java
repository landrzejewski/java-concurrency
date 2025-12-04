package pl.training.e006_executors;

import java.util.List;
import java.util.concurrent.*;

public class Application {

    private static final int THREADS_COUNT = 2;
    private static final int WAIT_TIME = 2;

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        Sum sum = new Sum(1, 5);
        ExecutorService executorService = Executors.newFixedThreadPool(THREADS_COUNT);
        Future<Integer> futureTask = executorService.submit(sum);
        executorService.shutdown();
        executorService.awaitTermination(WAIT_TIME, TimeUnit.SECONDS);
        System.out.printf("Result: %d", futureTask.get());

        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(THREADS_COUNT);
        scheduledExecutorService.schedule(new Multiplication(2, 5), WAIT_TIME, TimeUnit.SECONDS);
        scheduledExecutorService.shutdown();

        ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(THREADS_COUNT);
        List<Future<Integer>> results = threadPoolExecutor.invokeAll(List.of(new Multiplication(2, 5), new Multiplication(2, 6)));
        //threadPoolExecutor.shutdown();
        threadPoolExecutor.awaitTermination(10, TimeUnit.SECONDS);
        results.stream()
                .map(Future::isDone)
                .forEach(System.out::println);
    }

}
