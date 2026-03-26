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
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
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

    private String clipboardCode;
    private String fileCode;

    @BeforeEach
    void setUp() {
        // Create a clipboard with password
        var created = clipboardService.createClipboard();
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
