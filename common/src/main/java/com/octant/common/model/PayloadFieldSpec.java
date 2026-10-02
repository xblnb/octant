package com.octant.common.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

public sealed interface PayloadFieldSpec {

    String name();

    boolean required();

    Object defaultValue();

    PayloadFieldKind kind();

    String range();

    void check(Object value);

    record Int(String name, boolean required, Integer min, Integer max, Integer defaultValue)
            implements PayloadFieldSpec {

        @Override
        public PayloadFieldKind kind() {
            return PayloadFieldKind.INT;
        }

        @Override
        public String range() {
            return PayloadFieldSpec.describe(min, max);
        }

        @Override
        public void check(Object value) {
            int v = PayloadValues.asInt(name, value);
            PayloadValues.checkBounds(name, v, min, max);
        }
    }

    record Lng(String name, boolean required, Long min, Long max, Long defaultValue)
            implements PayloadFieldSpec {

        @Override
        public PayloadFieldKind kind() {
            return PayloadFieldKind.LONG;
        }

        @Override
        public String range() {
            return PayloadFieldSpec.describe(min, max);
        }

        @Override
        public void check(Object value) {
            long v = PayloadValues.asLong(name, value);
            PayloadValues.checkBounds(name, v, min, max);
        }
    }

    record Dbl(String name, boolean required, Double min, Double max, Double defaultValue)
            implements PayloadFieldSpec {

        @Override
        public PayloadFieldKind kind() {
            return PayloadFieldKind.DOUBLE;
        }

        @Override
        public String range() {
            return PayloadFieldSpec.describe(min, max);
        }

        @Override
        public void check(Object value) {
            double v = PayloadValues.asDouble(name, value);
            if (min != null && v < min) {
                throw new ContractException("payload." + name + " 小于下界 " + min + "：" + v);
            }
            if (max != null && v > max) {
                throw new ContractException("payload." + name + " 大于上界 " + max + "：" + v);
            }
        }
    }

    record Bool(String name, boolean required, Boolean defaultValue) implements PayloadFieldSpec {

        @Override
        public PayloadFieldKind kind() {
            return PayloadFieldKind.BOOL;
        }

        @Override
        public String range() {
            return "true / false";
        }

        @Override
        public void check(Object value) {
            if (!(value instanceof Boolean)) {
                throw new ContractException("payload." + name + " 必须是 boolean：" + value);
            }
        }
    }

    record Enum(String name, boolean required, Set<String> values, boolean allowEmpty,
                String defaultValue) implements PayloadFieldSpec {

        @Override
        public PayloadFieldKind kind() {
            return PayloadFieldKind.ENUM;
        }

        @Override
        public String range() {
            return String.join(" | ", values) + (allowEmpty ? " （或 \"\"）" : "");
        }

        @Override
        public void check(Object value) {
            if (!(value instanceof String s)) {
                throw new ContractException("payload." + name + " 必须是 string：" + value);
            }
            if (s.isEmpty() && allowEmpty) {
                return;
            }
            if (!values.contains(s)) {
                throw new ContractException("payload." + name + " 不在闭集内：" + s + "，允许：" + range());
            }
        }
    }

    record IdString(String name, boolean required, int maxLength, String defaultValue)
            implements PayloadFieldSpec {

        @Override
        public PayloadFieldKind kind() {
            return PayloadFieldKind.ID;
        }

        @Override
        public String range() {
            return "命名空间 ID / 枚举值 / 区域键，长度 ≤ " + maxLength + "，无空白字符";
        }

        @Override
        public void check(Object value) {
            if (!(value instanceof String s)) {
                throw new ContractException("payload." + name + " 必须是 string：" + value);
            }
            if (s.isEmpty()) {
                if (required) {
                    throw new ContractException("payload." + name + " 为 MUST 字段，不得为空串");
                }
                return;
            }
            if (s.length() > maxLength) {
                throw new ContractException("payload." + name + " 超长：" + s.length() + " > " + maxLength);
            }
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c <= ' ' || c == '\u007f' || Character.isISOControl(c)) {
                    throw new ContractException(
                            "payload." + name + " 含空白或控制字符，禁止自由文本（REQ-PRIV-05）");
                }
            }
        }
    }

    record Digest(String name, boolean required, int maxElements, String countKey)
            implements PayloadFieldSpec {

        @Override
        public PayloadFieldKind kind() {
            return PayloadFieldKind.DIGEST;
        }

        @Override
        public String range() {
            return "[{item, " + countKey + "}]，元素数 ≤ " + maxElements;
        }

        @Override
        public Object defaultValue() {
            return required ? null : List.of();
        }

        @Override
        public void check(Object value) {
            if (!(value instanceof List<?> list)) {
                throw new ContractException("payload." + name + " 必须是数组：" + value);
            }
            if (list.size() > maxElements) {
                throw new ContractException(
                        "payload." + name + " 元素数 " + list.size() + " 超过上限 " + maxElements);
            }
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> map)) {
                    throw new ContractException("payload." + name + " 元素必须是对象：" + element);
                }
                Object item = map.get("item");
                Object count = map.get(countKey);
                if (!(item instanceof String) || count == null) {
                    throw new ContractException(
                            "payload." + name + " 元素必须含 item 与 " + countKey + "：" + map);
                }
            }
        }
    }

    static String describe(Number min, Number max) {
        if (min == null && max == null) {
            return "无界";
        }
        if (min == null) {
            return "≤ " + max;
        }
        if (max == null) {
            return "≥ " + min;
        }
        return min + " … " + max;
    }
}
