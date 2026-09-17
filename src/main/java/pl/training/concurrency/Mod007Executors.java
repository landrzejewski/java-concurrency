package pl.training.concurrency;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/*
From threads to executors

- Manually creating a Thread per task ties the what (the work) to the where (the thread). For a server with thousands
  of short tasks this is wasteful: the JVM cannot reuse threads, scheduling pressure is high, and you have to
  hand-write start/join lifecycle for every task.
- An Executor is the abstraction "submit work and let someone else manage how it runs". ExecutorService extends it
  with task submission that returns a Future, and lifecycle management (shutdown, awaitTermination).
- Once you have an ExecutorService, the only question becomes what kind — fixed pool, cached pool, scheduled pool,
  virtual-thread executor (Mod011), or a hand-tuned ThreadPoolExecutor.
*/

public final class Mod007Executors {

    private Mod007Executors() {}

    /*
    ExecutorService factory methods

    The Executors class provides ready-made executors:

    - newFixedThreadPool(N) — N permanent threads. Excess tasks queue. Predictable upper bound on resource usage; use
      when the workload is steady.
    - newCachedThreadPool() — unbounded; threads idle for 60s are reaped. Spikes turn into many threads; risky for
      unpredictable bursts.
    - newSingleThreadExecutor() — exactly one worker; serializes tasks. Good for "the dedicated thread that owns this
      resource" patterns.
    - newScheduledThreadPool(N) — adds schedule(...) and scheduleAtFixedRate(...).
    - newWorkStealingPool() — a ForkJoinPool (Mod008).
    - newVirtualThreadPerTaskExecutor() — one virtual thread per task (Mod011).
    */
    static void factories() throws InterruptedException {
        System.out.println("[Section 2] Executors factory methods");

        try (var fixed = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 4; i++) {
                final int id = i;
                fixed.execute(() -> System.out.println("  fixed: task " + id
                        + " on " + Thread.currentThread().getName()));
            }
        } // try-with-resources auto-shutdown (Java 19+)

