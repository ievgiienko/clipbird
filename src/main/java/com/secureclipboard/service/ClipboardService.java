package com.secureclipboard.service;

import com.secureclipboard.config.AppProperties;
import com.secureclipboard.domain.*;
import com.secureclipboard.dto.*;
import com.secureclipboard.repository.*;
import com.secureclipboard.util.CodeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

@Service
public class ClipboardService {

    private static final Logger log = LoggerFactory.getLogger(ClipboardService.class);

    private final ClipboardRepository clipboardRepo;
    private final ClipboardTextSnapshotRepository textRepo;
    private final ClipboardFileRepository fileRepo;
    private final ChunkTransferRepository transferRepo;
    private final DeviceSessionManager sessionManager;
    private final AppProperties appProps;

    public ClipboardService(ClipboardRepository clipboardRepo,
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

    private static final List<Integer> ALLOWED_TTL_HOURS = List.of(1, 24, 168);

    @Transactional
    public ClipboardCreatedResponse createClipboard(CreateClipboardRequest request, User owner) {
        String code;
        int attempts = 0;
        do {
            code = CodeGenerator.generateClipboardCode(appProps.getClipboard().getIdLength());
            attempts++;
            if (attempts > 100) {
                throw new RuntimeException("Failed to generate unique clipboard code");
            }
        } while (clipboardRepo.existsByClipboardCode(code));

        String salt = CodeGenerator.generateSalt();
        int ttlHours = resolveTtl(request);
        Instant expiresAt = Instant.now().plus(ttlHours, ChronoUnit.HOURS);

        Clipboard clipboard = new Clipboard();
        clipboard.setClipboardCode(code);
        clipboard.setSalt(salt);
        clipboard.setPasswordHash(""); // Will be set in second phase
        clipboard.setKdfAlgorithm("PBKDF2");
        clipboard.setKdfIterations(600000);
        clipboard.setExpiresAt(expiresAt);
        clipboard.setStatus(ClipboardStatus.ACTIVE);
        clipboard.setOwner(owner);
        clipboardRepo.save(clipboard);

        // Create empty text snapshot
        ClipboardTextSnapshot snapshot = new ClipboardTextSnapshot();
        snapshot.setClipboard(clipboard);
        snapshot.setVersion(0);
        textRepo.save(snapshot);

        log.info("Created clipboard: {}", code);
        return new ClipboardCreatedResponse(code, salt, "PBKDF2", 600000, expiresAt.toString());
    }

    @Transactional
    public void setPasswordHash(String clipboardCode, String passwordHash) {
        Clipboard clipboard = findActiveClipboard(clipboardCode);
        if (!clipboard.getPasswordHash().isEmpty()) {
            throw new IllegalStateException("Password already set for this clipboard");
        }
        clipboard.setPasswordHash(passwordHash);
        clipboardRepo.save(clipboard);
    }

    @Transactional(readOnly = true)
    public HandshakeResponse getHandshake(String clipboardCode) {
        Clipboard clipboard = findActiveClipboard(clipboardCode);
        return new HandshakeResponse(
            clipboard.getClipboardCode(),
            clipboard.getSalt(),
            clipboard.getKdfAlgorithm(),
            clipboard.getKdfIterations(),
            clipboard.getStatus().name(),
            clipboard.getExpiresAt().toString()
        );
    }

    @Transactional(readOnly = true)
    public boolean verifyPassword(String clipboardCode, String passwordHash) {
        Clipboard clipboard = findActiveClipboard(clipboardCode);
        return clipboard.getPasswordHash().equals(passwordHash);
    }

    @Transactional(readOnly = true)
    public ClipboardStateResponse getState(String clipboardCode) {
        Clipboard clipboard = findActiveClipboard(clipboardCode);

        TextSnapshotDto textDto = null;
        ClipboardTextSnapshot snapshot = textRepo.findByClipboardId(clipboard.getId()).orElse(null);
        if (snapshot != null && snapshot.getCiphertext() != null) {
            textDto = new TextSnapshotDto(
                Base64.getEncoder().encodeToString(snapshot.getCiphertext()),
                snapshot.getIv(),
                snapshot.getVersion()
            );
        }

        List<ClipboardFile> files = fileRepo.findByClipboardIdAndStatus(clipboard.getId(), FileStatus.AVAILABLE);
        List<FileManifestDto> fileDtos = files.stream().map(f -> new FileManifestDto(
            f.getFileCode(),
            f.getEncryptedName(),
            f.getEncryptedNameIv(),
            f.getEncryptedMime(),
            f.getEncryptedMimeIv(),
            f.getSizeBytes(),
            f.getChunkSize(),
            f.getTotalChunks(),
            f.getSourceDeviceId(),
            sessionManager.isDeviceOnline(clipboardCode, f.getSourceDeviceId()),
            f.getFileHash(),
            f.getEncryptedPreview(),
            f.getEncryptedPreviewIv()
        )).toList();

        return new ClipboardStateResponse(
            clipboardCode,
            textDto,
            fileDtos,
            sessionManager.getConnectedCount(clipboardCode)
        );
    }

    @Transactional
    public TextSnapshotDto updateText(String clipboardCode, byte[] ciphertext, String iv,
                                       long expectedVersion, String deviceId) {
        Clipboard clipboard = findActiveClipboard(clipboardCode);
        ClipboardTextSnapshot snapshot = textRepo.findByClipboardId(clipboard.getId())
            .orElseGet(() -> {
                ClipboardTextSnapshot s = new ClipboardTextSnapshot();
                s.setClipboard(clipboard);
                s.setVersion(0);
                return s;
            });

        if (expectedVersion >= 0 && snapshot.getVersion() > expectedVersion) {
            // Return current version for conflict resolution (last-write-wins with version check)
            log.debug("Version conflict for clipboard {}: expected={}, actual={}",
                clipboardCode, expectedVersion, snapshot.getVersion());
        }

        long newVersion = snapshot.getVersion() + 1;
        snapshot.setCiphertext(ciphertext);
        snapshot.setIv(iv);
        snapshot.setVersion(newVersion);
        snapshot.setUpdatedByDevice(deviceId);
        textRepo.save(snapshot);

        return new TextSnapshotDto(
            Base64.getEncoder().encodeToString(ciphertext),
            iv,
            newVersion
        );
    }

    @Transactional
    public FileManifestDto addFile(String clipboardCode, AddFileRequest request) {
        Clipboard clipboard = findActiveClipboard(clipboardCode);

        if (request.sizeBytes() > appProps.getClipboard().getMaxFileSizeBytes()) {
            throw new IllegalArgumentException("File exceeds maximum allowed size");
        }

        ClipboardFile file = new ClipboardFile();
        file.setClipboard(clipboard);
        file.setFileCode(CodeGenerator.generateUUID());
        file.setEncryptedName(request.encryptedName());
        file.setEncryptedNameIv(request.encryptedNameIv());
        file.setEncryptedMime(request.encryptedMime());
        file.setEncryptedMimeIv(request.encryptedMimeIv());
        file.setSizeBytes(request.sizeBytes());
        file.setChunkSize(appProps.getClipboard().getChunkSizeBytes());
        file.setTotalChunks(request.totalChunks());
        file.setSourceDeviceId(request.sourceDeviceId());
        file.setFileHash(request.fileHash());
        file.setEncryptedPreview(request.encryptedPreview());
        file.setEncryptedPreviewIv(request.encryptedPreviewIv());
        file.setStatus(FileStatus.AVAILABLE);
        fileRepo.save(file);

        log.info("File added to clipboard {}: {}", clipboardCode, file.getFileCode());
        return new FileManifestDto(
            file.getFileCode(),
            file.getEncryptedName(),
            file.getEncryptedNameIv(),
            file.getEncryptedMime(),
            file.getEncryptedMimeIv(),
            file.getSizeBytes(),
            file.getChunkSize(),
            file.getTotalChunks(),
            file.getSourceDeviceId(),
            sessionManager.isDeviceOnline(clipboardCode, file.getSourceDeviceId()),
            file.getFileHash(),
            file.getEncryptedPreview(),
            file.getEncryptedPreviewIv()
        );
    }

    @Transactional
    public void removeFile(String clipboardCode, String fileCode) {
        ClipboardFile file = fileRepo.findByFileCode(fileCode)
            .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileCode));
        file.setStatus(FileStatus.REMOVED);
        fileRepo.save(file);
        log.info("File removed from clipboard {}: {}", clipboardCode, fileCode);
    }

    @Transactional
    public void deleteClipboard(String clipboardCode, User requestingUser) {
        Clipboard clipboard = findActiveClipboard(clipboardCode);
        User owner = clipboard.getOwner();
        if (owner != null && (requestingUser == null || !owner.getId().equals(requestingUser.getId()))) {
            throw new AccessDeniedException("You do not own this clipboard");
        }
        doDelete(clipboard);
    }

    @Transactional(readOnly = true)
    public Page<Clipboard> getClipboardsForUser(User user, Pageable pageable) {
        return clipboardRepo.findByOwnerAndStatusNot(user, ClipboardStatus.DELETED, pageable);
    }

    // For system-initiated deletes (WebSocket close) — no ownership check.
    @Transactional
    public void deleteClipboard(String clipboardCode) {
        doDelete(findActiveClipboard(clipboardCode));
    }

    private void doDelete(Clipboard clipboard) {
        Long clipId = clipboard.getId();
        String code = clipboard.getClipboardCode();
        clipboard.setTextSnapshot(null);
        clipboard.setStatus(ClipboardStatus.DELETED);
        clipboardRepo.saveAndFlush(clipboard);
        transferRepo.deleteByClipboardId(clipId);
        fileRepo.deleteByClipboardId(clipId);
        textRepo.deleteByClipboardId(clipId);
        sessionManager.removeRoom(code);
        log.info("Clipboard deleted: {}", code);
    }

    private int resolveTtl(CreateClipboardRequest request) {
        if (request == null || request.ttlHours() == null) {
            return appProps.getClipboard().getTtlHours();
        }
        int ttl = request.ttlHours();
        if (!ALLOWED_TTL_HOURS.contains(ttl)) {
            throw new IllegalArgumentException("Invalid TTL. Allowed values: 1, 24, 168");
        }
        return ttl;
    }

    public Clipboard findActiveClipboard(String clipboardCode) {
        return clipboardRepo.findByClipboardCodeAndStatus(clipboardCode, ClipboardStatus.ACTIVE)
            .orElseThrow(() -> new IllegalArgumentException("Clipboard not found or inactive: " + clipboardCode));
    }
}
