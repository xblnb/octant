package com.octant.pipeline.raw;

public enum UnitKind {
    ADVANCEMENT("advancement"),
    QUEST("quest"),
    RECIPE("recipe"),
    ITEM("item"),
    DIMENSION("dimension"),
    BIOME("biome"),
    STRUCTURE("structure"),
    MACHINE("machine"),
    CATEGORY("category");

    private final String wireName;

    UnitKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static UnitKind fromWireName(String name) {
        for (UnitKind k : values()) {
            if (k.wireName.equals(name)) {
                return k;
            }
        }
        throw new IllegalArgumentException("未知 unitKind：" + name);
    }
}
