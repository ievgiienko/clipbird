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
    // Verify the configured chunk size range is valid.
    @Test
    void chunkSizeRangeShouldBeValid() {
        int min = appProps.getClipboard().getMinChunkSizeBytes();
        int max = appProps.getClipboard().getMaxChunkSizeBytes();

        assertTrue(min < max);
    }

    // Verify all configured chunk sizes are positive values.
    @Test
    void chunkSizesShouldBePositive() {
        assertTrue(appProps.getClipboard().getChunkSizeBytes() > 0);
        assertTrue(appProps.getClipboard().getMinChunkSizeBytes() > 0);
        assertTrue(appProps.getClipboard().getMaxChunkSizeBytes() > 0);
    }

    // Verify all configured chunk sizes are aligned to whole kilobytes.
    @Test
    void chunkSizesShouldBeKilobyteAligned() {
        assertEquals(0, appProps.getClipboard().getChunkSizeBytes() % 1024);
        assertEquals(0, appProps.getClipboard().getMinChunkSizeBytes() % 1024);
        assertEquals(0, appProps.getClipboard().getMaxChunkSizeBytes() % 1024);
    }

    // Verify max chunk size is greater than the default size.
    @Test
    void maxChunkSizeShouldBeGreaterThanDefault() {
        assertTrue(
                appProps.getClipboard().getMaxChunkSizeBytes() >
                        appProps.getClipboard().getChunkSizeBytes()
        );
    }

    // Verify min chunk size is less than the default size.
    @Test
    void minChunkSizeShouldBeLessThanDefault() {
        assertTrue(
                appProps.getClipboard().getMinChunkSizeBytes() <
                        appProps.getClipboard().getChunkSizeBytes()
        );
    }

    // Verify the configured chunk size range has a positive width.
    @Test
    void chunkSizeDifferenceShouldBePositive() {
        int difference = appProps.getClipboard().getMaxChunkSizeBytes()
                - appProps.getClipboard().getMinChunkSizeBytes();

        assertTrue(difference > 0);
    }

    // Verify chunk sizes are ordered as min < default < max.
    @Test
    void chunkSizesShouldBeOrderedCorrectly() {
        int min = appProps.getClipboard().getMinChunkSizeBytes();
        int def = appProps.getClipboard().getChunkSizeBytes();
        int max = appProps.getClipboard().getMaxChunkSizeBytes();

        assertAll(
                () -> assertTrue(min < def),
                () -> assertTrue(def < max)
        );
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
