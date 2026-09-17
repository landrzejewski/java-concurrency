package pl.training.concurrency;

import java.util.*;
import java.util.concurrent.*;

public final class Mod005ConcurrentCollections {

    private Mod005ConcurrentCollections() {}

    /*
    Why Collections.synchronizedMap is not enough

    - Collections.synchronizedMap(...) wraps a map so that every individual method acquires a single global lock. Each
      method call is atomic in isolation.
    - It does NOT make compound operations atomic. Reading a value and then writing a new one based on it is two
      method calls, and another thread can sneak in between them. The classic bug:
      if (!m.containsKey(k)) m.put(k, v); — both threads pass the check, both put, only the second put survives.
    - Iteration is broken more thoroughly than you might expect. The wrapper does not synchronize iteration AT ALL:
      iterator(), stream() and spliterator() hand back the backing collection's own, unguarded objects — the JDK
      source is literally `return c.iterator(); // Must be manually synched by user!`. So iterating while another
      thread mutates throws ConcurrentModificationException, and two threads iterating are not serialised either.
      The Javadoc tells you to externally synchronize the whole iteration — at which point you have written your
      own critical section.
    - Use ConcurrentHashMap instead — it provides atomic compound operations (compute, merge, computeIfAbsent) and
      weakly consistent iterators that do not throw under concurrent modification.
    */
    static void synchronizedMapIsNotEnough() throws InterruptedException {
        System.out.println("[Section 1] synchronizedMap vs ConcurrentHashMap");

        // (a) synchronizedMap with a check-then-act: races and undercounts.
        Map<String, Integer> sync = Collections.synchronizedMap(new HashMap<>());
        sync.put("hits", 0);
        Runnable buggy = () -> {
            for (int i = 0; i < 50_000; i++) {
                int current = sync.get("hits");      // step 1
                sync.put("hits", current + 1);       // step 2 — racy!
            }
        };
        runAll(8, buggy);
        System.out.println("  synchronizedMap (get-then-put) → " + sync.get("hits") + " (expected 400000)");

        // (b) ConcurrentHashMap.compute makes the get-and-put atomic.
        var chm = new ConcurrentHashMap<String, Integer>();
        chm.put("hits", 0);
        Runnable correct = () -> {
            for (int i = 0; i < 50_000; i++) chm.compute("hits", (k, v) -> v + 1);
        };
        runAll(8, correct);
        System.out.println("  ConcurrentHashMap.compute    → " + chm.get("hits"));
    }

    /*
    ConcurrentHashMap

    - Designed for high-concurrency reads and writes. Reads are lock-free; writes lock only a small portion of the
      internal table.
    - The atomic compound operations are the killer feature:
      - compute(key, (k, v) -> ...) — atomically replace the value with the result of the lambda. The lambda must be
        short and side-effect-free.
      - computeIfAbsent(key, k -> ...) — atomically insert if missing. Perfect for memoization caches.
      - merge(key, value, (oldV, newV) -> ...) — atomic update or insert. The canonical "increment a per-key counter"
        pattern.
    - Bulk operations (forEachEntry, search, reduce) take a parallelism threshold — below it the operation runs
      sequentially; above it the map splits its internal table across the common ForkJoinPool.
    - null keys and null values are forbidden — they would be ambiguous with "not present" in concurrent semantics.
    */
    static void hitCounterAndBulkOps() throws InterruptedException {
        System.out.println("[Section 2] ConcurrentHashMap merge + bulk ops");

        var counter = new ConcurrentHashMap<String, Long>();
        String[] urls = { "/home", "/login", "/api/users", "/api/orders", "/help" };
        Runnable hammer = () -> {
            var rnd = new java.util.Random();
            for (int i = 0; i < 200_000; i++) {
                counter.merge(urls[rnd.nextInt(urls.length)], 1L, Long::sum);
            }
        };
        runAll(16, hammer);

        // Bulk reduce: total across all keys, parallel above 1000 entries.
        long total = counter.reduceValues(1000, Long::sum);
        System.out.println("  total hits = " + total);

        // forEachEntry — top URL by value.
        var topRef = new java.util.concurrent.atomic.AtomicReference<Map.Entry<String, Long>>(null);
        counter.forEachEntry(1000, e -> {
            var current = topRef.get();
            if (current == null || e.getValue() > current.getValue()) topRef.set(e);
        });
        System.out.println("  per-URL = " + counter);
        System.out.println("  topish entry observed = " + topRef.get()); // weak under contention but illustrative
    }

