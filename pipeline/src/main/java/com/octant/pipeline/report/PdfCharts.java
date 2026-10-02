package com.octant.pipeline.report;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.analysis.Chart;
import com.octant.pipeline.analysis.ChartForms;
import com.octant.pipeline.analysis.MetricOutcome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PdfCharts {

    private static final double[] SERIES_S1 = {0.20d, 0.42d, 0.68d};
    private static final double[] SERIES_S2 = {0.13d, 0.58d, 0.60d};
    private static final double[] SERIES_S3 = {0.78d, 0.52d, 0.20d};
    private static final double[] NEUTRAL = {0.85d, 0.86d, 0.88d};
    private static final double[] REF = {0.35d, 0.36d, 0.38d};

    private static final double BAR_HEIGHT = 9.0d;
    private static final double BAR_GAP = 4.0d;
    private static final double LABEL_WIDTH = 210.0d;

    static final java.util.Set<String> DRAWABLE_FORMS = java.util.Set.of(
            ChartForms.COVERAGE_BAR, ChartForms.SHARE_BAR, ChartForms.HORIZONTAL_BAR,
            ChartForms.BULLET_BAR, ChartForms.PROFILE_PARALLEL);

    static boolean draws(String form) {
        return DRAWABLE_FORMS.contains(form);
    }

    static boolean canDraw(Chart chart, MetricOutcome metric) {
        if (chart == null || metric == null || chart.suppressed()) {
            return false;
        }
        String form = chart.form();
        if (!DRAWABLE_FORMS.contains(form)) {
            return false;
        }
        if (ChartForms.COVERAGE_BAR.equals(form)) {
            return ratioOf(metric) != null;
        }
        Map<String, Double> data = seriesOrQuantiles(metric);
        if (ChartForms.SHARE_BAR.equals(form)) {
            double total = data.values().stream().mapToDouble(Double::doubleValue).sum();
            return data.size() >= 2 && total > 0.0d;
        }
        if (!data.isEmpty()) {
            return data.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0d) >= 0.0d;
        }
        return metric.value() instanceof Number n && n.doubleValue() >= 0.0d;
    }

    private static double domainOf(MetricOutcome metric, double fallback) {
        String unit = metric.unit() == null ? "" : metric.unit().trim();
        if ("%".equals(unit)) {
            return 100.0d;
        }
        if (unit.isEmpty() || "-".equals(unit) || "无单位".equals(unit)) {
            return 1.0d;
        }
        Map<String, Double> data = seriesOrQuantiles(metric);
        double max = data.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0d);
        return max > 0.0d ? max : fallback;
    }

    private static double maxValue(Map<String, Double> data) {
        return data.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0d);
    }

    private static final Pattern CHART_HEADING = Pattern.compile("^#{2,4}\\s*C(\\d{1,2})(?![0-9])");

    private PdfCharts() {
    }

    static void render(PdfWriter pdf, String rawLine, AnalysisReport report) {
        Matcher m = CHART_HEADING.matcher(rawLine);
        if (!m.find()) {
            return;
        }
        String chartId = "C" + m.group(1);
        Chart chart = report.chartById(chartId);
        if (chart == null || chart.suppressed()) {
            return;
        }
        MetricOutcome metric = report.metric(chart.metricIds().get(0));
        if (metric == null) {
            return;
        }
        double left = PdfWriter.MARGIN_LEFT;
        double width = PdfWriter.PAGE_WIDTH - 2 * PdfWriter.MARGIN_LEFT;
        int before = pdf.shapeCount();
        switch (chart.form()) {
            case ChartForms.KPI_UNIT -> {
            }
            case ChartForms.COVERAGE_BAR -> renderCoverage(pdf, chart, metric, left, width);
            case ChartForms.SHARE_BAR -> renderShareBar(pdf, chart, metric, left, width);
            case ChartForms.HORIZONTAL_BAR -> renderHorizontalBars(pdf, chart, metric, left, width);
            case ChartForms.BULLET_BAR -> renderBulletArray(pdf, chart, metric, left, width);
            case ChartForms.PROFILE_PARALLEL -> renderProfileBars(pdf, chart, metric, left, width);
            default -> {
            }
        }
        int drew = pdf.shapeCount() - before;
        boolean noDrawBySpec = ChartForms.KPI_UNIT.equals(chart.form())
                || ChartForms.SUPPRESSED_PLACEHOLDER.equals(chart.form());
        if (drew == 0 && !noDrawBySpec && canDraw(chart, metric)) {
            throw new RenderFailure(RenderFailure.SOURCE_UNAVAILABLE,
                    "图表项 " + chart.chartId() + "（" + chart.metricIds().get(0) + "，形式 "
                            + chart.form() + "）声明需要图形，但渲染器没有产出任何图形算子；"
                            + "按验收不变式，这种项必须画出来或从产物撤下，不得留只有标题的项。",
                    List.of());
        }
    }

    private static void renderCoverage(PdfWriter pdf, Chart chart, MetricOutcome metric,
                                       double left, double width) {
        Double ratio = ratioOf(metric);
        if (ratio == null) {
            return;
        }
        double top = pdf.reserve(16.0d);
        double barWidth = width - LABEL_WIDTH * 0.45d;
        pdf.coverageBar(left, top, barWidth, BAR_HEIGHT, ratio, SERIES_S2, NEUTRAL);
        pdf.strokeLine(left, top + BAR_HEIGHT + 2.0d, left, top - 2.0d, 0.6d, REF[0], REF[1], REF[2]);
        pdf.strokeLine(left + barWidth, top + BAR_HEIGHT + 2.0d, left + barWidth, top - 2.0d,
                0.6d, REF[0], REF[1], REF[2]);
    }

    private static void renderHorizontalBars(PdfWriter pdf, Chart chart, MetricOutcome metric,
                                             double left, double width) {
        List<Map.Entry<String, Double>> rows = rowsOf(metric);
        if (rows.isEmpty()) {
            return;
        }
        double max = Math.max(domainOf(metric, maxOf(rows)), maxOf(rows));
        if (max <= 0.0d) {
            max = 1.0d;
        }
        double barArea = width - LABEL_WIDTH;
        double height = rows.size() * (BAR_HEIGHT + BAR_GAP) + 6.0d;
        double top = pdf.reserve(height);
        pdf.strokeLine(left + LABEL_WIDTH, top + 2.0d, left + LABEL_WIDTH, top - height + 6.0d,
                0.6d, REF[0], REF[1], REF[2]);
        double y = top;
        for (Map.Entry<String, Double> row : rows) {
            y -= BAR_HEIGHT + BAR_GAP;
            pdf.horizontalBar(left + LABEL_WIDTH, y, barArea, row.getValue(), max, BAR_HEIGHT,
                    SERIES_S1);
        }
    }

    private static List<Map.Entry<String, Double>> rowsOf(MetricOutcome metric) {
        Map<String, Double> data = seriesOrQuantiles(metric);
        List<Map.Entry<String, Double>> rows = new ArrayList<>(data.entrySet());
        if (rows.isEmpty() && metric.value() instanceof Number n) {
            String label = metric.namedKey() != null ? metric.namedKey() : metric.metricId();
            rows.add(new java.util.AbstractMap.SimpleImmutableEntry<>(label, n.doubleValue()));
        }
        rows.sort(Comparator.comparingDouble(Map.Entry::getValue));
        return rows;
    }

    private static double maxOf(List<Map.Entry<String, Double>> rows) {
        return rows.stream().mapToDouble(Map.Entry::getValue).max().orElse(0.0d);
    }

    private static void renderShareBar(PdfWriter pdf, Chart chart, MetricOutcome metric,
                                       double left, double width) {
        Map<String, Double> data = seriesOrQuantiles(metric);
        if (data.isEmpty()) {
            return;
        }
        List<Map.Entry<String, Double>> rows = new ArrayList<>(data.entrySet());
        rows.sort(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue())
                .reversed());
        double total = rows.stream().mapToDouble(Map.Entry::getValue).sum();
        if (total <= 0.0d) {
            return;
        }
        List<Map.Entry<String, Double>> top = rows.size() > 3 ? rows.subList(0, 3) : rows;
        double rest = rows.size() > 3
                ? total - top.stream().mapToDouble(Map.Entry::getValue).sum() : 0.0d;

        double topY = pdf.reserve(16.0d);
        double x = left;
        double barWidth = width;
        double[][] palette = {SERIES_S1, SERIES_S2, SERIES_S3};
        for (int i = 0; i < top.size(); i++) {
            double segment = barWidth * (top.get(i).getValue() / total);
            pdf.fillRect(x, topY, segment, BAR_HEIGHT,
                    palette[i % palette.length][0], palette[i % palette.length][1],
                    palette[i % palette.length][2]);
            x += segment;
        }
        if (rest > 0.0d) {
            pdf.fillRect(x, topY, barWidth * (rest / total), BAR_HEIGHT,
                    NEUTRAL[0], NEUTRAL[1], NEUTRAL[2]);
        }
        pdf.strokeLine(left, topY + BAR_HEIGHT + 2.0d, left, topY - 2.0d, 0.6d, REF[0], REF[1], REF[2]);
        pdf.strokeLine(left + barWidth, topY + BAR_HEIGHT + 2.0d, left + barWidth, topY - 2.0d,
                0.6d, REF[0], REF[1], REF[2]);
    }

    private static void renderBulletArray(PdfWriter pdf, Chart chart, MetricOutcome metric,
                                          double left, double width) {
        List<Map.Entry<String, Double>> rows = rowsOf(metric);
        if (rows.isEmpty()) {
            return;
        }
        double max = Math.max(domainOf(metric, maxOf(rows)), maxOf(rows));
        if (max <= 0.0d) {
            max = 1.0d;
        }
        double barArea = width - LABEL_WIDTH;
        double height = rows.size() * (BAR_HEIGHT + BAR_GAP) + 6.0d;
        double top = pdf.reserve(height);
        pdf.strokeLine(left + LABEL_WIDTH, top + 2.0d, left + LABEL_WIDTH, top - height + 6.0d,
                0.6d, REF[0], REF[1], REF[2]);
        pdf.strokeLine(left + LABEL_WIDTH + barArea, top + 2.0d,
                left + LABEL_WIDTH + barArea, top - height + 6.0d, 0.6d, REF[0], REF[1], REF[2]);
        double y = top;
        for (Map.Entry<String, Double> row : rows) {
            y -= BAR_HEIGHT + BAR_GAP;
            pdf.horizontalBar(left + LABEL_WIDTH, y, barArea, row.getValue(), max, BAR_HEIGHT,
                    SERIES_S1);
        }
    }

    private static void renderProfileBars(PdfWriter pdf, Chart chart, MetricOutcome metric,
                                          double left, double width) {
        List<Map.Entry<String, Double>> rows = rowsOf(metric);
        if (rows.isEmpty()) {
            return;
        }
        double barArea = width - LABEL_WIDTH;
        double height = rows.size() * (BAR_HEIGHT + BAR_GAP) + 6.0d;
        double top = pdf.reserve(height);
        pdf.strokeLine(left + LABEL_WIDTH, top + 2.0d, left + LABEL_WIDTH, top - height + 6.0d,
                0.6d, REF[0], REF[1], REF[2]);
        pdf.strokeLine(left + LABEL_WIDTH + barArea, top + 2.0d,
                left + LABEL_WIDTH + barArea, top - height + 6.0d, 0.6d, REF[0], REF[1], REF[2]);
        double y = top;
        for (Map.Entry<String, Double> row : rows) {
            y -= BAR_HEIGHT + BAR_GAP;
            double v = Math.max(0.0d, Math.min(1.0d, row.getValue()));
            pdf.horizontalBar(left + LABEL_WIDTH, y, barArea, v, 1.0d, BAR_HEIGHT, SERIES_S1);
        }
    }

    private static Double ratioOf(MetricOutcome metric) {
        if (metric.series() != null && !metric.series().isEmpty()) {
            double total = metric.series().values().stream().mapToDouble(Double::doubleValue).sum();
            if (total > 0.0d) {
                return Math.max(0.0d, Math.min(1.0d, total / 100.0d));
            }
        }
        if (metric.value() instanceof Number n) {
            double v = n.doubleValue();
            if ("%".equals(metric.unit())) {
                return Math.max(0.0d, Math.min(1.0d, v / 100.0d));
            }
            return Math.max(0.0d, Math.min(1.0d, v));
        }
        return null;
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
}
