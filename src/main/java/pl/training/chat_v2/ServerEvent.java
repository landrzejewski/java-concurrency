package pl.training.chat_v2;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
class ServerEvent {

    private ServerEventType type;
    private String payload;
    private Worker source;

}
