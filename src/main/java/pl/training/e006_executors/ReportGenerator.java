package pl.training.e006_executors;

import java.util.concurrent.Callable;

public class ReportGenerator implements Callable<String> {

    private static final int TIMOUT = 2_000;

    @Override
    public String call() throws Exception {
        System.out.println("Generating report...");
        Thread.sleep(TIMOUT);
        System.out.println("Report is ready...");
        return "New report";
    }

}
