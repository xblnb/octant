package com.octant.pipeline.report;

public final class PdfTextWidth {

    private PdfTextWidth() {
    }

    public static double estimate(String text, double fontSize) {
        if (text == null) {
            return 0.0d;
        }
        double em = 0.0d;
        for (int i = 0; i < text.length(); i++) {
            em += isWide(text.charAt(i)) ? 1.0d : 0.55d;
        }
        return em * fontSize;
    }

    public static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0x303E)
                || (c >= 0x3041 && c <= 0x33FF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0xA000 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    public static java.util.List<String> wrap(String text, double fontSize, double maxWidth) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder line = new StringBuilder();
        double width = 0.0d;
        int lastBreak = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            double w = (isWide(c) ? 1.0d : 0.55d) * fontSize;
            if (width + w > maxWidth && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                width = 0.0d;
            }
            line.append(c);
            width += w;
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }
}
