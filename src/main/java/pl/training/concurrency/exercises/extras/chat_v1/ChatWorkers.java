package pl.training.concurrency.exercises.extras.chat_v1;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry of connected clients.
 *
 * <p>The obvious implementation — an ArrayList behind {@code synchronized} methods — has a subtle but serious
 * flaw: {@code broadcast} would hold the lock while writing to every client's socket. A single client that stops
 * reading (a laptop that went to sleep, a debugger paused at a breakpoint) fills its send buffer, blocks the
 * broadcasting thread, and with the lock still held nobody else can join, leave, or send anything. The whole chat
 * freezes because of one slow peer.
 *
 * <p>{@link CopyOnWriteArrayList} removes the lock from the read path entirely: iteration walks an immutable
 * snapshot, so broadcasting never blocks a joiner. This is exactly the workload it is designed for — many reads,
 * rare writes (Mod005 §3).
 */
public class ChatWorkers {

    private final List<ChatWorker> chatWorkers = new CopyOnWriteArrayList<>();

    public void add(ChatWorker chatWorker) {
        chatWorkers.add(chatWorker);
    }

    public void remove(ChatWorker chatWorker) {
        chatWorkers.remove(chatWorker);
    }

    public void broadcast(String text) {
        for (var chatWorker : chatWorkers) {
            chatWorker.send(text);
        }
    }

    public int size() {
        return chatWorkers.size();
    }
}
