package pl.training.chat_v2;

import lombok.RequiredArgsConstructor;

import java.util.function.Consumer;

@RequiredArgsConstructor
public class ServerEventsProcessor implements Consumer<ServerEvent> {

    private final ServerWorkers serverWorkers;

    @Override
    public void accept(ServerEvent event) {
        switch (event.getType()) {
            case MESSAGE_RECEIVED -> serverWorkers.broadcast(event.getPayload());
            case CONNECTION_CLOSED -> serverWorkers.remove(event.getSource());
        }
    }

}
