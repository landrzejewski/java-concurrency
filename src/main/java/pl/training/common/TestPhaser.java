package pl.training.common;

import java.util.concurrent.Phaser;

public class TestPhaser extends Phaser {

    public TestPhaser(int parties){
        super(parties);
    }

    @Override
    protected boolean onAdvance(int phase, int registeredParties) {
        System.out.println("Number of workers " + registeredParties + " in phase " + phase);
        return super.onAdvance(phase, registeredParties);
    }

}
