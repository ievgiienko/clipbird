package com.secureclipboard;

import com.secureclipboard.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies adaptive chunk size configuration bounds.
 */
@SpringBootTest
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
class ChunkSizeConfigTest {

    @Autowired
    private AppProperties appProps;

    @Test
    void chunkSizeDefaultShouldBe1MB() {
        assertEquals(1_048_576, appProps.getClipboard().getChunkSizeBytes());
    }

    @Test
    void minChunkSizeShouldBe256KB() {
        assertEquals(262_144, appProps.getClipboard().getMinChunkSizeBytes());
    }

    @Test
    void maxChunkSizeShouldBe5MB() {
        assertEquals(5_242_880, appProps.getClipboard().getMaxChunkSizeBytes());
    }

    @Test
    void maxShouldNotExceed5MB() {
        assertTrue(appProps.getClipboard().getMaxChunkSizeBytes() <= 5_242_880,
            "Max chunk size must not exceed 5 MB");
    }

    @Test
    void minShouldNotBeBelowMinimum() {
        assertTrue(appProps.getClipboard().getMinChunkSizeBytes() >= 262_144,
            "Min chunk size must be at least 256 KB");
    }

    @Test
    void defaultShouldBeWithinBounds() {
        int defaultSize = appProps.getClipboard().getChunkSizeBytes();
        int min = appProps.getClipboard().getMinChunkSizeBytes();
        int max = appProps.getClipboard().getMaxChunkSizeBytes();
        assertTrue(defaultSize >= min && defaultSize <= max,
            "Default chunk size must be within [min, max] range");
    }
}
