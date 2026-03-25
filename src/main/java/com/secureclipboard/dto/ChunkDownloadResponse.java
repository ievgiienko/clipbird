package com.secureclipboard.dto;

/**
 * Internal DTO for chunk download data.
 * Data is raw encrypted bytes (not base64).
 */
public record ChunkDownloadResponse(
    int chunkNo,
    byte[] data,
    String iv,
    boolean lastChunk
) {}
