package com.octant.pipeline;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.report.ReportDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class EndToEndExportTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-09-26T14:07:00Z");

    static ExportPipeline.Input input() {
        ContentCatalog catalog = SyntheticCorpus.catalog();
        AnalysisReport report = AnalysisReport.analyze(SyntheticCorpus.events(), catalog,
                SyntheticCorpus.SOURCE);
        ExportPipeline.EventStreamSource source = SyntheticCorpus::events;
        return new ExportPipeline.Input(report, source, catalog, "0.1.0", "1.20.1", "forge",
                "synthetic-salt-2026", List.of("C1", "C2", "C3", "C5", "C6", "C7", "C8"),
                List.of("minecraft", "octant"));
    }

    private static Path outDir() {
        String base = System.getProperty("octant.e2e.out");
        if (base == null || base.isEmpty()) {
            base = Path.of("build", "e2e-out").toAbsolutePath().toString();
        }
        return Path.of(base);
    }

    @Test
    @DisplayName("真实落盘：9 个导出文件全部生成，HTML 载体自足且文本层含 6 节标题（KH1/KH5/KH10）")
    void endToEndProducesRealArtifacts() throws Exception {
        ExportPipeline.Input in = input();
        Path root = outDir();
        Files.createDirectories(root);
        ExportPipeline.Result result = ExportPipeline.write(in, root, FIXED_TIME, 0xC0FFEEL);

        System.out.println("== mc-insight 端到端导出取证 ==");
        System.out.println("exportId   = " + result.exportId());
        System.out.println("directory  = " + result.directory().toAbsolutePath());
        System.out.println("written    = " + result.written());
        System.out.println("reportUnits = " + result.reportUnits()
                + "（载体可数单元 = 图表项数；HTML 无页概念，故不报页数）");
        System.out.println("gateViolations = " + result.gateViolations());
        for (var e : new java.util.TreeMap<>(result.fileBytes()).entrySet()) {
            System.out.println(String.format("  %-28s %8d bytes  sha256_16=%s", e.getKey(), e.getValue(),
                    result.fileSha256_16().get(e.getKey())));
            Path p = result.directory().resolve(e.getKey());
            assertTrue(Files.isRegularFile(p), "缺少导出文件：" + p);
            assertEquals(e.getValue().longValue(), Files.size(p), "字节数不一致：" + e.getKey());
        }

        if (!result.gateViolations().isEmpty()) {
            fail("隐私门禁未通过：" + result.gateViolations());
        }
        assertTrue(result.written(), "导出未落盘");

        assertFalse(result.fileBytes().containsKey("report.pdf"),
                "PDF 载体已退役，产物里不得再出现 report.pdf");
        assertTrue(result.fileBytes().containsKey("report.html"),
                "报告载体应为 report.html，实际文件清单：" + result.fileBytes().keySet());
        assertEquals(9, result.fileBytes().size(), "产物应为 9 个文件");

        assertEquals(-1, result.pdfPages(),
                "pdfPages() 在 HTML 载体上必须返回 -1（不适用），不得返回 0（0 会被读成没渲染）");
        assertEquals(42, result.reportUnits(),
                "载体可数单元 = 图表项数，应为 42（42 个输出单元各有 1 个 data-c 单元）");

        byte[] htmlBytes = Files.readAllBytes(result.directory().resolve("report.html"));
        HtmlTextExtractor html = new HtmlTextExtractor(htmlBytes);

        assertTrue(html.startsWithHtmlTag(), "HTML 载体缺少 <html 起始（对应 PDF 的 %PDF 头）");
        assertEquals(0, html.scriptLiterals().size(), "产物不得含 <script>（HC-3 不得有网络发起能力）");
        assertTrue(html.networkCapabilities().isEmpty(),
                "产物含网络发起能力：" + html.networkCapabilities());
        assertTrue(html.externalReferences().isEmpty(),
                "产物引用了外部资源（离线自足被破坏）：" + html.externalReferences());
        assertFalse(html.hasBom(), "UTF-8 不得带 BOM（HIG-FILE-02）");
        byte[] font = html.embeddedFontBytes();
        assertNotNull(font, "@font-face 必须给出 data: 内联字体资产（对应 PDF 的 /FontFile2）");
        assertTrue(font.length > 10_000, "内联字体子集字节数异常：" + font.length);
        assertEquals(0x00010000, ((font[0] & 0xFF) << 24) | ((font[1] & 0xFF) << 16)
                | ((font[2] & 0xFF) << 8) | (font[3] & 0xFF), "内联字体必须是 sfnt（TrueType）魔数");
        assertFalse(html.usesLocalFontSource(), "不得用 local() 作字体主路径（那等于未嵌入）");
        assertEquals(1, html.countTag("h1"), "文档恰 1 个 <h1>");
        assertEquals(6, html.countTag("h2"), "文档恰 6 个 <h2>（编号章节）");
        assertEquals(ReportDocument.REQUIRED_SECTIONS, html.tagTexts("h2"),
                "<h2> 文本必须依次等于 6 个章节标题");
        String text = html.text();
        for (String section : ReportDocument.REQUIRED_SECTIONS) {
            assertTrue(text.contains(section), "HTML 文本层缺少章节标题：" + section);
        }
        assertEquals(0, HtmlTextExtractor.zeroWidthCount(text),
                "可见文本含零宽字符（「所见 ≠ 所复制」）");
        assertEquals(0, HtmlTextExtractor.bidiControlCount(text), "可见文本含双向控制字符");
        System.out.println("HTML 文本层字符数 = " + text.length()
                + "，含 6 节标题 = " + ReportDocument.REQUIRED_SECTIONS.stream().allMatch(text::contains)
                + "，内联字体字节 = " + font.length
                + "，外链命中 = " + html.externalReferences().size());

        String md = Files.readString(result.directory().resolve("report.md"),
                java.nio.charset.StandardCharsets.UTF_8);
        int last = -1;
        for (String section : ReportDocument.REQUIRED_SECTIONS) {
            int idx = md.indexOf("## " + section);
            assertTrue(idx > 0, "Markdown 缺少章节：" + section);
            assertTrue(idx > last, "Markdown 章节顺序错误：" + section);
            last = idx;
        }

        java.util.Set<String> mdIds = idsIn(md, "\\b(F\\d+|E\\d{3}|C\\d{2}|A\\d{2})\\b");
        java.util.Set<String> htmlIds = html.ids("\\b(F\\d+|E\\d{3}|C\\d{2}|A\\d{2})\\b");
        java.util.Set<String> diff = new java.util.TreeSet<>(mdIds);
        diff.removeAll(htmlIds);
        assertTrue(diff.isEmpty(), "HTML 与 Markdown 的编号差集非空：" + diff);
        System.out.println("编号集合（载体一致）= " + new java.util.TreeSet<>(mdIds));

        String analysisJson = Files.readString(result.directory().resolve("analysis.json"),
                java.nio.charset.StandardCharsets.UTF_8);
        var analysis = ExportPipeline.readAnalysis(analysisJson);
        assertNotNull(analysis.get("metrics"));
        var metrics = (com.octant.pipeline.json.Json.JsonArray) analysis.get("metrics");
        assertEquals(ExportPipeline.suppressedCount(in.report()),
                in.report().metrics().stream().filter(m -> m.isSuppressedLike()).count(),
                "抑制计数自洽");
        System.out.println("analysis.json metrics 项数 = " + metrics.size());

        String jsonl = Files.readString(result.directory().resolve("events.anonymized.jsonl"),
                java.nio.charset.StandardCharsets.UTF_8);
        long jsonlLines = jsonl.lines().filter(l -> !l.isBlank()).count();
        assertTrue(jsonlLines > 100, "脱敏事件流过短：" + jsonlLines);
        assertTrue(jsonl.contains("playerPseudonym"), "脱敏事件流必须带每导出假名");
        System.out.println("events.anonymized.jsonl 行数 = " + jsonlLines);
        System.out.println("== 取证结束 ==");
    }

    private static java.util.Set<String> idsIn(String text, String regex) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        var m = java.util.regex.Pattern.compile(regex).matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
