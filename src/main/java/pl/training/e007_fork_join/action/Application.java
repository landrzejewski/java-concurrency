package pl.training.e007_fork_join.action;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

public class Application {

    private static final int PRODUCTS_COUNT = 50_000_000;
    private static final int CHUNK_SIZE = 100_000;
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getNumberInstance();

    void main() throws ExecutionException, InterruptedException {
        List<Product> products = new ArrayList<>();
        for (int index = 0;  index < PRODUCTS_COUNT; index++) {
            products.add(new Product(1));
        }

        for (int index = 0; index < 5; index++) {
            products.forEach(p -> p.setPrice(1));
            try (var forkJoinPool = new ForkJoinPool()) {
                var changePriceTask = new ChangePriceAction(products, 1, 0, products.size() - 1, CHUNK_SIZE);
                System.out.println("Starting...");
                long startTime = System.nanoTime();
                forkJoinPool.execute(changePriceTask);
                changePriceTask.get();
                System.out.println("Total time: " + NUMBER_FORMAT.format(System.nanoTime() - startTime) + " ns");
            }
        }
    }

}
