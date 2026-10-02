package com.octant.common.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    private Json() {
    }

    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new ContractException("JSON 不允许 NaN/Infinity：" + d);
            }
            sb.append(d.doubleValue());
        } else if (value instanceof Number n) {
            sb.append(n.longValue());
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            throw new ContractException("不可序列化的类型：" + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
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

    public static Object decode(String text) {
        if (text == null) {
            throw new ContractException("JSON 文本为 null");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new ContractException("JSON 尾部存在多余字符，位置 " + p.pos);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decodeObject(String text) {
        Object v = decode(text);
        if (!(v instanceof Map)) {
            throw new ContractException("期望 JSON 对象，实际：" + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWs();
            if (atEnd()) {
                throw new ContractException("JSON 意外结束");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (!atEnd() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                if (m.put(key, value) != null) {
                    throw new ContractException("JSON 对象存在重复键：" + key);
                }
                skipWs();
                if (atEnd()) {
                    throw new ContractException("JSON 对象未闭合");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new ContractException("JSON 对象期望 ',' 或 '}'，实际 '" + c + "'，位置 " + (pos - 1));
                }
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (!atEnd() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                if (atEnd()) {
                    throw new ContractException("JSON 数组未闭合");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new ContractException("JSON 数组期望 ',' 或 ']'，实际 '" + c + "'，位置 " + (pos - 1));
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new ContractException("JSON 字符串未闭合");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new ContractException("JSON 转义未完成");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw new ContractException("JSON \\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new ContractException("非法 JSON 转义：\\" + e);
                }
            }
        }

        Object readLiteral(String literal, Object value) {
            if (!s.startsWith(literal, pos)) {
                throw new ContractException("非法 JSON 字面量，位置 " + pos);
            }
            pos += literal.length();
            return value;
        }

        Object readNumber() {
            int start = pos;
            if (!atEnd() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            boolean fractional = false;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    fractional = fractional || c == '.' || c == 'e' || c == 'E';
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (num.isEmpty()) {
                throw new ContractException("非法 JSON 数值，位置 " + start);
            }
            try {
                return fractional ? (Object) java.lang.Double.valueOf(num) : (Object) Long.valueOf(num);
            } catch (NumberFormatException ex) {
                throw new ContractException("非法 JSON 数值：" + num, ex);
            }
        }

        void expect(char c) {
            skipWs();
            if (atEnd() || s.charAt(pos) != c) {
                throw new ContractException("JSON 期望 '" + c + "'，位置 " + pos);
            }
            pos++;
        }
    }
}
