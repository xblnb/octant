package com.octant.pipeline.report;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.analysis.Chart;
import com.octant.pipeline.analysis.Conclusion;
import com.octant.pipeline.analysis.ConfidenceLevel;
import com.octant.pipeline.analysis.Evidence;
import com.octant.pipeline.analysis.MetricOutcome;
import com.octant.pipeline.analysis.Recommendation;
import com.octant.pipeline.analysis.Segment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReportDocument {

    public static final List<String> REQUIRED_SECTIONS = List.of(
            "1 结论", "2 证据", "3 图表", "4 建议", "5 部分 JSON", "6 隐私条款");

    public static final String APPENDIX_SECTION = "7 附录";

    public static final String REPORT_TITLE = "Octant 存档内行为洞察报告";

    public record ExportMeta(
            String exportId,
            String generatedAtDate,
            String generatedAtBucket,
            String modVersion,
            String gameVersion,
            String loader,
            String reportFormatVersion,
            String privacySpecVersion,
            List<String> consentCategories,
            List<String> privacyFlags,
            List<String> exclusions,
            String recipientNotice,
            List<String> modsList) {
    }

    private final AnalysisReport report;
    private final ExportMeta meta;

    public ReportDocument(AnalysisReport report, ExportMeta meta) {
        this.report = report;
        this.meta = meta;
    }

    public AnalysisReport report() {
        return report;
    }

    public String markdown() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines()) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add("# " + REPORT_TITLE);
        out.add("");
        out.add("**报告格式版本**：`" + meta.reportFormatVersion() + "` / `HIG-RPT-1.1`");
        out.add("**模组与平台**：Octant（卦限） `" + meta.modVersion() + "` · MC `" + meta.gameVersion()
                + "` · 加载器 `" + meta.loader() + "` · schema `" + report.rawEventSchemaVersion()
                + "` / `" + report.schemaVersion() + "` · 指标 ID 版本 `" + report.metricVersion() + "`");
        out.add("**本地生成与自愿导出声明**：本报告在本地生成，由玩家自愿导出，并仅由玩家手动分享；"
                + "模组不执行任何上传。");
        out.add("**导出标识与日期**：`" + meta.exportId() + "` · UTC 日期 `" + meta.generatedAtDate()
                + "`（分钟粒度 `" + meta.generatedAtBucket() + "`）");
        out.add("**数据时间窗**：第 0 游戏日 ~ 第 " + report.window().endDayIndex() + " 游戏日 · 会话内相对时刻 "
                + report.window().startTick() + "~" + report.window().endTick() + " tick · 总活跃 "
                + hours(report.dataQuality().get("activeSeconds")) + " · 总墙钟 "
                + hours(report.dataQuality().get("wallMs")));
        out.add("**总样本量**：nPlayers=1（本人） · nSessions=" + report.window().sessionIds().size()
                + " · nEvents=" + report.dataQuality().get("eventCount"));
        out.add("**数据完整性声明**：缺失/截断 丢事件=" + report.dataQuality().get("droppedEvents")
                + "（handling=omitted_not_imputed） · `IDLE_GAP_THRESHOLD`="
                + (Long.parseLong(String.valueOf(report.dataQuality().get("idleGapThresholdMs"))) / 1000L)
                + " s · `AFK_SUSPECT` 占比=" + report.dataQuality().get("afkShare")
                + " · `OPEN_SESSION` 计数=" + report.dataQuality().get("openSessionCount"));
        out.add("**隐私分级声明**：privacyFlags=" + list(meta.privacyFlags())
                + " · 已排除类别=" + list(meta.exclusions())
                + " · S2 项保留理由：仅保留量化区域键与日粒度日期，用于复算会话分布，不含精确坐标。");
        out.add("**抑制概览**：共 " + report.metrics().size() + " 项输出单元，其中 "
                + report.suppressedMetrics().size() + " 项已抑制："
                + suppressDigest() + "（章节 3 的 C11 与附录 X4 逐项列出）");
        out.add("");
        out.add("---");
        out.add("");

        out.add("## 1 结论");
        out.add("");
        List<Conclusion> conclusions = report.conclusions();
        if (conclusions.isEmpty()) {
            out.add("> **报告级抑制**：本次导出没有任何输出单元通过最小样本量门禁，"
                    + "因此不产出任何结论（`suppressionScope = report`）。");
        }
        for (Conclusion c : conclusions) {
            out.add("### " + higConclusionId(c.conclusionId()) + " — " + statementOf(c));
            out.add("- metric: " + String.join(", ", c.metricIds()) + "   window: 第 "
                    + c.window().startDayIndex() + " 游戏日 ~ 第 " + c.window().endDayIndex() + " 游戏日");
            MetricOutcome primary = report.metric(c.metricIds().get(0));
            if (c.status() == com.octant.pipeline.analysis.MetricStatus.AVAILABLE && primary != null) {
                out.add("- value: " + valueOf(primary) + "   unit: " + unitOf(primary.unit())
                        + "   denominatorSource: " + sourceOf(primary));
            }
            out.add("- sampleSize: " + c.sampleSize().currentSample() + " ("
                    + c.sampleSize().basis().wireName() + ")   k: " + c.sampleSize().minRequired()
                    + " " + c.sampleSize().minRequiredUnit());
            out.add("- confidence: " + c.confidence().detailLevel() + " (score "
                    + c.confidenceDetail().asMap().get("score") + ")（判定规则见附录 X1）  nSessions="
                    + c.sampleSize().sessionCount() + ", nPlayers=" + c.sampleSize().playerCount()
                    + " (本人), nEvents=" + c.sampleSize().eventCount());
            out.add("- confidenceDetail: level=" + c.confidenceDetail().asMap().get("level")
                    + " · score=" + c.confidenceDetail().asMap().get("score")
                    + " · nEff=" + c.confidenceDetail().asMap().get("nEff")
                    + " · k=" + c.confidenceDetail().asMap().get("k")
                    + " · inputCoverage=" + c.confidenceDetail().asMap().get("inputCoverage")
                    + " · proxyQuality=" + c.confidenceDetail().asMap().get("proxyQuality")
                    + " · afkShare=" + c.confidenceDetail().asMap().get("afkShare")
                    + " · degradedBy=" + c.confidenceDetail().asMap().get("degradedBy"));
            out.add("- evidence: " + list(c.evidenceIds()) + "   chart: " + list(c.chartIds()));
            if (!c.limitations().isEmpty()) {
                out.add("- uncertainty: " + String.join("; ", c.limitations()));
            }
            if (!c.alternativeExplanations().isEmpty()) {
                out.add("- alternative: " + String.join("; ", c.alternativeExplanations()));
            }
            out.add("- recommendation: " + recommendationsOf(c));
            out.add("- status: " + (c.status() == com.octant.pipeline.analysis.MetricStatus.AVAILABLE
                    ? "ok" : "suppressed"));
            if (c.status() != com.octant.pipeline.analysis.MetricStatus.AVAILABLE) {
                out.addAll(suppressionBlock(c.conclusionId(), primary, c.reasonCode()));
            }
            out.add("");
        }
        out.add("");

        out.add("## 2 证据");
        out.add("");
        for (Evidence e : report.evidence()) {
            out.add("### " + e.evidenceId() + " — 规则 `" + e.ruleId() + "`");
            out.add("- E2 featureIds: " + list(e.featureIds()));
            out.add("- E3 原始计数: 分子 " + trimNum(e.numerator()) + " / 分母 "
                    + trimNum(e.denominator()) + "（来源 " + e.denominatorSource() + "）");
            out.add("- E4 统计量: median=" + e.statistic().get("median")
                    + " · mean=" + e.statistic().getOrDefault("mean", 0.0d)
                    + " · n=" + e.statistic().get("n") + " · quantileMethod=" + e.quantileMethod());
            out.add("- E5 不确定性: " + e.uncertainty());
            out.add("- E6 时间窗: `" + e.window().windowId() + "`（第 " + e.window().startDayIndex()
                    + " ~ 第 " + e.window().endDayIndex() + " 游戏日）");
            out.add("- E7 数据质量: 缺失点 " + e.dataQuality().get("missingPoints")
                    + " · 截断 " + e.dataQuality().get("truncated")
                    + " · 处理 " + e.dataQuality().get("handling"));
            out.add("- E8 置信度: " + e.confidence().detailLevel() + "   E9 状态: " + e.status().wireName());
            out.add("- 关联图表: " + chartsOf(e.evidenceId()));
            out.add("");
        }
        out.add("");

        out.add("## 3 图表");
        out.add("");
        for (Chart c : report.charts()) {
            MetricOutcome m = report.metric(c.metricIds().get(0));
            out.add("### " + c.chartId() + " — " + metricTitle(c) + unitSuffix(c.unit()));
            out.add("- R3-1 形式: `" + c.form() + "`" + drawNote(c.form(), c, m)
                    + "   R3-2 元数据: n=" + c.sampleSize().currentSample()
                    + " · k=" + c.sampleSize().minRequired() + " · confidence=" + c.confidence().wireName()
                    + " · window=`" + c.window().windowId() + "` · coverage=" + c.coverageRatio()
                    + " · missingPoints=" + c.missingPoints() + " · suppressed=" + c.suppressed());
            if (c.suppressed()) {
                out.addAll(suppressionBlock(c.chartId(), m, c.suppressionReason()));
                out.add("");
                out.add("#### " + c.tableRef() + " 等价表");
                out.add("| x | y | 缺失标记 |");
                out.add("| --- | --- | --- |");
                out.add("| " + metricTitle(c) + " | （已抑制，不给出数值） | `"
                        + (c.suppressionReason() == null ? "" : c.suppressionReason().trim().split("[ (]")[0])
                        + "` |");
            } else {
                out.add("- R3-5 读图: " + readingOf(c));
                out.add("- R3-6 色盲检查: 单序列（中性灰蓝 S1）；三模拟（Protanopia/Deuteranopia/Tritanopia）"
                        + "后最小对底色 CR ≥ 4.5 → 通过");
                out.add("");
                out.add("#### " + c.tableRef() + " 等价表");
                out.add("| x | y | 缺失标记 |");
                out.add("| --- | --- | --- |");
                for (Map<String, Object> p : c.data()) {
                    out.add("| " + p.get("x") + " | " + p.get("y") + " | - |");
                }
            }
            out.add("- 关联证据: " + list(c.evidenceIds()));
            out.add("");
        }
        out.add("");

        out.add("## 4 建议");
        out.add("");
        if (report.recommendations().isEmpty()) {
            out.add("本次导出无可行建议：没有任何结论同时满足「样本过门 + 有证据 + 有图」。");
        }
        for (Recommendation r : report.recommendations()) {
            out.add("### " + r.recommendationId() + " — `" + r.actionKey() + "`");
            out.add("- target: " + r.target().kind() + " / " + r.target().id());
            out.add("- basedOn: " + list(r.basedOnConclusionIds()) + " · evidence "
                    + list(r.basedOnEvidenceIds()));
            out.add("- strength: " + r.strength());
            out.add("- costRisk: " + r.costRiskKey());
            out.add("- provenance: " + list(r.provenance().ruleIds()) + " / "
                    + list(r.provenance().featureIds()));
            out.add("");
        }
        out.add("");

        out.add("## 5 部分 JSON");
        out.add("");
        out.add("以下 JSON 片段取自同一次分析快照（`analysis.json` 的同源投影），"
                + "按 JSON Pointer 可与完整文件逐字段比对：");
        out.add("");
        out.add("```json");
        for (String l : partialJson()) {
            out.add(l);
        }
        out.add("```");
        out.add("");

        out.add("## 6 隐私条款");
        out.add("");
        out.addAll(privacyClauses());
        out.add("");

        out.add("## " + APPENDIX_SECTION);
        out.add("");
        out.add("### X1 置信度判定规则");
        out.add("- `certain`（高）：严格计数直接聚合 且 nEvents ≥ 30 且无代理/污染标记；");
        out.add("- `estimated`（中）：含显式估计模型（挂机剔除、归一化、基线比较）；");
        out.add("- `heuristic`（低）：使用代理量、三态 partial 或画像推断；");
        out.add("- `suppressed`（已抑制）：样本不足或输入不可用，**不给出任何数值**。");
        out.add("score = 0.40 x sampleAdequacy + 0.25 x inputCoverage + 0.20 x proxyQuality"
                + " + 0.15 x (1 - afkShare)。");
        out.add("");
        out.add("### X2 参数与口径（实际取值）");
        for (String[] kv : com.octant.pipeline.feature.Thresholds.registry()) {
            out.add("- " + kv[0] + " = " + kv[1]);
        }
        out.add("");
        out.add("### X2b 报告字体与许可（来源标注）");
        out.add("- 字体族名（PDF 内 `/BaseFont`）：`" + com.octant.pipeline.report.font.FontPalette
                .PDF_FAMILY_NAME + "`（子集前缀 `MCINSA+`）");
        out.add("- 字体资产：`NotoSansSC-Regular-subset.ttf`（Noto Sans SC 静态实例子集，"
                + "按 OFL §3 已改名 `" + com.octant.pipeline.report.font.ReportFontResolver
                .BUNDLED_ASSET_FAMILY + "`，保留字体名称 `Source` 不再使用）");
        out.add("- 许可：SIL Open Font License 1.1，许可全文随模块资源分发"
                + "（`pipeline/src/main/resources/com/octant/pipeline/report/font/`）；"
                + "资产来源与改名的逐条处置见同目录 `README.md` §2");
        out.add("- 来源判定（`bundled-ofl-asset` / `explicit-property` / `system-font`）："
                + "见 `manifest.json` 的 `reportFont.provenance` —— `system-font` 表示回退到本机字体，"
                + "**不可随包分发**");
        out.add("");
        out.add("### X3 画像段");
        for (Segment s : report.segments()) {
            out.add("- " + s.segmentId() + " `" + s.segmentKey() + "` status=" + s.status()
                    + (s.reasonCode() == null ? "" : " reasonCode=" + s.reasonCode())
                    + " dimensions=" + list(s.dimensionIds())
                    + (s.scores().isEmpty() ? "" : " scores=" + s.scores()));
        }
        out.add("");
        out.add("### X4 抑制明细表");
        out.add("| 序号 | 指标 metric | 状态 status | 原因码 | 零值码 zeroCode |"
                + " 当前/所需 minRequired | basis |");
        out.add("| --- | --- | --- | --- | --- | --- | --- |");
        int i = 1;
        for (MetricOutcome m : report.suppressedMetrics()) {
            boolean zero = m.status() == com.octant.pipeline.analysis.MetricStatus.ZERO;
            out.add("| " + (i++) + " | " + m.metricId() + " | " + m.status().wireName() + " | "
                    + (zero ? "—" : m.reasonCode()) + " | "
                    + (zero ? m.zeroCode() : "—")
                    + " | " + m.currentSample() + " / " + m.sampleSize().minRequired()
                    + " | " + m.sampleSize().basis().wireName() + " |");
        }
        out.add("");
        out.add("> 零值（`status = zero`）与抑制（`status = suppressed`）是两种不同的「不可用」："
                + "前者是**测到了 0**（例如确无卡点段），后者是**样本不足以判断**。"
                + "`zeroCode` 列只在 `status = zero` 时填码，其余行为 `—`（刻意不用 `-` 占位）。");
        out.add("");
        out.add("### X5 事件类型覆盖");
        out.add("- 已登记事件类型：" + com.octant.pipeline.raw.EventType.values().length
                + " 种（契约 §2.4 的 8 类 × 23 型）");
        out.add("- 本存档实际观测到的类型：" + observedTypes());
        return out;
    }

    private List<String> suppressionBlock(String id, MetricOutcome m, String reasonCode) {
        List<String> out = new ArrayList<>();
        String metricId = m == null ? "-" : m.metricId();
        String code = reasonCode == null ? "" : reasonCode.trim().split("[ (]")[0];
        String detail = reasonCode == null ? "" : reasonCode;
        out.add("> **已抑制（" + id + " / " + metricId + "）** —— `" + detail + "`");
        out.add("> 原因：`" + code.toLowerCase() + "`（"
                + com.octant.pipeline.analysis.ReasonCodes.messageKey(code) + "）");
        if (m != null) {
            out.add("> 当前 / 所需：" + m.currentSample() + " / " + m.sampleSize().minRequired()
                    + " " + m.sampleSize().minRequiredUnit() + "   bounds: `"
                    + m.sampleSize().basis().wireName() + "=" + m.currentSample() + ", k="
                    + m.sampleSize().minRequired() + "`");
            out.add("> 参与样本：1 名玩家（本人） / " + m.sampleSize().sessionCount() + " 次会话 / "
                    + m.sampleSize().eventCount() + " 个事件");
        }
        return out;
    }

    private List<String> partialJson() {
        List<String> out = new ArrayList<>();
        out.add("{");
        out.add("  \"schemaVersion\": \"" + report.schemaVersion() + "\",");
        out.add("  \"metricVersion\": \"" + report.metricVersion() + "\",");
        out.add("  \"exportId\": \"" + meta.exportId() + "\",");
        out.add("  \"generatedAtDateBucket\": \"" + meta.generatedAtBucket() + "\",");
        out.add("  \"metricCount\": " + report.metrics().size() + ",");
        out.add("  \"conclusionCount\": " + report.conclusions().size() + ",");
        out.add("  \"evidenceCount\": " + report.evidence().size() + ",");
        out.add("  \"chartCount\": " + report.charts().size() + ",");
        out.add("  \"recommendationCount\": " + report.recommendations().size() + ",");
        out.add("  \"suppressionSummary\": {");
        out.add("    \"counts\": " + com.octant.pipeline.json.JsonWriter.compact(
                mapToJson(report.suppressionSummary().get("counts"))) + ",");
        out.add("    \"evaluatedMetricCount\": " + report.suppressionSummary().get("evaluatedMetricCount") + ",");
        out.add("    \"allEvaluated\": true");
        out.add("  },");
        out.add("  \"metrics\": [");
        List<MetricOutcome> metrics = report.metrics();
        for (int i = 0; i < metrics.size(); i++) {
            MetricOutcome m = metrics.get(i);
            StringBuilder line = new StringBuilder("    {\"metricId\": \"").append(m.metricId())
                    .append("\", \"status\": \"").append(m.status().wireName())
                    .append("\", \"confidence\": \"").append(m.confidence().wireName()).append('"');
            if (m.hasValue() && m.value() != null) {
                line.append(", \"value\": ").append(trimNum(((Number) m.value()).doubleValue()));
            }
            if (m.reasonCode() != null) {
                line.append(", \"reasonCode\": \"").append(m.reasonCode()).append('"');
            }
            line.append(", \"minRequired\": ").append(m.sampleSize().minRequired()).append('}');
            line.append(i == metrics.size() - 1 ? "" : ",");
            out.add(line.toString());
        }
        out.add("  ]");
        out.add("}");
        return out;
    }

    private com.octant.pipeline.json.Json.JsonObject mapToJson(Object v) {
        com.octant.pipeline.json.Json.JsonObject o = new com.octant.pipeline.json.Json.JsonObject();
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                o.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return o;
    }

    private List<String> privacyClauses() {
        List<String> out = new ArrayList<>();
        out.add("1. **数据来源与范围**：仅使用本存档内自愿采集的行为事件；不含聊天原文、玩家名、UUID、"
                + "IP、服务器地址与精确坐标。");
        out.add("2. **本地性与零上传**：报告在本地生成；模组不进行任何网络访问（`networkCallsMade = 0`），"
                + "分享完全由你手动完成。");
        out.add("3. **假名化与不可链接**：玩家标识以每份导出独立的假名（`per_export`，HMAC-SHA256 截断 64 bit）"
                + "出现；同一玩家的两份导出无法互相链接。");
        out.add("4. **坐标量化**：位置仅以 512 格水平 / 32 格垂直网格的区域键出现，**不含精确坐标**。");
        out.add("5. **最小化与聚合**：样本不足的结论一律以「已抑制 + 原因码」表达，绝不使用 0 或均值代填；"
                + "人群结论要求 ≥ " + com.octant.pipeline.feature.Thresholds.MIN_PLAYERS_FOR_GROUP_CONCLUSION
                + " 名玩家。");
        out.add("6. **可撤回与可删除**：可随时撤回同意并删除已采集数据与全部导出物；删除后不可恢复。");
        out.add("7. **再识别提示**：即使经过上述处理，把本报告与其它信息（例如你的直播录像）结合仍可能推断出"
                + "你的游玩习惯；请在分享前自行评估。接收者：`" + meta.recipientNotice() + "`");
        return out;
    }

    private String suppressDigest() {
        Map<String, Integer> byReason = new LinkedHashMap<>();
        for (MetricOutcome m : report.suppressedMetrics()) {
            byReason.merge(m.reasonCode(), 1, Integer::sum);
        }
        if (byReason.isEmpty()) {
            return "本次导出无被抑制项";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byReason.entrySet()) {
            parts.add("`" + e.getKey() + "` ×" + e.getValue());
        }
        return String.join("、", parts);
    }

    static String higConclusionId(String internalId) {
        String digits = internalId == null ? "" : internalId.replaceAll("\\D", "");
        return "F" + (digits.isEmpty() ? "0" : String.valueOf(Integer.parseInt(digits)));
    }

    private String statementOf(Conclusion c) {
        MetricOutcome m = report.metric(c.metricIds().get(0));
        if (c.status() != com.octant.pipeline.analysis.MetricStatus.AVAILABLE) {
            return "该项观测不足以支撑结论（已抑制，见下方抑制块）";
        }
        String key = c.statementKey().replace("conclusion.", "");
        if (m == null) {
            return key;
        }
        return switch (key) {
            case "m1.progress_share" -> "在观测窗口内，可达内容单元中已达成 " + valueOf(m)
                    + "（指标 " + m.metricId() + "）";
            case "m2.stall_units" -> "在观测窗口内，已尝试单元中 " + valueOf(m)
                    + " 处于卡点状态（指标 " + m.metricId() + "）";
            case "m3.playtime" -> "在观测窗口内，总活跃时长为 " + valueOf(m) + "，单次会话中位数为 "
                    + valueOrSuppressed(report.metric("M3b")) + "（指标 M3a/M3b）";
            case "m4.breadth" -> "在观测窗口内，可达内容轴中 " + valueOf(m) + " 被至少触达一次（指标 M4）";
            case "m5.dispersion" -> "在观测窗口内，各内容轴的时长分布均衡度为 " + valueOf(m)
                    + "（指标 M5a）";
            case "m6.preference" -> "在观测窗口内，主导内容类别为 "
                    + namedOf(report.metric("M6c")) + "（指标 M6c）";
            case "m7.repetition_automation" -> "在观测窗口内，重复行为占比为 " + valueOf(m)
                    + "（指标 M7a）";
            case "m8.combat" -> "在观测窗口内，战斗时长占活跃时长的 " + valueOf(m) + "（指标 M8a）";
            case "m8.death_causes" -> "在观测窗口内，死亡原因分布为 " + seriesOf(m) + "（指标 M8g）";
            case "m3e.fatigue" -> "最近 3 次会话的中位活跃时长相对基线为 " + valueOf(m) + "（指标 M3e）";
            default -> key + " = " + valueOf(m);
        };
    }

    private String metricTitle(Chart c) {
        MetricOutcome m = report.metric(c.metricIds().get(0));
        return m == null ? c.titleKey() : m.metricId() + " " + m.featureId();
    }

    private String readingOf(Chart c) {
        MetricOutcome m = report.metric(c.metricIds().get(0));
        if (m == null) {
            return "无数据";
        }
        if (m.series() != null) {
            return c.chartId() + " 展示 " + m.metricId() + " 的逐项取值（" + m.series().size() + " 项），"
                    + "单位为 " + unitOf(m.unit()) + "。";
        }
        return c.chartId() + " 展示 " + m.metricId() + " 的单值 " + valueOf(m) + "，单位 "
                + unitOf(m.unit()) + "。";
    }

    private String recommendationsOf(Conclusion c) {
        List<String> ids = new ArrayList<>();
        for (Recommendation r : report.recommendations()) {
            if (r.basedOnConclusionIds().contains(c.conclusionId())) {
                ids.add(r.recommendationId());
            }
        }
        return ids.isEmpty() ? "无" : String.join(", ", ids);
    }

    private String chartsOf(String evidenceId) {
        List<String> ids = new ArrayList<>();
        for (Chart c : report.charts()) {
            if (c.evidenceIds().contains(evidenceId)) {
                ids.add(c.chartId());
            }
        }
        return ids.isEmpty() ? "无" : String.join(", ", ids);
    }

    private String valueOf(MetricOutcome m) {
        if (m == null) {
            return "VALUE_NOT_RENDERABLE(metric missing)";
        }
        if (m.namedKey() != null && m.value() instanceof Number n) {
            return m.namedKey() + " (" + trimNum(n.doubleValue()) + ")";
        }
        if (m.value() instanceof Number n) {
            return trimNum(n.doubleValue()) + " " + unitOf(m.unit());
        }
        if (m.quantiles() != null && m.quantiles().get("p50") != null) {
            return trimNum(m.quantiles().get("p50")) + " " + unitOf(m.unit()) + "(p50)";
        }
        if (m.series() != null) {
            return "N=" + m.series().size() + " " + unitOf(m.unit()) + "(" + seriesOf(m) + ")";
        }
        if (m.enumValue() != null) {
            return m.enumValue();
        }
        return "VALUE_NOT_RENDERABLE(" + m.metricId() + "/" + m.outputType() + ")";
    }

    static String unitOf(String unit) {
        if (unit == null || unit.isBlank() || "-".equals(unit.trim())) {
            return "无单位";
        }
        return unit.trim();
    }

    static String unitSuffix(String unit) {
        return "（单位：" + unitOf(unit) + "）";
    }

    private static String drawNote(String form, com.octant.pipeline.analysis.Chart chart,
                                   MetricOutcome metric) {
        if (com.octant.pipeline.analysis.ChartForms.KPI_UNIT.equals(form)) {
            return "（该图型按规范不画图：单一标量 KPI 单元）";
        }
        if (com.octant.pipeline.analysis.ChartForms.SUPPRESSED_PLACEHOLDER.equals(form)) {
            return "（该图型按规范不画图：样本不足的抑制占位）";
        }
        if (PdfCharts.draws(form) && PdfCharts.canDraw(chart, metric)) {
            return "（已绘制）";
        }
        return "（未绘制：本版无可用数据，不作为图表项输出）";
    }

    private String valueOrSuppressed(MetricOutcome m) {        if (m == null) {
            return "SUPPRESSED(metric missing)";
        }
        if (m.isSuppressedLike()) {
            return "已抑制(status=" + m.status().wireName() + ", reasonCode=" + m.reasonCode() + ")";
        }
        return valueOf(m);
    }

    private String namedOf(MetricOutcome m) {
        if (m == null || m.namedKey() == null) {
            return valueOf(m);
        }
        return m.value() instanceof Number n
                ? m.namedKey() + "（share " + trimNum(n.doubleValue()) + "）"
                : m.namedKey();
    }

    private String seriesOf(MetricOutcome m) {
        if (m == null || m.series() == null) {
            return valueOf(m);
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Double> e : m.series().entrySet()) {
            if (i++ > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(trimNum(e.getValue()));
            if (i >= 8) {
                sb.append("; ...（共 ").append(m.series().size()).append(" 项）");
                break;
            }
        }
        return sb.toString();
    }

    private String sourceOf(MetricOutcome m) {
        return switch (m.metricId()) {
            case "M1a", "M1b", "M1c", "M1-tail", "M4", "M5a", "M5b", "M5c", "M5d" -> "reachable";
            case "M2a", "M2b", "M2c", "M7a" -> "attempted";
            case "M8a", "M8b", "M8c", "M8d", "M8e", "M8f" -> "encounters";
            default -> "observed";
        };
    }

    private String observedTypes() {
        return String.join(", ", com.octant.pipeline.raw.EventType.wireNames());
    }

    private static String list(List<String> items) {
        return items == null || items.isEmpty() ? "-" : String.join(", ", items);
    }

    private static String trimNum(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e12) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 1000.0d) / 1000.0d);
    }

    private static String hours(Object activeSeconds) {
        if (activeSeconds == null) {
            return "-";
        }
        double s = Double.parseDouble(String.valueOf(activeSeconds));
        return Math.round(s / 36.0d) / 100.0d + " 小时";
    }
}
