package pl.training.concurrency.exercises.extras.chat_v2;

import pl.training.concurrency.extras.chat_v2.commons.Sockets;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static pl.training.concurrency.extras.chat_v2.ServerEventType.CONNECTION_ACCEPTED;
import static pl.training.concurrency.extras.chat_v2.ServerEventType.SERVER_STARTED;

public class ChatServer {

    private static final int DEFAULT_PORT = 8888;

    private final ServerWorkers serverWorkers;
    private final EventsBus eventsBus;
    private final ExecutorService executorService;

    public ChatServer(ServerWorkers serverWorkers, EventsBus eventsBus, ExecutorService executorService) {
        this.serverWorkers = serverWorkers;
        this.eventsBus = eventsBus;
        this.executorService = executorService;
    }

    private void start(int port) throws IOException {
        eventsBus.addConsumer(new ServerEventsProcessor(serverWorkers));
        try (var serverSocket = new ServerSocket(port)) {
            eventsBus.publish(new ServerEvent(SERVER_STARTED, null, null));
            while (true) {
                var socket = serverSocket.accept();
                eventsBus.publish(new ServerEvent(CONNECTION_ACCEPTED, null, null));
                createWorker(socket);
            }
        }
    }

    private void createWorker(Socket socket) {
        var worker = new Worker(socket, eventsBus);
        serverWorkers.add(worker);
        executorService.execute(worker);
    }

    public static void main(String[] args) throws IOException {
        var port = args.length > 0 ? Sockets.parsePort(args[0], DEFAULT_PORT) : DEFAULT_PORT;
        var eventsBus = new EventsBus();
        eventsBus.addConsumer(new ServerEventsLogger());
        eventsBus.addConsumer(new MessagesHistoryLogger());
        var serviceWorkers = new SynchronizedServiceWorkers(new HashSetServerWorkers());
        // Was newFixedThreadPool(1024): a hard ceiling on concurrent clients, chosen by guesswork,
        // with every idle connection holding a 1 MB platform-thread stack. One virtual thread per
        // connection removes both problems and keeps the blocking code unchanged (Mod011 §3).
        var server = new ChatServer(serviceWorkers, eventsBus, newVirtualThreadPerTaskExecutor());
        server.start(port);
    }

}
