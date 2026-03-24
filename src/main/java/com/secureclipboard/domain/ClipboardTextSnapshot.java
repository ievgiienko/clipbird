package com.secureclipboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "clipboard_text_snapshots")
public class ClipboardTextSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clipboard_id", nullable = false)
    private Clipboard clipboard;

    @Lob
    @Column(columnDefinition = "LONGBLOB")
    private byte[] ciphertext;

    @Column(length = 64)
    private String iv;

    @Column(nullable = false)
    private long version = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_device", length = 64)
    private String updatedByDevice;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Clipboard getClipboard() { return clipboard; }
    public void setClipboard(Clipboard clipboard) { this.clipboard = clipboard; }

    public byte[] getCiphertext() { return ciphertext; }
    public void setCiphertext(byte[] ciphertext) { this.ciphertext = ciphertext; }

    public String getIv() { return iv; }
    public void setIv(String iv) { this.iv = iv; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedByDevice() { return updatedByDevice; }
    public void setUpdatedByDevice(String updatedByDevice) { this.updatedByDevice = updatedByDevice; }
}
