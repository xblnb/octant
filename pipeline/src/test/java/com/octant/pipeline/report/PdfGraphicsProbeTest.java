package com.octant.pipeline.report;

import com.octant.pipeline.RealCaptureCorpus;
import com.octant.pipeline.export.ExportPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfGraphicsProbeTest {

    private static final List<String> GRAPHICS_OPS = List.of(
            "re", "m", "l", "c", "v", "y", "h", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*",
            "rg", "RG", "g", "G", "k", "K", "sc", "scn", "w", "J", "j", "d", "gs", "sh", "Do");

    @Test
    @DisplayName("真实报告：内容流里必须真有路径/描绘/设色算子（不是字节里『出现过』）")
    void exportedReportPaintsSomething() {
        byte[] pdf = exportPdf();
        Map<Integer, String> streams = contentStreams(pdf);
        Map<String, Integer> ops = new LinkedHashMap<>();
        int pagesWithGraphics = 0;
        for (Map.Entry<Integer, String> e : streams.entrySet()) {
            int draw = countOps(e.getValue(), List.of("re", "m", "l", "c", "S", "f"))
                    + countOps(e.getValue(), List.of("rg", "RG"));
            if (draw > 0) {
                pagesWithGraphics++;
            }
            for (String op : GRAPHICS_OPS) {
                ops.merge(op, countOp(e.getValue(), op), Integer::sum);
            }
        }
        System.out.println("== 内容流图形算子（逐页解压后统计）==");
        System.out.println("pdf bytes = " + pdf.length + "   content streams = " + streams.size());
        System.out.println("每页真实算子 = " + ops);
        System.out.println("含图形的页 = " + pagesWithGraphics + " / " + streams.size());

        int pathConstruct = countOpsAll(streams, List.of("re", "m", "l", "c"));
        int paint = countOpsAll(streams, List.of("f", "F", "f*", "S", "s", "B", "B*"));
        int colour = countOpsAll(streams, List.of("rg", "RG", "g", "G", "k", "K", "scn"));
        System.out.println("path 构造点 = " + pathConstruct + " ; paint 算子 = " + paint
                + " ; 颜色算子 = " + colour);

        assertTrue(pathConstruct > 0, "必须写入路径构造算子（re/m/l/c）");
        assertTrue(paint > 0, "必须写入描绘算子（f/S）");
        assertTrue(colour > 0, "必须写入设色算子（rg/RG）");
        assertTrue(pagesWithGraphics > 0, "至少要有一页真的画了东西");
    }

    @Test
    @DisplayName("反向对照：纯文字 PDF（同一写入器、只写文本）必须判为**无图形**")
    void textOnlyPdfIsDetectedAsGraphicless() {
        String title = "存档内行为洞察报告";
        String body = "只有文字，没有任何图形原语";
        var resolved = com.octant.pipeline.report.font.ReportFontResolver
                .resolve(title + body + "0123456789%").orElseThrow();
        PdfWriter writer = new PdfWriter(resolved.palette(), resolved.subset());
        writer.line(title, true, 17.0d);
        writer.line(body, false, 10.5d);
        byte[] textOnly = writer.toPdf();

        Map<Integer, String> streams = contentStreams(textOnly);
        int pathConstruct = countOpsAll(streams, List.of("re", "m", "l", "c"));
        int paint = countOpsAll(streams, List.of("f", "F", "f*", "S", "B", "B*"));
        int colour = countOpsAll(streams, List.of("rg", "RG", "g", "G", "k", "K", "scn"));
        System.out.println("== 反向对照（纯文字）== streams=" + streams.size()
                + " path=" + pathConstruct + " paint=" + paint + " colour=" + colour);
        assertEquals(0, pathConstruct + paint + colour,
                "纯文字文档必须判为无图形 —— 否则计数器是在字节上撒谎（旧 S7 的假通过就是这么来的）");
    }

    @Test
    @DisplayName("正向对照：含 re+f+m/l+S 的最小 PDF（压缩与未压缩两种内容流）都必须判为**有图形**")
    void minimalGraphicsPdfIsDetectedInBothEncodings() {
        String content = "1 0 0 RG 0.2 0.4 0.7 rg 2 w\n"
                + "56 700 200 12 re f\n"
                + "56 660 m 300 660 l S\n";
        byte[] plain = minimalPdf(content.getBytes(StandardCharsets.ISO_8859_1), false);
        byte[] deflated = minimalPdf(content.getBytes(StandardCharsets.ISO_8859_1), true);

        for (var entry : List.of(Map.entry("uncompressed", plain), Map.entry("FlateDecode", deflated))) {
            Map<Integer, String> streams = contentStreams(entry.getValue());
            int pathConstruct = countOpsAll(streams, List.of("re", "m", "l", "c"));
            int paint = countOpsAll(streams, List.of("f", "F", "f*", "S", "B", "B*"));
            int colour = countOpsAll(streams, List.of("rg", "RG"));
            System.out.println("正向对照[" + entry.getKey() + "] streams=" + streams.size()
                    + " path=" + pathConstruct + " paint=" + paint + " colour=" + colour);
            assertEquals(1, streams.size(), entry.getKey() + "：必须解析出 1 个内容流");
            assertTrue(pathConstruct > 0 && paint > 0 && colour > 0,
                    entry.getKey() + "：含 re+f+m/l+S 的 PDF 必须被判为有图形");
        }
    }

    @Test
    @DisplayName("取样对象纪律：解析不出内容流必须显式失败，而不是静默返回 0")
    void missingContentStreamsFailLoudly() {
        byte[] noPage = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> contentStreams(noPage),
                "没有内容流时必须抛异常：静默返回空集合会让'没跑'看起来像'通过'");
        assertTrue(ex.getMessage().contains("内容流"), ex.getMessage());
    }

    static Map<Integer, String> contentStreams(byte[] pdf) {
        Map<Integer, Integer> objOffset = new LinkedHashMap<>();
        Pattern objPat = Pattern.compile("(?m)^(\\d+) 0 obj\\b");
        Matcher m = objPat.matcher(new String(pdf, StandardCharsets.ISO_8859_1));
        List<int[]> spans = new ArrayList<>();
        while (m.find()) {
            int num = Integer.parseInt(m.group(1));
            int end = indexOf(pdf, "endobj", m.end());
            spans.add(new int[]{num, m.end(), end < 0 ? pdf.length : end});
            objOffset.put(num, spans.size() - 1);
        }
        Map<Integer, String> out = new LinkedHashMap<>();
        int pageNo = 0;
        for (int[] span : spans) {
            String body = new String(pdf, span[1], span[2] - span[1], StandardCharsets.ISO_8859_1);
            if (!Pattern.compile("/Type\\s*/Page(?![s])").matcher(body).find()) {
                continue;
            }
            pageNo++;
            StringBuilder content = new StringBuilder();
            Matcher arr = Pattern.compile("/Contents\\s*\\[([^\\]]*)\\]").matcher(body);
            Matcher single = Pattern.compile("/Contents\\s+(\\d+)\\s+0\\s+R").matcher(body);
            List<Integer> refs = new ArrayList<>();
            if (arr.find()) {
                Matcher ref = Pattern.compile("(\\d+)\\s+0\\s+R").matcher(arr.group(1));
                while (ref.find()) {
                    refs.add(Integer.parseInt(ref.group(1)));
                }
            } else if (single.find()) {
                refs.add(Integer.parseInt(single.group(1)));
            }
            for (int ref : refs) {
                Integer idx = objOffset.get(ref);
                if (idx == null) {
                    continue;
                }
                int[] s = spans.get(idx);
                String obj = new String(pdf, s[1], s[2] - s[1], StandardCharsets.ISO_8859_1);
                content.append(decodeStream(obj));
            }
            if (content.length() > 0) {
                out.put(pageNo, content.toString());
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("取样失败：没有解析出任何内容流（"
                    + pdf.length + " 字节，页面对象 " + pageNo + " 个）。"
                    + "门禁必须声明取样对象；解析不出内容流时禁止退回'在原始字节上匹配'。");
        }
        return out;
    }

    private static String decodeStream(String objBody) {
        Matcher sm = Pattern.compile("stream\\r?\\n(.*?)\\r?\\nendstream",
                Pattern.DOTALL).matcher(objBody);
        if (!sm.find()) {
            return "";
        }
        byte[] raw = sm.group(1).getBytes(StandardCharsets.ISO_8859_1);
        if (!objBody.contains("/FlateDecode")) {
            return new String(raw, StandardCharsets.ISO_8859_1);
        }
        try {
            byte[] inflated = inflate(raw);
            return new String(inflated, StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            throw new IllegalStateException("内容流声明了 FlateDecode 但解压失败：" + e, e);
        }
    }

    private static byte[] inflate(byte[] data) {
        try (var in = new java.util.zip.InflaterInputStream(
                new java.io.ByteArrayInputStream(data))) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int indexOf(byte[] data, String needle, int from) {
        String hay = new String(data, StandardCharsets.ISO_8859_1);
        return hay.indexOf(needle, from);
    }

    static int countOp(String content, String op) {
        Pattern p = Pattern.compile("(?<![A-Za-z0-9*'])" + Pattern.quote(op)
                + "(?![A-Za-z0-9*'])");
        int n = 0;
        Matcher m = p.matcher(content);
        while (m.find()) {
            n++;
        }
        return n;
    }

    static int countOps(String content, List<String> ops) {
        int n = 0;
        for (String op : ops) {
            n += countOp(content, op);
        }
        return n;
    }

    static int countOpsAll(Map<Integer, String> streams, List<String> ops) {
        int n = 0;
        for (String s : streams.values()) {
            n += countOps(s, ops);
        }
        return n;
    }

    private static byte[] exportPdf() {
        ExportPipeline.Input in = RealCaptureCorpus.input();
        var meta = new ReportDocument.ExportMeta("20260926-150000-5a17e5", "2026-09-26",
                "2026-09-26T15:00Z", "0.1.0", "1.20.1", "forge", "HIG-1.2",
                com.octant.pipeline.export.ExportVersions.privacySpecVersion(),
                List.of("C1"), List.of("flags"), List.of("excl"), "notice", List.of("minecraft"));
        return PdfReport.render(in.report(), meta);
    }

    private static byte[] minimalPdf(byte[] content, boolean compress) {
        byte[] streamBytes = content;
        String filter = "";
        if (compress) {
            Deflater deflater = new Deflater(9);
            deflater.setInput(content);
            deflater.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                bos.write(buf, 0, deflater.deflate(buf));
            }
            deflater.end();
            streamBytes = bos.toByteArray();
            filter = " /Filter /FlateDecode";
        }
        List<byte[]> objects = new ArrayList<>();
        objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
        objects.add(bytes("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"));
        objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>"));
        objects.add(concat(bytes("<< /Length " + streamBytes.length + filter + " >>\nstream\n"),
                streamBytes, bytes("\nendstream")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, bytes("%PDF-1.4\n"));
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, bytes((i + 1) + " 0 obj\n"));
            write(out, objects.get(i));
            write(out, bytes("\nendobj\n"));
        }
        int xref = out.size();
        write(out, bytes("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n"));
        for (int offset : offsets) {
            write(out, bytes(String.format("%010d 00000 n \n", offset)));
        }
        write(out, bytes("trailer\n<< /Size " + (objects.size() + 1)
                + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n"));
        return out.toByteArray();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            write(out, p);
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }
}
