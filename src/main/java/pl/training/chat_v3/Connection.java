package pl.training.chat_v3;

import lombok.extern.java.Log;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.logging.Level;

@Log
public class Connection {

    private PrintWriter writer;

    Connection(Socket socket) {
        try {
            writer = new PrintWriter(socket.getOutputStream(), true);
        } catch (IOException ex) {
            log.log(Level.INFO,  "Creating output stream failed - " + ex.getMessage());
        }
    }

    void send(String message) {
        writer.println(message);
    }

}
