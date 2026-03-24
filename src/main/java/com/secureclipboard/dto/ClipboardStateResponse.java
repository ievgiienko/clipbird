package com.secureclipboard.dto;

import java.util.List;

public record ClipboardStateResponse(
    String clipboardCode,
    TextSnapshotDto text,
    List<FileManifestDto> files,
    int connectedDevices
) {}
