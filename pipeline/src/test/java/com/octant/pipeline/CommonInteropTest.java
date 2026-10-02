package com.octant.pipeline;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.model.EventCategory;
import com.octant.common.model.EventSource;
import com.octant.pipeline.adapter.CaptureEventAdapter;
import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;
import com.octant.pipeline.raw.RawEventSchema;
import com.octant.pipeline.raw.RawEventSchema.FieldSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonInteropTest {

    private static final String KEY = "9f1c4d7a2b6e8f03";

    private static CaptureEvent capture(CaptureEventType type, String sessionId, int seq, long tRelMs,
                                        Long durMs, Map<String, Object> payload) {
        return new CaptureEvent(com.octant.common.model.RawEventSchema.VERSION, CaptureEvent.eventId(seq), sessionId, KEY, type,
                tRelMs, CaptureEvent.tickOf(tRelMs), type.category(), EventSource.FORGE, true, durMs,
                payload);
    }

    @Nested
    @DisplayName("两侧事件目录不得漂移")
    class TypeCatalogParity {

        @Test
        @DisplayName("线名逐项一致，且类别与区间性全部对齐（空差异清单）")
        void catalogsMatchExactly() {
            List<String> divergence = CaptureEventAdapter.typeCatalogDivergence();
            assertEquals(List.of(), divergence, "两侧事件目录已漂移：" + divergence);
            assertEquals(CaptureEventType.CONTRACT_TYPE_COUNT, EventType.values().length,
                    "两侧类型总数必须一致");
            assertEquals(24, CaptureEventType.CONTRACT_TYPE_COUNT,
                    "dc@1.5.0 §2.4 的类型总数（23 + EV-S4 session_environment）");
            assertTrue(CaptureEventAdapter.pipelineWireNames().containsAll(CaptureEventType.all().keySet()),
                    "分析侧必须覆盖采集侧的全部线名");
        }

        @Test
        @DisplayName("按线名映射（而非枚举序）—— 两侧声明序不同也必须映射正确")
        void mappingIsByWireNameNotOrdinal() {
            for (CaptureEventType c : CaptureEventType.values()) {
                EventType mine = CaptureEventAdapter.typeOf(c.eventId()).orElseThrow(
                        () -> new AssertionError("分析侧缺少线名：" + c.eventId()));
                assertEquals(c.eventId(), mine.wireName());
                assertEquals(c.category().code(), mine.category());
                assertEquals(c.isInterval(), mine.interval());
            }
            assertTrue(CaptureEventAdapter.typeOf("session_load").isEmpty(),
                    "非契约线名不得被猜中");
            assertEquals(EventType.SESSION_START, CaptureEventAdapter.typeOf("session_start").orElseThrow(),
                    "查表必须按线名命中，与声明位置无关");
            assertEquals(EventType.RESOURCE_BLOCKED,
                    CaptureEventAdapter.typeOf("resource_blocked").orElseThrow(),
                    "末位元素同样必须按线名命中");
        }

        @Test
        @DisplayName("未知线名必须被拒绝，而不是猜测映射")
        void unknownWireNameRejected() {
            assertTrue(CaptureEventAdapter.typeOf("session_load").isEmpty());
            assertTrue(CaptureEventAdapter.typeOf("").isEmpty());
            assertTrue(CaptureEventAdapter.typeOf(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("逐字段映射（README §5 映射表的可执行形态）")
    class FieldMapping {

        @Test
        @DisplayName("标量型：封套逐字段原样透传，durMs null → 0")
        void scalarEventMapsFieldByField() {
            CaptureEvent c = capture(CaptureEventType.ADVANCEMENT_GAINED, "s0007", 7, 120_000L, null,
                    Map.of("advancementId", "minecraft:story/root", "parentId", "",
                            "isRecipe", Boolean.FALSE, "grantedByCommand", Boolean.FALSE));
            RawEvent p = CaptureEventAdapter.toPipeline(c);

            assertEquals(c.eventId(), p.eventId());
            assertEquals(c.sessionId(), p.sessionId());
            assertEquals(c.playerKey(), p.playerKey(), "playerKey 原样携带（由导出层丢弃）");
            assertEquals(c.tRelMs(), p.tRelMs());
            assertEquals(c.tTick(), p.tTick());
            assertEquals(0L, p.durMs(), "标量型 durMs 必须是 0 而不是 present");
            assertTrue(p.confirmed());
            assertEquals(EventType.ADVANCEMENT_GAINED, p.type());
            assertEquals(c.payload(), p.payload());
            assertEquals(0, RawEventSchema.validate(p, "forge").size(),
                    "映射产物必须通过分析侧 schema 校验：" + RawEventSchema.validate(p, "forge"));
        }

        @Test
        @DisplayName("区间型：durMs 携带且非 0；类别/区间性由两侧独立断言一致")
        void intervalEventCarriesDuration() {
            CaptureEvent c = capture(CaptureEventType.COMBAT_STARTED, "s0007", 9, 1_800_000L,
                    180_000L,
                    Map.of("opponentKey", "minecraft:zombie#1", "entityType", "minecraft:zombie",
                            "opponentThreat", 12.0d, "farmPattern", Boolean.FALSE,
                            "shared", Boolean.FALSE, "durMs", 180_000L));
            RawEvent p = CaptureEventAdapter.toPipeline(c);
            assertEquals(180_000L, p.durMs());
            assertEquals(EventType.COMBAT_STARTED, p.type());
            assertTrue(p.type().interval());
        }

        @Test
        @DisplayName("类别漂移必须抛异常（宁可炸也不静默改数）")
        void categoryDriftIsFatal() {
            assertThrows(ContractException.class,
                    () -> new CaptureEvent(com.octant.common.model.RawEventSchema.VERSION,
                            "e001", "s0001", KEY, CaptureEventType.COMBAT_STARTED, 1_000L,
                            CaptureEvent.tickOf(1_000L), EventCategory.ITEMS, EventSource.FORGE,
                            true, 60_000L,
                            Map.of("opponentKey", "minecraft:zombie#1",
                                    "entityType", "minecraft:zombie", "opponentThreat", 1.0d,
                                    "farmPattern", Boolean.FALSE, "shared", Boolean.FALSE,
                                    "durMs", 60_000L)),
                    "类别与类型不一致必须在采集层构造期被拒绝");

            assertThrows(IllegalArgumentException.class,
                    () -> new com.octant.pipeline.raw.RawEvent("e001", "s0001", KEY,
                            EventType.PLAYER_DEATH, 1_000L, 20L, 60_000L, true,
                            Map.of("deathCause", "minecraft:fall", "causeClass", "fall",
                                    "killerEntityType", "", "intentional", Boolean.FALSE)),
                    "标量型事件携带 durMs 必须被分析层拒绝");
        }

        @Test
        @DisplayName("批量转换：违规逐条拒绝并计数，绝不猜测修正")
        void tolerantBatchCountsRejectionsWithoutGuessing() {
            List<CaptureEvent> ok = new ArrayList<>();
            ok.add(capture(CaptureEventType.SESSION_START, "s0001", 1, 0L, null,
                    Map.of("gameVersion", "1.20.1", "loader", "forge", "privacyClass", "singleplayer",
                            "sessionStartDate", "2026-09-01", "cheatsEnabled", Boolean.FALSE)));
            ok.add(capture(CaptureEventType.STALL_SEGMENT, "s0001", 2, 60_000L, 60_000L,
                    Map.of("unitId", "pack:chapter_end", "unitKind", "quest", "dwellMs", 60_000L,
                            "activeDensityPm", 1.0d, "proxy", Boolean.FALSE, "durMs", 60_000L)));

            CaptureEventAdapter.Conversion conv = CaptureEventAdapter.toPipelineTolerant(ok);
            assertEquals(2, conv.acceptedCount());
            assertEquals(0, conv.rejectedCount());
            assertTrue(conv.rejections().isEmpty());
            List<CaptureEvent> withNull = new ArrayList<>(ok);
            withNull.add(null);
            CaptureEventAdapter.Conversion conv2 = CaptureEventAdapter.toPipelineTolerant(withNull);
            assertEquals(2, conv2.acceptedCount());
            assertEquals(1, conv2.rejectedCount());
        }
    }

    @Nested
    @DisplayName("两侧 payload 校验的一致性")
    class PayloadConsistency {

        @Test
        @DisplayName("采集层会为 MAY 字段补缺省哨兵；分析侧必须放行哨兵而不是判越界")
        void maySentinelsAreAcceptedOnPipelineSide() {
            CaptureEvent c = capture(CaptureEventType.QUEST_PROGRESSED, "s0001", 3, 5_000L, null,
                    Map.of("questId", "pack:chapter_stone", "stage", 1L));
            assertEquals(-1, ((Number) c.payload().get("objectiveCount")).intValue(),
                    "采集层应把 MAY 字段 objectiveCount 补成缺省 -1");
            RawEvent p = CaptureEventAdapter.toPipeline(c);
            List<String> violations = RawEventSchema.validate(p, "forge");
            assertEquals(List.of(), violations,
                    "分析侧必须放行契约的 MAY 缺省哨兵，否则两侧对同一 payload 结论相反：" + violations);
            RawEvent bad = new RawEvent(p.eventId(), p.sessionId(), p.playerKey(), p.type(), p.tRelMs(),
                    p.tTick(), 0L, true,
                    Map.of("questId", "pack:chapter_stone", "stage", -5L, "objectiveCount", 2L));
            assertTrue(RawEventSchema.validate(bad, "forge").stream()
                            .anyMatch(v -> v.startsWith("PAYLOAD_RANGE")),
                    "MUST 字段越界（stage=-5）仍必须被判违反");
        }

        @Test
        @DisplayName("字段表外的键必须被两侧同时拒绝")
        void undeclaredFieldRejectedByBothSides() {
            assertThrows(ContractException.class,
                    () -> capture(CaptureEventType.PLAYER_DEATH, "s0001", 4, 1_000L, null,
                            Map.of("deathCause", "minecraft:fall", "causeClass", "fall",
                                    "killerEntityType", "", "intentional", Boolean.FALSE,
                                    "chatMessage", "leak")),
                    "采集层应拒绝字段表外的键");
            RawEvent mine = new RawEvent("e004", "s0001", KEY, EventType.PLAYER_DEATH, 1_000L, 20L,
                    0L, true, Map.of("deathCause", "minecraft:fall", "causeClass", "fall",
                    "killerEntityType", "", "intentional", Boolean.FALSE, "chatMessage", "leak"));
            assertTrue(RawEventSchema.validate(mine, "forge").stream()
                            .anyMatch(v -> v.startsWith("PAYLOAD_UNDECLARED_FIELD")),
                    "分析侧同样必须拒绝字段表外的键");
        }

        @Test
        @DisplayName("分析侧登记的表内 MAY 字段，其缺省哨兵与契约一致（不许出现\"哨兵被判越界\"）")
        void sentinelPolicyIsUniform() {
            int checked = 0;
            for (FieldSpec spec : RawEventSchema
                    .fieldsOf(EventType.QUEST_PROGRESSED)) {
                if (!spec.required() && spec.kind() == RawEventSchema.Kind.INT) {
                    assertTrue(RawEventSchema.isSentinel(spec, -1.0d), spec.name() + " 应放行哨兵 -1");
                    assertFalse(RawEventSchema.isSentinel(spec, 0.0d), spec.name() + " 不应把 0 当哨兵");
                    checked++;
                }
            }
            assertTrue(checked > 0, "QUEST_PROGRESSED 应至少有一个 MAY 数值字段可供检查");
        }
    }

    @Nested
    @DisplayName("接线后的端到端口径")
    class EndToEndThroughAdapter {

        @Test
        @DisplayName("采集层事件经适配器进入管线后，分析结论与直接用分析层模型一致")
        void analysisThroughAdapterMatchesNativePath() {
            List<RawEvent> nativeEvents = SyntheticCorpus.events();
            List<CaptureEvent> captureEvents = new ArrayList<>();
            for (RawEvent e : nativeEvents) {
                captureEvents.add(toCapture(e));
            }
            CaptureEventAdapter.Conversion conv = CaptureEventAdapter.toPipelineTolerant(captureEvents);
            assertEquals(nativeEvents.size(), conv.acceptedCount(),
                    "全部事件都应可映射：" + conv.rejections().stream().limit(3).toList());
            assertTrue(conv.rejections().isEmpty());

            AnalysisReport viaAdapter = AnalysisReport.analyze(conv.events(), SyntheticCorpus.catalog(),
                    SyntheticCorpus.SOURCE);
            AnalysisReport nativeReport = AnalysisReport.analyze(nativeEvents,
                    SyntheticCorpus.catalog(), SyntheticCorpus.SOURCE);

            String via = com.octant.pipeline.json.JsonWriter.compact(
                    com.octant.pipeline.export.AnalysisJson.toJson(viaAdapter));
            String nat = com.octant.pipeline.json.JsonWriter.compact(
                    com.octant.pipeline.export.AnalysisJson.toJson(nativeReport));
            assertEquals(nat, via, "经适配器进入的分析结论必须与直接用分析层模型逐字段一致");

            CaptureEventAdapter.Conversion summary = conv;
            System.out.println("适配器端到端取证：events=" + summary.acceptedCount()
                    + " rejected=" + summary.rejectedCount()
                    + " metrics=" + viaAdapter.metrics().size()
                    + " conclusions=" + viaAdapter.conclusions().size()
                    + " 两侧 JSON 逐字段相等=true");
        }

        private static CaptureEvent toCapture(RawEvent e) {
            CaptureEventType type = CaptureEventType.require(e.type().wireName());
            Long dur = e.durMs() > 0L ? e.durMs() : null;
            assertNotNull(type);
            return new CaptureEvent(com.octant.common.model.RawEventSchema.VERSION, e.eventId(), e.sessionId(),
                    e.playerKey().isEmpty() ? KEY : e.playerKey(), type, e.tRelMs(), e.tTick(),
                    type.category(), EventSource.FORGE, e.confirmed(), dur, e.payload());
        }
    }
}
