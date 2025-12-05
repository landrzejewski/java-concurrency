package pl.training.e009_virtual_threads;

import java.time.Duration;

public class Application {

    void main() {
        /*for (int i = 0; i < 1_000_000_000; i++) {
            new Thread(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(100L));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).start();
        }*/

        for (int i = 0; i < 1_000_000_000; i++) {
            Thread.ofVirtual()
                    .name("V" + i)
                    .start(() -> {
                        try {
                            Thread.sleep(Duration.ofSeconds(100L));
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }


}
