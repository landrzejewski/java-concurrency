package pl.training.common;

public class Printing {

    public static void printWithThreadName(String text) {
        System.out.printf("%s (%s)\n", text, Thread.currentThread().getName());
    }

}
