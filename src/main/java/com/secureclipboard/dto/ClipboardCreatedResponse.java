package com.secureclipboard.dto;

public record ClipboardCreatedResponse(
    String clipboardCode,
    String salt,
    String kdfAlgorithm,
    int kdfIterations,
    String expiresAt
) {}
