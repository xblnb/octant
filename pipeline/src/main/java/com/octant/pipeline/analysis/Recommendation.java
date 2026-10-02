package com.octant.pipeline.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Recommendation(
        String recommendationId,
        String actionKey,
        Target target,
        List<String> basedOnConclusionIds,
        List<String> basedOnEvidenceIds,
        String expectedEffectKey,
        String costRiskKey,
        String strength,
        Provenance provenance) {

    public static final List<String> STRENGTHS = List.of("观察", "可选调整", "建议排查");
    public static final List<String> TARGET_KINDS =
            List.of("checkpoint", "recipe", "dimension", "category", "mechanic");

    public record Target(String kind, String id) {
        public Target {
            Objects.requireNonNull(kind, "target.kind 必填");
            Objects.requireNonNull(id, "target.id 必填");
            if (!TARGET_KINDS.contains(kind)) {
                throw new IllegalArgumentException("非法 target.kind：" + kind + "；允许值 " + TARGET_KINDS);
            }
        }
    }

    public record Provenance(List<String> ruleIds, List<String> featureIds) {
        public Provenance {
            if (ruleIds == null || ruleIds.isEmpty()) {
                throw new IllegalArgumentException("provenance.ruleIds 必须 ≥ 1");
            }
            if (featureIds == null || featureIds.isEmpty()) {
                throw new IllegalArgumentException("provenance.featureIds 必须 ≥ 1");
            }
            ruleIds = List.copyOf(ruleIds);
            featureIds = List.copyOf(featureIds);
        }
    }

    public Recommendation {
        Objects.requireNonNull(recommendationId, "recommendationId 必填");
        if (!recommendationId.matches("A\\d{2}")) {
            throw new IllegalArgumentException("recommendationId 必须形如 A01，实际 " + recommendationId);
        }
        Objects.requireNonNull(actionKey, recommendationId + ": actionKey 必填（HIG R4-1）");
        Objects.requireNonNull(target, recommendationId + ": target 必填（REQ-EXPT-07）");
        if (basedOnConclusionIds == null || basedOnConclusionIds.isEmpty()) {
            throw new IllegalArgumentException(recommendationId + ": basedOnConclusionIds 必须 ≥ 1（HIG R4-2）");
        }
        if (basedOnEvidenceIds == null || basedOnEvidenceIds.isEmpty()) {
            throw new IllegalArgumentException(recommendationId + ": basedOnEvidenceIds 必须 ≥ 1（HIG R4-2）");
        }
        if (costRiskKey == null
                || !(costRiskKey.contains("不适用于其它存档") || costRiskKey.contains("不适用于其他存档")
                     || costRiskKey.contains("not_apply_to_other_saves"))) {
            throw new IllegalArgumentException(recommendationId
                    + ": costRiskKey 必须含「以下信息可能不适用于其它存档」类限定语（HIG R4-4）");
        }
        if (!STRENGTHS.contains(strength)) {
            throw new IllegalArgumentException(recommendationId + ": strength 只允许 " + STRENGTHS
                    + "（禁止「必须」「紧急」）");
        }
        Objects.requireNonNull(provenance, recommendationId + ": provenance 必填");
        basedOnConclusionIds = List.copyOf(basedOnConclusionIds);
        basedOnEvidenceIds = List.copyOf(basedOnEvidenceIds);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recommendationId", recommendationId);
        m.put("actionKey", actionKey);
        m.put("target", Json2.map("kind", target.kind(), "id", target.id()));
        m.put("basedOnConclusionIds", basedOnConclusionIds);
        m.put("basedOnEvidenceIds", basedOnEvidenceIds);
        if (expectedEffectKey != null) {
            m.put("expectedEffectKey", expectedEffectKey);
        }
        m.put("costRiskKey", costRiskKey);
        m.put("strength", strength);
        m.put("provenance", Json2.map("ruleIds", provenance.ruleIds(), "featureIds", provenance.featureIds()));
        return m;
    }

    static final class Json2 {
        static Map<String, Object> map(Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
            return m;
        }

        private Json2() {
        }
    }
}
