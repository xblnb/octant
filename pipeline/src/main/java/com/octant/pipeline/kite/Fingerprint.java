package com.octant.pipeline.kite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Fingerprint {

    private Fingerprint() {
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase(java.util.Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", e);
        }
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha16(String sha256) {
        return sha256.substring(0, 16);
    }

    static int lineCount(byte[] data) {
        return count(data, (byte) '\n');
    }

    static int count(byte[] data, byte b) {
        int n = 0;
        for (byte x : data) {
            if (x == b) {
                n++;
            }
        }
        return n;
    }

    static int countCrlf(byte[] data) {
        int n = 0;
        for (int i = 1; i < data.length; i++) {
            if (data[i] == '\n' && data[i - 1] == '\r') {
                n++;
            }
        }
        return n;
    }

    static String instrument(java.nio.file.Path classFile) {
        try {
            if (!java.nio.file.Files.isRegularFile(classFile)) {
                return "<不可读:" + classFile + ">";
            }
            return sha16(sha256(java.nio.file.Files.readAllBytes(classFile)));
        } catch (java.io.IOException e) {
            return "<读取失败:" + e.getClass().getSimpleName() + ">";
        }
    }
}
