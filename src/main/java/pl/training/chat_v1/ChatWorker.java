package pl.training.chat_v1;

import java.io.IOException;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChatWorker implements Runnable {

    private static final String END_SESSION_COMMAND = "\\q";

    private final Logger logger = Logger.getLogger(getClass().getName());
    private final Socket socket;
    private final ChatWorkers chatWorkers;
    private MessageWriter writer;

    public ChatWorker(Socket socket, ChatWorkers chatWorkers) {
        this.socket = socket;
        this.chatWorkers = chatWorkers;
        writer = new MessageWriter(socket);
    }

    @Override
    public void run() {
        new MessageReader(socket, this::onText, () -> chatWorkers.remove(this)).read();
    }

    private void onText(String text) {
        if (text.endsWith(END_SESSION_COMMAND)) {
            closeSocket();
        } else {
            chatWorkers.broadcast(text);
        }
    }

    public void send(String text) {
        writer.write(text);
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Closing socked failed: " + e.getMessage());
        }
    }

}
