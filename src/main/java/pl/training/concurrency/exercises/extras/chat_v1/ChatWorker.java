package pl.training.concurrency.exercises.extras.chat_v1;

import java.io.IOException;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChatWorker implements Runnable {

    private static final String END_SESSION_COMMAND = "\\q";

    private static final Logger log = Logger.getLogger(ChatWorker.class.getName());

    private final Socket socket;
    private final ChatWorkers chatWorkers;
    private final MessageWriter writer;

    public ChatWorker(Socket socket, ChatWorkers chatWorkers) {
        this.socket = socket;
        this.chatWorkers = chatWorkers;
        this.writer = new MessageWriter(socket);
    }

    @Override
    public void run() {
        new MessageReader(socket, this::onText, this::onClose).read();
    }

    private void onText(String text) {
        // equals, not endsWith: otherwise any message that happens to end with the command
        // sequence would disconnect the sender.
        if (END_SESSION_COMMAND.equals(text.strip())) {
            closeSocket();
        } else {
            chatWorkers.broadcast(text);
        }
    }

    private void onClose() {
        chatWorkers.remove(this);
        closeSocket();
    }

    public void send(String text) {
        writer.write(text);
        if (writer.isBroken()) {
            chatWorkers.remove(this);
        }
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException exception) {
            log.log(Level.FINE, "Closing socket failed: {0}", exception.getMessage());
        }
    }
}
