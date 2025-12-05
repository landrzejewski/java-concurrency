package pl.training.chat_v3;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import lombok.extern.java.Log;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;

@Log
public class ChatServer {

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private final Connections connections = new Connections();

    public static void main(String[] args) {
        int port = Integer.parseInt(args[0]);
        new ChatServer().start(port);
    }

    private void start(int port) {
        Runtime.getRuntime().addShutdownHook(new Thread(compositeDisposable::dispose));
        try {
            log.log(Level.SEVERE, "Server is listening on port: " + port);
            compositeDisposable.add(ObservableSocket.from(new ServerSocket(port)).subscribe(this::onNextSocket));
        } catch (IOException e) {
            log.log(Level.SEVERE, "Server failed to start: " + e.getMessage());
        }
    }

    private void onNextSocket(Socket socket) throws IOException {
        connections.add(new Connection(socket));
        compositeDisposable.add(createTextStream(socket).subscribe(connections::broadcast));
    }

    private Observable<String> createTextStream(Socket socket) throws IOException {
        return ObservableReader.from(socket)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.newThread());
    }

}
