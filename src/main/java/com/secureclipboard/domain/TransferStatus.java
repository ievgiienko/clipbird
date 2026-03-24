package com.secureclipboard.domain;

public enum TransferStatus {
    PENDING,
    AWAITING_CHUNK,
    CHUNK_READY,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}
