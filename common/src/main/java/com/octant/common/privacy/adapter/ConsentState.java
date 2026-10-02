package com.octant.common.privacy.adapter;

import com.octant.common.model.ContractException;
import com.octant.common.model.Json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class ConsentState {

    public static final List<String> CATEGORY_IDS = List.of("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8");

    public static final int SCHEMA_VERSION = 2;

    public static final String CONSENT_VERSION_PREFIX = "privacy-spec@2.";

    private static final String SPEC_VERSION_RELATIVE = "docs/privacy/SPEC-VERSION.txt";

    private static final java.util.concurrent.atomic.AtomicReference<String> SPEC_VERSION_OVERRIDE =
            new java.util.concurrent.atomic.AtomicReference<>();

    public static String currentSpecVersion() {
        String override = SPEC_VERSION_OVERRIDE.get();
        if (override != null) {
            return override;
        }
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            java.nio.file.Path candidate = dir.resolve(SPEC_VERSION_RELATIVE);
            if (java.nio.file.Files.isRegularFile(candidate)) {
                try {
                    String v = java.nio.file.Files.readString(
                            candidate, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (v.matches("privacy-spec@\\d+\\.\\d+\\.\\d+")) {
                        return v;
                    }
                } catch (java.io.IOException ignored) {
                }
                break;
            }
            dir = dir.getParent();
        }
        return CONSENT_VERSION_PREFIX + "x.0";
    }

    public static void overrideSpecVersionForTest(String version) {
        SPEC_VERSION_OVERRIDE.set(version);
    }

    public static final long DEFAULT_MAX_BYTES = 268_435_456L;

    public static final int DEFAULT_MAX_AGE_DAYS = 180;

    public static final ConsentState DENIED = new ConsentState(SCHEMA_VERSION, "", currentSpecVersion(),
            false, Set.of(), null, DEFAULT_MAX_BYTES, DEFAULT_MAX_AGE_DAYS);

    private final int schemaVersion;
    private final String privacyInstanceId;
    private final String consentVersion;
    private final boolean collectionEnabled;
    private final Set<String> collectionCategories;
    private final String grantedAtDate;
    private final long retentionMaxBytes;
    private final int retentionMaxAgeDays;

    private final Revocation revocation;

    public record Revocation(String revokedAtDate, boolean collectionStopped, boolean exportDisabled,
                            boolean notRetroactive, boolean retroactiveNoticeAcknowledged) {

        public Revocation {
            if (revokedAtDate == null || revokedAtDate.isBlank()) {
                throw new ContractException("撤回记录必须带 revokedAtDate（日粒度 UTC）");
            }
        }

        public boolean isCompliant() {
            return collectionStopped && exportDisabled && notRetroactive && retroactiveNoticeAcknowledged;
        }
    }

    private ConsentState(int schemaVersion, String privacyInstanceId, String consentVersion,
                         boolean collectionEnabled, Set<String> collectionCategories, String grantedAtDate,
                         long retentionMaxBytes, int retentionMaxAgeDays) {
        this(schemaVersion, privacyInstanceId, consentVersion, collectionEnabled, collectionCategories,
                grantedAtDate, retentionMaxBytes, retentionMaxAgeDays, null);
    }

    private ConsentState(int schemaVersion, String privacyInstanceId, String consentVersion,
                         boolean collectionEnabled, Set<String> collectionCategories, String grantedAtDate,
                         long retentionMaxBytes, int retentionMaxAgeDays, Revocation revocation) {
        this.schemaVersion = schemaVersion;
        this.privacyInstanceId = privacyInstanceId == null ? "" : privacyInstanceId;
        this.consentVersion = consentVersion == null ? "" : consentVersion;
        this.collectionEnabled = collectionEnabled;
        this.collectionCategories = Collections.unmodifiableSet(new TreeSet<>(
                collectionCategories == null ? Set.of() : collectionCategories));
        this.grantedAtDate = grantedAtDate;
        this.retentionMaxBytes = retentionMaxBytes;
        this.retentionMaxAgeDays = retentionMaxAgeDays;
        this.revocation = revocation;
        if (collectionEnabled && this.collectionCategories.isEmpty()) {
            throw new ContractException("collection.enabled=true 必须至少有一个类别为 true");
        }
        if (!collectionEnabled && !this.collectionCategories.isEmpty()) {
            throw new ContractException("collection.enabled=false 时不得残留任何类别为 true");
        }
    }

    public static ConsentState denied() {
        return DENIED;
    }

    public static ConsentState granted(String privacyInstanceId, Set<String> categories,
                                       String grantedAtDate, long maxBytes, int maxAgeDays) {
        requireCategoryIds(categories);
        if (grantedAtDate == null || !grantedAtDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ContractException("grantedAtDate 必须是日粒度 UTC 日期 YYYY-MM-DD：" + grantedAtDate);
        }
        return new ConsentState(SCHEMA_VERSION, privacyInstanceId, currentSpecVersion(),
                true, categories, grantedAtDate, maxBytes, maxAgeDays);
    }

    public ConsentState revoked(String revokedAtDate) {
        return new ConsentState(schemaVersion, privacyInstanceId, consentVersion, false, Set.of(),
                grantedAtDate, retentionMaxBytes, retentionMaxAgeDays,
                new Revocation(revokedAtDate, true, true, true, true));
    }

    private static void requireCategoryIds(Set<String> categories) {
        if (categories == null || categories.isEmpty()) {
            throw new ContractException("同意必须至少授予一个类别（C1–C8）");
        }
        for (String c : categories) {
            if (!CATEGORY_IDS.contains(c)) {
                throw new ContractException("未知同意类别：" + c + "，允许 " + CATEGORY_IDS);
            }
        }
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String privacyInstanceId() {
        return privacyInstanceId;
    }

    public String consentVersion() {
        return consentVersion;
    }

    public boolean collectionEnabled() {
        return collectionEnabled && !isRevoked();
    }

    public Set<String> collectionCategories() {
        return collectionCategories;
    }

    public boolean allowsCategory(String categoryId) {
        return collectionEnabled() && collectionCategories.contains(categoryId);
    }

    public String grantedAtDate() {
        return grantedAtDate;
    }

    public long retentionMaxBytes() {
        return retentionMaxBytes;
    }

    public int retentionMaxAgeDays() {
        return retentionMaxAgeDays;
    }

    public Revocation revocation() {
        return revocation;
    }

    public boolean isRevoked() {
        return revocation != null;
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", schemaVersion);
        root.put("privacyInstanceId", privacyInstanceId);
        root.put("consentVersion", consentVersion);

        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("enabled", collectionEnabled);
        if (grantedAtDate != null) {
            collection.put("grantedAtDate", grantedAtDate);
        }
        collection.put("categories", categoriesMap());
        Map<String, Object> retention = new LinkedHashMap<>();
        retention.put("maxBytes", retentionMaxBytes);
        retention.put("maxAgeDays", retentionMaxAgeDays);
        collection.put("retention", retention);
        root.put("collection", collection);

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("enabled", false);
        export.put("categories", allFalseCategories());
        root.put("export", export);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("includeRecommendations", false);
        root.put("analysis", analysis);

        if (revocation != null) {
            Map<String, Object> rev = new LinkedHashMap<>();
            rev.put("revokedAtDate", revocation.revokedAtDate());
            Map<String, Object> sem = new LinkedHashMap<>();
            sem.put("collectionStopped", revocation.collectionStopped());
            sem.put("exportDisabled", revocation.exportDisabled());
            sem.put("notRetroactive", revocation.notRetroactive());
            sem.put("retroactiveNoticeAcknowledged", revocation.retroactiveNoticeAcknowledged());
            rev.put("revocationSemantics", sem);
            root.put("revocation", rev);
        }
        return Json.encode(root);
    }

    public static ConsentState fromJson(String text) {
        Map<String, Object> root = Json.decodeObject(text);
        Object sv = root.get("schemaVersion");
        int version = sv instanceof Number n ? n.intValue() : -1;
        if (version != SCHEMA_VERSION) {
            throw new ContractException("未知同意状态 schemaVersion=" + sv
                    + "（只支持 " + SCHEMA_VERSION + "），必须拒绝启用（fail-closed）");
        }
        String instanceId = asString(root.get("privacyInstanceId"));
        String consentVersion = asString(root.get("consentVersion"));

        Map<String, Object> collection = asMap(root.get("collection"));
        boolean enabled = asBoolean(collection.get("enabled"));
        String grantedAt = collection.get("grantedAtDate") == null ? null
                : asString(collection.get("grantedAtDate"));
        Map<String, Object> cats = asMap(collection.get("categories"));
        Set<String> granted = new TreeSet<>();
        for (String id : CATEGORY_IDS) {
            if (asBoolean(cats.get(id))) {
                granted.add(id);
            }
        }
        Map<String, Object> retention = asMap(collection.get("retention"));
        long maxBytes = retention.get("maxBytes") instanceof Number n ? n.longValue() : DEFAULT_MAX_BYTES;
        int maxAgeDays = retention.get("maxAgeDays") instanceof Number n ? n.intValue() : DEFAULT_MAX_AGE_DAYS;

        Revocation revocation = null;
        Map<String, Object> rev = root.get("revocation") instanceof Map ? asMap(root.get("revocation")) : null;
        if (rev != null) {
            Map<String, Object> sem = asMap(rev.get("revocationSemantics"));
            revocation = new Revocation(
                    asString(rev.get("revokedAtDate")),
                    asBoolean(sem.get("collectionStopped")),
                    asBoolean(sem.get("exportDisabled")),
                    asBoolean(sem.get("notRetroactive")),
                    asBoolean(sem.get("retroactiveNoticeAcknowledged")));
            enabled = false;
        }

        if (granted.isEmpty()) {
            enabled = false;
        }
        ConsentState base = new ConsentState(version, instanceId, consentVersion, enabled,
                enabled ? granted : Set.of(), grantedAt, maxBytes, maxAgeDays, revocation);
        return base;
    }

    private Map<String, Object> categoriesMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String id : CATEGORY_IDS) {
            m.put(id, collectionCategories.contains(id));
        }
        return m;
    }

    private static Map<String, Object> allFalseCategories() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String id : CATEGORY_IDS) {
            m.put(id, false);
        }
        return m;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean asBoolean(Object o) {
        return o instanceof Boolean b && b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConsentState other)) {
            return false;
        }
        return schemaVersion == other.schemaVersion
                && collectionEnabled == other.collectionEnabled
                && retentionMaxBytes == other.retentionMaxBytes
                && retentionMaxAgeDays == other.retentionMaxAgeDays
                && Objects.equals(privacyInstanceId, other.privacyInstanceId)
                && Objects.equals(consentVersion, other.consentVersion)
                && Objects.equals(collectionCategories, other.collectionCategories)
                && Objects.equals(revocation, other.revocation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, privacyInstanceId, consentVersion, collectionEnabled,
                collectionCategories, retentionMaxBytes, retentionMaxAgeDays, revocation);
    }

    @Override
    public String toString() {
        return "ConsentState{enabled=" + collectionEnabled + ", categories=" + collectionCategories
                + (revocation != null ? ", revokedAt=" + revocation.revokedAtDate() : "") + "}";
    }
}
