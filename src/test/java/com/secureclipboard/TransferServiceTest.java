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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
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
    void shouldCreateTransferWithPipelineDepth() {
        TransferResponse resp = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        assertNotNull(resp.transferCode());
        assertEquals(3, resp.totalChunks());
        assertEquals(0, resp.currentChunkNo());
        assertEquals("AWAITING_CHUNK", resp.status());
        assertEquals(2, resp.pipelineDepth());
        assertTrue(resp.defaultChunkSize() > 0);
        assertTrue(resp.minChunkSize() > 0);
        assertTrue(resp.maxChunkSize() > 0);
    }

    @Test
    void shouldUploadAndDownloadSingleChunk() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        byte[] data = new byte[]{1, 2, 3, 4, 5};
        transferService.uploadChunk(transfer.transferCode(), 0, data, "test-iv-0");

        ChunkDownloadResponse chunk = transferService.downloadChunk(transfer.transferCode(), 0);
        assertArrayEquals(data, chunk.data());
        assertEquals("test-iv-0", chunk.iv());
        assertEquals(0, chunk.chunkNo());
        assertFalse(chunk.lastChunk());
    }

    @Test
    void shouldUploadTwoChunksIntoDoubleBuffer() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        // Upload chunk 0 into slot A
        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0");
        // Upload chunk 1 into slot B
        transferService.uploadChunk(transfer.transferCode(), 1, new byte[]{20}, "iv-1");

        // Both should be downloadable
        ChunkDownloadResponse c0 = transferService.downloadChunk(transfer.transferCode(), 0);
        assertArrayEquals(new byte[]{10}, c0.data());

        ChunkDownloadResponse c1 = transferService.downloadChunk(transfer.transferCode(), 1);
        assertArrayEquals(new byte[]{20}, c1.data());
    }

    @Test
    void shouldRejectUploadWhenBothSlotsFull() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0");
        transferService.uploadChunk(transfer.transferCode(), 1, new byte[]{20}, "iv-1");

        // Third upload should fail — both slots full
        assertThrows(IllegalStateException.class, () ->
            transferService.uploadChunk(transfer.transferCode(), 2, new byte[]{30}, "iv-2"));
    }

    @Test
    void shouldAckChunkAndClearSlot() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0");
        transferService.uploadChunk(transfer.transferCode(), 1, new byte[]{20}, "iv-1");

        // ACK clears the lowest chunk (chunk 0)
        TransferResponse afterAck = transferService.acknowledgeChunk(transfer.transferCode());
        assertEquals(1, afterAck.currentChunkNo()); // next to download is chunk 1
        assertEquals("CHUNK_READY", afterAck.status()); // chunk 1 still buffered

        // Chunk 1 should still be downloadable
        ChunkDownloadResponse c1 = transferService.downloadChunk(transfer.transferCode(), 1);
        assertArrayEquals(new byte[]{20}, c1.data());

        // Now we can upload chunk 2 (slot freed by ack)
        transferService.uploadChunk(transfer.transferCode(), 2, new byte[]{30}, "iv-2");
    }

    @Test
    void shouldCompleteTransferAfterAllChunksAcked() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        // Upload and ack all 3 chunks
        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0");
        TransferResponse r1 = transferService.acknowledgeChunk(transfer.transferCode());
        assertEquals("AWAITING_CHUNK", r1.status());

        transferService.uploadChunk(transfer.transferCode(), 1, new byte[]{20}, "iv-1");
        TransferResponse r2 = transferService.acknowledgeChunk(transfer.transferCode());
        assertEquals("AWAITING_CHUNK", r2.status());

        transferService.uploadChunk(transfer.transferCode(), 2, new byte[]{30}, "iv-2");
        TransferResponse r3 = transferService.acknowledgeChunk(transfer.transferCode());
        assertEquals("COMPLETED", r3.status());
        assertEquals(3, r3.currentChunkNo());
    }

    @Test
    void shouldCompletePipelinedTransfer() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        // Pipeline: upload chunks 0 and 1 simultaneously
        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0");
        transferService.uploadChunk(transfer.transferCode(), 1, new byte[]{20}, "iv-1");

        // Ack chunk 0, upload chunk 2 into the freed slot
        transferService.acknowledgeChunk(transfer.transferCode());
        transferService.uploadChunk(transfer.transferCode(), 2, new byte[]{30}, "iv-2");

        // Ack chunk 1
        TransferResponse r2 = transferService.acknowledgeChunk(transfer.transferCode());
        assertEquals("CHUNK_READY", r2.status()); // chunk 2 still buffered

        // Ack chunk 2 → completed
        TransferResponse r3 = transferService.acknowledgeChunk(transfer.transferCode());
        assertEquals("COMPLETED", r3.status());
    }

    @Test
    void shouldCancelTransferAndClearBothSlots() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0");
        transferService.uploadChunk(transfer.transferCode(), 1, new byte[]{20}, "iv-1");

        transferService.cancelTransfer(transfer.transferCode());

        TransferResponse cancelled = transferService.getTransfer(transfer.transferCode());
        assertEquals("CANCELLED", cancelled.status());

        // Verify data is cleared by checking uploads fail
        assertThrows(IllegalStateException.class, () ->
            transferService.uploadChunk(transfer.transferCode(), 2, new byte[]{30}, "iv-2"));
    }

    @Test
    void shouldRejectDownloadForNonBufferedChunk() {
        TransferResponse transfer = transferService.createTransfer(
            new CreateTransferRequest(clipboardCode, fileCode, "target-device"));

        transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{10}, "iv-0");

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

        transferService.uploadChunk(transfer.transferCode(), 0, binaryData, "iv-binary");

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
            transferService.uploadChunk(transfer.transferCode(), 0, new byte[]{1}, "iv"));
    }

    @Test
    void shouldRejectTransferForOfflineSource() {
        // Remove source from session manager
        sessionManager.removeRoom(clipboardCode);

        assertThrows(IllegalStateException.class, () ->
            transferService.createTransfer(
                new CreateTransferRequest(clipboardCode, fileCode, "target-device")));
    }
}
