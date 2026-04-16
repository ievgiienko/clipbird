package com.secureclipboard.controller;

import com.secureclipboard.config.AppProperties;
import com.secureclipboard.domain.TelegramSubscriber;
import com.secureclipboard.dto.TelegramUpdate;
import com.secureclipboard.repository.TelegramSubscriberRepository;
import com.secureclipboard.service.TelegramBotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/telegram")
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    private final AppProperties appProps;
    private final TelegramSubscriberRepository subscriberRepo;
    private final TelegramBotService botService;

    public TelegramWebhookController(AppProperties appProps,
                                     TelegramSubscriberRepository subscriberRepo,
                                     TelegramBotService botService) {
        this.appProps = appProps;
        this.subscriberRepo = subscriberRepo;
        this.botService = botService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleUpdate(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret,
            @RequestBody TelegramUpdate update) {

        String expectedSecret = appProps.getTelegram().getWebhookSecret();
        if (!expectedSecret.isBlank() && !expectedSecret.equals(secret)) {
            log.warn("Telegram webhook received with invalid secret token");
            return ResponseEntity.status(403).build();
        }

        if (update.message() == null || update.message().text() == null) {
            return ResponseEntity.ok().build();
        }

        long chatId = update.message().chat().id();
        String text = update.message().text().trim();

        if ("/subscribe".equals(text) || text.startsWith("/subscribe@")) {
            handleSubscribe(chatId);
        }

        return ResponseEntity.ok().build();
    }

    private void handleSubscribe(long chatId) {
        if (subscriberRepo.existsByChatId(chatId)) {
            botService.sendMessage(chatId, "You are already subscribed to daily reports.");
            return;
        }

        TelegramSubscriber subscriber = new TelegramSubscriber();
        subscriber.setChatId(chatId);
        subscriberRepo.save(subscriber);

        log.info("New Telegram subscriber: chatId={}", chatId);
        botService.sendMessage(chatId, "Subscribed! You will receive daily statistics reports.");
    }
}
