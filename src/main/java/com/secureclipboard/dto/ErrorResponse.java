package com.secureclipboard.dto;

import java.time.Instant;

public record ErrorResponse(
    int status,
    String error,
    String message,
    String timestamp
) {
    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, Instant.now().toString());
    }
}
