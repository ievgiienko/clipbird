package com.secureclipboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "clipboards")
public class Clipboard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clipboard_code", nullable = false, unique = true, length = 12)
    private String clipboardCode;

    @Column(nullable = false, length = 64)
    private String salt;

    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Column(name = "kdf_algorithm", nullable = false, length = 20)
    private String kdfAlgorithm = "PBKDF2";

    @Column(name = "kdf_iterations", nullable = false)
    private int kdfIterations = 600000;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ClipboardStatus status = ClipboardStatus.ACTIVE;

    @OneToOne(mappedBy = "clipboard", fetch = FetchType.LAZY)
    private ClipboardTextSnapshot textSnapshot;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClipboardCode() { return clipboardCode; }
    public void setClipboardCode(String clipboardCode) { this.clipboardCode = clipboardCode; }

    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getKdfAlgorithm() { return kdfAlgorithm; }
    public void setKdfAlgorithm(String kdfAlgorithm) { this.kdfAlgorithm = kdfAlgorithm; }

    public int getKdfIterations() { return kdfIterations; }
    public void setKdfIterations(int kdfIterations) { this.kdfIterations = kdfIterations; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public ClipboardStatus getStatus() { return status; }
    public void setStatus(ClipboardStatus status) { this.status = status; }

    public ClipboardTextSnapshot getTextSnapshot() { return textSnapshot; }
    public void setTextSnapshot(ClipboardTextSnapshot textSnapshot) { this.textSnapshot = textSnapshot; }
}
