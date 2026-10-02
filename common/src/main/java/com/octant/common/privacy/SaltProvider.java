package com.octant.common.privacy;

import com.octant.common.model.ContractException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

public final class SaltProvider {

    public static final int SALT_BYTES = 32;

    public static final int TRUNCATE_BITS = 64;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] salt;

    private SaltProvider(byte[] salt) {
        this.salt = salt.clone();
    }

    public static SaltProvider generate() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return new SaltProvider(salt);
    }

    public static SaltProvider of(byte[] salt) {
        if (salt == null || salt.length < 16) {
            throw new ContractException("假名盐过短或为空（拒绝使用弱盐）");
        }
        return new SaltProvider(salt);
    }

    public static SaltProvider loadOrCreate(Path saltFile) throws IOException {
        if (Files.isRegularFile(saltFile)) {
            return of(Files.readAllBytes(saltFile));
        }
        SaltProvider provider = generate();
        provider.persist(saltFile);
        return provider;
    }

    public void persist(Path saltFile) throws IOException {
        Files.createDirectories(saltFile.getParent());
        Path tmp = saltFile.resolveSibling(saltFile.getFileName() + ".tmp");
        Files.write(tmp, salt);
        try {
            Files.move(tmp, saltFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp, saltFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void overwriteAndDelete(Path saltFile) throws IOException {
        if (!Files.isRegularFile(saltFile)) {
            return;
        }
        long size = Files.size(saltFile);
        byte[] zeros = new byte[(int) Math.max(1, Math.min(size, 1 << 16))];
        Files.write(saltFile, zeros);
        Files.deleteIfExists(saltFile);
    }

    public String pseudonymize(String canonicalPlayerId) {
        if (canonicalPlayerId == null || canonicalPlayerId.isBlank()) {
            throw new ContractException("canonicalPlayerId 不得为空");
        }
        return truncateHex(hmacSha256(salt, canonicalPlayerId.getBytes(StandardCharsets.UTF_8)));
    }

    public String pseudonym(String purpose, String value) {
        if (purpose == null || purpose.isBlank()) {
            throw new ContractException("purpose 不得为空（用途分离是强制项）");
        }
        if (value == null || value.isBlank()) {
            throw new ContractException("value 不得为空");
        }
        byte[] purposeKey = hmacSha256(salt, ("purpose:" + purpose).getBytes(StandardCharsets.UTF_8));
        byte[] digest = hmacSha256(purposeKey, value.getBytes(StandardCharsets.UTF_8));
        return truncateHex(digest);
    }

    public String saltedRecord(String value, long epochDay) {
        byte[] input = (value + "||" + epochDay).getBytes(StandardCharsets.UTF_8);
        return truncateHex(hmacSha256(salt, input));
    }

    public static String canonicalizeUuid(String uuid) {
        if (uuid == null) {
            throw new ContractException("UUID 为 null");
        }
        return uuid.trim().toLowerCase(java.util.Locale.ROOT).replace("-", "");
    }

    public String pseudonymizeUuid(String rawUuid) {
        return pseudonymize(canonicalizeUuid(rawUuid));
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 不可用（不应发生：JDK 必备算法）", ex);
        }
    }

    private static String truncateHex(byte[] digest) {
        int bytes = TRUNCATE_BITS / 8;
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (int i = 0; i < bytes; i++) {
            sb.append(HEX[(digest[i] >> 4) & 0x0F]).append(HEX[digest[i] & 0x0F]);
        }
        return sb.toString();
    }
}
