package com.octant.pipeline.kite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class KiteSpecRef {

    private KiteSpecRef() {
    }

    static final String PATH = "docs/requirements/requirements-behavior-kite-spec.md";
    static final long EXPECTED_BYTES = 92_357L;
    static final String EXPECTED_SHA256 =
            "CF8188FC38D08C4286EE5C29433843460935E479688FBFD846055D981E329552";
    static final String EXPECTED_SHA16 = "CF8188FC38D08C42";
    static final int EXPECTED_LF_LINES = 657;
    static final int EXPECTED_CRLF = 0;
    static final String EXPECTED_VERSION = "1.2.0";

    static String normalized() {
        return "bytes=" + EXPECTED_BYTES + " sha16=" + EXPECTED_SHA16 + " sha256=" + EXPECTED_SHA256
                + " LF=" + EXPECTED_LF_LINES + " CRLF=" + EXPECTED_CRLF + " v" + EXPECTED_VERSION;
    }

    static Measured measure(Path workingDir) {
        Path p = workingDir == null ? Path.of(PATH) : workingDir.resolve(PATH);
        try {
            if (!Files.isRegularFile(p)) {
                return null;
            }
            byte[] b = Files.readAllBytes(p);
            return new Measured(b.length, Fingerprint.sha256(b), Fingerprint.lineCount(b),
                    Fingerprint.countCrlf(b), p.toString());
        } catch (IOException e) {
            return null;
        }
    }

    record Measured(long bytes, String sha256, int lfLines, int crlf, String path) {
        String sha16() {
            return Fingerprint.sha16(sha256);
        }

        boolean matchesFrozen() {
            return bytes == EXPECTED_BYTES && sha256.equals(EXPECTED_SHA256)
                    && lfLines == EXPECTED_LF_LINES && crlf == EXPECTED_CRLF;
        }

        String dump() {
            return "bytes=" + bytes + " sha16=" + sha16() + " sha256=" + sha256
                    + " LF=" + lfLines + " CRLF=" + crlf + " path=" + path;
        }
    }

    static String readAll(Path workingDir) {
        Path p = workingDir == null ? Path.of(PATH) : workingDir.resolve(PATH);
        try {
            if (!Files.isRegularFile(p)) {
                return null;
            }
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
