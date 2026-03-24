package com.secureclipboard.dto;

public record ChunkDownloadResponse(
    int chunkNo,
    String data,
    String iv,
    boolean lastChunk
) {}
