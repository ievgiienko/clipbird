CREATE TABLE telegram_subscribers (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    chat_id      BIGINT NOT NULL,
    subscribed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_chat_id (chat_id)
);
