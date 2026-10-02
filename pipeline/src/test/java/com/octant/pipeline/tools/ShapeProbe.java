package com.octant.pipeline.tools;

import com.octant.pipeline.RealCaptureCorpus;
import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.analysis.Chart;
import com.octant.pipeline.analysis.MetricOutcome;

public final class ShapeProbe {

    private ShapeProbe() {
    }

    public static void main(String[] args) {
        AnalysisReport report = RealCaptureCorpus.input().report();
        System.out.printf("%-5s %-8s %-16s %-4s %-6s %-6s %-10s %-12s %s%n",
                "chart", "metric", "feature", "form", "series", "quant", "value", "unit", "namedKey");
        int cannotDraw = 0;
        int need = 0;
        for (Chart c : report.charts()) {
            MetricOutcome m = report.metric(c.metricIds().get(0));
            int series = m == null || m.series() == null ? -1 : m.series().size();
            int quant = m == null || m.quantiles() == null ? -1 : m.quantiles().size();
            Object value = m == null ? null : m.value();
            String form = c.form();
            boolean noDraw = "C1".equals(form) || "C11".equals(form);
            if (!noDraw) {
                need++;
                boolean drawable = series > 0 || quant > 0;
                if (!drawable) {
                    cannotDraw++;
                }
            }
            System.out.printf("%-5s %-8s %-16s %-4s %-6d %-6d %-10s %-12s %s%s%n",
                    c.chartId(), c.metricIds().get(0),
                    m == null ? "-" : m.featureId(), form, series, quant,
                    value == null ? "null" : String.valueOf(value), c.unit(),
                    m == null ? "null" : String.valueOf(m.namedKey()),
                    noDraw ? "  [不画图]"
                            : (series > 0 || quant > 0 ? "  [可画]" : "  [无数据可画]"));
        }
        System.out.println();
        System.out.println("需要图形的项 = " + need + " ; 无数据可画 = " + cannotDraw);
    }
}
