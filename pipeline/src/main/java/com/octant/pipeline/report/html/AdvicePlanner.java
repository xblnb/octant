package com.octant.pipeline.report.html;

import com.octant.pipeline.analysis.Chart;
import com.octant.pipeline.analysis.Conclusion;
import com.octant.pipeline.analysis.MetricOutcome;
import com.octant.pipeline.analysis.Recommendation;
import com.octant.pipeline.analysis.AnalysisReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AdvicePlanner {

    private static final String OBSERVE = "观察";
    private static final String OPTIONAL = "可选调整";
    private static final String PROBE = "建议排查";

    private static final String RISK =
            "以下信息可能不适用于其它存档：本建议基于本存档的观测窗口";

    record Advice(String id, String actionKey, String targetId, String targetKind, String action,
                  String headline, String basis, List<String> basedOnConclusionIds,
                  List<String> basedOnEvidenceIds, String strength, String costRisk) {
    }

    private AdvicePlanner() {
    }

    private static final Map<String, String> PROBLEM_BY_ADVICE = Map.of(
            "A01", "引导缺失",
            "A02", "引导缺失",
            "A04", "节奏失衡",
            "A05", "复杂度跳升",
            "A07", "节奏失衡");

    static String problemOf(String adviceId) {
        return PROBLEM_BY_ADVICE.get(adviceId);
    }

    private static List<String> evidenceOfMetrics(AnalysisReport report, String... metricIds) {
        List<String> out = new ArrayList<>();
        for (String mid : metricIds) {
            String fid = null;
            for (MetricOutcome mo : report.metrics()) {
                if (mid.equals(mo.metricId())) {
                    fid = mo.featureId();
                    break;
                }
            }
            if (fid == null) {
                continue;
            }
            for (var e : report.evidence()) {
                if (e.featureIds().contains(fid) && !out.contains(e.evidenceId())) {
                    out.add(e.evidenceId());
                    break;
                }
            }
        }
        return out;
    }

    static List<Advice> plan(AnalysisReport report) {
        Map<String, MetricOutcome> m = new LinkedHashMap<>();
        for (MetricOutcome x : report.metrics()) {
            m.put(x.metricId(), x);
        }
        Map<String, Conclusion> conclusionOfMetric = new LinkedHashMap<>();
        for (Conclusion c : report.conclusions()) {
            for (String mid : c.metricIds()) {
                MetricOutcome mo = m.get(mid);
                if (mo != null && mo.hasValue() && !conclusionOfMetric.containsKey(mid)) {
                    conclusionOfMetric.put(mid, c);
                }
            }
        }

        List<Advice> out = new ArrayList<>();

        MetricOutcome heaviest = m.get("M2c");
        if (heaviest != null && heaviest.namedKey() != null) {
            Conclusion c = conclusionOfMetric.get("M1c");
            if (c != null) {
                long dwellMs = heaviest.value() instanceof Number n ? n.longValue() : 0L;
                double resourceRate = number(m.get("M2d"));
                out.add(new Advice("A01", "action.raise_prerequisite_output",
                        heaviest.namedKey(), "checkpoint", "提高",
                        "把「" + heaviest.namedKey() + "」的前置资源产出提高约 " + pct(resourceRate)
                                + "（对齐本次观测到的资源阻塞占比），让到达该单元的路径不再被资源卡住",
                        "该进度是本次观测中停留最久的单元，停留约 " + hours(dwellMs)
                                + "；被资源阻塞的尝试占比 " + pct(resourceRate) + "。",
                        List.of(c.conclusionId()),
                        evidenceOfMetrics(report, "M2c", "M2d"), PROBE, RISK));
            }
        }

        MetricOutcome breadth = m.get("M4");
        if (breadth != null && breadth.series() != null && !breadth.series().isEmpty()) {
            Conclusion c = conclusionOfMetric.get("M4");
            if (c != null) {
                String[] lowest = {null, null};
                int[] count = {0};
                for (Map.Entry<String, Double> e : new java.util.TreeMap<>(breadth.series()).entrySet()) {
                    if (e.getValue() != null) {
                        count[0]++;
                        if (lowest[0] == null || e.getValue() < breadth.series().get(lowest[0])) {
                            lowest[1] = lowest[0];
                            lowest[0] = e.getKey();
                        }
                    }
                }
                if (lowest[0] != null && count[0] >= 2) {
                    out.add(new Advice("A02", "action.adjust_axis_entry",
                            lowest[0], "dimension", "调整",
                            "检查内容轴「" + lowest[0] + "」的入口与配平是否让玩家难以触达",
                            "本次可达内容轴中，该类别的触达次数最少（"
                                    + fmt(breadth.series().get(lowest[0])) + " 次）；"
                                    + "总计 " + count[0] + " 个内容轴被统计。",
                            List.of(c.conclusionId()), firstEvidence(c), OPTIONAL, RISK));
                }
            }
        }

        MetricOutcome dominant = m.get("M6c");
        if (dominant != null && dominant.namedKey() != null) {
            Conclusion c = conclusionOfMetric.get("M6c");
            if (c != null) {
                out.add(new Advice("A03", "action.rebalance_category_time_share",
                        dominant.namedKey(), "category", "调整",
                        "检查类别「" + dominant.namedKey() + "」的时间占比是否挤占了其它玩法",
                        "该类别的活跃时长占比为 " + ratio(dominant.value())
                                + "（本次观测中占比最高的类别）。",
                        List.of(c.conclusionId()), firstEvidence(c), OBSERVE, RISK));
            }
        }

        MetricOutcome combatShare = m.get("M8a");
        MetricOutcome nonLethal = m.get("M8b");
        Conclusion combatConc = conclusionOfMetric.get("M8a");
        if (combatShare != null && nonLethal != null && combatConc != null) {
            out.add(new Advice("A04", "action.lower_enemy_durability",
                    "机制:completeness", "mechanic", "下调",
                    "下调敌人耐久与威胁度，缩短单场交战时长",
                    "战斗时长占活跃时长 " + ratio(combatShare.value())
                            + "，其中未致命结束的占比 " + ratio(nonLethal.value())
                            + "（即多数交战以逃离或中断结束）。",
                    List.of(combatConc.conclusionId()), firstEvidence(combatConc), OPTIONAL, RISK));
        }

        Conclusion repeatConc = conclusionOfMetric.get("M7a");
        if (repeatConc != null) {
            String unit = heaviest != null && heaviest.namedKey() != null ? heaviest.namedKey() : null;
            if (unit != null) {
                out.add(new Advice("A05", "action.batch_recipe_output",
                        unit, "recipe", "合并",
                        "合并或前置与「" + unit + "」相关的重复操作，减少单次产出所需次数",
                        "重复行为占比为 " + ratio(number(m.get("M7a")))
                                + "；该单元同时是停留最久的单元。",
                        List.of(repeatConc.conclusionId()), firstEvidence(repeatConc), OPTIONAL, RISK));
            }
        }

        MetricOutcome blockRate = m.get("M2d");
        Conclusion stallConc = conclusionOfMetric.get("M1c");
        if (blockRate != null && heaviest != null && heaviest.namedKey() != null
                && stallConc != null) {
            out.add(new Advice("A06", "action.supply_missing_resource",
                    heaviest.namedKey(), "checkpoint", "补充",
                    "补充「" + heaviest.namedKey() + "」所需资源的产出路径",
                    "被资源阻塞的尝试占比为 " + ratio(blockRate.value())
                            + "（在尝试该单元的过程中出现资源不足）。",
                    List.of(stallConc.conclusionId()),
                    evidenceOfMetrics(report, "M2d"), PROBE, RISK));
        }

        MetricOutcome dominantAxis = m.get("M5c");
        Conclusion dispersion = conclusionOfMetric.get("M5a");
        if (dominantAxis != null && dominantAxis.namedKey() != null && dispersion != null) {
            out.add(new Advice("A07", "action.shorten_dominant_axis_loop",
                    dominantAxis.namedKey(), "category", "减少",
                    "减少「" + dominantAxis.namedKey() + "」轴上的重复往返次数",
                    "各内容轴的时长分布均衡度为 " + fmt(number(m.get("M5a")))
                            + "；占比最高的轴为 " + dominantAxis.namedKey() + "。",
                    List.of(dispersion.conclusionId()), firstEvidence(dispersion), OBSERVE, RISK));
        }

        if (out.isEmpty()) {
            return List.of();
        }

        Set<String> ids = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Advice a : out) {
            if (!ids.add(a.id())) {
                throw new IllegalStateException("建议 id 重复：" + a.id());
            }
            if (!keys.add(a.actionKey())) {
                throw new IllegalStateException("建议动作键重复（模板化形态）：" + a.actionKey());
            }
            if (looksLikeBareMetricId(a.targetId())) {
                throw new IllegalStateException("建议对象是裸指标 ID：" + a.targetId());
            }
        }
        return List.copyOf(out);
    }

    static boolean looksLikeBareMetricId(String id) {
        if (id == null || id.isEmpty()) {
            return true;
        }
        return id.matches("M\\d+[a-z]?") || id.matches("D\\d+_[A-Z0-9_]+") || id.matches("M\\d+_[A-Z0-9_]+");
    }

    private static List<String> firstEvidence(Conclusion c) {
        return c.evidenceIds().isEmpty() ? List.of() : List.of(c.evidenceIds().get(0));
    }

    private static double number(MetricOutcome m) {
        return m != null && m.value() instanceof Number n ? n.doubleValue() : 0.0d;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v).replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private static String ratio(Object value) {
        double v = value instanceof Number n ? n.doubleValue() : 0.0d;
        double pctValue = v > 1.0d ? v : v * 100.0d;
        return String.format(java.util.Locale.ROOT, "%.1f", pctValue) + "%";
    }

    private static String pct(double pctValue) {
        return String.format(java.util.Locale.ROOT, "%.1f", pctValue) + "%";
    }

    static String hours(long ms) {
        if (ms <= 0L) {
            return "0 分钟";
        }
        long minutes = Math.round(ms / 60000.0d);
        if (minutes < 60L) {
            return minutes + " 分钟";
        }
        return String.format(java.util.Locale.ROOT, "%.1f", minutes / 60.0d) + " 小时";
    }

    static List<Recommendation> analysisRecommendations(AnalysisReport report) {
        return report.recommendations();
    }

    static final List<String> VAGUE_BLACKLIST = List.of(
            "关注", "优化体验", "提升体验", "游戏平衡", "注意平衡", "加强引导", "丰富内容",
            "注意玩家体验", "予以重视", "需重视", "合理调整", "适当优化", "酌情", "综合考量",
            "improve the experience", "look into", "pay attention", "balance the game");

    static boolean isVague(Advice a) {
        String all = (a.headline() + " " + a.basis()).toLowerCase(java.util.Locale.ROOT);
        for (String bad : VAGUE_BLACKLIST) {
            if (all.contains(bad.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static final List<String> ACTION_WORDS = List.of(
            "检查", "调整", "增加", "减少", "提高", "降低", "补充", "修改", "修复", "移除",
            "合并", "拆分", "标注", "替换", "前置", "下调", "上调", "延迟", "提前");
}
