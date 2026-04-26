package com.secureclipboard;

import com.secureclipboard.config.AppProperties;
import com.secureclipboard.domain.*;
import com.secureclipboard.dto.ClipboardCreatedResponse;
import com.secureclipboard.repository.*;
import com.secureclipboard.service.CleanupService;
import com.secureclipboard.service.ClipboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
class CleanupServiceTest {

    @Autowired
    private CleanupService cleanupService;

    @Autowired
    private ClipboardService clipboardService;

    @Autowired
    private ClipboardRepository clipboardRepository;

    @Autowired
    private ClipboardTextSnapshotRepository textRepository;

    @Autowired
    private ClipboardFileRepository fileRepository;

    @Autowired
    private ChunkTransferRepository transferRepository;

    @Autowired
    private AppProperties appProps;

    @Test
    void deletedClipboardNotImmediatelyHardDeleted() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");
        clipboardService.deleteClipboard(code);

        // Clipboard row still exists with DELETED status
        Optional<Clipboard> found = clipboardRepository.findByClipboardCode(code);
        assertTrue(found.isPresent());
        assertEquals(ClipboardStatus.DELETED, found.get().getStatus());

        // Running purge should NOT delete it — expiresAt is still in the future
        cleanupService.purgeDeletedClipboards();

        found = clipboardRepository.findByClipboardCode(code);
        assertTrue(found.isPresent(), "Clipboard should still exist — not yet past retention period");
    }

    @Test
    void deletedClipboardHardDeletedAfterRetentionPeriod() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");
        clipboardService.deleteClipboard(code);

        // Simulate that expiresAt is far in the past (beyond retention)
        Clipboard clipboard = clipboardRepository.findByClipboardCode(code).orElseThrow();
        int delay = appProps.getCleanup().getHardDeleteDelayHours();
        clipboard.setExpiresAt(Instant.now().minus(delay + 1, ChronoUnit.HOURS));
        clipboardRepository.saveAndFlush(clipboard);

        cleanupService.purgeDeletedClipboards();

        Optional<Clipboard> found = clipboardRepository.findByClipboardCode(code);
        assertTrue(found.isEmpty(), "Clipboard should be hard-deleted after retention period");
    }

    @Test
    void expiredClipboardHardDeletedAfterRetentionPeriod() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");

        // Set clipboard to expired with expiresAt far in the past
        Clipboard clipboard = clipboardRepository.findByClipboardCode(code).orElseThrow();
        int delay = appProps.getCleanup().getHardDeleteDelayHours();
        clipboard.setStatus(ClipboardStatus.EXPIRED);
        clipboard.setExpiresAt(Instant.now().minus(delay + 1, ChronoUnit.HOURS));
        clipboardRepository.saveAndFlush(clipboard);

        cleanupService.purgeDeletedClipboards();

        Optional<Clipboard> found = clipboardRepository.findByClipboardCode(code);
        assertTrue(found.isEmpty(), "Expired clipboard should be hard-deleted after retention period");
    }

    @Test
    void activeClipboardNotHardDeleted() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");

        // Even with expiresAt in the past, ACTIVE status should not be purged
        Clipboard clipboard = clipboardRepository.findByClipboardCode(code).orElseThrow();
        int delay = appProps.getCleanup().getHardDeleteDelayHours();
        clipboard.setExpiresAt(Instant.now().minus(delay + 100, ChronoUnit.HOURS));
        clipboardRepository.saveAndFlush(clipboard);

        cleanupService.purgeDeletedClipboards();

        Optional<Clipboard> found = clipboardRepository.findByClipboardCode(code);
        assertTrue(found.isPresent(), "Active clipboard must never be hard-deleted by purge");
        assertEquals(ClipboardStatus.ACTIVE, found.get().getStatus());
    }

    @Test
    void hardDeleteFreesClipboardCode() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");
        clipboardService.deleteClipboard(code);

        // existsByClipboardCode still returns true before purge
        assertTrue(clipboardRepository.existsByClipboardCode(code));

        // Make eligible for purge
        Clipboard clipboard = clipboardRepository.findByClipboardCode(code).orElseThrow();
        int delay = appProps.getCleanup().getHardDeleteDelayHours();
        clipboard.setExpiresAt(Instant.now().minus(delay + 1, ChronoUnit.HOURS));
        clipboardRepository.saveAndFlush(clipboard);

        cleanupService.purgeDeletedClipboards();

        // Code is now free
        assertFalse(clipboardRepository.existsByClipboardCode(code),
            "Clipboard code should be freed after hard delete");
    }

    @Test
    void purgeIsIdempotent() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");
        clipboardService.deleteClipboard(code);

        Clipboard clipboard = clipboardRepository.findByClipboardCode(code).orElseThrow();
        int delay = appProps.getCleanup().getHardDeleteDelayHours();
        clipboard.setExpiresAt(Instant.now().minus(delay + 1, ChronoUnit.HOURS));
        clipboardRepository.saveAndFlush(clipboard);

        cleanupService.purgeDeletedClipboards();
        // Running again should not fail
        assertDoesNotThrow(() -> cleanupService.purgeDeletedClipboards());
    }

    @Test
    void purgeDisabledByConfig() {
        boolean original = appProps.getCleanup().isHardDeleteEnabled();
        try {
            appProps.getCleanup().setHardDeleteEnabled(false);

            ClipboardCreatedResponse created = clipboardService.createClipboard(null);
            String code = created.clipboardCode();
            clipboardService.setPasswordHash(code, "hash");
            clipboardService.deleteClipboard(code);

            Clipboard clipboard = clipboardRepository.findByClipboardCode(code).orElseThrow();
            int delay = appProps.getCleanup().getHardDeleteDelayHours();
            clipboard.setExpiresAt(Instant.now().minus(delay + 1, ChronoUnit.HOURS));
            clipboardRepository.saveAndFlush(clipboard);

            cleanupService.purgeDeletedClipboards();

            // Should still exist because hard delete is disabled
            assertTrue(clipboardRepository.findByClipboardCode(code).isPresent(),
                "Clipboard should not be purged when hard-delete is disabled");
        } finally {
            appProps.getCleanup().setHardDeleteEnabled(original);
        }
    }

    @Test
    void purgeRemovesRemainingChildRecords() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");

        // Add text content
        clipboardService.updateText(code, "encrypted".getBytes(), "iv123", -1, "device1");

        // Soft-delete (this already removes children)
        clipboardService.deleteClipboard(code);

        // Make eligible for purge
        Clipboard clipboard = clipboardRepository.findByClipboardCode(code).orElseThrow();
        Long clipId = clipboard.getId();
        int delay = appProps.getCleanup().getHardDeleteDelayHours();
        clipboard.setExpiresAt(Instant.now().minus(delay + 1, ChronoUnit.HOURS));
        clipboardRepository.saveAndFlush(clipboard);

        cleanupService.purgeDeletedClipboards();

        // Clipboard and all related data should be gone
        assertFalse(clipboardRepository.existsById(clipId));
        assertTrue(textRepository.findByClipboardId(clipId).isEmpty());
        assertTrue(fileRepository.findByClipboardIdAndStatus(clipId, FileStatus.AVAILABLE).isEmpty());
        assertTrue(transferRepository.findByClipboardId(clipId).isEmpty());
    }
}
