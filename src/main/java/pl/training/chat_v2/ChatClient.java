package pl.training.chat_v2;

import lombok.extern.java.Log;
import pl.training.chat_v2.commons.Sockets;
import pl.training.chat_v2.commons.TextReader;
import pl.training.chat_v2.commons.TextWriter;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

@Log
public class ChatClient {

    private static final int DEFAULT_PORT = 8888;

    private final Runnable readFromSocket;
    private final Runnable readFromConsole;

    public ChatClient(String host, int port, String name) throws IOException {
        var socket = new Socket(host, port);
        readFromSocket = () -> new TextReader(socket, log::info, () -> Sockets.close(socket)).read();
        readFromConsole = () -> new TextReader(System.in, text -> new TextWriter(socket).write(name + ": " + text)).read();
    }

    private void start() {
        new Thread(readFromSocket).start();
        var consoleReader = new Thread(readFromConsole);
        consoleReader.setDaemon(true);
        consoleReader.start();
    }

    public static void main(String[] args) throws IOException {
        var port = Sockets.parsePort(args[1], DEFAULT_PORT);
        new ChatClient(args[0], port, UUID.randomUUID().toString()).start();
    }

}
