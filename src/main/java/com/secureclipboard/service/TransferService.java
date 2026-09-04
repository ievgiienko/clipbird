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
import java.util.List;

/**
 * Manages chunk-based file transfers with a simple sequential model.
 * <p>
 * Transfer flow (one chunk at a time):
 *   1. Target requests a chunk from the source via WebSocket.
 *   2. Source encrypts and uploads the chunk to the server (stored in Redis).
 *   3. Target downloads the chunk and sends ACK.
 *   4. Server clears the Redis chunk and advances to the next chunk.
 *   5. Repeat until all chunks are transferred.
 * <p>
 * Temporary chunk data is stored in Redis (not MySQL).
 * Transfer metadata (status, progress) remains in MySQL.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final ChunkTransferRepository transferRepo;
    private final ClipboardFileRepository fileRepo;
    private final ClipboardService clipboardService;
    private final DeviceSessionManager sessionManager;
    private final RedisChunkStore chunkStore;
    private final AppProperties appProps;

    public TransferService(ChunkTransferRepository transferRepo,
                           ClipboardFileRepository fileRepo,
                           ClipboardService clipboardService,
                           DeviceSessionManager sessionManager,
                           RedisChunkStore chunkStore,
                           AppProperties appProps) {
        this.transferRepo = transferRepo;
        this.fileRepo = fileRepo;
        this.clipboardService = clipboardService;
        this.sessionManager = sessionManager;
        this.chunkStore = chunkStore;
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
     * Upload an encrypted chunk into Redis.
     * Binary data is stored directly (no base64 conversion).
     */
    @Transactional
    public void uploadChunk(String transferCode, int chunkNo, byte[] data, String iv, String hash) {
        ChunkTransfer transfer = findTransfer(transferCode);
        validateTransferActive(transfer);

        if (transfer.getTempChunkNo() != null) {
            throw new IllegalStateException("Chunk buffer slot is full. Wait for target to download.");
        }

        chunkStore.store(transferCode, chunkNo, data, iv, hash);
        transfer.setTempChunkNo(chunkNo);
        transfer.setStatus(TransferStatus.CHUNK_READY);
        transferRepo.save(transfer);
        log.debug("Chunk {} uploaded for transfer {}", chunkNo, transferCode);
    }

    /**
     * Download the buffered chunk from Redis.
     * Returns raw encrypted bytes (binary, not base64).
     */
    @Transactional(readOnly = true)
    public ChunkDownloadResponse downloadChunk(String transferCode, int chunkNo) {
        ChunkTransfer transfer = findTransfer(transferCode);

        if (transfer.getTempChunkNo() == null) {
            throw new IllegalStateException("No chunk ready for download");
        }

        if (transfer.getTempChunkNo() != chunkNo) {
            throw new IllegalArgumentException("Requested chunk " + chunkNo + " is not buffered. " +
                "Buffered: " + transfer.getTempChunkNo());
        }

        byte[] data = chunkStore.getData(transferCode, chunkNo);
        if (data == null) {
            throw new IllegalStateException("Chunk data not found in store");
        }

        String iv = chunkStore.getIv(transferCode, chunkNo);
        String hash = chunkStore.getHash(transferCode, chunkNo);
        boolean lastChunk = (chunkNo >= transfer.getTotalChunks() - 1);
        return new ChunkDownloadResponse(chunkNo, data, iv, hash, lastChunk);
    }

    /**
     * Acknowledge that the target has downloaded the current chunk.
     * Clears the Redis chunk and advances state.
     */
    @Transactional
    public TransferResponse acknowledgeChunk(String transferCode) {
        ChunkTransfer transfer = findTransfer(transferCode);

        int ackedChunk = transfer.getTempChunkNo() != null ? transfer.getTempChunkNo() : transfer.getCurrentChunkNo();

        // Clear chunk from Redis
        chunkStore.delete(transferCode, ackedChunk);
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
        transfer.setTempChunkNo(null);
        transferRepo.save(transfer);

        // Clean up any remaining chunks in Redis
        chunkStore.deleteAll(transferCode);
        log.info("Transfer cancelled: {}", transferCode);
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(String transferCode) {
        return toResponse(findTransfer(transferCode));
    }

    /**
     * Cancel all active transfers where the given device is the source.
     * Called when a device disconnects or goes offline.
     * Returns the list of cancelled transfers for notification purposes.
     */
    @Transactional
    public List<TransferResponse> failTransfersBySourceDevice(String sourceDeviceId) {
        List<TransferStatus> activeStatuses = List.of(
            TransferStatus.PENDING, TransferStatus.AWAITING_CHUNK,
            TransferStatus.CHUNK_READY, TransferStatus.IN_PROGRESS
        );
        List<ChunkTransfer> transfers = transferRepo.findBySourceDeviceIdAndStatusIn(sourceDeviceId, activeStatuses);
        return transfers.stream().map(t -> {
            t.setStatus(TransferStatus.CANCELLED);
            t.setTempChunkNo(null);
            transferRepo.save(t);
            chunkStore.deleteAll(t.getTransferCode());
            log.info("Transfer {} cancelled: source device {} went offline", t.getTransferCode(), sourceDeviceId);
            return toResponse(t);
        }).toList();
    }

    /**
     * Cancel all active transfers for a specific file.
     * Called when a file is removed by its owner.
     * Returns the list of cancelled transfers for notification purposes.
     */
    @Transactional
    public List<TransferResponse> failTransfersByFile(String fileCode) {
        List<TransferStatus> activeStatuses = List.of(
            TransferStatus.PENDING, TransferStatus.AWAITING_CHUNK,
            TransferStatus.CHUNK_READY, TransferStatus.IN_PROGRESS
        );
        List<ChunkTransfer> transfers = transferRepo.findByFileFileCodeAndStatusIn(fileCode, activeStatuses);
        return transfers.stream().map(t -> {
            t.setStatus(TransferStatus.CANCELLED);
            t.setTempChunkNo(null);
            transferRepo.save(t);
            chunkStore.deleteAll(t.getTransferCode());
            log.info("Transfer {} cancelled: file {} was removed", t.getTransferCode(), fileCode);
            return toResponse(t);
        }).toList();
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
