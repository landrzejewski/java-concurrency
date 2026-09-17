package pl.training.concurrency.exercises.mod005;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Exercise 5.3 — Three-stage pipeline with the BlockingQueue family.
 *
 * <p>Related: Mod005 §4, §5
 */
public final class Ex53Pipeline {

    private Ex53Pipeline() {}

    record Message(int id, boolean urgent, String payload) implements Comparable<Message> {
        static final Message POISON = new Message(-1, false, "POISON");

        /** urgent first, then by id; the poison pill sorts last so that it leaves the priority queue after real work. */
        @Override public int compareTo(Message other) {
            if (this == POISON || other == POISON) {
                return this == other ? 0 : this == POISON ? 1 : -1;
            }
            if (urgent != other.urgent) {
                return urgent ? -1 : 1;
            }
            return Integer.compare(id, other.id);
        }
    }

    static final int MESSAGES = 200;

    public static void main(String[] args) throws InterruptedException {
        BlockingQueue<Message> parsed = new ArrayBlockingQueue<>(10);   // bounded: back-pressure on the reader
        BlockingQueue<Message> toWrite = new PriorityBlockingQueue<>(); // unbounded, ordered
        int[] readerWaits = new int[1];
        List<Message> written = new ArrayList<>();

        var reader = Thread.ofPlatform().name("reader").unstarted(() -> {
            try {
                for (int id = 1; id <= MESSAGES; id++) {
                    var message = new Message(id, id % 7 == 0, "payload-" + id);
                    while (!parsed.offer(message, 10, TimeUnit.MILLISECONDS)) { // timed: report, then retry
                        readerWaits[0]++;
                    }
                }
                parsed.put(Message.POISON); // blocking: the pill must get through
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var parser = Thread.ofPlatform().name("parser").unstarted(() -> {
            try {
                while (true) {
                    var message = parsed.take();          // blocking
                    if (message == Message.POISON) {
                        toWrite.put(Message.POISON);       // propagate the pill downstream
                        return;
                    }
                    Thread.sleep(2);                       // parsing is slower than reading -> the reader waits
                    toWrite.put(new Message(message.id(), message.urgent(), message.payload().toUpperCase(Locale.ROOT)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var writer = Thread.ofPlatform().name("writer").unstarted(() -> {
            try {
                while (true) {
                    var message = toWrite.take();
                    if (message == Message.POISON) {
                        return;
                    }
                    Thread.sleep(5);                       // writing is slowest -> the priority queue fills up
                    written.add(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        reader.start();
        parser.start();
        writer.start();
        reader.join();
        parser.join();
        writer.join();

        long urgentTotal = written.stream().filter(Message::urgent).count();
        long urgentInFirst40 = written.stream().limit(40).filter(Message::urgent).count();
        System.out.printf(Locale.ROOT, "written %d of %d messages, reader waited %d times%n",
                written.size(), MESSAGES, readerWaits[0]);
        System.out.printf(Locale.ROOT, "urgent messages: %d total, %d of them among the first 40 written%n",
                urgentTotal, urgentInFirst40);
        System.out.println("first 15 ids written: "
                + written.stream().limit(15).map(m -> m.id() + (m.urgent() ? "!" : "")).toList());
        System.out.println(written.size() == MESSAGES ? "OK — nothing lost, all threads terminated" : "BROKEN");

        // Method styles used: the reader uses the timed style (offer with timeout) so it can count and report
        // back-pressure instead of blocking silently; the parser and writer use the blocking style (take/put)
        // because they have nothing better to do than wait. The throwing style (add/remove/element) and the
        // special-value style (offer/poll/peek without timeout) are for callers that must never block, e.g. a
        // UI thread or a caller that has its own fallback.
        System.out.println("Ex53Pipeline finished");
    }
}
