package pl.training.concurrency;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/*
Race condition recap — checklist

You already saw a non-atomic increment in Mod002. Race conditions follow a small number of recurring shapes;
recognise them and reach for the appropriate fix.

  Shape                    Example                                  Fix
  ------------------------ ---------------------------------------- ----------------------------------------------
  Read–modify–write        count++, total += x                      Atomic* / synchronized
  Check-then-act           if (!map.containsKey(k)) map.put(k,v)    ConcurrentHashMap.putIfAbsent / computeIfAbsent
  Get-then-put on counter  m.put(k, m.getOrDefault(k,0)+1)          ConcurrentHashMap.merge(k,1L,Long::sum)
  Compound state           "two fields must be consistent"          one lock around the whole compound update

Priority inversion

- A high-priority thread waits for a lock held by a low-priority thread, while a medium-priority thread (which
  neither holds nor needs the lock) keeps preempting the low-priority holder. The high-priority thread effectively
  runs at the medium priority.
- Real-time operating systems offer priority inheritance (the holder temporarily inherits the waiter's priority) to
  fix this. The HotSpot JVM does not — production Java rarely uses thread priorities for scheduling.
- The defensive practice is simply not to rely on priorities for correctness. Use bounded queues, fair locks, and
  explicit deadlines instead.

Testing concurrent code

- Plain JUnit tests run sequentially; they are good for verifying a small contention scenario but cannot prove the
  absence of races (the bug might require a specific schedule that JUnit never produces).
- For race-detection tests, use the jcstress harness. It runs your test inside specially controlled threads, repeats
  it millions of times across schedules, and reports if any unexpected outcomes occurred. jcstress needs its own
  build (an uber-jar with its annotation processor), so this project puts it behind a Maven profile:

    ./mvnw -P jcstress package
    java --enable-preview -jar target/jcstress.jar -jvmArgs "--enable-preview" -t CounterTest

- The starter jcstress test lives in src/jcstress/java/pl/training/concurrency/CounterTest.java (its own source
  root, so an ordinary `mvn test` neither compiles nor runs it). The plain-JUnit counterpart, which can only
  observe lost updates rather than hunt for them, is
  src/test/java/pl/training/concurrency/extras/tests/LostUpdateTest.java.
*/

public final class Mod014ThreadingProblems {

    private Mod014ThreadingProblems() {}

    /*
    Deadlock

    - Two (or more) threads each hold a lock the other is waiting for. Nobody proceeds. Often called the circular
      wait condition.
    - The minimal recipe: thread A acquires lock-1 then tries lock-2; thread B acquires lock-2 then tries lock-1.
    - A deadlocked JVM looks like a hang, but jstack <pid> (or kill -3 <pid>, or VisualVM) reports the cycle
      explicitly with the message "Found one Java-level deadlock".
    - There is no way to break a deadlock from inside the JVM: Thread.stop() is gone in modern JDKs, and a thread
      blocked on entering a synchronized block ignores interrupts (Mod003 §7). The only cure is prevention — see §3.
    - The two demo threads below stay deadlocked forever, so they are created as DAEMON threads. That way they do
      not keep the JVM alive and main() can simply move on to the next section.
    */
    static void deadlockDemo() throws InterruptedException {
        System.out.println("[Section 2] deadlock demo");

        var lockA = new Object();
        var lockB = new Object();

        var t1 = Thread.ofPlatform().name("T1-A-then-B").daemon(true).start(() -> {
            synchronized (lockA) {
                sleep(50);
                synchronized (lockB) { /* never gets here */ }
            }
        });
        var t2 = Thread.ofPlatform().name("T2-B-then-A").daemon(true).start(() -> {
            synchronized (lockB) {
                sleep(50);
                synchronized (lockA) { /* never gets here */ }
            }
        });

        Thread.sleep(200);

        System.out.println("  T1 state = " + t1.getState() + " (BLOCKED on lockB)");
        System.out.println("  T2 state = " + t2.getState() + " (BLOCKED on lockA)");
        System.out.println("  PID = " + ProcessHandle.current().pid()
                + "  → run `jstack " + ProcessHandle.current().pid() + "` to see the cycle");
        System.out.println("  (both threads stay stuck; they are daemons, so the JVM can still exit)");
    }

