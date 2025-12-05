package pl.training.chat_v1;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChatServer {

    private final Logger logger = Logger.getLogger(getClass().getName());
    private final ChatWorkers chatWorkers = new ChatWorkers();

    public static void main(String[] args) {
        int port = Integer.parseInt(args[0]);
        new ChatServer().start(port);
    }

    private void start(int port) {
        try {
            ServerSocket serverSocket = new ServerSocket(port);
            listen(serverSocket, port);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Server failed to start: " + e.getMessage());
        }
    }

    private void listen(ServerSocket serverSocket, int port) throws IOException {
        logger.log(Level.INFO, "Server is listening on port: " + port);
        while (true) {
            Socket socket = serverSocket.accept();
            logger.log(Level.INFO, "New connection established...");
            ChatWorker chatWorker = new ChatWorker(socket, chatWorkers);
            chatWorkers.add(chatWorker);
            new Thread(chatWorker).start();
        }
    }

}
