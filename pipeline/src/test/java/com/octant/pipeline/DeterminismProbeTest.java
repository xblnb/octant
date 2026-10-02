package com.octant.pipeline;

import com.octant.pipeline.adapter.CaptureEventAdapter;
import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.export.AnalysisJson;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.json.JsonWriter;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.EventStream;
import com.octant.pipeline.raw.RawEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterminismProbeTest {

    private static final Instant FIXED = Instant.parse("2026-09-26T15:00:00Z");

    private static List<RawEvent> pipelineEvents() {
        CaptureEventAdapter.Conversion conv = RealCaptureCorpus.toPipeline(RealCaptureCorpus.events());
        return conv.events();
    }

    private static String analysisHash(List<RawEvent> events, ContentCatalog catalog) {
        AnalysisReport r = AnalysisReport.analyze(EventStream.of(events), catalog, "forge");
        return ExportPipeline.sha256_16(
                JsonWriter.compact(AnalysisJson.toJson(r)).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("产物字节的稳定摘要是契约 §8 的守卫（任何键序漂移都会改这些值）")
    void artifactDigestsArePinned() {
        ExportPipeline.Input in = RealCaptureCorpus.input();
        ExportPipeline.Bundle b = ExportPipeline.build(in, "20260926-150000-5a17e5", FIXED, 0x5A17E5L);

        Map<String, String> digests = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : b.textFiles().entrySet()) {
            digests.put(e.getKey(), ExportPipeline.sha256_16(
                    e.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        for (Map.Entry<String, byte[]> e : b.binaryFiles().entrySet()) {
            digests.put(e.getKey(), ExportPipeline.sha256_16(e.getValue()));
        }
        System.out.println("== 产物摘要（跨 JVM 实测两次运行逐字节相同）==");
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(digests).entrySet()) {
            System.out.println(String.format("  %-26s %s", e.getKey(), e.getValue()));
        }

        Map<String, String> pinned = new java.util.LinkedHashMap<>();
        pinned.put("PRIVACY-README.txt", "919779e7910ca9b2");
        pinned.put("analysis.json", "e287f21c6575c834");
        pinned.put("conclusions.json", "1b3a32d143bed0e6");
        pinned.put("events.anonymized.jsonl", "ec86310869b5f481");
        pinned.put("manifest.json", "ed41bcd4d2ebcfbd");
        pinned.put("metrics.json", "f9cd46700ec2a4ab");
        pinned.put("redaction_report.json", "ac1dfba2846d502a");
        pinned.put("report.md", "7f1ecae2d44f80b9");
        pinned.put("report.html", "103d8204fed560d0");

        for (Map.Entry<String, String> e : pinned.entrySet()) {
            assertEquals(e.getValue(), digests.get(e.getKey()), e.getKey()
                    + " 字节漂移（跨次可再生性被破坏）。若因规范升版（当前 "
                    + com.octant.pipeline.export.ExportVersions.privacySpecVersion()
                    + "）导致 manifest 变化，请刷新 pinned 值。");
        }
        assertEquals(9, digests.size(), "应是 9 个产物");
    }

    @Test
    @DisplayName("探针：报告文案里所有字符都必须被选定字体覆盖（否则 PDF 文本层会缺字）")
    void reportTextIsFullyCoveredByFont() {
        ExportPipeline.Input in = RealCaptureCorpus.input();
        var meta = new com.octant.pipeline.report.ReportDocument.ExportMeta(
                "20260926-150000-5a17e5", "2026-09-26", "2026-09-26T15:00Z", "0.1.0", "1.20.1",
                "forge", "HIG-1.2", com.octant.pipeline.export.ExportVersions.privacySpecVersion(),
                List.of("C1"), List.of("pseudonym:per_export"),
                List.of("raw_events"), "notice", List.of("minecraft"));
        String allText = String.join("\n",
                new com.octant.pipeline.report.ReportDocument(in.report(), meta).lines());
        var resolved = com.octant.pipeline.report.font.ReportFontResolver.resolve(allText);
        assertTrue(resolved.isPresent(), "找不到可用字体");
        List<Integer> missing = resolved.get().palette().missingCodePoints(allText);
        StringBuilder detail = new StringBuilder();
        for (int cp : missing) {
            detail.append(String.format("U+%04X('%s') ", cp, new String(Character.toChars(cp))));
        }
        System.out.println("== 字体覆盖探针 ==");
        System.out.println("font = " + resolved.get().fontFile()
                + "  glyphs=" + resolved.get().glyphCount()
                + "  subsetBytes=" + resolved.get().subsetBytes());
        System.out.println("missing = " + (missing.isEmpty() ? "NONE" : detail.toString()));
        assertTrue(missing.isEmpty(), "报告文案含字体缺字（PDF 文本层会缺字）：" + detail);
    }

    @Test
    @DisplayName("同一 JVM 内重复 5 次：快照号与产物字节必须稳定")
    void snapshotIdAndBytesAreStableWithinOneJvm() {
        ContentCatalog catalog = RealCaptureCorpus.catalog();
        List<RawEvent> events = pipelineEvents();

        List<String> snaps = new ArrayList<>();
        List<String> analysisHashes = new ArrayList<>();
        List<String> manifestHashes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AnalysisReport r = AnalysisReport.analyze(EventStream.of(events), catalog, "forge");
            snaps.add(ExportPipeline.snapshotIdOf(AnalysisJson.toJson(r)));
            analysisHashes.add(analysisHash(events, catalog));

            ExportPipeline.Input in = RealCaptureCorpus.input();
            ExportPipeline.Bundle b = ExportPipeline.build(in, "20260926-150000-5a17e5", FIXED, 0x5A17E5L);
            manifestHashes.add(ExportPipeline.sha256_16(
                    b.textFiles().get("manifest.json").getBytes(StandardCharsets.UTF_8)));
        }

        System.out.println("== 跨次可再生性探针（同一 JVM，5 轮）==");
        System.out.println("snapshotIds        = " + snaps);
        System.out.println("analysis 摘要       = " + analysisHashes);
        System.out.println("manifest 摘要       = " + manifestHashes);
        System.out.println("suppressionSummary.counts 实现类 = "
                + RealCaptureCorpus.input().report().suppressionSummary().get("counts").getClass().getName());

        assertEquals(1, new LinkedHashSet<>(snaps).size(), "快照号必须逐次相同：实际 " + snaps);
        assertEquals(1, new LinkedHashSet<>(analysisHashes).size(),
                "analysis.json 必须逐次逐字节相同：" + analysisHashes);
        assertEquals(1, new LinkedHashSet<>(manifestHashes).size(),
                "manifest.json 必须逐次逐字节相同：" + manifestHashes);
    }
}
