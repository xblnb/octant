package com.octant.pipeline.report.chart;

import java.util.List;

public final class FigureSvg {

    private FigureSvg() {
    }

    public static String toSvg(Figure.Drawing drawing, String chartId, String ariaLabel) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<svg role=\"img\" viewBox=\"0 0 ").append(drawing.width()).append(' ')
                .append(drawing.height()).append('"')
                .append(" width=\"100%\" height=\"auto\" preserveAspectRatio=\"xMidYMid meet\"");
        if (ariaLabel != null && !ariaLabel.isEmpty()) {
            sb.append(" aria-label=\"").append(Svg.esc(ariaLabel)).append('"');
        }
        sb.append(" class=\"chart-svg\" data-chart=\"").append(Svg.esc(chartId)).append('"')
                .append(" data-domain=\"").append(Svg.esc(FigureGeometry.fmt(drawing.domain())))
                .append("\">\n");
        sb.append("<rect x=\"0\" y=\"0\" width=\"").append(drawing.width()).append("\" height=\"")
                .append(drawing.height()).append("\" fill=\"#ffffff\" data-role=\"frame\"/>\n");
        for (Figure.Primitive p : drawing.primitives()) {
            sb.append(element(p));
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private static String element(Figure.Primitive p) {
        StringBuilder b = new StringBuilder(160);
        if (p instanceof Figure.Rect r) {
            b.append("<rect x=\"").append(Svg.num(r.x())).append("\" y=\"").append(Svg.num(r.y()))
                    .append("\" width=\"").append(Svg.num(r.w())).append("\" height=\"")
                    .append(Svg.num(r.h())).append('"');
            b.append(" fill=\"").append(r.fill() == null ? "none" : Svg.hex(r.fill())).append('"');
            if (r.stroke() != null && r.strokeWidth() > 0.0d) {
                b.append(" stroke=\"").append(Svg.hex(r.stroke())).append("\" stroke-width=\"")
                        .append(Svg.num(r.strokeWidth())).append('"');
            }
            identity(b, r);
            b.append("/>\n");
        } else if (p instanceof Figure.Line l) {
            b.append("<line x1=\"").append(Svg.num(l.x1())).append("\" y1=\"")
                    .append(Svg.num(l.y1())).append("\" x2=\"").append(Svg.num(l.x2()))
                    .append("\" y2=\"").append(Svg.num(l.y2())).append('"')
                    .append(" fill=\"none\" stroke=\"")
                    .append(l.stroke() == null ? "none" : Svg.hex(l.stroke())).append("\"")
                    .append(" stroke-width=\"").append(Svg.num(l.strokeWidth())).append('"');
            if (l.dashed()) {
                b.append(" stroke-dasharray=\"4,4\"");
            }
            identity(b, l);
            b.append("/>\n");
        } else if (p instanceof Figure.Circle c) {
            b.append("<circle cx=\"").append(Svg.num(c.cx())).append("\" cy=\"")
                    .append(Svg.num(c.cy())).append("\" r=\"").append(Svg.num(c.r()))
                    .append('"');
            b.append(" fill=\"").append(c.fill() == null ? "none" : Svg.hex(c.fill()))
                    .append('"');
            if (c.stroke() != null && c.strokeWidth() > 0.0d) {
                b.append(" stroke=\"").append(Svg.hex(c.stroke()))
                        .append("\" stroke-width=\"").append(Svg.num(c.strokeWidth()))
                        .append('"');
            }
            identity(b, c);
            b.append("/>\n");
        } else if (p instanceof Figure.Polyline pl) {
            b.append("<polyline points=\"");
            for (int i = 0; i < pl.xs().length && i < pl.ys().length; i++) {
                b.append(i == 0 ? "" : " ").append(Svg.num(pl.xs()[i])).append(',')
                        .append(Svg.num(pl.ys()[i]));
            }
            b.append("\" fill=\"none\" stroke=\"")
                    .append(pl.stroke() == null ? "none" : Svg.hex(pl.stroke())).append("\"")
                    .append(" stroke-width=\"").append(Svg.num(pl.strokeWidth())).append('"');
            identity(b, pl);
            b.append("/>\n");
        } else if (p instanceof Figure.Text t) {
            if (t.content() == null || t.content().isEmpty()) {
                return "";
            }
            b.append("<text x=\"").append(Svg.num(t.x())).append("\" y=\"").append(Svg.num(t.y()))
                    .append('"');
            if (t.fill() != null) {
                b.append(" fill=\"").append(Svg.hex(t.fill())).append('"');
            }
            b.append(" font-size=\"").append(Svg.num(t.fontSize())).append('"');
            if (t.bold()) {
                b.append(" font-weight=\"bold\"");
            }
            if (t.anchor() != null && !"start".equals(t.anchor())) {
                b.append(" text-anchor=\"").append(Svg.esc(t.anchor())).append('"');
            }
            identity(b, t);
            b.append('>').append(Svg.esc(t.content())).append("</text>\n");
        } else {
            throw new IllegalArgumentException("未知图元类型：" + p.getClass().getName()
                    + "（编码器不得静默跳过图元）");
        }
        return b.toString();
    }

    private static void identity(StringBuilder b, Figure.Primitive p) {
        Figure.Role role = Figure.Drawing.roleOf(p);
        Figure.Effect effect = Figure.Drawing.effectOf(p);
        Double value = Figure.Drawing.valueOf(p);
        String label = Figure.Drawing.labelOf(p);
        b.append(" data-role=\"").append(role == null ? "frame" : role.wire()).append('"');
        b.append(" data-effect=\"")
                .append(effect == null ? "fixed" : effect.wire()).append('"');
        if (value != null && !value.isNaN()) {
            b.append(" data-value=\"").append(Double.toString(value)).append('"');
        }
        if (label != null && !label.isEmpty()) {
            b.append(" data-label=\"").append(Svg.esc(label)).append('"');
        }
    }

    public static long dataPrimitiveCount(Figure.Drawing drawing) {
        return drawing.dataPrimitiveCount();
    }

    public static List<Double> dataValues(Figure.Drawing drawing) {
        return drawing.dataValues();
    }
}
