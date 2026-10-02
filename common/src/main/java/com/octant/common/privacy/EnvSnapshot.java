package com.octant.common.privacy;

import com.octant.common.model.ContractException;
import com.octant.common.model.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class EnvSnapshot {

    public static final String SCHEMA_VERSION =
            com.octant.common.model.RawEventSchema.ENV_SNAPSHOT_SCHEMA_VERSION;

    public static final String DETAIL_DIR = "env";

    public enum EnvAvailability {
        AVAILABLE("available"),
        UNAVAILABLE("unavailable"),
        UNKNOWN("unknown");

        private final String wire;

        EnvAvailability(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static EnvAvailability of(boolean enumerated) {
            return enumerated ? AVAILABLE : UNKNOWN;
        }
    }

    private final PackSnapshot packs;
    private final int rev;

    public EnvSnapshot(PackSnapshot packs, int rev) {
        this.packs = Objects.requireNonNull(packs, "packs");
        if (rev < 1) {
            throw new ContractException("snapshotRev 必须 ≥ 1（契约 EV-S4）：" + rev);
        }
        this.rev = rev;
    }

    public int rev() {
        return rev;
    }

    public String schemaVersion() {
        return SCHEMA_VERSION;
    }

    public int modCount() {
        return packs.modCount();
    }

    public Map<String, Integer> modCountBySourceClass() {
        return packs.modCountBySourceClass();
    }

    public int resourcePackCount() {
        return packs.resourcePackCount();
    }

    public int dataPackCount() {
        return packs.dataPackCount();
    }

    public EnvAvailability resourcePacksAvailable() {
        return EnvAvailability.of(packs.enumerationAvailable());
    }

    public EnvAvailability dataPacksAvailable() {
        return EnvAvailability.of(packs.enumerationAvailable());
    }

    public Map<String, Object> toDetailMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("snapshotRev", rev);
        m.put("envSchemaVersion", SCHEMA_VERSION);
        m.put("modCount", modCount());
        m.put("modCountBySourceClass", new LinkedHashMap<>(modCountBySourceClass()));
        m.put("resourcePackCount", resourcePackCount());
        m.put("dataPackCount", dataPackCount());
        m.put("resourcePacksAvailable", resourcePacksAvailable().wire());
        m.put("dataPacksAvailable", dataPacksAvailable().wire());
        m.put("packSnapshot", packs.toMap());
        return java.util.Collections.unmodifiableMap(m);
    }

    public static Path detailPath(Path worldDir, int rev) {
        return Objects.requireNonNull(worldDir, "worldDir")
                .resolve(com.octant.common.privacy.OctantPaths.DATA_NAME).resolve("meta").resolve(DETAIL_DIR)
                .resolve(rev + ".json");
    }

    public String detailDigest16() {
        return packs.environmentDigest16(canonicalDetailString());
    }

    public String canonicalDetailString() {
        return Json.encode(toDetailMap());
    }

    public Path writeDetail(Path worldDir) throws IOException {
        Path target = detailPath(worldDir, rev);
        Files.createDirectories(target.getParent());
        String canonical = canonicalDetailString();
        if (Files.isRegularFile(target)) {
            String existing = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            if (existing.equals(canonical)) {
                return target;
            }
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, canonical.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    public Map<String, Object> toEventPayload(long observedAtRelMs) {
        long tick = Math.round(observedAtRelMs / 50.0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("envSchemaVersion", SCHEMA_VERSION);
        m.put("snapshotRev", rev);
        m.put("snapshotDigest16", detailDigest16());
        m.put("observedAtRelMs", observedAtRelMs);
        m.put("observedAtTick", tick);
        return m;
    }

    public void verifyDetailReproducible(Path worldDir) {
        Path target = detailPath(worldDir, rev);
        try {
            String onDisk = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            Map<String, Object> parsed = Json.decodeObject(onDisk);
            String reencoded = Json.encode(parsed);
            if (!reencoded.equals(canonicalDetailString())) {
                throw new ContractException(
                        "明细文件复算不自洽（契约 EV-S4 判定：不一致 ⇒ 阻断）：" + target);
            }
            String recomputed = packs.environmentDigest16(reencoded);
            if (!recomputed.equals(detailDigest16())) {
                throw new ContractException("snapshotDigest16 无法由明细复算：" + target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
