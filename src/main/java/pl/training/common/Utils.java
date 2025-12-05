package pl.training.common;

public class Utils {

    public static void printWithThreadName(String text) {
        System.out.printf("%s (%s)\n", text, Thread.currentThread().getName());
    }

    public static void silentSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
           printWithThreadName("Silent sleep interrupted");
        }
    }

    public static Thread asyncRun(Task task) {
        return new Thread(() -> {
            try {
                task.run();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

}
