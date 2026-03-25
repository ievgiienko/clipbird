package com.secureclipboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chunk_transfers")
public class ChunkTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_code", nullable = false, unique = true, length = 36)
    private String transferCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clipboard_id", nullable = false)
    private Clipboard clipboard;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private ClipboardFile file;

    @Column(name = "source_device_id", nullable = false, length = 64)
    private String sourceDeviceId;

    @Column(name = "target_device_id", nullable = false, length = 64)
    private String targetDeviceId;

    @Column(name = "current_chunk_no", nullable = false)
    private int currentChunkNo = 0;

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Column(name = "pipeline_depth", nullable = false)
    private int pipelineDepth = 1;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TransferStatus status = TransferStatus.PENDING;

    // --- Double-buffer: slot A ---
    @Lob
    @Column(name = "temp_chunk_data", columnDefinition = "LONGBLOB")
    private byte[] tempChunkData;

    @Column(name = "temp_chunk_iv", length = 64)
    private String tempChunkIv;

    @Column(name = "temp_chunk_no")
    private Integer tempChunkNo;

    // --- Double-buffer: slot B ---
    @Lob
    @Column(name = "temp_chunk_data_b", columnDefinition = "LONGBLOB")
    private byte[] tempChunkDataB;

    @Column(name = "temp_chunk_iv_b", length = 64)
    private String tempChunkIvB;

    @Column(name = "temp_chunk_no_b")
    private Integer tempChunkNoB;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTransferCode() { return transferCode; }
    public void setTransferCode(String transferCode) { this.transferCode = transferCode; }

    public Clipboard getClipboard() { return clipboard; }
    public void setClipboard(Clipboard clipboard) { this.clipboard = clipboard; }

    public ClipboardFile getFile() { return file; }
    public void setFile(ClipboardFile file) { this.file = file; }

    public String getSourceDeviceId() { return sourceDeviceId; }
    public void setSourceDeviceId(String sourceDeviceId) { this.sourceDeviceId = sourceDeviceId; }

    public String getTargetDeviceId() { return targetDeviceId; }
    public void setTargetDeviceId(String targetDeviceId) { this.targetDeviceId = targetDeviceId; }

    public int getCurrentChunkNo() { return currentChunkNo; }
    public void setCurrentChunkNo(int currentChunkNo) { this.currentChunkNo = currentChunkNo; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public int getPipelineDepth() { return pipelineDepth; }
    public void setPipelineDepth(int pipelineDepth) { this.pipelineDepth = pipelineDepth; }

    public TransferStatus getStatus() { return status; }
    public void setStatus(TransferStatus status) { this.status = status; }

    public byte[] getTempChunkData() { return tempChunkData; }
    public void setTempChunkData(byte[] tempChunkData) { this.tempChunkData = tempChunkData; }

    public String getTempChunkIv() { return tempChunkIv; }
    public void setTempChunkIv(String tempChunkIv) { this.tempChunkIv = tempChunkIv; }

    public Integer getTempChunkNo() { return tempChunkNo; }
    public void setTempChunkNo(Integer tempChunkNo) { this.tempChunkNo = tempChunkNo; }

    public byte[] getTempChunkDataB() { return tempChunkDataB; }
    public void setTempChunkDataB(byte[] tempChunkDataB) { this.tempChunkDataB = tempChunkDataB; }

    public String getTempChunkIvB() { return tempChunkIvB; }
    public void setTempChunkIvB(String tempChunkIvB) { this.tempChunkIvB = tempChunkIvB; }

    public Integer getTempChunkNoB() { return tempChunkNoB; }
    public void setTempChunkNoB(Integer tempChunkNoB) { this.tempChunkNoB = tempChunkNoB; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
