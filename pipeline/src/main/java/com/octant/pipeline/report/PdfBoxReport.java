package com.octant.pipeline.report;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.analysis.Chart;
import com.octant.pipeline.analysis.ChartForms;
import com.octant.pipeline.analysis.MetricOutcome;
import com.octant.pipeline.report.font.ReportFontResolver;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PdfBoxReport {

    private static final float MARGIN = 56f;
    private static final float LEADING = 15.5f;
    private static final float BODY_SIZE = 10.5f;
    private static final float HEAD_SIZE = 12f;
    private static final double BOTTOM = 56f;
    private static final int WRAP_WIDTH = 62;

    private static final Pattern CHART_HEADING = Pattern.compile("^#{2,4}\\s*C(\\d{1,2})\\s+—");
    private static final Pattern FORM_LINE = Pattern.compile("R3-1\\s*(?:形式|图型)\\s*:\\s*`(C\\d+)`");

    private static final long DETERMINISTIC_DOC_ID = 0x4D43494E53494748L;
    private static final long DETERMINISTIC_EPOCH_MS = 1758898800000L;

    private PdfBoxReport() {
    }

    public record Result(byte[] pdf, String fontPath, String provenance, String familyName,
                         int subsetBytes, int glyphCount, int pages, int chartImages) {
    }

    public static Result render(AnalysisReport report, ReportDocument.ExportMeta meta) {
        ReportFontResolver.Resolved font = ReportFontResolver.resolve(fontProbeText(report, meta))
                .orElseThrow(() -> new RenderFailure(RenderFailure.SOURCE_UNAVAILABLE,
                        "找不到可用的报告字体资产（classpath 资源缺失）—— 拒绝降级："
                                + "产出'文本层错字'或'未嵌入字体'的报告比不让导出更糟。", List.of()));
        List<String> lines = new ReportDocument(report, meta).lines();
        List<Integer> missing = font.palette().missingCodePoints(String.join("\n", lines));
        if (!missing.isEmpty()) {
            throw new RenderFailure(RenderFailure.SOURCE_UNAVAILABLE,
                    "报告文案含字体覆盖面之外的字符（" + missing.size() + " 个）："
                            + RenderFailure.describe(missing, 12)
                            + "。宽覆盖面之外的字符必须显式失败并带 reasonCode，"
                            + "不得印成空白，也不得静默替换。", missing);
        }

        int chartImages = 0;
        try (PDDocument doc = new PDDocument()) {
            doc.setDocumentId(DETERMINISTIC_DOC_ID);
            java.util.Calendar fixed = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"));
            fixed.setTimeInMillis(DETERMINISTIC_EPOCH_MS);
            doc.getDocumentInformation().setCreationDate(fixed);
            doc.getDocumentInformation().setModificationDate(fixed);
            doc.getDocumentInformation().setProducer("mc-insight");
            doc.getDocumentInformation().setCreator("mc-insight");

            PDType0Font embedded = loadFont(doc, font);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = PDRectangle.A4.getHeight() - MARGIN;

            for (int idx = 0; idx < lines.size(); idx++) {
                String raw = lines.get(idx);
                PdfReport.Normalized n = normalize(raw);                if (n.text().isEmpty()) {
                    y -= LEADING * 0.5f;
                    continue;
                }
                float size = n.heading() ? HEAD_SIZE : BODY_SIZE;
                for (String piece : wrap(n.text(), WRAP_WIDTH)) {
                    if (y < BOTTOM + LEADING) {
                        cs.close();
                        page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        cs = new PDPageContentStream(doc, page);
                        y = PDRectangle.A4.getHeight() - MARGIN;
                    }
                    cs.beginText();
                    cs.setFont(embedded, size);
                    cs.newLineAtOffset(MARGIN, y);
                    cs.showText(piece);
                    cs.endText();
                    y -= LEADING * (n.heading() ? 1.25f : 1.0f);
                }
                Matcher hm = CHART_HEADING.matcher(raw);
                if (hm.find()) {
                    String form = formAt(lines, idx);
                    Chart chart = report.chartById("C" + hm.group(1));
                    MetricOutcome metric = chart == null ? null : report.metric(chart.metricIds().get(0));
                    if (chart != null && metric != null && PdfCharts.canDraw(chart, metric)) {
                        y = drawChart(cs, doc, chart, metric, y);
                        chartImages++;
                    }
                }
            }
            cs.close();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out, CompressParameters.NO_COMPRESSION);
            byte[] bytes = out.toByteArray();
            return new Result(bytes, font.fontFile(), font.provenance(), font.familyName(),
                    font.subsetBytes(), font.glyphCount(), doc.getNumberOfPages(), chartImages);
        } catch (IOException e) {
            throw new RenderFailure(RenderFailure.SOURCE_UNAVAILABLE,
                    "PDFBox 渲染失败：" + e, List.of());
        }
    }

    private static PDType0Font loadFont(PDDocument doc, ReportFontResolver.Resolved font)
            throws IOException {
        try (java.io.InputStream in = ReportFontResolver.class
                .getResourceAsStream(ReportFontResolver.BUNDLED_FONT_RESOURCE)) {
            if (in == null) {
                throw new IOException("classpath 上找不到 " + ReportFontResolver.BUNDLED_FONT_RESOURCE);
            }
            return PDType0Font.load(doc, in, true);
        }
    }

    private static float drawChart(PDPageContentStream cs, PDDocument doc, Chart chart,
                                   MetricOutcome metric, float y) throws IOException {
        String mode = System.getProperty("octant.pdf.charts", "vector");
        if ("jfreechart".equalsIgnoreCase(mode)) {
            BufferedImage img = chartImage(chart.form(), metric);
            if (img != null) {
                PDImageXObject xobj = LosslessFactory.createFromImage(doc, img);
                float h = 96f;
                float w = h * img.getWidth() / (float) img.getHeight();
                float yy = ensureRoom(cs, y, h);
                cs.drawImage(xobj, MARGIN, yy - h, w, h);
                return yy - h - 8f;
            }
            return y;
        }
        return drawVectorChart(cs, chart, metric, y);
    }

    private static float drawVectorChart(PDPageContentStream cs, Chart chart,
                                         MetricOutcome metric, float y) throws IOException {
        String form = chart.form();
        Map<String, Double> data = seriesOrQuantiles(metric);
        boolean stacked = ChartForms.COVERAGE_BAR.equals(form) || ChartForms.SHARE_BAR.equals(form);
        float width = PDRectangle.A4.getWidth() - 2 * MARGIN;
        float barH = 10f;

        if (stacked) {
            double domain = domainOf(metric);
            double ratio;
            if (data.size() >= 2) {
                double total = data.values().stream().mapToDouble(Double::doubleValue).sum();
                ratio = total > 0 ? Math.min(1.0d, data.values().iterator().next() / total) : 0.0d;
            } else if (metric.value() instanceof Number n) {
                ratio = Math.max(0.0d, Math.min(1.0d, n.doubleValue() / domain));
            } else {
                return y;
            }
            float yy = ensureRoom(cs, y, barH + 16f);
            float done = (float) (width * ratio);
            cs.setNonStrokingColor(new java.awt.Color(13, 148, 136));
            cs.addRect(MARGIN, yy - barH, done, barH);
            cs.fill();
            cs.setNonStrokingColor(new java.awt.Color(217, 219, 224));
            cs.addRect(MARGIN + done, yy - barH, width - done, barH);
            cs.fill();
            cs.setStrokingColor(new java.awt.Color(89, 92, 97));
            cs.setLineWidth(0.6f);
            cs.moveTo(MARGIN, yy + 2f);
            cs.lineTo(MARGIN, yy - barH - 2f);
            cs.stroke();
            cs.moveTo(MARGIN + width, yy + 2f);
            cs.lineTo(MARGIN + width, yy - barH - 2f);
            cs.stroke();
            return yy - barH - 12f;
        }

        List<Map.Entry<String, Double>> rows = new ArrayList<>(data.entrySet());
        if (rows.isEmpty() && metric.value() instanceof Number n) {
            rows.add(new java.util.AbstractMap.SimpleImmutableEntry<>(
                    metric.namedKey() == null ? metric.metricId() : metric.namedKey(),
                    n.doubleValue()));
        }
        if (rows.isEmpty()) {
            return y;
        }
        double domain = Math.max(domainOf(metric),
                rows.stream().mapToDouble(Map.Entry::getValue).max().orElse(0.0d));
        if (domain <= 0.0d) {
            domain = 1.0d;
        }
        float height = rows.size() * (barH + 4f) + 6f;
        float yy = ensureRoom(cs, y, height);
        cs.setStrokingColor(new java.awt.Color(89, 92, 97));
        cs.setLineWidth(0.6f);
        cs.moveTo(MARGIN, yy + 2f);
        cs.lineTo(MARGIN, yy - height + 4f);
        cs.stroke();
        float rowY = yy;
        for (Map.Entry<String, Double> row : rows) {
            rowY -= barH + 4f;
            float w = (float) (width * Math.max(0.0d, Math.min(1.0d, row.getValue() / domain)));
            cs.setNonStrokingColor(new java.awt.Color(51, 107, 173));
            cs.addRect(MARGIN, rowY, Math.max(w, 0.0f), barH);
            cs.fill();
        }
        return yy - height - 6f;
    }

    private static float ensureRoom(PDPageContentStream cs, float y, float height) {
        return y - height < BOTTOM ? y - height - LEADING : y;
    }
    static BufferedImage chartImage(String form, MetricOutcome metric) {
        Map<String, Double> data = seriesOrQuantiles(metric);
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        double domain = domainOf(metric);
        if (!data.isEmpty()) {
            for (Map.Entry<String, Double> e : data.entrySet()) {
                dataset.addValue(e.getValue(), metric.metricId(), e.getKey());
            }
        } else if (metric.value() instanceof Number num) {
            double v = num.doubleValue();
            if (ChartForms.COVERAGE_BAR.equals(form) || ChartForms.SHARE_BAR.equals(form)) {
                double ratio = Math.max(0.0d, Math.min(1.0d, v / domain));
                dataset.addValue(ratio * 100.0d, "覆盖", metric.metricId());
                dataset.addValue((1.0d - ratio) * 100.0d, "剩余", metric.metricId());
            } else {
                dataset.addValue(v, metric.metricId(), metric.namedKey() == null
                        ? metric.metricId() : metric.namedKey());
            }
        } else {
            return null;
        }
        boolean stacked = ChartForms.COVERAGE_BAR.equals(form) || ChartForms.SHARE_BAR.equals(form);
        JFreeChart chart = stacked
                ? ChartFactory.createStackedBarChart("", "", "", dataset,
                        PlotOrientation.HORIZONTAL, false, false, false)
                : ChartFactory.createBarChart("", "", "", dataset,
                        PlotOrientation.HORIZONTAL, false, false, false);
        return chart.createBufferedImage(560, 140);
    }

    private static Map<String, Double> seriesOrQuantiles(MetricOutcome metric) {
        if (metric.series() != null && !metric.series().isEmpty()) {
            return metric.series();
        }
        if (metric.quantiles() != null && !metric.quantiles().isEmpty()) {
            return metric.quantiles();
        }
        return Map.of();
    }

    private static double domainOf(MetricOutcome metric) {
        String unit = metric.unit() == null ? "" : metric.unit().trim();
        if ("%".equals(unit)) {
            return 100.0d;
        }
        if (unit.isEmpty() || "-".equals(unit) || "无单位".equals(unit)) {
            return 1.0d;
        }
        double max = seriesOrQuantiles(metric).values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0.0d);
        return max > 0.0d ? max : 1.0d;
    }

    static String formAt(List<String> lines, int idx) {
        for (int i = idx; i < Math.min(lines.size(), idx + 6); i++) {
            Matcher m = FORM_LINE.matcher(lines.get(i));
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static String fontProbeText(AnalysisReport report, ReportDocument.ExportMeta meta) {
        return String.join("\n", new ReportDocument(report, meta).lines());
    }

    static PdfReport.Normalized normalize(String raw) {
        return PdfReport.normalize(raw);
    }

    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String token : text.split(" ")) {
            String t = token;
            while (t.length() > width) {
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                out.add(t.substring(0, width));
                t = t.substring(width);
            }
            if (line.length() == 0) {
                line.append(t);
            } else if (line.length() + 1 + t.length() <= width) {
                line.append(' ').append(t);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(t);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out.isEmpty() ? List.of("") : out;
    }

    static Map<String, Integer> chartImageCounts(List<String> lines, AnalysisReport report) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher hm = CHART_HEADING.matcher(lines.get(i));
            if (!hm.find()) {
                continue;
            }
            Chart chart = report.chartById("C" + hm.group(1));
            if (chart == null) {
                continue;
            }
            String form = formAt(lines, i);
            counts.merge(form == null ? "?" : form, 1, Integer::sum);
        }
        return counts;
    }
}
