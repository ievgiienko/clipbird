package com.secureclipboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record AddFileRequest(
    @NotBlank String encryptedName,
    @NotBlank String encryptedNameIv,
    String encryptedMime,
    String encryptedMimeIv,
    @Positive long sizeBytes,
    @Positive int totalChunks,
    @NotBlank String sourceDeviceId
) {}
