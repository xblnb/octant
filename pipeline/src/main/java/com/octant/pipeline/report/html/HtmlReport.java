package com.octant.pipeline.report.html;

import com.octant.pipeline.analysis.Chart;
import com.octant.pipeline.analysis.Conclusion;
import com.octant.pipeline.analysis.Evidence;
import com.octant.pipeline.analysis.MetricOutcome;
import com.octant.pipeline.analysis.MetricStatus;
import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.analysis.Segment;
import com.octant.pipeline.json.Json;
import com.octant.pipeline.json.JsonReader;
import com.octant.pipeline.report.ReportDocument;
import com.octant.pipeline.report.chart.Figure;
import com.octant.pipeline.report.chart.FigureGeometry;
import com.octant.pipeline.report.chart.FigureGroups;
import com.octant.pipeline.report.chart.FigureSvg;
import com.octant.pipeline.report.font.FontPalette;
import com.octant.pipeline.report.font.ReportFontResolver;
import com.octant.pipeline.report.font.TrueType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class HtmlReport {

    public static final String FORMAT_ID = "html";

    public record Result(byte[] html, String text, String fontFamily, String fontSha256,
                         int fontSubsetBytes, int fontFullBytes, int glyphCount,
                         int chartsDrawn, List<String> chartForms, List<String> adviceTargets,
                         List<String> adviceActions, int adviceCount, int conclusionUnits,
                         int suppressedUnits, String cssFontFamily) {

        public String htmlText() {
            return text;
        }
    }

    private final AnalysisReport report;
    private final ReportDocument.ExportMeta meta;

    private final String contentInsightsJson;

    private final String replayJson;
    private String lastBody;

    public HtmlReport(AnalysisReport report, ReportDocument.ExportMeta meta) {
        this(report, meta, null, null);
    }

    public HtmlReport(AnalysisReport report, ReportDocument.ExportMeta meta, String contentInsightsJson) {
        this(report, meta, contentInsightsJson, null);
    }

    public HtmlReport(AnalysisReport report, ReportDocument.ExportMeta meta, String contentInsightsJson,
                      String replayJson) {
        this.report = report;
        this.meta = meta;
        this.contentInsightsJson = contentInsightsJson;
        this.replayJson = replayJson;
    }

    String contentInsightsJson() {
        return contentInsightsJson;
    }

    public Result render() {
        String family = "OctantReport" + meta.exportId().replaceAll("[^0-9a-zA-Z]", "");
        StringBuilder body = new StringBuilder(1 << 18);

        body.append("<header class=\"report-header\">\n");
        body.append("<h1>").append(ReportDocument.REPORT_TITLE).append("</h1>\n");
        body.append("<p class=\"namespace\">编号命名空间：结论 <code>F#</code> · 图表项 <code>C##</code> · ")
                .append("图型 <code>C#</code> · 证据 <code>E#</code> · 建议 <code>A#</code> · ")
                .append("等价表 <code>&lt;C##&gt;-T</code></p>\n");
        body.append("<dl class=\"meta\">\n");
        kv(body, "报告格式版本", meta.reportFormatVersion() + " / HIG-RPT-1.11 / HIG-HTML-1.0");
        kv(body, "模组与平台", "Octant " + meta.modVersion() + "（卦限） · MC " + meta.gameVersion()
                + " · 加载器 " + meta.loader());
        kv(body, "契约版本", "raw-event-schema " + report.rawEventSchemaVersion()
                + " · 分析输出模型 " + report.schemaVersion()
                + " · 指标 ID 版本 " + report.metricVersion()
                + " · 内容目录 " + report.catalogVersion());
        kv(body, "导出标识与日期", meta.exportId() + " · UTC 日期 " + meta.generatedAtDate()
                + "（分钟粒度 " + meta.generatedAtBucket() + "）");
        kv(body, "数据时间窗", "第 0 游戏日 ~ 第 " + report.window().endDayIndex() + " 游戏日 · "
                + report.window().sessionIds().size() + " 次会话");
        kv(body, "总样本量", "nPlayers=1（本人） · nSessions=" + report.window().sessionIds().size()
                + " · 输出单元 " + report.metrics().size() + " 项（其中已抑制 "
                + report.suppressedMetrics().size() + " 项）");
        kv(body, "本地生成与自愿导出", "本报告在本地生成，由玩家自愿导出，并仅由玩家手动分享；"
                + "模组不执行任何上传。");
        body.append("</dl>\n");
        body.append("<p class=\"notice\">").append(esc(meta.recipientNotice())).append("</p>\n");
        body.append("</header>\n");

        List<AdvicePlanner.Advice> advice = AdvicePlanner.plan(report);
        Map<String, List<AdvicePlanner.Advice>> adviceOf = new LinkedHashMap<>();
        for (AdvicePlanner.Advice a : advice) {
            adviceOf.computeIfAbsent(a.basedOnConclusionIds().get(0), k -> new ArrayList<>()).add(a);
        }
        List<Conclusion> conclusions = sortedConclusions();
        body.append(glossaryBlock());
        body.append("<h2>1 结论</h2>\n");
        body.append(keyPathBlock(conclusions));
        body.append("<p class=\"lead\">以下每一条都是「一句话结论 + 它在说什么 + 可以做什么 + 可核查字段」。")
                .append("可核查层用于复核结论所用的字段，它不参与阅读。</p>\n");
        int unit = 0;
        for (Conclusion c : conclusions) {
            unit++;
            String fid = String.format(java.util.Locale.ROOT, "F%d", unit);
            String statement = statementOf(c);
            body.append("<section data-f=\"").append(fid)
                    .append("\" data-layout=\"two-column\" data-conclusion=\"")
                    .append(esc(statement)).append("\">\n");
            body.append("<h3 data-subtitle=\"").append(esc(statement))
                    .append("\" data-critical-path>").append(fid).append(" — ")
                    .append(esc(statement)).append("</h3>\n");
            body.append("<div class=\"conclusion-two-col\">\n");
            body.append("<blockquote data-baseline=\"none\">").append(esc(meaningOf(c, statement))).append("</blockquote>\n");
            List<AdvicePlanner.Advice> mine = adviceOf.getOrDefault(c.conclusionId(), List.of());
            if (mine.isEmpty()) {
                body.append("<p data-advice=\"none\">无建议：本次观测中该结论没有可指认的对象")
                        .append("（没有出现具体的关卡单元、内容类别或时间窗可以指名）。</p>\n");
            } else {
                for (AdvicePlanner.Advice a : mine) {
                    body.append("<p>建议 ").append(esc(a.headline()))
                            .append(" <a data-advice=\"").append(a.id()).append("\" href=\"#")
                            .append(a.id()).append("\">（看这条建议）</a></p>\n");
                }
            }
            body.append("</div>\n");
            body.append(implicationBlock(c));
            body.append(machineBlock(c));
            body.append("</section>\n");
        }

        body.append("<h2>2 证据</h2>\n");
        body.append("<p class=\"lead\">每条证据给出分子与分母：只给百分比不足以复核。</p>\n");
        for (Evidence e : sortedEvidence()) {
            MetricOutcome om = metricOfEvidence(e);
            List<String[]> rows = evidenceStatRows(e, om);
            body.append("<section data-e=\"").append(e.evidenceId()).append("\" id=\"")
                    .append(e.evidenceId()).append("\" data-stats=\"")
                    .append(esc(statSignature(rows))).append("\" data-stats-source=\"")
                    .append(esc(statBasisOf(om))).append("\">\n");
            body.append("<span hidden data-stats=\"").append(esc(statSignature(rows)))
                    .append("\" data-stats-source=\"").append(esc(statBasisOf(om)))
                    .append("\"></span>\n");
            body.append("<h3>").append(esc(humanize(evidenceTitle(e), codeNames())))
                    .append("</h3>\n");
            body.append("<table class=\"evidence-table\"><caption>")
                    .append("统计量（逐条按该指标自己的数据；每个数值都能在第 4 节证据里逐条核对）</caption>")
                    .append("<thead><tr><th>项目</th><th>取值</th><th>复算路径</th></tr></thead><tbody>\n");
            for (String[] r : rows) {
                body.append("<tr><th scope=\"row\">").append(esc(r[0])).append("</th><td>")
                        .append(esc(r[1])).append("</td><td>").append(esc(r[2])).append("</td></tr>\n");
            }
            row(body, "不确定度", e.uncertainty() + "（复算路径：contracts.md §4.4 不确定度定义）");
            row(body, "统计置信度", confidenceLabel(e.confidence().wireName())
                    + "（复算路径：confidenceDetail.level）");
            row(body, "关联图表", humanize(join(chartsOfEvidence(e.evidenceId())), chartNames()));
            body.append("</tbody></table>\n");
            body.append("</section>\n");
        }

        body.append("<h2>3 图表</h2>\n");
        body.append("<p class=\"lead\">每张图都由图表库渲染为内联矢量图，并在同一单元内给出等价表：")
                .append("图上每一个数值都能在表里查到。</p>\n");

        body.append(mergedFiguresBlock());
        body.append("<h3>逐项等价表（每个图表项一张，供逐值核对）</h3>\n");
        List<String> forms = new ArrayList<>();
        int drawn = 0;
        for (Chart c : sortedCharts()) {
            MetricOutcome m = report.metric(c.metricIds().get(0));
            Figure.Drawing drawing = drawingOf(c, m);
            List<Figure.Point> pts = FigureGeometry.pointsOf(
                    m == null ? null : m.series(), m == null ? null : m.quantiles(),
                    m == null ? null : m.value(), m == null ? c.chartId() : m.metricId());
            body.append("<section data-c=\"").append(c.chartId()).append("\" data-form=\"")
                    .append(c.form()).append("\" data-layout=\"full-width-figure\" id=\"")
                    .append(c.chartId()).append("\" data-n=\"").append(c.sampleSize().currentSample())
                    .append("\" data-n-required=\"").append(c.sampleSize().minRequired())
                    .append("\" data-n-unit=\"").append(esc(c.sampleSize().minRequiredUnit()))
                    .append("\" data-coverage=\"").append(ratioText(c.coverageRatio()))
                    .append("\" data-missing-points=\"").append(c.missingPoints())
                    .append("\" data-confidence=\"").append(c.confidence().wireName())
                    .append("\">\n");
            body.append("<h3>").append(esc(humanize(esc(chartTitle(c)), codeNames())))
                    .append("（单位：").append(unitOf(c.unit())).append("）</h3>\n");
            if (c.suppressed()) {
                body.append(suppressedBlock(c, m));
                body.append("<p class=\"no-figure\" data-no-figure=\"")
                        .append(esc(c.form())).append("\" data-suppression-reason=\"")
                        .append(esc(m == null || m.reasonCode() == null ? "status=suppressed" : m.reasonCode()))
                        .append("\">本图型未绘制（数据不足，不是渲染失败）。")
                        .append("。样本 ").append(c.sampleSize().currentSample())
                        .append(" / 需要 ≥ ").append(c.sampleSize().minRequired()).append(" ")
                        .append(esc(c.sampleSize().minRequiredUnit())).append("。</p>\n");
            } else if (drawing == null) {
                body.append("<p class=\"no-figure\" data-no-figure=\"")
                        .append(esc(c.form())).append("\">本项图型按规范不画图：")
                        .append("数值由下方等价表与结论承载。时序类图型（折线/阶梯）本项不适用："
                        + "该量当前只有 1 个观测值、没有时间序列可比，因此不画折线，"
                        + "也不把它画成单条横条来冒充趋势。</p>\n");
            } else if (isMergedNonPrimary(c)) {
                body.append("<p class=\"merged-ref\" data-merged-into=\"")
                        .append(esc(primaryChartOf(c))).append("\">")
                        .append("本项已并入上方的读者问题图，逐值核对见下方等价表。</p>\n");
            } else {
                String evidence = c.evidenceIds().isEmpty() ? "" : c.evidenceIds().get(0);
                String takeaway = takeawayOf(c, drawing);
                body.append("<figure data-layout=\"full-width-figure\" data-takeaway=\"")
                        .append(esc(takeaway)).append('"').append(refAttrs(drawing));
                if (!evidence.isEmpty()) {
                    body.append(" data-evidence=\"").append(esc(evidence)).append('"');
                }
                body.append(">\n");
                int drawnPoints = drawnDataPointCount(drawing);
                if (!takeaway.contains("图上画出 " + drawnPoints + " 个可比较的取值")
                        && !takeaway.contains("只画出 1 个可比较的取值")
                        && !takeaway.contains("图上没有随数值缩放的图形")) {
                    throw new IllegalStateException("题注的自述点数与实画点数不一致（"
                            + chartTitle(c) + "，实画 " + drawnPoints + "）：" + takeaway);
                }
                body.append(FigureSvg.toSvg(drawing, c.chartId(),
                        chartTitle(c) + "；本图画出 " + drawnPrimitiveCount(drawing) + " 根条（其中 " + drawnPoints + " 个为互异取值）、" + drawing.referencePointCount()
                                + " 个带数值的参考标记；读法：" + drawing.reading())).append('\n');
                body.append("<figcaption>").append(esc(humanize(chartTitle(c), codeNames())))
                        .append("；").append(esc(drawing.reading())).append(esc(refNoteOf(drawing))).append("</figcaption>\n");
                body.append("</figure>\n");
                drawn++;
                forms.add(c.form());
            }
            body.append("<table data-table-ref=\"").append(c.tableRef()).append("\" id=\"")
                    .append(c.tableRef()).append("\"><caption>")
                    .append("逐项取值：下面一行对应图上的一项，方便逐值核对")
                    .append("</caption><thead><tr><th>项目</th><th>数值</th><th>缺失标记</th>")
                    .append("</tr></thead><tbody>\n");
            if (c.suppressed()) {
                row3(body, chartTitle(c), "（已抑制，不给出数值）",
                        c.suppressionReason() == null ? "" : firstToken(c.suppressionReason()));
            } else {
                for (Map<String, Object> p : c.data()) {
                    row3(body, humanize(String.valueOf(p.get("x")), codeNames()),
                            String.valueOf(p.get("y")), "—");
                }
                if (drawing != null) {
                    for (int i = 0; i < drawing.ticks().size(); i++) {
                        row3(body, "刻度 " + (i + 1) + "/" + drawing.ticks().size(),
                                drawing.tickLabels().get(i), "—");
                    }
                }
            }
            body.append("</tbody></table>\n");
            body.append("<p class=\"ref\" data-evidence=\"")
                    .append(esc(String.join(" ", c.evidenceIds())))
                    .append("\">关联证据：见第 4 节的对应统计条目</p>\n");
            body.append("<p class=\"axis-note\">样本量 ").append(c.sampleSize().currentSample())
                    .append(" / 所需 ").append(c.sampleSize().minRequired()).append(" ")
                    .append(c.sampleSize().minRequiredUnit())
                    .append(" · 置信度 ").append(confidenceLabel(c.confidence().wireName()))
                    .append(" · 覆盖率 ").append(ratioText(c.coverageRatio()))
                    .append(" · 缺失点 ").append(c.missingPoints()).append("</p>\n");
            body.append("</section>\n");
        }

        body.append("<h2>4 建议</h2>\n");
        if (advice.isEmpty()) {
            body.append("<p data-advice=\"none\">本次没有可指认对象的建议：")
                    .append("所有结论都缺少可以指名的关卡单元、内容类别或时间窗。</p>\n");
        }
        for (AdvicePlanner.Advice a : advice) {
            String problem = AdvicePlanner.problemOf(a.id());
            body.append("<section data-a=\"").append(a.id()).append("\" id=\"").append(a.id())
                    .append("\" data-target=\"").append(esc(a.targetId())).append("\" data-action=\"")
                    .append(esc(a.action())).append('"');
            if (problem == null) {
                body.append(" data-problem-status=\"undetermined\"");
            } else {
                body.append(" data-problem=\"").append(esc(problem)).append('"');
            }
            body.append(">\n");
            body.append("<span hidden data-target=\"").append(esc(a.targetId()))
                    .append("\" data-action=\"").append(esc(a.action())).append('"');
            if (problem == null) {
                body.append(" data-problem-status=\"undetermined\"");
            } else {
                body.append(" data-problem=\"").append(esc(problem)).append('"');
            }
            body.append("></span>\n");
            body.append("<h3>").append(esc(a.headline())).append("</h3>\n");
            body.append("<p data-advice-text=\"").append(esc(a.headline())).append("\">")
                    .append(esc(a.headline())).append("</p>\n");
            body.append("<p>对象：").append(esc(targetNameOf(a)))
                    .append("（").append(kindLabel(a.targetKind())).append("）· 动作：")
                    .append(esc(a.action())).append(" · 强度：").append(esc(a.strength()))
                    .append("</p>\n");
            body.append("<p>依据：").append(esc(a.basis())).append("</p>\n");
            body.append("<p class=\"ref\" data-evidence=\"")
                    .append(esc(String.join(" ", a.basedOnEvidenceIds())))
                    .append("\">关联证据：见本建议的“依据”与第 4 节对应条目</p>\n");
            body.append("<p class=\"limit\">").append(esc(a.costRisk())).append("</p>\n");
            body.append("</section>\n");
        }

        String partial = partialJson();
        body.append("<h2>5 部分 JSON</h2>\n");
        body.append("<p class=\"lead\">以下片段与 <code>analysis.json</code> 同源，可按 JSON Pointer 逐字段比对。</p>\n");
        body.append("<pre class=\"json-part\">").append(esc(partial)).append("</pre>\n");

        body.append("<h2>6 隐私条款</h2>\n");
        body.append("<ol class=\"privacy\">\n");
        for (String clause : privacyClauses()) {
            body.append("<li>").append(esc(clause)).append("</li>\n");
        }
        body.append("</ol>\n");

        body.append("<h3 id=\"X4H\">X4 抑制明细表</h3>\n");
        body.append("<p class=\"lead\">「已抑制」是样本不足以判断；「零值」是确实测到了 0。")
                .append("两者都必须显式呈现，不得用 0 或短横代替。</p>\n");
        body.append("<table id=\"X4\" class=\"suppression-table\"><thead><tr>")
                .append("<th>序号</th><th>指标</th><th>状态</th><th>原因码</th><th>零值码</th>")
                .append("<th>判据（当前 / 门槛）</th><th>结论</th></tr></thead><tbody>\n");
        int si = 0;
        for (MetricOutcome m : sortedSuppressed()) {
            si++;
            boolean zero = m.status() == MetricStatus.ZERO;
            List<Object[]> checks = checksOf(m.metricId());
            Object[] failing = null;
            for (Object[] c : checks) {
                if (!Boolean.TRUE.equals(c[3])) {
                    failing = c;
                    break;
                }
            }
            String failedName = failing == null ? null : (String) failing[0];
            long failedCur = failing == null ? -1 : (Long) failing[1];
            long failedReq = failing == null ? -1 : (Long) failing[2];
            body.append("<tr data-suppressed=\"true\" data-reason-code=\"")
                    .append(esc(zero ? m.zeroCode() : m.reasonCode()))
                    .append('"');
            if (failing == null) {
                body.append(" data-sample-basis=\"input-missing\">\n");
            } else {
                body.append(" data-current=\"").append(failedCur)
                        .append("\" data-required=\"").append(failedReq)
                        .append("\" data-sample-basis=\"").append(esc(failedName))
                        .append("\" data-gate-checks=\"").append(checks.size())
                        .append("\">\n");
            }
            cell(body, String.valueOf(si));
            cell(body, m.metricId());
            cell(body, zero ? "零值" : "已抑制");
            cell(body, zero ? "—" : m.reasonCode());
            cell(body, zero ? m.zeroCode() : "—");
            cell(body, failing == null
                    ? "—（非样本量原因）"
                    : esc(failedName) + " " + failedCur + " / 需 " + failedReq);
            cell(body, failing == null
                    ? "门禁未因样本量拦截（原因码见左）"
                    : "未达标：" + esc(failedName) + " 差 " + (failedReq - failedCur));
            body.append("</tr>\n");
        }
        body.append("</tbody></table>\n");
        body.append("<p class=\"limit\">上表「判据」一列显示的是<b>闸门实际比较过、且没通过的那一条</b>"
                + "（口径 / 当前值 / 门槛三样同源）。一项指标可能有多条口径（例如「内容单元数」通过、"
                + "「活跃时长」未通过），因此它与指标自身的 `sampleSize` 字段<b>不是同一套数</b>——"
                + "后者是契约字段（单一 basis），前者是判定依据。</p>\n");

        body.append("<h3>X5 事件类型覆盖</h3>\n");
        body.append("<p>已登记事件类型 ").append(com.octant.pipeline.raw.EventType.values().length)
                .append(" 种；本存档实际观测到 ").append(observedTypes().size())
                .append(" 种（逐型清单见 <code>analysis.json</code>）。</p>\n");

        body.append("<h3>X2b 报告字体与许可</h3>\n");
        body.append("<p>字体族名：<code>").append(ReportFontResolver.BUNDLED_ASSET_FAMILY).append("</code>；")
                .append("来源判定：<code>bundled-ofl-asset</code>；许可：SIL Open Font License 1.1")
                .append("（许可全文随模块资源分发）。报告本体不含任何外链资源。</p>\n");

        this.lastBody = body.toString();
        String coverage = body.toString().replaceAll("<[^>]+>", " ") + "\n" + attributeText(body);
        FontEmbed font = embedFont(coverage);

        String html = assemble(body, family, font.base64());
        return new Result(html.getBytes(StandardCharsets.UTF_8), html, family, font.sha256(),
                font.subsetBytes(), font.fullBytes(), font.glyphCount(), drawn,
                List.copyOf(forms), advice.stream().map(AdvicePlanner.Advice::targetId).toList(),
                advice.stream().map(AdvicePlanner.Advice::action).toList(), advice.size(),
                unit, si, family);
    }

    private List<Object[]> checksOf(String metricId) {
        Object o = report.dataQuality().get("gateChecks");
        if (!(o instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object v = map.get(metricId);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<Object[]> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Object basis = row.get("basis");
            Object cur = row.get("current");
            Object req = row.get("required");
            Object ok = row.get("passed");
            if (basis == null || cur == null || req == null) {
                continue;
            }
            out.add(new Object[]{String.valueOf(basis), ((Number) cur).longValue(),
                    ((Number) req).longValue(), Boolean.TRUE.equals(ok)});
        }
        return out;
    }

    private String profileRadar() {
        List<Segment> segs = report.segments();
        if (segs == null || segs.isEmpty()) {
            return "";
        }
        Segment seg = segs.get(0);
        List<String> dims = seg.dimensionIds();
        Map<String, Double> scores = seg.scores();
        if (dims == null || dims.isEmpty() || scores == null || scores.isEmpty()) {
            return "";
        }
        int n = dims.size();
        double max = 0.0;
        List<Double> vals = new ArrayList<>();
        for (String d : dims) {
            double v = scores.getOrDefault(d, 0.0);
            vals.add(v);
            max = Math.max(max, v);
        }
        double scale = max <= 0.0 ? 1.0 : max;
        List<Double> sorted = new ArrayList<>(vals);
        java.util.Collections.sort(sorted);
        double median = sorted.size() % 2 == 1
                ? sorted.get(sorted.size() / 2)
                : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0;

        double cx = 280;
        double cy = 196;
        double rMax = 152;
        final double rBase = 0.06;
        final double rSpan = 0.94;
        StringBuilder axes = new StringBuilder();
        StringBuilder poly = new StringBuilder();
        StringBuilder dots = new StringBuilder();
        for (double g : new double[] {0.25, 0.5, 0.75, 1.0}) {
            StringBuilder ring = new StringBuilder();
            for (int i = 0; i < n; i++) {
                double ang = -Math.PI / 2 + 2 * Math.PI * i / n;
                if (ring.length() > 0) {
                    ring.append(' ');
                }
                ring.append(Math.round(cx + Math.cos(ang) * rMax * g)).append(',')
                        .append(Math.round(cy + Math.sin(ang) * rMax * g));
            }
            axes.append("<polygon points=\"").append(ring)
                    .append("\" fill=\"none\" stroke=\"rgba(255,255,255,.14)\" stroke-width=\"1\"/>");
        }
        for (int i = 0; i < n; i++) {
            double ang = -Math.PI / 2 + 2 * Math.PI * i / n;
            double ux = Math.cos(ang);
            double uy = Math.sin(ang);
            axes.append("<line x1=\"").append(Math.round(cx)).append("\" y1=\"").append(Math.round(cy))
                    .append("\" x2=\"").append(Math.round(cx + ux * rMax)).append("\" y2=\"")
                    .append(Math.round(cy + uy * rMax))
                    .append("\" stroke=\"rgba(255,255,255,.14)\" stroke-width=\"1\"/>");
            double rv = rMax * (rBase + rSpan * (vals.get(i) / scale));
            poly.append(poly.length() == 0 ? "M" : "L").append(Math.round(cx + ux * rv)).append(',')
                    .append(Math.round(cy + uy * rv));
            dots.append("<circle cx=\"").append(Math.round(cx + ux * rv)).append("\" cy=\"")
                    .append(Math.round(cy + uy * rv)).append("\" r=\"3\" fill=\"").append("#70c8e8")
                    .append("\"><title>").append(esc(dimShort(dims.get(i)))).append(' ')
                    .append(n3(vals.get(i))).append("</title></circle>");
            double lx = cx + ux * (rMax + 26);
            double ly = cy + uy * (rMax + 26);
            String anchor = Math.abs(ux) < 0.3 ? "middle" : (ux > 0 ? "start" : "end");
            axes.append("<text x=\"").append(Math.round(lx)).append("\" y=\"").append(Math.round(ly))
                    .append("\" fill=\"").append("#a1a1a6").append("\" font-size=\"11\" text-anchor=\"")
                    .append(anchor).append("\">").append(esc(dimShort(dims.get(i))))
                    .append(" ").append(n3(vals.get(i))).append("</text>");
        }
        poly.append('Z');
        double rMed = rMax * (rBase + rSpan * (median / scale));
        String takeaway = "画像：" + segmentLabel(seg.segmentKey()) + "；共 " + n
                + " 轴有分值（缺的轴不画成 0），分值最高的一轴是 "
                + esc(dimShort(dims.get(vals.indexOf(max)))) + "（" + n3(max) + "）。";
        StringBuilder sb = new StringBuilder();
        sb.append("<h3 id=\"profile-seg\">你的玩法画像</h3>\n");
        sb.append("<figure data-layout=\"full-width-figure\" data-chart=\"profile-radar\" ")
                .append("data-series=\"dimension-scores\" data-high-low=\"referenced\" ")
                .append("data-ref=\"").append(n3(median))
                .append("\" data-ref-source=\"本画像各轴分值的中位数（图中虚线环）\" ")
                .append("data-source=\"analysis.json:segments[]\" data-takeaway=\"")
                .append(esc(takeaway)).append("\">\n");
        sb.append("<svg viewBox=\"0 0 560 430\" width=\"100%\" role=\"img\" aria-label=\"")
                .append(esc(takeaway))
                .append("\" style=\"display:block;max-width:100%;height:auto\">\n");
        sb.append(axes);
        sb.append("<circle cx=\"").append(Math.round(cx)).append("\" cy=\"").append(Math.round(cy))
                .append("\" r=\"").append(Math.round(rMed)).append("\" fill=\"none\" stroke=\"")
                .append("#e8a33d").append("\" stroke-width=\"1\" stroke-dasharray=\"3 3\" opacity=\".8\"/>");
        sb.append("<path d=\"").append(poly).append("\" fill=\"").append("#70c8e8")
                .append("\" opacity=\".22\" stroke=\"").append("#70c8e8").append("\" stroke-width=\"2\"/>");
        sb.append(dots);
        sb.append("</svg>\n");
        sb.append("<figcaption>").append(esc(takeaway))
                .append("（同心网格自外向内＝ 100% / 75% / 50% / 25% 刻度，满刻度＝本画像的最大轴分值 ")
                .append(n3(scale))
                .append("；橙色虚线环＝各轴中位数 ").append(n3(median))
                .append("，比它靠外即高于你自己的常态；分值为 0 的轴画在最内圈附近而非正中，")
                .append("以免与「未判定」看起来一样。）</figcaption>\n");
        sb.append("</figure>\n");
        List<String> allDims = List.of("D1_PACE", "D2_DEPTH", "D3_BREADTH", "D4_DISPERSION",
                "D5_CRAFT", "D6_COMBAT", "D7_PERSIST");
        List<String> missing = new ArrayList<>();
        for (String dd : allDims) {
            if (!scores.containsKey(dd)) {
                missing.add(dd);
            }
        }
        if (!missing.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String dd : missing) {
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append("<b>").append(esc(dimShort(dd))).append("</b>");
            }
            sb.append("<p style=\"margin:.8rem 0;padding:.75rem .9rem;background:rgba(232,163,61,.08);")
                    .append("border:1px solid rgba(232,163,61,.4);border-radius:6px;color:#f5f5f7;")
                    .append("font-size:.93rem;line-height:1.8\"><b>「未判定」是什么意思</b>：")
                    .append("这次观测里 ").append(names)
                    .append(" 这几轴的<b>样本不够</b>，所以既不是 0、也不是差 —— 是「没法判」。")
                    .append("<br>图上这些轴<b>不画进多边形</b>（避免把「没测出来」画成「0」），")
                    .append("下表里写「未判定（样本不足）」。</p>\n");
        }
        sb.append("<table><thead><tr><th>剖面轴</th><th>分值</th><th>状态</th></tr></thead><tbody>\n");
        for (String dd : allDims) {
            boolean has = scores.containsKey(dd);
            sb.append("<tr><td>").append(esc(dimShort(dd))).append("</td><td>")
                    .append(has ? n3(scores.get(dd)) : "—")
                    .append("</td><td>").append(has ? "已判定" : "未判定（样本不足）")
                    .append("</td></tr>\n");
        }
        sb.append("</tbody></table>\n");
        return sb.toString();
    }

    private static String n3(double v) {
        long t = Math.round(v * 1000.0);
        long frac = Math.abs(t % 1000);
        if (frac == 0) {
            return String.valueOf(t / 1000);
        }
        String f = String.valueOf(frac);
        while (f.length() < 3) {
            f = "0" + f;
        }
        return (t / 1000) + "." + f;
    }

    private static String dimShort(String dim) {
        switch (dim) {
            case "D1_PACE": return "推进节奏";
            case "D2_DEPTH": return "推进深度";
            case "D3_BREADTH": return "内容宽度";
            case "D4_DISPERSION": return "精力分散";
            case "D5_CRAFT": return "合成面";
            case "D6_COMBAT": return "战斗面";
            case "D7_PERSIST": return "持续性";
            default: return dim;
        }
    }

    private static String segmentLabel(String key) {
        if (key == null) {
            return "未命名画像";
        }
        switch (key) {
            case "segment.focused_specialist": return "专注专精型（把精力集中在少数几条线上）";
            case "segment.broad_explorer": return "广撒网探索型";
            case "segment.steady_progressor": return "稳步推进型";
            default: return key;
        }
    }

    private String assemble(StringBuilder body, String family, String fontBase64) {
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"zh-CN\" data-namespace=\"F,C##,C#,E,A,T\" data-report-format=\"")
                .append(FORMAT_ID).append("\">\n<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>").append(ReportDocument.REPORT_TITLE).append(" · ")
                .append(meta.exportId()).append("</title>\n");
        sb.append("<meta name=\"report-format\" content=\"").append(FORMAT_ID).append("\">\n");
        sb.append("<meta name=\"report-format-version\" content=\"")
                .append(esc(meta.reportFormatVersion())).append("\">\n");
        sb.append("<style>\n").append(css(family, fontBase64)).append("</style>\n");
        sb.append("</head>\n<body>\n");
        sb.append(newStyleOrDefault(humanLayerFirst(body.toString())));
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private String newStyleOrDefault(String legacy) {
        String content = ContentSections.render(contentInsightsJson,
                report.suppressedMetrics().size(), countZeroSuppressed(), replayJson);
        if (content == null) {
            return legacy + (replayJson == null || replayJson.isBlank() ? "" : replayAppendixBlock());
        }
        int navAt = legacy.indexOf("<nav class=\"mi-nav\"");
        int navEnd = navAt < 0 ? -1 : legacy.indexOf("</nav>", navAt);
        int sec1 = legacy.indexOf("<h2 id=\"sec-1\">");
        int hdrAt = legacy.indexOf("<header");
        int hdrEnd = hdrAt < 0 ? -1 : legacy.indexOf("</header>", hdrAt);
        if (navAt < 0 || navEnd < 0 || sec1 < 0 || hdrAt < 0 || hdrEnd < 0 || navEnd > sec1) {
            return content + legacy;
        }
        String headerHtml = legacy.substring(hdrAt, hdrEnd + 9);
        String headRest = legacy.substring(0, navAt).replace(headerHtml, "");
        String appendixBody = (headRest + legacy.substring(sec1))
                .replaceAll("\\s+data-critical-path(?=[\\s>])", "")
                .replaceAll("<h2 (id=\"sec-[0-9]+\")>(.*?)</h2>", "<h3 $1>附 · $2</h3>");
        return headerHtml + content + profileRadar()
                + "<h2 id=\"c6\">6 · 机器附录</h2>\n"
                + "<p class=\"lead\">以下为判据与可核查层：每个数字的来源、算法路径、"
                + "抑制明细与可机读片段。默认折叠，普通阅读可以跳过。</p>\n"
                + "<details id=\"machine-appendix\" data-machine-appendix=\"true\">\n"
                + "<summary>展开机器附录（旧版六节标题在此降为三级标题）</summary>\n"
                + appendixBody
                + "</details>\n";
    }

    private String replayAppendixBlock() {
        if (replayJson == null || replayJson.isBlank()) {
            return "";
        }
        Json.JsonObject r;
        try {
            r = JsonReader.parseObject(replayJson);
        } catch (Exception e) {
            return "";
        }
        StringBuilder sb = new StringBuilder(1 << 12);
        sb.append("<h3>附 · 会话回放与玩家上下文</h3>\n");
        sb.append("<p>这一块回答两个问题：<b>时间花在哪</b>（每个会话里，事件流告诉我们玩家在"
                + "探索、推进、战斗还是整理），以及<b>哪几段值得回看</b>。"
                + "它<b>不读任何事件内容</b>——只用事件类型与时间，所以它不可能带出别的地方没脱敏的东西。</p>\n");

        List<Object> sessions = arr(r.get("sessions"));
        int total = intOf(r.get("sessionTotal"));
        String source = String.valueOf(r.get("signalSource"));
        sb.append("<p>会话共 <b>").append(total).append(" 个</b>。")
          .append(esc(String.valueOf(r.get("note")))).append("</p>\n");
        if (!"none".equals(source)) {
            sb.append("<p>片段来源：<b>")
              .append("collector".equals(source) ? "采集端在当时当地自报的停滞/失败信号"
                      : "由交战时长与心跳间隔推出来的信号")
              .append("</b>。采集端自报的信号优先——它是当场判的，不是事后反推的。</p>\n");
        }

        if (!sessions.isEmpty()) {
            sb.append("<table><caption>每个会话的时间去向（单位：分钟）</caption>")
              .append("<thead><tr><th>会话</th><th>时长</th><th>探索</th><th>推进</th><th>战斗</th>")
              .append("<th>整理与自动化</th><th>无观测</th></tr></thead><tbody>\n");
            int idx = 0;
            for (Object o : sessions) {
                idx++;
                Json.JsonObject s = obj(o);
                Json.JsonObject lanes = obj(s.get("lanesMs"));
                sb.append("<tr><th scope=\"row\">第 ").append(idx).append(" 个会话</th><td>")
                  .append(minutes(longOf(s.get("durationMs")))).append("</td>");
                for (String lane : List.of("探索", "推进", "战斗", "整理与自动化", "无观测")) {
                    sb.append("<td>").append(minutes(longOf(lanes.get(lane)))).append("</td>");
                }
                sb.append("</tr>\n");
            }
            sb.append("</tbody></table>\n");
            sb.append("<p>「无观测」= 事件流在那一整段里<b>没有</b>任何说明玩家在做什么的事件。")
                    .append("它不是「没玩」，也不是「在闲逛」，而是「我们没有数据」。")                    .append("会话序号按会话号稳定排序生成（同一份数据两次导出序号相同）。</p>\n");
        }

        List<Object> segs = arr(r.get("segments"));
        sb.append("<h4>选中的回放片段：").append(segs.size()).append(" 段</h4>\n");
        if (segs.isEmpty()) {
            sb.append("<p>没有片段。<b>这一条不是「没有值得看的东西」</b>，理由见上面那句说明：")
                    .append("要么本流里没有可用于回放的信号，要么候选段没达到选取下限"
                            + "（段内标记数 < 4 且不是强信号）。</p>\n");
        } else {
            java.util.Map<String, Integer> order = new java.util.LinkedHashMap<>();
            int k = 0;
            for (Object o : sessions) {
                k++;
                order.put(String.valueOf(obj(o).get("sessionId")), k);
            }
            sb.append("<ul>\n");
            for (Object o : segs) {
                Json.JsonObject g = obj(o);
                String sid = String.valueOf(g.get("sessionId"));
                Integer n = order.get(sid);
                sb.append("<li><b>第 ").append(n == null ? "?" : n).append(" 个会话 · ")
                  .append(esc(String.valueOf(g.get("kind")))).append("</b>　")
                  .append(clock(longOf(g.get("startMs")))).append(" → ")
                  .append(clock(longOf(g.get("endMs")))).append("（")
                  .append(seconds(longOf(g.get("lengthMs")))).append("，段内标记 ")
                  .append(intOf(g.get("marks"))).append(" 个）<br>")
                  .append(esc(joinText(arr(g.get("reasons"))))).append("</li>\n");
            }
            sb.append("</ul>\n");
            sb.append("<p>每段的理由来自事件本身（交战持续多久、采集端在何时判定停滞），")
                    .append("取证用的证据号在下面这段机器可读的 JSON 里。</p>\n");
        }
        sb.append("<pre data-machine hidden>").append(esc(replayJson)).append("</pre>\n");
        return sb.toString();
    }

    private static long longOf(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static List<Object> arr(Object o) {
        if (o instanceof Json.JsonArray a) {
            return new java.util.ArrayList<>(a.items());
        }
        return List.of();
    }

    private static Json.JsonObject obj(Object o) {
        return o instanceof Json.JsonObject jo ? jo : new Json.JsonObject();
    }

    private static String joinText(List<Object> items) {
        StringBuilder sb = new StringBuilder();
        for (Object o : items) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(String.valueOf(o));
        }
        return sb.toString();
    }

    private static String minutes(long ms) {
        return round1(ms / 60_000.0) + " 分钟";
    }

    private static String seconds(long ms) {
        return Math.round(ms / 1000.0) + " 秒";
    }

    private static String clock(long ms) {
        long total = Math.max(0, ms) / 1000;
        long m = total / 60;
        long s = total % 60;
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    private static String round1(double v) {
        long tenths = Math.round(v * 10.0);
        return (tenths / 10) + "." + Math.abs(tenths % 10);
    }

    private int countZeroSuppressed() {
        int n = 0;
        for (MetricOutcome m : sortedSuppressed()) {
            if (m.status() == MetricStatus.ZERO) {
                n++;
            }
        }
        return n;
    }

    private static String humanLayerFirst(String html) {
        String[] heads = {"<h2>1 结论</h2>", "<h2>2 证据</h2>", "<h2>3 图表</h2>", "<h2>4 建议</h2>",
                "<h2>5 部分 JSON</h2>"};
        int[] at = new int[heads.length];
        for (int i = 0; i < heads.length; i++) {
            at[i] = html.indexOf(heads[i]);
            if (at[i] < 0 || (i > 0 && at[i] <= at[i - 1])) {
                return html;
            }
        }
        String head = html.substring(0, at[0]);
        String conclusion = html.substring(at[0], at[1]);
        String evidence = html.substring(at[1], at[2]);
        String charts = html.substring(at[2], at[3]);
        String advice = html.substring(at[3], at[4]);
        String tail = html.substring(at[4]);
        evidence = evidence.replace("<h2>2 证据</h2>", "<h2>4 证据</h2>");
        advice = advice.replace("<h2>4 建议</h2>", "<h2>2 建议</h2>");
        String joined = head + conclusion + advice + charts + evidence + tail;
        joined = moveFiguresIntoHumanLayer(joined);
        int dlStart = joined.indexOf("<dl class=\"meta\">");
        int dlEnd = dlStart < 0 ? -1 : joined.indexOf("</dl>", dlStart);
        String metaBlock = "";
        if (dlStart >= 0 && dlEnd > dlStart) {
            metaBlock = joined.substring(dlStart, dlEnd + 5);
            joined = joined.substring(0, dlStart) + joined.substring(dlEnd + 5);
        }
        String[] texts = {"1 结论", "2 建议", "3 图表", "4 证据", "5 部分 JSON", "6 隐私条款"};
        String[] labels = {"结论", "建议", "图表", "证据", "部分 JSON", "隐私条款"};
        for (int i = 0; i < texts.length; i++) {
            joined = joined.replace("<h2>" + texts[i] + "</h2>",
                    "<h2 id=\"sec-" + (i + 1) + "\">" + texts[i] + "</h2>");
        }
        joined = joined.replace("<div class=\"glossary\"", "<div class=\"glossary\" id=\"sec-9\"");
        int gs = joined.indexOf("<div class=\"glossary\"");
        int ge = joined.indexOf("<h2 id=\"sec-1\">");
        String glossary = "";
        if (gs >= 0 && ge > gs) {
            glossary = joined.substring(gs, ge);
            joined = joined.substring(0, gs) + joined.substring(ge);
        }
        joined = joined.replaceAll(">(F[0-9]+) — ", ">");
        joined = joined.replaceAll("<span class=\"kp-id\">[^<]*</span>\\s*", "");
        String intro = "<p class=\"intro-h\" id=\"sec-0\">怎么读这份报告</p>\n"
                + "<p class=\"lead\">这份报告在你的存档本地生成，数字只来自你自己的游玩记录，"
                + "没有任何上传。</p>\n"
                + "<p>建议按这个顺序看：先读「结论」，每条都写清了发生了什么、这意味着什么、可以做什么；"
                + "再读「建议」，每条都指名了对象与动作；需要核对数字时再看「图表」与「证据」，"
                + "它们给出每个数字的来源与算法路径。术语速查、机器可读的部分 JSON 与隐私条款都在后面，"
                + "不读也不影响理解。</p>\n";
        String appendix = (metaBlock.isEmpty() && glossary.isEmpty()) ? ""
                : "<p class=\"intro-h\" id=\"sec-8\">附：报告元数据与代号速查</p>\n"
                        + "<p>以下为复核用的生成信息与代号对照表，普通阅读可跳过。</p>\n"
                        + metaBlock + "\n" + glossary + "\n";
        StringBuilder nav = new StringBuilder("<nav class=\"mi-nav\" id=\"top\" aria-label=\"章节跳转\">");
        nav.append("<div class=\"mi-nav-row\">")
                .append("<a class=\"mi-nav-brand\" href=\"#top\">Octant（卦限）</a>")
                .append("<a href=\"#sec-0\">怎么读</a>")
                .append("<a href=\"#sec-1\">结论</a>")
                .append("<a href=\"#sec-2\">建议</a>")
                .append("</div>\n<div class=\"mi-nav-row sub\">")
                .append("<a href=\"#sec-9\">术语速查</a>")
                .append("<a href=\"#sec-3\">图表</a>")
                .append("<a href=\"#sec-4\">证据</a>")
                .append("<a href=\"#sec-5\">部分 JSON</a>")
                .append("<a href=\"#sec-6\">隐私条款</a>")
                .append("<a href=\"#sec-8\">报告元数据</a>")
                .append("</div>\n</nav>\n");
        int anchor = joined.indexOf("<div class=\"glossary\"");
        if (anchor < 0) {
            anchor = joined.indexOf("<h2 id=\"sec-1\">");
        }
        String body2 = anchor > 0 ? joined.substring(0, anchor) + intro + joined.substring(anchor)
                : intro + joined;
        return nav + body2 + appendix;
    }

    private static String moveFiguresIntoHumanLayer(String html) {
        java.util.List<String> figs = new ArrayList<>();
        java.util.List<String> evs = new ArrayList<>();
        StringBuilder sb = new StringBuilder(html);
        int from = 0;
        while (true) {
            int a = sb.indexOf("<figure", from);
            if (a < 0) {
                break;
            }
            int b = sb.indexOf("</figure>", a);
            if (b < 0) {
                break;
            }
            String fig = sb.substring(a, b + 9);
            int k = fig.indexOf("data-evidence=\"");
            evs.add(k < 0 ? "" : fig.substring(k + 15, k + 18));
            figs.add(fig);
            sb.replace(a, b + 9, "");
            from = a;
        }
        String out = sb.toString();
        boolean[] used = new boolean[figs.size()];
        int pos = 0;
        while (true) {
            int sf = out.indexOf("<section data-f=\"", pos);
            int sa = out.indexOf("<section data-a=\"", pos);
            int s;
            if (sf < 0) {
                s = sa;
            } else if (sa < 0) {
                s = sf;
            } else {
                s = Math.min(sf, sa);
            }
            if (s < 0) {
                break;
            }
            int e = out.indexOf("</section>", s);
            if (e < 0) {
                break;
            }
            String body = out.substring(s, e);
            StringBuilder add = new StringBuilder();
            for (int i = 0; i < figs.size(); i++) {
                if (!used[i] && !evs.get(i).isEmpty() && body.contains(evs.get(i))) {
                    used[i] = true;
                    add.append(figs.get(i)).append('\n');
                }
            }
            if (add.length() > 0) {
                out = out.substring(0, e) + add + out.substring(e);
                pos = e + add.length() + 10;
            } else {
                pos = e + 10;
            }
        }
        return out;
    }

    private String css(String family, String fontBase64) {
        StringBuilder c = new StringBuilder();
        c.append("@font-face{font-family:\"").append(family)
                .append("\";font-style:normal;font-weight:400;src:url(data:font/ttf;base64,")
                .append(fontBase64).append(") format('truetype');}\n");
        c.append(":root{color-scheme:dark;")
                .append("--mi-ink:#f5f5f7;--mi-ink-2:#d2d2d7;--mi-ink-3:#a1a1a6;")
                .append("--mi-line:rgba(255,255,255,.16);--mi-line-2:rgba(255,255,255,.26);")
                .append("--mi-surface:#1c1c1e;--mi-surface-2:#2c2c2e;")
                .append("--mi-accent:#2997ff;--mi-accent-ink:#64b5ff;")
                .append("--mi-radius:18px;--mi-radius-sm:10px;--mi-measure:68ch;")
                .append("--mi-page:#000;--mi-card:#1c1c1e;--mi-chip:rgba(255,255,255,.12);")
                .append("--mi-shadow:0 2px 12px rgba(0,0,0,.5);")
                .append("--mi-shadow-lg:0 4px 24px rgba(0,0,0,.6);")
                .append("--mi-nav-bg:rgba(0,0,0,.72);")
                .append("--mi-step-1:1rem;--mi-step-2:2rem;--mi-step-3:5rem;")
                .append("--mi-motion:160ms;}\n");
        c.append("html{font-family:\"").append(family)
                .append("\";font-size:100%;line-height:1.65;color:var(--mi-ink);")
                .append("background:var(--mi-page);-webkit-text-size-adjust:100%;")
                .append("scroll-behavior:smooth;}\n");
        c.append("body{max-width:64rem;margin:0 auto;padding:0 1.5rem 7rem;")
                .append("font-size:1rem;}\n");
        c.append(".mi-nav{position:sticky;top:0;z-index:20;display:block;")
                .append("padding:.7rem .25rem .55rem;margin:0 -.25rem 1rem;")
                .append("background:var(--mi-nav-bg);")
                .append("-webkit-backdrop-filter:saturate(180%) blur(20px);")
                .append("backdrop-filter:saturate(180%) blur(20px);")
                .append("border-bottom:1px solid var(--mi-line);}\n");
        c.append(".mi-nav-row{display:flex;align-items:center;gap:1.4rem;")
                .append("font-size:.95rem;overflow-x:auto;}\n");
        c.append(".mi-nav-row.sub{gap:1rem;font-size:.82rem;padding-top:.45rem;margin-top:.45rem;")
                .append("border-top:1px solid var(--mi-line);}\n");
        c.append(".mi-nav-brand{font-weight:600;color:var(--mi-ink);white-space:nowrap;")
                .append("text-decoration:none;}\n");
        c.append(".mi-nav a{color:var(--mi-ink-3);text-decoration:none;white-space:nowrap;")
                .append("transition:color var(--mi-motion) ease;}\n");
        c.append(".mi-nav a:hover{color:var(--mi-accent);}\n");
        c.append("h2,section,figure{scroll-margin-top:5.5rem;}\n");
        c.append(":target{outline:2px solid var(--mi-accent);outline-offset:6px;border-radius:var(--mi-radius-sm);}\n");
        c.append("p,.lead,blockquote{max-width:68ch;}\n");
        c.append("h1{font-size:2.75rem;line-height:1.05;letter-spacing:-.015em;")
                .append("margin:0 0 .9rem;font-weight:600;max-width:22ch;}\n");
        c.append("h2{font-size:2.25rem;line-height:1.1;letter-spacing:-.012em;")
                .append("margin:5rem 0 1.25rem;font-weight:600;}\n");
        c.append("h3{font-size:1.35rem;line-height:1.25;letter-spacing:-.008em;")
                .append("margin:2.6rem 0 .6rem;font-weight:600;}\n");
        c.append(".lead{color:var(--mi-ink-2);font-size:1.25rem;line-height:1.5;}\n");
        c.append(".intro-h{font-size:2.25rem;line-height:1.1;letter-spacing:-.012em;")
                .append("margin:5rem 0 1.25rem;font-weight:600;scroll-margin-top:5.5rem;}\n");
        c.append(".report-header dl{display:grid;grid-template-columns:auto 1fr;gap:.35rem 1.5rem;")
                .append("margin:1.2rem 0;}\n");
        c.append(".report-header{background:#1c1c1e;color:#f5f5f7;border-radius:var(--mi-radius);")
                .append("border:1px solid var(--mi-line);")
                .append("padding:3.5rem 2.5rem 3rem;margin:1.5rem 0 1rem;}\n");
        c.append(".report-header h1,.report-header dd,.report-header strong{color:#f5f5f7;}\n");
        c.append(".report-header dt{color:#a1a1a6;}\n");
        c.append(".report-header a{color:#2997ff;}\n");
        c.append(".report-header .notice{background:rgba(255,255,255,.08);")
                .append("border-color:rgba(255,255,255,.18);color:#f5f5f7;}\n");
        c.append(".report-header .namespace code,.report-header .meta code,")
                .append(".report-header code{background:var(--mi-chip);color:#f5f5f7;}\n");
        c.append(".report-header dt{font-weight:600;color:var(--mi-ink-3);font-size:.88rem;}\n");
        c.append(".report-header dd{margin:0;}\n");
        c.append(".namespace code,.meta code{background:var(--mi-chip);color:var(--mi-ink);")
                .append("padding:0 .25rem;border-radius:3px;}\n");
        c.append(".notice{background:var(--mi-surface);border:1px solid var(--mi-line);")
                .append("border-radius:var(--mi-radius);padding:1rem 1.2rem;margin:1.5rem 0;}\n");
        c.append("section[data-f],section[data-e],section[data-c],section[data-a],figure,.key-path{")
                .append("background:var(--mi-card);border:1px solid var(--mi-line);")
                .append("border-radius:var(--mi-radius);box-shadow:var(--mi-shadow);")
                .append("padding:1.4rem 1.5rem;margin:1.6rem 0;}\n");
        c.append("blockquote{margin:.4rem 0;padding-left:.75rem;border-left:3px solid var(--mi-line-2);")
                .append("color:var(--mi-ink-2);}\n");
        c.append("table{border-collapse:collapse;width:100%;margin:1rem 0;font-size:.97rem;}\n");
        c.append("caption{text-align:left;color:var(--mi-ink-3);padding-bottom:.4rem;}\n");
        c.append("th,td{border:0;border-bottom:1px solid var(--mi-line);padding:.62rem .6rem;")
                .append("text-align:left;vertical-align:top;}\n");
        c.append("thead th{background:transparent;color:var(--mi-ink-3);font-size:.84rem;")
                .append("font-weight:600;letter-spacing:.02em;border-bottom:1px solid var(--mi-line-2);}\n");
        c.append("tbody tr:last-child td{border-bottom:0;}\n");
        c.append("pre{margin:.4rem 0;padding:.7rem .85rem;background:var(--mi-surface-2);")
                .append("border:1px solid var(--mi-line);border-radius:var(--mi-radius-sm);")
                .append("overflow-x:auto;font-size:.86rem;color:var(--mi-ink-2);}\n");
        c.append("code{font-family:inherit;background:var(--mi-chip);color:var(--mi-ink);")
                .append("padding:0 .25rem;border-radius:4px;}\n");
        c.append("svg.chart-svg{display:block;width:100%;height:auto;border:0;")
                .append("border-radius:var(--mi-radius-sm);background:#fff;}\n");
        c.append(".ref,.limit,.axis-note,.no-figure{color:var(--mi-ink-3);font-size:.92rem;}\n");
        c.append("a{color:var(--mi-accent);text-decoration-thickness:1px;")
                .append("text-underline-offset:2px;transition:color var(--mi-motion) ease;}\n");
        c.append("a:hover{color:var(--mi-accent-ink);text-decoration-color:currentColor;}\n");
        c.append("a:active{opacity:.72;}\n");
        c.append("section[data-f],section[data-e],section[data-c],section[data-a]")
                .append("{transition:border-color var(--mi-motion) ease,")
                .append("box-shadow var(--mi-motion) ease;}\n");
        c.append("section[data-f]:hover,section[data-e]:hover,section[data-c]:hover,")
                .append("section[data-a]:hover{border-color:#c9ced6;")
                .append("box-shadow:0 1px 3px rgba(31,35,40,.07);}\n");
        c.append("@media (prefers-reduced-motion: reduce){*{transition:none !important;}}\n");
        c.append("figure[data-layout=\"full-width-figure\"]{margin:1rem 0;}\n");
        c.append("figure[data-layout=\"full-width-figure\"] figcaption{color:var(--mi-ink-3);")
                .append("font-size:.92rem;padding-top:.3rem;}\n");
        c.append("figcaption{color:var(--mi-ink-3);}\n");
        c.append("section[data-layout=\"two-column\"] .conclusion-two-col{display:grid;")
                .append("grid-template-columns:1.6fr 1fr;gap:.4rem 1.2rem;align-items:start;}\n");
        c.append("@media (max-width:640px){section[data-layout=\"two-column\"] ")
                .append(".conclusion-two-col{grid-template-columns:1fr;}}\n");
        c.append(".key-path{background:var(--mi-surface);border:1px solid var(--mi-line);")
                .append("border-left:4px solid var(--mi-accent);")
                .append("border-radius:var(--mi-radius);padding:1.2rem 1.4rem;margin:1.2rem 0 1.6rem;}\n");
        c.append(".key-path h3{margin-top:0;}\n");
        c.append(".key-path-list{margin:.4rem 0 0;padding-left:1.2rem;}\n");
        c.append(".key-path-list li{margin:.15rem 0;}\n");
        c.append(".kp-id{font-weight:700;}\n");
        c.append("@keyframes mi-rise{from{opacity:.7;transform:translateY(6px);}")
                .append("to{opacity:1;transform:translateY(0);}}\n");
        c.append(".key-path{animation-name:mi-rise;animation-duration:320ms;")
                .append("animation-iteration-count:1;animation-timing-function:ease-out;}\n");
        c.append("a,tbody tr{transition:color 180ms cubic-bezier(.25,.1,.25,1),")
                .append("background-color 180ms cubic-bezier(.25,.1,.25,1),")
                .append("border-color 180ms cubic-bezier(.25,.1,.25,1);}\n");
        c.append("tbody tr:hover{background:rgba(255,255,255,.06);}\n");
        c.append("a:focus-visible,tbody tr:focus-visible,[tabindex]:focus-visible{outline:2px solid var(--mi-accent);outline-offset:2px;border-radius:2px;}\n");
        c.append("@media (prefers-reduced-motion: no-preference){")
                .append(".key-path,section>h2,figure,.implication{")
                .append("animation-name:mi-rise;animation-duration:320ms;")
                .append("animation-timing-function:cubic-bezier(.25,.1,.25,1);")
                .append("animation-iteration-count:1;animation-fill-mode:both;}}\n");
        c.append("@media (prefers-reduced-motion: reduce){*{animation:none !important;}}\n");
        c.append("@media (prefers-reduced-motion: reduce){.key-path{animation:none;}}\n");
        c.append("[data-machine]{position:absolute;width:1px;height:1px;overflow:hidden;")
                .append("clip-path:inset(50%);white-space:pre-wrap;}\n");
        c.append("tr[data-suppressed]{background:rgba(255,196,120,.10);color:var(--mi-ink);}\n");
        c.append("@media print{body{max-width:none;padding:0;}section{break-inside:avoid;}")
                .append("svg.chart-svg{border-color:#bbb;}}\n");
        return c.toString();
    }

    private String statementOf(Conclusion c) {
        if (c.status() != MetricStatus.AVAILABLE && c.status() != MetricStatus.ZERO) {
            return "本项观测样本不足以判断";
        }
        String key = c.statementKey().replace("conclusion.", "");
        return switch (key) {
            case "m1.progress_share" -> "可达内容里约 " + pctOf(number(report.metric("M1a")))
                    + " 已经走到过";
            case "m2.stall_units" -> "有 " + num(number(report.metric("M2a")))
                    + " 个关卡单元停住没有推进";
            case "m3.playtime" -> "累计活跃 " + num(number(report.metric("M3a"))) + " 小时";
            case "m4.breadth" -> "八类内容里触达了 " + countPositive(report.metric("M4")) + " 类";
            case "m5.dispersion" -> "时间在各类内容之间的分布"
                    + (number(report.metric("M5a")) >= 0.8d ? "较为均匀" : "偏集中");
            case "m6.preference" -> "时间主要花在「" + named(report.metric("M6c")) + "」这一类内容上";
            case "m7.repetition_automation" -> "重复性操作占全部行为的约 "
                    + pctOf(number(report.metric("M7a")) * 100.0d);
            case "m8.combat" -> "战斗占用活跃时间的约 "
                    + pctOf(number(report.metric("M8a")) * 100.0d);
            case "m3e.fatigue" -> "最近三次会话的活跃时长与基线相当";
            default -> "本项观测已形成可用结论";
        };
    }

    private String meaningOf(Conclusion c, String statement) {
        String zeroNote = c.status() == MetricStatus.ZERO ? "本次确实测到了 0（不是缺数据）。" : "";
        return "这条结论说的是本次观测窗口内的实际行为：" + statement + "。" + zeroNote
                + "无基线可比；只描述观测，不做评价。";
    }

    private String machineBlock(Conclusion c) {
        StringBuilder sb = new StringBuilder("<pre data-machine hidden>");
        sb.append("conclusionId: ").append(c.conclusionId()).append('\n');
        sb.append("status: ").append(c.status().wireName()).append('\n');
        sb.append("statementKey: ").append(c.statementKey()).append('\n');
        sb.append("metricIds: ").append(String.join(",", c.metricIds())).append('\n');
        sb.append("sampleSize.current: ").append(c.sampleSize().currentSample()).append('\n');
        sb.append("sampleSize.minRequired: ").append(c.sampleSize().minRequired()).append('\n');
        sb.append("sampleSize.basis: ").append(c.sampleSize().basis().wireName()).append('\n');
        sb.append("confidence: ").append(c.confidence().wireName()).append('\n');
        sb.append("confidenceDetail.level: ").append(c.confidenceDetail().level().detailLevel()).append('\n');
        sb.append("evidenceIds: ").append(String.join(",", c.evidenceIds())).append('\n');
        if (!c.chartIds().isEmpty()) {
            sb.append("chartIds: ").append(String.join(",", c.chartIds())).append('\n');
        }
        if (c.reasonCode() != null) {
            sb.append("reasonCode: ").append(c.reasonCode()).append('\n');
        }
        if (c.limitations() != null && !c.limitations().isEmpty()) {
            sb.append("limitations: ").append(String.join(";", c.limitations())).append('\n');
        }
        if (c.alternativeExplanations() != null && !c.alternativeExplanations().isEmpty()) {
            sb.append("alternativeExplanations: ")
                    .append(String.join(";", c.alternativeExplanations())).append('\n');
        }
        sb.append("window: ").append(c.window().windowId()).append('\n');
        sb.append("</pre>\n");
        return sb.toString();
    }

    private String keyPathBlock(List<Conclusion> conclusions) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"key-path\" data-critical-path data-layout=\"emphasis-value\">\n");
        sb.append("<h3>先读这里：这份存档的 7 件关键事与去哪看</h3>\n");
        sb.append("<p>每条结论单元里都有一句结论、它对你的意义、可执行的建议，")
                .append("以及用于复核的表格与可核查层。按这个顺序读，五分钟内可读完关键部分。</p>\n");
        sb.append("<ol class=\"key-path-list\">\n");
        int unit = 0;
        for (Conclusion c : conclusions) {
            unit++;
            String fid = String.format(java.util.Locale.ROOT, "F%d", unit);
            sb.append("<li data-critical-item=\"").append(fid).append("\"><span class=\"kp-id\">")
                    .append(fid).append("</span> ").append(esc(categoryOf(c)))
                    .append(" <a href=\"#").append(chartAnchor(c)).append("\">看图</a></li>\n");
        }
        sb.append("</ol>\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    private static String categoryOf(Conclusion c) {
        if (c.status() != MetricStatus.AVAILABLE && c.status() != MetricStatus.ZERO) {
            return "本项样本不足，未形成结论";
        }
        String key = c.statementKey() == null ? "" : c.statementKey().replace("conclusion.", "");
        return switch (key) {
            case "m1.progress_share" -> "推进广度";
            case "m2.stall_units" -> "卡点";
            case "m3.playtime" -> "活跃时长";
            case "m3e.fatigue" -> "节奏变化";
            case "m4.breadth" -> "内容面覆盖";
            case "m5.dispersion" -> "精力分布";
            case "m6.preference" -> "偏好落点";
            case "m7.repetition_automation" -> "重复操作";
            case "m8.combat" -> "战斗占比";
            default -> "本次观测";
        };
    }

    private Figure.Drawing drawingOf(Chart c, MetricOutcome m) {
        return geometryOfChartBody(c, m);
    }

    public Figure.Drawing geometryOfChart(Chart c) {
        if (c == null) {
            return null;
        }
        MetricOutcome m = c.metricIds() == null || c.metricIds().isEmpty()
                ? null : report.metric(c.metricIds().get(0));
        return geometryOfChartBody(c, m);
    }

    private Figure.Drawing geometryOfChartBody(Chart c, MetricOutcome m) {
        double minRequired = m != null ? m.sampleSize().minRequired() : 0.0d;
        if (m != null && m.series() != null && m.series().size() >= 2
                && ("scalar".equals(m.outputType()) || "count".equals(m.outputType()))) {
            List<Figure.Point> seriesPts = new ArrayList<>();
            int k = 1;
            double sum = 0.0d;
            for (Map.Entry<String, Double> e : m.series().entrySet()) {
                seriesPts.add(new Figure.Point(e.getKey(), e.getValue(), "第 " + k + " 次会话"));
                sum += e.getValue();
                k++;
            }
            double avg = Math.round(sum / seriesPts.size() * 10.0d) / 10.0d;
            return FigureGeometry.groupedBars(chartTitle(c), seriesPts, avg,
                    "参照线 = 本序列均值 " + FigureGeometry.valueTextOf(avg, unitOf(c.unit()))
                            + "（序列内自比）", false,
                    pt -> unitOf(c.unit()));
        }
        List<Figure.Point> pts = withDisplayNames(FigureGeometry.pointsOf(
                m == null ? null : m.series(), m == null ? null : m.quantiles(),
                m == null ? null : m.value(), m == null ? c.chartId() : m.metricId()));
        String needUnit = m == null ? "" : m.sampleSize().minRequiredUnit();
        boolean sameRuler = sameUnit(c.unit(), needUnit);
        return FigureGeometry.compute(c.form(), c.unit(), pts,
                (minRequired > 0.0d && sameRuler) ? minRequired : null);
    }

    private static boolean sameUnit(String chartUnit, String needUnit) {
        String a = normUnit(chartUnit);
        String b = normUnit(needUnit);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b);
    }

    private static String normUnit(String u) {
        String s = u == null ? "" : u.trim();
        return "-".equals(s) || "无单位".equals(s) ? "" : s;
    }

    private static String targetNameOf(AdvicePlanner.Advice a) {
        String id = a.targetId() == null ? "" : a.targetId();
        if (id.isEmpty()) {
            return "该观测对象";
        }
        String ns = "minecraft";
        String rest = id;
        int colon = id.indexOf(':');
        if (colon > 0) {
            ns = id.substring(0, colon);
            rest = id.substring(colon + 1);
        }
        int slash = rest.indexOf('/');
        String leaf = slash >= 0 ? rest.substring(slash + 1) : rest;
        return "「" + leaf + "」（" + ns + "）";
    }

    private List<String[]> evidenceStatRows(Evidence e, MetricOutcome m) {
        List<String[]> rows = new ArrayList<>();
        String unit = m == null ? "" : unitOf(m.unit());
        if (m != null && m.series() != null && !m.series().isEmpty()) {
            List<Double> vs = new ArrayList<>(m.series().values());
            Collections.sort(vs);
            double median = medianOf(vs);
            double mean = vs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
            rows.add(new String[]{"分布", "共 " + vs.size() + " 项（" + unit + "）",
                    "见本节可核查层：指标序列（字段路径见本节可核查层）"});
            rows.add(new String[]{"中位数", num(median) + "（" + unit + "）",
                    "公式：对 series 升序取 p50（偶数个取中间两项均值）"});
            rows.add(new String[]{"均值", num(mean) + "（" + unit + "）",
                    "公式：sum(series)/count(series)"});
            rows.add(new String[]{"极值", num(vs.get(0)) + " ~ " + num(vs.get(vs.size() - 1))
                    + "（" + unit + "）", "公式：min(series) / max(series)"});
        } else if (m != null && m.quantiles() != null && !m.quantiles().isEmpty()) {
            for (Map.Entry<String, Double> q : new TreeMap<>(m.quantiles()).entrySet()) {
                rows.add(new String[]{"分位数 " + q.getKey(), num(q.getValue()) + "（" + unit + "）",
                        "见本节可核查层：分位数 " + q.getKey() + "（字段路径见本节可核查层）"});
            }
        } else if (m != null && m.value() instanceof Number n) {
            rows.add(new String[]{"取值", num(n.doubleValue()) + "（" + unit + "）",
                    "见本节可核查层：指标取值（字段路径见本节可核查层）"});
            rows.add(new String[]{"分布", "单点标量，无分布可比",
                    "该指标的 outputType=" + m.outputType() + "（契约未产出该量的分布）"});
        } else {
            rows.add(new String[]{"分布", "本项已抑制，不给出统计量",
                    "抑制块见 X4 表（原因码 + 当前/所需）"});
        }
        long units = m == null ? 0L : m.sampleSize().unitCount();
        if (units <= 0L && m != null && m.denominator() != null) {
            units = Math.round(m.denominator());
        }
        String basis = m == null ? "unknown" : m.sampleSize().basis().wireName();
        rows.add(new String[]{"观测单元数", String.valueOf(units),
                "见本节可核查层：观测单元数（口径 " + basis + "，键已写入 data-recompute）"});
        rows.add(new String[]{"判定所需样本量",
                (m == null ? 0L : m.sampleSize().minRequired()) + " "
                        + (m == null ? "" : m.sampleSize().minRequiredUnit()),
                "阈值表：docs/design/metrics-semantics.md 的 min-k 登记"});
        if (e.numerator() != 0.0d || e.denominator() != 0.0d) {
            rows.add(new String[]{"分子与分母", num(e.numerator()) + " / " + num(e.denominator())
                    + "（分母来源：" + e.denominatorSource() + "）",
                    "见本节可核查层：分子与分母（字段路径见本节可核查层）"});
        }
        return rows;
    }

    private static String statSignature(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            sb.append(r[0]).append('=').append(r[1]).append(';');
        }
        return sb.toString();
    }

    private String statBasisOf(MetricOutcome m) {
        if (m == null) {
            return "analysis.json → metrics[]（该指标不在本次分析输出里）";
        }
        return "analysis.json → metrics[]." + m.metricId()
                + (m.series() != null && !m.series().isEmpty() ? ".series" : ".value");
    }

    private MetricOutcome metricOfEvidence(Evidence e) {
        for (MetricOutcome m : report.metrics()) {
            if (e.featureIds().contains(m.featureId())) {
                return m;
            }
        }
        for (MetricOutcome m : report.suppressedMetrics()) {
            if (e.featureIds().contains(m.featureId())) {
                return m;
            }
        }
        return null;
    }

    private static double medianOf(List<Double> sorted) {
        int n = sorted.size();
        if (n == 0) {
            return 0.0d;
        }
        return (n % 2 == 1) ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0d;
    }

    private String implicationBlock(Conclusion c) {
        MetricOutcome m = c.metricIds().isEmpty() ? null : report.metric(c.metricIds().get(0));
        String key = c.statementKey() == null ? "" : c.statementKey().replace("conclusion.", "");
        String practice = switch (key) {
            case "m1.progress_share" -> "可达内容里没被走到的那部分，就是玩家卡住或找不到入口的地方。";
            case "m2.stall_units" -> "停住的关卡单元会在玩家推进时反复挡住同一条路。";
            case "m3.playtime" -> "活跃时长决定了单次游玩能覆盖多少内容。";
            case "m3e.fatigue" -> "时长在最近几次会话的变化，反映节奏是被内容推着走还是自己慢下来。";
            case "m4.breadth" -> "触达的内容类别越多，玩家越不容易只沿一条线走到黑。";
            case "m5.dispersion" -> "精力集中在少数类别时，其余类别的内容容易长期无人问津。";
            case "m6.preference" -> "时间集中的那一类，是玩家实际承认的主线。";
            case "m7.repetition_automation" -> "重复操作占比越高，玩家花在非新内容上的时间越多。";
            case "m8.combat" -> "战斗占用越多，非战斗内容（建造、探索、收集）被挤掉的时间越多。";
            default -> "本条描述的是本次观测窗口内的实际行为。";
        };
        String relative;
        if (m != null && m.series() != null && m.series().size() >= 2) {
            List<Double> vs = new ArrayList<>(m.series().values());
            Collections.sort(vs);
            relative = "在同一存档的 " + vs.size() + " 个同类取值里，最高 "
                    + num(vs.get(vs.size() - 1)) + "、最低 " + num(vs.get(0))
                    + "（自比，不跨存档）。";
        } else {
            relative = "本项只有一个观测值，本次无基线可比 —— 报告不给高低判断。";
        }
        return "<p class=\"implication\" data-implication=\""
                + esc(practice + relative) + "\">这意味着什么：" + esc(practice) + relative + "</p>\n";
    }

    private String glossaryBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"glossary\" data-glossary=\"codes\">\n");
        sb.append("<h3>读之前：指标与代号速查</h3>\n");
        sb.append("<p>报告正文里的每个代号都能在下表查到它是什么意思。")
                .append("代号只是「同一件东西的短名」，阅读时可以只看中文名。</p>\n");
        sb.append("<table class=\"glossary-table\"><thead><tr><th>代号</th><th>是什么</th>")
                .append("<th>它在这份报告里出现的位置</th></tr></thead><tbody>\n");
        Map<String, String> names = codeNames();
        for (Map.Entry<String, String> e : names.entrySet()) {
            sb.append("<tr><td><code>").append(esc(e.getKey())).append("</code></td><td>")
                    .append(esc(e.getValue())).append("</td><td>")
                    .append(esc(whereOf(e.getKey()))).append("</td></tr>\n");
        }
        sb.append("</tbody></table>\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    private List<MetricOutcome> sortedMetrics() {
        List<MetricOutcome> out = new ArrayList<>(report.metrics());
        out.sort(Comparator.comparing(MetricOutcome::metricId));
        return out;
    }

    private String humanMetricName(MetricOutcome m) {
        String label = featureLabel(m.featureId());
        return label == null || label.isBlank() ? m.metricId() : label;
    }

    private static Map<String, String> dimensionNames() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("D1_PACE", "推进节奏（单位活跃时间里走过多少可达内容）");
        out.put("D2_DEPTH", "推进深度（可达进度里已经走到的比例）");
        out.put("D3_BREADTH", "内容面宽度（各类内容里被触达的比例）");
        out.put("D4_DISPERSION", "精力分散度（时间是否均匀铺在各类内容上）");
        out.put("D5_CRAFT", "制作参与度（制作/合成类内容的占比）");
        out.put("D6_COMBAT", "战斗参与度（战斗时间的占比）");
        out.put("D7_PERSIST", "留存强度（连续回访的稳定程度）");
        return out;
    }

    private List<MetricOutcome> allMetrics() {
        List<MetricOutcome> out = new ArrayList<>(report.metrics());
        out.addAll(report.suppressedMetrics());
        out.sort(Comparator.comparing(MetricOutcome::metricId));
        return out;
    }

    private List<Figure.Point> withDisplayNames(List<Figure.Point> pts) {
        Map<String, String> names = codeNames();
        List<Figure.Point> out = new ArrayList<>(pts.size());
        for (Figure.Point p : pts) {
            out.add(new Figure.Point(p.label(), p.value(), humanize(p.label(), names)));
        }
        return out;
    }

    private Map<String, String> codeNames() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Conclusion c : sortedConclusions()) {
            map.put(c.conclusionId(), categoryOf(c) + "（结论的机器名）");
        }
        for (Chart ch : sortedCharts()) {
            MetricOutcome m = report.metric(ch.metricIds().get(0));
            map.put(ch.chartId(), m == null ? chartTitle(ch) : humanMetricName(m));
        }
        for (MetricOutcome m : allMetrics()) {
            map.put(m.metricId(), humanMetricName(m) + "（指标）");
        }
        for (Map.Entry<String, String> e : dimensionNames().entrySet()) {
            map.put(e.getKey(), e.getValue() + "（剖面轴）");
        }
        for (Evidence e : sortedEvidence()) {
            MetricOutcome m = metricOfEvidence(e);
            map.put(e.evidenceId(), (m == null ? "统计口径" : humanMetricName(m)) + "的统计证据");
        }
        for (AdvicePlanner.Advice a : AdvicePlanner.plan(report)) {
            map.put(a.id(), a.headline() + "（建议）");
        }
        for (int i = 1; i <= 99; i++) {
            String id = String.format(java.util.Locale.ROOT, "A%02d", i);
            if (!map.containsKey(id)) {
                map.put(id, "建议 " + id + "（见第 2 节对应条目）");
            }
        }
        for (String form : List.of("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10",
                "C11", "C12", "C13", "C14", "C15", "C16", "C17")) {
            map.put(form, "图型模板 " + form + "（HIG §6.2 的图型码，与图表项编号不同）");
        }
        for (Chart ch : sortedCharts()) {
            MetricOutcome m = report.metric(ch.metricIds().get(0));
            map.put(ch.chartId(), m == null ? chartTitle(ch) : humanMetricName(m));
        }
        return map;
    }

    static String humanize(String text, Map<String, String> names) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        List<String> keys = new ArrayList<>(names.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String k : keys) {
            if (!out.contains(k)) {
                continue;
            }
            String name = names.get(k);
            int paren = name.indexOf('（');
            String shortName = paren > 0 ? name.substring(0, paren) : name;
            out = replaceToken(out, k, shortName);
        }
        return out;
    }

    static String replaceToken(String text, String token, String replacement) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            int j = text.indexOf(token, i);
            if (j < 0) {
                sb.append(text, i, text.length());
                return sb.toString();
            }
            char before = j == 0 ? ' ' : text.charAt(j - 1);
            int end = j + token.length();
            char after = end >= text.length() ? ' ' : text.charAt(end);
            boolean okBefore = !Character.isLetterOrDigit(before) && before != '_';
            boolean okAfter = !Character.isLetterOrDigit(after) && after != '_' && after != '-';
            if (okBefore && okAfter) {
                sb.append(text, i, j).append(replacement);
            } else {
                sb.append(text, i, end);
            }
            i = end;
        }
    }

    private Chart primaryChartOf(FigureGroups.Group g) {
        for (String mid : g.metricIds()) {
            for (Chart c : sortedCharts()) {
                if (c.metricIds().contains(mid) && !c.suppressed()) {
                    return c;
                }
            }
        }
        return null;
    }

    private String primaryChartOf(Chart c) {
        FigureGroups.Group g = groupOfChart(c);
        if (g == null || "independent".equals(g.kind())) {
            return null;
        }
        Chart primary = primaryChartOf(g);
        return primary == null || primary.chartId().equals(c.chartId()) ? null : primary.chartId();
    }

    private FigureGroups.Group groupOfChart(Chart c) {
        for (String mid : c.metricIds()) {
            FigureGroups.Group g = FigureGroups.groupOfMetric(mid);
            if (g != null) {
                return g;
            }
        }
        return null;
    }

    private boolean isMergedNonPrimary(Chart c) {
        return primaryChartOf(c) != null;
    }

    private String mergedFiguresBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"merged-intro\">下面的每一张图回答一个读者问题：")
                .append("组内的量共享同一把尺（或同一条时间轴），可以在同一张图上直接比。</p>\n");
        for (FigureGroups.Group g : FigureGroups.groups()) {
            if ("independent".equals(g.kind())) {
                continue;
            }
            Chart primary = primaryChartOf(g);
            if (primary == null) {
                continue;
            }
            List<Figure.Point> series = new ArrayList<>();
            StringBuilder names = new StringBuilder();
            boolean any = false;
            java.util.Map<String, Integer> nameSeen = new java.util.LinkedHashMap<>();
            for (String mid : g.metricIds()) {
                MetricOutcome m = report.metric(mid);
                if (m == null || !(m.value() instanceof Number n)) {
                    continue;
                }
                String nm = uniqueNameOf(mid, nameOfMetric(mid));
                int seen = nameSeen.merge(nm, 1, Integer::sum);
                if (seen > 1) {
                    nm = nm + "①②③④⑤⑥⑦⑧⑨".charAt(Math.min(seen - 2, 8));
                }
                series.add(new Figure.Point(mid, n.doubleValue(), nm));
                names.append(names.length() == 0 ? "" : " · ").append(nm);
                any = true;
            }
            if (!any) {
                continue;
            }
            Figure.Drawing d = FigureGroups.needsSeries(g)
                    ? FigureGeometry.lineSeries(g.question(), markerSeries(series), 0.5d,
                            "参照线 = 0.5（归一化区间中线；同组内自比）", null)
                    : FigureGeometry.groupedBars(g.question(), series, 0.5d,
                            "参照线 = 0.5（归一化区间中线；同组内自比）", true,
                            pt -> unitOfMetric(pt.label()));
            int drawnBars = drawnDataPointCount(d);
            int anchors = 0;
            for (Figure.Primitive p : d.primitives()) {
                if (Figure.Drawing.roleOf(p) == Figure.Role.VALUE
                        && Figure.Drawing.effectOf(p) == Figure.Effect.SCALES
                        && !(p instanceof Figure.Polyline)) {
                    anchors++;
                }
            }
            if (anchors != series.size()) {
                throw new IllegalStateException("合并图实画锚点数与组内量数不一致（" + g.key() + "，"
                        + "「" + g.question() + "」）：实画锚点 " + anchors
                        + " != 组内量数 " + series.size());
            }
            String evidence = primary.evidenceIds().isEmpty() ? "" : primary.evidenceIds().get(0);
            sb.append("<figure data-merged=\"").append(esc(g.key()))
                    .append("\" data-merged-why=\"").append(esc(g.whyTogether()))
                    .append("\" data-layout=\"full-width-figure\" data-takeaway=\"")
                    .append(esc(mergedTakeaway(g, series, drawnBars, drawnPrimitiveCount(d)))).append('"')
                    .append(refAttrs(d));
            if (!evidence.isEmpty()) {
                sb.append(" data-evidence=\"").append(esc(evidence)).append('"');
            }
            sb.append(">\n");
            sb.append(FigureSvg.toSvg(d, "G_" + g.key(),
                    "同图比较：" + g.question() + "；本图画出 " + drawnPrimitiveCount(d)
                            + " 根条（其中 " + drawnBars + " 个为互异取值）、"
                            + d.referencePointCount() + " 个带数值的参考标记")).append('\n');
            sb.append("<figcaption>").append(esc(g.question())).append("：")
                    .append(esc(names.toString())).append("。")
                    .append(esc(g.whyTogether())).append("</figcaption>\n");
            sb.append("</figure>\n");
        }
        if (SERIES_FIGURES_ENABLED) {
            sb.append(sessionSeriesBlock());
        }
        return sb.toString();
    }

    private static final boolean SERIES_FIGURES_ENABLED = true;

    private String sessionSeriesBlock() {
        StringBuilder sb = new StringBuilder();
        List<Figure.Point> allPoints = new ArrayList<>();
        List<com.octant.pipeline.analysis.MetricOutcome> withSeries = new ArrayList<>();
        for (String mid : new String[]{"M1a", "M1b", "M1c"}) {
            com.octant.pipeline.analysis.MetricOutcome m = report.metric(mid);
            if (m == null || m.series() == null || m.series().size() < 2) {
                continue;
            }
            if (!"scalar".equals(m.outputType())) {
                continue;
            }
            withSeries.add(m);
        }
        if (withSeries.isEmpty()) {
            return "";
        }
        sb.append("<p class=\"merged-intro\">下面按会话顺序给出进度族的逐会话走向："
                + "第 i 根条就是第 i 次会话（累计到该次为止），条与参照线的高低就是"
                + "\"这次比上次快还是慢\"。</p>\n");
        for (com.octant.pipeline.analysis.MetricOutcome m : withSeries) {
            List<Figure.Point> pts = new ArrayList<>();
            int i = 1;
            double sum = 0.0d;
            for (Map.Entry<String, Double> e : m.series().entrySet()) {
                String display = "第 " + i + " 次会话";
                pts.add(new Figure.Point(e.getKey(), e.getValue(), display));
                sum += e.getValue();
                i++;
            }
            if (pts.size() < 2) {
                continue;
            }
            double mean = Math.round(sum / pts.size() * 10.0d) / 10.0d;
            String unit = unitOf(m.unit());
            Figure.Drawing d = FigureGeometry.groupedBars(
                    humanize(nameOfMetric(m.metricId()), codeNames()) + "（按会话顺序）", pts, mean,
                    "参照线 = 本序列均值 " + FigureGeometry.valueTextOf(mean, unit)
                            + "（序列内自比）", false,
                    pt -> unit);
            int drawnPts = drawnPrimitiveCount(d); int drawnVals = drawnDataPointCount(d);
            if (drawnPts != pts.size()) {
                throw new IllegalStateException("会话序列图实画点数与采样点数不一致（"
                        + m.metricId() + "）：实画 " + drawnPts + " != 采样 " + pts.size());
            }
            String takeaway = "「" + humanize(nameOfMetric(m.metricId()), codeNames())
                    + "」按会话顺序图上画出 " + drawnPts + " 根条（其中 " + drawnVals + " 个为互异取值）（第 1 次到第 "
                    + pts.size() + " 次会话，累计口径），最高 "
                    + FigureGeometry.valueTextOf(maxOf(pts), unit) + "、最低 "
                    + FigureGeometry.valueTextOf(minOf(pts), unit)
                    + "，序列均值 " + FigureGeometry.valueTextOf(mean, unit)
                    + "；这 " + drawnPts + " 项只有 " + drawnVals + " 种不同的取值，即图上看得"
                    + "出 " + drawnVals + " 种高低，高低顺序可直接比较。";
            String evidence = "";
            String tableRef = "";
            for (Chart c : sortedCharts()) {
                if (c.metricIds() != null && c.metricIds().contains(m.metricId())) {
                    if (tableRef.isEmpty()) {
                        tableRef = c.tableRef();
                    }
                    if (!c.evidenceIds().isEmpty()) {
                        evidence = c.evidenceIds().get(0);
                        break;
                    }
                }
            }
            if (!tableRef.isEmpty()) {
                takeaway = takeaway + "本图与等价表 " + tableRef + " 同源同长："
                        + "二者取自同一组 " + pts.size() + " 个逐会话取值，不是两次独立观测。";
            }
            sb.append("<figure data-series-of=\"").append(esc(m.metricId()))
                    .append("\" data-series-basis=\"cumulative_prefix_sessions\"")
                    .append(" data-layout=\"full-width-figure\" data-takeaway=\"")
                    .append(esc(takeaway)).append('"').append(refAttrs(d));
            if (!evidence.isEmpty()) {
                sb.append(" data-evidence=\"").append(esc(evidence)).append('"');
            }
            sb.append(">\n");
            sb.append(FigureSvg.toSvg(d, "S_" + m.metricId(),
                    humanize(nameOfMetric(m.metricId()), codeNames())
                            + "（按会话顺序）；本图画出 " + drawnPrimitiveCount(d) + " 根条（其中 " + drawnPts + " 个为互异取值）、"
                            + d.referencePointCount() + " 个带数值的参考标记")).append('\n');
            sb.append("<figcaption>").append(esc(humanize(nameOfMetric(m.metricId()), codeNames())))
                    .append("随时间的变化：横轴是会话顺序（第 1 次到第 ").append(pts.size())
                    .append(" 次，累计口径），每个点都标着到该次为止的取值，虚线是本序列均值。")
                    .append("</figcaption>\n");
            sb.append("</figure>\n");
        }
        return sb.toString();
    }

    private static double maxOf(List<Figure.Point> pts) {
        double v = pts.get(0).value();
        for (Figure.Point p : pts) {
            v = Math.max(v, p.value());
        }
        return v;
    }

    private static double minOf(List<Figure.Point> pts) {
        double v = pts.get(0).value();
        for (Figure.Point p : pts) {
            v = Math.min(v, p.value());
        }
        return v;
    }

    private List<Figure.Point> markerSeries(List<Figure.Point> series) {
        List<Figure.Point> out = new ArrayList<>();
        int i = 1;
        for (Figure.Point p : series) {
            out.add(new Figure.Point("当前存档·第 " + i + " 个量", p.value(),
                    "当前存档·第 " + i + " 个量"));
            i++;
        }
        return out;
    }

    private String mergedTakeaway(FigureGroups.Group g, List<Figure.Point> series, int drawnBars,
                                              int drawnPrimitives) {
        Figure.Point max = series.get(0);
        Figure.Point min = series.get(0);
        for (Figure.Point p : series) {
            if (p.value() > max.value()) {
                max = p;
            }
            if (p.value() < min.value()) {
                min = p;
            }
        }
        java.util.Map<Double, Integer> mult = new java.util.LinkedHashMap<>();
        for (Figure.Point p : series) {
            mult.merge(p.value(), 1, Integer::sum);
        }
        StringBuilder ties = new StringBuilder();
        for (Map.Entry<Double, Integer> e : mult.entrySet()) {
            if (e.getValue() > 1) {
                ties.append(ties.length() == 0 ? "" : "、")
                        .append(FigureGeometry.valueTextOf(e.getKey(), null))
                        .append(" 有 ").append(e.getValue()).append(" 个量取到同一值");
            }
        }
        String tail = FigureGroups.needsSeries(g)
                ? "本组（" + g.question() + "）与时间相关的量目前只有 1 个时点，图上画出 "
                        + drawnBars + " 个可比较的取值，本组 " + series.size()
                        + " 个量各占 1 个时点，趋势不适用（等序列接通后再谈变化）。"
                : "图上共画出 " + drawnBars + " 个可比较的取值（本组「" + g.question()
                        + "」的 " + series.size() + " 个量在这把尺上同比，对应 "
                        + drawnPrimitives + " 根条"
                        + (ties.length() == 0 ? "" : "；其中 " + ties
                                + "，即这几根条等高、形状上看不出差别") + "）。";
        java.util.Map<String, String> unitByPoint = new java.util.LinkedHashMap<>();
        java.util.Set<String> units = new java.util.LinkedHashSet<>();
        for (Figure.Point p : series) {
            String u = unitOfMetric(p.label());
            unitByPoint.put(p.label(), u);
            units.add(u);
        }
        boolean oneUnit = units.size() == 1;
        if (!FigureGroups.needsSeries(g) && !oneUnit) {
            java.util.Map<String, java.util.List<String>> byUnit = new java.util.LinkedHashMap<>();
            for (Figure.Point p : series) {
                byUnit.computeIfAbsent(unitByPoint.get(p.label()),
                        k -> new java.util.ArrayList<>()).add(p.shown());
            }
            StringBuilder parts = new StringBuilder();
            for (Map.Entry<String, java.util.List<String>> en : byUnit.entrySet()) {
                parts.append(parts.length() == 0 ? "" : "；")
                        .append(en.getValue().size()).append(" 个量的单位是「")
                        .append(en.getKey()).append("」（")
                        .append(String.join("、", en.getValue())).append("）");
            }
            tail = "图上共画出 " + drawnBars + " 个可比较的取值（本组「" + g.question() + "」的 "
                    + series.size() + " 个量：" + parts
                    + "；组内量纲不一致，故只与同量纲者比高低，不同量纲者仅以绝对值列出"
                    + (ties.length() == 0 ? "" : "；其中 " + ties
                            + "，即这几根条等高、形状上看不出差别") + "）。";
        }
        StringBuilder all = new StringBuilder();
        for (Figure.Point p : series) {
            all.append(all.length() == 0 ? "" : "、").append(p.shown()).append('=')
                    .append(FigureGeometry.valueTextOf(p.value(), unitByPoint.get(p.label())));
        }
        String span = oneUnit
                ? "，两者相差 " + FigureGeometry.valueTextOf(max.value() - min.value(),
                        units.iterator().next())
                : "；组内量纲不一致，故不做相减（各量的单位已逐个标在取值后："
                        + String.join("、", units) + "）";
        String headRank;
        if (oneUnit) {
            headRank = max.shown() + " 最高（"
                    + FigureGeometry.valueTextOf(max.value(), unitByPoint.get(max.label())) + "）、"
                    + min.shown() + " 最低（"
                    + FigureGeometry.valueTextOf(min.value(), unitByPoint.get(min.label())) + "）";
        } else {
            java.util.Map<String, java.util.List<Figure.Point>> byUnitRank = new java.util.LinkedHashMap<>();
            for (Figure.Point p : series) {
                byUnitRank.computeIfAbsent(unitByPoint.get(p.label()),
                        k -> new java.util.ArrayList<>()).add(p);
            }
            StringBuilder hb = new StringBuilder();
            for (Map.Entry<String, java.util.List<Figure.Point>> en : byUnitRank.entrySet()) {
                Figure.Point hi = en.getValue().get(0);
                Figure.Point lo = en.getValue().get(0);
                for (Figure.Point p : en.getValue()) {
                    if (p.value() > hi.value()) {
                        hi = p;
                    }
                    if (p.value() < lo.value()) {
                        lo = p;
                    }
                }
                hb.append(hb.length() == 0 ? "" : "；")
                        .append("量纲「").append(en.getKey()).append("」内 ")
                        .append(hi.shown()).append(" 最高（")
                        .append(FigureGeometry.valueTextOf(hi.value(), en.getKey())).append("）");
                if (en.getValue().size() > 1) {
                    hb.append("、").append(lo.shown()).append(" 最低（")
                            .append(FigureGeometry.valueTextOf(lo.value(), en.getKey())).append("）");
                }
            }
            headRank = hb.toString();
        }
        return "在「" + g.question() + "」这一组里，" + headRank
                + span + "；逐项取值 " + all + "；" + tail;
    }

    private String nameOfMetric(String metricId) {
        Map<String, String> dims = dimensionNames();
        if (dims.containsKey(metricId)) {
            String v = dims.get(metricId);
            int paren = v.indexOf('（');
            return paren > 0 ? v.substring(0, paren) : v;
        }
        MetricOutcome m = report.metric(metricId);
        return m == null ? metricId : humanMetricName(m);
    }

    private Map<String, String> chartNames() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Chart ch : sortedCharts()) {
            MetricOutcome m = report.metric(ch.metricIds().get(0));
            map.put(ch.chartId(), m == null ? chartTitle(ch) : humanMetricName(m));
        }
        return map;
    }

    private static String whereOf(String code) {
        if (code.startsWith("F")) {
            return "第 1 节结论";
        }
        if (code.startsWith("M") || code.startsWith("D")) {
            return "第 4 节证据与第 3 节图表";
        }
        if (code.startsWith("C")) {
            return "第 3 节图表";
        }
        if (code.startsWith("E")) {
            return "第 4 节证据";
        }
        if (code.startsWith("A")) {
            return "第 1 节结论的建议指针与第 2 节建议";
        }
        return "见相关章节";
    }

    List<String> glossaryCoverage() {
        Map<String, String> names = codeNames();
        java.util.Set<String> body = new java.util.LinkedHashSet<>();
        String text = lastBody == null ? "" : lastBody;
        java.util.regex.Matcher gm = java.util.regex.Pattern
                .compile("\\b(M\\d+[a-z]?|D\\d_[A-Z]+|E\\d+|A\\d+|C\\d+)\\b").matcher(text);
        while (gm.find()) {
            body.add(gm.group(1));
        }
        List<String> missing = new ArrayList<>();
        for (String c : body) {
            if (!names.containsKey(c)) {
                missing.add(c);
            }
        }
        return missing;
    }

    private String chartAnchor(Conclusion c) {
        for (Chart ch : sortedCharts()) {
            if (c.chartIds().contains(ch.chartId())) {
                return ch.chartId();
            }
        }
        return "X4H";
    }

    private String takeawayOf(Chart c, Figure.Drawing drawing) {
        int n = drawnDataPointCount(drawing);
        java.util.List<Double> drawn = new java.util.ArrayList<>();
        for (Figure.Primitive p : drawing.primitives()) {
            if (Figure.Drawing.roleOf(p) != Figure.Role.VALUE
                    || Figure.Drawing.effectOf(p) != Figure.Effect.SCALES) {
                continue;
            }
            Double v = Figure.Drawing.valueOf(p);
            if (v != null) {
                drawn.add(v);
            }
        }
        if (drawn.isEmpty() && n == 0) {
            return "该单元的取值只出现在刻度与等价表里，图上没有随数值缩放的图形，逐项取值见同组等价表。";
        }
        if (n == 1) {
            double v = drawn.isEmpty() ? 0.0d : drawn.get(0);
            double pct = drawing.domain() > 0.0d ? 100.0d * v / drawing.domain() : 0.0d;
            double rest = Math.max(0.0d, drawing.domain() - v);
            return "该图只画出 1 个可比较的取值 " + FigureGeometry.valueTextOf(v, c.unit())
                    + "，图上只有这 1 项 —— 不存在「高低顺序」可言；"
                    + "它处在 0 ~ " + FigureGeometry.fmt(drawing.domain())
                    + FigureGeometry.unitSuffix(c.unit()) + " 这条尺的 "
                    + FigureGeometry.fmt(Math.round(pct * 10.0d) / 10.0d) + "% 处，"
                    + "离上界还差 " + FigureGeometry.fmt(rest)
                    + FigureGeometry.unitSuffix(c.unit()) + "。";
        }
        if (drawn.isEmpty()) {
            return "该单元的取值只出现在刻度与等价表里，图上没有随数值缩放的图形，逐项取值见同组等价表。";
        }
        double max = drawn.get(0);
        double min = drawn.get(0);
        for (double v : drawn) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        double spread = Math.max(0.0d, max - min) / Math.max(1.0e-9d, drawing.domain());
        String selfName = humanize(chartTitle(c), codeNames());
        int prims = drawnPrimitiveCount(drawing);
        return "「" + selfName + "」图上画出 " + n + " 个可比较的取值，最高 "
                + FigureGeometry.valueTextOf(max, c.unit())
                + "、最低 " + FigureGeometry.valueTextOf(min, c.unit())
                + "，极差相当于本图整条尺的约 "
                + FigureGeometry.fmt(Math.round(spread * 1000.0d) / 10.0d)
                + "%，这 " + n + " 项高低顺序可直接比较"
                + (prims > n ? "（图上是 " + prims + " 根条，其中有取值相同的，故只看得出 "
                        + n + " 种高低）" : "") + "。";
    }

    private static int drawnDataPointCount(Figure.Drawing d) {
        java.util.Map<Double, Boolean> seen = new java.util.LinkedHashMap<>();
        for (Figure.Primitive p : d.primitives()) {
            if (Figure.Drawing.roleOf(p) == Figure.Role.VALUE
                    && Figure.Drawing.effectOf(p) == Figure.Effect.SCALES) {
                Double v = Figure.Drawing.valueOf(p);
                if (v != null) {
                    seen.putIfAbsent(v, Boolean.TRUE);
                }
            }
        }
        return seen.size();
    }

    private static int drawnPrimitiveCount(Figure.Drawing d) {
        int n = 0;
        for (Figure.Primitive p : d.primitives()) {
            if (Figure.Drawing.roleOf(p) == Figure.Role.VALUE
                    && Figure.Drawing.effectOf(p) == Figure.Effect.SCALES) {
                n++;
            }
        }
        return n;
    }

    private static String refAttrs(Figure.Drawing d) {
        if (d == null) {
            return "";
        }
        String[] priority = {"reference", "min-required", "range-end", "band-domain", "range-start"};
        for (String want : priority) {
            for (Figure.Primitive p : d.primitives()) {
                if (Figure.Drawing.roleOf(p) != Figure.Role.REFERENCE
                        || Figure.Drawing.effectOf(p) != Figure.Effect.SCALES) {
                    continue;
                }
                if (!want.equals(Figure.Drawing.labelOf(p))) {
                    continue;
                }
                Double v = Figure.Drawing.valueOf(p);
                if (v == null) {
                    continue;
                }
                return " data-ref=\"" + esc(FigureGeometry.fmt(v)) + "\" data-ref-source=\""
                        + esc(refSourceOf(d, want)) + "\"";
            }
        }
        List<Double> vals = new ArrayList<>();
        for (Figure.Primitive p : d.primitives()) {
            if (Figure.Drawing.roleOf(p) == Figure.Role.VALUE
                    && Figure.Drawing.effectOf(p) == Figure.Effect.SCALES) {
                Double v = Figure.Drawing.valueOf(p);
                if (v != null) {
                    vals.add(v);
                }
            }
        }
        if (vals.size() >= 3) {
            double med = medianOf(vals);
            return " data-ref=\"" + esc(FigureGeometry.fmt(med))
                    + "\" data-ref-source=\"本图各取值的中位数（由图内数值算出）\"";
        }
        return "";
    }

    private static String refNoteOf(Figure.Drawing d) {
        if (d == null || !refAttrs(d).contains("中位数")) {
            return "";
        }
        List<Double> vals = new ArrayList<>();
        for (Figure.Primitive p : d.primitives()) {
            if (Figure.Drawing.roleOf(p) == Figure.Role.VALUE
                    && Figure.Drawing.effectOf(p) == Figure.Effect.SCALES) {
                Double v = Figure.Drawing.valueOf(p);
                if (v != null) {
                    vals.add(v);
                }
            }
        }
        if (vals.size() < 3) {
            return "";
        }
        return "（参照：本图各取值的中位数 " + FigureGeometry.fmt(medianOf(vals))
                + "，高于它的即高于本图常态。）";
    }

    private static String refSourceOf(Figure.Drawing d, String label) {
        if ("reference".equals(label)) {
            for (Figure.Primitive p : d.primitives()) {
                if (p instanceof Figure.Text t && "reference-label".equals(t.label())
                        && t.content() != null && !t.content().isBlank()) {
                    return t.content();
                }
            }
            return "参照线（图元自带，图上未写来源文字）";
        }
        return switch (label) {
            case "min-required" -> "判定所需的最小样本量门槛";
            case "range-end", "band-domain" -> "量程上界";
            case "range-start" -> "零点";
            default -> "参照物（图元自带）";
        };
    }

    private String suppressedBlock(Chart c, MetricOutcome m) {
        String reason = c.suppressionReason() == null ? "" : firstToken(c.suppressionReason());
        if (reason.isEmpty() && m != null) {
            reason = m.status() == MetricStatus.ZERO ? String.valueOf(m.zeroCode())
                    : String.valueOf(m.reasonCode());
        }
        long current = m == null ? 0L : m.currentSample();
        long required = m == null ? c.sampleSize().minRequired() : m.sampleSize().minRequired();
        String unitText = m == null ? c.sampleSize().minRequiredUnit() : m.sampleSize().minRequiredUnit();
        String basis = m == null ? c.sampleSize().basis().wireName() : m.sampleSize().basis().wireName();
        StringBuilder sb = new StringBuilder();
        sb.append("<div data-suppressed=\"true\" data-reason-code=\"").append(esc(reason))
                .append("\" data-current=\"").append(current).append("\" data-required=\"")
                .append(required).append("\" data-sample-basis=\"").append(esc(basis))
                .append("\">\n");
        sb.append("<p>本项已抑制：当前观测到的样本量与判断所需的样本量之间还有差距，")
                .append("因此不给数值，也不给结论。</p>\n");
        sb.append("<p>当前 ").append(current).append(" / 所需 ").append(required).append(" ")
                .append(unitText).append("；样本口径 ").append(basis)
                .append("；原因码 ").append(reason).append("。</p>\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    private record FontEmbed(String base64, String sha256, int subsetBytes, int fullBytes,
                             int glyphCount) {
    }

    private FontEmbed embedFont(String coverageText) {
        ReportFontResolver.Resolved resolved = ReportFontResolver.fromBundledResource(coverageText)
                .orElseThrow(() -> new IllegalStateException(
                        "找不到随包报告字体资产：拒绝产出没有内嵌字体的报告"));
        TrueType font = resolved.font();
        Set<Integer> codePoints = new LinkedHashSet<>();
        for (int i = 0; i < coverageText.length(); ) {
            int cp = coverageText.codePointAt(i);
            i += Character.charCount(cp);
            if (!FontPalette.isIgnorable(cp)) {
                codePoints.add(cp);
            }
        }
        boolean[] used = new boolean[font.numGlyphs()];
        used[0] = true;
        StringBuilder missing = new StringBuilder();
        for (int cp : codePoints) {
            int gid = font.glyphFor(cp);
            if (gid == 0) {
                missing.append(String.format(java.util.Locale.ROOT, "U+%04X ", cp));
            } else {
                used[gid] = true;
            }
        }
        if (missing.length() > 0) {
            throw new IllegalStateException("报告文案含随包字体覆盖之外的字符，拒绝产出（缺字不得静默）："
                    + missing);
        }
        byte[] subset;
        try {
            subset = font.subset(used);
        } catch (IOException e) {
            throw new IllegalStateException("字体子集化失败：" + e.getMessage(), e);
        }
        return new FontEmbed(Base64.getEncoder().encodeToString(subset),
                ReportFontResolver.sha256(subset), subset.length, font.rawBytes().length,
                codePoints.size());
    }

    private List<Conclusion> sortedConclusions() {
        List<Conclusion> out = new ArrayList<>(report.conclusions());
        out.sort(Comparator.comparing(Conclusion::conclusionId));
        return out;
    }

    private List<Evidence> sortedEvidence() {
        List<Evidence> out = new ArrayList<>(report.evidence());
        out.sort(Comparator.comparing(Evidence::evidenceId));
        return out;
    }

    private List<Chart> sortedCharts() {
        List<Chart> out = new ArrayList<>(report.charts());
        out.sort(Comparator.comparingInt(c -> Integer.parseInt(c.chartId().substring(1))));
        return out;
    }

    private List<MetricOutcome> sortedSuppressed() {
        List<MetricOutcome> out = new ArrayList<>(report.suppressedMetrics());
        out.sort(Comparator.comparing(MetricOutcome::metricId));
        return out;
    }

    private String evidenceTitle(Evidence e) {
        for (Chart c : sortedCharts()) {
            if (c.evidenceIds().contains(e.evidenceId())) {
            String title = chartTitle(c);
            if ("该指标".equals(title)) {
                return "（本项无关联图表）";
            }
            return "（关联图表：" + title + "）";
            }
        }
        return "统计口径与取值";
    }

    private List<String> chartsOfEvidence(String evidenceId) {
        List<String> out = new ArrayList<>();
        for (Chart c : sortedCharts()) {
            if (c.evidenceIds().contains(evidenceId)) {
                out.add(c.chartId());
            }
        }
        return out;
    }

    private String chartTitle(Chart c) {
        MetricOutcome m = report.metric(c.metricIds().get(0));
        return m == null ? c.titleKey() : featureLabel(m.featureId());
    }

    private static String featureLabel(String featureId) {
        if (featureId == null) {
            return "";
        }
        return switch (featureId) {
            case "M1A_PROGRESS_COMPLETION" -> "进度完成度";
            case "M1B_CONTENT_CONVERSION" -> "内容转化率";
            case "M1C_STALL_RATE" -> "卡点率";
            case "M2A_STALL_UNITS" -> "卡点单元数";
            case "M2B_STALL_SHARE" -> "卡点时长占比";
            case "M2C_HEAVIEST_STALL" -> "停留最久的单元";
            case "M2D_RESOURCE_BLOCK_RATE" -> "资源阻塞率";
            case "M3A_ACTIVE_TOTAL" -> "累计活跃时长";
            case "M3E_FATIGUE_INDEX" -> "疲劳趋势";
            case "M4_BREADTH" -> "内容面广度";
            case "M5A_AXIS_ENTROPY" -> "分布均衡度";
            case "M5B_AXIS_GINI" -> "分布不均衡度";
            case "M5C_DOMINANT_AXIS" -> "主导内容轴";
            case "M6C_DOMINANT_CATEGORY" -> "主导内容类别";
            case "M7A_REPETITION_SHARE" -> "重复行为占比";
            case "M7D_COMPLEXITY_DEBT" -> "复杂度债";
            case "M8A_COMBAT_TIME_SHARE" -> "战斗时长占比";
            case "M8B_NON_LETHAL_RATE" -> "未致命结束占比";
            case "M8E_DAMAGE_EXCHANGE_RATIO" -> "伤害交换比";
            case "M5D_DOMINANT_AXIS_COVERAGE" -> "主导轴覆盖率";
            case "M6A_CATEGORY_COVERAGE" -> "内容类别覆盖";
            case "M6B_CATEGORY_CONCENTRATION" -> "类别集中度";
            case "M6D_STALL_CATEGORY" -> "停滞最多的类别";
            case "M8F_DEATH_CAUSE_SPREAD" -> "死亡原因分散度";
            case "M1_TAIL_ITEM_SPECTRUM" -> "长尾物品谱";
            case "M1_TAIL" -> "长尾物品谱";
            default -> "该指标";
        };
    }

    private String partialJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": \"").append(report.schemaVersion()).append("\",\n");
        sb.append("  \"metricVersion\": \"").append(report.metricVersion()).append("\",\n");
        sb.append("  \"exportId\": \"").append(meta.exportId()).append("\",\n");
        sb.append("  \"reportFormat\": \"").append(FORMAT_ID).append("\",\n");
        sb.append("  \"metricCount\": ").append(report.metrics().size()).append(",\n");
        sb.append("  \"conclusionCount\": ").append(report.conclusions().size()).append(",\n");
        sb.append("  \"evidenceCount\": ").append(report.evidence().size()).append(",\n");
        sb.append("  \"chartCount\": ").append(report.charts().size()).append(",\n");
        sb.append("  \"suppressedMetricCount\": ").append(report.suppressedMetrics().size()).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private List<String> privacyClauses() {
        List<String> out = new ArrayList<>();
        out.add("本地生成、零上传：本报告在本地生成，模组不进行任何网络访问。");
        out.add("自愿导出、手动分享：只有在玩家明确同意后才会生成；分享完全由玩家手动完成。");
        out.add("假名化：玩家标识以每份导出独立的假名出现，两份导出之间不可链接。");
        out.add("坐标量化：位置仅以网格区域键出现，不含精确坐标。");
        out.add("已排除类别：" + String.join("、", meta.exclusions()) + "。");
        out.add("样本不足的表达：一律以「已抑制 + 原因码」给出，不使用 0 或均值代填。");
        out.add("本文件为单一自足文件：样式与字体全部内联，不含任何外部资源引用。");
        out.add("再识别提示：与其它信息结合仍可能推断游玩习惯，请在分享前自行评估。");
        return out;
    }

    static String attributeText(CharSequence html) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(aria-label|title|alt|data-target|data-action)=\"([^\"]*)\"")
                .matcher(html);
        while (m.find()) {
            sb.append(m.group(2)).append('\n');
        }
        return sb.toString();
    }

    private List<String> observedTypes() {
        List<String> names = new ArrayList<>();
        for (com.octant.pipeline.raw.EventType t : com.octant.pipeline.raw.EventType.values()) {
            names.add(t.wireName());
        }
        return names;
    }

    private static String stat(Evidence e, String key) {
        Double v = e.statistic().get(key);
        return v == null ? "（未给出）" : num(v);
    }

    private static int countPositive(MetricOutcome m) {
        if (m == null || m.series() == null) {
            return 0;
        }
        int n = 0;
        for (Double v : m.series().values()) {
            if (v != null && v > 0.0d) {
                n++;
            }
        }
        return n;
    }

    private static String named(MetricOutcome m) {
        return m != null && m.namedKey() != null ? m.namedKey() : "未命名";
    }

    private static double number(MetricOutcome m) {
        return m != null && m.value() instanceof Number n ? n.doubleValue() : 0.0d;
    }

    static String num(double v) {
        double r = Math.round(v * 1000.0d) / 1000.0d;
        if (r == Math.floor(r) && Math.abs(r) < 1.0e15d) {
            return String.valueOf((long) r);
        }
        return String.format(java.util.Locale.ROOT, "%.3f", r);
    }

    static String pctOf(double percentValue) {
        return String.format(java.util.Locale.ROOT, "%.1f", percentValue) + "%";
    }

    static String ratioText(double v) {
        return pctOf(v > 1.0d ? v : v * 100.0d);
    }

    static String confidenceLabel(String wire) {
        return switch (wire) {
            case "certain" -> "高（严格计数直接聚合）";
            case "estimated" -> "中（含显式估计模型）";
            case "heuristic" -> "低（含代理量或推断）";
            case "suppressed" -> "已抑制";
            default -> wire;
        };
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "checkpoint" -> "关卡单元";
            case "recipe" -> "配方";
            case "dimension" -> "维度";
            case "category" -> "内容类别";
            case "mechanic" -> "机制";
            default -> kind;
        };
    }

    private static String unitOf(String unit) {
        return unit == null || unit.isBlank() || "-".equals(unit.trim()) ? "无单位" : unit.trim();
    }

    private String unitOfMetric(String metricId) {
        com.octant.pipeline.analysis.MetricOutcome m = report.metric(metricId);
        return m == null ? "无单位" : unitOf(m.unit());
    }

    private static String uniqueNameOf(String metricId, String resolvedName) {
        if (!"该指标".equals(resolvedName)) {
            return resolvedName;
        }
        int us = metricId.indexOf('_');
        if (us < 0) {
            return "未登记指标";
        }
        String tail = metricId.substring(us + 1).replace('_', ' ').trim();
        return tail.isEmpty() ? "未登记指标" : "未登记指标 " + tail;
    }

    private static String join(List<String> items) {
        return items == null || items.isEmpty() ? "无" : String.join(" ", items);
    }

    private static String firstToken(String s) {
        return s == null ? "" : s.trim().split("[ (]")[0];
    }

    private static void kv(StringBuilder sb, String key, String value) {
        sb.append("<dt>").append(esc(key)).append("</dt><dd>").append(esc(value)).append("</dd>\n");
    }

    private static void row(StringBuilder sb, String label, String value) {
        sb.append("<tr><th scope=\"row\">").append(esc(label)).append("</th><td>")
                .append(esc(value)).append("</td></tr>\n");
    }

    private static void row3(StringBuilder sb, String a, String b, String c) {
        sb.append("<tr>");
        cell(sb, a);
        cell(sb, b);
        cell(sb, c);
        sb.append("</tr>\n");
    }

    private static void cell(StringBuilder sb, String v) {
        sb.append("<td>").append(esc(v)).append("</td>");
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
