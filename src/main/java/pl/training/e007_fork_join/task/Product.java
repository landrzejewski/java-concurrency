package pl.training.e007_fork_join.task;

public class Product {

    private double price;

    public Product(double price) {
        this.price = price;
    }

    public void increasePrice(double changeValue) {
        price += changeValue;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public double getPrice() {
        return price;
    }

}