    /*
    CopyOnWriteArrayList

    - Every mutation (add, remove, set) allocates a fresh array; readers see the old immutable snapshot. The result is
      lock-free reads and atomic publishing.
    - Best for collections that are read often and written rarely: listener registries, configuration sets, observer
      chains.
    - Iteration is cheap and never throws — the iterator wraps the snapshot taken at its creation. A modification
      mid-iteration is simply not visible until the next iterator is created.
    - Quadratic for write-heavy workloads (each write copies the entire array).
    */
    static void copyOnWriteEventBus() throws InterruptedException {
        System.out.println("[Section 3] CopyOnWriteArrayList event bus");

        var listeners = new CopyOnWriteArrayList<String>();
        listeners.add("audit");
        listeners.add("metrics");

        // A reader iterating sees a STABLE snapshot even if the writer adds in the middle.
        var reader = Thread.ofPlatform().name("dispatcher").start(() -> {
            int seen = 0;
            for (String name : listeners) {
                seen++;
                System.out.println("  iterating: notify " + name);
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
            System.out.println("  reader saw " + seen + " listeners during the dispatch");
        });

        Thread.sleep(10);
        listeners.add("logger"); // mid-iteration mutation — reader will NOT see it this round
        reader.join();

        System.out.println("  next dispatch will see " + listeners.size() + " listeners");
    }

    /*
    BlockingQueue family

    - A BlockingQueue<E> adds blocking semantics to a queue: put blocks while the queue is full, take blocks while it
      is empty. This makes producer–consumer code trivial — no manual wait/notify, no Conditions.
    - Three method styles:
      - Throw on failure: add, remove, element — throw if full/empty.
      - Special return: offer, poll, peek — return false/null.
      - Block / time out: put, take, and offer(e, t, u) / poll(t, u).
    - Implementations:
      - ArrayBlockingQueue(capacity) — fixed-size array, optionally fair.
      - LinkedBlockingQueue([capacity]) — linked-list, optionally bounded; faster on multiprocessors because head and
        tail can be locked separately.
      - SynchronousQueue — zero capacity. Every put waits for a matching take and vice versa. Used for direct
        hand-offs (e.g., newCachedThreadPool).
      - PriorityBlockingQueue — unbounded, ordered by Comparator. Useful for job queues with priorities.
    */
    static void blockingQueueFamily() throws InterruptedException {
        System.out.println("[Section 4] BlockingQueue family");

        // (a) ArrayBlockingQueue — bounded.
        BlockingQueue<String> bounded = new ArrayBlockingQueue<>(2);
        bounded.put("a");
        bounded.put("b");
        boolean accepted = bounded.offer("c", 50, TimeUnit.MILLISECONDS);
        System.out.println("  ArrayBQ(2): offer with 50ms timeout while full → " + accepted);

        // (b) PriorityBlockingQueue — unbounded, ordered.
        var jobs = new PriorityBlockingQueue<Integer>();
        jobs.offer(5); jobs.offer(1); jobs.offer(3);
        System.out.println("  PriorityBQ pulls in priority order: "
                + jobs.poll() + ", " + jobs.poll() + ", " + jobs.poll());

        // (c) SynchronousQueue — zero capacity, every put waits for a take.
        BlockingQueue<String> sync = new SynchronousQueue<>();
        var consumer = Thread.ofPlatform().name("syncQ-consumer").start(() -> {
            try { System.out.println("  syncQ took: " + sync.take()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread.sleep(20);
        sync.put("hand-off"); // returns as soon as the waiting take() picks it up; with no
                              // consumer present this put would simply block, not fail
        consumer.join();
    }

    /*
    Producer–consumer with BlockingQueue

    - Compare to Mod003/Mod004: the queue subsumes both the locking and the condition signalling. The producer just
      calls put, the consumer just calls take, and back-pressure is handled automatically because put blocks.
    - Most modern producer–consumer code in Java is written this way. Reach for explicit locks or wait/notify only
      when the queue API does not fit.
    */
    static void producerConsumerWithBlockingQueue() throws InterruptedException {
        System.out.println("[Section 5] producer–consumer with LinkedBlockingQueue");

        var queue = new LinkedBlockingQueue<String>(3);
        var producer = Thread.ofPlatform().name("producer").start(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    queue.put("doc-" + i);
                    System.out.println("  put doc-" + i + " (size=" + queue.size() + ")");
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        var consumer = Thread.ofPlatform().name("consumer").start(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(40);                       // slower consumer → producer blocks
                    System.out.println("  got " + queue.take());
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.join();
        consumer.join();
    }

    /*
    ConcurrentLinkedQueue / ConcurrentLinkedDeque

    - Lock-free, unbounded, non-blocking. offer/poll always return immediately.
    - Use when you do not want producers ever to block (e.g., logging, event bus). The price is unbounded growth —
      combine with monitoring or a separate drain thread, otherwise a slow consumer becomes a memory leak.
    - size() is O(n) and only weakly consistent — do not use it in tight loops.
    */
    static void concurrentLinkedQueue() throws InterruptedException {
        System.out.println("[Section 6] ConcurrentLinkedQueue");

        var inbox = new ConcurrentLinkedQueue<String>();
        Runnable producer = () -> {
            for (int i = 0; i < 1000; i++) inbox.offer("msg-" + i);
        };
        runAll(4, producer); // 4 producers × 1000 messages — none of them ever block
        System.out.println("  inbox holds " + inbox.size() + " messages (no blocking ever)");
    }

    /*
    ConcurrentSkipListMap / ConcurrentSkipListSet

    - A concurrent NavigableMap (SortedMap) — keys are kept in their natural ordering or by an explicit Comparator.
    - Operations are O(log n) and lock-free (skip list with CAS). Useful for leaderboards, time-series buckets, range
      queries — anywhere you need both concurrency and ordered traversal.
    - Iterators are weakly consistent. firstKey, lastKey, headMap, tailMap, subMap, descendingMap all work
      concurrently.
    - size() is NOT a constant-time operation here (it walks the list) and is only weakly consistent — same caveat
      as ConcurrentLinkedQueue in §6.
    */
    static void concurrentSkipListMap() throws InterruptedException {
        System.out.println("[Section 7] ConcurrentSkipListMap");

        // Negative keys give us descending order in the natural traversal. Scores are not unique, so
        // the value is a LIST of players — a plain put() would silently drop every tie. merge() keeps
        // the whole compound update atomic, exactly as in §2.
        var leaderboard = new ConcurrentSkipListMap<Integer, List<String>>();
        Runnable updater = () -> {
            var rnd = new java.util.Random();
            for (int i = 0; i < 500; i++) {
                String player = "p-" + Thread.currentThread().getName() + "-" + i;
                leaderboard.merge(-rnd.nextInt(10_000), List.of(player),
                        (oldList, newList) -> {
                            var merged = new ArrayList<>(oldList);
                            merged.addAll(newList);
                            return List.copyOf(merged);
                        });
            }
        };
        runAll(4, updater);

        System.out.println("  distinct scores = " + leaderboard.size()
                + ", players = " + leaderboard.values().stream().mapToInt(List::size).sum());
        System.out.println("  top 5 scores (ties kept):");
        leaderboard.entrySet().stream()
                .limit(5)
                .forEach(e -> System.out.println("    score=" + (-e.getKey()) + " by " + e.getValue()));
    }

    /*
    Iteration semantics

    - The legacy collections (HashMap, ArrayList, Collections.synchronizedMap, etc.) use fail-fast iterators: any
      structural modification during iteration throws ConcurrentModificationException.
    - java.util.concurrent collections use weakly consistent iterators:
      - They never throw ConcurrentModificationException.
      - They reflect the state at, or after, iterator construction; they may, but are not required to, reflect
        modifications made after construction.
      - They traverse each element exactly once.
    - The trade-off: weakly consistent iteration cannot give you a precise snapshot, but it lets reads and mutations
      proceed in parallel without copy-on-write costs.
    */
    static void iterationSemantics() throws InterruptedException {
        System.out.println("[Section 8] iteration semantics");

        // (a) ConcurrentHashMap — weakly consistent, no exception.
        var concurrent = new ConcurrentHashMap<Integer, Integer>();
        for (int i = 0; i < 50; i++) concurrent.put(i, i);
        var mutator1 = Thread.ofPlatform().start(() -> {
            for (int i = 50; i < 1000; i++) concurrent.put(i, i);
        });
        int seen1 = 0;
        for (var ignored : concurrent.entrySet()) seen1++;
        mutator1.join();
        System.out.println("  ConcurrentHashMap iteration finished without throwing, saw "
                + seen1 + " entries (mid-iteration mutations may or may not be reflected)");

        // (b) synchronizedMap — fail-fast: ConcurrentModificationException unless externally synced.
        Map<Integer, Integer> sync = Collections.synchronizedMap(new HashMap<>());
        for (int i = 0; i < 50; i++) sync.put(i, i);
        var mutator2 = Thread.ofPlatform().start(() -> {
            for (int i = 50; i < 100; i++) {
                sync.put(i, i);
                try { Thread.sleep(1); } catch (InterruptedException e) { return; }
            }
        });
        try {
            for (var ignored : sync.entrySet()) Thread.sleep(2);
            System.out.println("  synchronizedMap iteration finished without exception (this run got lucky)");
        } catch (java.util.ConcurrentModificationException ex) {
            System.out.println("  synchronizedMap iteration threw " + ex.getClass().getSimpleName());
        }
        mutator2.join();
    }

    // helper
    private static void runAll(int threads, Runnable body) throws InterruptedException {
        var ts = new ArrayList<Thread>();
        for (int i = 0; i < threads; i++) ts.add(new Thread(body));
        for (var t : ts) t.start();
        for (var t : ts) t.join();
    }

    public static void main(String[] args) throws InterruptedException {
        synchronizedMapIsNotEnough();
        hitCounterAndBulkOps();
        copyOnWriteEventBus();
        blockingQueueFamily();
        producerConsumerWithBlockingQueue();
        concurrentLinkedQueue();
        concurrentSkipListMap();
        iterationSemantics();
        System.out.println("Mod005ConcurrentCollections finished");
    }
}
