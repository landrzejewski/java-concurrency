package pl.training.concurrency.exercises.extras.chat_v1;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MessageReader {

    private static final Logger log = Logger.getLogger(MessageReader.class.getName());

    private final Consumer<String> onText;
    private final BufferedReader reader;
    private final Runnable onClose;

    public MessageReader(InputStream inputStream, Consumer<String> onText) {
        this(new BufferedReader(new InputStreamReader(inputStream)), onText, () -> { });
    }

    public MessageReader(Socket socket, Consumer<String> onText, Runnable onClose) {
        this(openReader(socket), onText, onClose);
    }

    private MessageReader(BufferedReader reader, Consumer<String> onText, Runnable onClose) {
        this.reader = reader;
        this.onText = onText;
        this.onClose = onClose;
    }

    private static BufferedReader openReader(Socket socket) {
        try {
            return new BufferedReader(new InputStreamReader(socket.getInputStream()));
        } catch (IOException exception) {
            throw new UncheckedIOException("Creating input stream failed", exception);
        }
    }

    public void read() {
        try {
            String text;
            while ((text = reader.readLine()) != null) {
                onText.accept(text);
            }
        } catch (IOException exception) {
            log.log(Level.FINE, "Connection closed: {0}", exception.getMessage());
        } finally {
            onClose.run();
        }
    }
}
