package com.secureclipboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secureclipboard.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@Service
public class TelegramBotService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);
    private static final String API_BASE = "https://api.telegram.org/bot";

    private final AppProperties appProps;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    public TelegramBotService(AppProperties appProps, ObjectMapper objectMapper) {
        this.appProps = appProps;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWebhook() {
        AppProperties.TelegramProps cfg = appProps.getTelegram();
        if (!cfg.isEnabled()) return;

        try {
            Map<String, Object> body = cfg.getWebhookSecret().isBlank()
                    ? Map.of("url", cfg.getWebhookUrl())
                    : Map.of("url", cfg.getWebhookUrl(), "secret_token", cfg.getWebhookSecret());

            post("setWebhook", body);
            log.info("Telegram webhook registered: {}", cfg.getWebhookUrl());
        } catch (Exception e) {
            log.error("Failed to register Telegram webhook", e);
        }
    }

    public void sendMessage(long chatId, String text) {
        if (!appProps.getTelegram().isEnabled()) return;
        try {
            post("sendMessage", Map.of(
                    "chat_id", chatId,
                    "text", text,
                    "parse_mode", "HTML"
            ));
        } catch (Exception e) {
            log.error("Failed to send Telegram message to chatId={}: {}", chatId, e.getMessage());
        }
    }

    private void post(String method, Object body) throws Exception {
        String token = appProps.getTelegram().getBotToken();
        String url = API_BASE + token + "/" + method;
        String json = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("Telegram API [{}] returned HTTP {}: {}", method, response.statusCode(), response.body());
        }
    }
}
