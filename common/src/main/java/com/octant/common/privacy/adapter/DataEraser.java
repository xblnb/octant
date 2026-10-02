package com.octant.common.privacy.adapter;

import com.octant.common.privacy.SaltProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class DataEraser {

    public enum Category {
        EVENTS_DIR("world.octant.events", "raw.raw_events"),
        SESSION_LEDGER("world.octant.state.sessions.json", "raw.session_ledger"),
        TRUNCATION_LEDGER("world.octant.state.truncation.json", "raw.truncation_ledger"),
        PERIODIC_STATE_SNAPSHOTS("world.octant.state/<epoch>.state.json", "raw.periodic_state_snapshot"),
        PSEUDONYM_SALT("world.octant.meta.salt.bin", "raw.pseudonym_salt"),
        CONSENT_MIRROR("world.octant.meta.consent.json", "raw.consent_state_mirror"),
        SCHEMA_SNAPSHOT("world.octant.meta.schema.json", "local.meta.envSnapshot"),
        PACK_SNAPSHOT("world.octant.meta.pack-snapshot.json", "pack.env_snapshot"),
        ANALYSIS_CACHE("world.octant.analysis/", "raw.local_analysis_cache"),
        EXPORT_DIR("world.octant.export/", "export.manifest"),
        CONSENT_LOCAL_DIR("world.octant.consent/", "raw.consent_ledger"),
        CONSENT_STATE("gameDir.config.octant.privacy.json", "raw.consent_ledger"),
        CONSENT_LEDGER("gameDir.config.octant.consent-ledger.jsonl", "raw.consent_ledger"),
        CROSS_EXPORT_SALT("gameDir.config.octant.salt.bin", "raw.cross_export_salt"),
        DELETION_LEDGER("gameDir.config.octant.deletion-ledger.jsonl", "raw.deletion_receipt_paths"),
        LOCAL_LOG("gameDir.logs.octant*.log", "raw.local_log"),
        LOCAL_ONLY_DIR("gameDir.octant-local/", "raw.local_persistence_scope"),
        ENV_DETAIL_DIR("world.octant.meta.env/", "raw.local_persistence_scope");

        private final String pathKey;
        private final String registryRef;

        Category(String pathKey, String registryRef) {
            this.pathKey = pathKey;
            this.registryRef = registryRef;
        }

        public String pathKey() {
            return pathKey;
        }

        public String registryRef() {
            return registryRef;
        }
    }

    public static final int SCOPE_SIZE = 18;

    public static boolean scopeMatchesDeclared(int declaredScopeSize) {
        return Category.values().length == declaredScopeSize && SCOPE_SIZE == declaredScopeSize;
    }

    public static String scopeMismatchReason(int declaredScopeSize) {
        if (scopeMatchesDeclared(declaredScopeSize)) {
            return "";
        }
        return "删除范围不一致：清单声明 " + declaredScopeSize
                + " 类，实现有 " + Category.values().length + " 类，SCOPE_SIZE=" + SCOPE_SIZE
                + " ⇒ 有类别漏登记或漏实现（这正是「兜底掩盖漏登记」会隐藏的情形）";
    }

    public record Receipt(Map<Category, Integer> removedEntries,
                          boolean pseudonymSaltRegenerated,
                          boolean eventsDirEmpty) {

        public Receipt {
            removedEntries = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(removedEntries));
        }

        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> counts = new LinkedHashMap<>();
            for (Category c : Category.values()) {
                counts.put(c.name(), removedEntries.getOrDefault(c, 0));
            }
            m.put("removedEntries", counts);
            m.put("pseudonymSaltRegenerated", pseudonymSaltRegenerated);
            m.put("eventsDirEmpty", eventsDirEmpty);
            return com.octant.common.model.Json.encode(m);
        }
    }

    private final Path worldDir;
    private final Path gameDir;

    public DataEraser(Path worldDir, Path gameDir) {
        this.worldDir = worldDir;
        this.gameDir = gameDir;
    }

    public static Map<String, Object> deletionScope() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (Category c : Category.values()) {
            keys.add(c.pathKey());
        }
        m.put("categories", keys);
        m.put("count", keys.size());
        return m;
    }

    public Receipt eraseAll() {
        Path w = com.octant.common.privacy.OctantPaths.dataDir(worldDir);
        Path g = com.octant.common.privacy.OctantPaths.dataDir(gameDir);
        Path cfg = com.octant.common.privacy.OctantPaths.dataDir(gameDir.resolve("config"));

        Map<Category, Integer> counts = new LinkedHashMap<>();

        counts.put(Category.EVENTS_DIR, deleteRecursively(w.resolve("events")));
        counts.put(Category.SESSION_LEDGER,
                deleteFiles(w.resolve("state"), n -> n.equals("sessions.json")));
        counts.put(Category.TRUNCATION_LEDGER,
                deleteFiles(w.resolve("state"), n -> n.equals("truncation.json")));
        counts.put(Category.PERIODIC_STATE_SNAPSHOTS,
                deleteFiles(w.resolve("state"), n -> n.endsWith(".state.json")));
        counts.put(Category.CONSENT_MIRROR,
                deleteFiles(w.resolve("meta"), n -> n.equals("consent.json")));
        counts.put(Category.SCHEMA_SNAPSHOT,
                deleteFiles(w.resolve("meta"), n -> n.equals("schema.json")));
        counts.put(Category.PACK_SNAPSHOT,
                deleteFiles(w.resolve("meta"), n -> n.equals("pack-snapshot.json")));
        counts.put(Category.ANALYSIS_CACHE, deleteRecursively(w.resolve("analysis")));
        counts.put(Category.EXPORT_DIR, deleteRecursively(w.resolve("export")));
        counts.put(Category.CONSENT_LOCAL_DIR, deleteRecursively(w.resolve("consent")));
        counts.put(Category.CONSENT_STATE,
                deleteFiles(cfg.getParent(), n -> n.equals("privacy.json")));
        counts.put(Category.CONSENT_LEDGER,
                deleteFiles(cfg, n -> n.equals("consent-ledger.jsonl")));
        counts.put(Category.CROSS_EXPORT_SALT,
                deleteFiles(cfg, n -> n.equals("salt.bin")));
        counts.put(Category.DELETION_LEDGER,
                deleteFiles(cfg, n -> n.equals("deletion-ledger.jsonl")));
        counts.put(Category.LOCAL_LOG,
                deleteFiles(gameDir.resolve("logs"),
                        n -> (n.startsWith("octant") || n.startsWith("mcinsight")) && n.endsWith(".log")));
        counts.put(Category.LOCAL_ONLY_DIR, deleteRecursively(gameDir.resolve("octant-local"))
                + deleteRecursively(gameDir.resolve("mcinsight-local")));
        counts.put(Category.ENV_DETAIL_DIR, deleteRecursively(w.resolve("meta").resolve("env")));

        boolean saltRegenerated = false;
        Path saltFile = w.resolve("meta").resolve("salt.bin");
        counts.put(Category.PSEUDONYM_SALT, Files.isRegularFile(saltFile) ? 1 : 0);
        try {
            SaltProvider.overwriteAndDelete(saltFile);
            SaltProvider.generate().persist(saltFile);
            saltRegenerated = Files.isRegularFile(saltFile);
        } catch (IOException ignored) {
            saltRegenerated = false;
        }

        if (Files.exists(w)) {
            deleteRecursivelyPreserving(w, "meta");
            deleteFiles(w.resolve("meta"), n -> !n.equals("salt.bin"));
        }

        boolean eventsEmpty = isEmptyDir(w.resolve("events"));
        return new Receipt(counts, saltRegenerated, eventsEmpty);
    }

    private static boolean saltFileExists(Path p) {
        return Files.isRegularFile(p);
    }

    private static int deleteRecursively(Path dir) {
        return deleteRecursivelyFiltered(dir, n -> true);
    }

    private static int deleteRecursivelyPreserving(Path dir, String preservedChildName) {
        Path preserved = dir.resolve(preservedChildName);
        return deleteRecursivelyFiltered(dir, n -> true, p -> !p.startsWith(preserved));
    }

    private static int deleteFiles(Path dir, Predicate<String> nameFilter) {
        return deleteRecursivelyFiltered(dir, nameFilter);
    }

    private static int deleteRecursivelyFiltered(Path dir, Predicate<String> nameFilter) {
        return deleteRecursivelyFiltered(dir, nameFilter, p -> true);
    }

    private static int deleteRecursivelyFiltered(Path dir, Predicate<String> nameFilter,
                                                 Predicate<Path> pathFilter) {
        if (!Files.exists(dir)) {
            return 0;
        }
        int removed = 0;
        List<Path> targets = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> !p.equals(dir))
                    .filter(pathFilter)
                    .filter(p -> nameFilter.test(p.getFileName().toString()))
                    .sorted(Comparator.reverseOrder())
                    .forEach(targets::add);
        } catch (IOException ignored) {
            return 0;
        }
        List<Path> deletedDirs = new ArrayList<>();
        for (Path p : targets) {
            if (hasDeletedAncestor(p, deletedDirs)) {
                continue;
            }
            try {
                if (Files.isDirectory(p)) {
                    Files.delete(p);
                    deletedDirs.add(p);
                } else {
                    Files.write(p, new byte[0]);
                    Files.delete(p);
                }
                removed++;
            } catch (IOException ignored) {
            }
        }
        return removed;
    }

    private static boolean hasDeletedAncestor(Path p, List<Path> deletedDirs) {
        for (Path d : deletedDirs) {
            if (p.startsWith(d)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmptyDir(Path dir) {
        if (!Files.exists(dir)) {
            return true;
        }
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.findAny().isEmpty();
        } catch (IOException ex) {
            return false;
        }
    }
}
