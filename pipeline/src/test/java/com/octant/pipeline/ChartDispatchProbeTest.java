package com.octant.pipeline;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.analysis.Chart;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.report.ReportDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartDispatchProbeTest {

    private static final Pattern HEADING = Pattern.compile("^###\\s+(C\\d{1,2})\\s*—");

    @Test
    @DisplayName("探针：报告里的图表项标题能否解析出 chartId，且能取到 form")
    void chartHeadingsResolveToForms() {
        ExportPipeline.Input in = RealCaptureCorpus.input();
        var meta = new ReportDocument.ExportMeta("20260926-150000-5a17e5", "2026-09-26",
                "2026-09-26T15:00Z", "0.1.0", "1.20.1", "forge", "HIG-1.2",
                com.octant.pipeline.export.ExportVersions.privacySpecVersion(),
                List.of("C1"), List.of("privacyFlags"), List.of("excl"), "notice", List.of("minecraft"));
        AlertReportHolder.REPORT = in.report();
        List<String> lines = new ReportDocument(in.report(), meta).lines();

        int headings = 0;
        int resolved = 0;
        StringBuilder sample = new StringBuilder();
        for (String line : lines) {
            Matcher m = HEADING.matcher(line);
            if (!m.find()) {
                continue;
            }
            headings++;
            Chart c = in.report().chartById(m.group(1));
            if (c != null) {
                resolved++;
                if (sample.length() < 300) {
                    sample.append(m.group(1)).append("->").append(c.form()).append("  ");
                }
            }
        }
        System.out.println("== 图表派发探针 ==");
        System.out.println("图表项标题数 = " + headings + "  能取到图表对象 = " + resolved);
        System.out.println("样例 = " + sample);
        System.out.println("report.charts() = " + in.report().charts().size());
        for (Chart c : in.report().charts()) {
            if ("C15".equals(c.form()) || "C3".equals(c.form())) {
                System.out.println("   含绘图图型：" + c.chartId() + " form=" + c.form()
                        + " metrics=" + c.metricIds() + " suppressed=" + c.suppressed());
            }
        }
        assertTrue(headings > 0, "报告里应存在图表项标题");
        assertTrue(resolved > 0, "图表项标题应能解析到图表对象");
    }

    static final class AlertReportHolder {
        static AnalysisReport REPORT;
    }
}
