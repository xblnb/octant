package com.octant.capture;

import com.octant.common.privacy.adapter.ConsentGate;
import com.octant.common.privacy.adapter.ConsentState;
import com.octant.common.privacy.adapter.ConsentStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PrivacyConsent {

    public static final String PERMIT_BLOCK = "permit";

    static final String PERMIT_SCHEMA_VERSION = "privacy-consent@2.0.0";

    private final ConsentStore store;
    private final ConsentGate gate;

    public PrivacyConsent(Path gameDir, Path worldDir, ConsentGate gate) {
        this.store = new ConsentStore(gameDir, worldDir);
        this.gate = gate;
    }

    public Path privacyFile() {
        return store.privacyFile();
    }

    public ConsentState reload() {
        ConsentState loaded = store.load();
        gate.refresh(loaded);
        return loaded;
    }

    public ConsentState current() {
        return gate.state();
    }

    public boolean collecting() {
        return gate.isCollecting();
    }

    public ConsentState grant(String instanceId, Set<String> categories, boolean acknowledged)
            throws IOException {
        if (!acknowledged) {
            throw new IllegalArgumentException("必须确认已知悉撤回语义后才能授予（acknowledged=false）");
        }
        ConsentState state = ConsentState.granted(
                instanceId, categories, utcToday(),
                ConsentState.DEFAULT_MAX_BYTES, ConsentState.DEFAULT_MAX_AGE_DAYS);
        return write(state);
    }

    public ConsentState revoke() throws IOException {
        return write(gate.state().revoked(utcToday()));
    }

    public boolean exportPermitted() {
        ConsentState s = gate.state();
        return s.collectionEnabled() && !s.collectionCategories().isEmpty() && !s.isRevoked();
    }

    public List<String> consentedCategories() {
        return List.copyOf(gate.state().collectionCategories());
    }

    String toFileJson(ConsentState state) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", ConsentState.SCHEMA_VERSION);
        root.put("privacyInstanceId", state.privacyInstanceId());
        root.put("consentVersion", state.consentVersion());

        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("enabled", state.collectionEnabled());
        if (state.grantedAtDate() != null && !state.grantedAtDate().isBlank()) {
            collection.put("grantedAtDate", state.grantedAtDate());
        }
        collection.put("categories", categoriesMap(state.collectionCategories()));
        Map<String, Object> retention = new LinkedHashMap<>();
        retention.put("maxBytes", state.retentionMaxBytes());
        retention.put("maxAgeDays", state.retentionMaxAgeDays());
        collection.put("retention", retention);
        root.put("collection", collection);

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("enabled", state.collectionEnabled());
        export.put("categories", categoriesMap(state.collectionCategories()));
        export.put("includePlayerLabels", false);
        export.put("includeAnonymizedEventStream", true);
        Map<String, Object> pseudonym = new LinkedHashMap<>();
        pseudonym.put("mode", "per_export");
        pseudonym.put("algorithm", "HMAC-SHA256");
        pseudonym.put("truncateBits", 64);
        export.put("pseudonym", pseudonym);
        root.put("export", export);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("includeRecommendations", false);
        root.put("analysis", analysis);

        if (state.revocation() != null) {
            Map<String, Object> rev = new LinkedHashMap<>();
            rev.put("revokedAtDate", state.revocation().revokedAtDate());
            Map<String, Object> sem = new LinkedHashMap<>();
            sem.put("collectionStopped", state.revocation().collectionStopped());
            sem.put("exportDisabled", state.revocation().exportDisabled());
            sem.put("notRetroactive", state.revocation().notRetroactive());
            sem.put("retroactiveNoticeAcknowledged",
                    state.revocation().retroactiveNoticeAcknowledged());
            rev.put("revocationSemantics", sem);
            root.put("revocation", rev);
        }

        Map<String, Object> permit = new LinkedHashMap<>();
        permit.put("schemaVersion", PERMIT_SCHEMA_VERSION);
        permit.put("enabled", state.collectionEnabled());
        permit.put("exportEnabled", state.collectionEnabled());
        permit.put("acknowledged", true);
        permit.put("consentedCategories", List.copyOf(state.collectionCategories()));
        root.put(PERMIT_BLOCK, permit);

        return com.octant.common.model.Json.encode(root);
    }

    public String describeCurrent() {
        return toFileJson(gate.state());
    }

    public ConsentState write(ConsentState state) throws IOException {
        Path target = store.privacyFile();
        writeRaw(target, toFileJson(state));
        Path mirror = store.mirrorFile();
        writeRaw(mirror, toFileJson(state));
        gate.refresh(state);
        return state;
    }

    private static void writeRaw(Path target, String json) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ConsentStore.writeAtomically(target, json);
    }

    private static Map<String, Object> categoriesMap(Set<String> on) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String id : ConsentState.CATEGORY_IDS) {
            m.put(id, on.contains(id));
        }
        return m;
    }

    static String utcToday() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }
}
