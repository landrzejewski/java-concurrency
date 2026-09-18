package pl.training.concurrency.exercises.extras.chat_v2;

import pl.training.concurrency.extras.chat_v2.commons.Sockets;
import pl.training.concurrency.extras.chat_v2.commons.TextReader;
import pl.training.concurrency.extras.chat_v2.commons.TextWriter;

import java.net.Socket;

import static pl.training.concurrency.extras.chat_v2.ServerEventType.CONNECTION_CLOSED;
import static pl.training.concurrency.extras.chat_v2.ServerEventType.MESSAGE_RECEIVED;

class Worker implements Runnable {

    private final Socket socket;
    private final EventsBus eventsBus;
    private final TextWriter writer;

    Worker(Socket socket, EventsBus eventsBus) {
        this.socket = socket;
        this.eventsBus = eventsBus;
        writer = new TextWriter(socket);
    }

    @Override
    public void run() {
        new TextReader(socket, this::onText, this::onInputClose).read();
    }

    private void onText(String text) {
        eventsBus.publish(new ServerEvent(MESSAGE_RECEIVED, text, this));
    }

    private void onInputClose() {
        eventsBus.publish(new ServerEvent(CONNECTION_CLOSED, null, this));
        // Nothing downstream owns this socket: the events processor only removes the worker from the
        // registry. Without this the file descriptor leaks for the lifetime of the server.
        Sockets.close(socket);
    }

    void send(String text) {
        writer.write(text);
    }

}
