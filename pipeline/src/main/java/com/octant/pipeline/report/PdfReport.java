package com.octant.pipeline.report;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.report.font.FontPalette;
import com.octant.pipeline.report.font.ReportFontResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PdfReport {

    private PdfReport() {
    }

    public static final class FontUnavailableException extends RenderFailure {
        private static final long serialVersionUID = 1L;

        public FontUnavailableException(String message) {
            this(message, List.of());
        }

        public FontUnavailableException(String message, List<Integer> codePoints) {
            super(RenderFailure.SOURCE_UNAVAILABLE, message, codePoints);
        }
    }

    public record Result(byte[] pdf, String fontPath, String provenance, String familyName,
                         int subsetBytes, int glyphCount, int pages) {
    }

    public static byte[] render(AnalysisReport report, ReportDocument.ExportMeta meta) {
        return renderWithInfo(report, meta).pdf();
    }

    public static Result renderWithInfo(AnalysisReport report, ReportDocument.ExportMeta meta) {
        PdfBoxReport.Result r = PdfBoxReport.render(report, meta);
        return new Result(r.pdf(), r.fontPath(), r.provenance(), r.familyName(),
                r.subsetBytes(), r.glyphCount(), r.pages());
    }

    static Result legacyRenderWithInfo(AnalysisReport report, ReportDocument.ExportMeta meta) {
        List<String> rawLines = new ReportDocument(report, meta).lines();
        StringBuilder all = new StringBuilder();
        for (String raw : rawLines) {
            all.append(typographic(raw)).append('\n');
            all.append(normalize(raw).text()).append('\n');
        }
        all.append("—–…、。（）：；，？");
        String allText = all.toString();

        Optional<ReportFontResolver.Resolved> resolvedOpt = ReportFontResolver.resolve(allText);
        if (resolvedOpt.isEmpty()) {
            throw new FontUnavailableException(
                    "找不到可用的 TrueType 字体来渲染报告（候选见 ReportFontResolver.DEFAULT_CANDIDATES）。"
                            + "拒绝降级为非嵌入字体：那会产出'文本层存在但字符全错'的报告。"
                            + "可用 -D" + ReportFontResolver.FONT_PROPERTY + "=<字体文件> 显式指定。");
        }
        ReportFontResolver.Resolved font = resolvedOpt.get();
        List<Integer> missing = font.palette().missingCodePoints(allText);
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int cp : missing.subList(0, Math.min(20, missing.size()))) {
                sb.append(String.format("U+%04X ", cp));
            }
            throw new FontUnavailableException("字体 " + font.fontFile() + " 缺少报告所需字符（"
                    + missing.size() + " 个）：" + sb + "；继续会让文本层缺字", missing);
        }

        PdfWriter pdf = new PdfWriter(font.palette(), font.subset());
        for (String raw : rawLines) {
            Normalized n = normalize(raw);
            if (n.text().isEmpty()) {
                pdf.blank();
                continue;
            }
            if (n.heading()) {
                pdf.blank();
            }
            for (String piece : PdfTextWidth.wrap(n.text(), n.size(),
                    PdfWriter.PAGE_WIDTH - 2 * PdfWriter.MARGIN_LEFT)) {
                pdf.line(piece, n.bold(), n.size());
            }
            PdfCharts.render(pdf, raw, report);
        }
        byte[] bytes = pdf.toPdf();
        return new Result(bytes, font.fontFile(), font.provenance(), font.familyName(),
                font.subsetBytes(), font.glyphCount(), pdf.pageCount());
    }

    record Normalized(String text, boolean bold, double size, boolean heading) {
    }

    static Normalized normalize(String raw) {
        String line = raw;
        boolean bold = false;
        double size = 10.5d;
        boolean heading = false;
        if (line.startsWith("# ")) {
            line = line.substring(2);
            bold = true;
            size = 17.0d;
            heading = true;
        } else if (line.startsWith("## ")) {
            line = line.substring(3);
            bold = true;
            size = 14.0d;
            heading = true;
        } else if (line.startsWith("### ")) {
            line = line.substring(4);
            bold = true;
            size = 12.0d;
            heading = true;
        } else if (line.startsWith("#### ")) {
            line = line.substring(5);
            bold = true;
            size = 10.5d;
            heading = true;
        } else if (line.startsWith("> ")) {
            line = "  " + line.substring(2);
        } else if (line.startsWith("|")) {
            line = line.replace("|", " ").trim();
        }
        line = stripMarkdown(typographic(line));
        return new Normalized(line, bold, size, heading);
    }

    static String stripMarkdown(String line) {
        String out = line.replace("**", "").replace("`", "");
        if (out.startsWith("- ")) {
            out = "  " + out.substring(2);
        }
        return out;
    }

    public static String typographic(String text) {
        if (text == null) {
            return "";
        }
        return text.indexOf('\u2212') < 0 ? text : text.replace('\u2212', '-');
    }

    public static List<String> textLayerLines(AnalysisReport report, ReportDocument.ExportMeta meta) {
        List<String> out = new ArrayList<>();
        for (String raw : new ReportDocument(report, meta).lines()) {
            String text = normalize(raw).text();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    public static FontPalette paletteOf(String allText) {
        return ReportFontResolver.resolve(allText).orElseThrow(
                () -> new FontUnavailableException("找不到可用字体")).palette();
    }
}
