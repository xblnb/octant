package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.List;

public final class KiteVectorAggregator {

    private final KiteLabelRegistry registry;

    public KiteVectorAggregator(KiteLabelRegistry registry) {
        this.registry = registry;
    }

    public record Weighted(KiteVector vector, long activeSeconds) {
    }

    public void requireAggregatable(String reference) {
        for (KiteAxis axis : KiteAxis.values()) {
            if (axis.name().equals(reference)) {
                return;
            }
        }
        if (reference.contains(":")) {
            registry.requireForAggregation(reference);
            throw new IllegalStateException("LV-10 fail-closed：即使标签 " + reference
                    + " 的判定式已建立，本切片也不做标签→向量（映射表在 metrics-semantics §20，"
                    + "不在本任务 inScope）");
        }
        throw new IllegalStateException("未知聚合输入：" + reference
                + "（只允许三轴 PROG/SPON/GUID；标签路径按 LV-10 整条关闭）");
    }

    public void requireForAggregationOnly(String reference) {
        registry.requireForAggregation(reference);
    }

    public KiteVector aggregate(List<Weighted> items) {
        requireAggregatable(KiteAxis.PROG.name());
        requireAggregatable(KiteAxis.SPON.name());
        requireAggregatable(KiteAxis.GUID.name());
        if (items.isEmpty()) {
            throw new IllegalArgumentException("聚合至少需要一个区间（否则应显式抑制，而不是产出空向量）");
        }
        long totalWeight = 0L;
        for (Weighted w : items) {
            totalWeight += Math.max(0L, w.activeSeconds());
        }
        KiteSampleSize ss = KiteSampleSize.ofActiveSeconds(totalWeight, items.size());

        List<KiteAxisValue> out = new ArrayList<>();
        for (KiteAxis axis : KiteAxis.values()) {
            double sum = 0d;
            long wSum = 0L;
            String lastReason = null;
            StringBuilder dump = new StringBuilder();
            for (Weighted w : items) {
                KiteAxisValue v = w.vector().axisValue(axis);
                dump.append(v.available() ? KiteConstants.trim(v.value()) : "<" + v.reasonCode() + ">").append(' ');
                if (!v.available()) {
                    lastReason = v.reasonCode();
                    continue;
                }
                long weight = Math.max(0L, w.activeSeconds());
                if (weight == 0L) {
                    continue;
                }
                sum += v.value() * (double) weight;
                wSum += weight;
            }
            if (wSum == 0L) {
                out.add(KiteAxisValue.suppressed(axis, lastReason == null ? KiteConstants.RC_SAMPLE_INSUFFICIENT
                        : lastReason, ss, "权重合计 = 0（无活跃时长）⇒ 按 SG-7 显式抑制；逐区间 = " + dump));
            } else {
                out.add(KiteAxisValue.available(axis, KiteAxisMath.clamp01(sum / (double) wSum),
                        "Σ Δ^active·X / Σ Δ^active，权重合计=" + wSum + "s；逐区间 = " + dump));
            }
        }
        return KiteVector.of(out.toArray(new KiteAxisValue[0]));
    }
}
