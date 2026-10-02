package com.octant.pipeline.analysis;

public enum ConfidenceLevel {
    CERTAIN("certain", "HIGH"),
    ESTIMATED("estimated", "MEDIUM"),
    HEURISTIC("heuristic", "LOW"),
    SUPPRESSED("suppressed", "LOW");

    private final String wireName;
    private final String detailLevel;

    ConfidenceLevel(String wireName, String detailLevel) {
        this.wireName = wireName;
        this.detailLevel = detailLevel;
    }

    public String wireName() {
        return wireName;
    }

    public String detailLevel() {
        return detailLevel;
    }

    public String localizedLabel() {
        return switch (this) {
            case CERTAIN -> "高";
            case ESTIMATED -> "中";
            case HEURISTIC -> "低";
            case SUPPRESSED -> "已抑制";
        };
    }

    public static ConfidenceLevel fromWireName(String name) {
        for (ConfidenceLevel c : values()) {
            if (c.wireName.equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("未知 confidence：" + name);
    }
}
