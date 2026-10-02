package com.octant.common;

public enum ContentAxis {
    DIMENSION("维度", 0.15),
    BIOME("生物群系", 0.15),
    STRUCTURE("结构", 0.10),
    ITEM("物品", 0.15),
    RECIPE("配方", 0.10),
    MOB("生物", 0.10),
    BLOCK("方块", 0.10),
    MACHINE("机器", 0.10),
    QUEST("任务", 0.05);

    private final String label;
    private final double defaultWeight;

    ContentAxis(String label, double defaultWeight) {
        this.label = label;
        this.defaultWeight = defaultWeight;
    }

    public String label() {
        return label;
    }

    public double defaultWeight() {
        return defaultWeight;
    }
}
