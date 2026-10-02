package com.octant.common.privacy;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.model.EventSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class RedactionPipeline {

    private static final Set<String> ID_FIELDS = Set.of(
            "dimension", "fromDimension", "biome", "structure", "regionKey",
            "advancementId", "parentId", "questId", "chapterId", "questSource", "unitId",
            "item", "recipeId", "station", "machineBlock", "outputItem", "containerKey",
            "deathCause", "killerEntityType", "entityType", "opponentKey");

    private final SaltProvider salt;
    private final Set<String> vanillaIds;
    private final Set<String> allowedModPrefixes;
    private final RedactionReport report = new RedactionReport();

    private int customDimensionSeq;
    private int customIdSeq;

    public RedactionPipeline(SaltProvider salt, Set<String> vanillaIds, Set<String> allowedModPrefixes) {
        this.salt = java.util.Objects.requireNonNull(salt, "salt");
        this.vanillaIds = OrderedCollections.copyOf(vanillaIds);
        this.allowedModPrefixes = OrderedCollections.copyOf(allowedModPrefixes);
    }

    public RedactionReport report() {
        return report;
    }

    public boolean hasUnregisteredRedactionsRecorded() {
        return report.hasUnregisteredRedactions();
    }

    public CaptureEvent redactEnvelope(CaptureEvent event, String canonicalPlayerId) {
        String key = salt.pseudonymize(canonicalPlayerId);
        report.record("envelope.playerKey", FieldRegistry.Transform.HASH_SALT_TRUNC64, false);
        report.record("session_start.playerKey", FieldRegistry.Transform.HASH_SALT_TRUNC64, false);
        report.record("envelope.tRelMs", FieldRegistry.Transform.RELATIVIZE_COARSEN, false);
        report.record("event.timestamp", FieldRegistry.Transform.RELATIVIZE_COARSEN, false);
        return new CaptureEvent(event.schemaVersion(), event.eventId(), event.sessionId(), key,
                event.type(), event.tRelMs(), event.tTick(), event.category(), event.source(),
                event.confirmed(), event.durMs(), event.payload());
    }

    public Map<String, Object> redactPayload(CaptureEventType type, Map<String, Object> payload,
                                             int horizontalOffset, int verticalOffset) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            String field = e.getKey();
            Object value = e.getValue();
            String rawPath = type.eventId() + "." + field;

            FieldRegistry.FieldPolicy policy = FieldRegistry.lookup(rawPath).orElse(null);
            if (policy == null) {
                if (FieldRegistry.isUnknownFieldName(field)) {
                    out.put(field, redactedPlaceholder(value));
                    report.recordUnregistered(rawPath);
                } else {
                    out.put(field, value);
                    report.record(rawPath, FieldRegistry.Transform.KEEP, false);
                }
                continue;
            }
            if (policy.mustNotBeCollected()) {
                report.record(rawPath, policy.transform(), true);
                throw new ContractException("C 类字段不得进入脱敏管道（应在采集期即被拒）：" + rawPath);
            }
            switch (policy.transform()) {
                case KEEP_ID_WHITELIST -> {
                    Object mapped = whitelistOrCustom(field, String.valueOf(value), type);
                    out.put(field, mapped);
                    report.record(rawPath, policy.transform(), false);
                }
                case KEEP, KEEP_ENUM, KEEP_READONLY_SETTING, KEEP_FOR_LOCAL_USE -> {
                    FieldRegistry.requireIrreversible(type.eventId(), field);
                    out.put(field, value);
                    report.record(rawPath, policy.transform(), false);
                }
                case GENERALIZE_CATEGORY -> {
                    out.put(field, generalize(field, String.valueOf(value)));
                    report.record(rawPath, policy.transform(), false);
                }
                default -> {
                    out.put(field, value);
                    report.record(rawPath, policy.transform(), false);
                }
            }
        }
        return out;
    }

    static Object redactedPlaceholder(Object originalValue) {
        if (originalValue instanceof String) {
            return "";
        }
        if (originalValue instanceof Boolean) {
            return Boolean.FALSE;
        }
        if (originalValue instanceof Number) {
            return 0L;
        }
        if (originalValue instanceof List<?>) {
            return List.of();
        }
        if (originalValue instanceof Map<?, ?>) {
            return Map.of();
        }
        return "";
    }

    public String whitelistOrCustom(String field, String id, CaptureEventType type) {
        if (id == null || id.isBlank()) {
            return "";
        }
        if (vanillaIds.contains(id)) {
            return id;
        }
        String prefix = namespaceOf(id);
        if (allowedModPrefixes.contains(prefix)) {
            return id;
        }
        customIdSeq++;
        return "custom:" + customIdSeq;
    }

    public String customDimension(String dimension) {
        if (vanillaIds.contains(dimension) || allowedModPrefixes.contains(namespaceOf(dimension))) {
            return dimension;
        }
        customDimensionSeq++;
        return "custom_dimension#" + customDimensionSeq;
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : id;
    }

    private static String generalize(String field, String value) {
        if ("privacyClass".equals(field)) {
            return Transforms.privacyClass(value);
        }
        if ("shared".equals(field) || "farmPattern".equals(field)) {
            return Boolean.parseBoolean(value) ? "other_player" : "self";
        }
        return value;
    }

    public Object redactScalar(String rawField, Object rawValue) {
        FieldRegistry.FieldPolicy policy = FieldRegistry.strictestFor(rawField).orElse(null);
        if (policy == null) {
            return rawValue;
        }
        if (policy.mustNotBeCollected()) {
            report.record(rawField, policy.transform(), true);
            throw new ContractException("C 类字段不得被采集或导出（不采集/不落盘）：" + rawField);
        }
        Object mapped = switch (policy.transform()) {
            case HASH_SALT_TRUNC64 -> salt.pseudonymize(String.valueOf(rawValue));
            case HASH_SALT_RECORD -> salt.saltedRecord(String.valueOf(rawValue), 0L);
            case DROP, DROP_COUNT_METADATA, DROP_RELABEL_SEQUENCE, DROP_KEEP_ID_ONLY -> null;
            case GENERALIZE_CATEGORY -> Transforms.privacyClass(String.valueOf(rawValue));
            case GENERALIZE_RAM_BUCKET -> Transforms.ramBucket(asLong(rawValue));
            case GENERALIZE_OS_FAMILY -> Transforms.osFamily(String.valueOf(rawValue));
            case GENERALIZE_GPU_FAMILY -> Transforms.gpuFamily(String.valueOf(rawValue));
            case GENERALIZE_DAY_BUCKET -> Transforms.dayBucket((int) asLong(rawValue));
            case GENERALIZE_LENGTH_BUCKET -> Transforms.lengthBucket(String.valueOf(rawValue));
            case TRUNCATE_LANG_SUBTAG -> Transforms.languageMainTag(String.valueOf(rawValue));
            case TRUNCATE_DATE_GRANULARITY -> Transforms.utcDate(asLong(rawValue));
            case RELATIVIZE_COARSEN -> Transforms.relativize(asLong(rawValue), 0L);
            case QUANTIZE_512 -> Transforms.quantizeHorizontal(asLong(rawValue));
            case QUANTIZE_32 -> Transforms.quantizeVertical(asLong(rawValue));
            case QUANTIZE_512_OFFSET -> Transforms.quantizeHorizontalWithOffset(asLong(rawValue), 0);
            case QUANTIZE_OFFSET -> Transforms.quantizeVerticalWithOffset(asLong(rawValue), 0);
            case QUANTIZE_TRUNCATE -> Transforms.quantizeTruncateLabel(String.valueOf(rawValue));
            case KEEP_ID_WHITELIST -> whitelistOrCustom(rawField, String.valueOf(rawValue), null);
            case GENERALIZE_REASON_CODE -> rawValue;
            default -> rawValue;
        };
        report.record(rawField, policy.transform(), mapped == null);
        return mapped;
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public Map<String, Object> redactScalarBatch(Map<String, Object> rawFields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rawFields.entrySet()) {
            Object v = redactScalar(e.getKey(), e.getValue());
            if (v != null) {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    public static EventSource requireSource(String code) {
        return EventSource.byCode(code).orElseThrow(
                () -> new ContractException("未知采集来源：" + code));
    }

    public static <T> List<T> stable(List<T> in, Function<T, String> keyFn) {
        List<T> copy = new java.util.ArrayList<>(in);
        copy.sort(java.util.Comparator.comparing(keyFn));
        return List.copyOf(copy);
    }
}
