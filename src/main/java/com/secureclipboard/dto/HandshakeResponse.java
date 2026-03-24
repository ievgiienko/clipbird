package com.secureclipboard.dto;

public record HandshakeResponse(
    String clipboardCode,
    String salt,
    String kdfAlgorithm,
    int kdfIterations,
    String status,
    String expiresAt
) {}
