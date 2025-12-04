package pl.training.e002_joining_and_stopping_threads;

import static pl.training.common.Utils.printWithThreadName;

public class Application {

    void main() throws InterruptedException {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> printWithThreadName("Shutting down...")));

        var generateReport = new GenerateReport();
        var thread = new Thread(generateReport);
        // thread.setDaemon(true);
        thread.start();
        printWithThreadName("Before task");
        // thread.join();
        thread.join(1_000);
        // thread.join(Duration.ofSeconds(5));

        // thread.stop(); // anti-pattern
        thread.interrupt();
        printWithThreadName("After task");
    }

}