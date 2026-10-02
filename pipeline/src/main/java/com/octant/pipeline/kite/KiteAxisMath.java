package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class KiteAxisMath {

    private KiteAxisMath() {
    }

    static final double SPON_ENTROPY_SATURATION = 3.0d;
    static final double SPON_MIX_SATURATION = 3.0d;
    static final double[] W_SPON = {
            KiteConstants.KITE_W_SPON_ENTROPY,
            KiteConstants.KITE_W_SPON_BREADTH,
            KiteConstants.KITE_W_SPON_PREF
    };
    static final double[] W_GUID = {0.5d, 0.5d};
    static final double[] W_STRUCTURE = {0.5d, 0.5d};

    static double[] sponWeightsUsed() {
        return W_SPON.clone();
    }

    static double[] guidWeightsUsed() {
        return new double[] {W_GUID[0], W_GUID[1], W_STRUCTURE[0], W_STRUCTURE[1]};
    }

    static KiteVector vectorOf(List<KiteObservation> obs, KiteSampleSize sampleSize) {
        Map<String, KiteObservation> byKey = new LinkedHashMap<>();
        for (KiteObservation o : obs) {
            byKey.put(o.key(), o);
        }
        if (KiteAxisProfile.current() == KiteAxisProfile.REDEFINED_2026) {
            return vectorOfRaw(obs, sampleSize);
        }
        return KiteVector.of(
                prog(byKey, sampleSize),
                spon(byKey, sampleSize),
                guid(byKey, sampleSize));
    }

    static KiteVector vectorOfRaw(List<KiteObservation> obs, KiteSampleSize sampleSize) {
        Map<String, KiteObservation> byKey = new LinkedHashMap<>();
        for (KiteObservation o : obs) {
            byKey.put(o.key(), o);
        }
        return KiteVector.of(
                prog(byKey, sampleSize),
                spon(byKey, sampleSize),
                guidRaw(byKey, sampleSize));
    }

    private static KiteAxisValue guidRaw(Map<String, KiteObservation> byKey, KiteSampleSize sampleSize) {
        KiteObservation variants = byKey.get("octant.env.dimension_variants");
        KiteObservation dominant = byKey.get("octant.env.dimension_dominant_share");
        KiteObservation rate = byKey.get("octant.pref.completion_rate_per_hour");
        String dump = "dimension_variants=" + show(variants) + ", dimension_dominant_share=" + show(dominant)
                + ", completion_rate_per_hour=" + show(rate);
        boolean densityOk = variants != null && variants.available() && dominant != null && dominant.available();
        boolean adherenceOk = rate != null && rate.available();
        if (!densityOk) {
            String rc = variants != null && !variants.available() ? variants.reasonCode()
                    : (dominant != null && !dominant.available() ? dominant.reasonCode()
                    : KiteConstants.RC_INPUT_MISSING);
            return KiteAxisValue.suppressed(KiteAxis.GUID, rc, sampleSize,
                    dump + " ⇒ structure_density 不可用（adherence 是否可用不影响该判定）");
        }
        double v = variants.value();
        double sat = Math.sqrt(KiteConstants.KITE_STRUCT_SAT);
        double rootV = Math.sqrt(v);
        double structureDensity = W_STRUCTURE[0] * (rootV / (rootV + sat))
                + W_STRUCTURE[1] * (1d - dominant.value());
        double adherence = adherenceOk ? rate.value() / (rate.value() + KiteConstants.K_ADH) : Double.NaN;
        double value = clamp01(structureDensity);
        String extra;
        if (adherenceOk) {
            value = clamp01(W_GUID[0] * structureDensity + W_GUID[1] * adherence);
            extra = "；adherence 可用 ⇒ 按 §1 L45 的 0.5/0.5 合成";
        } else {
            extra = "；adherence 不可用（来源/空窗）⇒ 按 SG-7 以 structure_density 单分量给出并声明弃权";
        }
        return KiteAxisValue.available(KiteAxis.GUID, value,
                dump + " ⇒ structure_density=" + KiteConstants.trim(structureDensity)
                        + "、adherence=" + (adherenceOk ? KiteConstants.trim(adherence) : "<弃权>") + extra);
    }

    private static KiteAxisValue prog(Map<String, KiteObservation> byKey, KiteSampleSize sampleSize) {
        KiteObservation done = byKey.get("octant.progress.completed_units");
        KiteObservation reach = byKey.get("octant.progress.reachable_units");
        String dump = "completed_units=" + show(done) + ", reachable_units=" + show(reach);
        if (done == null || reach == null) {
            return KiteAxisValue.suppressed(KiteAxis.PROG, KiteConstants.RC_INPUT_MISSING, sampleSize, dump);
        }
        if (!done.available() || !reach.available()) {
            String rc = !done.available() ? done.reasonCode() : reach.reasonCode();
            return KiteAxisValue.suppressed(KiteAxis.PROG, rc, sampleSize, dump);
        }
        double denom = reach.value();
        if (denom == 0d) {
            return KiteAxisValue.suppressed(KiteAxis.PROG, KiteConstants.RC_INPUT_MISSING, sampleSize,
                    dump + " ⇒ reachable_units == 0（规格 §1 L43：不得记 0）");
        }
        double cover = done.value() / denom;
        return KiteAxisValue.available(KiteAxis.PROG, clamp01(cover),
                dump + " ⇒ cover_weighted=" + KiteConstants.trim(cover));
    }

    private static KiteAxisValue spon(Map<String, KiteObservation> byKey, KiteSampleSize sampleSize) {
        String[] keys = {
                "octant.dispersion.entropy_normalized",
                "octant.breadth.weighted_coverage",
                "octant.pref.category_entropy"
        };
        double[] terms = new double[3];
        List<String> dump = new ArrayList<>();
        String firstReason = null;
        double weightSum = 0d;
        boolean gateSubComponents = KiteAxisProfile.current() == KiteAxisProfile.REDEFINED_2026
                || KiteAxisProfile.current() == KiteAxisProfile.REDEFINED_PROG_DELTA;
        for (int i = 0; i < keys.length; i++) {
            KiteObservation o = byKey.get(keys[i]);
            dump.add(shortName(keys[i]) + "=" + show(o));
            if (gateSubComponents && i > 0) {
                dump.add("（档位门：该分量在本档不参与派生）");
                continue;
            }
            if (o == null || !o.available()) {
                if (firstReason == null) {
                    firstReason = o == null ? KiteConstants.RC_INPUT_MISSING : o.reasonCode();
                }
                continue;
            }
            if (o.hasEntityDiversityInput()) {
                int[] kc = o.entityDiversityInput();
                terms[i] = entityDiversity(kc[0], kc[1]) * W_SPON[i];
            } else {
                terms[i] = o.value() * W_SPON[i];
            }
            weightSum += W_SPON[i];
        }
        String joined = String.join(", ", dump);
        if (weightSum == 0d) {
            return KiteAxisValue.suppressed(KiteAxis.SPON, firstReason, sampleSize,
                    joined + " ⇒ 三项全不可用，整轴抑制");
        }
        double sum = terms[0] + terms[1] + terms[2];
        double value = sum / weightSum;
        String reweight = weightSum < 1.0d - 1e-12d
                ? "；弃权项重归一化（剩余权重和=" + KiteConstants.trim(weightSum) + "）" : "";
        return KiteAxisValue.available(KiteAxis.SPON, clamp01(value),
                joined + " ⇒ SPON=" + KiteConstants.trim(value) + reweight);
    }

    private static KiteAxisValue guid(Map<String, KiteObservation> byKey, KiteSampleSize sampleSize) {
        KiteObservation variants = byKey.get("octant.env.dimension_variants");
        KiteObservation dominant = byKey.get("octant.env.dimension_dominant_share");
        KiteObservation rate = byKey.get("octant.pref.completion_rate_per_hour");
        String dump = "dimension_variants=" + show(variants) + ", dimension_dominant_share=" + show(dominant)
                + ", completion_rate_per_hour=" + show(rate);

        boolean densityOk = variants != null && variants.available() && dominant != null && dominant.available();
        boolean adherenceOk = rate != null && rate.available();
        if (!densityOk || !adherenceOk) {
            String rc = firstReason(adherenceOk ? dominant : rate, densityOk ? null : variants);
            return KiteAxisValue.suppressed(KiteAxis.GUID, rc, sampleSize,
                    dump + " ⇒ 两分量任一不可用 ⇒ 按 SG-7「弃权即抑制」处置（不得省略该项）");
        }
        double v = variants.value();
        double sat = Math.sqrt(KiteConstants.KITE_STRUCT_SAT);
        double rootV = Math.sqrt(v);
        double structureDensity = W_STRUCTURE[0] * (rootV / (rootV + sat))
                + W_STRUCTURE[1] * (1d - dominant.value());
        double adherence = rate.value() / (rate.value() + KiteConstants.K_ADH);
        double value = clamp01(W_GUID[0] * structureDensity + W_GUID[1] * adherence);
        return KiteAxisValue.available(KiteAxis.GUID, value,
                dump + " ⇒ structure_density=" + KiteConstants.trim(structureDensity)
                        + ", adherence=" + KiteConstants.trim(adherence));
    }

    private static String firstReason(KiteObservation preferred, KiteObservation fallback) {
        if (preferred != null && !preferred.available()) {
            return preferred.reasonCode();
        }
        if (fallback != null && !fallback.available()) {
            return fallback.reasonCode();
        }
        return KiteConstants.RC_INPUT_MISSING;
    }

    private static String shortName(String key) {
        int i = key.lastIndexOf('.');
        return i < 0 ? key : key.substring(i + 1);
    }

    static String show(KiteObservation o) {
        if (o == null) {
            return "<未提供>";
        }
        if (o.hasEntityDiversityInput()) {
            int[] kc = o.entityDiversityInput();
            return KiteConstants.trim(entityDiversity(kc[0], kc[1])) + "(k=" + kc[0] + ",C=" + kc[1] + ")";
        }
        return o.available() ? KiteConstants.trim(o.value()) : "<" + o.reasonCode() + ">";
    }

    static double clamp01(double v) {
        if (v < 0d) {
            return 0d;
        }
        if (v > 1d) {
            return 1d;
        }
        return v;
    }

    static double normalizedEntropy(int distinctClasses, double saturation) {
        if (distinctClasses <= 1) {
            return 0d;
        }
        return clamp01(Math.log(distinctClasses) / Math.log(saturation));
    }

    static double entityDiversity(int windowEntities, int catalogSize) {
        if (catalogSize <= 0) {
            return 0d;
        }
        return clamp01(Math.log(1d + windowEntities) / Math.log(1d + catalogSize));
    }
}
