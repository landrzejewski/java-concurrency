package pl.training.chat_v2;

import lombok.extern.java.Log;

import java.util.function.Consumer;

@Log
class ServerEventsLogger implements Consumer<ServerEvent> {

    @Override
    public void accept(ServerEvent event) {
        switch (event.getType()) {
            case SERVER_STARTED -> log.info("Server started.");
            case CONNECTION_ACCEPTED -> log.info("New connection accepted.");
            case CONNECTION_CLOSED -> log.info("Connection form client closed.");
        }
    }

}
