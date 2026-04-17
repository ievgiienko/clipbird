package com.secureclipboard.service;

import com.secureclipboard.config.AppProperties;
import com.secureclipboard.domain.TelegramSubscriber;
import com.secureclipboard.repository.TelegramSubscriberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.starter.SpringWebhookBot;

@Component
@ConditionalOnProperty(name = "app.telegram.enabled", havingValue = "true")
public class ClipbirdTelegramBot extends SpringWebhookBot {

    private static final Logger log = LoggerFactory.getLogger(ClipbirdTelegramBot.class);

    private final AppProperties appProps;
    private final TelegramSubscriberRepository subscriberRepo;

    @SuppressWarnings("deprecation")
    public ClipbirdTelegramBot(SetWebhook setWebhook,
                                AppProperties appProps,
                                TelegramSubscriberRepository subscriberRepo) {
        super(setWebhook);
        this.appProps = appProps;
        this.subscriberRepo = subscriberRepo;
    }

    @Override
    public BotApiMethod<?> onWebhookUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return null;

        long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText().trim();

        if (text.startsWith("/subscribe")) {
            handleSubscribe(chatId);
        }

        return null;
    }

    private void handleSubscribe(long chatId) {
        if (subscriberRepo.existsByChatId(chatId)) {
            sendMessage(chatId, "You are already subscribed to daily reports.");
            return;
        }

        TelegramSubscriber subscriber = new TelegramSubscriber();
        subscriber.setChatId(chatId);
        subscriberRepo.save(subscriber);

        log.info("New Telegram subscriber: chatId={}", chatId);
        sendMessage(chatId, "Subscribed! You will receive daily statistics reports.");
    }

    public void sendMessage(long chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chatId={}: {}", chatId, e.getMessage());
        }
    }

    @Override
    public String getBotPath() {
        return appProps.getTelegram().getWebhookPath();
    }

    @Override
    public String getBotUsername() {
        return appProps.getTelegram().getBotUsername();
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getBotToken() {
        return appProps.getTelegram().getBotToken();
    }
}
