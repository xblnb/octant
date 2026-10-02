package com.octant.pipeline.tools;

import com.octant.pipeline.RealCaptureCorpus;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.export.FileConsentSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.TreeMap;

public final class ExportRunner {

    private ExportRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--probe-2values".equals(args[0])) {
            probeTwoValues();
            return;
        }
        Path out = Path.of(args.length > 0 ? args[0] : "pipeline/build/manual-out/samples");
        Files.createDirectories(out);
        Path consent = Path.of("pipeline/build/manual-out/consent-fixtures/samples-consent.json");
        Files.createDirectories(consent.getParent());
        Files.writeString(consent, "{\n"
                + "  \"schemaVersion\": \"privacy-consent@2.0.0\",\n"
                + "  \"permit\": {\n"
                + "    \"enabled\": true,\n"
                + "    \"exportEnabled\": true,\n"
                + "    \"acknowledged\": true,\n"
                + "    \"consentedCategories\": [\"C1\",\"C2\",\"C3\",\"C5\",\"C6\",\"C7\",\"C8\"]\n"
                + "  }\n}\n", StandardCharsets.UTF_8);

        var events = RealCaptureCorpus.events();
        var conv = RealCaptureCorpus.toPipeline(events);
        ExportPipeline.Input input = RealCaptureCorpus.input();
        ExportPipeline.Result result = ExportPipeline.write(input, out,
                Instant.parse("2026-09-26T15:00:00Z"), 0x5A17E5L, new FileConsentSource(consent));

        System.out.println("captureEvents = " + events.size()
                + "  accepted = " + conv.acceptedCount() + "  rejected = " + conv.rejectedCount());
        System.out.println("exportId     = " + result.exportId());
        System.out.println("directory    = " + result.directory().toAbsolutePath());
        System.out.println("pdfPages     = " + result.pdfPages());
        for (var e : new TreeMap<>(result.fileBytes()).entrySet()) {
            System.out.println(String.format("  %-28s %9d bytes  sha256_16=%s",
                    e.getKey(), e.getValue(), result.fileSha256_16().get(e.getKey())));
        }
    }

    static void probeTwoValues() {
        double[] values = {0.2d, 0.8d};
        for (String form : new String[]{"C14", "C12", "C2"}) {
            System.out.println("== 图型 " + form + " ==");
            String prev = null;
            for (double v : values) {
                var points = com.octant.pipeline.report.chart.FigureGeometry.pointsOf(
                        null, null, v, "A");
                var d = com.octant.pipeline.report.chart.FigureGeometry.compute(
                        form, "-", points, null);
                StringBuilder sb = new StringBuilder();
                sb.append("  值 ").append(v).append(" ｜ 数据图元 ")
                        .append(d.dataPrimitiveCount()).append(" ｜ 画布 ")
                        .append(d.width()).append('x').append(d.height()).append('\n');
                for (var p : d.primitives()) {
                    if (!(p instanceof com.octant.pipeline.report.chart.Figure.Rect r)) {
                        continue;
                    }
                    String role = r.role().wire();
                    sb.append(String.format(java.util.Locale.ROOT,
                            "    rect[%-9s] x=%.3f y=%.3f w=%.3f h=%.3f%n",
                            role, r.x(), r.y(), r.w(), r.h()));
                }
                String s = sb.toString();
                System.out.print(s);
                prev = s;
                if (prev.isEmpty()) {
                    System.out.println("    （无矩形图元）");
                }
            }
        }
    }
}
