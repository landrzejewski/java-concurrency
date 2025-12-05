package pl.training.common;

import java.util.Collection;
import java.util.concurrent.locks.ReentrantLock;

public class TestLock extends ReentrantLock {

    public Collection<Thread> getLockedThreads() {
        return getQueuedThreads();
    }

    public Thread getOwner() {
        return super.getOwner();
    }

}
