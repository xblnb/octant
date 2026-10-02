package com.octant.common.model;

final class PayloadValues {

    private PayloadValues() {
    }

    static int asInt(String field, Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            long l = n.longValue();
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new ContractException("payload." + field + " 超出 int 范围：" + l);
            }
            return (int) l;
        }
        throw new ContractException("payload." + field + " 必须是整数：" + value);
    }

    static long asLong(String field, Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new ContractException("payload." + field + " 必须是整数：" + value);
    }

    static double asDouble(String field, Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new ContractException("payload." + field + " 必须是数值：" + value);
    }

    static void checkBounds(String field, long value, Number min, Number max) {
        if (min != null && value < min.longValue()) {
            throw new ContractException("payload." + field + " 小于下界 " + min + "：" + value);
        }
        if (max != null && value > max.longValue()) {
            throw new ContractException("payload." + field + " 大于上界 " + max + "：" + value);
        }
    }
}
