package com.secureclipboard;

import com.secureclipboard.domain.*;
import com.secureclipboard.dto.CreateTransferRequest;
import com.secureclipboard.dto.ChunkDownloadResponse;
import com.secureclipboard.dto.TransferResponse;
import com.secureclipboard.repository.ChunkTransferRepository;
import com.secureclipboard.repository.ClipboardFileRepository;
import com.secureclipboard.repository.ClipboardRepository;
import com.secureclipboard.service.ClipboardService;
import com.secureclipboard.service.DeviceSessionManager;
import com.secureclipboard.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.WebSocketSession;
import com.secureclipboard.service.RedisChunkStore;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
@Transactional
class TransferServiceTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private ClipboardService clipboardService;

    @Autowired
    private ClipboardRepository clipboardRepository;

    @Autowired
    private ClipboardFileRepository fileRepository;

    @Autowired
    private ChunkTransferRepository transferRepository;

    @Autowired
    private DeviceSessionManager sessionManager;

    @Autowired
    private RedisChunkStore chunkStore;


    private String clipboardCode;
    private String fileCode;

    @BeforeEach
    void setUp() {
        // Create a clipboard with password
        var created = clipboardService.createClipboard(null, null);
        clipboardCode = created.clipboardCode();
        clipboardService.setPasswordHash(clipboardCode, "testHash");

        // Simulate source device online
        String sessionId = "session-" + UUID.randomUUID();
        WebSocketSession mockSession = mock(WebSocketSession.class);
        when(mockSession.getId()).thenReturn(sessionId);
        when(mockSession.isOpen()).thenReturn(true);
        sessionManager.join(clipboardCode, "source-device", mockSession);

        // Create a file entry with unique fileCode
        Clipboard clipboard = clipboardRepository.findByClipboardCode(clipboardCode).orElseThrow();
        fileCode = UUID.randomUUID().toString();
        ClipboardFile file = new ClipboardFile();
        file.setClipboard(clipboard);
        file.setFileCode(fileCode);
        file.setEncryptedName("enc-name");
        file.setEncryptedNameIv("enc-iv");
        file.setSizeBytes(15_000_000); // ~15 MB = 3 chunks at 5MB
        file.setChunkSize(5_242_880);
        file.setTotalChunks(3);
        file.setSourceDeviceId("source-device");
        file.setStatus(FileStatus.AVAILABLE);
        fileRepository.save(file);
    }

    @Test
    void shouldCreateTransfer() {
        TransferResponse resp = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        assertNotNull(resp.transferCode());
        assertEquals(3, resp.totalChunks());
        assertEquals(0, resp.currentChunkNo());
        assertEquals("AWAITING_CHUNK", resp.status());
        assertTrue(resp.defaultChunkSize() > 0);
        assertTrue(resp.minChunkSize() > 0);
        assertTrue(resp.maxChunkSize() > 0);
    }

    @Test
    void shouldUploadAndDownloadSingleChunk() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        byte[] data = new byte[]{1, 2, 3, 4, 5};
        transferService.uploadChunk(transfer.transferCode(), 0, data, "test-iv-0", "hash-0");

        ChunkDownloadResponse chunk = transferService.downloadChunk(transfer.transferCode(), 0);
        assertArrayEquals(data, chunk.data());
        assertEquals("test-iv-0", chunk.iv());
        assertEquals(0, chunk.chunkNo());
        assertFalse(chunk.lastChunk());
    }

    @Test
    void shouldRejectUploadWhenSlotFull() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0", "h0");

        // Second upload should fail — slot is full
        assertThrows(IllegalStateException.class, () ->
            transferService.uploadChunk(transfer.transferCode(), 1, new byte[]{20}, "iv-1", "h1"));
    }

    @Test
    void shouldAckChunkAndClearSlot() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0", "h0");

        // ACK chunk 0
        TransferResponse afterAck = transferService.acknowledgeChunk(transfer.transferCode());
        assertEquals(1, afterAck.currentChunkNo());
        assertEquals("AWAITING_CHUNK", afterAck.status());

        // Now we can upload chunk 1 (slot freed by ack)
        transferService.uploadChunk(transfer.transferCode(), 1, new byte[]{20}, "iv-1", "h1");
    }

    @Test
    void shouldCompleteTransferAfterAllChunksAcked() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        // Upload and ack all 3 chunks sequentially
        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0", "h0");
        TransferResponse r1 = transferService.acknowledgeChunk(transfer.transferCode());
        assertEquals("AWAITING_CHUNK", r1.status());

        transferService.uploadChunk(transfer.transferCode(), 1, new byte[]{20}, "iv-1", "h1");
        TransferResponse r2 = transferService.acknowledgeChunk(transfer.transferCode());
        assertEquals("AWAITING_CHUNK", r2.status());

        transferService.uploadChunk(transfer.transferCode(), 2, new byte[]{30}, "iv-2", "h2");
        TransferResponse r3 = transferService.acknowledgeChunk(transfer.transferCode());
        assertEquals("COMPLETED", r3.status());
        assertEquals(3, r3.currentChunkNo());
    }

    @Test
    void shouldCancelTransferAndClearSlot() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0", "h0");

        transferService.cancelTransfer(transfer.transferCode());

        TransferResponse cancelled = transferService.getTransfer(transfer.transferCode());
        assertEquals("CANCELLED", cancelled.status());

        // Verify data is cleared by checking uploads fail (transfer is cancelled)
        assertThrows(IllegalStateException.class, () ->
            transferService.uploadChunk(transfer.transferCode(), 1, new byte[]{20}, "iv-1", "h1"));
    }

    @Test
    void shouldRejectDownloadForNonBufferedChunk() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0", "h0");

        // Request chunk 1, which isn't buffered
        assertThrows(IllegalArgumentException.class, () ->
            transferService.downloadChunk(transfer.transferCode(), 1));
    }

    @Test
    void shouldStoreBinaryDataWithoutCorruption() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        // Binary data with all byte values including 0x00 and 0xFF
        byte[] binaryData = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryData[i] = (byte) i;
        }

        transferService.uploadChunk(transfer.transferCode(), 0, binaryData, "iv-binary", "hash-bin");

        ChunkDownloadResponse chunk = transferService.downloadChunk(transfer.transferCode(), 0);
        assertArrayEquals(binaryData, chunk.data());
        assertEquals("iv-binary", chunk.iv());
    }

    @Test
    void shouldRejectOperationsOnExpiredTransfer() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        // Manually expire the transfer
        ChunkTransfer entity = transferRepository.findByTransferCode(transfer.transferCode()).orElseThrow();
        entity.setExpiresAt(java.time.Instant.now().minusSeconds(10));
        transferRepository.save(entity);

        assertThrows(IllegalStateException.class, () ->
            transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{1}, "iv", "h"));
    }

    @Test
    void shouldRejectTransferForOfflineSource() {
        // Remove source from session manager
        sessionManager.removeRoom(clipboardCode);

        assertThrows(IllegalStateException.class, () ->
            transferService.createTransfer(
                new CreateTransferRequest(clipboardCode, fileCode, "target-device")));
    }

    @Test
    void shouldRejectDownloadWhenNoChunkReady() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        // No chunk uploaded yet
        assertThrows(IllegalStateException.class, () ->
            transferService.downloadChunk(transfer.transferCode(), 0));
    }

    @Test
    void shouldCancelActiveTransfersWhenSourceDisconnects() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        // Upload a chunk so transfer is in CHUNK_READY state
        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{1}, "iv-0", "h0");

        // Simulate source disconnect: cancel all active transfers for this source
        List<TransferResponse> cancelled = transferService.failTransfersBySourceDevice("source-device");
        assertEquals(1, cancelled.size());
        assertEquals("CANCELLED", cancelled.get(0).status());
        assertEquals(transfer.transferCode(), cancelled.get(0).transferCode());

        // Verify transfer is actually cancelled in the DB
        TransferResponse afterCancel = transferService.getTransfer(transfer.transferCode());
        assertEquals("CANCELLED", afterCancel.status());

        // Verify further uploads are rejected
        assertThrows(IllegalStateException.class, () ->
            transferService.uploadChunk(transfer.transferCode(), 1, new byte[]{2}, "iv-1", "h1"));
    }

    @Test
    void shouldCancelActiveTransfersWhenFileRemoved() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        List<TransferResponse> cancelled = transferService.failTransfersByFile(fileCode);
        assertEquals(1, cancelled.size());
        assertEquals("CANCELLED", cancelled.get(0).status());

        TransferResponse afterCancel = transferService.getTransfer(transfer.transferCode());
        assertEquals("CANCELLED", afterCancel.status());
    }

    @Test
    void shouldNotCancelAlreadyCompletedTransfers() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        // Complete the transfer
        for (int i = 0; i < 3; i++) {
            transferService.uploadChunk(transfer.transferCode(), i, new byte[]{(byte) i}, "iv-" + i, "h" + i);
            transferService.acknowledgeChunk(transfer.transferCode());
        }

        // failTransfersBySourceDevice should NOT affect the completed transfer
        List<TransferResponse> cancelled = transferService.failTransfersBySourceDevice("source-device");
        assertTrue(cancelled.stream().noneMatch(t -> t.transferCode().equals(transfer.transferCode())),
            "Completed transfer should not be in the cancelled list");

        TransferResponse afterCheck = transferService.getTransfer(transfer.transferCode());
        assertEquals("COMPLETED", afterCheck.status());
    }
    @Test
    void shouldRejectTransferForRemovedFile() {
        ClipboardFile file = fileRepository.findByFileCode(fileCode).orElseThrow();
        file.setStatus(FileStatus.REMOVED);
        fileRepository.save(file);
        assertThrows(IllegalArgumentException.class, () ->
                transferService.createTransfer(
                        new CreateTransferRequest(clipboardCode, fileCode, "target-device")));
    }

    // Downloading a chunk from a cancelled transfer should not be allowed.
    @Test
    void shouldRejectDownloadAfterTransferCancelled() {
        TransferResponse transfer = transferService.createTransfer(
                new CreateTransferRequest(clipboardCode, fileCode, "target-device"));
        transferService.uploadChunk(
                transfer.transferCode(),
                0,
                new byte[]{1, 2, 3},
                "iv-0",
                "hash-0"
        );
        transferService.cancelTransfer(transfer.transferCode());
        assertThrows(
                IllegalStateException.class,
                () -> transferService.downloadChunk(transfer.transferCode(), 0)
        );
    }

    // Cancelling an already cancelled transfer should keep it in the cancelled state.
    @Test
    void shouldAllowRepeatedTransferCancellation() {
        TransferResponse transfer = transferService.createTransfer(
                new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.cancelTransfer(transfer.transferCode());

        assertDoesNotThrow(() ->
                transferService.cancelTransfer(transfer.transferCode()));

        TransferResponse response = transferService.getTransfer(transfer.transferCode());

        assertEquals("CANCELLED", response.status());
    }

    // Uploading a chunk after a transfer has been cancelled should not be allowed.
    @Test
    void shouldRejectAcknowledgementAfterTransferCancelled() {
        TransferResponse transfer = transferService.createTransfer(
                new CreateTransferRequest(clipboardCode, fileCode, "target-device"));
        transferService.cancelTransfer(transfer.transferCode());
        assertThrows(
                IllegalStateException.class,
                () -> transferService.uploadChunk(
                        transfer.transferCode(),
                        0,
                        new byte[]{1},
                        "iv",
                        "hash"));
    }

    // Loading a transfer should return the same metadata that was created
    @Test
    void shouldReturnSameTransferInformation() {
        TransferResponse created = transferService.createTransfer(
                new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        TransferResponse loaded = transferService.getTransfer(created.transferCode());

        assertEquals(created.transferCode(), loaded.transferCode());
        assertEquals(created.fileCode(), loaded.fileCode());
        assertEquals(created.sourceDeviceId(), loaded.sourceDeviceId());
        assertEquals(created.targetDeviceId(), loaded.targetDeviceId());
        assertEquals(created.totalChunks(), loaded.totalChunks());
        assertEquals(created.currentChunkNo(), loaded.currentChunkNo());
        assertEquals(created.status(), loaded.status());
    }

    // Creating a transfer for a non-existing file should fail.
    @Test
    void shouldRejectCreatingTransferForMissingFile() {
        assertThrows(
                IllegalArgumentException.class,
                () -> transferService.createTransfer(
                        new CreateTransferRequest(
                                clipboardCode,
                                UUID.randomUUID().toString(),
                                "target-device")));
    }

    // Requesting a non-existing transfer should throw an except
    @Test
    void shouldRejectUnknownTransferCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> transferService.getTransfer(UUID.randomUUID().toString()));
    }

    // Uploading a chunk for a non-existing transfer should fail.
    @Test
    void shouldRejectUnknownTransferDuringUpload() {
        assertThrows(
                IllegalArgumentException.class,
                () -> transferService.uploadChunk(
                        UUID.randomUUID().toString(),
                        0,
                        new byte[]{1, 2, 3},
                        "iv",
                        "hash"));
    }

    // Downloading a chunk for a non-existing transfer should fail.
    @Test
    void shouldRejectUnknownTransferDuringDownload() {
        assertThrows(
                IllegalArgumentException.class,
                () -> transferService.downloadChunk(
                        UUID.randomUUID().toString(),
                        0));
    }

    // A transfer should remain awaiting the next chunk until all chunks are acknowledged.
    @Test
    void shouldKeepTransferStatusAsAwaitingChunkAfterFirstAcknowledgement() {
        TransferResponse transfer = transferService.createTransfer(
                new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.uploadChunk(
                transfer.transferCode(),
                0,
                new byte[]{1},
                "iv-0",
                "hash-0");

        transferService.acknowledgeChunk(transfer.transferCode());

        TransferResponse updated = transferService.getTransfer(transfer.transferCode());

        assertEquals("AWAITING_CHUNK", updated.status());
        assertEquals(1, updated.currentChunkNo());
    }

    // Downloaded chunk should contain the same hash that was uploaded.
    @Test
    void shouldReturnChunkHashWhenDownloading() {
        TransferResponse transfer = transferService.createTransfer(
                new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.uploadChunk(
                transfer.transferCode(),
                0,
                new byte[]{5, 6, 7},
                "iv-test",
                "expected-hash");

        ChunkDownloadResponse response =
                transferService.downloadChunk(transfer.transferCode(), 0);

        assertEquals("expected-hash", response.chunkHash());
    }

    // Retrieving a transfer should return its current persisted state.
    @Test
    void shouldReturnCurrentTransferState() {
        TransferResponse created = transferService.createTransfer(
                new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        TransferResponse loaded =
                transferService.getTransfer(created.transferCode());

        assertEquals(created.transferCode(), loaded.transferCode());
        assertEquals(fileCode, loaded.fileCode());
        assertEquals("source-device", loaded.sourceDeviceId());
        assertEquals("target-device", loaded.targetDeviceId());
        assertEquals("AWAITING_CHUNK", loaded.status());
        assertEquals(0, loaded.currentChunkNo());
        assertEquals(3, loaded.totalChunks());
    }

    // Upload should be rejected after the transfer has been cancelled
    @Test
    void shouldRejectUploadAfterTransferCancelled() {
        TransferResponse transfer = transferService.createTransfer(
                new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.cancelTransfer(transfer.transferCode());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> transferService.uploadChunk(
                        transfer.transferCode(),
                        0,
                        new byte[]{1},
                        "iv",
                        "hash"
                )
        );

        assertTrue(ex.getMessage().contains("Transfer is no longer active"));
        assertEquals(
                TransferStatus.CANCELLED,
                transferRepository.findByTransferCode(transfer.transferCode())
                        .orElseThrow()
                        .getStatus()
        );
    }

    @Test
    void shouldSequentiallyTransferAllChunks() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        // Simulate full sequential transfer: upload -> download -> ack for each chunk
        for (int i = 0; i < 3; i++) {
            byte[] data = new byte[]{(byte) (i * 10)};
            transferService.uploadChunk(transfer.transferCode(), i, data, "iv-" + i, "hash-" + i);

            ChunkDownloadResponse chunk = transferService.downloadChunk(transfer.transferCode(), i);
            assertArrayEquals(data, chunk.data());
            assertEquals(i == 2, chunk.lastChunk());

            TransferResponse afterAck = transferService.acknowledgeChunk(transfer.transferCode());
            assertEquals(i + 1, afterAck.currentChunkNo());

            if (i < 2) {
                assertEquals("AWAITING_CHUNK", afterAck.status());
            } else {
                assertEquals("COMPLETED", afterAck.status());
            }
        }
    }
}
