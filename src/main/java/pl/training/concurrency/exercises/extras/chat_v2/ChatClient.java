package pl.training.concurrency.exercises.extras.chat_v2;

import pl.training.concurrency.extras.chat_v2.commons.Sockets;
import pl.training.concurrency.extras.chat_v2.commons.TextReader;
import pl.training.concurrency.extras.chat_v2.commons.TextWriter;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;
import java.util.logging.Logger;

public class ChatClient {

    private static final Logger log = Logger.getLogger(ChatClient.class.getName());

    private static final int DEFAULT_PORT = 8888;

    private final Socket socket;
    private final Runnable readFromSocket;
    private final Runnable readFromConsole;

    public ChatClient(String host, int port, String name) throws IOException {
        socket = new Socket(host, port);
        // One writer for the whole session, not one per message: a PrintWriter per line would allocate
        // a new buffer over the same stream every time the user hits enter.
        var writer = new TextWriter(socket);
        readFromSocket = () -> new TextReader(socket, log::info, () -> Sockets.close(socket)).read();
        readFromConsole = () -> new TextReader(System.in, text -> writer.write(name + ": " + text)).read();
    }

    private void start() throws InterruptedException {
        // The console reader owns the session's lifetime, so it is the non-daemon thread. The socket
        // reader is a daemon: it parks in readLine() forever, and must not be able to outlive the
        // console reader and keep the JVM up after end-of-input.
        var socketReader = new Thread(readFromSocket, "socket-reader");
        socketReader.setDaemon(true);
        socketReader.start();

        var consoleReader = new Thread(readFromConsole, "console-reader");
        consoleReader.start();
        consoleReader.join();
        Sockets.close(socket);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Sockets.parsePort(args[1], DEFAULT_PORT) : DEFAULT_PORT;
        var name = args.length > 2 ? args[2] : UUID.randomUUID().toString();
        new ChatClient(host, port, name).start();
    }

}
