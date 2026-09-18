package pl.training.concurrency.exercises.extras.chat_v2;

record ServerEvent(ServerEventType type, String payload, Worker source) {
}