        try (var single = Executors.newSingleThreadExecutor()) {
            single.execute(() -> System.out.println("  single: " + Thread.currentThread().getName()));
            single.execute(() -> System.out.println("  single: same thread again? "
                    + Thread.currentThread().getName()));
        }
        System.out.println("[Section 1] Executors factory methods");
    }

    /*
    Runnable vs Callable<V> vs Future<V>

    - Runnable returns nothing and cannot throw checked exceptions.
    - Callable<V> returns a value and can throw checked exceptions. The functional shape is V call() throws Exception.
    - submit(Runnable) and submit(Callable<V>) both return a Future<V>. The future:
      - blocks on get() until the task completes,
      - returns the value (or null for Runnable),
      - rethrows the task's exception wrapped in ExecutionException,
      - supports cancellation via cancel(mayInterruptIfRunning).
    */
    static void callableAndFuture() throws InterruptedException, ExecutionException {
        System.out.println("[Section 3] Callable + Future");

        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> sum = () -> { Thread.sleep(50); return 1 + 2 + 3 + 4 + 5; };
            Future<Integer> future = pool.submit(sum);
            System.out.println("  result = " + future.get());
        }
    }

    /*
    submit vs execute

    - execute(Runnable) is fire-and-forget. If the task throws, the exception is handled by the executor's
      Thread.UncaughtExceptionHandler — by default it prints the stack trace to stderr and the task is forgotten.
    - submit(...) always returns a Future. If the task throws and you never call get(), the exception is swallowed
      silently. This is one of the most common sources of "missing logs": code submits a job, never inspects the
      future, and the failure is invisible.
    - Pick execute when you genuinely don't care about results; pick submit when you do — but then always observe the
      future.
    */
    static void submitVsExecute() throws InterruptedException, ExecutionException {
        System.out.println("[Section 4] submit vs execute");

        try (var pool = Executors.newFixedThreadPool(1)) {
            // execute(): exception goes to the uncaught handler (visible).
            pool.execute(() -> { throw new RuntimeException("boom from execute"); });
            Thread.sleep(50);

            // submit(): exception is captured in the future. If we ignore it, it is silent.
            Future<?> f = pool.submit(() -> { throw new RuntimeException("boom from submit"); });
            try { f.get(); }
            catch (ExecutionException ee) {
                System.out.println("  future.get() rethrew: " + ee.getCause().getMessage());
            }
        }
    }

    /*
    invokeAll and invokeAny

    - invokeAll(Collection<? extends Callable<V>>) submits every task and returns only after all of them complete
      (successfully, with an exception, or by cancellation). The returned List<Future<V>> is in the same order as the
      input. A timed variant cancels stragglers when the deadline expires.
    - invokeAny(...) returns the result of the first task to complete successfully. Pending siblings are cancelled. If
      every task fails, it throws ExecutionException. Useful for "race two equivalent providers, take whichever
      answers first".
    */
    static void invokeAllAndAny() throws InterruptedException, ExecutionException {
        System.out.println("[Section 5] invokeAll, invokeAny");

        try (var pool = Executors.newFixedThreadPool(3)) {
            List<Callable<Integer>> tasks = List.of(
                    () -> { Thread.sleep(60); return 1; },
                    () -> { Thread.sleep(20); return 2; },
                    () -> { Thread.sleep(40); return 3; }
            );

            List<Future<Integer>> all = pool.invokeAll(tasks);
            System.out.print("  invokeAll results in submission order: [");
            for (var f : all) System.out.print(f.get() + " ");
            System.out.println("]");

            int first = pool.invokeAny(tasks);
            System.out.println("  invokeAny first-success result: " + first);
        }
    }

    /*
    CompletionService

    - CompletionService decouples submission from result consumption. As tasks finish, their futures land on an
      internal queue you drain with take() (blocks) or poll().
    - This lets you process results as they complete, instead of waiting for the slowest task in a fixed order.
      Typical use: fan out N work items, write each result as soon as it is ready, do not block on the slowest one.
    - The standard implementation ExecutorCompletionService wraps any ExecutorService.
    */
    static void completionService() throws InterruptedException, ExecutionException {
        System.out.println("[Section 6] CompletionService");

        try (var pool = Executors.newFixedThreadPool(3)) {
            CompletionService<Integer> cs = new ExecutorCompletionService<>(pool);
            int[] delays = { 80, 30, 50 };
            for (int i = 0; i < delays.length; i++) {
                final int id = i, delay = delays[i];
                cs.submit(() -> { Thread.sleep(delay); return id; });
            }
            for (int i = 0; i < delays.length; i++) {
                Future<Integer> ready = cs.take();           // blocks until SOMETHING is ready
                System.out.println("  consumed result " + ready.get() + " (in completion order)");
            }
        }
    }

    /*
    ScheduledExecutorService

    - schedule(task, delay, unit) runs once after a delay.
    - scheduleAtFixedRate(task, initial, period, unit) aims to fire on a fixed calendar — if a run takes longer than
      the period, runs queue up.
    - scheduleWithFixedDelay(task, initial, delay, unit) waits the given delay between the end of one run and the
      start of the next.
    - A scheduled task that throws an uncaught exception is silently dropped and will never run again. Wrap the body
      in a try/catch (or use submit and check the future) to keep periodic tasks alive.
    */
    static void scheduledExecutor() throws InterruptedException {
        System.out.println("[Section 7] ScheduledExecutorService");

        try (ScheduledExecutorService sched = Executors.newScheduledThreadPool(1)) {
            var counter = new AtomicLong();
            var handle = sched.scheduleAtFixedRate(() -> {
                long n = counter.incrementAndGet();
                System.out.println("  tick " + n);
            }, 0, 30, TimeUnit.MILLISECONDS);

            Thread.sleep(140);
            handle.cancel(false);
            System.out.println("  scheduler cancelled at count=" + counter.get());
        }
    }

    /*
    Custom ThreadPoolExecutor

    - Construct directly when you need control beyond the Executors factories:
      new ThreadPoolExecutor(corePool, maxPool, keepAliveTime, unit, workQueue, threadFactory,
      rejectedExecutionHandler).
    - The interaction is subtle, and the order matters:
      1. While fewer than corePool threads exist, every new task creates a new thread — even if other threads are idle.
      2. Once corePool threads exist, new work goes onto the queue.
      3. Only when the queue is FULL do new threads spin up, to a maximum of maxPool.
      4. If the queue is full and maxPool is reached, the RejectedExecutionHandler decides what happens.
      Consequence: with an UNBOUNDED queue (the default of newFixedThreadPool) step 3 never fires, so maxPool is
      dead configuration and the queue grows without limit. Bounded queue + CallerRunsPolicy is the usual
      back-pressure recipe.
    - Built-in handlers: AbortPolicy (throws), CallerRunsPolicy (runs on the caller — gives back-pressure for free),
      DiscardPolicy, DiscardOldestPolicy.
    - Use a ThreadFactory to give threads meaningful names — this is the single most important thing you can do to
      make production thread dumps readable.
    */
    static void customThreadPoolExecutor() throws InterruptedException {
        System.out.println("[Section 8] custom ThreadPoolExecutor");

        var seq = new AtomicLong();
        ThreadFactory factory = r -> {
            var t = new Thread(r, "report-pool-" + seq.incrementAndGet());
            t.setDaemon(false);
            return t;
        };

        // Bounded queue + small max pool + AbortPolicy → we will deliberately overflow it.
        var pool = new ThreadPoolExecutor(
                /* core */ 2, /* max */ 20,
                30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(/* capacity */ 1),
                factory,
                new ThreadPoolExecutor.AbortPolicy());

        int rejected = 0;
        for (int i = 0; i < 5; i++) {
            final int id = i;
            try {
                pool.execute(() -> {
                    System.out.println("  task " + id + " on " + Thread.currentThread().getName());
                    try { Thread.sleep(50); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (RejectedExecutionException ree) {
                rejected++;
                System.out.println("  task " + id + " rejected (no room in queue, no spare worker)");
            }
        }
        System.out.println("  total rejections = " + rejected);

        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
    }

    /*
    Graceful shutdown

    - shutdown() stops accepting new tasks and lets queued ones finish.
    - awaitTermination(timeout, unit) blocks until tasks complete or the timeout expires.
    - shutdownNow() interrupts running tasks and returns the unstarted ones in the queue.
    - The recommended sequence is: shutdown() → awaitTermination(...); if it returns false, escalate to shutdownNow()
      and awaitTermination(...) again.
    - Java 19+ makes ExecutorService AutoCloseable, so try-with-resources is the cleanest idiom — but read what
      close() actually does before relying on it. The default implementation calls shutdown() and then waits with
      no effective deadline (a loop of awaitTermination(1, DAYS)); it escalates to shutdownNow() ONLY if the thread
      calling close() is itself interrupted. So close() can block forever on a hung task. When you need a bounded
      shutdown, write the shutdown → awaitTermination → shutdownNow sequence above by hand.
    */
    static void gracefulShutdown() throws InterruptedException {
        System.out.println("[Section 9] graceful shutdown");

        var pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 4; i++) {
            final int id = i;
            pool.execute(() -> {
                try { Thread.sleep(40); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                System.out.println("  finished task " + id);
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            System.out.println("  did not terminate in 500ms — calling shutdownNow()");
            pool.shutdownNow();
            pool.awaitTermination(500, TimeUnit.MILLISECONDS);
        }
        System.out.println("  pool terminated = " + pool.isTerminated());
    }

    public static void main(String[] args) throws Exception {
        factories();
        callableAndFuture();
        submitVsExecute();
        invokeAllAndAny();
        completionService();
        scheduledExecutor();
        customThreadPoolExecutor();
        gracefulShutdown();
        System.out.println("Mod007Executors finished");
    }
}
