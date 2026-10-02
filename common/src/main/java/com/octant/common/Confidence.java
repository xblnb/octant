package com.octant.common;

public enum Confidence {

    A("严格计数直接聚合，误差仅来自采集丢失"),

    B("计数 + 显式估计模型（含挂机剔除、归一化）"),

    C("启发式/相关性推断，误差来源多"),

    SUPPRESSED("抑制态：结论未输出，但 confidence 字段仍必须存在并取本值");

    private final String meaning;

    Confidence(String meaning) {
        this.meaning = meaning;
    }

    public String meaning() {
        return meaning;
    }
}
