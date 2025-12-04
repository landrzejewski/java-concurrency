package pl.training.ex005_tools;

import java.util.concurrent.Callable;

import static pl.training.common.Utils.silentSleep;

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
        silentSleep(SLEEP_TIME);
        return firstValue + secondValue;
    }

}
