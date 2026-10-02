package com.octant.pipeline.report;

import com.octant.pipeline.PdfTextExtractor;
import com.octant.pipeline.RealCaptureCorpus;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.report.font.FontPalette;
import com.octant.pipeline.report.font.ReportFontResolver;
import com.octant.pipeline.report.font.TrueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FontMappingConsistencyTest {

    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF]");

    @Test
    @DisplayName("/ToUnicode 与 CIDToGIDMap 必须与嵌入字体的 cmap **逐字符**一致")
    void cidMappingIsConsistentPerCharacter() {
        byte[] pdf = render();
        Map<Integer, String> toUnicode = toUnicodeMap(pdf);
        Map<Integer, Integer> cidToGid = cidToGidMap(pdf);
        ReportFontResolver.Resolved resolved = ReportFontResolver
                .resolve(textOf(pdf)).orElseThrow();
        TrueType font = resolved.font();

        System.out.println("== CID 机制逐字符一致性 ==");
        System.out.println("/ToUnicode 条目 = " + toUnicode.size()
                + " ; /CIDToGIDMap 条目 = " + cidToGid.size()
                + " ; 嵌入字体 glyphs = " + font.numGlyphs());

        List<String> badUnicode = new ArrayList<>();
        List<String> badGid = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<Integer, Integer> e : cidToGid.entrySet()) {
            int cid = e.getKey();
            int gid = e.getValue();
            if (gid == 0) {
                continue;
            }
            checked++;
            String mapped = toUnicode.get(cid);
            if (mapped == null || mapped.codePointAt(0) != cid) {
                badUnicode.add(String.format("CID U+%04X -> ToUnicode %s", cid,
                        mapped == null ? "(缺失)" : String.format("U+%04X", mapped.codePointAt(0))));
            }
            int expected = font.glyphFor(cid);
            if (expected == 0 || expected != gid) {
                badGid.add(String.format("CID U+%04X -> gid %d，字体 cmap 说 %d", cid, gid, expected));
            }
        }
        System.out.println("检查的非零 CID 条目 = " + checked + " ; 不一致 = "
                + badUnicode.size() + " + " + badGid.size());
        assertTrue(checked > 100, "检查的条目过少（" + checked + "），这条断言会变成空转");
        assertEquals(List.of(), badUnicode, "/ToUnicode 与码点不一致");
        assertEquals(List.of(), badGid, "/CIDToGIDMap 与嵌入字体 cmap 不一致");
    }

    @Test
    @DisplayName("文本层往返：PDF 提取出的中文串必须能在 report.md 里逐字找到（不是'能解出字'而是'解出原字'）")
    void textLayerRoundTripsToOriginalCharacters() {
        byte[] pdf = render();
        String text = new PdfTextExtractor(pdf).text();
        String md = markdown();
        assertFalse(text.isEmpty(), "文本层不得为空");
        String probe = "存档内行为洞察报告";
        assertTrue(text.contains(probe),
                "文本层必须还原原字符；提取片段 = " + text.substring(0, Math.min(60, text.length())));
        Set<Integer> inMd = new LinkedHashSet<>();
        md.codePoints().forEach(inMd::add);
        Set<Integer> alien = new LinkedHashSet<>();
        Matcher m = CJK.matcher(text);
        while (m.find()) {
            int cp = m.group().codePointAt(0);
            if (!inMd.contains(cp)) {
                alien.add(cp);
            }
        }
        System.out.println("文本层长度 = " + text.length() + " ; md 非 ASCII 字符 = " + inMd.size()
                + " ; 文本层中 md 没有的 CJK 字符 = " + alien.size());
        assertEquals(Set.of(), alien, "文本层出现了 report.md 里没有的字符 ⇒ CID/ToUnicode 映射不一致");
    }

    private static byte[] render() {
        ExportPipeline.Input in = RealCaptureCorpus.input();
        var meta = new ReportDocument.ExportMeta("20260926-150000-5a17e5", "2026-09-26",
                "2026-09-26T15:00Z", "0.1.0", "1.20.1", "forge", "HIG-1.2",
                com.octant.pipeline.export.ExportVersions.privacySpecVersion(),
                List.of("C1"), List.of("flags"), List.of("excl"), "notice", List.of("minecraft"));
        return PdfReport.render(in.report(), meta);
    }

    private static String markdown() {
        ExportPipeline.Input in = RealCaptureCorpus.input();
        var meta = new ReportDocument.ExportMeta("20260926-150000-5a17e5", "2026-09-26",
                "2026-09-26T15:00Z", "0.1.0", "1.20.1", "forge", "HIG-1.2",
                com.octant.pipeline.export.ExportVersions.privacySpecVersion(),
                List.of("C1"), List.of("flags"), List.of("excl"), "notice", List.of("minecraft"));
        return new ReportDocument(in.report(), meta).markdown();
    }

    private static String textOf(byte[] pdf) {
        return new PdfTextExtractor(pdf).text();
    }

    static Map<Integer, String> toUnicodeMap(byte[] pdf) {
        Map<Integer, String> out = new LinkedHashMap<>();
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        Matcher perChar = Pattern.compile("beginbfchar(.*?)endbfchar", Pattern.DOTALL).matcher(s);
        while (perChar.find()) {
            Matcher pair = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
                    .matcher(perChar.group(1));
            while (pair.find()) {
                out.put(Integer.parseInt(pair.group(1), 16), decodeUtf16Be(pair.group(2)));
            }
        }
        Matcher perRange = Pattern.compile("beginbfrange(.*?)endbfrange", Pattern.DOTALL).matcher(s);
        while (perRange.find()) {
            Matcher triple = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
                    .matcher(perRange.group(1));
            while (triple.find()) {
                int lo = Integer.parseInt(triple.group(1), 16);
                int hi = Integer.parseInt(triple.group(2), 16);
                int dst = Integer.parseInt(triple.group(3), 16);
                for (int c = lo; c <= hi; c++) {
                    out.put(c, new String(Character.toChars(dst + (c - lo))));
                }
            }
        }
        return out;
    }

    static Map<Integer, Integer> cidToGidMap(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        Matcher ref = Pattern.compile("/CIDToGIDMap\\s+(\\d+)\\s+0\\s+R").matcher(s);
        if (!ref.find()) {
            return Map.of();
        }
        int num = Integer.parseInt(ref.group(1));
        Matcher obj = Pattern.compile("(?m)^" + num + " 0 obj(.*?)endobj", Pattern.DOTALL).matcher(s);
        if (!obj.find()) {
            return Map.of();
        }
        String body = obj.group(1);
        Matcher stream = Pattern.compile("stream\\r?\\n(.*?)\\r?\\nendstream", Pattern.DOTALL)
                .matcher(body);
        if (!stream.find()) {
            return Map.of();
        }
        byte[] raw = stream.group(1).getBytes(StandardCharsets.ISO_8859_1);
        byte[] data = raw;
        if (body.contains("/FlateDecode")) {
            try (var in = new java.util.zip.InflaterInputStream(
                    new java.io.ByteArrayInputStream(raw))) {
                data = in.readAllBytes();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("CIDToGIDMap 解压失败", e);
            }
        }
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < data.length; i += 2) {
            int gid = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            out.put(i / 2, gid);
        }
        return out;
    }

    private static String decodeUtf16Be(String hex) {
        if (hex == null || hex.isEmpty()) {
            return "";
        }
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return new String(b, StandardCharsets.UTF_16BE);
    }

    static int paletteSize(byte[] pdf) {
        FontPalette p = ReportFontResolver.resolve(textOf(pdf)).orElseThrow().palette();
        return p.entries().size();
    }
}
