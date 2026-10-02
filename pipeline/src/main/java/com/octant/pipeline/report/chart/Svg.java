package com.octant.pipeline.report.chart;

import java.util.ArrayList;
import java.util.List;

public final class Svg {

    private static final String XML_HEADER =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n";

    private final StringBuilder body = new StringBuilder();
    private final List<String> defs = new ArrayList<>();
    private int width;
    private int height;
    private String background = "#ffffff";

    public Svg(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0";
        }
        double r = Math.round(v * 1000.0d) / 1000.0d;
        if (r == 0.0d) {
            return "0";
        }
        boolean lossless = Math.abs(v) < 1.0e6d
                ? (r == v || Math.abs(r - v) <= 1.0e-12d * Math.max(1.0d, Math.abs(v)))
                : false;
        if (!lossless) {
            return Double.toString(v);
        }
        String s = String.format(java.util.Locale.ROOT, "%.3f", r);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    public static String esc(String t) {
        if (t == null) {
            return "";
        }
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static String hex(java.awt.Color c) {
        if (c == null) {
            return "none";
        }
        if (c.getAlpha() == 0) {
            return "none";
        }
        String s = String.format(java.util.Locale.ROOT, "#%02x%02x%02x",
                c.getRed(), c.getGreen(), c.getBlue());
        if (c.getAlpha() < 255) {
            s += String.format(java.util.Locale.ROOT, "%02x", c.getAlpha());
        }
        return s;
    }

    public void size(int w, int h) {
        this.width = w;
        this.height = h;
    }

    public void background(java.awt.Color c) {
        this.background = hex(c);
    }

    public void attr(String name, String value) {
        if (name == null || name.isEmpty()) {
            return;
        }
        body.append(' ').append(name).append("=\"").append(value == null ? "" : value).append('"');
    }

    public void addDef(String def) {
        defs.add(def);
    }

    public void rect(double x, double y, double w, double h, java.awt.Color fill, double strokeWidth,
              java.awt.Color stroke) {
        body.append("<rect x=\"").append(num(x)).append("\" y=\"").append(num(y))
                .append("\" width=\"").append(num(w)).append("\" height=\"").append(num(h)).append('"');
        paint(fill, stroke, strokeWidth, 0.0d);
        body.append("/>\n");
    }

    public void line(double x1, double y1, double x2, double y2, java.awt.Color stroke, double strokeWidth,
              double[] dash) {
        body.append("<line x1=\"").append(num(x1)).append("\" y1=\"").append(num(y1))
                .append("\" x2=\"").append(num(x2)).append("\" y2=\"").append(num(y2)).append('"');
        paint(null, stroke, strokeWidth, 0.0d);
        if (dash != null && dash.length > 0) {
            StringBuilder d = new StringBuilder();
            for (double v : dash) {
                d.append(d.isEmpty() ? "" : ",").append(num(v));
            }
            body.append(" stroke-dasharray=\"").append(d).append('"');
        }
        body.append("/>\n");
    }

    public void polyline(double[] xs, double[] ys, java.awt.Color stroke, double strokeWidth) {
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < xs.length && i < ys.length; i++) {
            pts.append(i == 0 ? "" : " ").append(num(xs[i])).append(',').append(num(ys[i]));
        }
        body.append("<polyline points=\"").append(pts).append('"');
        body.append(" fill=\"none\"");
        paint(null, stroke, strokeWidth, 0.0d);
        body.append("/>\n");
    }

    public void circle(double cx, double cy, double r, java.awt.Color fill, java.awt.Color stroke,
                double strokeWidth) {
        body.append("<circle cx=\"").append(num(cx)).append("\" cy=\"").append(num(cy))
                .append("\" r=\"").append(num(r)).append('"');
        paint(fill, stroke, strokeWidth, 0.0d);
        body.append("/>\n");
    }

    public void path(String d, java.awt.Color fill, java.awt.Color stroke, double strokeWidth) {
        body.append("<path d=\"").append(esc(d)).append('"');
        paint(fill, stroke, strokeWidth, 0.0d);
        body.append("/>\n");
    }

