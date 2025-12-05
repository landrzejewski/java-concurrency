package pl.training.chat_v1;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MessageWriter {

    private final Logger logger = Logger.getLogger(getClass().getName());

    private PrintWriter writer;

    public MessageWriter(Socket socket) {
        try {
            writer = new PrintWriter(socket.getOutputStream(), true);
        } catch (IOException e) {
           logger.log(Level.SEVERE, "Creating output stream failed: " + e.getMessage());
        }
    }

    public void write(String text) {
        writer.println(text);
    }

}
