package com.octant.common.collection;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.model.EventCategory;
import com.octant.common.model.EventSource;
import com.octant.common.model.PayloadSchema;
import com.octant.common.model.RawEventSchema;
import com.octant.common.session.SessionClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionContractTest {

    private static final String KEY = "7f3a1c9b04d2e6a5";

    private static CaptureEvent ev(CaptureEventType type, long tRelMs, Long durMs,
                                   Map<String, Object> payload) {
        return new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(1), CaptureEvent.sessionId(1),
                KEY, type, tRelMs, CaptureEvent.tickOf(tRelMs), type.category(),
                EventSource.FABRIC, true, durMs, payload);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("§2.4 事件类型目录：必须恰好 24 个具名类型")
    class TypeCatalog {

        @Test
        @DisplayName("枚举数量与契约一致，且 ID 逐字对齐契约写法")
        void twentyFourTypes() {
            assertEquals(24, CaptureEventType.values().length);
            assertEquals(24, CaptureEventType.CONTRACT_TYPE_COUNT,
                    "常量与枚举数量必须同步");
            assertEquals(CaptureEventType.CONTRACT_TYPE_COUNT, CaptureEventType.values().length);
            assertTrue(CaptureEventType.byEventId("session_environment").isPresent(),
                    "第 24 型必须存在（dc@1.5.0 EV-S4）");
            assertTrue(CaptureEventType.byEventId("advancement_gained").isPresent());
            assertTrue(CaptureEventType.byEventId("region_first_visit").isPresent());
            assertTrue(CaptureEventType.byEventId("resource_blocked").isPresent());
            assertFalse(CaptureEventType.byEventId("mob_engaged").isPresent(),
                    "PoC 的 mob_engaged 不在契约目录内，必须被识别为未知类型");
            assertThrows(ContractException.class, () -> CaptureEventType.require("mob_engaged"));
        }

        @Test
        @DisplayName("区间型类型集合与 §2.5「区间型(D)」逐项一致")
        void intervalTypes() {
            long intervals = java.util.Arrays.stream(CaptureEventType.values())
                    .filter(CaptureEventType::isInterval).count();
            assertEquals(4, intervals, "§2.5 标记为 D 的恰好 4 型");
            assertTrue(CaptureEventType.COMBAT_STARTED.isInterval());
            assertTrue(CaptureEventType.COMBAT_ENDED.isInterval());
            assertTrue(CaptureEventType.MACHINE_INTERACTED.isInterval());
            assertTrue(CaptureEventType.STALL_SEGMENT.isInterval());
            assertFalse(CaptureEventType.ITEM_ACTION.isInterval());
        }
    }

    @Nested
    @DisplayName("§2.2 封套：11 字段与硬约束")
    class Envelope {

        @Test
        @DisplayName("字段序与数量：11 字段封套（标量型无 durMs）")
        void elevenFieldsWithoutDur() {
            CaptureEvent e = ev(CaptureEventType.DIMENSION_ENTERED, 1000L, null,
                    map("dimension", "minecraft:overworld"));
            assertEquals(11, e.toOrderedMap().size());
            assertEquals(java.util.List.of("schemaVersion", "eventId", "sessionId", "playerKey", "type",
                            "tRelMs", "tTick", "cat", "src", "confirmed", "payload"),
                    java.util.List.copyOf(e.toOrderedMap().keySet()));
        }

        @Test
        @DisplayName("区间型事件必须带 durMs，标量型不得出现")
        void durPresence() {
            assertThrows(ContractException.class, () -> ev(CaptureEventType.COMBAT_STARTED, 1000L, null,
                    map("opponentKey", "minecraft:zombie#1", "entityType", "minecraft:zombie",
                            "opponentThreat", 20.0d, "farmPattern", false, "shared", false)));
            assertThrows(ContractException.class, () -> ev(CaptureEventType.DIMENSION_ENTERED, 1000L, 5L,
                    map("dimension", "minecraft:overworld")));
        }

        @Test
        @DisplayName("tTick 必须等于 round(tRelMs/50)，不一致即拒绝")
        void tickConversion() {
            assertEquals(0L, CaptureEvent.tickOf(0L));
            assertEquals(1L, CaptureEvent.tickOf(50L));
            assertEquals(21L, CaptureEvent.tickOf(1049L), "1049/50 = 20.98 → round = 21");
            assertEquals(20L, CaptureEvent.tickOf(1024L), "1024/50 = 20.48 → round = 20");
            assertThrows(ContractException.class, () -> new CaptureEvent(RawEventSchema.VERSION,
                    CaptureEvent.eventId(1), CaptureEvent.sessionId(1), KEY,
                    CaptureEventType.DIMENSION_ENTERED, 1000L, 999L, EventCategory.EXPLORE,
                    EventSource.FABRIC, true, null, map("dimension", "minecraft:overworld")));
        }

        @Test
        @DisplayName("cat 必须等于 type 所属类别；src/playerKey/schemaVersion 形态受检")
        void envelopeStrictness() {
            assertThrows(ContractException.class, () -> new CaptureEvent(RawEventSchema.VERSION,
                    CaptureEvent.eventId(1), CaptureEvent.sessionId(1), KEY,
                    CaptureEventType.DIMENSION_ENTERED, 1000L, 20L, EventCategory.COMBAT,
                    EventSource.FABRIC, true, null, map("dimension", "minecraft:overworld")));
            assertThrows(ContractException.class, () -> new CaptureEvent("raw-event-schema@9.9.9",
                    CaptureEvent.eventId(1), CaptureEvent.sessionId(1), KEY,
                    CaptureEventType.DIMENSION_ENTERED, 1000L, 20L, EventCategory.EXPLORE,
                    EventSource.FABRIC, true, null, map("dimension", "minecraft:overworld")));
            assertThrows(ContractException.class, () -> new CaptureEvent(RawEventSchema.VERSION,
                    CaptureEvent.eventId(1), CaptureEvent.sessionId(1), "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                    CaptureEventType.DIMENSION_ENTERED, 1000L, 20L, EventCategory.EXPLORE,
                    EventSource.FABRIC, true, null, map("dimension", "minecraft:overworld")));
        }

        @Test
        @DisplayName("确定性 ID：e<3 位>/s<4 位>，越界拒绝（禁止随机）")
        void deterministicIds() {
            assertEquals("e001", CaptureEvent.eventId(1));
            assertEquals("e042", CaptureEvent.eventId(42));
            assertEquals("s0007", CaptureEvent.sessionId(7));
            assertThrows(ContractException.class, () -> CaptureEvent.eventId(0));
            assertThrows(ContractException.class, () -> CaptureEvent.eventId(1000));
            assertThrows(ContractException.class, () -> CaptureEvent.sessionId(10_000));
        }

        @Test
        @DisplayName("tRelMs 越界（> 7 天）拒绝")
        void tRelMsBounds() {
            assertThrows(ContractException.class, () -> ev(CaptureEventType.DIMENSION_ENTERED,
                    RawEventSchema.SESSION_MAX_TREL_MS + 1L, null, map("dimension", "minecraft:overworld")));
        }
    }

    @Nested
    @DisplayName("§2.4 payload 字段表：键集合必须完全相同")
    class PayloadTables {

        @Test
        @DisplayName("MAY 字段缺失时按契约补缺省值（字符串 \"\" / 数值 -1），禁止 null")
        void mayFieldsGetDefaults() {
            Map<String, Object> enc = PayloadSchema.encode(CaptureEventType.ADVANCEMENT_GAINED,
                    map("advancementId", "minecraft:story/smelt_iron", "isRecipe", false,
                            "grantedByCommand", false), 1000L);
            assertEquals("", enc.get("parentId"), "根节点 parentId 缺省必须是 \"\"（不是 null）");
            assertFalse(enc.containsKey("null"));

            Map<String, Object> s1 = PayloadSchema.encode(CaptureEventType.SESSION_START,
                    map("privacyClass", "singleplayer", "gameVersion", "1.21.1", "loader", "neoforge",
                            "sessionStartDate", "2026-09-26", "cheatsEnabled", false), 0L);
            assertEquals(-1, s1.get("worldCreateDay"));
            assertEquals(-1, s1.get("loadMs"));
            assertEquals("", s1.get("sessionStartLocalBucket"));
        }

        @Test
        @DisplayName("MUST 字段缺失即拒绝（REQ-DATA-06 丢弃并计数）")
        void mustFieldsRequired() {
            assertThrows(ContractException.class, () -> PayloadSchema.encode(
                    CaptureEventType.SESSION_START,
                    map("gameVersion", "1.21.1", "loader", "neoforge",
                            "sessionStartDate", "2026-09-26", "cheatsEnabled", false), 0L));
        }

        @Test
        @DisplayName("字段表外的键即 schema 违反（多一个也不行）")
        void extraKeysRejected() {
            assertThrows(ContractException.class, () -> PayloadSchema.encode(
                    CaptureEventType.ITEM_ACTION,
                    map("item", "minecraft:diamond", "action", "obtain", "count", 1,
                            "creativeGiven", false, "displayName", "玩家自定义名"), 1000L));
        }

        @Test
        @DisplayName("取值范围是硬约束：越界拒绝、闭集外拒绝、自由文本拒绝")
        void rangeAndClosedSets() {
            assertThrows(ContractException.class, () -> PayloadSchema.encode(CaptureEventType.ITEM_ACTION,
                    map("item", "minecraft:diamond", "action", "obtain", "count", 0, "creativeGiven", false),
                    1000L));
            assertThrows(ContractException.class, () -> PayloadSchema.encode(CaptureEventType.ITEM_ACTION,
                    map("item", "minecraft:diamond", "action", "eaten", "count", 1, "creativeGiven", false),
                    1000L));
            assertThrows(ContractException.class, () -> PayloadSchema.encode(
                    CaptureEventType.DIMENSION_ENTERED,
                    map("dimension", "the 世界 with 空格"), 1000L));
        }

        @Test
        @DisplayName("itemsDigest 元素数上限 32、shortfallDigest 上限 16")
        void digestLimits() {
            java.util.List<Map<String, Object>> tooMany = new java.util.ArrayList<>();
            for (int i = 0; i < 33; i++) {
                tooMany.add(map("item", "minecraft:stone", "count", 1));
            }
            assertThrows(ContractException.class, () -> PayloadSchema.encode(
                    CaptureEventType.CONTAINER_SNAPSHOT,
                    map("containerKey", "minecraft:overworld#0#0#1", "itemsDigest", tooMany,
                            "truncated", false), 1000L));
        }
    }

    @Nested
    @DisplayName("§2.4 全部 24 型逐型可编码（不得只实现自造子集）")
    class AllTypesEncodable {

        private final java.util.Map<CaptureEventType, Map<String, Object>> full = Map.ofEntries(
                Map.entry(CaptureEventType.SESSION_START, map("worldCreateDay", 12, "loadMs", 4200,
                        "privacyClass", "singleplayer", "gameVersion", "1.21.1", "loader", "neoforge",
                        "sessionStartDate", "2026-09-26", "sessionStartLocalBucket", "evening",
                        "cheatsEnabled", false)),
                Map.entry(CaptureEventType.SESSION_END, map("wallMs", 5_400_000L, "activeMs", 4_800_000L,
                        "afkMs", 600_000L, "afkReason", "no_input", "closeCause", "logout",
                        "openSession", false, "inputEvents", 2310, "totalEvents", 4213)),
                Map.entry(CaptureEventType.SESSION_HEARTBEAT, map("activity", "active",
                        "sinceLastInputMs", 12_000L, "tickProgress", 240L)),
                Map.entry(CaptureEventType.SESSION_ENVIRONMENT, map(
                        "envSchemaVersion", RawEventSchema.ENV_SNAPSHOT_SCHEMA_VERSION,
                        "snapshotRev", 3, "snapshotDigest16", "0123456789abcdef",
                        "observedAtRelMs", 1_000_000L, "observedAtTick", 20_000L)),
                Map.entry(CaptureEventType.ADVANCEMENT_GAINED, map("advancementId",
                        "minecraft:story/smelt_iron", "parentId", "minecraft:story/root",
                        "isRecipe", false, "grantedByCommand", false)),
                Map.entry(CaptureEventType.QUEST_COMPLETED, map("questId", "chapter2/quest_14",
                        "chapterId", "chapter2", "questSource", "ftbquests")),
                Map.entry(CaptureEventType.QUEST_PROGRESSED, map("questId", "chapter2/quest_14",
                        "stage", 3, "objectiveCount", 2)),
                Map.entry(CaptureEventType.DIMENSION_ENTERED, map("dimension", "minecraft:the_nether",
                        "fromDimension", "minecraft:overworld")),
                Map.entry(CaptureEventType.BIOME_VISITED, map("biome", "minecraft:plains",
                        "dimension", "minecraft:overworld", "firstVisit", true)),
                Map.entry(CaptureEventType.STRUCTURE_ENTERED, map("structure", "minecraft:village",
                        "dimension", "minecraft:overworld", "regionKey", "minecraft:overworld#1#2")),
                Map.entry(CaptureEventType.REGION_FIRST_VISIT, map("dimension", "minecraft:overworld",
                        "regionKey", "minecraft:overworld#1#2", "biome", "minecraft:plains")),
                Map.entry(CaptureEventType.COMBAT_STARTED, map("opponentKey", "minecraft:zombie#1",
                        "entityType", "minecraft:zombie", "opponentThreat", 20.0d,
                        "farmPattern", false, "shared", false, "durMs", 8_400L)),
                Map.entry(CaptureEventType.COMBAT_ENDED, map("opponentKey", "minecraft:zombie#1",
                        "outcome", "kill", "damageDealt", 20.0d, "damageTaken", 3.0d,
                        "lastHitByPlayer", true, "resolved", "resolved", "tactic", "melee",
                        "durMs", 8_400L)),
                Map.entry(CaptureEventType.PLAYER_DEATH, map("deathCause", "minecraft:fall",
                        "causeClass", "fall", "killerEntityType", "", "intentional", false)),
                Map.entry(CaptureEventType.ITEM_ACTION, map("item", "minecraft:diamond",
                        "action", "obtain", "count", 3, "creativeGiven", false)),
                Map.entry(CaptureEventType.RECIPE_UNLOCKED, map("recipeId", "minecraft:iron_pickaxe",
                        "unlockSource", "craft")),
                Map.entry(CaptureEventType.RECIPE_ATTEMPTED, map("recipeId", "minecraft:iron_pickaxe",
                        "station", "minecraft:crafting_table", "outcome", "missing_materials")),
                Map.entry(CaptureEventType.CONTAINER_SNAPSHOT, map("containerKey",
                        "minecraft:overworld#1#2#1",
                        "itemsDigest", java.util.List.of(map("item", "minecraft:stone", "count", 64)),
                        "truncated", false)),
                Map.entry(CaptureEventType.MACHINE_OBSERVED, map("machineBlock", "minecraft:furnace",
                        "regionKey", "minecraft:overworld#1#2", "outputDelta10m", 12,
                        "deviceClass", "auto_producer", "reusedSessions", 3)),
                Map.entry(CaptureEventType.MACHINE_INTERACTED, map("machineBlock", "minecraft:furnace",
                        "resolved", "resolved", "activeMs", 4_500, "durMs", 4_500L)),
                Map.entry(CaptureEventType.AUTOMATION_CYCLE, map("machineBlock", "minecraft:furnace",
                        "cycleMs", 12_000, "outputItem", "minecraft:iron_ingot", "outputCount", 2)),
                Map.entry(CaptureEventType.STALL_SEGMENT, map("unitId", "minecraft:story/smelt_iron",
                        "unitKind", "advancement", "dwellMs", 900_000L, "activeDensityPm", 1.2d,
                        "proxy", false, "durMs", 900_000L)),
                Map.entry(CaptureEventType.REPEATED_FAILURE, map("unitId", "minecraft:story/smelt_iron",
                        "failKind", "death", "countInWindow", 3)),
                Map.entry(CaptureEventType.RESOURCE_BLOCKED, map("unitId", "minecraft:story/smelt_iron",
                        "station", "minecraft:furnace",
                        "shortfallDigest", java.util.List.of(map("item", "minecraft:iron_ore",
                                "missingCount", 2)),
                        "resolution", "resolved")));

        @Test
        @DisplayName("24 型逐型构造 CaptureEvent 成功，且 payload 键集合与字段表完全相同")
        void everyTypeEncodes() {
            assertEquals(CaptureEventType.CONTRACT_TYPE_COUNT, full.size(),
                    "本测试必须覆盖全部 24 型，缺一即失败");
            for (CaptureEventType type : CaptureEventType.values()) {
                Map<String, Object> payload = full.get(type);
                assertNotNull(payload, "缺少类型的完整 payload 夹具：" + type);
                Long dur = type.isInterval() ? (Long) payload.get("durMs") : null;
                CaptureEvent e = new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(1),
                        CaptureEvent.sessionId(1), KEY, type, 1_000_000L,
                        CaptureEvent.tickOf(1_000_000L), type.category(), EventSource.NEOFORGE,
                        true, dur, payload);

                Map<String, Object> encoded = e.payload();
                assertEquals(expectedFieldNames(type), java.util.List.copyOf(encoded.keySet()),
                        type + " 的 payload 键集合或键序与 §2.4 字段表不一致");
                assertEquals(type.category(), EventCategory.byCode(e.category().code()).orElseThrow());
                assertEquals(CaptureEvent.tickOf(e.tRelMs()), e.tTick());
            }
        }

        private java.util.List<String> expectedFieldNames(CaptureEventType type) {
            return switch (type) {
                case SESSION_START -> java.util.List.of("worldCreateDay", "loadMs", "privacyClass",
                        "gameVersion", "loader", "sessionStartDate", "sessionStartLocalBucket",
                        "cheatsEnabled");
                case SESSION_END -> java.util.List.of("wallMs", "activeMs", "afkMs", "afkReason",
                        "closeCause", "openSession", "inputEvents", "totalEvents");
                case SESSION_HEARTBEAT -> java.util.List.of("activity", "sinceLastInputMs", "tickProgress");
                case SESSION_ENVIRONMENT -> java.util.List.of("envSchemaVersion", "snapshotRev",
                        "snapshotDigest16", "observedAtRelMs", "observedAtTick");
                case ADVANCEMENT_GAINED -> java.util.List.of("advancementId", "parentId", "isRecipe",
                        "grantedByCommand");
                case QUEST_COMPLETED -> java.util.List.of("questId", "chapterId", "questSource");
                case QUEST_PROGRESSED -> java.util.List.of("questId", "stage", "objectiveCount");
                case DIMENSION_ENTERED -> java.util.List.of("dimension", "fromDimension");
                case BIOME_VISITED -> java.util.List.of("biome", "dimension", "firstVisit");
                case STRUCTURE_ENTERED -> java.util.List.of("structure", "dimension", "regionKey");
                case REGION_FIRST_VISIT -> java.util.List.of("dimension", "regionKey", "biome");
                case COMBAT_STARTED -> java.util.List.of("opponentKey", "entityType", "opponentThreat",
                        "farmPattern", "shared", "durMs");
                case COMBAT_ENDED -> java.util.List.of("opponentKey", "outcome", "damageDealt",
                        "damageTaken", "lastHitByPlayer", "resolved", "tactic", "durMs");
                case PLAYER_DEATH -> java.util.List.of("deathCause", "causeClass", "killerEntityType",
                        "intentional");
                case ITEM_ACTION -> java.util.List.of("item", "action", "count", "creativeGiven");
                case RECIPE_UNLOCKED -> java.util.List.of("recipeId", "unlockSource");
                case RECIPE_ATTEMPTED -> java.util.List.of("recipeId", "station", "outcome");
                case CONTAINER_SNAPSHOT -> java.util.List.of("containerKey", "itemsDigest", "truncated");
                case MACHINE_OBSERVED -> java.util.List.of("machineBlock", "regionKey", "outputDelta10m",
                        "deviceClass", "reusedSessions");
                case MACHINE_INTERACTED -> java.util.List.of("machineBlock", "resolved", "activeMs",
                        "durMs");
                case AUTOMATION_CYCLE -> java.util.List.of("machineBlock", "cycleMs", "outputItem",
                        "outputCount");
                case STALL_SEGMENT -> java.util.List.of("unitId", "unitKind", "dwellMs",
                        "activeDensityPm", "proxy", "durMs");
                case REPEATED_FAILURE -> java.util.List.of("unitId", "failKind", "countInWindow");
                case RESOURCE_BLOCKED -> java.util.List.of("unitId", "station", "shortfallDigest",
                        "resolution");
            };
        }
    }

    @Nested
    @DisplayName("§2.3 时间基准与单调性")
    class TimeBasis {

        @Test
        @DisplayName("回拨的墙钟不得让 tRelMs 倒退（注入回拨时钟）")
        void monotonicUnderClockRollback() {
            AtomicLong nanos = new AtomicLong(1_000_000_000L);
            SessionClock clock = new SessionClock(nanos::get);

            assertEquals(0L, clock.observe());
            nanos.addAndGet(1_000_000_000L);
            assertEquals(1000L, clock.observe());
            nanos.addAndGet(-900_000_000L);
            assertEquals(1000L, clock.observe(), "回拨后 tRelMs 必须保持不小于上一个值");
            nanos.addAndGet(1_000_000_000L);
            assertEquals(1100L, clock.observe());
        }

        @Test
        @DisplayName("tTick 与 tRelMs 的换算误差 ≤ 1 tick")
        void tickErrorWithinOneTick() {
            for (long ms : new long[] {0L, 1L, 24L, 25L, 49L, 50L, 51L, 1_049L, 604_800_000L}) {
                long tick = CaptureEvent.tickOf(ms);
                assertTrue(Math.abs(tick * 50L - ms) <= 50L,
                        "tRelMs=" + ms + " → tTick=" + tick + " 的换算误差超过 1 tick");
            }
        }

        @Test
        @DisplayName("超过切分间隔必须切会话；超过 7 天必须强制闭合")
        void forcedClosure() {
            AtomicLong nanos = new AtomicLong(0L);
            SessionClock split = new SessionClock(nanos::get);
            split.observe();
            nanos.addAndGet(1_000_000_000L);
            split.observe();
            nanos.addAndGet(RawEventSchema.SESSION_SPLIT_GAP_MS * 1_000_000L);
            ContractException splitErr = assertThrows(ContractException.class, split::observe);
            assertTrue(splitErr.getMessage().contains("切分新会话"));
            assertEquals("split_gap", split.closeCause());

            AtomicLong slow = new AtomicLong(0L);
            SessionClock max = new SessionClock(slow::get);
            max.observe();
            long stepMs = RawEventSchema.SESSION_SPLIT_GAP_MS - 60_000L;
            long advanced = 0L;
            while (advanced + stepMs < RawEventSchema.SESSION_MAX_TREL_MS) {
                slow.addAndGet(stepMs * 1_000_000L);
                max.observe();
                advanced += stepMs;
            }
            slow.addAndGet((RawEventSchema.SESSION_MAX_TREL_MS - advanced) * 1_000_000L);
            assertEquals(RawEventSchema.SESSION_MAX_TREL_MS, max.observe(),
                    "超过 7 天必须封顶到 SESSION_MAX_TREL_MS");
            assertTrue(max.forcedCloseByMaxTrel());
            assertEquals("max_trel", max.closeCause());
            assertThrows(ContractException.class, max::observe);
        }

        @Test
        @DisplayName("常量集中定义并回显（§2.3.3 / §2.8.1）")
        void constantsEcho() {
            assertEquals(50, RawEventSchema.TICK_MS);
            assertEquals(300_000L, RawEventSchema.IDLE_GAP_THRESHOLD_MS);
            assertEquals(1_800_000L, RawEventSchema.SESSION_SPLIT_GAP_MS);
            assertEquals(60_000L, RawEventSchema.SESSION_MIN_MS);
            assertEquals(604_800_000L, RawEventSchema.SESSION_MAX_TREL_MS);
            assertEquals(60_000L, RawEventSchema.HEARTBEAT_ACTIVE_MS);
            assertEquals(600_000L, RawEventSchema.HEARTBEAT_IDLE_MS);
            assertEquals(268_435_456L, RawEventSchema.WORLD_STORAGE_CAP_BYTES);
            assertEquals(2_048, RawEventSchema.MAX_EVENT_BYTES);
            assertEquals(1, RawEventSchema.SAMPLE_EVERY_NTH_OBSERVATION, "禁止静默采样");
            assertEquals("drop_oldest", RawEventSchema.IF_FULL_POLICY);
            assertNotNull(RawEventSchema.VERSION);
            assertEquals("raw-event-schema@1.0.0", RawEventSchema.VERSION);
        }
    }
}
