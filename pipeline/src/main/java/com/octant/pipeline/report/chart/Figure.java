package com.octant.pipeline.report.chart;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Figure {

    public enum Role {
        VALUE("value"),
        REFERENCE("reference"),
        FRAME("frame");

        private final String wire;

        Role(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public enum Effect {
        SCALES("scales"),
        TEXT("text"),
        FIXED("fixed");

        private final String wire;

        Effect(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public record Rect(double x, double y, double w, double h, Color fill, Color stroke,
                       double strokeWidth, Role role, Double dataValue, String label,
                       Effect effect) implements Primitive {
    }

    public record Line(double x1, double y1, double x2, double y2, Color stroke, double strokeWidth,
                       boolean dashed, Role role, Double dataValue, String label,
                       Effect effect) implements Primitive {
    }

    public record Circle(double cx, double cy, double r, Color fill, Color stroke,
                         double strokeWidth, Role role, Double dataValue, String label,
                         Effect effect) implements Primitive {
    }

    public record Polyline(double[] xs, double[] ys, Color stroke, double strokeWidth,
                           Role role, Double dataValue, String label, Effect effect)
            implements Primitive {
    }

    public record Text(double x, double y, String content, Color fill, double fontSize, boolean bold,
                       String anchor, Role role, Double dataValue, String label, Effect effect)
            implements Primitive {
    }

    public interface Primitive {
    }

    public record Point(String label, double value, String display) {

        public Point(String label, double value) {
            this(label, value, null);
        }

        public String shown() {
            return display == null || display.isBlank() ? label : display;
        }
    }

    public record Drawing(int width, int height, List<Primitive> primitives, String unit,
                          double domain, String domainText, List<Double> ticks,
                          List<String> tickLabels, String reading) {

        public long dataPrimitiveCount() {
            return primitives.stream().filter(p -> roleOf(p) != Role.FRAME).count();
        }

        public int seriesPointCount() {
            Map<Double, Boolean> seen = new LinkedHashMap<>();
            for (Primitive p : primitives) {
                Double v = valueOf(p);
                if (roleOf(p) == Role.VALUE && effectOf(p) == Effect.SCALES && v != null) {
                    seen.putIfAbsent(v, Boolean.TRUE);
                }
            }
            return seen.size();
        }

        public int referencePointCount() {
            Map<Double, Boolean> seen = new LinkedHashMap<>();
            for (Primitive p : primitives) {
                Double v = valueOf(p);
                if (roleOf(p) == Role.REFERENCE && effectOf(p) == Effect.SCALES && v != null) {
                    seen.putIfAbsent(v, Boolean.TRUE);
                }
            }
            return seen.size();
        }

        public boolean isComparable() {
            return seriesPointCount() >= 2 || referencePointCount() >= 2;
        }

        public List<Double> dataValues() {
            Map<Double, Boolean> seen = new LinkedHashMap<>();
            for (Primitive p : primitives) {
                Double v = valueOf(p);
                if (roleOf(p) != Role.FRAME && v != null) {
                    seen.putIfAbsent(v, Boolean.TRUE);
                }
            }
            return new ArrayList<>(seen.keySet());
        }

        public boolean dataOnlyInText() {
            boolean textHasValue = false;
            boolean somethingScales = false;
            for (Primitive p : primitives) {
                if (valueOf(p) == null) {
                    continue;
                }
                if (effectOf(p) == Effect.SCALES) {
                    somethingScales = true;
                } else if (effectOf(p) == Effect.TEXT) {
                    textHasValue = true;
                }
            }
            return textHasValue && !somethingScales;
        }

        public boolean hasReading() {
            return reading != null && !reading.isBlank();
        }

        public static Role roleOf(Primitive p) {
            if (p instanceof Circle c) {
                return c.role();
            }
            if (p instanceof Rect r) {
                return r.role();
            }
            if (p instanceof Line l) {
                return l.role();
            }
            if (p instanceof Polyline pl) {
                return pl.role();
            }
            if (p instanceof Text t) {
                return t.role();
            }
            throw new IllegalArgumentException("未知图元类型：" + (p == null ? "null" : p.getClass()));
        }

        public static Double valueOf(Primitive p) {
            if (p instanceof Circle c) {
                return c.dataValue();
            }
            if (p instanceof Rect r) {
                return r.dataValue();
            }
            if (p instanceof Line l) {
                return l.dataValue();
            }
            if (p instanceof Polyline pl) {
                return pl.dataValue();
            }
            if (p instanceof Text t) {
                return t.dataValue();
            }
            return null;
        }

        public static Effect effectOf(Primitive p) {
            if (p instanceof Circle c) {
                return c.effect();
            }
            if (p instanceof Rect r) {
                return r.effect();
            }
            if (p instanceof Line l) {
                return l.effect();
            }
            if (p instanceof Polyline pl) {
                return pl.effect();
            }
            if (p instanceof Text t) {
                return t.effect();
            }
            throw new IllegalArgumentException("未知图元类型：" + (p == null ? "null" : p.getClass()));
        }

        public static String labelOf(Primitive p) {
            if (p instanceof Circle c) {
                return c.label();
            }
            if (p instanceof Rect r) {
                return r.label();
            }
            if (p instanceof Line l) {
                return l.label();
            }
            if (p instanceof Polyline pl) {
                return pl.label();
            }
            if (p instanceof Text t) {
                return t.label();
            }
            return null;
        }
    }

    private Figure() {
    }
}
