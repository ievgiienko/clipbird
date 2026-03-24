package com.secureclipboard;

import com.secureclipboard.domain.Clipboard;
import com.secureclipboard.domain.ClipboardStatus;
import com.secureclipboard.dto.ClipboardCreatedResponse;
import com.secureclipboard.dto.ClipboardStateResponse;
import com.secureclipboard.dto.TextSnapshotDto;
import com.secureclipboard.repository.ClipboardRepository;
import com.secureclipboard.service.ClipboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ClipboardServiceTest {

    @Autowired
    private ClipboardService clipboardService;

    @Autowired
    private ClipboardRepository clipboardRepository;

    @Test
    void shouldCreateClipboard() {
        ClipboardCreatedResponse response = clipboardService.createClipboard();

        assertNotNull(response.clipboardCode());
        assertEquals(8, response.clipboardCode().length());
        assertNotNull(response.salt());
        assertEquals("PBKDF2", response.kdfAlgorithm());
        assertEquals(600000, response.kdfIterations());
        assertNotNull(response.expiresAt());

        // Verify it exists in DB
        Clipboard clipboard = clipboardRepository.findByClipboardCode(response.clipboardCode()).orElse(null);
        assertNotNull(clipboard);
        assertEquals(ClipboardStatus.ACTIVE, clipboard.getStatus());
    }

    @Test
    void shouldSetPasswordAndVerify() {
        ClipboardCreatedResponse created = clipboardService.createClipboard();
        String code = created.clipboardCode();

        clipboardService.setPasswordHash(code, "testPasswordHash123");
        assertTrue(clipboardService.verifyPassword(code, "testPasswordHash123"));
        assertFalse(clipboardService.verifyPassword(code, "wrongHash"));
    }

    @Test
    void shouldUpdateAndRetrieveText() {
        ClipboardCreatedResponse created = clipboardService.createClipboard();
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");

        byte[] ciphertext = "encrypted-content".getBytes();
        String iv = Base64.getEncoder().encodeToString("test-iv-12byte".getBytes());

        TextSnapshotDto updated = clipboardService.updateText(code, ciphertext, iv, -1, "device1");

        assertEquals(1, updated.version());
        assertNotNull(updated.ciphertext());
        assertEquals(iv, updated.iv());

        // Retrieve state
        ClipboardStateResponse state = clipboardService.getState(code);
        assertNotNull(state.text());
        assertEquals(1, state.text().version());
        assertEquals(iv, state.text().iv());
    }

    @Test
    void shouldIncrementVersionOnMultipleUpdates() {
        ClipboardCreatedResponse created = clipboardService.createClipboard();
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");

        clipboardService.updateText(code, "v1".getBytes(), "iv1", -1, "d1");
        clipboardService.updateText(code, "v2".getBytes(), "iv2", 1, "d1");
        TextSnapshotDto v3 = clipboardService.updateText(code, "v3".getBytes(), "iv3", 2, "d2");

        assertEquals(3, v3.version());
    }

    @Test
    void shouldDeleteClipboard() {
        ClipboardCreatedResponse created = clipboardService.createClipboard();
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");

        clipboardService.deleteClipboard(code);

        Clipboard clipboard = clipboardRepository.findByClipboardCode(code).orElse(null);
        assertNotNull(clipboard);
        assertEquals(ClipboardStatus.DELETED, clipboard.getStatus());

        assertThrows(IllegalArgumentException.class, () -> clipboardService.getState(code));
    }

    @Test
    void shouldRejectPasswordSetTwice() {
        ClipboardCreatedResponse created = clipboardService.createClipboard();
        String code = created.clipboardCode();

        clipboardService.setPasswordHash(code, "hash1");
        assertThrows(IllegalStateException.class, () -> clipboardService.setPasswordHash(code, "hash2"));
    }
}