    /*
    Lock-ordering rule

    - The classic deadlock fix: define a global ordering on locks and always acquire them in that order across the
      whole program. If every thread acquires locks in the same sequence, no cycle can form.
    - A common runtime ordering uses System.identityHashCode(lock) to compare two locks at acquisition time and
      acquire the smaller-hashed one first. If two locks tie, fall back to a tie-breaker mutex.
    - This is the standard recipe for the "transfer money between two accounts" operation: sort the two account
      locks by hash before acquiring.
    - Note the shape of the demo below: transfer(from, to) is called with the SAME two locks but in OPPOSITE order
      by the two threads — exactly the situation that deadlocked §2. Comment out the sorting block and this section
      hangs; leave it in and both transfers complete.
    */
    static void lockOrderingFix() throws InterruptedException {
        System.out.println("[Section 3] lock-ordering rule");

        var accountA = new Object();
        var accountB = new Object();

        var a1 = new Thread(() -> transfer(accountA, accountB), "transfer-A→B");
        var a2 = new Thread(() -> transfer(accountB, accountA), "transfer-B→A");
        a1.start(); a2.start();
        a1.join(); a2.join();
        System.out.println("  both transfers completed without deadlock");
    }

    private static void transfer(Object from, Object to) {
        // Sort the two locks by identityHashCode before acquiring. Both threads now
        // acquire in the same global order regardless of the argument order, so a
        // cycle cannot form. WITHOUT these three lines the two calls above deadlock.
        Object first = from, second = to;
        if (System.identityHashCode(first) > System.identityHashCode(second)) {
            first = to; second = from;
        }
        synchronized (first) {
            sleep(20); // widen the window so an unordered version would surely deadlock
            synchronized (second) {
                System.out.println("  " + Thread.currentThread().getName() + " ok");
            }
        }
    }

    /*
    Livelock

    - Like deadlock, no progress is made; unlike deadlock, the threads are active — they keep doing something,
      repeatedly making moves that cancel each other out. In a thread dump they show up as RUNNABLE, not BLOCKED,
      which is exactly what makes livelock harder to spot than deadlock.
    - The canonical example: two diners share one spoon. Each is unfailingly polite — whenever the partner is still
      hungry, it hands the spoon over instead of eating. Both stay hungry, so both keep handing the spoon back and
      forth forever. Every step is "progress" locally and no progress globally.
    - The essential ingredient is SYMMETRY: both parties respond to the same condition with the same concession.
      Deadlock is a cycle of waiting; livelock is a cycle of yielding.
    - Cure: break the symmetry. Defer only sometimes (randomised), or give one party a fixed priority, or bound the
      number of concessions. The second run below defers on a coin flip and finishes almost immediately.
    - The same shape appears with locks: two workers that tryLock both resources, release everything on failure, and
      retry after the SAME back-off will keep colliding. The fix there is identical — randomised jitter.
    */
    static void livelock() throws InterruptedException {
        System.out.println("[Section 4] livelock");

        System.out.println("  always defer (the bug):        " + dine(false));
        System.out.println("  defer on a coin flip (cure):   " + dine(true));
    }

    /** The shared resource. Ownership is guarded by the object's monitor. */
    private static final class Spoon {
        private Diner owner;
        Spoon(Diner owner) { this.owner = owner; }
        synchronized Diner owner() { return owner; }
        synchronized void passTo(Diner diner) { owner = diner; }
    }

    private static final class Diner {
        private final String name;
        private volatile boolean hungry = true;
        Diner(String name) { this.name = name; }

        /**
         * Eat with the spoon, deferring to a still-hungry partner. With breakSymmetry == false both diners defer
         * unconditionally and nobody ever eats — that is the livelock.
         */
        void eatWith(Spoon spoon, Diner partner, boolean breakSymmetry,
                     AtomicInteger passes, AtomicInteger meals, long deadlineNanos) {
            while (hungry && System.nanoTime() < deadlineNanos) {
                if (spoon.owner() != this) {          // not my turn — spin politely
                    Thread.onSpinWait();
                    continue;
                }
                if (partner.hungry && (!breakSymmetry || ThreadLocalRandom.current().nextBoolean())) {
                    passes.incrementAndGet();          // "no, you first" — hand the spoon over
                    spoon.passTo(partner);
                    continue;
                }
                meals.incrementAndGet();               // actually eat
                hungry = false;
                spoon.passTo(partner);
            }
        }
    }

