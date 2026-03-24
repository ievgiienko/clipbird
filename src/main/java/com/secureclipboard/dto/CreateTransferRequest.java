package com.secureclipboard.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateTransferRequest(
    @NotBlank String clipboardCode,
    @NotBlank String fileCode,
    @NotBlank String targetDeviceId
) {}
