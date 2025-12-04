package pl.training.e006_executors;

import java.util.concurrent.Callable;

import static pl.training.common.Utils.silentSleep;

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
        silentSleep(SLEEP_TIME);
        return firstValue * secondValue;
    }

}
