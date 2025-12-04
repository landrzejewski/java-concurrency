package pl.training.e001_creating_threads;

public class Application {

    void main() {
        System.out.format("Current thread: %s\n", Thread.currentThread().getName());
        var printTime = new PrintTime();
        var thread1 = new Thread(printTime);
        var thread2 = new PrintTimeThread();
        thread1.start();
        thread2.start();
    }

}