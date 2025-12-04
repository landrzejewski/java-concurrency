package pl.training.e006_executors;

import java.util.List;
import java.util.concurrent.*;

public class Application {

    private static final int THREADS_COUNT = 2;
    private static final int WAIT_TIME = 2;

    void main() throws InterruptedException, ExecutionException {
        var sum = new Sum(1, 5);
        var executorService = Executors.newFixedThreadPool(THREADS_COUNT);
        /*var futureTask = executorService.submit(sum);

        executorService.shutdown();
        executorService.awaitTermination(WAIT_TIME, TimeUnit.SECONDS);
        System.out.printf("Result: %d\n", futureTask.get());

        var scheduledExecutorService = Executors.newScheduledThreadPool(THREADS_COUNT);
        scheduledExecutorService.schedule(new Multiplication(2, 5), WAIT_TIME, TimeUnit.SECONDS);
        scheduledExecutorService.shutdown();

        var threadPoolExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(THREADS_COUNT);
        var results = threadPoolExecutor.invokeAll(List.of(new Multiplication(2, 5), new Multiplication(2, 6)));
        //threadPoolExecutor.shutdown();
        var isSuccess = threadPoolExecutor.awaitTermination(10, TimeUnit.SECONDS);
        results.stream()
                .map(Future::isDone)
                .forEach(System.out::println);*/

        CompletionService<String> completionService = new ExecutorCompletionService<>(executorService);
        var printer = new Printer(completionService);
        executorService.execute(printer);
        completionService.submit(new ReportGenerator());
        completionService.submit(new ReportGenerator());
        completionService.submit(new ReportGenerator());
        executorService.shutdown();
    }

}
