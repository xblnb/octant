package com.octant.pipeline.raw;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RawEventSchema {

    public static final String SCHEMA_VERSION = "raw-event-schema@1.0.0";
    public static final Set<String> CATEGORIES =
            Set.of("session", "progress", "explore", "combat", "items", "machine", "stall");
    public static final Set<String> SOURCES = Set.of("forge", "neoforge", "fabric");
    public static final long TICK_MS = 50L;

    public record FieldSpec(String name, Kind kind, boolean required, String enumValues,
                            double min, double max, String note) {
        static FieldSpec must(String name, Kind kind) {
            return new FieldSpec(name, kind, true, null, Double.NaN, Double.NaN, "");
        }

        static FieldSpec mustEnum(String name, Kind kind, String values) {
            return new FieldSpec(name, kind, true, values, Double.NaN, Double.NaN, "");
        }

        static FieldSpec may(String name, Kind kind) {
            return new FieldSpec(name, kind, false, null, Double.NaN, Double.NaN, "");
        }

        static FieldSpec mayEnum(String name, Kind kind, String values) {
            return new FieldSpec(name, kind, false, values, Double.NaN, Double.NaN, "");
        }

        static FieldSpec range(String name, Kind kind, boolean required, double min, double max) {
            return new FieldSpec(name, kind, required, null, min, max, "");
        }

        public boolean hasEnum() {
            return enumValues != null;
        }

        public List<String> allowed() {
            return enumValues == null ? List.of() : List.of(enumValues.split("\\|"));
        }
    }

    public enum Kind { STRING, INT, LONG, DOUBLE, BOOL, ARRAY }

    private static final Map<EventType, List<FieldSpec>> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put(EventType.SESSION_START, List.of(
                FieldSpec.range("worldCreateDay", Kind.INT, false, -1, Integer.MAX_VALUE),
                FieldSpec.range("loadMs", Kind.INT, false, -1, Integer.MAX_VALUE),
                FieldSpec.mustEnum("privacyClass", Kind.STRING,
                        "singleplayer|private_server|public_server|unknown"),
                FieldSpec.mustEnum("gameVersion", Kind.STRING, "1.20.1|1.21.1"),
                FieldSpec.mustEnum("loader", Kind.STRING, "forge|neoforge|fabric"),
                FieldSpec.must("sessionStartDate", Kind.STRING),
                FieldSpec.mayEnum("sessionStartLocalBucket", Kind.STRING,
                        "night|morning|afternoon|evening"),
                FieldSpec.must("cheatsEnabled", Kind.BOOL)));

        FIELDS.put(EventType.SESSION_END, List.of(
                FieldSpec.range("wallMs", Kind.LONG, true, 0, Long.MAX_VALUE),
                FieldSpec.range("activeMs", Kind.LONG, true, 0, Long.MAX_VALUE),
                FieldSpec.range("afkMs", Kind.LONG, true, 0, Long.MAX_VALUE),
                FieldSpec.mustEnum("afkReason", Kind.STRING, "none|no_input|singleplayer_pause|unknown"),
                FieldSpec.mustEnum("closeCause", Kind.STRING,
                        "logout|world_unload|crash_recovery|split_gap|max_trel"),
                FieldSpec.must("openSession", Kind.BOOL),
                FieldSpec.range("inputEvents", Kind.INT, true, 0, Integer.MAX_VALUE),
                FieldSpec.range("totalEvents", Kind.INT, true, 0, Integer.MAX_VALUE)));

        FIELDS.put(EventType.SESSION_HEARTBEAT, List.of(
                FieldSpec.mustEnum("activity", Kind.STRING, "active|idle"),
                FieldSpec.range("sinceLastInputMs", Kind.LONG, true, 0, Long.MAX_VALUE),
                FieldSpec.range("tickProgress", Kind.LONG, true, 0, Long.MAX_VALUE)));

        FIELDS.put(EventType.SESSION_ENVIRONMENT, List.of(
                FieldSpec.mustEnum("envSchemaVersion", Kind.STRING, "env-snapshot@1.0.0"),
                FieldSpec.range("snapshotRev", Kind.INT, true, 1, 1_000_000),
                FieldSpec.must("snapshotDigest16", Kind.STRING),
                FieldSpec.range("observedAtRelMs", Kind.LONG, true, 0, Long.MAX_VALUE),
                FieldSpec.range("observedAtTick", Kind.LONG, true, 0, Long.MAX_VALUE)));

        FIELDS.put(EventType.ADVANCEMENT_GAINED, List.of(
                FieldSpec.must("advancementId", Kind.STRING),
                FieldSpec.may("parentId", Kind.STRING),
                FieldSpec.must("isRecipe", Kind.BOOL),
                FieldSpec.must("grantedByCommand", Kind.BOOL)));

        FIELDS.put(EventType.QUEST_COMPLETED, List.of(
                FieldSpec.must("questId", Kind.STRING),
                FieldSpec.may("chapterId", Kind.STRING),
                FieldSpec.must("questSource", Kind.STRING)));

        FIELDS.put(EventType.QUEST_PROGRESSED, List.of(
                FieldSpec.must("questId", Kind.STRING),
                FieldSpec.range("stage", Kind.INT, true, 0, Integer.MAX_VALUE),
                FieldSpec.range("objectiveCount", Kind.INT, false, 0, Integer.MAX_VALUE)));

        FIELDS.put(EventType.DIMENSION_ENTERED, List.of(
                FieldSpec.must("dimension", Kind.STRING),
                FieldSpec.may("fromDimension", Kind.STRING)));

        FIELDS.put(EventType.BIOME_VISITED, List.of(
                FieldSpec.must("biome", Kind.STRING),
                FieldSpec.must("dimension", Kind.STRING),
                FieldSpec.must("firstVisit", Kind.BOOL)));

        FIELDS.put(EventType.STRUCTURE_ENTERED, List.of(
                FieldSpec.must("structure", Kind.STRING),
                FieldSpec.must("dimension", Kind.STRING),
                FieldSpec.must("regionKey", Kind.STRING)));

        FIELDS.put(EventType.REGION_FIRST_VISIT, List.of(
                FieldSpec.must("dimension", Kind.STRING),
                FieldSpec.must("regionKey", Kind.STRING),
                FieldSpec.must("biome", Kind.STRING)));

        FIELDS.put(EventType.COMBAT_STARTED, List.of(
                FieldSpec.must("opponentKey", Kind.STRING),
                FieldSpec.must("entityType", Kind.STRING),
                FieldSpec.range("opponentThreat", Kind.DOUBLE, true, 0, Double.MAX_VALUE),
                FieldSpec.must("farmPattern", Kind.BOOL),
                FieldSpec.must("shared", Kind.BOOL),
                FieldSpec.range("durMs", Kind.LONG, true, 0, Long.MAX_VALUE)));

        FIELDS.put(EventType.COMBAT_ENDED, List.of(
                FieldSpec.must("opponentKey", Kind.STRING),
                FieldSpec.mustEnum("outcome", Kind.STRING,
                        "kill|flee|player_death|timeout|interrupted"),
                FieldSpec.range("damageDealt", Kind.DOUBLE, true, 0, Double.MAX_VALUE),
                FieldSpec.range("damageTaken", Kind.DOUBLE, true, 0, Double.MAX_VALUE),
                FieldSpec.must("lastHitByPlayer", Kind.BOOL),
                FieldSpec.mustEnum("resolved", Kind.STRING, "resolved|partial|unresolved"),
                FieldSpec.mayEnum("tactic", Kind.STRING,
                        "melee|ranged|spell|summon|flee|trap|block"),
                FieldSpec.range("durMs", Kind.LONG, true, 0, Long.MAX_VALUE)));

        FIELDS.put(EventType.PLAYER_DEATH, List.of(
                FieldSpec.must("deathCause", Kind.STRING),
                FieldSpec.mustEnum("causeClass", Kind.STRING,
                        "mob|fall|lava|starvation|drowning|environment|mod_mechanic|player|unknown"),
                FieldSpec.may("killerEntityType", Kind.STRING),
                FieldSpec.must("intentional", Kind.BOOL)));

        FIELDS.put(EventType.ITEM_ACTION, List.of(
                FieldSpec.must("item", Kind.STRING),
                FieldSpec.mustEnum("action", Kind.STRING, "obtain|consume|drop|craft_output"),
                FieldSpec.range("count", Kind.INT, true, 1, 100_000),
                FieldSpec.must("creativeGiven", Kind.BOOL)));

        FIELDS.put(EventType.RECIPE_UNLOCKED, List.of(
                FieldSpec.must("recipeId", Kind.STRING),
                FieldSpec.mustEnum("unlockSource", Kind.STRING, "craft|inventory|advancement|command")));

        FIELDS.put(EventType.RECIPE_ATTEMPTED, List.of(
                FieldSpec.must("recipeId", Kind.STRING),
                FieldSpec.must("station", Kind.STRING),
                FieldSpec.mustEnum("outcome", Kind.STRING, "success|missing_materials|unknown")));

        FIELDS.put(EventType.CONTAINER_SNAPSHOT, List.of(
                FieldSpec.must("containerKey", Kind.STRING),
                FieldSpec.must("itemsDigest", Kind.ARRAY),
                FieldSpec.must("truncated", Kind.BOOL)));

        FIELDS.put(EventType.MACHINE_OBSERVED, List.of(
                FieldSpec.must("machineBlock", Kind.STRING),
                FieldSpec.must("regionKey", Kind.STRING),
                FieldSpec.range("outputDelta10m", Kind.INT, true, -100_000, Integer.MAX_VALUE),
                FieldSpec.mustEnum("deviceClass", Kind.STRING, "auto_producer|unknown_device"),
                FieldSpec.range("reusedSessions", Kind.INT, false, -1, Integer.MAX_VALUE)));

        FIELDS.put(EventType.MACHINE_INTERACTED, List.of(
                FieldSpec.must("machineBlock", Kind.STRING),
                FieldSpec.mustEnum("resolved", Kind.STRING, "resolved|partial|unresolved"),
                FieldSpec.range("activeMs", Kind.INT, true, 0, Integer.MAX_VALUE),
                FieldSpec.range("durMs", Kind.LONG, true, 0, Long.MAX_VALUE)));

        FIELDS.put(EventType.AUTOMATION_CYCLE, List.of(
                FieldSpec.must("machineBlock", Kind.STRING),
                FieldSpec.range("cycleMs", Kind.INT, true, 0, Integer.MAX_VALUE),
                FieldSpec.must("outputItem", Kind.STRING),
                FieldSpec.range("outputCount", Kind.INT, true, 1, Integer.MAX_VALUE)));

        FIELDS.put(EventType.STALL_SEGMENT, List.of(
                FieldSpec.must("unitId", Kind.STRING),
                FieldSpec.mustEnum("unitKind", Kind.STRING, "advancement|quest|content_unit"),
                FieldSpec.range("dwellMs", Kind.LONG, true, 0, Long.MAX_VALUE),
                FieldSpec.range("activeDensityPm", Kind.DOUBLE, true, 0, Double.MAX_VALUE),
                FieldSpec.must("proxy", Kind.BOOL),
                FieldSpec.range("durMs", Kind.LONG, true, 0, Long.MAX_VALUE)));

        FIELDS.put(EventType.REPEATED_FAILURE, List.of(
                FieldSpec.must("unitId", Kind.STRING),
                FieldSpec.mustEnum("failKind", Kind.STRING, "death|recipe_missing|combat_flee|unknown"),
                FieldSpec.range("countInWindow", Kind.INT, true, 1, Integer.MAX_VALUE)));

        FIELDS.put(EventType.RESOURCE_BLOCKED, List.of(
                FieldSpec.must("unitId", Kind.STRING),
                FieldSpec.must("station", Kind.STRING),
                FieldSpec.may("shortfallDigest", Kind.ARRAY),
                FieldSpec.mustEnum("resolution", Kind.STRING, "resolved|unknown")));
    }

    private RawEventSchema() {
    }

    public static List<FieldSpec> fieldsOf(EventType type) {
        return FIELDS.getOrDefault(type, List.of());
    }

    public static boolean registered(EventType type) {
        return FIELDS.containsKey(type);
    }

    public static List<String> allPayloadFieldNames() {
        List<String> out = new ArrayList<>();
        for (List<FieldSpec> specs : FIELDS.values()) {
            for (FieldSpec s : specs) {
                if (!out.contains(s.name())) {
                    out.add(s.name());
                }
            }
        }
        return List.copyOf(out);
    }

    public static int registeredTypeCount() {
        return FIELDS.size();
    }

    public static List<String> validate(RawEvent e, String source) {
        List<String> violations = new ArrayList<>();
        if (e == null) {
            violations.add("ENVELOPE_NULL");
            return violations;
        }
        if (!SOURCES.contains(source)) {
            violations.add("ENVELOPE_SRC_INVALID: " + source);
        }
        if (!CATEGORIES.contains(e.type().category())) {
            violations.add("ENVELOPE_CAT_INVALID: " + e.type().category());
        }
        if (!e.eventId().matches("e\\d{3,}")) {
            violations.add("ENVELOPE_EVENT_ID_FORMAT: " + e.eventId());
        }
        if (!e.sessionId().matches("s\\d{4,}(-p\\d+)?")) {
            violations.add("ENVELOPE_SESSION_ID_FORMAT: " + e.sessionId());
        }
        long expectedTick = Math.round(e.tRelMs() / (double) TICK_MS);
        if (Math.abs(expectedTick - e.tTick()) > 1L) {
            violations.add("ENVELOPE_TICK_MISMATCH: tRelMs=" + e.tRelMs() + " tTick=" + e.tTick());
        }
        if (e.type().interval() && e.durMs() == 0L) {
            violations.add("ENVELOPE_DUR_REQUIRED_FOR_INTERVAL: " + e.type().wireName());
        }
        if (!e.type().interval() && e.durMs() != 0L) {
            violations.add("ENVELOPE_DUR_FORBIDDEN_FOR_SCALAR: " + e.type().wireName());
        }
        if (!registered(e.type())) {
            violations.add("PAYLOAD_NOT_REGISTERED: " + e.type().wireName());
            return violations;
        }
        List<FieldSpec> specs = fieldsOf(e.type());
        for (FieldSpec spec : specs) {
            Object v = e.payload().get(spec.name());
            if (v == null) {
                if (spec.required()) {
                    violations.add("PAYLOAD_MISSING_MUST: " + e.type().wireName() + "." + spec.name());
                }
                continue;
            }
            violations.addAll(checkField(e.type(), spec, v));
        }
        for (String key : e.payload().keySet()) {
            if (specs.stream().noneMatch(s -> s.name().equals(key))) {
                violations.add("PAYLOAD_UNDECLARED_FIELD: " + e.type().wireName() + "." + key);
            }
        }
        return violations;
    }

    private static List<String> checkField(EventType type, FieldSpec spec, Object v) {
        List<String> out = new ArrayList<>();
        String where = type.wireName() + "." + spec.name();
        switch (spec.kind()) {
            case STRING -> {
                if (!(v instanceof String s)) {
                    out.add("PAYLOAD_TYPE: " + where + " 应为 string，实际 " + v.getClass().getSimpleName());
                } else if (spec.hasEnum() && !spec.allowed().contains(s)) {
                    out.add("PAYLOAD_ENUM: " + where + "=" + s + " 不在 " + spec.allowed() + " 中");
                }
            }
            case BOOL -> {
                if (!(v instanceof Boolean)) {
                    out.add("PAYLOAD_TYPE: " + where + " 应为 boolean");
                }
            }
            case INT, LONG -> {
                if (!(v instanceof Number n)) {
                    out.add("PAYLOAD_TYPE: " + where + " 应为整数");
                } else {
                    out.addAll(checkRange(where, n.doubleValue(), spec));
                }
            }
            case DOUBLE -> {
                if (!(v instanceof Number n)) {
                    out.add("PAYLOAD_TYPE: " + where + " 应为数值");
                } else {
                    out.addAll(checkRange(where, n.doubleValue(), spec));
                }
            }
            case ARRAY -> {
                if (!(v instanceof List<?>)) {
                    out.add("PAYLOAD_TYPE: " + where + " 应为 array");
                }
            }
            default -> out.add("PAYLOAD_TYPE_UNKNOWN: " + where);
        }
        return out;
    }

    private static List<String> checkRange(String where, double v, FieldSpec spec) {
        if (Double.isNaN(spec.min())) {
            return List.of();
        }
        if (!spec.required() && isSentinel(spec, v)) {
            return List.of();
        }
        if (v < spec.min() || v > spec.max()) {
            return List.of("PAYLOAD_RANGE: " + where + "=" + v + " 超出 [" + spec.min() + ", " + spec.max() + "]");
        }
        return List.of();
    }

    public static boolean isSentinel(FieldSpec spec, double value) {
        return switch (spec.kind()) {
            case INT, LONG, DOUBLE -> value == -1.0d;
            default -> false;
        };
    }
}
