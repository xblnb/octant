package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MiniJson {

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("JSON 尾随内容 @" + p.pos + "：" + p.near());
        }
        return v;
    }

    private Object value() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("JSON 意外结束");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++;
        skipWs();
        if (peek() == '}') {
            pos++;
            return m;
        }
        while (true) {
            skipWs();
            String k = string();
            skipWs();
            if (peek() != ':') {
                throw new IllegalArgumentException("JSON 期待 ':' @" + pos + "：" + near());
            }
            pos++;
            skipWs();
            m.put(k, value());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return m;
            }
            throw new IllegalArgumentException("JSON 期待 ',' 或 '}' @" + pos + "：" + near());
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        pos++;
        skipWs();
        if (peek() == ']') {
            pos++;
            return l;
        }
        while (true) {
            skipWs();
            l.add(value());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return l;
            }
            throw new IllegalArgumentException("JSON 期待 ',' 或 ']' @" + pos + "：" + near());
        }
    }

    private String string() {
        if (peek() != '"') {
            throw new IllegalArgumentException("JSON 期待字符串 @" + pos + "：" + near());
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("JSON 字符串未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw new IllegalArgumentException("JSON 未知转义 \\" + e);
            }
        }
    }

    private Object number() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                pos++;
                continue;
            }
            break;
        }
        String text = src.substring(start, pos);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("JSON 非数值 @" + start + "：" + near());
        }
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return Double.valueOf(text);
    }

    private void expect(String token) {
        if (!src.startsWith(token, pos)) {
            throw new IllegalArgumentException("JSON 期待 " + token + " @" + pos + "：" + near());
        }
        pos += token.length();
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("JSON 意外结束");
        }
        return src.charAt(pos);
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
                continue;
            }
            break;
        }
    }

    private String near() {
        int from = Math.max(0, pos - 20);
        int to = Math.min(src.length(), pos + 20);
        return src.substring(from, to);
    }
}
