package com.octant.common.model;

import java.util.Map;

public final class PayloadSchema {

    private PayloadSchema() {
    }

    public static java.util.Set<String> allowedFieldNames() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (CaptureEventType t : CaptureEventType.values()) {
            out.addAll(PayloadSpecs.fieldNamesOf(t));
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    public static java.util.List<String> fieldNamesOf(CaptureEventType type) {
        return PayloadSpecs.fieldNamesOf(type);
    }

    public static Map<String, Object> encode(CaptureEventType type, Map<String, Object> raw, long wallMs) {
        Map<String, Object> input = raw == null ? Map.of() : raw;
        PayloadSpecs.Spec spec = PayloadSpecs.of(type);

        for (String key : input.keySet()) {
            if (!spec.has(key)) {
                throw new ContractException(
                        "payload 含字段表外的键（dc §2.2 要求键集合完全相同）：" + type + "." + key);
            }
        }

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (PayloadFieldSpec field : spec.fields()) {
            Object value = input.get(field.name());
            if (value == null) {
                if (field.required()) {
                    throw new ContractException(
                            "MUST 字段缺失，必须按 REQ-DATA-06 丢弃该事件：" + type + "." + field.name());
                }
                value = field.defaultValue();
                if (value == null) {
                    throw new ContractException("MAY 字段缺省值未定义（禁止 null）：" + field.name());
                }
            }
            out.put(field.name(), value);
        }

        for (PayloadFieldSpec field : spec.fields()) {
            Object value = out.get(field.name());
            if (!field.required() && java.util.Objects.equals(value, field.defaultValue())) {
                continue;
            }
            field.check(value);
        }

        if (type.isInterval()) {
            long dur = asLong(out.get("durMs"));
            if (dur < 0) {
                throw new ContractException("durMs 不得为负：" + dur);
            }
            if (wallMs >= 0 && dur > wallMs) {
                throw new ContractException("durMs 超过会话墙钟时长：" + dur + " > " + wallMs);
            }
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    public static void checkDurPresence(CaptureEventType type, boolean durMsPresent) {
        if (type.isInterval() && !durMsPresent) {
            throw new ContractException("区间型事件必须带 durMs（dc §2.5）：" + type);
        }
        if (!type.isInterval() && durMsPresent) {
            throw new ContractException("标量型事件不得出现 durMs（dc §2.5）：" + type);
        }
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : -1L;
    }
}
