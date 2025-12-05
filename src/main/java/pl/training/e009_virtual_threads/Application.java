package pl.training.e009_virtual_threads;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static pl.training.common.Utils.printWithThreadName;

public class Application {

    public static void main(String[] args) {
        Thread.ofVirtual()
                .name("VThread")
                .start(() -> printWithThreadName("Task"));

        var threadFactory = Thread.ofVirtual().factory();
        // try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
            for (int i = 0; i < 1_000; i++) {
                executor.submit(() -> System.out.format("Is virtual: %s, %d\n", Thread.currentThread().isVirtual(), Thread.currentThread().getId()));
            }
        }
    }


}
