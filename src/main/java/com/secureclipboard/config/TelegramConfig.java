package com.secureclipboard.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.updatesreceivers.DefaultWebhook;

@Configuration
@ConditionalOnProperty(name = "app.telegram.enabled", havingValue = "true")
public class TelegramConfig {

    @Bean
    public DefaultWebhook defaultWebhook(AppProperties appProps) {
        DefaultWebhook webhook = new DefaultWebhook();
        webhook.setInternalUrl(appProps.getTelegram().getWebhookUrl());
        return webhook;
    }

    @Bean
    public SetWebhook setWebhook(AppProperties appProps) {
        return SetWebhook.builder()
                .url(appProps.getTelegram().getWebhookUrl())
                .build();
    }
}
