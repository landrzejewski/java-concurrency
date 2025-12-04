package pl.training.ex005_tools;

import static pl.training.common.Utils.printWithThreadName;
import static pl.training.common.Utils.silentSleep;

public class Counter implements Runnable {

    private static final int SLEEP_TIME = 200;

    private final ThreadLocal<Long> value = new ThreadLocal<>();

    @Override
    public void run() {
        value.set(System.currentTimeMillis());
        while (true) {
            silentSleep(SLEEP_TIME);
            printWithThreadName(value.get().toString());
        }
    }

}
