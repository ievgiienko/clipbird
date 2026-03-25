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
 * Manages chunk-based file transfers with pipelined double-buffer support.
 *
 * Pipeline design:
 * The server holds up to 2 encrypted chunk slots (A and B) per transfer.
 * This allows the source device to upload chunk N+1 while the target device
 * downloads chunk N, roughly doubling throughput for multi-chunk transfers.
 *
 * Slot lifecycle:
 *   empty → filled (upload) → consumed (download + ack) → empty
 *
 * The currentChunkNo field tracks the next chunk the TARGET expects to download.
 * Uploads can be ahead by up to pipelineDepth slots.
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
        int pipelineDepth = appProps.getClipboard().getPipelineDepth();

        ChunkTransfer transfer = new ChunkTransfer();
        transfer.setTransferCode(CodeGenerator.generateUUID());
        transfer.setClipboard(clipboard);
        transfer.setFile(file);
        transfer.setSourceDeviceId(file.getSourceDeviceId());
        transfer.setTargetDeviceId(request.targetDeviceId());
        transfer.setTotalChunks(file.getTotalChunks());
        transfer.setCurrentChunkNo(0);
        transfer.setPipelineDepth(pipelineDepth);
        transfer.setStatus(TransferStatus.AWAITING_CHUNK);
        transfer.setExpiresAt(Instant.now().plus(timeoutSeconds, ChronoUnit.SECONDS));
        transferRepo.save(transfer);

        log.info("Transfer created: {} for file {} (pipeline={})",
                transfer.getTransferCode(), request.fileCode(), pipelineDepth);
        return toResponse(transfer);
    }

    /**
     * Upload an encrypted chunk into the next available buffer slot.
     * Binary data is stored directly (no base64 conversion).
     */
    @Transactional
    public void uploadChunk(String transferCode, int chunkNo, byte[] data, String iv) {
        ChunkTransfer transfer = findTransfer(transferCode);
        validateTransferActive(transfer);

        // Determine which slot to use. Slot A has priority if empty.
        if (transfer.getTempChunkData() == null) {
            transfer.setTempChunkData(data);
            transfer.setTempChunkIv(iv);
            transfer.setTempChunkNo(chunkNo);
        } else if (transfer.getTempChunkDataB() == null) {
            transfer.setTempChunkDataB(data);
            transfer.setTempChunkIvB(iv);
            transfer.setTempChunkNoB(chunkNo);
        } else {
            throw new IllegalStateException("Both chunk buffer slots are full. Wait for target to download.");
        }

        transfer.setStatus(TransferStatus.CHUNK_READY);
        transferRepo.save(transfer);
        log.debug("Chunk {} uploaded for transfer {} (slot {})", chunkNo, transferCode,
                transfer.getTempChunkNoB() != null && transfer.getTempChunkNoB() == chunkNo ? "B" : "A");
    }

    /**
     * Download the lowest-numbered buffered chunk.
     * Returns raw encrypted bytes (binary, not base64).
     */
    @Transactional(readOnly = true)
    public ChunkDownloadResponse downloadChunk(String transferCode, int chunkNo) {
        ChunkTransfer transfer = findTransfer(transferCode);
        if (transfer.getTempChunkData() == null && transfer.getTempChunkDataB() == null) {
            throw new IllegalStateException("No chunk ready for download");
        }

        // Find the slot holding the requested chunk
        byte[] data;
        String iv;
        if (transfer.getTempChunkNo() != null && transfer.getTempChunkNo() == chunkNo) {
            data = transfer.getTempChunkData();
            iv = transfer.getTempChunkIv();
        } else if (transfer.getTempChunkNoB() != null && transfer.getTempChunkNoB() == chunkNo) {
            data = transfer.getTempChunkDataB();
            iv = transfer.getTempChunkIvB();
        } else {
            throw new IllegalArgumentException("Requested chunk " + chunkNo + " is not buffered. " +
                "Available: A=" + transfer.getTempChunkNo() + ", B=" + transfer.getTempChunkNoB());
        }

        boolean lastChunk = (chunkNo >= transfer.getTotalChunks() - 1);
        return new ChunkDownloadResponse(chunkNo, data, iv, lastChunk);
    }

    /**
     * Acknowledge that the target has downloaded a specific chunk.
     * Clears the corresponding buffer slot and advances state.
     */
    @Transactional
    public TransferResponse acknowledgeChunk(String transferCode) {
        ChunkTransfer transfer = findTransfer(transferCode);

        // Find lowest-numbered filled slot to acknowledge
        int ackChunk = findLowestBufferedChunk(transfer);

        // Clear the slot holding this chunk
        clearSlot(transfer, ackChunk);

        int nextExpected = ackChunk + 1;
        transfer.setCurrentChunkNo(nextExpected);

        if (nextExpected >= transfer.getTotalChunks()) {
            // All chunks delivered
            transfer.setStatus(TransferStatus.COMPLETED);
            // Clear any lingering slot data (safety)
            clearAllSlots(transfer);
            log.info("Transfer completed: {}", transferCode);
        } else if (transfer.getTempChunkData() != null || transfer.getTempChunkDataB() != null) {
            // Another chunk is already buffered; stay CHUNK_READY
            transfer.setStatus(TransferStatus.CHUNK_READY);
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
        clearAllSlots(transfer);
        transferRepo.save(transfer);
        log.info("Transfer cancelled: {}", transferCode);
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(String transferCode) {
        return toResponse(findTransfer(transferCode));
    }

    /**
     * Returns how many buffer slots are currently free for upload.
     */
    public int getFreeSlotCount(ChunkTransfer transfer) {
        int free = 0;
        if (transfer.getTempChunkData() == null) free++;
        if (transfer.getTempChunkDataB() == null) free++;
        return Math.min(free, transfer.getPipelineDepth());
    }

    /**
     * Returns the highest chunk number currently buffered, or -1 if empty.
     */
    public int getHighestBufferedChunk(ChunkTransfer transfer) {
        int highest = -1;
        if (transfer.getTempChunkNo() != null) highest = Math.max(highest, transfer.getTempChunkNo());
        if (transfer.getTempChunkNoB() != null) highest = Math.max(highest, transfer.getTempChunkNoB());
        return highest;
    }

    private int findLowestBufferedChunk(ChunkTransfer transfer) {
        Integer a = transfer.getTempChunkNo();
        Integer b = transfer.getTempChunkNoB();
        if (a != null && b != null) return Math.min(a, b);
        if (a != null) return a;
        if (b != null) return b;
        throw new IllegalStateException("No chunk to acknowledge");
    }

    private void clearSlot(ChunkTransfer transfer, int chunkNo) {
        if (transfer.getTempChunkNo() != null && transfer.getTempChunkNo() == chunkNo) {
            transfer.setTempChunkData(null);
            transfer.setTempChunkIv(null);
            transfer.setTempChunkNo(null);
        } else if (transfer.getTempChunkNoB() != null && transfer.getTempChunkNoB() == chunkNo) {
            transfer.setTempChunkDataB(null);
            transfer.setTempChunkIvB(null);
            transfer.setTempChunkNoB(null);
        }
    }

    private void clearAllSlots(ChunkTransfer transfer) {
        transfer.setTempChunkData(null);
        transfer.setTempChunkIv(null);
        transfer.setTempChunkNo(null);
        transfer.setTempChunkDataB(null);
        transfer.setTempChunkIvB(null);
        transfer.setTempChunkNoB(null);
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
            transfer.getPipelineDepth(),
            appProps.getClipboard().getChunkSizeBytes(),
            appProps.getClipboard().getMinChunkSizeBytes(),
            appProps.getClipboard().getMaxChunkSizeBytes()
        );
    }
}
