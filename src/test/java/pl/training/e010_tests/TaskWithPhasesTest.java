package pl.training.e010_tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.training.common.TestExecutor;
import pl.training.common.TestPhaser;
import pl.training.common.TestThreadFactory;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static pl.training.common.ThreadUtils.sleep;
public class TaskWithPhasesTest {

    private static final int TIMEOUT = 100_000;
    private static final int PARTIES =3;

    private final Phaser phaser = new TestPhaser(PARTIES);
    private final ThreadPoolExecutor executor = new TestExecutor(3, 10,
            10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10));

    @BeforeEach
    public void init() {
        executor.setThreadFactory(new TestThreadFactory());
        for (int index = 0; index < PARTIES; index++) {
            executor.execute(new TaskWithPhases(index,phaser));
        }
    }

    @Test
    public void monitorThreads() {
        sleep(TIMEOUT);
        executor.shutdown();
    }

}
