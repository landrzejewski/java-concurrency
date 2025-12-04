package pl.training.e006_executors;

import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;

public class Printer implements Runnable {

    private final CompletionService<String> completionService;

    public Printer(CompletionService<String> completionService) {
        this.completionService = completionService;
    }

    @Override
    public void run() {
        while (true) {
            try {
                System.out.println("Report: " + completionService.take().get());
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

}
