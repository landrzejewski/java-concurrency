package pl.training.chat_v2.commons;

import java.io.IOException;
import java.net.Socket;

public class Sockets {

    private static final int MIN_PORT_NUMBER = 80;
    private static final int MAX_PORT_NUMBER = 9000;

    private Sockets() {
    }

    public static int parsePort(String text, int defaultPort) {
        try {
            var port = Integer.parseInt(text);
            return isInRange(port) ? port : defaultPort;
        } catch (NumberFormatException exception) {
            return defaultPort;
        }
    }

    private static boolean isInRange(int portNumber) {
        return portNumber >= MIN_PORT_NUMBER && portNumber <= MAX_PORT_NUMBER;
    }

    public static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

}
