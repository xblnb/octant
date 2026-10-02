package com.octant.pipeline.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WilsonIntervalTest {

    @Test
    void containsPointEstimateOnTheRealSample() {
        int[] ci = WilsonInterval.percent95(14, 22);
        assertNotNull(ci);
        assertTrue(ci[0] <= 64 && 64 <= ci[1], "区间必须含点估计 64%：实际 " + ci[0] + "–" + ci[1]);
        assertTrue(ci[0] <= 45, "22 次样本的 95% 下界不该高于 45%：实际 " + ci[0]);
    }

    @Test
    void neverLeavesZeroHundred() {
        int[][] cases = {{0, 1}, {1, 1}, {0, 3}, {3, 3}, {1, 22}, {0, 22}, {22, 22}, {1, 2}};
        for (int[] c : cases) {
            int[] ci = WilsonInterval.percent95(c[0], c[1]);
            assertNotNull(ci, "n>0 必须给出区间");
            assertTrue(ci[0] >= 0 && ci[1] <= 100, "越界：" + c[0] + "/" + c[1] + " -> " + ci[0] + "–" + ci[1]);
            assertTrue(ci[0] <= ci[1], "下界不得大于上界：" + ci[0] + "–" + ci[1]);
        }
    }

    @Test
    void narrowsAsSampleGrows() {
        int[] small = WilsonInterval.percent95(2, 3);
        int[] large = WilsonInterval.percent95(200, 300);
        int smallW = small[1] - small[0];
        int largeW = large[1] - large[0];
        assertTrue(largeW < smallW, "同样 67% 的比例，n=300 的区间必须比 n=3 窄：" + largeW + " vs " + smallW);
    }

    @Test
    void roundingOnlyWidens() {
        int[] ci = WilsonInterval.percent95(14, 22);
        double z = WilsonInterval.Z_95;
        double n = 22, p = 14.0 / 22.0, z2 = z * z;
        double denom = 1 + z2 / n;
        double center = (p + z2 / (2 * n)) / denom;
        double half = (z / denom) * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n));
        double loPct = Math.max(0, (center - half)) * 100.0;
        double hiPct = Math.min(1, (center + half)) * 100.0;
        assertTrue(ci[0] <= Math.ceil(loPct), "下界取整后不该比真下界更紧：" + ci[0] + " vs " + loPct);
        assertTrue(ci[1] >= Math.floor(hiPct), "上界取整后不该比真上界更紧：" + ci[1] + " vs " + hiPct);
    }

    @Test
    void noDenominatorMeansNoInterval() {
        assertNull(WilsonInterval.percent95(0, 0));
        assertNull(WilsonInterval.percent95(7, 0), "n=0 必须返回 null");
    }
}
