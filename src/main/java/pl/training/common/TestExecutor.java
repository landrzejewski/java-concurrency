package pl.training.common;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class TestExecutor extends ThreadPoolExecutor {

    private ConcurrentHashMap<Integer, Long> times = new ConcurrentHashMap<>();

    public TestExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
    }

    @Override
    protected void beforeExecute(Thread thread, Runnable runnable) {
        int id = runnable.hashCode();
        System.out.printf("Task is starting: %s, id: %d\n", thread.getName(), id);
        times.put(id, System.nanoTime());
        super.beforeExecute(thread, runnable);
    }

    @Override
    protected void afterExecute(Runnable runnable, Throwable throwable) {
        System.out.println("#####################################################################################");
        int id = runnable.hashCode();
        long time = System.nanoTime() - times.get(id);
        times.put(id, time);
        System.out.println("Time: " + time);
        super.afterExecute(runnable, throwable);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        times.forEach((id, time) -> System.out.printf("Hash: %d, time: %d ns\n", id, time));
    }

}
