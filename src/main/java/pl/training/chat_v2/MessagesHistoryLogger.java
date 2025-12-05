package pl.training.chat_v2;

import lombok.extern.java.Log;

import java.util.function.Consumer;

import static pl.training.chat_v2.ServerEventType.MESSAGE_RECEIVED;

@Log
class MessagesHistoryLogger implements Consumer<ServerEvent> {

    @Override
    public void accept(ServerEvent event) {
        if (event.getType().equals(MESSAGE_RECEIVED)) {
            log.info("New message: " + event.getPayload());
        }
    }

}
