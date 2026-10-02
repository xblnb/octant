package com.octant.pipeline;

import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;
import com.octant.pipeline.raw.UnitKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SyntheticCorpus {

    public static final String SOURCE = "forge";
    public static final String PLAYER_KEY = "9f1c4d7a2b6e8f03";
    public static final int SESSION_COUNT = 12;
    public static final long SESSION_ACTIVE_MS = 7_200_000L;
    public static final long SESSION_STRIDE_MS = 21_600_000L;

    private static long clock = 0L;

    private SyntheticCorpus() {
    }

    public static void resetClock() {
        clock = 0L;
    }

    private static RawEvent timed(String sessionId, int seq, EventType type, long tRelMs,
                                  long durMs, boolean confirmed, Map<String, Object> payload) {
        clock++;
        long tick = Math.round(tRelMs / 50.0d);
        return new RawEvent(String.format("e%03d", seq), sessionId, PLAYER_KEY, type, tRelMs,
                tick, durMs, confirmed, payload);
    }

    private static RawEvent ev(String sessionId, int seq, EventType type, long tRelMs,
                              Map<String, Object> payload) {
        return timed(sessionId, seq, type, tRelMs, 0L, true, payload);
    }

    private static RawEvent iv(String sessionId, int seq, EventType type, long tRelMs, long durMs,
                              Map<String, Object> payload) {
        return timed(sessionId, seq, type, tRelMs, durMs, true, payload);
    }

    public static ContentCatalog catalog() {
        ContentCatalog c = new ContentCatalog();
        c.register(UnitKind.ADVANCEMENT, List.of(
                "minecraft:story/root", "minecraft:story/mine_stone", "minecraft:story/upgrade_tools",
                "minecraft:story/smelt_iron", "minecraft:story/iron_tools", "minecraft:story/obtain_armor",
                "minecraft:story/lava_bucket", "minecraft:story/deflect_arrow",
                "minecraft:nether/root", "minecraft:nether/obtain_blaze_rod",
                "minecraft:end/root", "minecraft:end/kill_dragon"), false);
        c.register(UnitKind.QUEST, List.of(
                "pack:chapter_stone", "pack:chapter_iron", "pack:chapter_nether",
                "pack:chapter_energy", "pack:chapter_end"), false);
        c.register(UnitKind.RECIPE, List.of(
                "minecraft:crafting_table", "minecraft:furnace", "minecraft:iron_pickaxe",
                "minecraft:iron_chestplate", "minecraft:blast_furnace", "minecraft:anvil",
                "pack:crushing_plant", "pack:energy_cell"), false);
        c.register(UnitKind.ITEM, List.of(
                "minecraft:cobblestone", "minecraft:iron_ingot", "minecraft:coal", "minecraft:stick",
                "minecraft:bread", "pack:energy_cell", "minecraft:diamond"), false);
        c.register(UnitKind.DIMENSION, List.of("minecraft:overworld", "minecraft:the_nether",
                "minecraft:the_end", "pack:void_dimension"), false);
        c.register(UnitKind.BIOME, List.of("minecraft:plains", "minecraft:forest",
                "minecraft:desert", "minecraft:nether_wastes", "minecraft:the_end",
                "minecraft:swamp"), false);
        c.register(UnitKind.STRUCTURE, List.of("minecraft:village_plains", "minecraft:mineshaft",
                "minecraft:fortress", "minecraft:bastion_remnant", "minecraft:stronghold"), false);
        c.register(UnitKind.MACHINE, List.of("minecraft:furnace", "minecraft:blast_furnace",
                "pack:crusher", "pack:assembler", "minecraft:hopper"), false);
        return c;
    }

    public static List<RawEvent> events() {
        resetClock();
        List<RawEvent> out = new ArrayList<>();
        for (int s = 0; s < SESSION_COUNT; s++) {
            String sessionId = String.format("s%04d", s + 1);
            long baseMs = s * SESSION_STRIDE_MS;
            int seq = 1;

            out.add(ev(sessionId, seq++, EventType.SESSION_START, baseMs, Map.of(
                    "gameVersion", "1.20.1", "loader", "forge", "privacyClass", "singleplayer",
                    "sessionStartDate", "2026-09-0" + ((s % 9) + 1), "cheatsEnabled", Boolean.FALSE)));

            for (long t = 0; t <= SESSION_ACTIVE_MS; t += 30_000L) {
                out.add(timed(sessionId, seq++, EventType.SESSION_HEARTBEAT, baseMs + t, 0L, false,
                        Map.of("activity", "active", "sinceLastInputMs", 1_000L, "tickProgress", 600L)));
            }

            int advances = Math.min(11, s + 3);
            int previousAdvances = Math.max(0, Math.min(11, s + 2));
            for (int i = previousAdvances; i < advances; i++) {
                out.add(ev(sessionId, seq++, EventType.ADVANCEMENT_GAINED,
                        baseMs + 120_000L + i * 60_000L,
                        Map.of("advancementId", advancementId(i),
                                "parentId", i == 0 ? "" : advancementId(i - 1),
                                "isRecipe", Boolean.FALSE, "grantedByCommand", Boolean.FALSE)));
            }
            int quests = Math.min(5, 1 + s / 3);
            int previousQuests = Math.min(5, 1 + Math.max(0, s - 1) / 3);
            for (int i = previousQuests; i < quests; i++) {
                out.add(ev(sessionId, seq++, EventType.QUEST_COMPLETED, baseMs + 200_000L + i * 90_000L,
                        Map.of("questId", questId(i), "chapterId", "pack:chapter_" + i,
                                "questSource", "packquests")));
            }
            for (int i = 0; i < quests; i++) {
                out.add(ev(sessionId, seq++, EventType.QUEST_PROGRESSED, baseMs + 260_000L + i * 90_000L,
                        Map.of("questId", questId(i), "stage", (long) (s + 1), "objectiveCount", 2L)));
            }

            out.add(ev(sessionId, seq++, EventType.DIMENSION_ENTERED, baseMs + 300_000L,
                    Map.of("dimension", "minecraft:overworld", "fromDimension", "")));
            if (s % 2 == 1) {
                out.add(ev(sessionId, seq++, EventType.DIMENSION_ENTERED, baseMs + 1_200_000L,
                        Map.of("dimension", "minecraft:the_nether",
                                "fromDimension", "minecraft:overworld")));
            }
            if (s % 3 == 2) {
                out.add(ev(sessionId, seq++, EventType.DIMENSION_ENTERED, baseMs + 2_400_000L,
                        Map.of("dimension", "minecraft:the_end",
                                "fromDimension", "minecraft:overworld")));
            }
            String[] biomes = {"minecraft:plains", "minecraft:forest", "minecraft:desert",
                    "minecraft:nether_wastes", "minecraft:swamp"};
            out.add(ev(sessionId, seq++, EventType.BIOME_VISITED, baseMs + 360_000L,
                    Map.of("biome", biomes[s % biomes.length], "dimension", "minecraft:overworld",
                            "firstVisit", Boolean.TRUE)));
            out.add(ev(sessionId, seq++, EventType.STRUCTURE_ENTERED, baseMs + 600_000L,
                    Map.of("structure", s % 2 == 0 ? "minecraft:village_plains" : "minecraft:mineshaft",
                            "dimension", "minecraft:overworld",
                            "regionKey", "minecraft:overworld#0#" + (s * 512L))));
            out.add(ev(sessionId, seq++, EventType.REGION_FIRST_VISIT, baseMs + 660_000L,
                    Map.of("dimension", "minecraft:overworld",
                            "regionKey", "minecraft:overworld#0#" + (s * 512L),
                            "biome", biomes[s % biomes.length])));

            String[] items = {"minecraft:cobblestone", "minecraft:iron_ingot", "minecraft:coal",
                    "minecraft:stick", "minecraft:bread"};
            for (int i = 0; i < 8; i++) {
                out.add(ev(sessionId, seq++, EventType.ITEM_ACTION, baseMs + 700_000L + i * 45_000L,
                        Map.of("item", items[i % items.length], "action", "craft_output",
                                "count", 4L, "creativeGiven", Boolean.FALSE)));
            }
            out.add(ev(sessionId, seq++, EventType.ITEM_ACTION, baseMs + 1_500_000L,
                    Map.of("item", "pack:energy_cell", "action", "obtain", "count", 2L,
                            "creativeGiven", Boolean.FALSE)));
            out.add(ev(sessionId, seq++, EventType.RECIPE_UNLOCKED, baseMs + 760_000L,
                    Map.of("recipeId", "minecraft:iron_pickaxe", "unlockSource", "craft")));
            out.add(ev(sessionId, seq++, EventType.RECIPE_ATTEMPTED, baseMs + 820_000L,
                    Map.of("recipeId", "minecraft:iron_pickaxe", "station", "minecraft:crafting_table",
                            "outcome", s % 4 == 0 ? "missing_materials" : "success")));
            out.add(ev(sessionId, seq++, EventType.CONTAINER_SNAPSHOT, baseMs + 900_000L,
                    Map.of("containerKey", "minecraft:overworld#0#" + (s * 512L) + "#1",
                            "itemsDigest", List.of(Map.of("item", "minecraft:cobblestone",
                                    "count", 64L)),
                            "truncated", Boolean.FALSE)));

            out.add(ev(sessionId, seq++, EventType.MACHINE_OBSERVED, baseMs + 1_000_000L + s * 1_000L,
                    Map.of("machineBlock", "minecraft:furnace", "regionKey", "minecraft:overworld#0#0",
                            "outputDelta10m", 16L, "deviceClass", "auto_producer",
                            "reusedSessions", (long) (s + 1))));
            if (s % 2 == 0) {
                out.add(ev(sessionId, seq++, EventType.MACHINE_OBSERVED, baseMs + 1_100_000L,
                        Map.of("machineBlock", "pack:crusher", "regionKey", "minecraft:overworld#0#0",
                                "outputDelta10m", 24L, "deviceClass", "auto_producer",
                                "reusedSessions", (long) (s + 2))));
            }
            out.add(iv(sessionId, seq++, EventType.MACHINE_INTERACTED, baseMs + 1_160_000L, 4_000L,
                    Map.of("machineBlock", "pack:crusher", "resolved", "resolved",
                            "activeMs", 4_000L, "durMs", 4_000L)));
            out.add(ev(sessionId, seq++, EventType.AUTOMATION_CYCLE, baseMs + 1_260_000L,
                    Map.of("machineBlock", "pack:crusher", "cycleMs", 12_000L,
                            "outputItem", "minecraft:iron_ingot", "outputCount", 3L)));

            for (int i = 0; i < 4; i++) {
                long t = baseMs + 1_800_000L + i * 240_000L;
                String opponentKey = "minecraft:zombie#" + (i + 1);
                out.add(iv(sessionId, seq++, EventType.COMBAT_STARTED, t, 180_000L,
                        Map.of("opponentKey", opponentKey, "entityType", "minecraft:zombie",
                                "opponentThreat", 12.0d, "farmPattern", Boolean.FALSE,
                                "shared", Boolean.FALSE, "durMs", 180_000L)));
                out.add(iv(sessionId, seq++, EventType.COMBAT_ENDED, t + 180_000L, 180_000L,
                        Map.of("opponentKey", opponentKey, "outcome", i == 3 ? "flee" : "kill",
                                "damageDealt", 40.0d, "damageTaken", i == 3 ? 24.0d : 6.0d,
                                "lastHitByPlayer", Boolean.TRUE, "resolved", "resolved",
                                "tactic", i % 3 == 0 ? "melee" : (i % 3 == 1 ? "ranged" : "trap"),
                                "durMs", 180_000L)));
            }
            long farmT = baseMs + 3_000_000L;
            out.add(iv(sessionId, seq++, EventType.COMBAT_STARTED, farmT, 120_000L,
                    Map.of("opponentKey", "minecraft:skeleton#1", "entityType", "minecraft:skeleton",
                            "opponentThreat", 8.0d, "farmPattern", Boolean.TRUE,
                            "shared", Boolean.FALSE, "durMs", 120_000L)));
            out.add(iv(sessionId, seq++, EventType.COMBAT_ENDED, farmT + 120_000L, 120_000L,
                    Map.of("opponentKey", "minecraft:skeleton#1", "outcome", "kill",
                            "damageDealt", 30.0d, "damageTaken", 2.0d,
                            "lastHitByPlayer", Boolean.TRUE, "resolved", "resolved",
                            "tactic", "melee", "durMs", 120_000L)));

            String[] causes = {"minecraft:fall", "minecraft:lava", "minecraft:zombie"};
            String[] causeClass = {"fall", "lava", "mob"};
            int ci = s % 3;
            out.add(ev(sessionId, seq++, EventType.PLAYER_DEATH, baseMs + 3_300_000L,
                    Map.of("deathCause", causes[ci], "causeClass", causeClass[ci],
                            "killerEntityType", "mob".equals(causeClass[ci]) ? "minecraft:zombie" : "",
                            "intentional", Boolean.FALSE)));

            long stallT = baseMs + 2_700_000L;
            out.add(iv(sessionId, seq++, EventType.STALL_SEGMENT, stallT, 90_000L,
                    Map.of("unitId", stallUnit(s), "unitKind", "advancement",
                            "dwellMs", 90_000L, "activeDensityPm", 1.4d, "proxy", Boolean.FALSE,
                            "durMs", 90_000L)));
            out.add(ev(sessionId, seq++, EventType.REPEATED_FAILURE, baseMs + 2_760_000L,
                    Map.of("unitId", stallUnit(s), "failKind", "recipe_missing", "countInWindow", 4L)));
            out.add(ev(sessionId, seq++, EventType.RESOURCE_BLOCKED, baseMs + 2_820_000L,
                    Map.of("unitId", stallUnit(s), "station", "minecraft:crafting_table",
                            "shortfallDigest", List.of(Map.of("item", "minecraft:iron_ingot",
                                    "missingCount", 3L)),
                            "resolution", "resolved")));

            long endT = baseMs + SESSION_ACTIVE_MS;
            out.add(ev(sessionId, seq++, EventType.SESSION_END, endT,
                    Map.of("wallMs", SESSION_ACTIVE_MS, "activeMs", SESSION_ACTIVE_MS, "afkMs", 0L,
                            "afkReason", "none", "closeCause", "logout", "openSession", Boolean.FALSE,
                            "inputEvents", 60L, "totalEvents", (long) seq)));
        }
        resetClock();
        return out;
    }

    public static List<RawEvent> sparseEvents() {
        resetClock();
        List<RawEvent> out = new ArrayList<>();
        String sessionId = "s0001";
        int seq = 1;
        out.add(ev(sessionId, seq++, EventType.SESSION_START, 0L, Map.of(
                "gameVersion", "1.20.1", "loader", "forge", "privacyClass", "singleplayer",
                "sessionStartDate", "2026-09-01", "cheatsEnabled", Boolean.FALSE)));
        for (long t = 0; t <= 600_000L; t += 30_000L) {
            out.add(timed(sessionId, seq++, EventType.SESSION_HEARTBEAT, t, 0L, false,
                    Map.of("activity", "active", "sinceLastInputMs", 1_000L, "tickProgress", 600L)));
        }
        out.add(ev(sessionId, seq++, EventType.ADVANCEMENT_GAINED, 120_000L,
                Map.of("advancementId", "minecraft:story/root", "parentId", "",
                        "isRecipe", Boolean.FALSE, "grantedByCommand", Boolean.FALSE)));
        out.add(ev(sessionId, seq, EventType.SESSION_END, 600_000L,
                Map.of("wallMs", 600_000L, "activeMs", 600_000L, "afkMs", 0L, "afkReason", "none",
                        "closeCause", "logout", "openSession", Boolean.FALSE, "inputEvents", 1L,
                        "totalEvents", (long) seq)));
        resetClock();
        return out;
    }

    public static List<RawEvent> emptyEvents() {
        return List.of();
    }

    public static ContentCatalog emptyCatalog() {
        return new ContentCatalog();
    }

    private static String stallUnit(int s) {
        return s % 2 == 0 ? "minecraft:nether/obtain_blaze_rod" : "pack:chapter_energy";
    }

    private static String advancementId(int i) {
        String[] ids = {"minecraft:story/root", "minecraft:story/mine_stone",
                "minecraft:story/upgrade_tools", "minecraft:story/smelt_iron",
                "minecraft:story/iron_tools", "minecraft:story/obtain_armor",
                "minecraft:story/lava_bucket", "minecraft:story/deflect_arrow",
                "minecraft:nether/root", "minecraft:nether/obtain_blaze_rod",
                "minecraft:end/root", "minecraft:end/kill_dragon"};
        return ids[Math.max(0, Math.min(ids.length - 1, i))];
    }

    private static String questId(int i) {
        String[] ids = {"pack:chapter_stone", "pack:chapter_iron", "pack:chapter_nether",
                "pack:chapter_energy", "pack:chapter_end"};
        return ids[Math.max(0, Math.min(ids.length - 1, i))];
    }
}
