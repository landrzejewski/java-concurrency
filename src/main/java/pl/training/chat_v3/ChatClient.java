package pl.training.chat_v3;

import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

import java.io.IOException;
import java.net.Socket;

public class ChatClient {

    /*

https://proandroiddev.com/understanding-rxjava-subscribeon-and-observeon-744b0c6a41ea

We can specify a thread to execute any operator by using subscribeOn and/or observeOn.

subscribeOn affects upstream operators (operators above the subscribeOn)

observeOn affects downstream operators (operators below the observeOn)

If only subscribeOn is specified, all operators will be be executed on that thread

If only observeOn is specified, all operators will be executed on the current thread and only operators
below the observeOn will be switched to thread specified by the observeOn

 */

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    private void start(String host, int port, String user) throws IOException {
        Runtime.getRuntime().addShutdownHook(new Thread(compositeDisposable::dispose));
        Socket socket = new Socket(host, port);
        Connection connection = new Connection(socket);

        compositeDisposable.add(ObservableReader.from(System.in)
                .subscribeOn(Schedulers.newThread())
                .observeOn(Schedulers.newThread())
                .map(message -> user + ": " + message)
                .subscribe(connection::send));

        compositeDisposable.add(ObservableReader.from(socket).subscribe(System.out::println));
    }

    public static void main(String[] args) throws IOException {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String name = args[2];
        new ChatClient().start(host, port, name);
    }

}
