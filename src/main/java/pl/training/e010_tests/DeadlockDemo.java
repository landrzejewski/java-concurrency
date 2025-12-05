package pl.training.e010_tests;

public class DeadlockDemo {
    private static final Object lock1 = new Object();
    private static final Object lock2 = new Object();
    
    public static void main(String[] args) {
        System.out.println("Aplikacja uruchomiona. PID: " + 
            ProcessHandle.current().pid());
        System.out.println("Uruchom VisualVM i podłącz się do tego procesu");
        
        Thread t1 = new Thread(() -> {
            synchronized(lock1) {
                System.out.println("Wątek 1: zdobyto lock1");
                sleep(100);
                System.out.println("Wątek 1: próba zdobycia lock2");
                synchronized(lock2) {
                    System.out.println("Wątek 1: zdobyto lock2");
                }
            }
        }, "Worker-Thread-1");
        
        Thread t2 = new Thread(() -> {
            synchronized(lock2) {
                System.out.println("Wątek 2: zdobyto lock2");
                sleep(100);
                System.out.println("Wątek 2: próba zdobycia lock1");
                synchronized(lock1) {
                    System.out.println("Wątek 2: zdobyto lock1");
                }
            }
        }, "Worker-Thread-2");
        
        t1.start();
        t2.start();
        
        // Utrzymuj aplikację
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}