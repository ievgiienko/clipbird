package com.secureclipboard.util;

import java.security.SecureRandom;
import java.util.Base64;

public final class CodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    private CodeGenerator() {}

    public static String generateClipboardCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    public static String generateSalt() {
        byte[] salt = new byte[32];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String generateUUID() {
        return java.util.UUID.randomUUID().toString();
    }
}
