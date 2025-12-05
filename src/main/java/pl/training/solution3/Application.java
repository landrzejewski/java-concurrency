package pl.training.solution3;

import java.util.Random;

import static pl.training.common.ThreadUtils.asyncRun;

public class Application {

    public static void main(String[] args) throws InterruptedException {
        Random random = new Random();
        BarberShop barberShop = new BarberShop(3);
        Thread barberThread = asyncRun(barberShop::barber);
        barberThread.start();

        for (int i = 0; i < 20; i++) {
            asyncRun(barberShop::customerWalksIn).start();
            Thread.sleep(random.nextInt(1_000));
        }

        barberThread.join();
    }

}
