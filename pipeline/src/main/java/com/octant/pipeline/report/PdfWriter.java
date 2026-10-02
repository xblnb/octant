package com.octant.pipeline.report;

import com.octant.pipeline.report.font.FontPalette;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PdfWriter {

    public static final double PAGE_WIDTH = 595.0d;
    public static final double PAGE_HEIGHT = 842.0d;
    public static final double MARGIN_LEFT = 56.0d;
    public static final double MARGIN_TOP = 56.0d;
    public static final double MARGIN_BOTTOM = 56.0d;

    public record Glyph(double x, double y, String text, boolean bold, double size) {
    }

    static final String SUBSET_TAG = "MCINSA";

    public record Shape(Kind kind, double x, double y, double w, double h,
                        double r, double g, double b, double lineWidth) {

        public enum Kind {
            FILL_RECT,
            STROKE_LINE
        }

        public String fillRgb() {
            return fmt(r) + " " + fmt(g) + " " + fmt(b) + " rg";
        }

        public String strokeRgb() {
            return fmt(r) + " " + fmt(g) + " " + fmt(b) + " RG";
        }
    }

    private final FontPalette palette;
    private final byte[] subset;
    private final List<List<Glyph>> pages = new ArrayList<>();
    private final List<List<Shape>> pageShapes = new ArrayList<>();
    private final List<Glyph> current = new ArrayList<>();
    private final List<Shape> currentShapes = new ArrayList<>();
    private double y = PAGE_HEIGHT - MARGIN_TOP;

    public PdfWriter(FontPalette palette, byte[] subset) {
        this.palette = palette;
        this.subset = subset;
    }

    public List<Integer> missingIn(String text) {
        return palette.missingCodePoints(text);
    }

    public int pageCount() {
        return pages.size() + (current.isEmpty() ? 0 : 1);
    }

    public int shapeCount() {
        int n = currentShapes.size();
        for (List<Shape> page : pageShapes) {
            n += page.size();
        }
        return n;
    }

    public void line(String text, boolean bold, double size) {
        double lineHeight = size * 1.45d;
        if (y - lineHeight < MARGIN_BOTTOM) {
            newPage();
        }
        y -= lineHeight;
        current.add(new Glyph(MARGIN_LEFT, y, text == null ? "" : text, bold, size));
    }

    public void line(String text) {
        line(text, false, 10.5d);
    }

    public void blank() {
        line("", false, 6.0d);
    }

    public void row(String left, String right) {
        double size = 10.5d;
        double lineHeight = size * 1.45d;
        if (y - lineHeight < MARGIN_BOTTOM) {
            newPage();
        }
        y -= lineHeight;
        String r = right == null ? "" : right;
        double maxRight = (PAGE_WIDTH - 2 * MARGIN_LEFT) * 0.42d;
        if (PdfTextWidth.estimate(r, size) > maxRight) {
            current.add(new Glyph(MARGIN_LEFT, y, left + "  " + r, false, size));
            return;
        }
        current.add(new Glyph(MARGIN_LEFT, y, left == null ? "" : left, false, size));
        double w = PdfTextWidth.estimate(r, size);
        current.add(new Glyph(PAGE_WIDTH - MARGIN_LEFT - w, y, r, false, size));
    }

    public void rule() {
        if (y - 8.0d < MARGIN_BOTTOM) {
            newPage();
        }
        y -= 8.0d;
        int n = (int) ((PAGE_WIDTH - 2 * MARGIN_LEFT) / (10.5d * 0.55d));
        current.add(new Glyph(MARGIN_LEFT, y, "-".repeat(Math.max(4, n)), false, 10.5d));
    }

    public double reserve(double height) {
        if (y - height < MARGIN_BOTTOM) {
            newPage();
        }
        double top = y;
        y -= height;
        return top;
    }

    public void fillRect(double x, double topY, double w, double h,
                         double r, double g, double b) {
        if (w <= 0.0d || h <= 0.0d) {
            return;
        }
        currentShapes.add(new Shape(Shape.Kind.FILL_RECT, x, topY - h, w, h, r, g, b, 0.0d));
    }

    public void strokeLine(double x1, double topY1, double x2, double topY2,
                           double lineWidth, double r, double g, double b) {
        currentShapes.add(new Shape(Shape.Kind.STROKE_LINE, x1, topY1, x2, topY2, r, g, b, lineWidth));
    }

    public void coverageBar(double x, double topY, double totalWidth, double barHeight,
                            double ratio, double[] doneRgb, double[] restRgb) {
        double clamped = Math.max(0.0d, Math.min(1.0d, ratio));
        fillRect(x, topY, totalWidth * clamped, barHeight, doneRgb[0], doneRgb[1], doneRgb[2]);
        fillRect(x + totalWidth * clamped, topY, totalWidth * (1.0d - clamped), barHeight,
                restRgb[0], restRgb[1], restRgb[2]);
    }

    public void horizontalBar(double zeroX, double topY, double maxWidth, double value, double max,
                              double barHeight, double[] rgb) {
        if (max <= 0.0d || value <= 0.0d) {
            return;
        }
        double w = maxWidth * Math.min(1.0d, value / max);
        fillRect(zeroX, topY, w, barHeight, rgb[0], rgb[1], rgb[2]);
    }

    private void newPage() {
        pages.add(new ArrayList<>(current));
        pageShapes.add(new ArrayList<>(currentShapes));
        current.clear();
        currentShapes.clear();
        y = PAGE_HEIGHT - MARGIN_TOP;
    }

    public byte[] toPdf() {
        if (!current.isEmpty() || pageShapes.size() < pages.size() + 1) {
            pages.add(new ArrayList<>(current));
            pageShapes.add(new ArrayList<>(currentShapes));
            current.clear();
            currentShapes.clear();
        }
        int pageCount = pages.size();

        int firstPageObj = 9;
        int firstContentObj = firstPageObj + pageCount;
        List<byte[]> objects = new ArrayList<>();

        objects.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            if (i > 0) {
                kids.append(' ');
            }
            kids.append(firstPageObj + i).append(" 0 R");
        }
        objects.add(ascii("<< /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >>"));
        objects.add(ascii("<< /Type /Font /Subtype /Type0 /BaseFont /" + SUBSET_TAG + "+"
                + palette.familyName()
                + " /Encoding /Identity-H /DescendantFonts [4 0 R] /ToUnicode 7 0 R >>"));
        objects.add(ascii("<< /Type /Font /Subtype /CIDFontType2 /BaseFont /" + SUBSET_TAG + "+"
                + palette.familyName()
                + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >>"
                + " /FontDescriptor 5 0 R /DW 1000 /W " + widthArray()
                + " /CIDToGIDMap 8 0 R >>"));
        objects.add(ascii(fontDescriptor()));
        byte[] subsetCompressed = deflate(subset);
        objects.add(stream("<< /Length " + subsetCompressed.length + " /Length1 " + subset.length
                + " /Filter /FlateDecode >>", subsetCompressed));
        objects.add(toUnicodeStream());
        byte[] gidMap = cidToGid();
        byte[] gidMapCompressed = deflate(gidMap);
        objects.add(stream("<< /Length " + gidMapCompressed.length + " /Filter /FlateDecode >>",
                gidMapCompressed));

        for (int i = 0; i < pageCount; i++) {
            objects.add(ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                    + (int) PAGE_WIDTH + " " + (int) PAGE_HEIGHT
                    + "] /Resources << /Font << /F1 3 0 R >> >> /Contents "
                    + (firstContentObj + i) + " 0 R >>"));
        }
        for (int i = 0; i < pages.size(); i++) {
            byte[] content = contentStream(pages.get(i), pageShapes.get(i));
            objects.add(stream("<< /Length " + content.length + " >>", content));
        }
        return assemble(objects);
    }

    private String fontDescriptor() {
        return "<< /Type /FontDescriptor /FontName /" + SUBSET_TAG + "+"
                + palette.familyName() + " /Flags 4"
                + " /FontBBox [0 -200 1000 900]"
                + " /ItalicAngle 0 /Ascent 880 /Descent -120 /CapHeight 880 /StemV 93"
                + " /FontFile2 6 0 R >>";
    }

    private String widthArray() {
        Map<Integer, Integer> widths = new TreeMap<>();
        for (FontPalette.Entry e : palette.entries().values()) {
            if (e.codePoint() > 0 && e.codePoint() < 65536) {
                widths.put(e.codePoint(), (int) Math.round(e.widthEm() * 1000.0d));
            }
        }
        StringBuilder sb = new StringBuilder("[");
        Integer runStart = null;
        List<Integer> run = new ArrayList<>();
        int previous = Integer.MIN_VALUE;
        for (Map.Entry<Integer, Integer> e : widths.entrySet()) {
            if (runStart != null && e.getKey() == previous + 1) {
                run.add(e.getValue());
                previous = e.getKey();
            } else {
                if (runStart != null) {
                    appendRun(sb, runStart, run);
                }
                runStart = e.getKey();
                run = new ArrayList<>();
                run.add(e.getValue());
                previous = e.getKey();
            }
        }
        if (runStart != null) {
            appendRun(sb, runStart, run);
        }
        sb.append(']');
        return sb.toString();
    }

    private void appendRun(StringBuilder sb, int start, List<Integer> widths) {
        sb.append(start).append(" [");
        for (int i = 0; i < widths.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(widths.get(i));
        }
        sb.append("] ");
    }

    private byte[] cidToGid() {
        int maxCid = 0;
        for (int cp : palette.entries().keySet()) {
            maxCid = Math.max(maxCid, cp);
        }
        byte[] out = new byte[(maxCid + 1) * 2];
        for (FontPalette.Entry e : palette.entries().values()) {
            int cp = e.codePoint();
            if (cp < 0) {
                continue;
            }
            int gid = Math.min(e.glyphId(), 0xFFFF);
            out[cp * 2] = (byte) ((gid >> 8) & 0xFF);
            out[cp * 2 + 1] = (byte) (gid & 0xFF);
        }
        return out;
    }

    private byte[] toUnicodeStream() {
        List<Integer> cids = new ArrayList<>(new TreeMap<>(palette.entries()).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n");
        sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n");
        sb.append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n");
        sb.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        int chunk = 100;
        for (int i = 0; i < cids.size(); i += chunk) {
            List<Integer> slice = cids.subList(i, Math.min(cids.size(), i + chunk));
            sb.append(slice.size()).append(" beginbfchar\n");
            for (int cp : slice) {
                sb.append(String.format("<%04X> <%04X>%n", cp, cp));
            }
            sb.append("endbfchar\n");
        }
        sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        return ascii(sb.toString());
    }

    private byte[] contentStream(List<Glyph> glyphs, List<Shape> shapes) {
        StringBuilder sb = new StringBuilder();
        for (Shape s : shapes) {
            switch (s.kind()) {
                case FILL_RECT -> sb.append(s.fillRgb()).append(' ')
                        .append(fmt(s.x())).append(' ').append(fmt(s.y())).append(' ')
                        .append(fmt(s.w())).append(' ').append(fmt(s.h())).append(" re f\n");
                case STROKE_LINE -> {
                    double y1 = PAGE_HEIGHT - s.y();
                    double y2 = PAGE_HEIGHT - s.h();
                    sb.append(s.strokeRgb()).append(' ')
                            .append(fmt(Math.max(0.2d, s.lineWidth()))).append(" w ")
                            .append(fmt(s.x())).append(' ').append(fmt(y1)).append(" m ")
                            .append(fmt(s.w())).append(' ').append(fmt(y2)).append(" l S\n");
                }
            }
        }
        for (Glyph g : glyphs) {
            if (g.text().isEmpty()) {
                continue;
            }
            List<Integer> unavailable = new ArrayList<>();
            for (int i = 0; i < g.text().length(); ) {
                int cp = g.text().codePointAt(i);
                i += Character.charCount(cp);
                if (FontPalette.isIgnorable(cp)) {
                    continue;
                }
                if (palette.entry(cp).isEmpty() || cp > 0xFFFF) {
                    unavailable.add(cp);
                }
            }
            if (!unavailable.isEmpty()) {
                throw new RenderFailure(RenderFailure.SOURCE_UNAVAILABLE,
                        "字体 " + palette.sourcePath() + " 无法渲染本行文本："
                                + RenderFailure.describe(unavailable, 12)
                                + "（字形缺失或超出 BMP）。契约要求显式失败而不是印成空白/tofu"
                                + "（font/README.md §5.2）——请补齐字体子集或改写文案。",
                        unavailable);
            }
            sb.append("BT /F1 ").append(fmt(g.size())).append(" Tf ")
                    .append(fmt(g.x())).append(' ').append(fmt(g.y())).append(" Td <");
            for (int i = 0; i < g.text().length(); ) {
                int cp = g.text().codePointAt(i);
                i += Character.charCount(cp);
                if (FontPalette.isIgnorable(cp)) {
                    continue;
                }
                sb.append(String.format("%04X", cp));
            }
            sb.append("> Tj ET\n");
        }
        return ascii(sb.toString());
    }

    private byte[] assemble(List<byte[]> objects) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBytes(out, ascii("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n"));
        int[] offsets = new int[objects.size() + 1];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = out.size();
            writeBytes(out, ascii((i + 1) + " 0 obj\n"));
            writeBytes(out, objects.get(i));
            writeBytes(out, ascii("\nendobj\n"));
        }
        int xrefPos = out.size();
        StringBuilder xref = new StringBuilder("xref\n0 ").append(objects.size() + 1).append('\n');
        xref.append("0000000000 65535 f \n");
        for (int i = 1; i <= objects.size(); i++) {
            xref.append(String.format("%010d 00000 n \n", offsets[i]));
        }
        xref.append("trailer\n<< /Size ").append(objects.size() + 1)
                .append(" /Root 1 0 R >>\nstartxref\n").append(xrefPos).append("\n%%EOF\n");
        writeBytes(out, ascii(xref.toString()));
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(
                java.util.zip.Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] stream(String dict, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBytes(out, ascii(dict + "\nstream\n"));
        writeBytes(out, content);
        writeBytes(out, ascii("\nendstream"));
        return out.toByteArray();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    public FontPalette palette() {
        return palette;
    }
}
