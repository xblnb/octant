package com.octant.common.privacy.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ConsentStore {

    private static final Logger LOG = Logger.getLogger(ConsentStore.class.getName());

    private final Path privacyFile;
    private final Path mirrorFile;

    public ConsentStore(Path gameDir, Path worldDir) {
        this.privacyFile = ConsentLedger.consentDirOf(gameDir).resolve("privacy.json");
        this.mirrorFile = com.octant.common.privacy.OctantPaths.dataDir(worldDir).resolve("meta").resolve("consent.json");
    }

    public Path privacyFile() {
        return privacyFile;
    }

    public Path mirrorFile() {
        return mirrorFile;
    }

    public ConsentState load() {
        if (!Files.isRegularFile(privacyFile)) {
            return ConsentState.denied();
        }
        try {
            String text = Files.readString(privacyFile, StandardCharsets.UTF_8);
            return ConsentState.fromJson(text);
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.WARNING, "同意状态不可读或非法，按 fail-closed 处理为「不采集」", ex);
            return ConsentState.denied();
        }
    }

    public void save(ConsentState state) throws IOException {
        Files.createDirectories(privacyFile.getParent());
        writeAtomically(privacyFile, state.toJson());
        try {
            Files.createDirectories(mirrorFile.getParent());
            writeAtomically(mirrorFile, state.toJson());
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "同意状态镜像写入失败（权威状态已保存）", ex);
        }
    }

    public static void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
