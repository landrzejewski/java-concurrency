package pl.training.concurrency.exercises.extras.chat_v2;

import java.util.function.Consumer;
import java.util.logging.Logger;

import static pl.training.concurrency.extras.chat_v2.ServerEventType.MESSAGE_RECEIVED;

class MessagesHistoryLogger implements Consumer<ServerEvent> {

    private static final Logger log = Logger.getLogger(MessagesHistoryLogger.class.getName());

    @Override
    public void accept(ServerEvent event) {
        if (event.type().equals(MESSAGE_RECEIVED)) {
            log.info("New message: " + event.payload());
        }
    }

}