    public void text(double x, double y, String content, java.awt.Color fill, String family,
              double fontSize, int weight, String anchor) {
        if (content == null || content.isEmpty()) {
            return;
        }
        body.append("<text x=\"").append(num(x)).append("\" y=\"").append(num(y)).append('"');
        if (fill != null && fill.getAlpha() != 0) {
            body.append(" fill=\"").append(hex(fill)).append('"');
        }
        if (family != null && !family.isEmpty()) {
            body.append(" font-family=\"").append(esc(family)).append('"');
        }
        body.append(" font-size=\"").append(num(fontSize)).append('"');
        if (weight != 400) {
            body.append(" font-weight=\"").append(weight >= 600 ? "bold" : "normal").append('"');
        }
        if (anchor != null && !"start".equals(anchor)) {
            body.append(" text-anchor=\"").append(anchor).append('"');
        }
        body.append('>').append(esc(content)).append("</text>\n");
    }

    public void textRotated(double x, double y, String content, java.awt.Color fill, String family,
                     double fontSize, int weight, String anchor, double degrees) {
        if (content == null || content.isEmpty()) {
            return;
        }
        body.append("<text x=\"").append(num(x)).append("\" y=\"").append(num(y)).append('"');
        if (fill != null && fill.getAlpha() != 0) {
            body.append(" fill=\"").append(hex(fill)).append('"');
        }
        if (family != null && !family.isEmpty()) {
            body.append(" font-family=\"").append(esc(family)).append('"');
        }
        body.append(" font-size=\"").append(num(fontSize)).append('"');
        if (weight != 400) {
            body.append(" font-weight=\"").append(weight >= 600 ? "bold" : "normal").append('"');
        }
        if (anchor != null && !"start".equals(anchor)) {
            body.append(" text-anchor=\"").append(anchor).append('"');
        }
        if (degrees != 0.0d) {
            body.append(" transform=\"rotate(").append(num(degrees)).append(' ')
                    .append(num(x)).append(' ').append(num(y)).append(")\"");
        }
        body.append('>').append(esc(content)).append("</text>\n");
    }

    private void paint(java.awt.Color fill, java.awt.Color stroke, double strokeWidth,
                       double strokeOpacity) {
        body.append(" fill=\"").append(fill == null ? "none" : hex(fill)).append('"');
        if (stroke != null && stroke.getAlpha() != 0 && strokeWidth > 0.0d) {
            body.append(" stroke=\"").append(hex(stroke)).append('"');
            body.append(" stroke-width=\"").append(num(strokeWidth)).append('"');
        }
    }

    public void textOpacity(double x, double y, String content, java.awt.Color fill, String family,
                     double fontSize, int weight, String anchor, double opacity) {
        if (content == null || content.isEmpty()) {
            return;
        }
        body.append("<text x=\"").append(num(x)).append("\" y=\"").append(num(y)).append('"');
        if (fill != null && fill.getAlpha() != 0) {
            body.append(" fill=\"").append(hex(fill)).append('"');
        }
        if (opacity < 1.0d) {
            body.append(" fill-opacity=\"").append(num(opacity)).append('"');
        }
        if (family != null && !family.isEmpty()) {
            body.append(" font-family=\"").append(esc(family)).append('"');
        }
        body.append(" font-size=\"").append(num(fontSize)).append('"');
        if (weight != 400) {
            body.append(" font-weight=\"").append(weight >= 600 ? "bold" : "normal").append('"');
        }
        if (anchor != null && !"start".equals(anchor)) {
            body.append(" text-anchor=\"").append(anchor).append('"');
        }
        body.append('>').append(esc(content)).append("</text>\n");
    }

    public String toSvg(String ariaLabel, String extraClass) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg role=\"img\"")
                .append(" viewBox=\"0 0 ").append(width).append(' ').append(height).append('"')
                .append(" width=\"100%\" height=\"auto\"")
                .append(" preserveAspectRatio=\"xMidYMid meet\"");
        if (ariaLabel != null && !ariaLabel.isEmpty()) {
            sb.append(" aria-label=\"").append(esc(ariaLabel)).append('"');
        }
        if (extraClass != null && !extraClass.isEmpty()) {
            sb.append(" class=\"").append(esc(extraClass)).append('"');
        }
        sb.append(">\n");
        if (!defs.isEmpty()) {
            sb.append("<defs>\n");
            for (String d : defs) {
                sb.append(d).append('\n');
            }
            sb.append("</defs>\n");
        }
        if (background != null) {
            sb.append("<rect x=\"0\" y=\"0\" width=\"").append(width).append("\" height=\"")
                    .append(height).append("\" fill=\"").append(background).append("\"/>\n");
        }
        sb.append(body);
        sb.append("</svg>");
        return sb.toString();
    }

    public int elementCount(String tag) {
        String needle = "<" + tag;
        int n = 0;
        int i = 0;
        String s = body.toString();
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    public static String xmlHeader() {
        return XML_HEADER;
    }
}
