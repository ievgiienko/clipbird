package com.secureclipboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChunkUploadRequest(
    @NotNull byte[] data,
    @NotBlank String iv
) {}
