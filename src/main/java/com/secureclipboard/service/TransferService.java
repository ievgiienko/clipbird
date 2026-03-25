package com.secureclipboard.service;

import com.secureclipboard.config.AppProperties;
import com.secureclipboard.domain.*;
import com.secureclipboard.dto.*;
import com.secureclipboard.repository.*;
import com.secureclipboard.util.CodeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Manages chunk-based file transfers with a simple sequential model.
 *
 * Transfer flow (one chunk at a time):
 *   1. Target requests a chunk from the source via WebSocket.
 *   2. Source encrypts and uploads the chunk to the server (single temp slot).
 *   3. Target downloads the chunk and sends ACK.
 *   4. Server clears the temp slot and advances to the next chunk.
 *   5. Repeat until all chunks are transferred.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final ChunkTransferRepository transferRepo;
    private final ClipboardFileRepository fileRepo;
    private final ClipboardService clipboardService;
    private final DeviceSessionManager sessionManager;
    private final AppProperties appProps;

    public TransferService(ChunkTransferRepository transferRepo,
                           ClipboardFileRepository fileRepo,
                           ClipboardService clipboardService,
                           DeviceSessionManager sessionManager,
                           AppProperties appProps) {
        this.transferRepo = transferRepo;
        this.fileRepo = fileRepo;
        this.clipboardService = clipboardService;
        this.sessionManager = sessionManager;
        this.appProps = appProps;
    }

    @Transactional
    public TransferResponse createTransfer(CreateTransferRequest request) {
        Clipboard clipboard = clipboardService.findActiveClipboard(request.clipboardCode());
        ClipboardFile file = fileRepo.findByFileCode(request.fileCode())
            .orElseThrow(() -> new IllegalArgumentException("File not found: " + request.fileCode()));

        if (file.getStatus() != FileStatus.AVAILABLE) {
            throw new IllegalArgumentException("File is not available for transfer");
        }

        if (!sessionManager.isDeviceOnline(request.clipboardCode(), file.getSourceDeviceId())) {
            throw new IllegalStateException("Source device is offline");
        }

        int timeoutSeconds = appProps.getCleanup().getTransferTimeoutSeconds();

        ChunkTransfer transfer = new ChunkTransfer();
        transfer.setTransferCode(CodeGenerator.generateUUID());
        transfer.setClipboard(clipboard);
        transfer.setFile(file);
        transfer.setSourceDeviceId(file.getSourceDeviceId());
        transfer.setTargetDeviceId(request.targetDeviceId());
        transfer.setTotalChunks(file.getTotalChunks());
        transfer.setCurrentChunkNo(0);
        transfer.setStatus(TransferStatus.AWAITING_CHUNK);
        transfer.setExpiresAt(Instant.now().plus(timeoutSeconds, ChronoUnit.SECONDS));
        transferRepo.save(transfer);

        log.info("Transfer created: {} for file {}", transfer.getTransferCode(), request.fileCode());
        return toResponse(transfer);
    }

    /**
     * Upload an encrypted chunk into the single buffer slot.
     * Binary data is stored directly (no base64 conversion).
     */
    @Transactional
    public void uploadChunk(String transferCode, int chunkNo, byte[] data, String iv) {
        ChunkTransfer transfer = findTransfer(transferCode);
        validateTransferActive(transfer);

        if (transfer.getTempChunkData() != null) {
            throw new IllegalStateException("Chunk buffer slot is full. Wait for target to download.");
        }

        transfer.setTempChunkData(data);
        transfer.setTempChunkIv(iv);
        transfer.setTempChunkNo(chunkNo);
        transfer.setStatus(TransferStatus.CHUNK_READY);
        transferRepo.save(transfer);
        log.debug("Chunk {} uploaded for transfer {}", chunkNo, transferCode);
    }

    /**
     * Download the buffered chunk.
     * Returns raw encrypted bytes (binary, not base64).
     */
    @Transactional(readOnly = true)
    public ChunkDownloadResponse downloadChunk(String transferCode, int chunkNo) {
        ChunkTransfer transfer = findTransfer(transferCode);
        if (transfer.getTempChunkData() == null) {
            throw new IllegalStateException("No chunk ready for download");
        }

        if (transfer.getTempChunkNo() == null || transfer.getTempChunkNo() != chunkNo) {
            throw new IllegalArgumentException("Requested chunk " + chunkNo + " is not buffered. " +
                "Buffered: " + transfer.getTempChunkNo());
        }

        boolean lastChunk = (chunkNo >= transfer.getTotalChunks() - 1);
        return new ChunkDownloadResponse(chunkNo, transfer.getTempChunkData(), transfer.getTempChunkIv(), lastChunk);
    }

    /**
     * Acknowledge that the target has downloaded the current chunk.
     * Clears the buffer slot and advances state.
     */
    @Transactional
    public TransferResponse acknowledgeChunk(String transferCode) {
        ChunkTransfer transfer = findTransfer(transferCode);

        // Clear the temp slot
        int ackedChunk = transfer.getTempChunkNo() != null ? transfer.getTempChunkNo() : transfer.getCurrentChunkNo();
        transfer.setTempChunkData(null);
        transfer.setTempChunkIv(null);
        transfer.setTempChunkNo(null);

        int nextExpected = ackedChunk + 1;
        transfer.setCurrentChunkNo(nextExpected);

        if (nextExpected >= transfer.getTotalChunks()) {
            transfer.setStatus(TransferStatus.COMPLETED);
            log.info("Transfer completed: {}", transferCode);
        } else {
            transfer.setStatus(TransferStatus.AWAITING_CHUNK);
        }

        transferRepo.save(transfer);
        return toResponse(transfer);
    }

    @Transactional
    public void cancelTransfer(String transferCode) {
        ChunkTransfer transfer = findTransfer(transferCode);
        transfer.setStatus(TransferStatus.CANCELLED);
        transfer.setTempChunkData(null);
        transfer.setTempChunkIv(null);
        transfer.setTempChunkNo(null);
        transferRepo.save(transfer);
        log.info("Transfer cancelled: {}", transferCode);
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(String transferCode) {
        return toResponse(findTransfer(transferCode));
    }

    private ChunkTransfer findTransfer(String transferCode) {
        return transferRepo.findByTransferCode(transferCode)
            .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + transferCode));
    }

    private void validateTransferActive(ChunkTransfer transfer) {
        if (transfer.getStatus() == TransferStatus.COMPLETED ||
            transfer.getStatus() == TransferStatus.FAILED ||
            transfer.getStatus() == TransferStatus.CANCELLED) {
            throw new IllegalStateException("Transfer is no longer active: " + transfer.getStatus());
        }
        if (transfer.getExpiresAt().isBefore(Instant.now())) {
            transfer.setStatus(TransferStatus.FAILED);
            transferRepo.save(transfer);
            throw new IllegalStateException("Transfer has expired");
        }
    }

    private TransferResponse toResponse(ChunkTransfer transfer) {
        return new TransferResponse(
            transfer.getTransferCode(),
            transfer.getFile().getFileCode(),
            transfer.getSourceDeviceId(),
            transfer.getTargetDeviceId(),
            transfer.getTotalChunks(),
            transfer.getCurrentChunkNo(),
            transfer.getStatus().name(),
            appProps.getClipboard().getChunkSizeBytes(),
            appProps.getClipboard().getMinChunkSizeBytes(),
            appProps.getClipboard().getMaxChunkSizeBytes()
        );
    }
}
