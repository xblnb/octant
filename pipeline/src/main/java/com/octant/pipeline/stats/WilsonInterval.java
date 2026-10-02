package com.octant.pipeline.stats;

public final class WilsonInterval {

    private WilsonInterval() {
    }

    public static final double Z_95 = 1.959963984540054;

    public static int[] percent95(int successes, int total) {
        return percent(successes, total, Z_95);
    }

    public static int[] percent(int successes, int total, double z) {
        if (total <= 0) {
            return null;
        }
        int k = Math.max(0, Math.min(successes, total));
        double n = total;
        double p = k / n;
        double z2 = z * z;
        double denom = 1.0 + z2 / n;
        double center = (p + z2 / (2.0 * n)) / denom;
        double half = (z / denom) * Math.sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * n));
        double lo = Math.max(0.0, center - half);
        double hi = Math.min(1.0, center + half);
        int loPct = (int) Math.floor(lo * 100.0);
        int hiPct = (int) Math.ceil(hi * 100.0);
        loPct = Math.max(0, Math.min(100, loPct));
        hiPct = Math.max(0, Math.min(100, hiPct));
        if (hiPct < loPct) {
            hiPct = loPct;
        }
        return new int[]{loPct, hiPct};
    }
}
