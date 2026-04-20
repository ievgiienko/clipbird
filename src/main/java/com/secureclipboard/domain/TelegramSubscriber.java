package com.secureclipboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "telegram_subscribers")
public class TelegramSubscriber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false, unique = true)
    private Long chatId;

    @Column(name = "subscribed_at", nullable = false, updatable = false)
    private Instant subscribedAt;

    @PrePersist
    protected void onCreate() {
        if (subscribedAt == null) subscribedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChatId() { return chatId; }
    public void setChatId(Long chatId) { this.chatId = chatId; }

    public Instant getSubscribedAt() { return subscribedAt; }
    public void setSubscribedAt(Instant subscribedAt) { this.subscribedAt = subscribedAt; }
}
