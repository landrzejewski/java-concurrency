package pl.training.chat_v2;

import lombok.RequiredArgsConstructor;
import pl.training.chat_v2.commons.Sockets;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static pl.training.chat_v2.ServerEventType.CONNECTION_ACCEPTED;
import static pl.training.chat_v2.ServerEventType.SERVER_STARTED;

@RequiredArgsConstructor
public class ChatServer {

    private static final int DEFAULT_PORT = 8888;
    private static final int THREADS_COUNT = 1024;

    private final ServerWorkers serverWorkers;
    private final EventsBus eventsBus;
    private final ExecutorService executorService;

    private void start(int port) throws IOException {
        eventsBus.addConsumer(new ServerEventsProcessor(serverWorkers));
        try (var serverSocket = new ServerSocket(port)) {
            eventsBus.publish(ServerEvent.builder().type(SERVER_STARTED).build());
            while (true) {
                var socket = serverSocket.accept();
                eventsBus.publish(ServerEvent.builder().type(CONNECTION_ACCEPTED).build());
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
        var port = Sockets.parsePort(args[0], DEFAULT_PORT);
        var eventsBus = new EventsBus();
        eventsBus.addConsumer(new ServerEventsLogger());
        eventsBus.addConsumer(new MessagesHistoryLogger());
        var serviceWorkers = new SynchronizedServiceWorkers(new HashSetServerWorkers());
        var server = new ChatServer(serviceWorkers, eventsBus, newFixedThreadPool(THREADS_COUNT));
        server.start(port);
    }

}