    private static String dine(boolean breakSymmetry) throws InterruptedException {
        var alphonse = new Diner("Alphonse");
        var gaston = new Diner("Gaston");
        var spoon = new Spoon(alphonse);
        var passes = new AtomicInteger();
        var meals = new AtomicInteger();
        long deadline = System.nanoTime() + 300_000_000L; // 300 ms budget

        var t1 = Thread.ofPlatform().name("Alphonse")
                .start(() -> alphonse.eatWith(spoon, gaston, breakSymmetry, passes, meals, deadline));
        var t2 = Thread.ofPlatform().name("Gaston")
                .start(() -> gaston.eatWith(spoon, alphonse, breakSymmetry, passes, meals, deadline));
        t1.join(); t2.join();

        return meals.get() + "/2 diners ate after " + String.format(Locale.ROOT, "%,d", passes.get()) + " courtesy hand-offs"
                + (meals.get() == 0 ? "  ← livelocked: busy, RUNNABLE, zero progress" : "");
    }

    /*
    Starvation

    - A thread cannot make progress because other threads keep grabbing the resource it is waiting for.
    - A non-fair ReentrantLock (the default) lets a newly arriving thread "barge" past queued waiters. With a very
      short critical section the thread that has just released the lock is usually the one that wins the race to
      re-acquire it, so a handful of threads monopolise it and the rest starve.
    - A fair lock (new ReentrantLock(true)) hands the lock out in FIFO order — the distribution evens out, but total
      throughput drops sharply because every hand-off is a context switch.
    - The measurement below is time-boxed on purpose: each worker takes the lock as often as it can for a fixed
      window. Giving every worker a fixed ITERATION COUNT instead would hide the effect completely — the counts
      would come out equal by construction.
    - Other cures: Semaphore(N, true), partitioning the workload so contention is local, or removing the shared lock
      altogether (Mod002 atomics, Mod005 concurrent collections).
    */
    static void starvation() throws InterruptedException {
        System.out.println("[Section 5] starvation");

        report("unfair (default, barging)", contendFor(new ReentrantLock(false)));
        report("fair    (FIFO)           ", contendFor(new ReentrantLock(true)));
        System.out.println("  → barging maximises throughput; fairness maximises evenness. Pick per workload.");
    }

    private static Map<String, Integer> contendFor(ReentrantLock lock) throws InterruptedException {
        var counts = new ConcurrentHashMap<String, Integer>();
        long deadline = System.nanoTime() + 300_000_000L; // 300 ms window
        var ts = new Thread[6];
        for (int i = 0; i < ts.length; i++) {
            String name = "w-" + i;
            counts.put(name, 0);
            ts[i] = new Thread(() -> {
                while (System.nanoTime() < deadline) {
                    lock.lock();
                    try { counts.merge(Thread.currentThread().getName(), 1, Integer::sum); }
                    finally { lock.unlock(); }
                }
            }, name);
        }
        for (var t : ts) t.start();
        for (var t : ts) t.join();
        return new TreeMap<>(counts);
    }

    private static void report(String label, Map<String, Integer> counts) {
        int min = counts.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        long total = counts.values().stream().mapToInt(Integer::intValue).sum();
        System.out.printf(Locale.ROOT, "  %s total=%,d  min=%,d  max=%,d  max/min=%.1fx%n",
                label, total, min, max, min == 0 ? Double.POSITIVE_INFINITY : (double) max / min);
        System.out.println("      " + counts);
    }

    /*
    Diagnostics workflow

    1. Get the PID — ProcessHandle.current().pid(), or jps -l.
    2. Take a thread dump — jstack <pid>, or send the JVM SIGQUIT with kill -3 <pid>, or use VisualVM / JConsole
       "Thread dump".
    3. Read the dump — look for:
       - BLOCKED threads → contention; check who owns the monitor.
       - WAITING threads with no notifier → missing notify/signal.
       - "Found one Java-level deadlock" → cycle; switch to lock-ordering (§3).
       - RUNNABLE threads spinning → busy-loop or livelock (§4).
    4. For repeating bugs, JFR (Java Flight Recorder) records lock contention and parking events with timestamps —
       useful for investigations across many minutes of runtime.
    */
    static void diagnosticsHints() {
        System.out.println("[Section 6] diagnostics workflow");
        long pid = ProcessHandle.current().pid();
        System.out.println("  PID            = " + pid);
        System.out.println("  thread dump    : jstack " + pid);
        System.out.println("  signal-based   : kill -3 " + pid);
        System.out.println("  GUI            : VisualVM, JConsole, JMC");
        System.out.println("  flight recorder: jcmd " + pid + " JFR.start duration=10s filename=rec.jfr");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void main(String[] args) throws InterruptedException {
        deadlockDemo();
        lockOrderingFix();
        livelock();
        starvation();
        diagnosticsHints();
        System.out.println("Mod014ThreadingProblems finished");
    }
}
