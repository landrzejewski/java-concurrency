package pl.training.e010_tests;

public class VisibilityProblem {

    private boolean ready = false;
    private int result = 0;
    
    // NIEPOPRAWNE - brak synchronizacji
    public void writerThread() {
        result = 42;
        ready = true;  // Może być widoczne przed result = 42!
    }
    
    public void readerThread() {
        while (!ready) {
            Thread.yield();
        }
        System.out.println("Result: " + result);  // Może wypisać 0!
    }

}
