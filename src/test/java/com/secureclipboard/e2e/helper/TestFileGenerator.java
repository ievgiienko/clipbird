package com.secureclipboard.e2e.helper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates deterministic test files and computes SHA-256 hashes.
 * File content is a repeating byte pattern: byte[i] = (i * 7 + 13) & 0xFF.
 * This ensures reproducible content for any given size.
 */
public final class TestFileGenerator {

    private static final int WRITE_BUFFER_SIZE = 65536;
    private static final int READ_BUFFER_SIZE = 8192;

    private TestFileGenerator() {}

    /**
     * Generate a file with deterministic content at the given path.
     * Uses streaming writes to handle large files without excessive memory allocation.
     */
    public static Path generate(Path dir, String name, int sizeBytes) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[Math.min(sizeBytes, WRITE_BUFFER_SIZE)];
            int written = 0;
            while (written < sizeBytes) {
                int toWrite = Math.min(buffer.length, sizeBytes - written);
                for (int i = 0; i < toWrite; i++) {
                    buffer[i] = (byte) (((written + i) * 7 + 13) & 0xFF);
                }
                out.write(buffer, 0, toWrite);
                written += toWrite;
            }
        }
        return file;
    }

    /**
     * Compute SHA-256 hex digest of a file using streaming reads.
     */
    public static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var is = Files.newInputStream(file)) {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return bytesToHex(digest.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
