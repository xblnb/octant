package com.octant.pipeline.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ExportVersions {

    public static final String SPEC_VERSION_PROPERTY = "octant.privacy.specVersion";
    public static final String SPEC_VERSION_FILE_PROPERTY = "octant.privacy.specVersionFile";

    public static final String SPEC_VERSION_UNVERIFIED = "privacy-spec@UNVERIFIED";

    public enum Source {
        INJECTED,
        FILE,
        FALLBACK
    }

    private ExportVersions() {
    }

    public static String privacySpecVersion() {
        Source src = source();
        return src == Source.FALLBACK ? SPEC_VERSION_UNVERIFIED : resolvedValue();
    }

    public static Source source() {
        if (!System.getProperty(SPEC_VERSION_PROPERTY, "").trim().isEmpty()) {
            return Source.INJECTED;
        }
        String file = System.getProperty(SPEC_VERSION_FILE_PROPERTY, "");
        if (!file.isEmpty() && readVersionFile(Path.of(file)) != null) {
            return Source.FILE;
        }
        return Source.FALLBACK;
    }

    public static boolean isUnverified() {
        return source() == Source.FALLBACK;
    }

    public static String sourceLabel() {
        return source().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String resolvedValue() {
        String injected = System.getProperty(SPEC_VERSION_PROPERTY, "").trim();
        if (!injected.isEmpty()) {
            return injected;
        }
        String file = System.getProperty(SPEC_VERSION_FILE_PROPERTY, "");
        if (!file.isEmpty()) {
            String fromFile = readVersionFile(Path.of(file));
            if (fromFile != null) {
                return fromFile;
            }
        }
        return SPEC_VERSION_UNVERIFIED;
    }

    public static String readVersionFile(Path path) {
        try {
            if (path == null || !Files.isReadable(path)) {
                return null;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }
}
