package pl.training.concurrency.exercises.extras.chat_v2;

import java.util.function.Consumer;
import java.util.logging.Logger;

class ServerEventsLogger implements Consumer<ServerEvent> {

    private static final Logger log = Logger.getLogger(ServerEventsLogger.class.getName());

    @Override
    public void accept(ServerEvent event) {
        switch (event.type()) {
            case SERVER_STARTED -> log.info("Server started.");
            case CONNECTION_ACCEPTED -> log.info("New connection accepted.");
            case CONNECTION_CLOSED -> log.info("Connection from client closed.");
            case MESSAGE_RECEIVED -> { } // handled by MessagesHistoryLogger
        }
    }

}
