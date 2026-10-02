package com.octant.pipeline.json;

import java.math.BigDecimal;
import java.util.Map;

public final class JsonWriter {

    private JsonWriter() {
    }

    public static String compact(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, false, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, true, 0);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb, boolean pretty, int depth) {
        if (value == null || value == Json.MISSING) {
            throw new IllegalArgumentException("JSON 输出中不允许 null（应省略该键）");
        }
        if (value instanceof Json.JsonObject o) {
            writeObject(o, sb, pretty, depth);
        } else if (value instanceof Json.JsonArray a) {
            writeArray(a, sb, pretty, depth);
        } else if (value instanceof String s) {
            writeString(s, sb);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON 不得承载 NaN/Infinity");
            }
            sb.append(number(BigDecimal.valueOf(d)));
        } else if (value instanceof Number n) {
            sb.append(n.longValue());
        } else {
            throw new IllegalArgumentException("不支持的 JSON 值类型：" + value.getClass().getName());
        }
    }

    private static void writeObject(Json.JsonObject o, StringBuilder sb, boolean pretty, int depth) {
        Map<String, Object> members = o.members();
        if (members.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : members.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (pretty) {
                sb.append('\n');
                indent(sb, depth + 1);
            }
            writeString(e.getKey(), sb);
            sb.append(':');
            if (pretty) {
                sb.append(' ');
            }
            write(e.getValue(), sb, pretty, depth + 1);
        }
        if (pretty) {
            sb.append('\n');
            indent(sb, depth);
        }
        sb.append('}');
    }

    private static void writeArray(Json.JsonArray a, StringBuilder sb, boolean pretty, int depth) {
        if (a.size() == 0) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        for (int i = 0; i < a.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            if (pretty) {
                sb.append('\n');
                indent(sb, depth + 1);
            }
            write(a.get(i), sb, pretty, depth + 1);
        }
        if (pretty) {
            sb.append('\n');
            indent(sb, depth);
        }
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    private static String number(BigDecimal bd) {
        BigDecimal stripped = bd.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
