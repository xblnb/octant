package com.octant.pipeline;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.report.PdfReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfTextReconstructionTest {

    private static final java.time.Instant FIXED = java.time.Instant.parse("2026-09-26T15:00:00Z");

    private static String stripAllWhitespace(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static List<String> numbersIn(String text) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{2,}").matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    @Test
    @DisplayName("PDF 文本层必须重建出生成器的每一行（含全部多位数）")
    void textLayerRebuildsEveryGeneratedLine() {
        ExportPipeline.Input in = RealCaptureCorpus.input();
        var meta = new com.octant.pipeline.report.ReportDocument.ExportMeta(
                "20260926-150000-5a17e5", "2026-09-26", "2026-09-26T15:00Z", "0.1.0", "1.20.1",
                "forge", "HIG-1.2", com.octant.pipeline.export.ExportVersions.privacySpecVersion(),
                List.of("C1"), List.of("pseudonym:per_export"), List.of("raw_events"), "notice",
                List.of("minecraft"));

        AnalysisReport report = in.report();
        byte[] pdf = PdfReport.render(report, meta);
        PdfTextExtractor extractor = new PdfTextExtractor(pdf);

        assertTrue(extractor.hasEmbeddedFontFile(), "必须嵌入字体（否则字形无来源）");
        assertTrue(extractor.hasCidToGidMap(), "必须给出 CID→字形映射");
        assertEquals("CIDFontType2", extractor.cidFontSubtype());
        assertTrue(extractor.unmappedCodes().isEmpty(),
                "有 ToUnicode 查不到的值：" + extractor.unmappedCodes());

        String layer = stripAllWhitespace(extractor.text());
        List<String> expected = PdfReport.textLayerLines(report, meta);

        List<String> missingLines = new ArrayList<>();
        for (String expectedLine : expected) {
            String needle = stripAllWhitespace(expectedLine);
            if (needle.isEmpty()) {
                continue;
            }
            if (!layer.contains(needle)) {
                missingLines.add(expectedLine);
            }
        }

        List<String> missingNumbers = new ArrayList<>();
        for (String n : numbersIn(String.join("\n", expected))) {
            if (!layer.contains(n)) {
                missingNumbers.add(n);
            }
        }

        System.out.println("== PDF 文本层重建对账 ==");
        System.out.println("生成行数 = " + expected.size()
                + "  文本层字符数 = " + extractor.text().length()
                + "  嵌入字体字节 = " + extractor.embeddedFontBytes()
                + "  ToUnicode 条目 = " + extractor.toUnicodeEntryCount());
        System.out.println("未重建行数 = " + missingLines.size()
                + "  未命中多位数个数 = " + missingNumbers.size());

        assertTrue(missingLines.isEmpty(),
                "PDF 文本层未重建出这些行（前 5 条）：" + missingLines.subList(0, Math.min(5, missingLines.size())));
        assertTrue(missingNumbers.isEmpty(),
                "PDF 文本层丢失多位数（前 10 个）：" + missingNumbers.subList(0, Math.min(10, missingNumbers.size())));
    }
}
