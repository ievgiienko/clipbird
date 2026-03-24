package com.secureclipboard.config;

import com.secureclipboard.websocket.ClipboardWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ClipboardWebSocketHandler clipboardWebSocketHandler;

    public WebSocketConfig(ClipboardWebSocketHandler clipboardWebSocketHandler) {
        this.clipboardWebSocketHandler = clipboardWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(clipboardWebSocketHandler, "/ws/clipboard")
                .setAllowedOrigins("*");
    }
}
