package pl.training.e001_creating_threads;

public class PrintTimeThread extends Thread {

    @Override
    public void run() {
        while(true){
            System.out.format("Current time: %s (%s)\n", System.currentTimeMillis(), Thread.currentThread().getName());
        }
    }

}
