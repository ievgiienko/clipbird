package com.secureclipboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages WebSocket sessions grouped by clipboard code.
 * In-memory for MVP; can be replaced with Redis for horizontal scaling.
 */
@Component
public class DeviceSessionManager {

    private static final Logger log = LoggerFactory.getLogger(DeviceSessionManager.class);

    // clipboardCode -> { deviceId -> session }
    private final Map<String, Map<String, WebSocketSession>> rooms = new ConcurrentHashMap<>();

    // sessionId -> deviceId
    private final Map<String, String> sessionToDevice = new ConcurrentHashMap<>();

    // sessionId -> clipboardCode
    private final Map<String, String> sessionToClipboard = new ConcurrentHashMap<>();

    public void join(String clipboardCode, String deviceId, WebSocketSession session) {
        rooms.computeIfAbsent(clipboardCode, k -> new ConcurrentHashMap<>())
             .put(deviceId, session);
        sessionToDevice.put(session.getId(), deviceId);
        sessionToClipboard.put(session.getId(), clipboardCode);
        log.info("Device {} joined clipboard {}", deviceId, clipboardCode);
    }

    public void leave(WebSocketSession session) {
        String deviceId = sessionToDevice.remove(session.getId());
        String clipboardCode = sessionToClipboard.remove(session.getId());
        if (clipboardCode != null && deviceId != null) {
            Map<String, WebSocketSession> room = rooms.get(clipboardCode);
            if (room != null) {
                room.remove(deviceId);
                if (room.isEmpty()) {
                    rooms.remove(clipboardCode);
                }
            }
            log.info("Device {} left clipboard {}", deviceId, clipboardCode);
        }
    }

    public Map<String, WebSocketSession> getRoom(String clipboardCode) {
        return rooms.getOrDefault(clipboardCode, Collections.emptyMap());
    }

    public int getConnectedCount(String clipboardCode) {
        Map<String, WebSocketSession> room = rooms.get(clipboardCode);
        return room != null ? room.size() : 0;
    }

    public boolean isDeviceOnline(String clipboardCode, String deviceId) {
        Map<String, WebSocketSession> room = rooms.get(clipboardCode);
        if (room == null) return false;
        WebSocketSession session = room.get(deviceId);
        return session != null && session.isOpen();
    }

    public WebSocketSession getSession(String clipboardCode, String deviceId) {
        Map<String, WebSocketSession> room = rooms.get(clipboardCode);
        return room != null ? room.get(deviceId) : null;
    }

    public String getDeviceId(WebSocketSession session) {
        return sessionToDevice.get(session.getId());
    }

    public String getClipboardCode(WebSocketSession session) {
        return sessionToClipboard.get(session.getId());
    }

    public Set<String> getDeviceIds(String clipboardCode) {
        Map<String, WebSocketSession> room = rooms.get(clipboardCode);
        return room != null ? room.keySet() : Collections.emptySet();
    }

    public void removeRoom(String clipboardCode) {
        Map<String, WebSocketSession> room = rooms.remove(clipboardCode);
        if (room != null) {
            room.forEach((deviceId, session) -> {
                sessionToDevice.remove(session.getId());
                sessionToClipboard.remove(session.getId());
            });
        }
    }
}
