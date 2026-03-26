package com.secureclipboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Stores temporary encrypted file chunks in Redis.
 * Each chunk is stored as raw bytes with a TTL for automatic cleanup.
 *
 * Key structure:
 *   cb:chunk:{transferCode}:{chunkNo}      → byte[] (encrypted chunk data)
 *   cb:chunk:{transferCode}:{chunkNo}:iv   → String (IV for decryption)
 */
@Service
public class RedisChunkStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChunkStore.class);

    private static final String KEY_PREFIX = "cb:chunk:";
    private static final Duration CHUNK_TTL = Duration.ofMinutes(1);

    private final RedisTemplate<String, byte[]> redisTemplate;

    public RedisChunkStore(RedisTemplate<String, byte[]> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Store an encrypted chunk with its IV and plaintext hash. All keys expire after CHUNK_TTL.
     */
    public void store(String transferCode, int chunkNo, byte[] data, String iv, String hash) {
        String dataKey = dataKey(transferCode, chunkNo);
        String ivKey = ivKey(transferCode, chunkNo);

        redisTemplate.opsForValue().set(dataKey, data, CHUNK_TTL);
        redisTemplate.opsForValue().set(ivKey, iv.getBytes(), CHUNK_TTL);

        if (hash != null) {
            redisTemplate.opsForValue().set(hashKey(transferCode, chunkNo), hash.getBytes(), CHUNK_TTL);
        }

        log.debug("Stored chunk {} for transfer {} ({} bytes)", chunkNo, transferCode, data.length);
    }

    /**
     * Retrieve chunk data. Returns null if not present or expired.
     */
    public byte[] getData(String transferCode, int chunkNo) {
        return redisTemplate.opsForValue().get(dataKey(transferCode, chunkNo));
    }

    /**
     * Retrieve chunk IV. Returns null if not present or expired.
     */
    public String getIv(String transferCode, int chunkNo) {
        byte[] ivBytes = redisTemplate.opsForValue().get(ivKey(transferCode, chunkNo));
        return ivBytes != null ? new String(ivBytes) : null;
    }

    /**
     * Retrieve plaintext chunk hash. Returns null if not present or expired.
     */
    public String getHash(String transferCode, int chunkNo) {
        byte[] hashBytes = redisTemplate.opsForValue().get(hashKey(transferCode, chunkNo));
        return hashBytes != null ? new String(hashBytes) : null;
    }

    /**
     * Check if a chunk exists in the store.
     */
    public boolean exists(String transferCode, int chunkNo) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(dataKey(transferCode, chunkNo)));
    }

    /**
     * Delete a specific chunk, its IV, and its hash.
     */
    public void delete(String transferCode, int chunkNo) {
        redisTemplate.delete(dataKey(transferCode, chunkNo));
        redisTemplate.delete(ivKey(transferCode, chunkNo));
        redisTemplate.delete(hashKey(transferCode, chunkNo));
        log.debug("Deleted chunk {} for transfer {}", chunkNo, transferCode);
    }

    /**
     * Delete all chunks for a transfer (used on cancel/cleanup).
     * Scans for all keys matching the transfer prefix and deletes them.
     */
    public void deleteAll(String transferCode) {
        String pattern = KEY_PREFIX + transferCode + ":*";
        var keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.debug("Deleted {} keys for transfer {}", keys.size(), transferCode);
        }
    }

    private String dataKey(String transferCode, int chunkNo) {
        return KEY_PREFIX + transferCode + ":" + chunkNo;
    }

    private String ivKey(String transferCode, int chunkNo) {
        return KEY_PREFIX + transferCode + ":" + chunkNo + ":iv";
    }

    private String hashKey(String transferCode, int chunkNo) {
        return KEY_PREFIX + transferCode + ":" + chunkNo + ":hash";
    }
}
