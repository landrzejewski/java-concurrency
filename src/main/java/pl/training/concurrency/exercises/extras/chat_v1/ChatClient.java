package pl.training.concurrency.exercises.extras.chat_v1;

import java.io.IOException;
import java.net.Socket;

public class ChatClient {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8888;

    private final Socket socket;
    private final MessageWriter writer;
    private final String name;

    public ChatClient(String host, int port, String name) throws IOException {
        this.socket = new Socket(host, port);
        this.writer = new MessageWriter(socket);   // one writer, not one per message
        this.name = name;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        String name = args.length > 2 ? args[2] : "user-" + ProcessHandle.current().pid();
        new ChatClient(host, port, name).start();
    }

    private void start() throws InterruptedException {
        // The console reader is the thread that keeps the client alive: when stdin ends, so does the
        // session. The socket reader is a daemon so it cannot outlive it.
        var socketReader = Thread.ofPlatform().name("socket-reader").daemon(true)
                .start(() -> new MessageReader(socket, System.out::println, this::close).read());
        var consoleReader = Thread.ofPlatform().name("console-reader")
                .start(() -> new MessageReader(System.in, text -> writer.write(name + ": " + text)).read());

        consoleReader.join();
        close();
        socketReader.interrupt();
    }

    private void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
