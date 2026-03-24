package com.secureclipboard.dto;

public record FileManifestDto(
    String fileCode,
    String encryptedName,
    String encryptedNameIv,
    String encryptedMime,
    String encryptedMimeIv,
    long sizeBytes,
    int chunkSize,
    int totalChunks,
    String sourceDeviceId,
    boolean sourceOnline
) {}
