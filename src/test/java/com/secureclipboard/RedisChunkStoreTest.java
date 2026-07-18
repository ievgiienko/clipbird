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
    void chunksShouldHaveExpiration() {
        chunkStore.store("transfer-ttl", 0, new byte[]{1}, "iv", "hash");
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
    // Verifies that the stored chunk hash can be retrieved correctly.
    @Test
    void shouldStoreAndRetrieveHash() {
        chunkStore.store("transfer-hash", 0, new byte[]{1, 2}, "iv-test", "hash-test");

        assertEquals("hash-test", chunkStore.getHash("transfer-hash", 0));
    }

    // Verifies that requesting a hash for a missing chunk returns null.
    @Test
    void shouldReturnNullHashForMissingChunk() {
        assertNull(chunkStore.getHash("missing-transfer", 0));
    }

    // Verifies that deleting a chunk removes its data, IV and hash.
    @Test
    void shouldDeleteChunkHashTogetherWithChunk() {
        chunkStore.store("transfer-delete", 0, new byte[]{1}, "iv", "hash");

        chunkStore.delete("transfer-delete", 0);

        assertNull(chunkStore.getHash("transfer-delete", 0));
        assertNull(chunkStore.getIv("transfer-delete", 0));
        assertNull(chunkStore.getData("transfer-delete", 0));
    }

    // Verifies that deleting a chunk does not affect chunks from another transfer.
    @Test
    void shouldNotAffectOtherTransfersWhenDeletingChunk() {
        chunkStore.store("transfer-a", 0, new byte[]{1}, "iv-a", "hash-a");
        chunkStore.store("transfer-b", 0, new byte[]{2}, "iv-b", "hash-b");

        chunkStore.delete("transfer-a", 0);

        assertFalse(chunkStore.exists("transfer-a", 0));
        assertTrue(chunkStore.exists("transfer-b", 0));
    }

    // Verifies that storing the same chunk again overwrites the previous data.
    @Test
    void shouldOverwriteExistingChunk() {
        chunkStore.store("transfer-overwrite", 0, new byte[]{1}, "iv1", "hash1");
        chunkStore.store("transfer-overwrite", 0, new byte[]{2}, "iv2", "hash2");

        assertArrayEquals(new byte[]{2}, chunkStore.getData("transfer-overwrite", 0));
        assertEquals("iv2", chunkStore.getIv("transfer-overwrite", 0));
        assertEquals("hash2", chunkStore.getHash("transfer-overwrite", 0));
    }

    // Verifies that different chunk numbers store independent data.
    @Test
    void shouldKeepChunksIndependentWithinTransfer() {
        chunkStore.store("transfer-multi", 0, new byte[]{1}, "iv0", "hash0");
        chunkStore.store("transfer-multi", 1, new byte[]{2}, "iv1", "hash1");

        assertArrayEquals(new byte[]{1}, chunkStore.getData("transfer-multi", 0));
        assertArrayEquals(new byte[]{2}, chunkStore.getData("transfer-multi", 1));
    }

    // Verifies that deleting one chunk does not remove other chunks of the same transfer.
    @Test
    void shouldDeleteOnlySpecifiedChunk() {
        chunkStore.store("transfer-delete-one", 0, new byte[]{1}, "iv0", "hash0");
        chunkStore.store("transfer-delete-one", 1, new byte[]{2}, "iv1", "hash1");

        chunkStore.delete("transfer-delete-one", 0);

        assertFalse(chunkStore.exists("transfer-delete-one", 0));
        assertTrue(chunkStore.exists("transfer-delete-one", 1));
    }

    // Verifies that deleting all chunks for an unknown transfer does not throw an exception.
    @Test
    void shouldDeleteAllWithoutExistingChunks() {
        assertDoesNotThrow(() -> chunkStore.deleteAll("unknown-transfer"));
    }

    // Verifies that empty chunk data can be stored and retrieved.
    @Test
    void shouldSupportEmptyChunkData() {
        byte[] empty = new byte[0];

        chunkStore.store("transfer-empty", 0, empty, "iv", "hash");

        assertTrue(chunkStore.exists("transfer-empty", 0));
        assertArrayEquals(empty, chunkStore.getData("transfer-empty", 0));
    }

    // Verifies that chunks from different transfers remain isolated.
    @Test
    void shouldKeepChunksFromDifferentTransfersSeparated() {
        chunkStore.store("transfer-a", 0, new byte[]{1}, "iv-a", "hash-a");
        chunkStore.store("transfer-b", 0, new byte[]{2}, "iv-b", "hash-b");

        assertArrayEquals(new byte[]{1}, chunkStore.getData("transfer-a", 0));
        assertArrayEquals(new byte[]{2}, chunkStore.getData("transfer-b", 0));
    }

    // Verifies that deleteAll removes stored hashes and IVs together with chunk data.
    @Test
    void shouldDeleteHashAndIvWithDeleteAll() {
        chunkStore.store("transfer-all", 0, new byte[]{1}, "iv0", "hash0");
        chunkStore.store("transfer-all", 1, new byte[]{2}, "iv1", "hash1");

        chunkStore.deleteAll("transfer-all");

        assertNull(chunkStore.getHash("transfer-all", 0));
        assertNull(chunkStore.getHash("transfer-all", 1));
        assertNull(chunkStore.getIv("transfer-all", 0));
        assertNull(chunkStore.getIv("transfer-all", 1));
    }

    // Verifies that a deleted chunk is no longer reported as existing.
    @Test
    void shouldReturnFalseForDeletedChunk() {
        chunkStore.store("transfer-check", 0, new byte[]{1}, "iv", "hash");

        chunkStore.delete("transfer-check", 0);

        assertFalse(chunkStore.exists("transfer-check", 0));
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
