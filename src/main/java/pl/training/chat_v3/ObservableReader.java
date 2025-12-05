package pl.training.chat_v3;

import io.reactivex.Observable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;

public class ObservableReader {

    static Observable<String> from(InputStream inputStream) {
        return Observable.create(emitter -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String message;
                while ((message = reader.readLine()) != null) {
                    emitter.onNext(message);
                }
            } catch (RuntimeException ex) {
                emitter.onError(ex);
            }
            emitter.onComplete();
        });
    }

    static Observable<String> from(Socket socket) throws IOException {
        return from(socket.getInputStream());
    }

}
