package pl.training.chat_v1;

import java.util.ArrayList;
import java.util.List;

public class ChatWorkers {

    private final List<ChatWorker> chatWorkers = new ArrayList<>();

    public synchronized void add(ChatWorker chatWorker) {
        chatWorkers.add(chatWorker);
    }

    public synchronized void remove(ChatWorker chatWorker) {
        chatWorkers.remove(chatWorker);
    }

    public synchronized void broadcast(String text) {
        chatWorkers.forEach(chatWorker -> chatWorker.send(text));
    }

}
