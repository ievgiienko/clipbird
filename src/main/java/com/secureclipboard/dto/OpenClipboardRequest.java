package com.secureclipboard.dto;

import jakarta.validation.constraints.NotBlank;

public record OpenClipboardRequest(
    @NotBlank String passwordHash
) {}
