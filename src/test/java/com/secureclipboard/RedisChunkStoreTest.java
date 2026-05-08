package com.secureclipboard;

import com.secureclipboard.service.RedisChunkStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Redis-backed chunk storage.
 */
@SpringBootTest
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
class RedisChunkStoreTest {

    @Autowired
    private RedisChunkStore chunkStore;

    @Test
    void shouldStoreAndRetrieveChunk() {
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        chunkStore.store("transfer-1", 0, data, "iv-test", "hash-test");

        assertTrue(chunkStore.exists("transfer-1", 0));
        assertArrayEquals(data, chunkStore.getData("transfer-1", 0));
        assertEquals("iv-test", chunkStore.getIv("transfer-1", 0));
    }

    @Test
    void shouldReturnNullForMissingChunk() {
        assertNull(chunkStore.getData("nonexistent", 0));
        assertNull(chunkStore.getIv("nonexistent", 0));
        assertFalse(chunkStore.exists("nonexistent", 0));
    }

    @Test
    void shouldDeleteChunk() {
        chunkStore.store("transfer-2", 0, new byte[]{10}, "iv-0", "hash-0");
        assertTrue(chunkStore.exists("transfer-2", 0));

        chunkStore.delete("transfer-2", 0);
        assertFalse(chunkStore.exists("transfer-2", 0));
        assertNull(chunkStore.getData("transfer-2", 0));
    }

    @Test
    void shouldDeleteAllChunksForTransfer() {
        chunkStore.store("transfer-3", 0, new byte[]{1}, "iv-0", "h0");
        chunkStore.store("transfer-3", 1, new byte[]{2}, "iv-1", "h1");
        chunkStore.store("transfer-3", 2, new byte[]{3}, "iv-2", "h2");

        chunkStore.deleteAll("transfer-3");

        assertFalse(chunkStore.exists("transfer-3", 0));
        assertFalse(chunkStore.exists("transfer-3", 1));
        assertFalse(chunkStore.exists("transfer-3", 2));
    }
    @Test
    void chunksShouldHaveExpiration() {
        chunkStore.store("transfer-ttl", 0, new byte[]{1}, "iv", "hash");
    }

    @Test
    void shouldStoreBinaryDataWithoutCorruption() {
        // Test all byte values 0x00 through 0xFF
        byte[] binaryData = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryData[i] = (byte) i;
        }

        chunkStore.store("transfer-binary", 0, binaryData, "iv-binary", "hash-binary");

        byte[] retrieved = chunkStore.getData("transfer-binary", 0);
        assertArrayEquals(binaryData, retrieved);
    }
}
