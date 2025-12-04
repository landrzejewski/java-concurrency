package pl.training.e005_util_concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Exchanger;

public class Producer implements Runnable {

    private static final int MAX_BUFFER_SIZE = 10;

    private List<String> buffer = new ArrayList<>();
    private final Exchanger<List<String>> exchanger;

    public Producer(Exchanger<List<String>> exchanger) {
        this.exchanger = exchanger;
    }

    @Override
    public void run() {
        while (true) {
            System.out.println("Producer buffer size: " + buffer.size());
            for (int i = 0; i < MAX_BUFFER_SIZE; i++) {
                buffer.add(UUID.randomUUID().toString());
                System.out.println("Producing...");
            }
            try {
                buffer = exchanger.exchange(buffer);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

}
