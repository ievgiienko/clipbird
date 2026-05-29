package com.secureclipboard;

import com.secureclipboard.domain.Clipboard;
import com.secureclipboard.domain.ClipboardStatus;
import com.secureclipboard.dto.*;
import com.secureclipboard.repository.ClipboardRepository;
import com.secureclipboard.service.ClipboardService;
import com.secureclipboard.service.DeviceSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
class ClipboardServiceTest {

    @Autowired
    private ClipboardService clipboardService;

    @Autowired
    private ClipboardRepository clipboardRepository;

    @Autowired
    private DeviceSessionManager sessionManager;

    @Test
    void shouldCreateClipboard() {
        ClipboardCreatedResponse response = clipboardService.createClipboard(null, null);

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
        ClipboardCreatedResponse created = clipboardService.createClipboard(null, null);
        String code = created.clipboardCode();

        clipboardService.setPasswordHash(code, "testPasswordHash123");
        assertTrue(clipboardService.verifyPassword(code, "testPasswordHash123"));
        assertFalse(clipboardService.verifyPassword(code, "wrongHash"));
    }

    @Test
    void shouldUpdateAndRetrieveText() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null, null);
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
        ClipboardCreatedResponse created = clipboardService.createClipboard(null, null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");

        clipboardService.updateText(code, "v1".getBytes(), "iv1", -1, "d1");
        clipboardService.updateText(code, "v2".getBytes(), "iv2", 1, "d1");
        TextSnapshotDto v3 = clipboardService.updateText(code, "v3".getBytes(), "iv3", 2, "d2");

        assertEquals(3, v3.version());
    }

    @Test
    void shouldDeleteClipboard() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null, null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");

        clipboardService.deleteClipboard(code, null);

        Clipboard clipboard = clipboardRepository.findByClipboardCode(code).orElse(null);
        assertNotNull(clipboard);
        assertEquals(ClipboardStatus.DELETED, clipboard.getStatus());

        assertThrows(IllegalArgumentException.class, () -> clipboardService.getState(code));
    }

    @Test
    void shouldRejectPasswordSetTwice() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null, null);
        String code = created.clipboardCode();

        clipboardService.setPasswordHash(code, "hash1");
        assertThrows(IllegalStateException.class, () -> clipboardService.setPasswordHash(code, "hash2"));
    }

    @Test
    void shouldStoreAndReturnFileHash() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null, null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");

        // Simulate source device online
        WebSocketSession mockSession = mock(WebSocketSession.class);
        when(mockSession.getId()).thenReturn("sess-" + UUID.randomUUID());
        when(mockSession.isOpen()).thenReturn(true);
        sessionManager.join(code, "source-dev", mockSession);

        String expectedHash = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

        FileManifestDto manifest = clipboardService.addFile(code, new AddFileRequest(
            "enc-name", "enc-iv", "", "", 1000, 1, "source-dev", expectedHash, null, null
        ));

        assertEquals(expectedHash, manifest.fileHash());

        // Verify hash is returned in clipboard state
        ClipboardStateResponse state = clipboardService.getState(code);
        assertEquals(1, state.files().size());
        assertEquals(expectedHash, state.files().get(0).fileHash());
    }

    @Test
    void shouldCreateClipboardWithCustomTtl() {
        ClipboardCreatedResponse response = clipboardService.createClipboard(new CreateClipboardRequest(1), null);

        Instant expiresAt = Instant.parse(response.expiresAt());
        long minutesUntilExpiry = java.time.Duration.between(Instant.now(), expiresAt).toMinutes();
        assertTrue(minutesUntilExpiry > 50 && minutesUntilExpiry <= 60,
            "1h TTL should expire in ~60 minutes, got " + minutesUntilExpiry);
    }

    @Test
    void shouldRejectInvalidTtl() {
        assertThrows(IllegalArgumentException.class,
            () -> clipboardService.createClipboard(new CreateClipboardRequest(99), null));
    }

    @Test
    void shouldAllowNullFileHash() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null, null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");

        WebSocketSession mockSession = mock(WebSocketSession.class);
        when(mockSession.getId()).thenReturn("sess-" + UUID.randomUUID());
        when(mockSession.isOpen()).thenReturn(true);
        sessionManager.join(code, "source-dev", mockSession);

        FileManifestDto manifest = clipboardService.addFile(code, new AddFileRequest(
            "enc-name", "enc-iv", "", "", 1000, 1, "source-dev", null, null, null
        ));

        assertNull(manifest.fileHash());
    }

    @Test
    void shouldStoreAndReturnEncryptedPreview() {
        ClipboardCreatedResponse created = clipboardService.createClipboard(null, null);
        String code = created.clipboardCode();
        clipboardService.setPasswordHash(code, "hash");

        WebSocketSession mockSession = mock(WebSocketSession.class);
        when(mockSession.getId()).thenReturn("sess-" + UUID.randomUUID());
        when(mockSession.isOpen()).thenReturn(true);
        sessionManager.join(code, "source-dev", mockSession);

        String previewData = "base64encryptedthumbnaildata==";
        String previewIv = "base64previewiv==";

        FileManifestDto manifest = clipboardService.addFile(code, new AddFileRequest(
            "enc-name", "enc-iv", "enc-mime", "enc-mime-iv", 500000, 1, "source-dev", null,
            previewData, previewIv
        ));

        assertEquals(previewData, manifest.encryptedPreview());
        assertEquals(previewIv, manifest.encryptedPreviewIv());

        ClipboardStateResponse state = clipboardService.getState(code);
        assertEquals(1, state.files().size());
        assertEquals(previewData, state.files().get(0).encryptedPreview());
        assertEquals(previewIv, state.files().get(0).encryptedPreviewIv());
    }
}
