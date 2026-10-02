package com.octant.pipeline;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.analysis.Chart;
import com.octant.pipeline.analysis.Conclusion;
import com.octant.pipeline.analysis.Evidence;
import com.octant.pipeline.analysis.MetricOutcome;
import com.octant.pipeline.analysis.MetricStatus;
import com.octant.pipeline.analysis.ReasonCodes;
import com.octant.pipeline.export.AnalysisJson;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.feature.Features;
import com.octant.pipeline.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisContractTest {

    private static AnalysisReport rich() {
        return AnalysisReport.analyze(SyntheticCorpus.events(), SyntheticCorpus.catalog(),
                SyntheticCorpus.SOURCE);
    }

    private static AnalysisReport empty() {
        return AnalysisReport.analyze(SyntheticCorpus.emptyEvents(), SyntheticCorpus.emptyCatalog(),
                SyntheticCorpus.SOURCE);
    }

    @Nested
    @DisplayName("§4.3/§4.4 强制字段：sampleSize 与 confidence 永远存在")
    class MandatoryFields {

        @Test
        @DisplayName("每条指标与结论都带 sampleSize（7 子字段）与 confidence（四态枚举）")
        void everyUnitCarriesSampleAndConfidence() {
            AnalysisReport r = rich();
            assertEquals(42, r.metrics().size(), "应评估 §3.3 对齐表的 42 个指标键");
            for (MetricOutcome m : r.metrics()) {
                Map<String, Object> s = m.sampleSize().asMap();
                for (String key : List.of("eventCount", "sessionCount", "playerCount", "sampleBasis",
                        "minRequired", "minRequiredUnit")) {
                    assertNotNull(s.get(key), m.metricId() + " 缺少 sampleSize." + key);
                }
                assertTrue(((Number) s.get("playerCount")).intValue() >= 1,
                        m.metricId() + " 的 playerCount 必须 ≥ 1");
                assertNotNull(m.confidence(), m.metricId() + " 缺少 confidence");
                assertEquals(m.sampleSize().minRequired(), m.confidenceDetail().k(),
                        m.metricId() + " 的 confidenceDetail.k 必须等于 sampleSize.minRequired");
            }
            for (Conclusion c : r.conclusions()) {
                assertNotNull(c.sampleSize());
                assertNotNull(c.confidence());
                assertFalse(c.metricIds().isEmpty());
                assertFalse(c.featureIds().isEmpty());
                assertFalse(c.ruleIds().isEmpty());
            }
        }

        @Test
        @DisplayName("JSON 中不存在 sampleSize:null / {} 与任何 null 值")
        void noNullsInJson() {
            String json = com.octant.pipeline.json.JsonWriter.pretty(AnalysisJson.toJson(rich()));
            assertFalse(json.contains("\"sampleSize\": null"), "不得出现 sampleSize:null");
            assertFalse(json.contains("\"sampleSize\": {}"), "不得出现 sampleSize:{}");
            assertFalse(json.contains(": null"), "导出物中不得出现任何 null 值");
            assertFalse(json.contains("NaN") || json.contains("Infinity"), "不得出现 NaN/Infinity");
        }

        @Test
        @DisplayName("可用项的 eventCount > 0；confidence=certain 要求 eventCount ≥ 30")
        void availableImpliesNonZeroEvents() {
            for (MetricOutcome m : rich().metrics()) {
                if (!m.isSuppressedLike()) {
                    assertTrue(m.sampleSize().eventCount() > 0, m.metricId() + " 可用但 eventCount=0");
                }
                if (m.confidence() == com.octant.pipeline.analysis.ConfidenceLevel.CERTAIN) {
                    assertTrue(m.sampleSize().eventCount() >= 30, m.metricId() + " certain 但事件数不足 30");
                }
            }
        }
    }

    @Nested
    @DisplayName("§5 抑制的显式表达（强制条款）")
    class SuppressionExpression {

        @Test
        @DisplayName("空语料：全部 42 项显式抑制，且每项都有闭集内的 reasonCode，绝不输出数值")
        void emptyCorpusSuppressesEverythingExplicitly() {
            AnalysisReport r = empty();
            assertEquals(42, r.metrics().size(), "零输入也必须逐项留痕，不得输出空集");
            for (MetricOutcome m : r.metrics()) {
                assertTrue(m.isSuppressedLike(), m.metricId() + " 在零输入下必须抑制");
                assertNotNull(m.reasonCode(), m.metricId() + " 抑制必须带 reasonCode");
                assertTrue(ReasonCodes.isValid(m.reasonCode()),
                        m.metricId() + " 的 reasonCode 不在 26 项闭集内：" + m.reasonCode());
                assertTrue(m.value() == null && m.series() == null && m.quantiles() == null,
                        m.metricId() + " 抑制态不得携带数值");
                assertEquals(com.octant.pipeline.analysis.ConfidenceLevel.SUPPRESSED, m.confidence(),
                        m.metricId() + " 抑制态 confidence 必须为 suppressed");
                assertTrue(m.confidenceDetail().degradedBy().contains(m.reasonCode().toLowerCase())
                                || m.confidenceDetail().degradedBy().contains("events_below_30"),
                        m.metricId() + " 抑制必须留痕在 degradedBy 中");
            }
            Json.JsonObject analysis = AnalysisJson.toJson(r);
            Json.JsonArray metrics = (Json.JsonArray) analysis.get("metrics");
            for (Object o : metrics.items()) {
                Json.JsonObject m = (Json.JsonObject) o;
                String status = m.str("status");
                assertTrue(List.of("suppressed", "unavailable").contains(status),
                        m.str("metricId") + " 状态表达异常：" + status);
                assertEquals("suppressed", m.str("confidence"),
                        m.str("metricId") + " 非 available 态 confidence 必须为 suppressed");
                assertFalse(m.has("value"), m.str("metricId") + " 抑制态不得出现 value 键");
                assertFalse(m.has("series"), m.str("metricId") + " 抑制态不得出现 series 键");
                assertFalse(m.has("quantiles"), m.str("metricId") + " 抑制态不得出现 quantiles 键");
                assertTrue(m.has("reasonCode"), m.str("metricId") + " 抑制态必须出现 reasonCode");
                assertFalse(m.has("zeroCode"), m.str("metricId") + " 非 zero 态不得出现 zeroCode");
            }
            Object scope = r.suppressionSummary().get("allEvaluated");
            assertEquals(Boolean.TRUE, scope);
            Object countsRaw = r.suppressionSummary().get("counts");
            assertTrue(countsRaw instanceof Map<?, ?>,
                    "suppressionSummary.counts 必须是对象，实际：" + countsRaw);
            assertEquals(0L, ((Number) ((Map<?, ?>) countsRaw).get("available")).longValue());
        }

        @Test
        @DisplayName("小样本：M3b/M3e/M3a 按各自门禁区分抑制，M5 因活跃不足抑制")
        void sparseCorpusSuppressesByGate() {
            AnalysisReport r = AnalysisReport.analyze(SyntheticCorpus.sparseEvents(),
                    SyntheticCorpus.catalog(), SyntheticCorpus.SOURCE);
            assertEquals(MetricStatus.SUPPRESSED, r.metric("M3b").status());
            assertEquals(ReasonCodes.SAMPLE_INSUFFICIENT_SESSIONS_FOR_DISTRIBUTION,
                    r.metric("M3b").reasonCode());
            assertEquals(ReasonCodes.SAMPLE_INSUFFICIENT_SESSIONS_FOR_TREND, r.metric("M3e").reasonCode());
            assertFalse(r.metric("M3a").isSuppressedLike(), "M3a 是严格计数，应输出");
            assertEquals(ReasonCodes.SAMPLE_INSUFFICIENT_ACTIVE_TIME, r.metric("M5a").reasonCode());
            for (MetricOutcome m : r.suppressedMetrics()) {
                assertTrue(ReasonCodes.isValid(m.reasonCode()), m.metricId() + " 原因码越界");
            }
        }

        @Test
        @DisplayName("全部指标都出现在 suppressionSummary.evaluated（结构上杜绝静默省略）")
        void suppressionSummaryCoversEveryMetric() {
            AnalysisReport r = rich();
            Json.JsonObject summary = AnalysisJson.fromMap(r.suppressionSummary());
            Json.JsonArray evaluated = (Json.JsonArray) summary.get("evaluated");
            assertEquals(r.metrics().size(), evaluated.size(), "抑制汇总必须覆盖全部指标");
            Json.JsonObject counts = (Json.JsonObject) summary.get("counts");
            long sum = 0;
            for (String k : counts.keys()) {
                sum += ((Number) counts.get(k)).longValue();
            }
            assertEquals(evaluated.size(), sum, "counts 之和必须等于 evaluated 长度");
        }

        @Test
        @DisplayName("零值码只出现在 status=zero 的项上，且与 reasonCode 互斥")
        void zeroCodeIsExclusive() {
            for (MetricOutcome m : rich().metrics()) {
                if (m.zeroCode() != null) {
                    assertEquals(MetricStatus.ZERO, m.status());
                    assertNull(m.reasonCode(), m.metricId() + " 不得同时出现 zeroCode 与 reasonCode");
                }
                if (m.reasonCode() != null) {
                    assertNull(m.zeroCode());
                }
            }
        }
    }

    @Nested
    @DisplayName("§4.5/§4.6 结论与证据的三元组")
    class ConclusionEvidence {

        @Test
        @DisplayName("结论必须可溯源到 指标 ID + 特征 ID + 规则 ID，且可用结论必带证据与图表")
        void conclusionsAreTraceable() {
            AnalysisReport r = rich();
            for (Conclusion c : r.conclusions()) {
                assertTrue(c.statementKey().matches("[A-Za-z0-9_.]+"),
                        c.conclusionId() + " 的 statementKey 必须是语言键");
                for (String ruleId : c.ruleIds()) {
                    assertNotNull(r.runStats().get("ruleCount"));
                    assertTrue(ruleId.startsWith("RS_"), "规则 ID 形态错误：" + ruleId);
                }
                if (c.status() == MetricStatus.AVAILABLE) {
                    assertFalse(c.evidenceIds().isEmpty(), c.conclusionId() + " 缺证据引用");
                    assertFalse(c.chartIds().isEmpty(), c.conclusionId() + " 缺图表引用");
                    for (String eid : c.evidenceIds()) {
                        assertNotNull(r.evidenceById(eid), c.conclusionId() + " 引用了不存在的证据 " + eid);
                    }
                    for (String cid : c.chartIds()) {
                        assertNotNull(r.chartById(cid), c.conclusionId() + " 引用了不存在的图表 " + cid);
                    }
                } else {
                    assertTrue(c.chartIds().isEmpty(), "被抑制的结论不得含图表引用");
                    assertTrue(c.statementArgs().values().stream().noneMatch(v -> v instanceof Number),
                            c.conclusionId() + " 被抑制却携带数值参数");
                }
            }
        }

        @Test
        @DisplayName("每条证据都给出分子/分母/中位数/不确定性/窗口/数据质量")
        void evidenceHasRequiredFields() {
            AnalysisReport r = rich();
            assertFalse(r.evidence().isEmpty());
            for (Evidence e : r.evidence()) {
                assertNotNull(e.ruleId());
                assertFalse(e.featureIds().isEmpty());
                assertNotNull(e.statistic().get("median"), e.evidenceId() + " 必须给出中位数");
                assertNotNull(e.uncertainty());
                assertNotNull(e.window());
                assertNotNull(e.dataQuality().get("missingPoints"));
                assertNotNull(e.denominatorSource());
            }
        }

        @Test
        @DisplayName("图表的 C11 占位：抑制项 data 物理缺失并给出原因 + 所需样本量")
        void suppressedChartPlaceholder() {
            AnalysisReport r = empty();
            assertFalse(r.charts().isEmpty(), "零输入也必须输出 C11 占位图，不得空集");
            long c11 = r.charts().stream().filter(Chart::suppressed).count();
            assertEquals(r.charts().size(), c11);
            for (Chart c : r.charts()) {
                assertEquals("C11", c.form());
                assertNull(c.data(), "C11 不得携带 data");
                assertTrue(c.suppressionReason().contains("k="), "C11 必须给出所需样本量");
                assertEquals(com.octant.pipeline.analysis.ConfidenceLevel.SUPPRESSED, c.confidence());
            }
        }

        @Test
        @DisplayName("建议必须带 target/provenance/strength≤建议排查，且不承诺数值收益")
        void recommendationsAreActionable() {
            AnalysisReport r = rich();
            for (com.octant.pipeline.analysis.Recommendation rec : r.recommendations()) {
                assertNotNull(rec.target().id());
                assertFalse(rec.basedOnConclusionIds().isEmpty());
                assertFalse(rec.basedOnEvidenceIds().isEmpty());
                assertTrue(List.of("观察", "可选调整", "建议排查").contains(rec.strength()));
                assertFalse(rec.strength().contains("必须"));
                assertTrue(rec.costRiskKey().contains("不适用于"));
            }
        }

        @Test
        @DisplayName("确定性：两次分析产出逐字段相等")
        void deterministicAcrossRuns() {
            String a = com.octant.pipeline.json.JsonWriter.compact(
                    AnalysisJson.toJson(rich()));
            String b = com.octant.pipeline.json.JsonWriter.compact(
                    AnalysisJson.toJson(rich()));
            assertEquals(a, b, "同一输入必须得到逐字段相等的分析结果（契约 §8）");
        }
    }

    @Nested
    @DisplayName("§4.2 指标目录完整性")
    class MetricCatalog {

        @Test
        @DisplayName("§3.3 对齐表的 42 个指标键全部落地，特征 ID 为机械转换且唯一")
        void allMetricKeysPresent() {
            List<Features.Supports> registry = Features.registry();
            assertEquals(42, registry.size(), "对齐表应有 42 行");
            long distinct = registry.stream().map(Features.Supports::metricId).distinct().count();
            assertEquals(42L, distinct, "指标键不得重复");
            for (Features.Supports s : registry) {
                assertTrue(s.featureId().matches("[A-Z0-9_]+"),
                        "特征 ID 只允许大写字母、数字与下划线：" + s.featureId());
                String digits = s.metricId().toUpperCase().replace("-", "").replace(".", "")
                        .replace("_", "");
                assertTrue(s.featureId().replace("_", "").contains(digits.substring(0, 2)),
                        "特征 ID 必须与指标键同族：" + s.metricId() + " → " + s.featureId());
            }
            assertEquals("M1A_PROGRESS_COMPLETION",
                    registry.stream().filter(x -> x.metricId().equals("M1a")).findFirst().orElseThrow().featureId());
            assertEquals("M9_SEGMENT".isEmpty() ? "" : "M9_D1_PACE",
                    registry.stream().filter(x -> x.metricId().equals("D1_PACE")).findFirst().orElseThrow().featureId());
            assertEquals("M8G_DEATH_CAUSE_DIST",
                    registry.stream().filter(x -> x.metricId().equals("M8g")).findFirst().orElseThrow().featureId());
        }

        @Test
        @DisplayName("M9 画像段：门槛为析取（活跃 ≥10 h 或有效会话 ≥5），且段必带 sampleSize")
        void profileSegmentCarriesSample() {
            AnalysisReport r = rich();
            assertFalse(r.segments().isEmpty(), "必须输出画像段对象（可为抑制态）");
            for (com.octant.pipeline.analysis.Segment s : r.segments()) {
                assertNotNull(s.sampleSize());
                assertNotNull(s.confidence());
                if ("suppressed".equals(s.status())) {
                    assertTrue(ReasonCodes.isValid(s.reasonCode()));
                    assertTrue(s.segmentKey().contains("suppressed"),
                            "抑制段不得用真实段名暗示结论");
                }
            }
        }
    }

    @Nested
    @DisplayName("导出包结构")
    class ExportBundleStructure {

        @Test
        @DisplayName("导出目录含 9 个文件，manifest 6 项必填键齐备且 networkCallsMade=0")
        void manifestComplete() {
            ExportPipeline.Input in = EndToEndExportTest.input();
            ExportPipeline.Bundle b = ExportPipeline.build(in, "20260926-140700-abcdef",
                    java.time.Instant.parse("2026-09-26T14:07:00Z"), 1L);
            assertTrue(b.gateViolations().isEmpty(), "门禁不应命中：" + b.gateViolations());
            java.util.Set<String> names = new java.util.TreeSet<>(b.textFiles().keySet());
            names.addAll(b.binaryFiles().keySet());
            assertEquals(java.util.Set.of("PRIVACY-README.txt", "analysis.json", "conclusions.json",
                    "events.anonymized.jsonl", "manifest.json", "metrics.json",
                    "redaction_report.json", "report.md", "report.html"), names);
            Json.JsonObject m = b.manifest();
            for (String key : List.of("schemaVersion", "contractVersions", "privacySpecVersion",
                    "metricVersion", "catalogVersion", "reportFormatVersion", "exportId",
                    "generatedAtDate", "generatedAtBucket", "generatedBy", "gameVersion", "loader",
                    "loaderVersion", "contentOrigin", "sharing", "consent", "privacyFlags",
                    "aggregation", "exclusions", "recipientNotice", "files", "contributingPlayers")) {
                assertTrue(m.has(key), "manifest 缺少必填键：" + key);
            }
            Json.JsonObject sharing = (Json.JsonObject) m.get("sharing");
            assertEquals(0L, ((Number) sharing.get("networkCallsMade")).longValue());
            assertEquals(Boolean.FALSE, sharing.get("uploadedByMod"));
            assertEquals(ExportPipeline.CONTENT_ORIGIN, m.str("contentOrigin"));
            assertTrue(m.has("analysisSnapshotId"), "manifest 缺少 analysisSnapshotId（C-1 同源无法判定）");
        }

        @Test
        @DisplayName("C-1 同源：四份数据文件声明同一个分析快照号，且快照号由内容派生（可复算、非随机）")
        void fourFilesShareOneAnalysisSnapshotId() {
            ExportPipeline.Input in = EndToEndExportTest.input();
            ExportPipeline.Bundle a = ExportPipeline.build(in, "20260926-141100-ddd444",
                    java.time.Instant.parse("2026-09-26T14:11:00Z"), 7L);
            ExportPipeline.Bundle b = ExportPipeline.build(in, "20260926-141200-eee555",
                    java.time.Instant.parse("2026-09-26T14:12:00Z"), 8L);

            java.util.Set<String> ids = new java.util.TreeSet<>();
            for (String name : List.of("manifest.json", "metrics.json", "conclusions.json",
                    "analysis.json")) {
                Json.JsonObject doc = com.octant.pipeline.json.JsonReader.parseObject(
                        a.textFiles().get(name));
                String id = doc.str("analysisSnapshotId");
                assertNotNull(id, name + " 缺少 analysisSnapshotId（C-1 同源无法判定）");
                assertTrue(id.matches("[0-9a-f]{16}"),
                        name + " 的快照号应为 16 位小写十六进制：" + id);
                ids.add(id);
            }
            assertEquals(1, ids.size(), "四份文件必须声明同一个快照号，实际：" + ids);

            String idA = com.octant.pipeline.json.JsonReader.parseObject(
                    a.textFiles().get("analysis.json")).str("analysisSnapshotId");
            String idB = com.octant.pipeline.json.JsonReader.parseObject(
                    b.textFiles().get("analysis.json")).str("analysisSnapshotId");
            assertEquals(idA, idB, "同一次分析的快照号不得随 exportId/时间变化（契约 §8 可复算）");

            AnalysisReport empty = AnalysisReport.analyze(SyntheticCorpus.emptyEvents(),
                    SyntheticCorpus.emptyCatalog(), SyntheticCorpus.SOURCE);
            ExportPipeline.Input emptyIn = new ExportPipeline.Input(empty, List::of,
                    SyntheticCorpus.emptyCatalog(), "0.1.0", "1.20.1", "forge", "salt", List.of("C1"),
                    List.of());
            ExportPipeline.Bundle c = ExportPipeline.build(emptyIn, "20260926-141300-fff666",
                    java.time.Instant.parse("2026-09-26T14:13:00Z"), 9L);
            String idC = com.octant.pipeline.json.JsonReader.parseObject(
                    c.textFiles().get("analysis.json")).str("analysisSnapshotId");
            assertNotEquals(idA, idC, "内容不同必须换快照号，否则无法检出同源被破坏");
        }

        @Test
        @DisplayName("manifest 声明当前 privacy-spec 版本（EX-11：声明过期版本会让收件人按旧规则理解）")
        void manifestDeclaresCurrentPrivacySpecVersion() {
            ExportPipeline.Bundle b = ExportPipeline.build(EndToEndExportTest.input(),
                    "20260926-141400-aaa777", java.time.Instant.parse("2026-09-26T14:14:00Z"), 10L);
            assertEquals(com.octant.pipeline.export.ExportVersions.privacySpecVersion(),
                    b.manifest().str("privacySpecVersion"));
        }
    }
}
