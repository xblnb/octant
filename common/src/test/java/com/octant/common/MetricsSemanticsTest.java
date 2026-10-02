package com.octant.common;

import com.octant.common.engine.AnalysisReport;
import com.octant.common.engine.MetricsEngine;
import com.octant.common.metrics.DispersionMetrics;
import com.octant.common.metrics.PlaytimeMetrics;
import com.octant.common.model.ContentCatalog;
import com.octant.common.model.ContentUnit;
import com.octant.common.model.EventType;
import com.octant.common.model.RawEvent;
import com.octant.common.session.Session;
import com.octant.common.session.Sessionizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsSemanticsTest {

    private static final long SECOND = 20L;

    private static List<RawEvent> denseEvents(long startSecond, long endSecond) {
        List<RawEvent> events = new ArrayList<>();
        for (long s = startSecond; s <= endSecond; s += 30) {
            events.add(new RawEvent(EventType.HEARTBEAT, s * SECOND, ""));
        }
        return events;
    }

    private static ContentCatalog catalogOf(int visiblePerAxis) {
        ContentCatalog catalog = new ContentCatalog();
        for (ContentAxis axis : ContentAxis.values()) {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < visiblePerAxis; i++) {
                keys.add(axis.name().toLowerCase() + "_" + i);
            }
            catalog.register(axis, keys, false);
        }
        return catalog;
    }

    @Nested
    @DisplayName("会话化（§0.2 / §3.5 R5）")
    class Sessionization {

        @Test
        @DisplayName("超过挂机阈值的间隔切成新会话，且不计入活跃时长")
        void idleGapSplitsSessions() {
            List<RawEvent> events = new ArrayList<>(denseEvents(0, 60));
            events.addAll(denseEvents(600, 660));
            List<Session> sessions = new Sessionizer().sessionize(events);

            assertEquals(2, sessions.size(), "超过 IDLE_GAP_THRESHOLD 的间隔必须切分会话");
            assertEquals(60L, sessions.get(0).activeSeconds());
            assertEquals(60L, sessions.get(1).activeSeconds());
        }

        @Test
        @DisplayName("无 LOGOUT 闭合的会话标记 openSession（崩溃/重载场景）")
        void openSessionIsFlagged() {
            List<RawEvent> withLogout = new ArrayList<>(denseEvents(0, 60));
            withLogout.add(new RawEvent(EventType.LOGOUT, 61 * SECOND, ""));
            List<Session> closed = new Sessionizer().sessionize(withLogout);
            assertEquals(1, closed.size());
            assertFalse(closed.get(0).openSession(), "有 LOGOUT 的会话不应标记为未闭合");

            List<Session> orphan = new Sessionizer().sessionize(denseEvents(0, 60));
            assertTrue(orphan.get(0).openSession(), "缺少 LOGOUT 的会话必须标记 openSession");
        }
    }

    @Nested
    @DisplayName("M3 游玩时长的抑制（§3.4）")
    class PlaytimeSuppression {

        @Test
        @DisplayName("活跃低于 1 h 时，会话级结论全部抑制并带原因码")
        void belowMinActiveTimeSuppresses() {
            AnalysisReport report = new MetricsEngine().analyze(denseEvents(0, 1200));

            MetricResult m3b = report.metric("M3b_p50");
            assertTrue(m3b.isSuppressed(), "活跃不足 1 h 时 M3b 必须抑制");
            assertEquals(SuppressionReason.INSUFFICIENT_ACTIVE_TIME, m3b.reason().orElseThrow());
            assertTrue(m3b.value().isEmpty(), "抑制态的 value 必须为空，禁止用 0 或均值代填");
            assertEquals(Confidence.SUPPRESSED, m3b.confidence(),
                    "抑制态的 confidence 必须为 SUPPRESSED，不得为空");
            assertTrue(m3b.reasonDetail().orElseThrow().startsWith("INSUFFICIENT_ACTIVE_TIME: "),
                    "原因码必须带实际值与阈值");
        }

        @Test
        @DisplayName("活跃足够但会话数不足时，M3a 仍输出而会话级指标抑制")
        void enoughActiveButTooFewSessions() {
            List<RawEvent> events = new ArrayList<>();
            long start = 0;
            for (int i = 0; i < 4; i++) {
                events.addAll(denseEvents(start, start + 1200));
                start += 7200;
            }
            assertEquals(4, new Sessionizer().sessionize(events).size());

            AnalysisReport report = new MetricsEngine().analyze(events);
            assertFalse(report.metric("M3a").isSuppressed(),
                    "M3a 是严格计数，不应因会话数不足而抑制");
            MetricResult m3d = report.metric("M3d");
            assertTrue(m3d.isSuppressed());
            assertEquals(SuppressionReason.INSUFFICIENT_SESSION_COUNT, m3d.reason().orElseThrow());
        }
    }

    @Nested
    @DisplayName("M3e 疲劳预警（§3.2 / §3.3）")
    class FatigueWarning {

        private List<Session> sessions(long activeSeconds, int count, long startTickSecond) {
            List<Session> out = new ArrayList<>();
            long t = startTickSecond;
            for (int i = 0; i < count; i++) {
                out.add(new Session(t, t + activeSeconds, activeSeconds, 300, false, false));
                t += 86_400;
            }
            return out;
        }

        @Test
        @DisplayName("最近 3 次会话相对基线腰斩时，M3e 达到 0.5 并附两个中位数")
        void decliningEngagementIsFlagged() {
            List<Session> all = new ArrayList<>(sessions(3600, 5, 0));
            all.addAll(sessions(1800, 3, 10L * 86_400));

            MetricResult m3e = new PlaytimeMetrics(all).m3e();
            assertFalse(m3e.isSuppressed(), "会话数 8 ≥ MIN_SESSION_N_FATIGUE(6) 时应输出");
            assertEquals(0.5d, m3e.value().orElseThrow(), 1e-9, "1 - 30/60 = 0.5");
            assertEquals(60.0d, (Double) m3e.extras().get("baselineMedianMin"), 1e-9);
            assertEquals(30.0d, (Double) m3e.extras().get("recentMedianMin"), 1e-9);
        }

        @Test
        @DisplayName("会话数不足警示门槛时 M3e 抑制并给出专用原因码")
        void tooFewSessionsForFatigueSuppresses() {
            MetricResult m3e = new PlaytimeMetrics(sessions(3600, 5, 0)).m3e();
            assertTrue(m3e.isSuppressed(), "会话数 5 < 6 时 M3e 必须抑制");
            assertEquals(SuppressionReason.INSUFFICIENT_SESSION_COUNT_FOR_FATIGUE,
                    m3e.reason().orElseThrow());
        }
    }

    @Nested
    @DisplayName("M5 离散度（§5.2 / §5.5 R3）")
    class Dispersion {

        @Test
        @DisplayName("归一化香农熵按实际可见轴数 K 归一化")
        void entropyIsNormalizedByAxisCount() {
            List<ContentUnit> units = List.of(
                    ContentUnit.of(ContentAxis.DIMENSION, "overworld", AttributionPriority.DIMENSION, 10800, 10),
                    ContentUnit.of(ContentAxis.RECIPE, "iron_pickaxe", AttributionPriority.SYSTEM, 5400, 10),
                    ContentUnit.of(ContentAxis.MOB, "zombie", AttributionPriority.SYSTEM, 1800, 10));

            MetricResult m5a = new DispersionMetrics(units, catalogOf(5), 36_000L).compute().get("M5a");
            assertFalse(m5a.isSuppressed());
            assertEquals(0.8174d, m5a.value().orElseThrow(), 1e-3);
        }

        @Test
        @DisplayName("活跃未达 10 h 时输出 premature=true 并抑制判定标签，而非显示极端集中")
        void earlyPlayerGetsPrematureFlagNotVerdict() {
            List<ContentUnit> units = List.of(
                    ContentUnit.of(ContentAxis.DIMENSION, "overworld", AttributionPriority.DIMENSION, 3600, 10),
                    ContentUnit.of(ContentAxis.RECIPE, "stick", AttributionPriority.SYSTEM, 1200, 10),
                    ContentUnit.of(ContentAxis.MOB, "cow", AttributionPriority.SYSTEM, 600, 10));

            MetricResult m5b = new DispersionMetrics(units, catalogOf(5), 6000L).compute().get("M5b");
            assertFalse(m5b.isSuppressed(), "活跃 6000 s 高于 MIN_ACTIVE_S，数值仍应输出");
            assertEquals(Boolean.TRUE, m5b.extras().get("premature"));
            assertEquals(Boolean.TRUE, m5b.extras().get("verdictSuppressed"));
            assertEquals("PREMATURE_PROGRESSION", m5b.extras().get("verdictReason"));
        }

        @Test
        @DisplayName("可用轴不足 3 条时抑制离散度")
        void tooFewAxesSuppresses() {
            List<ContentUnit> units = List.of(
                    ContentUnit.of(ContentAxis.DIMENSION, "overworld", AttributionPriority.DIMENSION, 10800, 10),
                    ContentUnit.of(ContentAxis.RECIPE, "stick", AttributionPriority.SYSTEM, 5400, 10));

            MetricResult m5a = new DispersionMetrics(units, catalogOf(5), 36_000L).compute().get("M5a");
            assertTrue(m5a.isSuppressed(), "少于 3 条轴谈离散度无意义，必须抑制");
            assertEquals(SuppressionReason.INSUFFICIENT_AXIS_COUNT, m5a.reason().orElseThrow());
        }
    }

    @Nested
    @DisplayName("输出契约的结构不变式（§0.4）")
    class OutputContract {

        @Test
        @DisplayName("抑制态必须有原因码 + value 缺省，但 confidence 必须存在且为 SUPPRESSED")
        void suppressedMustCarryReasonAndNoValue() {
            MetricResult r = MetricResult.suppressed("M9", SuppressionReason.INSUFFICIENT_CONTENT_UNIT,
                    "INSUFFICIENT_CONTENT_UNIT: n=2, required=3");
            assertTrue(r.isSuppressed());
            assertTrue(r.value().isEmpty(), "抑制态 value 必须缺省");
            assertEquals(Confidence.SUPPRESSED, r.confidence(),
                    "抑制态 confidence 必须是 SUPPRESSED，不得为 null（dc §4.2 / HC-11）");
            assertTrue(r.reason().isPresent());
        }

        @Test
        @DisplayName("未抑制却缺少置信度，必须在构造期失败")
        void unsuppressedWithoutConfidenceIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> MetricResult.of("M9", 1.0d, null));
        }

        @Test
        @DisplayName("禁止无原因码的抑制（防止把缺失伪装成结论）")
        void suppressionWithoutReasonIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> MetricResult.suppressed("M9", null, null));
        }
    }

    @Nested
    @DisplayName("互斥归因（§5.5 R2）")
    class Attribution {

        @Test
        @DisplayName("任务优先级高于维度，维度高于系统")
        void questBeatsDimensionBeatsSystem() {
            assertEquals(ContentAxis.QUEST, AttributionPriority.attribute(
                    ContentAxis.QUEST, AttributionPriority.QUEST,
                    ContentAxis.DIMENSION, AttributionPriority.DIMENSION));
            assertEquals(ContentAxis.DIMENSION, AttributionPriority.attribute(
                    ContentAxis.DIMENSION, AttributionPriority.DIMENSION,
                    ContentAxis.MACHINE, AttributionPriority.SYSTEM));
        }
    }
}
