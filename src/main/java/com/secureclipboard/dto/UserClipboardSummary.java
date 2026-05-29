package com.secureclipboard.dto;

import java.time.Instant;

public record UserClipboardSummary(
    String clipboardCode,
    Instant createdAt,
    Instant expiresAt,
    String status
) {}
