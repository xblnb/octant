package com.octant.pipeline.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    public static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "<missing>";
        }
    };

    private Json() {
    }

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonArray arr() {
        return new JsonArray();
    }

    public static JsonObject of(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("键值必须成对");
        }
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            o.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return o;
    }

    public static JsonArray list(Object first, Object... rest) {
        JsonArray a = new JsonArray();
        a.add(first);
        for (Object r : rest) {
            a.add(r);
        }
        return a;
    }

    public static final class JsonObject {

        private final LinkedHashMap<String, Object> members = new LinkedHashMap<>();

        public JsonObject put(String key, Object value) {
            if (value == null || value == MISSING) {
                members.remove(key);
                return this;
            }
            members.put(key, unwrap(value));
            return this;
        }

        public JsonObject putAll(Map<String, Object> other) {
            if (other != null) {
                for (Map.Entry<String, Object> e : other.entrySet()) {
                    put(e.getKey(), e.getValue());
                }
            }
            return this;
        }

        public boolean has(String key) {
            return members.containsKey(key);
        }

        public Object get(String key) {
            return members.get(key);
        }

        public String str(String key) {
            Object v = members.get(key);
            return v instanceof String s ? s : null;
        }

        public Map<String, Object> members() {
            return Collections.unmodifiableMap(members);
        }

        public int size() {
            return members.size();
        }

        public List<String> keys() {
            return new ArrayList<>(members.keySet());
        }

        @Override
        public String toString() {
            return JsonWriter.compact(this);
        }
    }

    public static final class JsonArray {

        private final List<Object> items = new ArrayList<>();

        public JsonArray add(Object value) {
            items.add(unwrap(value));
            return this;
        }

        public JsonArray addAll(List<?> values) {
            if (values != null) {
                for (Object v : values) {
                    add(v);
                }
            }
            return this;
        }

        public List<Object> items() {
            return Collections.unmodifiableList(items);
        }

        public int size() {
            return items.size();
        }

        public Object get(int i) {
            return items.get(i);
        }

        @Override
        public String toString() {
            return JsonWriter.compact(this);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object unwrap(Object value) {
        if (value instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                o.put(String.valueOf(e.getKey()), e.getValue());
            }
            return o;
        }
        if (value instanceof List<?> l) {
            JsonArray a = new JsonArray();
            for (Object v : l) {
                a.add(v);
            }
            return a;
        }
        if (value instanceof JsonObject || value instanceof JsonArray
                || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON 不得承载 NaN/Infinity（应使用 SAMPLE_ZERO_DENOM 表达）");
            }
            return d;
        }
        throw new IllegalArgumentException("不支持的 JSON 值类型：" + value.getClass().getName());
    }
}
