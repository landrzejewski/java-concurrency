package pl.training.solution2;

import java.util.Random;

public class Producer implements Runnable {

    private final Factory factory;
    private final String atomName;
    private final int requiredNumber;
    private final Random random = new Random();

    public Producer(Factory factory, String atomName, int requiredNumber) {
        this.factory = factory;
        this.atomName = atomName;
        this.requiredNumber = requiredNumber;
    }

    public void run() {
        while (true) {
            try {
                factory.atom(atomName, requiredNumber);
                Thread.sleep(random.nextInt(2_000));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}