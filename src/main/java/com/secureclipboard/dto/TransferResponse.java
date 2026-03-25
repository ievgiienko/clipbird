package com.secureclipboard.dto;

public record TransferResponse(
    String transferCode,
    String fileCode,
    String sourceDeviceId,
    String targetDeviceId,
    int totalChunks,
    int currentChunkNo,
    String status,
    int defaultChunkSize,
    int minChunkSize,
    int maxChunkSize
) {}
