package pl.training.e007_fork_join.action;

import java.util.List;
import java.util.concurrent.RecursiveAction;

public class ChangePriceAction extends RecursiveAction {

    private final List<Product> products;
    private final long changeValue;
    private final int startIndex;
    private final int endIndex;
    private final int chunkSize;

    public ChangePriceAction(List<Product> products, long changeValue, int startIndex, int endIndex, int chunkSize) {
        this.products = products;
        this.changeValue = changeValue;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.chunkSize = chunkSize;
    }

    @Override
    protected void compute() {
        if (endIndex - startIndex <= chunkSize) {
            for (int index = startIndex; index <= endIndex; index++) {
                var product = products.get(index);
                for (int count = 0; count <= 100; count++) {
                    product.increasePrice(Math.sqrt(product.getPrice() * count));
                }
            }
        } else {
            int middle = (startIndex + endIndex) / 2;
            var firstTask = new ChangePriceAction(products, changeValue, startIndex, middle, chunkSize);
            var secondTask = new ChangePriceAction(products, changeValue, middle + 1, endIndex, chunkSize);
            invokeAll(firstTask, secondTask);
        }
    }

}
