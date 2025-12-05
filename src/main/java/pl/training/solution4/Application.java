package pl.training.solution4;

import java.util.Date;

import static pl.training.common.Utils.asyncRun;

public class Application {

    void main() throws InterruptedException {
        var tokenBucket = new TokenBucket(5, 5_000);
        Thread.sleep(2_000);
        for (int index = 0; index < 12; index++) {
            asyncRun(() -> {
                tokenBucket.getToken();
                System.out.println(Thread.currentThread().getName() + " received token at " + new Date());
            }).start();
        }
        Thread.sleep(10_000);
    }

}
