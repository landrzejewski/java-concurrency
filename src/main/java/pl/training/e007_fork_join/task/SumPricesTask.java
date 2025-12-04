package pl.training.e007_fork_join.task;

import java.util.List;
import java.util.concurrent.RecursiveTask;

public class SumPricesTask extends RecursiveTask<Double> {

    private final List<Product> products;
    private final long changeValue;
    private final int startIndex;
    private final int endIndex;
    private final int chunkSize;

    public SumPricesTask(List<Product> products, long changeValue, int startIndex, int endIndex, int chunkSize) {
        this.products = products;
        this.changeValue = changeValue;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.chunkSize = chunkSize;
    }

    @Override
    protected Double compute() {
        if (endIndex - startIndex <= chunkSize) {
            // Przypadek bazowy - przetwarzamy chunk i zwracamy sumę cen
            double sum = 0;
            for (int index = startIndex; index <= endIndex; index++) {
                var product = products.get(index);
                for (int count = 0; count <= 100; count++) {
                    product.increasePrice(Math.sqrt(product.getPrice() * count));
                }
                sum += product.getPrice();
            }
            return sum;
        } else {
            // Przypadek rekurencyjny - dzielimy i łączymy wyniki
            int middle = (startIndex + endIndex) / 2;
            var firstTask = new SumPricesTask(products, changeValue, startIndex, middle, chunkSize);
            var secondTask = new SumPricesTask(products, changeValue, middle + 1, endIndex, chunkSize);
            
            firstTask.fork();  // asynchronicznie uruchamiamy pierwsze zadanie
            var secondResult = secondTask.compute();  // wykonujemy drugie w bieżącym wątku
            var firstResult = firstTask.join();  // czekamy na wynik pierwszego
            
            return firstResult + secondResult;  // łączymy wyniki
        }
    }

}