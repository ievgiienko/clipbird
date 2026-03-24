package com.secureclipboard.service;

import com.secureclipboard.config.AppProperties;
import com.secureclipboard.domain.*;
import com.secureclipboard.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    private final ClipboardRepository clipboardRepo;
    private final ClipboardTextSnapshotRepository textRepo;
    private final ClipboardFileRepository fileRepo;
    private final ChunkTransferRepository transferRepo;
    private final DeviceSessionManager sessionManager;

    public CleanupService(ClipboardRepository clipboardRepo,
                          ClipboardTextSnapshotRepository textRepo,
                          ClipboardFileRepository fileRepo,
                          ChunkTransferRepository transferRepo,
                          DeviceSessionManager sessionManager) {
        this.clipboardRepo = clipboardRepo;
        this.textRepo = textRepo;
        this.fileRepo = fileRepo;
        this.transferRepo = transferRepo;
        this.sessionManager = sessionManager;
    }

    @Scheduled(fixedDelayString = "${app.cleanup.interval-seconds:300}000")
    @Transactional
    public void cleanupExpiredClipboards() {
        List<Clipboard> expired = clipboardRepo.findByStatusAndExpiresAtBefore(
            ClipboardStatus.ACTIVE, Instant.now());

        for (Clipboard clipboard : expired) {
            log.info("Cleaning up expired clipboard: {}", clipboard.getClipboardCode());
            Long clipId = clipboard.getId();
            clipboard.setTextSnapshot(null);
            clipboard.setStatus(ClipboardStatus.EXPIRED);
            clipboardRepo.saveAndFlush(clipboard);
            transferRepo.deleteByClipboardId(clipId);
            fileRepo.deleteByClipboardId(clipId);
            textRepo.deleteByClipboardId(clipId);
            sessionManager.removeRoom(clipboard.getClipboardCode());
        }

        if (!expired.isEmpty()) {
            log.info("Cleaned up {} expired clipboards", expired.size());
        }
    }

    @Scheduled(fixedDelayString = "${app.cleanup.interval-seconds:300}000")
    @Transactional
    public void cleanupExpiredTransfers() {
        List<TransferStatus> activeStatuses = List.of(
            TransferStatus.PENDING, TransferStatus.AWAITING_CHUNK,
            TransferStatus.CHUNK_READY, TransferStatus.IN_PROGRESS
        );

        List<ChunkTransfer> expired = transferRepo.findByStatusInAndExpiresAtBefore(
            activeStatuses, Instant.now());

        for (ChunkTransfer transfer : expired) {
            log.info("Cleaning up expired transfer: {}", transfer.getTransferCode());
            transfer.setStatus(TransferStatus.FAILED);
            transfer.setTempChunkData(null);
            transfer.setTempChunkIv(null);
            transferRepo.save(transfer);
        }
    }
}
