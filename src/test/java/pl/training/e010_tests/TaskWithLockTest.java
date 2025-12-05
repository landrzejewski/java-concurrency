package pl.training.e010_tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.training.common.TestLock;

import java.util.Collection;

public class TaskWithLockTest {

    private static final int TIMOUT = 1_000;
    private static final int THREADS_COUNT = 10;

    private final TestLock testLock = new TestLock();
    private final TaskWithLock taskWithLock = new TaskWithLock(testLock);

    @BeforeEach
    public void init() {
        for (int index = 0; index < THREADS_COUNT; index++) {
            new Thread(taskWithLock).start();
        }
    }

    @Test
    public void monitorThreads() throws InterruptedException {
        while (testLock.hasQueuedThreads()) {
            Collection<Thread> lockedThreads = testLock.getLockedThreads();
            System.out.println("Locked threads: " + lockedThreads.size());
            System.out.println("Lock owner: " + testLock.getOwner().getName());
            //lockedThreads.forEach(thread -> System.out.println(thread.getName()));
            Thread.sleep(TIMOUT);
        }
    }

}
