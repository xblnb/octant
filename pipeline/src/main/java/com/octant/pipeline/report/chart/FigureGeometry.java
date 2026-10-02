package com.octant.pipeline.report.chart;

import com.octant.pipeline.analysis.ChartForms;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FigureGeometry {

    public static final int WIDTH = 720;
    private static final double LABEL_COL = 150.0d;
    private static final double LEFT = LABEL_COL + 16.0d;
    private static final double RIGHT = WIDTH - 24.0d;
    private static final double TICK_MIN = 1.5d;

    public static final Color AXIS = new Color(89, 92, 97);
    public static final Color GRID = new Color(214, 217, 222);
    public static final Color LABEL = new Color(38, 42, 47);
    public static final Color MUTED = new Color(87, 96, 106);
    public static final Color SERIES_1 = new Color(51, 107, 173);
    public static final Color SERIES_2 = new Color(13, 148, 136);
    public static final Color NEUTRAL = new Color(217, 219, 224);
    public static final Color RAIL = new Color(242, 244, 247);
    public static final Color BAND = new Color(89, 92, 97, 46);

    private static final double FS = 11.0d;
    private static final double FS_TICK = 9.5d;
    private static final double FS_LABEL = 10.5d;

    private FigureGeometry() {
    }

    public static Figure.Drawing compute(String form, String unit, List<Figure.Point> points,
                                         Double minRequired) {
        if (points == null || points.isEmpty() || ChartForms.KPI_UNIT.equals(form)
                || ChartForms.SUPPRESSED_PLACEHOLDER.equals(form)) {
            return null;
        }
        if (ChartForms.HORIZONTAL_BAR.equals(form) || ChartForms.DEVIATION_BAR.equals(form)) {
            return points.size() == 1 ? verticalBar(unit, points.get(0), minRequired)
                    : horizontalBars(form, unit, points, minRequired);
        }
        if (ChartForms.COVERAGE_BAR.equals(form)) {
            return shareBar(form, unit, points);
        }
        if (ChartForms.SHARE_BAR.equals(form) && points.size() >= 3) {
            return horizontalBars(form, unit, points, minRequired);
        }
        if (ChartForms.SHARE_BAR.equals(form)) {
            return shareBar(form, unit, points);
        }
        return horizontalBars(form, unit, points, minRequired);
    }

    private static Figure.Drawing horizontalBars(String form, String unit, List<Figure.Point> points,
                                                 Double minRequired) {
        boolean profile = ChartForms.PROFILE_PARALLEL.equals(form);
        boolean counts = ChartForms.SHARE_BAR.equals(form);
        double domain = counts ? countDomain(points) : domainOf(unit, points, profile);
        List<Double> ticks = counts ? countTicks(domain) : ticksFor(domain, 4);
        List<String> tickLabels = counts ? countTickLabels(ticks)
                : tickLabelsFor(ticks, unit, profile);

        int rows = points.size();
        double axisY = 30.0d + rows * 24.0d;
        double height = axisY + 40.0d;

        List<Figure.Primitive> out = new ArrayList<>();
        out.add(fixedText(12.0d, 18.0d, "数值范围 " + axisText(unit, profile, counts) + "（0 ~ "
                + fmt(domain) + "）", MUTED, FS_LABEL, false, "start"));
        out.add(fixedLine(LEFT, axisY, RIGHT, axisY, AXIS, 0.8d, false));
        for (int i = 0; i < ticks.size(); i++) {
            double x = LEFT + (RIGHT - LEFT) * ticks.get(i) / domain;
            out.add(fixedLine(x, axisY - 4.0d, x, axisY + 4.0d, GRID, 0.6d, false));
            out.add(fixedText(x, axisY + 16.0d, tickLabels.get(i), MUTED, FS_TICK, false, "middle"));
        }
        out.add(new Figure.Rect(LEFT, 30.0d, RIGHT - LEFT, rows * 24.0d + 6.0d, BAND, null, 0.0d,
                Figure.Role.REFERENCE, domain, "band-domain", Figure.Effect.SCALES));
        out.add(new Figure.Line(LEFT, 30.0d, LEFT, axisY, AXIS, 0.6d, true,
                Figure.Role.REFERENCE, 0.0d, "range-start", Figure.Effect.SCALES));
        out.add(new Figure.Line(RIGHT, 30.0d, RIGHT, axisY, AXIS, 0.6d, true,
                Figure.Role.REFERENCE, domain, "range-end", Figure.Effect.SCALES));
        out.add(valueText(LEFT, 24.0d, "量程下限 " + valueTextOf(0.0d, unit), MUTED, FS_TICK, false,
                "start", 0.0d, "range-label:start"));
        out.add(valueText(RIGHT, 24.0d, "量程上限 " + valueTextOf(domain, unit), MUTED, FS_TICK,
                false, "end", domain, "range-label:end"));
        if (minRequired != null && minRequired > 0.0d && minRequired <= domain) {
            double mx = LEFT + (RIGHT - LEFT) * minRequired / domain;
            out.add(new Figure.Line(mx, 30.0d, mx, axisY, SERIES_2, 0.8d, true,
                    Figure.Role.REFERENCE, minRequired, "min-required", Figure.Effect.SCALES));
            out.add(valueText(mx - 4.0d, 16.0d, "判定所需 " + fmt(minRequired) + unitSuffix(unit),
                    SERIES_2, FS_TICK, false, "end", minRequired, "min-required"));
        }
        for (int i = 0; i < rows; i++) {
            Figure.Point p = points.get(i);
            double y = 30.0d + i * 24.0d + 6.0d;
            double barH = 13.0d;
            double span = RIGHT - LEFT;
            double w = Math.max(TICK_MIN, span * clamp01(p.value() / domain));
            out.add(new Figure.Rect(LEFT, y, span, barH, RAIL, null, 0.0d, Figure.Role.FRAME,
                    null, "rail:" + p.label(), Figure.Effect.FIXED));
            out.add(new Figure.Rect(LEFT, y, w, barH, SERIES_1, null, 0.0d, Figure.Role.VALUE,
                    p.value(), "bar:" + p.label(), Figure.Effect.SCALES));
            out.add(fixedText(LEFT - 6.0d, y + barH - 2.0d, p.shown(), LABEL, FS_LABEL, false,
                    "end"));
            String shown = valueTextOf(p.value(), unit);
            if (w >= textWidth(shown, FS_LABEL) + 10.0d) {
                out.add(valueText(LEFT + w - 6.0d, y + barH - 2.0d, shown, java.awt.Color.WHITE,
                        FS_LABEL, false, "end", p.value(), "value-label:" + p.label()));
            } else {
                out.add(valueText(LEFT + w + 6.0d, y + barH - 2.0d, shown, LABEL, FS_LABEL, false,
                        "start", p.value(), "value-label:" + p.label()));
            }
        }
        return new Figure.Drawing(WIDTH, (int) Math.ceil(height), List.copyOf(out), unit, domain,
                axisText(unit, profile, counts) + "（0 ~ " + fmt(domain) + "）", ticks, tickLabels,
                "同一张图里的各项可以直接比长短：哪一项更长，它的数值就更大；"
                        + "每一项的数值也直接写在它旁边，可与等价表逐项核对。");
    }

    private static Figure.Drawing shareBar(String form, String unit, List<Figure.Point> points) {
        boolean coverage = ChartForms.COVERAGE_BAR.equals(form);
        double actual = points.get(0).value();
        double second = points.size() >= 2 ? points.get(1).value() : 0.0d;
        double domain = points.size() >= 2 ? Math.max(actual + second, 1.0e-9d)
                : domainOf(unit, points, false);
        double remainder = points.size() >= 2 ? second : Math.max(0.0d, domain - actual);
        double span = RIGHT - LEFT;
        double actualW = Math.max(TICK_MIN, span * clamp01(actual / domain));
        double restW = Math.max(0.0d, span - actualW);

        double top = 46.0d;
        double barH = 34.0d;
        double bottom = top + barH;
        double height = bottom + 56.0d;

        List<Figure.Primitive> out = new ArrayList<>();
        out.add(fixedText(12.0d, 18.0d, "本项的总量 = " + fmt(domain) + unitSuffix(unit)
                + (coverage ? "（可达内容的总量）" : "（合计）"), MUTED, FS_LABEL, false, "start"));
        if (restW > 0.5d) {
            out.add(new Figure.Rect(LEFT + actualW, top, restW, barH, NEUTRAL, null, 0.0d,
                    Figure.Role.REFERENCE, remainder, coverage ? "band-remaining" : "band-rest",
                    Figure.Effect.SCALES));
        }
        out.add(new Figure.Rect(LEFT, top, actualW, barH, SERIES_2, null, 0.0d, Figure.Role.VALUE,
                actual, "bar-actual", Figure.Effect.SCALES));
        out.add(new Figure.Line(LEFT, top, LEFT, bottom, AXIS, 0.8d, false, Figure.Role.REFERENCE,
                0.0d, "range-start", Figure.Effect.SCALES));
        out.add(new Figure.Line(RIGHT, top, RIGHT, bottom, AXIS, 0.8d, false,
                Figure.Role.REFERENCE, domain, "range-end", Figure.Effect.SCALES));
        out.add(valueText(LEFT, bottom + 16.0d, "已达 " + valueTextOf(actual, unit), SERIES_2,
                FS_LABEL + 1.0d, true, "start", actual, "value-label:actual"));
        if (remainder > 0.0d) {
            String rest = (coverage ? "尚未触达 " : "余量 ") + valueTextOf(remainder, unit);
            double restStart = LEFT + actualW + 6.0d;
            if (restW >= textWidth(rest, FS_LABEL) + 12.0d
                    && restStart >= LEFT + textWidth("已达 " + valueTextOf(actual, unit), FS_LABEL + 1.0d) + 12.0d) {
                out.add(valueText(restStart, bottom + 16.0d, rest, LABEL, FS_LABEL, false,
                        "start", remainder, "value-label:remainder"));
            } else {
                out.add(valueText(LEFT, bottom + 32.0d, rest, MUTED, FS_TICK, false, "start",
                        remainder, "value-label:remainder"));
            }
        }
        out.add(valueText(RIGHT, bottom + 16.0d, "总量 " + valueTextOf(domain, unit), MUTED,
                FS_TICK, false, "end", domain, "range-label:end"));
        return new Figure.Drawing(WIDTH, (int) Math.ceil(height), List.copyOf(out), unit, domain,
                fmt(domain) + unitSuffix(unit), List.of(0.0d, actual, domain),
                List.of("0" + unitSuffix(unit), valueTextOf(actual, unit),
                        valueTextOf(domain, unit)),
                "这是一条从 0 起的区间条：深色那段是已达量，浅色那段是余下未达的量，"
                        + "两段都直接写着数值，区间两端用参考线标出了 0 与总量。");
    }

    private static Figure.Drawing verticalBar(String unit, Figure.Point point, Double minRequired) {
        double domain = domainOf(unit, List.of(point), false);
        List<Double> ticks = ticksFor(domain, 8);
        List<String> tickLabels = tickLabelsFor(ticks, unit, false);

        double top = 34.0d;
        double baseline = 240.0d;
        double plotH = baseline - top;
        double barW = 112.0d;
        double barX = LEFT + (RIGHT - LEFT) / 2.0d - barW / 2.0d;
        double barH = Math.max(TICK_MIN, plotH * clamp01(point.value() / domain));
        double barY = baseline - barH;
        double height = baseline + 62.0d;

        List<Figure.Primitive> out = new ArrayList<>();
        out.add(fixedText(12.0d, 18.0d, "纵轴 " + axisText(unit, false, false) + "（0 ~ "
                + fmt(domain) + "）", MUTED, FS_LABEL, false, "start"));
        double bandTop = baseline - plotH;
        out.add(new Figure.Rect(LEFT, bandTop, RIGHT - LEFT, plotH, BAND, null, 0.0d,
                Figure.Role.REFERENCE, domain, "band-domain", Figure.Effect.SCALES));
        for (int i = 0; i < ticks.size(); i++) {
            double y = baseline - plotH * ticks.get(i) / domain;
            out.add(fixedLine(LEFT - 3.0d, y, RIGHT, y, GRID, 0.6d, false));
            out.add(fixedText(LEFT - 8.0d, y + 3.0d, tickLabels.get(i), MUTED, FS_TICK, false,
                    "end"));
        }
        out.add(fixedLine(LEFT, baseline, RIGHT, baseline, AXIS, 0.8d, false));
        out.add(new Figure.Rect(barX, barY, barW, barH, SERIES_1, null, 0.0d, Figure.Role.VALUE,
                point.value(), "bar-value", Figure.Effect.SCALES));
        out.add(valueText(barX + barW / 2.0d, barY - 8.0d, valueTextOf(point.value(), unit), LABEL,
                FS + 1.0d, true, "middle", point.value(), "value-label:value"));
        if (minRequired != null && minRequired > 0.0d && minRequired <= domain) {
            double my = baseline - plotH * minRequired / domain;
            out.add(new Figure.Line(LEFT, my, RIGHT, my, SERIES_2, 0.8d, true,
                    Figure.Role.REFERENCE, minRequired, "min-required", Figure.Effect.SCALES));
            out.add(valueText(RIGHT, my - 4.0d, "判定所需 " + fmt(minRequired) + unitSuffix(unit),
                    SERIES_2, FS_TICK, false, "end", minRequired, "min-required"));
        }
        out.add(new Figure.Line(LEFT, bandTop, RIGHT, bandTop, AXIS, 0.6d, true,
                Figure.Role.REFERENCE, domain, "range-end", Figure.Effect.SCALES));
        out.add(new Figure.Line(LEFT, baseline, RIGHT, baseline, AXIS, 0.6d, true,
                Figure.Role.REFERENCE, 0.0d, "range-start", Figure.Effect.SCALES));
        out.add(valueText(RIGHT, bandTop - 5.0d, "量程上限 " + valueTextOf(domain, unit), MUTED,
                FS_TICK, false, "end", domain, "range-label:end"));
        out.add(valueText(LEFT, baseline + 18.0d, "量程下限 0" + unitSuffix(unit), MUTED, FS_TICK,
                false, "start", 0.0d, "range-label:start"));
        out.add(fixedText(barX + barW / 2.0d, baseline + 34.0d, point.shown(), LABEL, FS_LABEL, true,
                "middle"));
        return new Figure.Drawing(WIDTH, (int) Math.ceil(height), List.copyOf(out), unit, domain,
                axisText(unit, false, false) + "（0 ~ " + fmt(domain) + "）", ticks, tickLabels,
                "柱子越高，这一项的数值越大；柱子上方直接写着数值，纵轴的数字给出取值范围。"
                        + "它在这条范围里的位置可以判读：越靠上就越接近上限。");
    }

    public static Figure.Drawing groupedBars(String title, java.util.List<Figure.Point> series,
                                             double refValue, String refLabel, boolean zoom) {
        return groupedBars(title, series, refValue, refLabel, zoom, null);
    }

    public static Figure.Drawing groupedBars(String title, java.util.List<Figure.Point> series,
                                             double refValue, String refLabel, boolean zoom,
                                             java.util.function.Function<Figure.Point, String> unitOf) {
        java.util.List<Figure.Primitive> out = new java.util.ArrayList<>();
        int rows = Math.max(1, series.size());
        double domain = 1.0d;
        for (Figure.Point p : series) {
            domain = Math.max(domain, Math.abs(p.value()));
        }
        if (zoom && domain < 1.0d) {
            domain = 1.0d;
        }
        double span = RIGHT - LEFT;
        double top = 40.0d;
        double rowH = 22.0d;
        double barH = 14.0d;
        double axisY = top + rows * rowH + 6.0d;
        double height = axisY + 44.0d;

        out.add(fixedText(12.0d, 18.0d, "同图比较：".concat(title), MUTED, FS_LABEL, true, "start"));
        if (refValue > 0.0d) {
            double rx = LEFT + span * clamp01(refValue / domain);
            out.add(new Figure.Line(rx, top - 4.0d, rx, axisY, SERIES_2, 0.9d, true,
                    Figure.Role.REFERENCE, refValue, "reference", Figure.Effect.SCALES));
            out.add(valueText(rx, top - 8.0d, refLabel, SERIES_2, FS_TICK, false, "middle",
                    refValue, "reference-label"));
        }
        out.add(fixedLine(LEFT, axisY, RIGHT, axisY, AXIS, 0.8d, false));
        for (int i = 0; i <= 4; i++) {
            double x = LEFT + span * i / 4.0d;
            out.add(fixedLine(x, axisY - 4.0d, x, axisY + 4.0d, GRID, 0.6d, false));
            out.add(fixedText(x, axisY + 16.0d, fmt(domain * i / 4.0d), MUTED, FS_TICK, false,
                    "middle"));
        }
        for (int i = 0; i < series.size(); i++) {
            Figure.Point p = series.get(i);
            double y = top + i * rowH;
            double w = Math.max(TICK_MIN, span * clamp01(Math.abs(p.value()) / domain));
            out.add(new Figure.Rect(LEFT, y, span, barH, RAIL, null, 0.0d, Figure.Role.FRAME,
                    null, "rail:" + p.label(), Figure.Effect.FIXED));
            out.add(new Figure.Rect(LEFT, y, w, barH, SERIES_1, null, 0.0d, Figure.Role.VALUE,
                    p.value(), "bar:" + p.label(), Figure.Effect.SCALES));
            out.add(fixedText(LEFT - 6.0d, y + barH - 2.0d, p.shown(), LABEL, FS_LABEL, false,
                    "end"));
            String shown = valueTextOf(p.value(), unitOf == null ? null : unitOf.apply(p));
            out.add(valueText(LEFT + w + 6.0d, y + barH - 2.0d, shown, LABEL, FS_LABEL, false,
                    "start", p.value(), "value-label:" + p.label()));
        }
        return new Figure.Drawing(WIDTH, (int) Math.ceil(height), java.util.List.copyOf(out), "-",
                domain, "0 ~ " + fmt(domain), java.util.List.of(0.0d, domain),
                java.util.List.of("0", fmt(domain)),
                "同一张图里的每一项各占一条，横轴是同一把尺；虚线标出了参照值（来源已写在图上），"
                        + "各项与参照值的距离就是'高了还是低了'。");
    }

    public static Figure.Drawing lineSeries(String title, java.util.List<Figure.Point> points,
                                            double refValue, String refLabel, String axisUnit) {
        java.util.List<Figure.Primitive> out = new java.util.ArrayList<>();
        double domain = 0.0d;
        for (Figure.Point p : points) {
            domain = Math.max(domain, Math.abs(p.value()));
        }
        double lo = axisUnit == null ? 0.0d : 0.0d;
        double hi = Math.max(domain, refValue > 0.0d ? refValue : domain);
        if (hi <= 0.0d) {
            hi = 1.0d;
        }
        hi = hi * 1.15d;
        double left = 60.0d;
        double bottom = 190.0d;
        double top = 46.0d;
        double right = WIDTH - 30.0d;
        double height = bottom + 56.0d;

        out.add(fixedText(12.0d, 18.0d, "时间序列：".concat(title), MUTED, FS_LABEL, true, "start"));
        for (int i = 0; i <= 4; i++) {
            double y = bottom - (bottom - top) * i / 4.0d;
            out.add(fixedLine(left, y, right, y, GRID, 0.6d, false));
            out.add(fixedText(left - 6.0d, y + 3.0d, fmt(hi * i / 4.0d), MUTED, FS_TICK, false,
                    "end"));
        }
        out.add(fixedLine(left, bottom, right, bottom, AXIS, 0.8d, false));
        if (refValue > 0.0d) {
            double ry = bottom - (bottom - top) * clamp01(refValue / hi);
            out.add(new Figure.Line(left, ry, right, ry, SERIES_2, 0.9d, true,
                    Figure.Role.REFERENCE, refValue, "reference", Figure.Effect.SCALES));
            out.add(valueText(right, ry - 5.0d, refLabel, SERIES_2, FS_TICK, false, "end",
                    refValue, "reference-label"));
        }
        int n = points.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? (left + right) / 2.0d
                    : left + (right - left) * i / (double) (n - 1);
            ys[i] = bottom - (bottom - top) * clamp01(points.get(i).value() / hi);
        }
        out.add(new Figure.Polyline(xs, ys, SERIES_1, 2.0d, Figure.Role.VALUE,
                points.isEmpty() ? null : points.get(n - 1).value(), "line-series",
                Figure.Effect.SCALES));
        for (int i = 0; i < n; i++) {
            out.add(new Figure.Circle(xs[i], ys[i], 3.5d, SERIES_1, java.awt.Color.WHITE, 1.2d,
                    Figure.Role.VALUE, points.get(i).value(), "point:" + points.get(i).label(),
                    Figure.Effect.SCALES));
            out.add(fixedText(xs[i], bottom + 16.0d, points.get(i).shown(), MUTED, FS_TICK, false,
                    "middle"));
        }
        return new Figure.Drawing(WIDTH, (int) Math.ceil(height), java.util.List.copyOf(out),
                axisUnit == null ? "-" : axisUnit, hi, "0 ~ " + fmt(hi),
                java.util.List.of(0.0d, hi), java.util.List.of("0", fmt(hi)),
                "折线给出这个量随时间点的走向，每个点是一个时间点的取值，虚线标出了参照值"
                        + "（来源已写在图上）；点与点之间抬升还是回落，就是'变多还是变少'。");
    }

    public static double domainOf(String unit, List<Figure.Point> points, boolean normalized) {
        String u = unit == null ? "" : unit.trim();
        if (normalized || u.isEmpty() || "-".equals(u) || "无单位".equals(u)) {
            return 1.0d;
        }
        if ("%".equals(u)) {
            return 100.0d;
        }
        double max = 0.0d;
        for (Figure.Point p : points) {
            max = Math.max(max, p.value());
        }
        return max > 0.0d ? max : 1.0d;
    }

    private static double countDomain(List<Figure.Point> points) {
        double sum = 0.0d;
        for (Figure.Point p : points) {
            sum += Math.max(0.0d, p.value());
        }
        return sum > 0.0d ? sum : 1.0d;
    }

    private static List<Double> countTicks(double domain) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i <= 4; i++) {
            double t = Math.rint(domain * i / 4.0d);
            if (!out.isEmpty() && t <= out.get(out.size() - 1)) {
                t = out.get(out.size() - 1) + 1.0d;
            }
            out.add(t);
        }
        return out;
    }

    private static List<String> countTickLabels(List<Double> ticks) {
        List<String> out = new ArrayList<>();
        for (Double t : ticks) {
            out.add(fmt(t));
        }
        return out;
    }

    private static List<Double> ticksFor(double domain, int divisions) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i <= divisions; i++) {
            out.add(domain * i / divisions);
        }
        return out;
    }

    private static List<String> tickLabelsFor(List<Double> ticks, String unit, boolean normalized) {
        List<String> out = new ArrayList<>();
        boolean percent = "%".equals(unit == null ? "" : unit.trim());
        for (Double t : ticks) {
            if (percent) {
                out.add(fmt(t) + "%");
            } else if (normalized || unit == null || unit.isBlank() || "-".equals(unit.trim())
                    || "无单位".equals(unit.trim())) {
                out.add(String.format(java.util.Locale.ROOT, "%.2f", t));
            } else {
                out.add(fmt(t));
            }
        }
        return out;
    }

    private static String trimZeros(String s) {
        if (!s.contains(".")) {
            return s;
        }
        String t = s.replaceAll("0+$", "");
        return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
    }

    private static String axisText(String unit, boolean normalized, boolean counts) {
        if (counts) {
            return "单位 项（各项在合计里的份额）";
        }
        String u = unit == null ? "" : unit.trim();
        if (normalized || u.isEmpty() || "-".equals(u) || "无单位".equals(u)) {
            return "已归一化到 0~1（轴间不可相加）";
        }
        return "单位 " + u;
    }

    static double textWidth(String s, double fontSize) {
        if (s == null || s.isEmpty()) {
            return 0.0d;
        }
        double w = 0.0d;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += (c > 0x2E80) ? fontSize : fontSize * 0.55d;
        }
        return w;
    }

    public static String fmt(double v) {
        double r = Math.round(v * 1000.0d) / 1000.0d;
        if (r == Math.floor(r) && Math.abs(r) < 1.0e15d) {
            return String.valueOf((long) r);
        }
        return trimZeros(String.format(java.util.Locale.ROOT, "%.3f", r));
    }

    public static String valueTextOf(double v, String unit) {
        return fmt(v) + unitSuffix(unit);
    }

    public static String unitSuffix(String unit) {
        String u = unit == null || unit.isBlank() || "-".equals(unit.trim()) ? "无单位" : unit.trim();
        return "（" + u + "）";
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0.0d) {
            return 0.0d;
        }
        return Math.min(1.0d, v);
    }

    private static Figure.Line fixedLine(double x1, double y1, double x2, double y2, Color stroke,
                                         double w, boolean dashed) {
        return new Figure.Line(x1, y1, x2, y2, stroke, w, dashed, Figure.Role.FRAME, null,
                null, Figure.Effect.FIXED);
    }

    private static Figure.Text fixedText(double x, double y, String content, Color fill, double size,
                                         boolean bold, String anchor) {
        return new Figure.Text(x, y, content, fill, size, bold, anchor, Figure.Role.FRAME, null,
                "text:" + content, Figure.Effect.FIXED);
    }

    private static Figure.Text valueText(double x, double y, String content, Color fill, double size,
                                         boolean bold, String anchor, Double value, String label) {
        return new Figure.Text(x, y, content, fill, size, bold, anchor, Figure.Role.VALUE, value,
                label, Figure.Effect.TEXT);
    }

    public static List<Figure.Point> pointsOf(java.util.Map<String, Double> series,
                                              java.util.Map<String, Double> quantiles,
                                              Object scalar, String scalarLabel) {
        java.util.Map<String, Double> src = series != null && !series.isEmpty() ? series
                : (quantiles != null && !quantiles.isEmpty() ? quantiles : null);
        List<Figure.Point> out = new ArrayList<>();
        if (src != null) {
            List<String> keys = new ArrayList<>(src.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                out.add(new Figure.Point(k, src.get(k) == null ? 0.0d : src.get(k)));
            }
            return out;
        }
        if (scalar instanceof Number n) {
            out.add(new Figure.Point(scalarLabel == null ? "取值" : scalarLabel, n.doubleValue()));
        }
        return out;
    }
}
