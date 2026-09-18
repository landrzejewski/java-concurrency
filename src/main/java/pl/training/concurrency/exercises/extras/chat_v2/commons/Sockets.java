package pl.training.concurrency.exercises.extras.chat_v2.commons;

import java.io.IOException;
import java.net.Socket;

public class Sockets {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(Sockets.class.getName());

    // The full range of non-privileged ports. The previous 80..9000 window silently rejected
    // perfectly valid ports (9090, 50000, ...) and fell back to the default without saying so.
    private static final int MIN_PORT_NUMBER = 1_024;
    private static final int MAX_PORT_NUMBER = 65_535;

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
            LOG.log(java.util.logging.Level.FINE, "Closing socket failed: {0}", exception.getMessage());
        }
    }

}
