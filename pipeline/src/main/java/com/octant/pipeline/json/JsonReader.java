package com.octant.pipeline.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonReader {

    private final String src;
    private int pos;

    private JsonReader(String src) {
        this.src = src;
        this.pos = 0;
    }

    public static Object parse(String text) {
        JsonReader r = new JsonReader(text == null ? "" : text);
        r.skipWs();
        Object v = r.value();
        r.skipWs();
        if (r.pos != r.src.length()) {
            throw r.err("JSON 尾部存在多余内容");
        }
        return v;
    }

    public static Json.JsonObject parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Json.JsonObject o)) {
            throw new IllegalArgumentException("期望 JSON 对象，实际：" + (v == null ? "null" : v.getClass()));
        }
        return o;
    }

    private Object value() {
        if (pos >= src.length()) {
            throw err("JSON 意外结束");
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", Json.MISSING);
            default -> number();
        };
    }

    private Object literal(String text, Object v) {
        if (!src.startsWith(text, pos)) {
            throw err("非法字面量");
        }
        pos += text.length();
        return v;
    }

    private Json.JsonObject object() {
        expect('{');
        Json.JsonObject o = new Json.JsonObject();
        skipWs();
        if (peek() == '}') {
            pos++;
            return o;
        }
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            expect(':');
            skipWs();
            Object v = value();
            if (o.has(key)) {
                throw err("重复键：" + key);
            }
            o.put(key, v);
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            expect('}');
            return o;
        }
    }

    private Json.JsonArray array() {
        expect('[');
        Json.JsonArray a = new Json.JsonArray();
        skipWs();
        if (peek() == ']') {
            pos++;
            return a;
        }
        while (true) {
            skipWs();
            a.add(value());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            expect(']');
            return a;
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw err("字符串未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                if (c < 0x20) {
                    throw err("字符串中出现未转义控制字符");
                }
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw err("转义序列未闭合");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw err("\\u 转义不完整");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw err("非法转义 \\" + e);
            }
        }
    }

    private Object number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        boolean fractional = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                fractional = fractional || c == '.' || c == 'e' || c == 'E';
                pos++;
            } else {
                break;
            }
        }
        String text = src.substring(start, pos);
        if (text.isEmpty() || "-".equals(text)) {
            throw err("非法数值");
        }
        try {
            if (fractional) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException nfe) {
            throw err("非法数值：" + text);
        }
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw err("JSON 意外结束");
        }
        return src.charAt(pos);
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw err("期望 '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException err(String msg) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, src.length()); i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return new IllegalArgumentException("JSON 解析失败（偏移 " + pos + "，行 " + line + "）：" + msg);
    }

    public static String str(Json.JsonObject o, String key, String dflt) {
        Object v = o.get(key);
        return v instanceof String s ? s : dflt;
    }

    public static long lng(Json.JsonObject o, String key, long dflt) {
        Object v = o.get(key);
        return v instanceof Number n ? n.longValue() : dflt;
    }

    public static double dbl(Json.JsonObject o, String key, double dflt) {
        Object v = o.get(key);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }

    public static boolean bool(Json.JsonObject o, String key, boolean dflt) {
        Object v = o.get(key);
        return v instanceof Boolean b ? b : dflt;
    }

    public static Json.JsonObject obj(Json.JsonObject o, String key) {
        Object v = o.get(key);
        return v instanceof Json.JsonObject j ? j : null;
    }

    public static Json.JsonArray arr(Json.JsonObject o, String key) {
        Object v = o.get(key);
        return v instanceof Json.JsonArray a ? a : null;
    }

    public static Map<String, Object> toPlainMap(Json.JsonObject o) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : o.members().entrySet()) {
            out.put(e.getKey(), plain(e.getValue()));
        }
        return out;
    }

    private static Object plain(Object v) {
        if (v instanceof Json.JsonObject o) {
            return toPlainMap(o);
        }
        if (v instanceof Json.JsonArray a) {
            List<Object> l = new ArrayList<>();
            for (Object item : a.items()) {
                l.add(plain(item));
            }
            return l;
        }
        return v;
    }
}
