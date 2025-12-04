package pl.training.e006_executors;

import java.util.concurrent.Callable;

public class Sum implements Callable<Integer> {

    private static final int SLEEP_TIME = 3_000;

    private final int firstValue;
    private final int secondValue;

    public Sum(int firstValue, int secondValue) {
        this.firstValue = firstValue;
        this.secondValue = secondValue;
    }

    @Override
    public Integer call() {
        try {
            Thread.sleep(SLEEP_TIME);
        } catch (InterruptedException e) {
            System.out.println("Stopping sum thread");
        }
        return firstValue + secondValue;
    }

}
