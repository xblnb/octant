package com.octant.pipeline.kiteview;

import java.util.ArrayList;
import java.util.List;

public final class KiteChannels {

    private KiteChannels() {
    }

    public static final String TOKEN_SMOOTH = "STATE.kite.smooth";
    public static final String TOKEN_URGENT = "STATE.kite.urgent";

    public static final String HEX_SMOOTH = "#70C8E8";
    public static final String HEX_URGENT = "#B84038";

    public static final String LEGEND_SMOOTH = "平稳";
    public static final String LEGEND_URGENT = "急促";

    public static final String DASH_SMOOTH = "none";
    public static final String DASH_URGENT = "7 4";

    public static final String COLOR_ROLE = "state";

    public static final int[] WIDTH_BINS = {1, 2, 3};

    public static final double[] STAGE_FILL_OPACITY = {0.10d, 0.14d, 0.18d};
    public static final String[] STAGE_TEXTURE = {"none", "sparse", "dense"};
    public static final String[] STAGE_LABELS = {"阶段 1", "阶段 2", "阶段 3"};

    public static final List<String> FORBIDDEN_VISIBLE_TOKENS = List.of(
            "健康", "不健康", "异常", "正常", "失败", "优劣", "好", "坏", "问题");

    public static final List<String> FORBIDDEN_CAUSAL_TOKENS = List.of(
            "退坑", "迷路", "卡点", "超载", "无脑", "失衡", "认知负荷", "玩家放弃");

    public static List<String> validateChannelMappings(String colorEncoded, String widthEncoded) {
        List<String> bad = new ArrayList<>();
        if (colorEncoded == null || colorEncoded.trim().isEmpty()) {
            bad.add("颜色映射未声明被编码量");
        }
        if (widthEncoded == null || widthEncoded.trim().isEmpty()) {
            bad.add("线宽映射未声明被编码量");
        }
        if (colorEncoded != null && widthEncoded != null
                && colorEncoded.trim().equalsIgnoreCase(widthEncoded.trim())) {
            bad.add("两条映射的被编码量同名（" + colorEncoded + "）⇒ 通道分工不成立（HIG-COL-20.6②）");
        }
        return bad;
    }

    public static List<String> mappingDeclarations() {
        return List.of(
                "颜色 → U_t 档位（HIG-COL-20.2）：U_t < 1.0 = 平稳（" + HEX_SMOOTH + "）；U_t ≥ 1.0 = 急促（" + HEX_URGENT + "）",
                "线宽 → v 档位（HIG-COL-18）：v 的三分位分档，取 1 / 2 / 3 px，禁止连续插值");
    }

    public static List<String> validateDashSecondCarrier(List<String> dashValues) {
        List<String> bad = new ArrayList<>();
        if (dashValues == null || dashValues.isEmpty()) {
            bad.add("产物里没有任何 stroke-dasharray 声明 ⇒ 线型第二载体不存在（HIG-COL-20.5）");
            return bad;
        }
        boolean hasSolid = false;
        boolean hasDashed = false;
        for (String d : dashValues) {
            if (d == null) {
                continue;
            }
            if (DASH_SMOOTH.equals(d.trim())) {
                hasSolid = true;
            } else if (!d.trim().isEmpty() && !"0".equals(d.trim())) {
                hasDashed = true;
            }
        }
        if (!hasSolid) {
            bad.add("缺少实线（平稳）段落 ⇒ 线型第二载体不成对（HIG-COL-20.5）");
        }
        if (!hasDashed) {
            bad.add("缺少虚线（急促）段落 ⇒ 线型第二载体不成对（HIG-COL-20.5）");
        }
        return bad;
    }

    public static List<String> validateVisibleFace(String html) {
        List<String> bad = new ArrayList<>();
        if (html == null) {
            bad.add("产物为空");
            return bad;
        }
        for (String t : FORBIDDEN_VISIBLE_TOKENS) {
            if (html.contains(t)) {
                bad.add("可见面出现中性语义禁词「" + t + "」（HIG-COL-20.4①）");
            }
        }
        for (String t : FORBIDDEN_CAUSAL_TOKENS) {
            if (html.contains(t)) {
                bad.add("可见面出现因果断言词「" + t + "」（形态可呈现，因果不得写成断言）");
            }
        }
        return bad;
    }

    public static List<String> validateStageChannels(String html) {
        List<String> bad = new ArrayList<>();
        if (html == null) {
            return bad;
        }
        for (String t : STAGE_LABELS) {
            if (!html.contains(t)) {
                bad.add("阶段标签缺失：" + t);
            }
        }
        if (!html.contains("data-grid-plane") && !html.contains("data-stage-plane")) {
            bad.add("阶段平面未声明（data-grid-plane / data-stage-plane 均缺失）");
        }
        if (html.contains("data-stage-hue")) {
            bad.add("阶段借用了色相通道（data-stage-hue 存在）");
        }
        if (html.contains("data-stage-dash")) {
            bad.add("阶段借用了线型通道（data-stage-dash 存在）");
        }
        if (html.contains("data-stage-width")) {
            bad.add("阶段借用了线宽通道（data-stage-width 存在）");
        }
        return bad;
    }
}
