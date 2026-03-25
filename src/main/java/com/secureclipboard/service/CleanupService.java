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
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    private final ClipboardRepository clipboardRepo;
    private final ClipboardTextSnapshotRepository textRepo;
    private final ClipboardFileRepository fileRepo;
    private final ChunkTransferRepository transferRepo;
    private final DeviceSessionManager sessionManager;
    private final AppProperties appProps;

    public CleanupService(ClipboardRepository clipboardRepo,
                          ClipboardTextSnapshotRepository textRepo,
                          ClipboardFileRepository fileRepo,
                          ChunkTransferRepository transferRepo,
                          DeviceSessionManager sessionManager,
                          AppProperties appProps) {
        this.clipboardRepo = clipboardRepo;
        this.textRepo = textRepo;
        this.fileRepo = fileRepo;
        this.transferRepo = transferRepo;
        this.sessionManager = sessionManager;
        this.appProps = appProps;
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
    public void purgeDeletedClipboards() {
        if (!appProps.getCleanup().isHardDeleteEnabled()) {
            return;
        }

        int delayHours = appProps.getCleanup().getHardDeleteDelayHours();
        Instant cutoff = Instant.now().minus(delayHours, ChronoUnit.HOURS);

        List<Clipboard> eligible = clipboardRepo.findByStatusInAndExpiresAtBefore(
            List.of(ClipboardStatus.DELETED, ClipboardStatus.EXPIRED), cutoff);

        if (eligible.isEmpty()) {
            return;
        }

        log.info("Hard-delete cleanup: found {} eligible clipboards", eligible.size());

        int purged = 0;
        for (Clipboard clipboard : eligible) {
            try {
                Long clipId = clipboard.getId();
                String code = clipboard.getClipboardCode();
                // Remove any remaining child records (safety net; normally already cleaned up)
                transferRepo.deleteByClipboardId(clipId);
                fileRepo.deleteByClipboardId(clipId);
                textRepo.deleteByClipboardId(clipId);
                clipboardRepo.delete(clipboard);
                purged++;
                log.debug("Hard-deleted clipboard: {}", code);
            } catch (Exception e) {
                log.warn("Failed to hard-delete clipboard id={}: {}", clipboard.getId(), e.getMessage());
            }
        }

        log.info("Hard-delete cleanup: purged {}/{} clipboards", purged, eligible.size());
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
            transfer.setTempChunkNo(null);
            transferRepo.save(transfer);
        }
    }
}
