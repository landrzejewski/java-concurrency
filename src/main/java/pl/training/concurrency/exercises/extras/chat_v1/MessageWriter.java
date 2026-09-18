package pl.training.concurrency.exercises.extras.chat_v1;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.Socket;

/**
 * The constructor fails loudly instead of leaving {@code writer} null. A "successfully" constructed object whose
 * every method throws NullPointerException is far harder to diagnose than a constructor that refuses to build it.
 */
public class MessageWriter {

    private final PrintWriter writer;

    public MessageWriter(Socket socket) {
        try {
            writer = new PrintWriter(socket.getOutputStream(), true);
        } catch (IOException exception) {
            throw new UncheckedIOException("Creating output stream failed", exception);
        }
    }

    public void write(String text) {
        writer.println(text);
    }

    public boolean isBroken() {
        return writer.checkError();
    }
}
