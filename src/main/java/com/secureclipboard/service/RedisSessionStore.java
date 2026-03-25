package com.secureclipboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Stores device presence and session metadata in Redis.
 * WebSocket session objects remain in-memory (they are not serializable);
 * this store tracks presence state for potential horizontal scaling.
 *
 * Key structure:
 *   cb:presence:{clipboardCode}              → Hash { deviceId → lastSeen ISO instant }
 *   cb:ws:{sessionId}                        → Hash { "deviceId" → ..., "clipboardCode" → ... }
 */
@Service
public class RedisSessionStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);

    private static final String PRESENCE_PREFIX = "cb:presence:";
    private static final String WS_PREFIX = "cb:ws:";
    private static final Duration PRESENCE_TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, byte[]> redisTemplate;

    public RedisSessionStore(RedisTemplate<String, byte[]> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Register a device as online for a clipboard.
     */
    public void registerDevice(String clipboardCode, String deviceId, String sessionId) {
        String presenceKey = presenceKey(clipboardCode);
        String wsKey = wsKey(sessionId);

        redisTemplate.opsForHash().put(presenceKey, deviceId, Instant.now().toString());
        redisTemplate.expire(presenceKey, PRESENCE_TTL);

        redisTemplate.opsForHash().put(wsKey, "deviceId", deviceId);
        redisTemplate.opsForHash().put(wsKey, "clipboardCode", clipboardCode);
        redisTemplate.expire(wsKey, PRESENCE_TTL);

        log.debug("Registered device {} for clipboard {} (session {})", deviceId, clipboardCode, sessionId);
    }

    /**
     * Unregister a device when it disconnects.
     */
    public void unregisterDevice(String clipboardCode, String deviceId, String sessionId) {
        if (clipboardCode != null && deviceId != null) {
            String presenceKey = presenceKey(clipboardCode);
            redisTemplate.opsForHash().delete(presenceKey, deviceId);

            // Clean up empty presence keys
            Long size = redisTemplate.opsForHash().size(presenceKey);
            if (size != null && size == 0) {
                redisTemplate.delete(presenceKey);
            }
        }

        if (sessionId != null) {
            redisTemplate.delete(wsKey(sessionId));
        }

        log.debug("Unregistered device {} from clipboard {} (session {})", deviceId, clipboardCode, sessionId);
    }

    /**
     * Get all online device IDs for a clipboard.
     */
    public Set<Object> getDeviceIds(String clipboardCode) {
        return redisTemplate.opsForHash().keys(presenceKey(clipboardCode));
    }

    /**
     * Get the count of connected devices for a clipboard.
     */
    public int getConnectedCount(String clipboardCode) {
        Long size = redisTemplate.opsForHash().size(presenceKey(clipboardCode));
        return size != null ? size.intValue() : 0;
    }

    /**
     * Check if a device is registered as online.
     */
    public boolean isDeviceRegistered(String clipboardCode, String deviceId) {
        return redisTemplate.opsForHash().hasKey(presenceKey(clipboardCode), deviceId);
    }

    /**
     * Get session metadata by WebSocket session ID.
     */
    public Map<Object, Object> getSessionMeta(String sessionId) {
        return redisTemplate.opsForHash().entries(wsKey(sessionId));
    }

    /**
     * Remove all presence data for a clipboard (used on clipboard expiry/deletion).
     */
    public void removeClipboard(String clipboardCode) {
        redisTemplate.delete(presenceKey(clipboardCode));
        log.debug("Removed presence data for clipboard {}", clipboardCode);
    }

    /**
     * Refresh TTL on presence key (call periodically to keep active clipboards alive).
     */
    public void refreshPresence(String clipboardCode) {
        redisTemplate.expire(presenceKey(clipboardCode), PRESENCE_TTL);
    }

    private String presenceKey(String clipboardCode) {
        return PRESENCE_PREFIX + clipboardCode;
    }

    private String wsKey(String sessionId) {
        return WS_PREFIX + sessionId;
    }
}
