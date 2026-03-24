package com.secureclipboard.dto;

public record TextSnapshotDto(
    String ciphertext,
    String iv,
    long version
) {}
