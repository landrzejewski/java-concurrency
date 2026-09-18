package pl.training.concurrency.exercises.extras.chat_v1;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The simplest of the three chat servers: one thread per connection.
 *
 * <p>Historically this design capped a server at a few thousand clients, because every connection cost a platform
 * thread with a megabyte of stack. Virtual threads remove that ceiling without changing a line of the blocking
 * logic below — which is the whole argument of Mod011. Compare chat_v2 (a bounded pool plus an event bus) and
 * chat_v3 (RxJava) to see what people used to do instead.
 */
public class ChatServer {

    private static final Logger log = Logger.getLogger(ChatServer.class.getName());
    private static final int DEFAULT_PORT = 8888;

    private final ChatWorkers chatWorkers = new ChatWorkers();
    /* One builder for the whole server. Thread.Builder.name(prefix, start) keeps its counter in the
       BUILDER, so creating a fresh builder per connection would reset it and name every thread
       "chat-client-1". */
    private final Thread.Builder threadBuilder = Thread.ofVirtual().name("chat-client-", 1);

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        new ChatServer().start(port);
    }

    private void start(int port) {
        ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException exception) {
            log.log(Level.SEVERE, "Server failed to start: " + exception.getMessage());
            return;
        }
        log.log(Level.INFO, "Server is listening on port: {0}", port);
        try (serverSocket) {
            listen(serverSocket);
        } catch (IOException exception) {
            log.log(Level.SEVERE, "Accept loop failed: " + exception.getMessage());
        }
    }

    private void listen(ServerSocket serverSocket) throws IOException {
        while (!serverSocket.isClosed()) {
            var socket = serverSocket.accept();
            var chatWorker = new ChatWorker(socket, chatWorkers);
            chatWorkers.add(chatWorker);
            log.log(Level.INFO, "New connection, {0} client(s) online", chatWorkers.size());
            // One virtual thread per connection: cheap enough that "a thread per client" scales to
            // hundreds of thousands of clients (Mod011 §3).
            threadBuilder.start(chatWorker);
        }
    }
}
