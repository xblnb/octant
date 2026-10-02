package com.octant.common.privacy;

import java.nio.file.Files;
import java.nio.file.Path;

public final class OctantPaths {

    public static final String LEGACY_NAME = "mcinsight";
    public static final String DATA_NAME = "octant";

    private OctantPaths() {
    }

    public static Path dataDir(Path base) {
        if (base == null) {
            return null;
        }
        migrateLegacy(base);
        return base.resolve(DATA_NAME);
    }

    public static void migrateLegacy(Path base) {
        if (base == null) {
            return;
        }
        try {
            Path oldDir = base.resolve(LEGACY_NAME);
            Path newDir = base.resolve(DATA_NAME);
            if (Files.isDirectory(oldDir) && !Files.exists(newDir)) {
                Files.move(oldDir, newDir);
            }
        } catch (Exception ignored) {
        }
    }
}
