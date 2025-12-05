package pl.training.solution1;

public class Application {

    static void main() throws InterruptedException {
        var bathroom = new Bathroom();
        var female1 = asyncRun(() -> bathroom.femaleUseBathroom("Anna"));
        var male1 = asyncRun(() -> bathroom.maleUseBathroom("Jan"));
        var male2 = asyncRun(() -> bathroom.maleUseBathroom("Marek"));
        var male3 = asyncRun(() -> bathroom.maleUseBathroom("Adam"));
        var female2 = asyncRun(() -> bathroom.femaleUseBathroom("Marta"));
        var male4 = asyncRun(() -> bathroom.maleUseBathroom("Michał"));

        female1.start();
        male1.start();
        male2.start();
        female2.start();
        male3.start();
        male4.start();
        female1.join();
        male1.join();
        male2.join();
        male3.join();
        male4.join();
        female2.join();
    }

    interface Task {

        void run() throws InterruptedException;

    }

    private static Thread asyncRun(Task task) {
        return new Thread(() -> {
            try {
                task.run();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

}
