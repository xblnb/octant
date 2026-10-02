package com.octant.pipeline.report.chart;

import com.octant.pipeline.analysis.ChartForms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FigureGroups {

    public record Group(String key, String question, String whyTogether, List<String> metricIds,
                        String kind, String reference) {
    }

    public static final List<String> STANDALONE_REASONS = List.of(
            "量纲不同", "尺度差 >= 10 倍", "受众不同", "隐私分级不同", "样本量不足");

    private FigureGroups() {
    }

    public static List<Group> groups() {
        List<Group> out = new ArrayList<>();
        out.add(new Group("progress",
                "进度推进到哪、卡在哪",
                "同实体（同一存档的可达内容单元）+ 同为占比或计数，因此能放在一张进度图上",
                List.of("M1a", "M1c", "M2b", "M2c"),
                "share",
                "参照线 = 本存档可达内容总量的中位单元（同存档自比）"));
        out.add(new Group("time",
                "时间花在哪、是否疲劳",
                "同一条时间轴 + 同一实体（同一玩家的会话），因此按阶段读一条线",
                List.of("D1_PACE", "M3e", "D2_DEPTH"),
                "timeseries",
                "参照线 = 本存档各阶段的基线中位（阶段内自比）"));
        out.add(new Group("breadth",
                "内容面广不广、均不均",
                "同实体（内容类别）+ 同为无单位占比，因此按类别并列比较",
                List.of("M4", "M5a", "M5b", "M6e"),
                "grouped",
                "参照线 = 类别数均分线（1 除以类别数）"));
        out.add(new Group("repetition",
                "是否在做重复劳动",
                "同为无单位占比或计数 + 同一个读者问题，因此分组并列",
                List.of("M7a", "M7b", "M7c", "M7d", "M7e"),
                "grouped",
                "参照线 = 重复占比的存档内中位"));
        out.add(new Group("combat",
                "打得如何",
                "同为无单位占比或计数 + 同一个读者问题，因此分组并列",
                List.of("M8a", "M8b", "M8c", "D6_COMBAT"),
                "grouped",
                "参照带 = 存档内交战结果的中位区间"));
        out.add(new Group("profile",
                "七个维度画像（一张图看完）",
                "同为 0 到 1 归一化（轴间不可相加，故只做并列比较）+ 同一实体，因此能放在一张图上",
                List.of("D1_PACE", "D2_DEPTH", "D3_BREADTH", "D4_DISPERSION", "D5_CRAFT",
                        "D6_COMBAT", "D7_PERSIST"),
                "profile",
                "参照带 = 0 到 1 归一化区间的中线 0.5"));
        out.add(new Group("kpi",
                "总量与极端值（各自独立成图）",
                "不合并理由（闭集合）：尺度差 >= 10 倍——小时、次、张 与无量纲占比同轴会把占比压成 0",
                List.of("M1b", "M2a", "M3a", "M3d", "M5c", "M5d", "M6b", "M6c", "M6d", "M8e"),
                "independent",
                "各量各自量程，不共享坐标"));
        return out;
    }

    public static Map<String, String> mergeMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Group g : groups()) {
            for (String m : g.metricIds()) {
                out.put(m, g.key());
            }
        }
        return out;
    }

    public static Group groupOfMetric(String metricId) {
        for (Group g : groups()) {
            if (g.metricIds().contains(metricId)) {
                return g;
            }
        }
        return null;
    }

    public static boolean isTrendLike(String label) {
        if (label == null) {
            return false;
        }
        String s = label.toLowerCase(java.util.Locale.ROOT);
        for (String k : List.of("趋势", "变化", "随时间", "pace", "fatigue")) {
            if (s.contains(k.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean needsSeries(Group g) {
        return "timeseries".equals(g.kind());
    }

    public static boolean sameRuler(List<String> units) {
        String first = units.isEmpty() ? "" : norm(units.get(0));
        for (String u : units) {
            if (!norm(u).equals(first)) {
                return false;
            }
        }
        return true;
    }

    private static String norm(String u) {
        String s = u == null ? "" : u.trim();
        return "-".equals(s) || "无单位".equals(s) ? "" : s;
    }

    public static String formHint(Group g) {
        return switch (g.kind()) {
            case "share" -> ChartForms.SHARE_BAR + "（分段条）";
            case "profile" -> ChartForms.PROFILE_PARALLEL + "（多维对比）";
            case "grouped" -> ChartForms.BULLET_BAR + "（分组并列）";
            case "timeseries" -> ChartForms.LINE + "（折线）";
            default -> ChartForms.KPI_UNIT + "（各自独立）";
        };
    }
}
