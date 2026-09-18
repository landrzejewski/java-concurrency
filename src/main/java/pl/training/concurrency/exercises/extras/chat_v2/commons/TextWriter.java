package pl.training.concurrency.exercises.extras.chat_v2.commons;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.Socket;

public class TextWriter {

    private final PrintWriter writer;

    public TextWriter(Socket socket) {
        try {
            writer = new PrintWriter(socket.getOutputStream(), true);
        } catch (IOException exception) {
            // Fail here rather than hand back an object whose every method throws NullPointerException.
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
