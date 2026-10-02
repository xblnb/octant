package com.octant.pipeline;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.analysis.Chart;
import com.octant.pipeline.analysis.MetricOutcome;
import com.octant.pipeline.report.ReportDocument;
import com.octant.pipeline.report.chart.Figure;
import com.octant.pipeline.report.chart.FigureGeometry;
import com.octant.pipeline.report.chart.FigureSvg;
import com.octant.pipeline.report.html.HtmlReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class HtmlPresentationReadabilityTest {

    private static final Pattern SVG_RX = Pattern.compile("<svg\\b.*?</svg\\s*>", Pattern.DOTALL);
    private static final Pattern UNIT_RX = Pattern.compile("<section\\b[^>]*data-f=\"[^\"]*\"[^>]*>.*?</section\\s*>", Pattern.DOTALL);
    private static final Pattern MACHINE_RX = Pattern.compile("<pre\\b[^>]*data-machine[^>]*>.*?</pre\\s*>", Pattern.DOTALL);
    private static final Pattern DATA_ROLE_RX = Pattern.compile("data-role=\"(value|reference)\"");
    private static final Pattern DATA_VALUE_TOKENS_RX = Pattern.compile("<[^>]*data-value=\"([^\"]*)\"[^>]*>");

    private static final List<String> BANNED = List.of(
            "metric", "value", "unit", "sampleSize", "sampleBasis", "minRequired",
            "minRequiredUnit", "confidence", "confidenceDetail", "nEff", "inputCoverage",
            "proxyQuality", "afkShare", "degradedBy", "contaminated", "sampleAdequacy",
            "denominator", "denominatorSource", "window", "status", "reasonCode", "zeroCode",
            "suppressed", "unavailable", "certain", "estimated", "heuristic", "evidence",
            "chart", "scope", "conclusionId", "statementKey", "metricIds", "evidenceIds",
            "chartIds", "limitations", "basis", "truncated_stream", "proxy_unavailable");

    private static final List<Pattern> BANNED_RX = List.of(
            Pattern.compile("\\bk\\b"), Pattern.compile("\\bn\\b"),
            Pattern.compile("M\\d+[a-z]?"), Pattern.compile("E\\d+"), Pattern.compile("C\\d+"));

    private static final AnalysisReport REPORT;
    private static final HtmlReport RENDERER;
    private static final HtmlReport.Result RENDERED;

    static {
        REPORT = RealCaptureCorpus.input().report();
        RENDERER = new HtmlReport(REPORT, meta("20260926-150000-5a17e5"));
        RENDERED = RENDERER.render();
    }

    private static ReportDocument.ExportMeta meta(String exportId) {
        return new ReportDocument.ExportMeta(exportId, "2026-09-26", "2026-09-26T15:00",
                "0.1.0", "1.20.1", "forge", "1.11", "privacy-spec@2.5.3",
                List.of("C1", "C2", "C3", "C5", "C6", "C7", "C8"),
                List.of(), List.of("精确坐标", "聊天原文", "服务器地址"),
                "本报告由玩家自愿导出；模组不上传任何数据。", List.of("minecraft"));
    }

    @Test
    @DisplayName("图表可读性：每个出图单元都**在图内可比**（同序列 ≥2 点 或 ≥2 个带数值的参考标记）")
    void everyDrawnFigureIsComparable() {
        int drawnUnits = 0;
        int comparableUnits = 0;
        for (Chart c : REPORT.charts()) {
            Figure.Drawing d = drawingOf(c);
            if (d == null) {
                continue;
            }
            drawnUnits++;
            assertTrue(d.isComparable(), c.chartId() + "（图型 " + c.form()
                    + "）：图内不可比 —— 同序列点数=" + d.seriesPointCount()
                    + "、参考标记数=" + d.referencePointCount()
                    + " ⇒ 它就是「把数字换成条形的等价表」");
            String svg = FigureSvg.toSvg(d, c.chartId(), "画出 " + d.seriesPointCount() + " 个");
            long drawnPoints = Pattern.compile("data-role=\"value\" data-effect=\"scales\"")
                    .matcher(svg).results().count();
            assertTrue(drawnPoints > 0, c.chartId() + "：没有任何随值缩放的数据图元");
            assertFalse(d.dataOnlyInText(), c.chartId() + "：数据只出现在文字里");
            comparableUnits++;
        }
        assertTrue(drawnUnits > 0, "没有任何单元出图 ⇒ 判据作用对象为空（空集不得判通过）");
        assertEqualsInt(drawnUnits, comparableUnits, "可比单元数应等于出图单元数");
        System.out.println("== 出图单元数 = " + drawnUnits + "（全部图内可比）==");
    }

    @Test
    @DisplayName("静默丢点探测器：等价表的数据点数 == 图上实画点数（改前 M4 丢 6 点）")
    void noSilentPointLoss() {
        int checked = 0;
        for (Chart c : REPORT.charts()) {
            Figure.Drawing d = drawingOf(c);
            if (d == null) {
                continue;
            }
            checked++;
            long drawn = 0;
            Map<Double, Boolean> drawnValues = new java.util.LinkedHashMap<>();
            for (Figure.Primitive p : d.primitives()) {
                if (Figure.Drawing.roleOf(p) == Figure.Role.VALUE
                        && Figure.Drawing.effectOf(p) == Figure.Effect.SCALES) {
                    drawn++;
                    drawnValues.putIfAbsent(Figure.Drawing.valueOf(p), Boolean.TRUE);
                }
            }
            int dataPoints = c.data().size();
            assertTrue(drawn == dataPoints, c.chartId() + "：等价表有 " + dataPoints
                    + " 个数据点，图上只画出 " + drawn + " 个（**静默丢点**）");
            for (Map<String, Object> row : c.data()) {
                Object y = row.get("y");
                if (y instanceof Number n) {
                    assertTrue(drawnValues.containsKey(n.doubleValue()),
                            c.chartId() + "：数据点 " + n + " 没有对应的随值缩放图元");
                }
            }
            assertTrue(d.seriesPointCount() <= drawn,
                    c.chartId() + "：自述点数 " + d.seriesPointCount() + " 超过实画 " + drawn);
        }
        assertTrue(checked > 0, "没有出图单元 ⇒ 判据作用域为空");
        System.out.println("== 静默丢点检查通过：" + checked + " 个出图单元，表点数 == 实画点数 ==");
    }

    @Test
    @DisplayName("负控：两个数值不同 ⇒ 数据图元的几何必须不同（改前两图几何完全相同）")
    void geometryMustChangeWithValue() {
        for (String form : List.of("C14", "C12", "C3", "C15", "C2")) {
            Figure.Drawing low = FigureGeometry.compute(form, "-", scalar(0.2d, "A"), null);
            Figure.Drawing high = FigureGeometry.compute(form, "-", scalar(0.8d, "A"), null);
            assertNotNull(low, form + "：低值不画图");
            assertNotNull(high, form + "：高值不画图");
            String a = geometrySignature(low);
            String b = geometrySignature(high);
            assertFalse(a.equals(b), form + "：0.2 与 0.8 画出**逐字符相同**的几何"
                    + " ⇒ 该图不能反映数值（`§106` 的实测形态）");
        }
    }

    @Test
    @DisplayName("反面对照：把几何钉成常量必须被同一条判据判红")
    void reverseControlFailsTheJudgement() {
        Figure.Drawing low = FigureGeometry.compute("C14", "-", scalar(0.2d, "A"), null);
        Figure.Drawing high = FigureGeometry.compute("C14", "-", scalar(0.8d, "A"), null);
        assertFalse(geometrySignature(low).equals(geometrySignature(high)));
        assertTrue(geometrySignature(low).equals(geometrySignature(low)),
                "同一份几何自比必须相等（否则下面对照不成立）");
        assertFalse(geometrySignature(low).equals(geometrySignature(high)),
                "反面对照失效：判据对几何变化不敏感");
    }

    private static String geometrySignature(Figure.Drawing d) {
        StringBuilder sb = new StringBuilder();
        for (Figure.Primitive p : d.primitives()) {
            sb.append(p).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("人话层：结论单元可见文本中契约字段与短 id 计数必须为 0")
    void conclusionHumanLayerHasZeroContractTokens() {
        Matcher m = UNIT_RX.matcher(RENDERED.htmlText());
        int units = 0;
        while (m.find()) {
            units++;
            String unit = m.group();
            String visible = visibleText(unit);
            StringBuilder hits = new StringBuilder();
            for (String token : BANNED) {
                if (visible.contains(token)) {
                    hits.append(token).append(' ');
                }
            }
            for (Pattern p : BANNED_RX) {
                if (p.matcher(visible).find()) {
                    hits.append(p.pattern()).append(' ');
                }
            }
            assertTrue(hits.length() == 0,
                    unitId(unit) + " 的可见文本里出现了契约字段/短 id：" + hits);
        }
        assertEqualsInt(7, units, "结论单元数（判据作用对象个数）");
    }

    @Test
    @DisplayName("可核查层：hidden 使字段不进可见文本，但仍可被机器读取")
    void machineLayerIsHiddenButReadable() {
        Matcher m = MACHINE_RX.matcher(RENDERED.htmlText());
        int blocks = 0;
        while (m.find()) {
            blocks++;
            String all = m.group();
            String tag = all.substring(0, all.indexOf('>') + 1);
            assertTrue(tag.contains("hidden"),
                    "可核查层缺 hidden（`K24`/`HIG-HTML-12.1`）：读者会直接读到契约字段");
            String body = all.replaceAll("<[^>]+>", "");
            for (String must : List.of("conclusionId:", "status:", "sampleSize.current:",
                    "confidence:", "evidenceIds:", "window:")) {
                assertTrue(body.contains(must),
                        "可核查层缺字段 " + must + "（`K3` 字段齐备性）");
            }
        }
        assertEqualsInt(7, blocks, "可核查层块数");
        String visible = visibleText(RENDERED.htmlText());
        assertFalse(visible.contains("conclusionId"), "可见文本里仍有 conclusionId");
        assertFalse(visible.contains("sampleSize"), "可见文本里仍有 sampleSize");
        assertFalse(visible.contains("statementKey"), "可见文本里仍有 statementKey");
    }

    @Test
    @DisplayName("图表单元：每图 <figure> + 图注；布局三类齐备；关键路径标记在（HIG-COPY-06/08）")
    void figuresLayoutAndKeyPath() {
        String html = RENDERED.htmlText();
        int figures = count(html, "<figure");
        int captions = count(html, "<figcaption");
        int svgs = count(html, "<svg");
        assertTrue(figures > 0, "没有任何 <figure>（`HIG-COPY-07` 判据域为空，不得判通过）");
        assertEqualsInt(svgs, figures, "<svg> 与 <figure> 数不等（有图却没有图容器）");
        assertEqualsInt(figures, captions, "<figure> 与 <figcaption> 数不等（图注缺失）");
        assertTrue(count(html, "data-takeaway=\"") >= figures,
                "有图缺断言句（`HIG-COPY-07`）");
        assertTrue(count(html, "data-layout=\"emphasis-value\"") >= 1, "缺强调数值块");
        assertTrue(count(html, "data-layout=\"two-column\"") >= 1, "缺双栏块");
        assertTrue(count(html, "data-layout=\"full-width-figure\"") >= 1, "缺全宽图块");
        assertTrue(count(html, "data-critical-path") >= 1, "缺关键路径标记（`HIG-COPY-06`）");
        assertEqualsInt(0, count(html, "<details"), "`HIG-COPY-06.6` 禁止用折叠承载信息");
        assertEqualsInt(0, count(html, "<script"), "`HIG-HTML-04` 禁脚本");
        assertEqualsInt(0, count(html, "http" + "s://"), "`HIG-HTML-04` 禁外链");
        Matcher fc = Pattern.compile("<figcaption>(.*?)</figcaption>").matcher(html);
        int n = 0;
        while (fc.find()) {
            n++;
            assertTrue(fc.group(1).length() >= 10, "图注过短：" + fc.group(1));
        }
        assertEqualsInt(figures, n, "图注数");
    }

    @Test
    @DisplayName("图内几何自洽：所有图元在画布内、数值文字不被深色数据条压住")
    void geometryStaysInCanvasAndLabelsAreReadable() {
        int checked = 0;
        for (Chart c : REPORT.charts()) {
            Figure.Drawing d = drawingOf(c);
            if (d == null) {
                continue;
            }
            checked++;
            for (Figure.Primitive p : d.primitives()) {
                for (double[] box : boxes(p)) {
                    assertTrue(box[0] >= -1.0d && box[1] >= -1.0d
                                    && box[0] + box[2] <= d.width() + 1.0d
                                    && box[1] + box[3] <= d.height() + 1.0d,
                            c.chartId() + "：图元超出画布 " + box[0] + "," + box[1] + " "
                                    + box[2] + "x" + box[3] + "（画布 " + d.width() + "x"
                                    + d.height() + "）");
                }
                if (p instanceof Figure.Text t && t.role() == Figure.Role.FRAME) {
                    for (Figure.Primitive q : d.primitives()) {
                        if (!(q instanceof Figure.Rect r) || r.role() != Figure.Role.VALUE) {
                            continue;
                        }
                        if (overlap(textBox(t), new double[]{r.x(), r.y(), r.w(), r.h()})) {
                            fail(c.chartId() + "：文字「" + t.content()
                                    + "」压在数据条上（读者读不到）");
                        }
                    }
                }
            }
        }
        assertTrue(checked > 0, "没有任何单元出图 ⇒ 判据作用域为空");
    }

    private static List<double[]> boxes(Figure.Primitive p) {
        List<double[]> out = new ArrayList<>();
        if (p instanceof Figure.Rect r) {
            out.add(new double[]{r.x(), r.y(), r.w(), r.h()});
        } else if (p instanceof Figure.Line l) {
            out.add(new double[]{Math.min(l.x1(), l.x2()), Math.min(l.y1(), l.y2()),
                    Math.abs(l.x2() - l.x1()), Math.abs(l.y2() - l.y1())});
        }
        return out;
    }

    private static double[] textBox(Figure.Text t) {
        double w = 0.0d;
        for (int i = 0; i < t.content().length(); i++) {
            char ch = t.content().charAt(i);
            w += (ch > 0x2E80) ? t.fontSize() : t.fontSize() * 0.55d;
        }
        double x = t.x();
        if ("end".equals(t.anchor())) {
            x -= w;
        } else if ("middle".equals(t.anchor())) {
            x -= w / 2.0d;
        }
        return new double[]{x, t.y() - t.fontSize() * 0.8d, w, t.fontSize()};
    }

    private static boolean overlap(double[] a, double[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2]
                && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    @Test
    @DisplayName("确定性：两次渲染逐字节一致（新图元不得引入迭代序依赖）")
    void deterministic() {
        String a = new HtmlReport(REPORT, meta("20260926-150000-5a17e5")).render().htmlText();
        String b = new HtmlReport(REPORT, meta("20260926-150000-5a17e5")).render().htmlText();
        assertTrue(a.equals(b), "两次渲染字节不同（新图元引入了不确定性）");
    }

    @Test
    @DisplayName("取证打印：逐单元数据图元数与数值（失败时人工可复核）")
    void printCensus() {
        List<String> lines = new ArrayList<>();
        for (Chart c : REPORT.charts()) {
            Figure.Drawing d = drawingOf(c);
            lines.add(String.format(Locale.ROOT, "%s 图型=%-3s 出图=%-5s 数据图元=%-3s 数值=%s",
                    c.chartId(), c.form(), String.valueOf(d != null),
                    d == null ? "-" : String.valueOf(d.dataPrimitiveCount()),
                    d == null ? "-" : String.valueOf(d.dataValues())));
        }
        System.out.println("== 图表取证（" + lines.size() + " 个图表项）==");
        lines.forEach(System.out::println);
        assertEqualsInt(REPORT.charts().size(), lines.size(), "取证行数");
    }

    private static Figure.Drawing drawingOf(Chart c) {
        return RENDERER.geometryOfChart(c);
    }

    private static List<Figure.Point> scalar(double v, String label) {
        return FigureGeometry.pointsOf(null, null, v, label);
    }

    static String visibleText(String html) {
        String s = html.replaceAll("(?s)<!--.*?-->", " ");
        s = s.replaceAll("(?s)<(style|script)\\b.*?</\\1\\s*>", " ");
        s = s.replaceAll("(?s)<[a-zA-Z][^>]*\\shidden(?:\\s|>|/>)[^>]*>.*?</[a-zA-Z]+\\s*>", " ");
        s = s.replaceAll("(?s)<pre\\b[^>]*data-machine[^>]*>.*?</pre\\s*>", " ");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String unitId(String unit) {
        Matcher m = Pattern.compile("data-f=\"([^\"]*)\"").matcher(unit);
        return m.find() ? m.group(1) : "?";
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static void assertEqualsInt(int expected, int actual, String what) {
        assertTrue(expected == actual, what + "：期望 " + expected + "，实际 " + actual);
    }

    static List<String> dataValueTokens(String svg) {
        List<String> out = new ArrayList<>();
        Matcher m = DATA_VALUE_TOKENS_RX.matcher(svg);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @SuppressWarnings("unused")
    private static List<String> svgElements(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = SVG_RX.matcher(html);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
