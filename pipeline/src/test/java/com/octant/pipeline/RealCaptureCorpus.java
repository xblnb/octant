package com.octant.pipeline;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.EventCategory;
import com.octant.common.model.EventSource;
import com.octant.common.model.RawEventSchema;
import com.octant.pipeline.adapter.CaptureEventAdapter;
import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.UnitKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RealCaptureCorpus {

    public static final String PLAYER_KEY = "3f7a91c2d45be806";
    public static final String SESSION_TAG = "s";
    public static final int SESSION_COUNT = 12;
    public static final long SESSION_ACTIVE_MS = 7_200_000L;
    public static final long SESSION_STRIDE_MS = 21_600_000L;

    private RealCaptureCorpus() {
    }

    public static ContentCatalog catalog() {
        ContentCatalog c = new ContentCatalog();
        c.register(UnitKind.ADVANCEMENT, List.of(
                "minecraft:story/root", "minecraft:story/mine_stone", "minecraft:story/upgrade_tools",
                "minecraft:story/smelt_iron", "minecraft:story/iron_tools", "minecraft:story/obtain_armor",
                "minecraft:story/lava_bucket", "minecraft:story/deflect_arrow",
                "minecraft:nether/root", "minecraft:nether/obtain_blaze_rod",
                "minecraft:end/root", "minecraft:end/kill_dragon"), false);
        c.register(UnitKind.QUEST, List.of("pack:chapter_stone", "pack:chapter_iron",
                "pack:chapter_nether", "pack:chapter_energy", "pack:chapter_end"), false);
        c.register(UnitKind.RECIPE, List.of("minecraft:crafting_table", "minecraft:furnace",
                "minecraft:iron_pickaxe", "minecraft:iron_chestplate", "minecraft:blast_furnace",
                "minecraft:anvil", "pack:crushing_plant", "pack:energy_cell"), false);
        c.register(UnitKind.ITEM, List.of("minecraft:cobblestone", "minecraft:iron_ingot",
                "minecraft:coal", "minecraft:stick", "minecraft:bread", "pack:energy_cell",
                "minecraft:diamond"), false);
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

    public static List<CaptureEvent> events() {
        List<CaptureEvent> out = new ArrayList<>();
        for (int s = 0; s < SESSION_COUNT; s++) {
            int[] seq = {1};
            String session = CaptureEvent.sessionId(s + 1);
            long base = (long) s * SESSION_STRIDE_MS;

            out.add(add(out, session, seq, CaptureEventType.SESSION_START, base, null, Map.of(
                    "gameVersion", "1.20.1", "loader", "forge", "privacyClass", "singleplayer",
                    "sessionStartDate", "2026-09-" + String.format("%02d", 1 + (s % 9)),
                    "cheatsEnabled", Boolean.FALSE)));

            for (long t = 0; t <= SESSION_ACTIVE_MS; t += 30_000L) {
                out.add(add(out, session, seq, CaptureEventType.SESSION_HEARTBEAT, base + t, null,
                        Map.of("activity", "active", "sinceLastInputMs", 1_000L, "tickProgress", 600L)));
            }

            int advances = Math.min(11, s + 3);
            int prev = Math.max(0, Math.min(11, s + 2));
            for (int i = prev; i < advances; i++) {
                out.add(add(out, session, seq, CaptureEventType.ADVANCEMENT_GAINED,
                        base + 120_000L + i * 60_000L, null, Map.of(
                                "advancementId", advancementId(i),
                                "parentId", i == 0 ? "" : advancementId(i - 1),
                                "isRecipe", Boolean.FALSE, "grantedByCommand", Boolean.FALSE)));
            }
            int quests = Math.min(5, 1 + s / 3);
            int prevQ = Math.min(5, 1 + Math.max(0, s - 1) / 3);
            for (int i = prevQ; i < quests; i++) {
                out.add(add(out, session, seq, CaptureEventType.QUEST_COMPLETED,
                        base + 200_000L + i * 90_000L, null, Map.of(
                                "questId", questId(i), "chapterId", "pack:chapter_" + i,
                                "questSource", "packquests")));
            }
            for (int i = 0; i < quests; i++) {
                out.add(add(out, session, seq, CaptureEventType.QUEST_PROGRESSED,
                        base + 260_000L + i * 90_000L, null, Map.of(
                                "questId", questId(i), "stage", (long) (s + 1), "objectiveCount", 2L)));
            }

            out.add(add(out, session, seq, CaptureEventType.DIMENSION_ENTERED, base + 300_000L, null,
                    Map.of("dimension", "minecraft:overworld", "fromDimension", "")));
            if (s % 2 == 1) {
                out.add(add(out, session, seq, CaptureEventType.DIMENSION_ENTERED, base + 1_200_000L,
                        null, Map.of("dimension", "minecraft:the_nether",
                                "fromDimension", "minecraft:overworld")));
            }
            if (s % 3 == 2) {
                out.add(add(out, session, seq, CaptureEventType.DIMENSION_ENTERED, base + 2_400_000L,
                        null, Map.of("dimension", "minecraft:the_end",
                                "fromDimension", "minecraft:overworld")));
            }
            String[] biomes = {"minecraft:plains", "minecraft:forest", "minecraft:desert",
                    "minecraft:nether_wastes", "minecraft:swamp"};
            out.add(add(out, session, seq, CaptureEventType.BIOME_VISITED, base + 360_000L, null,
                    Map.of("biome", biomes[s % biomes.length], "dimension", "minecraft:overworld",
                            "firstVisit", Boolean.TRUE)));
            String region = "minecraft:overworld#0#" + (s * 512L);
            out.add(add(out, session, seq, CaptureEventType.STRUCTURE_ENTERED, base + 600_000L, null,
                    Map.of("structure", s % 2 == 0 ? "minecraft:village_plains" : "minecraft:mineshaft",
                            "dimension", "minecraft:overworld", "regionKey", region)));
            out.add(add(out, session, seq, CaptureEventType.REGION_FIRST_VISIT, base + 660_000L, null,
                    Map.of("dimension", "minecraft:overworld", "regionKey", region,
                            "biome", biomes[s % biomes.length])));

            String[] items = {"minecraft:cobblestone", "minecraft:iron_ingot", "minecraft:coal",
                    "minecraft:stick", "minecraft:bread"};
            for (int i = 0; i < 8; i++) {
                out.add(add(out, session, seq, CaptureEventType.ITEM_ACTION,
                        base + 700_000L + i * 45_000L, null, Map.of(
                                "item", items[i % items.length], "action", "craft_output",
                                "count", 4L, "creativeGiven", Boolean.FALSE)));
            }
            out.add(add(out, session, seq, CaptureEventType.ITEM_ACTION, base + 1_500_000L, null,
                    Map.of("item", "pack:energy_cell", "action", "obtain", "count", 2L,
                            "creativeGiven", Boolean.FALSE)));
            out.add(add(out, session, seq, CaptureEventType.RECIPE_UNLOCKED, base + 760_000L, null,
                    Map.of("recipeId", "minecraft:iron_pickaxe", "unlockSource", "craft")));
            out.add(add(out, session, seq, CaptureEventType.RECIPE_ATTEMPTED, base + 820_000L, null,
                    Map.of("recipeId", "minecraft:iron_pickaxe", "station", "minecraft:crafting_table",
                            "outcome", s % 4 == 0 ? "missing_materials" : "success")));
            out.add(add(out, session, seq, CaptureEventType.CONTAINER_SNAPSHOT, base + 900_000L, null,
                    Map.of("containerKey", region + "#1",
                            "itemsDigest", List.of(digest("minecraft:cobblestone", 64L)),
                            "truncated", Boolean.FALSE)));

            out.add(add(out, session, seq, CaptureEventType.MACHINE_OBSERVED,
                    base + 1_000_000L + s * 1_000L, null, Map.of(
                            "machineBlock", "minecraft:furnace",
                            "regionKey", "minecraft:overworld#0#0", "outputDelta10m", 16L,
                            "deviceClass", "auto_producer", "reusedSessions", (long) (s + 1))));
            if (s % 2 == 0) {
                out.add(add(out, session, seq, CaptureEventType.MACHINE_OBSERVED, base + 1_100_000L,
                        null, Map.of("machineBlock", "pack:crusher",
                                "regionKey", "minecraft:overworld#0#0", "outputDelta10m", 24L,
                                "deviceClass", "auto_producer", "reusedSessions", (long) (s + 2))));
            }
            out.add(add(out, session, seq, CaptureEventType.MACHINE_INTERACTED, base + 1_160_000L,
                    4_000L, Map.of("machineBlock", "pack:crusher", "resolved", "resolved",
                            "activeMs", 4_000L, "durMs", 4_000L)));
            out.add(add(out, session, seq, CaptureEventType.AUTOMATION_CYCLE, base + 1_260_000L, null,
                    Map.of("machineBlock", "pack:crusher", "cycleMs", 12_000L,
                            "outputItem", "minecraft:iron_ingot", "outputCount", 3L)));

            for (int i = 0; i < 4; i++) {
                long t = base + 1_800_000L + i * 240_000L;
                String key = "minecraft:zombie#" + (i + 1);
                out.add(add(out, session, seq, CaptureEventType.COMBAT_STARTED, t, 180_000L, Map.of(
                        "opponentKey", key, "entityType", "minecraft:zombie",
                        "opponentThreat", 12.0d, "farmPattern", Boolean.FALSE, "shared", Boolean.FALSE,
                        "durMs", 180_000L)));
                out.add(add(out, session, seq, CaptureEventType.COMBAT_ENDED, t + 180_000L, 180_000L,
                        Map.of("opponentKey", key, "outcome", i == 3 ? "flee" : "kill",
                                "damageDealt", 40.0d, "damageTaken", i == 3 ? 24.0d : 6.0d,
                                "lastHitByPlayer", Boolean.TRUE, "resolved", "resolved",
                                "tactic", i % 3 == 0 ? "melee" : (i % 3 == 1 ? "ranged" : "trap"),
                                "durMs", 180_000L)));
            }
            long farmT = base + 3_000_000L;
            out.add(add(out, session, seq, CaptureEventType.COMBAT_STARTED, farmT, 120_000L, Map.of(
                    "opponentKey", "minecraft:skeleton#1", "entityType", "minecraft:skeleton",
                    "opponentThreat", 8.0d, "farmPattern", Boolean.TRUE, "shared", Boolean.FALSE,
                    "durMs", 120_000L)));
            out.add(add(out, session, seq, CaptureEventType.COMBAT_ENDED, farmT + 120_000L, 120_000L,
                    Map.of("opponentKey", "minecraft:skeleton#1", "outcome", "kill",
                            "damageDealt", 30.0d, "damageTaken", 2.0d,
                            "lastHitByPlayer", Boolean.TRUE, "resolved", "resolved",
                            "tactic", "melee", "durMs", 120_000L)));

            String[] causes = {"minecraft:fall", "minecraft:lava", "minecraft:zombie"};
            String[] classes = {"fall", "lava", "mob"};
            int ci = s % 3;
            out.add(add(out, session, seq, CaptureEventType.PLAYER_DEATH, base + 3_300_000L, null,
                    Map.of("deathCause", causes[ci], "causeClass", classes[ci],
                            "killerEntityType", "mob".equals(classes[ci]) ? "minecraft:zombie" : "",
                            "intentional", Boolean.FALSE)));

            String unit = stallUnit(s);
            long stallT = base + 2_700_000L;
            out.add(add(out, session, seq, CaptureEventType.STALL_SEGMENT, stallT, 90_000L, Map.of(
                    "unitId", unit, "unitKind", "advancement", "dwellMs", 90_000L,
                    "activeDensityPm", 1.4d, "proxy", Boolean.FALSE, "durMs", 90_000L)));
            out.add(add(out, session, seq, CaptureEventType.REPEATED_FAILURE, base + 2_760_000L, null,
                    Map.of("unitId", unit, "failKind", "recipe_missing", "countInWindow", 4L)));
            out.add(add(out, session, seq, CaptureEventType.RESOURCE_BLOCKED, base + 2_820_000L, null,
                    Map.of("unitId", unit, "station", "minecraft:crafting_table",
                            "shortfallDigest", List.of(shortfall("minecraft:iron_ingot", 3L)),
                            "resolution", "resolved")));

            out.add(add(out, session, seq, CaptureEventType.SESSION_END,
                    base + SESSION_ACTIVE_MS, null, Map.of(
                            "wallMs", SESSION_ACTIVE_MS, "activeMs", SESSION_ACTIVE_MS, "afkMs", 0L,
                            "afkReason", "none", "closeCause", "logout",
                            "openSession", Boolean.FALSE, "inputEvents", 60L,
                            "totalEvents", (long) seq[0])));
        }
        return out;
    }

    public static CaptureEventAdapter.Conversion toPipeline(List<CaptureEvent> events) {
        return CaptureEventAdapter.toPipelineTolerant(events);
    }

    public static ExportPipeline.Input input() {
        List<CaptureEvent> events = events();
        CaptureEventAdapter.Conversion conv = toPipeline(events);
        if (conv.rejectedCount() > 0) {
            throw new IllegalStateException("真实采集语料存在被拒事件（不应发生）："
                    + conv.rejections().subList(0, Math.min(5, conv.rejections().size())));
        }
        AnalysisReport report = AnalysisReport.analyze(conv.events(), catalog(), "forge");
        List<com.octant.pipeline.raw.RawEvent> pipelineEvents = conv.events();
        return new ExportPipeline.Input(report, () -> pipelineEvents, catalog(), "0.1.0", "1.20.1",
                "forge", "samples-salt-2026",
                List.of("C1", "C2", "C3", "C5", "C6", "C7", "C8"), List.of("minecraft", "octant"));
    }

    private static Map<String, Object> digest(String item, long count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", item);
        m.put("count", count);
        return m;
    }

    private static Map<String, Object> shortfall(String item, long missingCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", item);
        m.put("missingCount", missingCount);
        return m;
    }

    private static CaptureEvent add(List<CaptureEvent> out, String session, int[] seq,
                                    CaptureEventType type, long tRelMs, Long durMs,
                                    Map<String, Object> payload) {
        CaptureEvent e = new CaptureEvent(com.octant.common.model.RawEventSchema.VERSION,
                CaptureEvent.eventId(seq[0]++), session, PLAYER_KEY, type, tRelMs,
                CaptureEvent.tickOf(tRelMs), type.category(), EventSource.FORGE, true, durMs, payload);
        return e;
    }

    public static EventCategory categoryOf(CaptureEventType type) {
        return type.category();
    }

    public static Map<String, Object> envelopeOf(CaptureEvent e) {
        return new LinkedHashMap<>(e.toOrderedMap());
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
