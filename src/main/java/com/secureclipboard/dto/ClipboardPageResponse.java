package com.secureclipboard.dto;

import java.util.List;

public record ClipboardPageResponse(
    List<UserClipboardSummary> items,
    int totalPages,
    long totalElements
) {}
