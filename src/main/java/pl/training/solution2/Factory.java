package pl.training.solution2;

import java.util.Arrays;
import java.util.Collections;

public class Factory {

    private final Object monitor = new Object();
    private final String[] molecule = new String[3];
    private int count;

    public void atom(String name, int requiredNumber) {
        synchronized (monitor) {
            while (Collections.frequency(Arrays.asList(molecule), name) == requiredNumber) {
                try {
                    monitor.wait();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            molecule[count] = name;
            count++;
            if (count == 3) {
               produce();
            }
            monitor.notifyAll();
        }
    }

    private void produce() {
        for (String element : molecule) {
            System.out.print(element);
        }
        System.out.println();
        Arrays.fill(molecule, null);
        count = 0;
    }
    
}