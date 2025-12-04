package pl.training.e006_executors;

import java.util.concurrent.Callable;

public class Multiplication implements Callable<Integer> {

    private static final int SLEEP_TIME = 3_000;

    private final int firstValue;
    private final int secondValue;

    public Multiplication(int firstValue, int secondValue) {
        this.firstValue = firstValue;
        this.secondValue = secondValue;
    }

    @Override
    public Integer call() {
        try {
            Thread.sleep(SLEEP_TIME);
        } catch (InterruptedException exception) {
            System.out.println("Stopping multiplication thread");
        }
        return firstValue * secondValue;
    }

}
