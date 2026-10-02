package com.octant.pipeline.export;

import com.octant.pipeline.json.Json;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PrivacyRedactor {

    public static final String PSEUDONYM_ALGORITHM = "HMAC-SHA256";
    public static final int PSEUDONYM_TRUNCATE_BITS = 64;
    public static final String COORD_OFFSET_SEED_NOTE = "per_export_uniform_offset";

    private final byte[] salt;
    private final ContentCatalog catalog;
    private final Map<String, String> pseudonyms = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> customIds = new LinkedHashMap<>();
    private final Map<String, Long> transformCounts = new LinkedHashMap<>();
    private final List<String> violations = new ArrayList<>();
    private final Set<String> scannedFields = new LinkedHashSet<>();

    private PrivacyRedactor(byte[] salt, ContentCatalog catalog) {
        this.salt = salt;
        this.catalog = catalog;
    }

    public static PrivacyRedactor withSalt(String saltSeed, ContentCatalog catalog) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(saltSeed.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new PrivacyRedactor(mac.doFinal("mc-insight-salt".getBytes(StandardCharsets.UTF_8)), catalog);
        } catch (Exception e) {
            throw new IllegalStateException("本地 HMAC 初始化失败", e);
        }
    }

    public List<String> violations() {
        return List.copyOf(violations);
    }

    public boolean clean() {
        return violations.isEmpty();
    }

    public Map<String, String> pseudonyms() {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(pseudonyms));
    }

    public Map<String, Long> transformCounts() {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(transformCounts));
    }

    public Set<String> scannedFields() {
        return Set.copyOf(scannedFields);
    }

    public String pseudonymFor(String playerKey) {
        if (playerKey == null || playerKey.isEmpty()) {
            return "p_" + "0".repeat(16);
        }
        return pseudonyms.computeIfAbsent(playerKey, k -> {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(salt, "HmacSHA256"));
                byte[] digest = mac.doFinal(k.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder("p_");
                for (int i = 0; i < 8; i++) {
                    sb.append(String.format("%02x", digest[i]));
                }
                return sb.toString();
            } catch (Exception e) {
                throw new IllegalStateException("本地 HMAC 计算失败", e);
            }
        });
    }

    public String whitelistId(String field, String id) {
        if (id == null || id.isEmpty()) {
            return id == null ? "" : id;
        }
        scannedFields.add(field);
        Map<String, String> map = customIds.computeIfAbsent(field, k -> new LinkedHashMap<>());
        if (catalog != null && catalog.idWhitelist().contains(id)) {
            return id;
        }
        return map.computeIfAbsent(id, k -> {
            bump("id_whitelist_miss:" + field);
            return "custom:" + map.size();
        });
    }

    public Json.JsonObject redactEvent(RawEvent e) {
        Json.JsonObject out = new Json.JsonObject();
        out.put("schemaVersion", com.octant.pipeline.raw.RawEventSchema.SCHEMA_VERSION);
        out.put("eventId", e.eventId());
        out.put("sessionId", e.sessionId());
        out.put("playerPseudonym", pseudonymFor(e.playerKey()));
        bump("playerKey->playerPseudonym");
        out.put("type", e.type().wireName());
        out.put("cat", e.type().category());
        out.put("src", catalog == null ? "" : "local");
        out.put("tRelMs", e.tRelMs());
        out.put("tTick", e.tTick());
        out.put("confirmed", e.confirmed());
        if (e.type().interval()) {
            out.put("durMs", e.durMs());
        }
        Json.JsonObject payload = new Json.JsonObject();
        for (Map.Entry<String, Object> entry : e.payload().entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String s) {
                scanText(field, s);
            }
            if (RedactionMatrix.transformationFor(field) == RedactionMatrix.Transformation.DROP) {
                bump("drop:" + field);
                if (RedactionMatrix.isFailClosedDrop(field)) {
                    droppedFailClosed.add(field);
                    bump("drop_failclosed:" + field);
                } else {
                    bump("drop_rule:" + field);
                }
                continue;
            }
            if (RedactionMatrix.isContractKnownButUnregistered(field)) {
                contractKnownUnregistered.add(field);
                bump("keep_unregistered:" + field);
            }
            if (RedactionMatrix.isIdField(field)) {
                payload.put(field, whitelistId(field, String.valueOf(value)));
                bump("id_whitelist:" + field);
                continue;
            }
            payload.put(field, value);
        }
        out.put("payload", payload);
        return out;
    }

    public java.util.List<String> failClosedDroppedFields() {
        return java.util.List.copyOf(new java.util.TreeSet<>(droppedFailClosed));
    }

    public java.util.List<String> contractKnownUnregisteredFields() {
        return java.util.List.copyOf(new java.util.TreeSet<>(contractKnownUnregistered));
    }

    private final java.util.Set<String> droppedFailClosed = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> contractKnownUnregistered = new java.util.LinkedHashSet<>();

    public String scanText(String field, String text) {
        if (text == null) {
            return "";
        }
        scannedFields.add(field);
        for (String[] p : RedactionMatrix.T1_PATTERNS) {
            if (RedactionMatrix.isLinearScan(p[0])) {
                java.util.List<int[]> hits = RedactionMatrix.linearHits(p[0], text);
                if (!hits.isEmpty()) {
                    violations.add("T1_HIT: field=" + field + " patternClass=" + p[0]
                            + " offset=" + hits.get(0)[0]);
                }
                continue;
            }
            Matcher m = Pattern.compile(p[1]).matcher(text);
            if (m.find()) {
                violations.add("T1_HIT: field=" + field + " patternClass=" + p[0]
                        + " offset=" + m.start());
            }
        }
        return text;
    }

    public void scanArtifact(String artifactName, String text) {
        if (text == null) {
            return;
        }
        for (String[] p : RedactionMatrix.T1_PATTERNS) {
            if (RedactionMatrix.isLinearScan(p[0])) {
                int count = 0;
                for (int[] span : RedactionMatrix.linearHits(p[0], text)) {
                    if (count >= 3) {
                        break;
                    }
                    violations.add("T1_HIT: artifact=" + artifactName + " patternClass=" + p[0]
                            + " offset=" + span[0]);
                    count++;
                }
                continue;
            }
            Matcher m = Pattern.compile(p[1]).matcher(text);
            int count = 0;
            while (m.find() && count < 3) {
                violations.add("T1_HIT: artifact=" + artifactName + " patternClass=" + p[0]
                        + " offset=" + m.start());
                count++;
            }
        }
        for (String forbidden : RedactionMatrix.FORBIDDEN_FIELDS) {
            if (text.contains("\"" + forbidden + "\"")) {
                violations.add("FORBIDDEN_FIELD: artifact=" + artifactName + " field=" + forbidden);
            }
        }
    }

    private void bump(String key) {
        transformCounts.merge(key, 1L, Long::sum);
    }

    public Json.JsonObject report(String exportId) {
        Json.JsonObject report = new Json.JsonObject();
        report.put("schemaVersion", "redaction-report@1.0.0");
        report.put("exportId", exportId);
        report.put("pseudonym", Json.of(
                "mode", "per_export",
                "algorithm", PSEUDONYM_ALGORITHM,
                "truncateBits", (long) PSEUDONYM_TRUNCATE_BITS,
                "distinctPseudonyms", (long) pseudonyms.size()));
        report.put("coordinatePolicy", Json.of(
                "quantization", "x>>9, z>>9, y>>5",
                "preciseCoordinatesExported", Boolean.FALSE,
                "perExportOffset", COORD_OFFSET_SEED_NOTE));
        Json.JsonObject transformations = new Json.JsonObject();
        for (Map.Entry<String, Long> e : new java.util.TreeMap<>(transformCounts).entrySet()) {
            transformations.put(e.getKey(), e.getValue());
        }
        report.put("transformations", transformations);
        Json.JsonArray fields = new Json.JsonArray();
        List<String> sorted = new ArrayList<>(scannedFields);
        java.util.Collections.sort(sorted);
        for (String f : sorted) {
            fields.add(f);
        }
        report.put("scannedTextFields", fields);
        report.put("gate", Json.of(
                "t1Hits", (long) violations.size(),
                "t1PatternClasses", (long) RedactionMatrix.T1_PATTERNS.size(),
                "forbiddenFieldCount", (long) RedactionMatrix.FORBIDDEN_FIELDS.size(),
                "result", violations.isEmpty() ? "pass" : "blocked"));
        report.put("rawHitsIncluded", Boolean.FALSE);
        report.put("networkCallsMade", 0L);

        Json.JsonArray unregistered = new Json.JsonArray();
        for (String f : failClosedDroppedFields()) {
            unregistered.add(f);
        }
        report.put("unregisteredDroppedFields", unregistered);
        report.put("unregisteredDroppedCount", (long) droppedFailClosed.size());
        Json.JsonArray pendingTier = new Json.JsonArray();
        for (String f : contractKnownUnregisteredFields()) {
            pendingTier.add(f);
        }
        report.put("contractKnownUnregisteredFields", pendingTier);
        report.put("contractKnownUnregisteredCount", (long) contractKnownUnregistered.size());
        report.put("failClosedPolicy", "unregistered_field_is_redacted_not_kept");
        return report;
    }

    public int eventTypeCount() {
        return EventType.values().length;
    }

    public static String saltFingerprint(String saltSeed) {
        return Base64.getEncoder().encodeToString(
                saltSeed.getBytes(StandardCharsets.UTF_8)).substring(0, 4);
    }
}
