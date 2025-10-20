package pl.training.concurrency.ex002_joining_threads;

public class Application {

    public static void main(String[] args) throws InterruptedException {
        Sum sum = new Sum(1, 5);
        Thread thread = new Thread(sum);
       //  thread.setDaemon(true);
        thread.start();
        System.out.printf("%s thread before join\n", Thread.currentThread().getName());
        thread.interrupt();
        // thread.join();
        thread.join(1_000); // dołączenie wątku main do Thread-0
        System.out.printf("%s thread after join\n", Thread.currentThread().getName());
    }

}
