package pl.training.e010_tests;

public class CounterWithRaceCondition {
    private int count = 0;
    
    // NIEPOPRAWNE - race condition!
    public void increment() {
        count++;  // To NIE jest operacja atomowa!
    }
    
    public int getCount() {
        return count;
    }

}