package pl.training.chat_v1;

import java.io.IOException;
import java.net.Socket;
import java.util.function.Consumer;

public class ChatClient {

    private final Consumer<String> onText;
    private final Runnable readFromSocket;
    private final Runnable readFromConsole;

    public ChatClient(String host, int port, String name) throws IOException {
        Socket socket = new Socket(host, port);
        onText = text -> new MessageWriter(socket).write(name + ": " + text);
        readFromSocket = () -> new MessageReader(socket, System.out::println, () -> {}).read();
        readFromConsole = () -> new MessageReader(System.in, onText).read();
    }

    public static void main(String[] args) throws IOException {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String name = args[2];
        new ChatClient(host, port, name).start();
    }

    private void start() {
        new Thread(readFromSocket).start();
        Thread consoleMessageReader = new Thread(readFromConsole);
        consoleMessageReader.setDaemon(true);
        consoleMessageReader.start();
    }

}
