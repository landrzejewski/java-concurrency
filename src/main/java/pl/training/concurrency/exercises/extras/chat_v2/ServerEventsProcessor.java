package pl.training.concurrency.exercises.extras.chat_v2;

import java.util.function.Consumer;

public class ServerEventsProcessor implements Consumer<ServerEvent> {

    private final ServerWorkers serverWorkers;

    public ServerEventsProcessor(ServerWorkers serverWorkers) {
        this.serverWorkers = serverWorkers;
    }

    @Override
    public void accept(ServerEvent event) {
        switch (event.type()) {
            case MESSAGE_RECEIVED -> serverWorkers.broadcast(event.payload());
            case CONNECTION_CLOSED -> serverWorkers.remove(event.source());
        }
    }

}
