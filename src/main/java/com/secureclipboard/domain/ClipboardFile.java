package com.secureclipboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "clipboard_files")
public class ClipboardFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clipboard_id", nullable = false)
    private Clipboard clipboard;

    @Column(name = "file_code", nullable = false, unique = true, length = 36)
    private String fileCode;

    @Column(name = "encrypted_name", nullable = false, columnDefinition = "TEXT")
    private String encryptedName;

    @Column(name = "encrypted_name_iv", nullable = false, length = 64)
    private String encryptedNameIv;

    @Column(name = "encrypted_mime", columnDefinition = "TEXT")
    private String encryptedMime;

    @Column(name = "encrypted_mime_iv", length = 64)
    private String encryptedMimeIv;

    @Column(name = "size_bytes")
    private long sizeBytes;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize = 5242880;

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Column(name = "source_device_id", nullable = false, length = 64)
    private String sourceDeviceId;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "encrypted_preview", columnDefinition = "TEXT")
    private String encryptedPreview;

    @Column(name = "encrypted_preview_iv", length = 64)
    private String encryptedPreviewIv;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private FileStatus status = FileStatus.AVAILABLE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Clipboard getClipboard() { return clipboard; }
    public void setClipboard(Clipboard clipboard) { this.clipboard = clipboard; }

    public String getFileCode() { return fileCode; }
    public void setFileCode(String fileCode) { this.fileCode = fileCode; }

    public String getEncryptedName() { return encryptedName; }
    public void setEncryptedName(String encryptedName) { this.encryptedName = encryptedName; }

    public String getEncryptedNameIv() { return encryptedNameIv; }
    public void setEncryptedNameIv(String encryptedNameIv) { this.encryptedNameIv = encryptedNameIv; }

    public String getEncryptedMime() { return encryptedMime; }
    public void setEncryptedMime(String encryptedMime) { this.encryptedMime = encryptedMime; }

    public String getEncryptedMimeIv() { return encryptedMimeIv; }
    public void setEncryptedMimeIv(String encryptedMimeIv) { this.encryptedMimeIv = encryptedMimeIv; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public String getSourceDeviceId() { return sourceDeviceId; }
    public void setSourceDeviceId(String sourceDeviceId) { this.sourceDeviceId = sourceDeviceId; }

    public FileStatus getStatus() { return status; }
    public void setStatus(FileStatus status) { this.status = status; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public String getEncryptedPreview() { return encryptedPreview; }
    public void setEncryptedPreview(String encryptedPreview) { this.encryptedPreview = encryptedPreview; }

    public String getEncryptedPreviewIv() { return encryptedPreviewIv; }
    public void setEncryptedPreviewIv(String encryptedPreviewIv) { this.encryptedPreviewIv = encryptedPreviewIv; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
