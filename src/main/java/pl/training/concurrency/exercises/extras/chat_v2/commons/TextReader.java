package pl.training.concurrency.exercises.extras.chat_v2.commons;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TextReader {

    private static final Logger log = Logger.getLogger(TextReader.class.getName());

    private final Consumer<String> textConsumer;
    private final BufferedReader reader;
    private final Runnable onClose;

    public TextReader(InputStream inputStream, Consumer<String> textConsumer) {
        this(new BufferedReader(new InputStreamReader(inputStream)), textConsumer, () -> { });
    }

    public TextReader(Socket socket, Consumer<String> textConsumer, Runnable onClose) {
        this(openReader(socket), textConsumer, onClose);
    }

    private TextReader(BufferedReader reader, Consumer<String> textConsumer, Runnable onClose) {
        this.reader = reader;
        this.textConsumer = textConsumer;
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
                textConsumer.accept(text);
            }
        } catch (IOException exception) {
            log.log(Level.FINE, "Connection closed: {0}", exception.getMessage());
        } finally {
            onClose.run();
        }
    }
}
