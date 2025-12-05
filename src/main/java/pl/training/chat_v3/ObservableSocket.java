package pl.training.chat_v3;

import io.reactivex.Observable;

import java.net.ServerSocket;
import java.net.Socket;

public class ObservableSocket {

    static Observable<Socket> from(ServerSocket serverSocket) {
        return Observable.create(emitter -> {
            while (true) {
                Socket socket = serverSocket.accept();
                emitter.onNext(socket);
            }
        });
    }

}
