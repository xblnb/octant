package com.octant.common.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PayloadSpecs {

    private static final Map<CaptureEventType, Spec> TABLE;

    private static final int ID_MAX = 128;

    static {
        Map<CaptureEventType, Spec> t = new LinkedHashMap<>();

        t.put(CaptureEventType.SESSION_START, spec(
                intMay("worldCreateDay", 0, -1),
                intMay("loadMs", 0, -1),
                enums("privacyClass", Set.of("singleplayer", "private_server", "public_server", "unknown")),
                enums("gameVersion", Set.of("1.20.1", "1.21.1")),
                enums("loader", Set.of("forge", "neoforge", "fabric")),
                idMust("sessionStartDate"),
                enumMay("sessionStartLocalBucket", Set.of("night", "morning", "afternoon", "evening")),
                boolMust("cheatsEnabled")));

        t.put(CaptureEventType.SESSION_END, spec(
                longMust("wallMs", 0L),
                longMust("activeMs", 0L),
                longMust("afkMs", 0L),
                enums("afkReason", Set.of("none", "no_input", "singleplayer_pause", "unknown")),
                enums("closeCause", Set.of("logout", "world_unload", "crash_recovery", "split_gap", "max_trel")),
                boolMust("openSession"),
                intMust("inputEvents", 0),
                intMust("totalEvents", 0)));

        t.put(CaptureEventType.SESSION_HEARTBEAT, spec(
                enums("activity", Set.of("active", "idle")),
                longMust("sinceLastInputMs", 0L),
                longMust("tickProgress", 0L)));

        t.put(CaptureEventType.SESSION_ENVIRONMENT, spec(
                enums("envSchemaVersion", Set.of(RawEventSchema.ENV_SNAPSHOT_SCHEMA_VERSION)),
                intBounded("snapshotRev", 1, 1_000_000),
                idMust("snapshotDigest16"),
                longMust("observedAtRelMs", 0L),
                longMust("observedAtTick", 0L)));

        t.put(CaptureEventType.ADVANCEMENT_GAINED, spec(
                idMust("advancementId"),
                idMay("parentId"),
                boolMust("isRecipe"),
                boolMust("grantedByCommand")));

        t.put(CaptureEventType.QUEST_COMPLETED, spec(
                idMust("questId"),
                idMay("chapterId"),
                idMust("questSource")));

        t.put(CaptureEventType.QUEST_PROGRESSED, spec(
                idMust("questId"),
                intMust("stage", 0),
                intMay("objectiveCount", 0, -1)));

        t.put(CaptureEventType.DIMENSION_ENTERED, spec(
                idMust("dimension"),
                idMay("fromDimension")));

        t.put(CaptureEventType.BIOME_VISITED, spec(
                idMust("biome"),
                idMust("dimension"),
                boolMust("firstVisit")));

        t.put(CaptureEventType.STRUCTURE_ENTERED, spec(
                idMust("structure"),
                idMust("dimension"),
                idMust("regionKey")));

        t.put(CaptureEventType.REGION_FIRST_VISIT, spec(
                idMust("dimension"),
                idMust("regionKey"),
                idMust("biome")));

        t.put(CaptureEventType.COMBAT_STARTED, spec(
                idMust("opponentKey"),
                idMust("entityType"),
                dblMust("opponentThreat", 0.0d),
                boolMust("farmPattern"),
                boolMust("shared"),
                longMust("durMs", 0L)));

        t.put(CaptureEventType.COMBAT_ENDED, spec(
                idMust("opponentKey"),
                enums("outcome", Set.of("kill", "flee", "player_death", "timeout", "interrupted")),
                dblMust("damageDealt", 0.0d),
                dblMust("damageTaken", 0.0d),
                boolMust("lastHitByPlayer"),
                enums("resolved", Set.of("resolved", "partial", "unresolved")),
                enumMay("tactic", Set.of("melee", "ranged", "spell", "summon", "flee", "trap", "block")),
                longMust("durMs", 0L)));

        t.put(CaptureEventType.PLAYER_DEATH, spec(
                idMust("deathCause"),
                enums("causeClass", Set.of("mob", "fall", "lava", "starvation", "drowning",
                        "environment", "mod_mechanic", "player", "unknown")),
                idMay("killerEntityType"),
                boolMust("intentional")));

        t.put(CaptureEventType.ITEM_ACTION, spec(
                idMust("item"),
                enums("action", Set.of("obtain", "consume", "drop", "craft_output")),
                intBounded("count", 1, 100_000),
                boolMust("creativeGiven")));

        t.put(CaptureEventType.RECIPE_UNLOCKED, spec(
                idMust("recipeId"),
                enums("unlockSource", Set.of("craft", "inventory", "advancement", "command"))));

        t.put(CaptureEventType.RECIPE_ATTEMPTED, spec(
                idMust("recipeId"),
                idMust("station"),
                enums("outcome", Set.of("success", "missing_materials", "unknown"))));

        t.put(CaptureEventType.CONTAINER_SNAPSHOT, spec(
                idMust("containerKey"),
                digest("itemsDigest", 32, "count"),
                boolMust("truncated")));

        t.put(CaptureEventType.MACHINE_OBSERVED, spec(
                idMust("machineBlock"),
                idMust("regionKey"),
                intBounded("outputDelta10m", -100_000, null),
                enums("deviceClass", Set.of("auto_producer", "unknown_device")),
                intMay("reusedSessions", 0, -1)));

        t.put(CaptureEventType.MACHINE_INTERACTED, spec(
                idMust("machineBlock"),
                enums("resolved", Set.of("resolved", "partial", "unresolved")),
                intMust("activeMs", 0),
                longMust("durMs", 0L)));

        t.put(CaptureEventType.AUTOMATION_CYCLE, spec(
                idMust("machineBlock"),
                intMust("cycleMs", 0),
                idMust("outputItem"),
                intBounded("outputCount", 1, null)));

        t.put(CaptureEventType.STALL_SEGMENT, spec(
                idMust("unitId"),
                enums("unitKind", Set.of("advancement", "quest", "content_unit")),
                longMust("dwellMs", 0L),
                dblMust("activeDensityPm", 0.0d),
                boolMust("proxy"),
                longMust("durMs", 0L)));

        t.put(CaptureEventType.REPEATED_FAILURE, spec(
                idMust("unitId"),
                enums("failKind", Set.of("death", "recipe_missing", "combat_flee", "unknown")),
                intBounded("countInWindow", 1, null)));

        t.put(CaptureEventType.RESOURCE_BLOCKED, spec(
                idMust("unitId"),
                idMust("station"),
                digestMay("shortfallDigest", 16, "missingCount"),
                enums("resolution", Set.of("resolved", "unknown"))));

        if (t.size() != CaptureEventType.CONTRACT_TYPE_COUNT) {
            throw new IllegalStateException(
                    "payload 字段表数量与 dc §2.4 的具名事件类型数不符：期望 "
                            + CaptureEventType.CONTRACT_TYPE_COUNT + "，实际 " + t.size());
        }
        TABLE = Collections.unmodifiableMap(t);
    }

    private PayloadSpecs() {
    }

    public static java.util.List<String> fieldNamesOf(CaptureEventType type) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (PayloadFieldSpec f : of(type).fields()) {
            out.add(f.name());
        }
        return java.util.List.copyOf(out);
    }

    public static java.util.Set<String> allFieldNames() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (CaptureEventType t : CaptureEventType.values()) {
            out.addAll(fieldNamesOf(t));
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    static Spec of(CaptureEventType type) {
        Spec s = TABLE.get(type);
        if (s == null) {
            throw new ContractException("缺少 payload 字段表：" + type);
        }
        return s;
    }

    private static PayloadFieldSpec intMust(String name, int min) {
        return new PayloadFieldSpec.Int(name, true, min, null, null);
    }

    private static PayloadFieldSpec intBounded(String name, Integer min, Integer max) {
        return new PayloadFieldSpec.Int(name, true, min, max, null);
    }

    private static PayloadFieldSpec intMay(String name, int min, int defaultValue) {
        return new PayloadFieldSpec.Int(name, false, min, null, defaultValue);
    }

    private static PayloadFieldSpec longMust(String name, long min) {
        return new PayloadFieldSpec.Lng(name, true, min, null, null);
    }

    private static PayloadFieldSpec dblMust(String name, double min) {
        return new PayloadFieldSpec.Dbl(name, true, min, null, null);
    }

    private static PayloadFieldSpec boolMust(String name) {
        return new PayloadFieldSpec.Bool(name, true, null);
    }

    private static PayloadFieldSpec enums(String name, Set<String> values) {
        return new PayloadFieldSpec.Enum(name, true, values, false, null);
    }

    private static PayloadFieldSpec enumMay(String name, Set<String> values) {
        return new PayloadFieldSpec.Enum(name, false, values, true, "");
    }

    private static PayloadFieldSpec idMust(String name) {
        return new PayloadFieldSpec.IdString(name, true, ID_MAX, null);
    }

    private static PayloadFieldSpec idMay(String name) {
        return new PayloadFieldSpec.IdString(name, false, ID_MAX, "");
    }

    private static PayloadFieldSpec digest(String name, int maxElements, String countKey) {
        return new PayloadFieldSpec.Digest(name, true, maxElements, countKey);
    }

    private static PayloadFieldSpec digestMay(String name, int maxElements, String countKey) {
        return new PayloadFieldSpec.Digest(name, false, maxElements, countKey);
    }

    private static Spec spec(PayloadFieldSpec... fields) {
        return new Spec(List.of(fields));
    }

    record Spec(List<PayloadFieldSpec> fields) {

        Spec {
            fields = List.copyOf(fields);
        }

        boolean has(String fieldName) {
            return fields.stream().anyMatch(f -> f.name().equals(fieldName));
        }
    }
}
