package com.octant.pipeline.kite;

public enum KiteAxisProfile {

    LEGACY("legacy", "旧定义（第一版；保留并列读数）"),

    REDEFINED_2026("redefined-2026", "重定义（甲；SPON 累计实体多样性 + GUID 规格原式）"),

    REDEFINED_PROG_DELTA("redefined-prog-delta", "重定义 + PROG 窗内增量（去累计；分辨率会降到 2）");

    private final String wireName;
    private final String note;

    KiteAxisProfile(String wireName, String note) {
        this.wireName = wireName;
        this.note = note;
    }

    public String wireName() {
        return wireName;
    }

    public String note() {
        return note;
    }

    public static KiteAxisProfile current() {
        return current;
    }

    public static KiteAxisProfile of(String wireName) {
        for (KiteAxisProfile p : values()) {
            if (p.wireName.equals(wireName)) {
                return p;
            }
        }
        return null;
    }

    private static KiteAxisProfile current = REDEFINED_2026;

    public static void setCurrent(KiteAxisProfile profile) {
        if (profile != null) {
            current = profile;
        }
    }
}
