package com.octant.pipeline.kiteview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.octant.pipeline.kiteview.KiteViewData.Sample;
import com.octant.pipeline.kiteview.KiteViewData.Series;

public final class KiteViewPageChecks {

    private KiteViewPageChecks() {
    }

    public static final List<String> CHECK_NAMES = List.of(
            "K-1 可追溯", "K-2 无脚本回退", "K-3 离线自足", "K-4 线型第二载体",
            "K-5 可见面中性", "K-6 阶段通道纪律", "K-7 通道分工", "K-8 交互开关形态", "K-9 确定性",
            "K-10 色相枚举与 valenceNeutral");

    public static final List<String> ALLOWED_HEX = List.of(
            "#70c8e8", "#b84038",
            "#0d1216", "#12181d", "#1c252c", "#31404b", "#5d7180", "#7d94a4", "#b7c6d2",
            "#101418", "#161c22", "#263039", "#6b7c88", "#8fa6b5", "#9fb3c0", "#c8d6e0", "#e8eef2", "#f2f6f9");

    private static final Pattern SPAN = Pattern.compile(
            "<span data-t=\"(\\d+)\" data-k=\"([^\"]+)\">([^<]*)</span>");
    private static final Pattern OBS = Pattern.compile(
            "\\{\"k\":\"([^\"]+)\",\"v\":([^,]+),\"src\":\"([^\"]*)\",\"line\":(\\d+)\\}");
    private static final Pattern POINT = Pattern.compile("\\{\"i\":(\\d+),\"t\":(\\d+),\"obs\":\\[(.*?)\\]\\}");

    public static Map<Long, Map<String, String>> tableOf(String html) {
        Map<Long, Map<String, String>> out = new LinkedHashMap<>();
        Matcher pt = POINT.matcher(html);
        while (pt.find()) {
            long t = Long.parseLong(pt.group(2));
            Map<String, String> keys = new LinkedHashMap<>();
            Matcher ob = OBS.matcher(pt.group(3));
            while (ob.find()) {
                String v = ob.group(2).trim();
                if ("null".equals(v)) {
                    keys.put(ob.group(1), "不可判定");
                } else {
                    keys.put(ob.group(1), String.format(java.util.Locale.ROOT, "%.3f", Double.valueOf(v)));
                }
            }
            out.put(Long.valueOf(t), keys);
        }
        return out;
    }

    public static List<String> checkTraceable(String html, Series series) {
        List<String> bad = new ArrayList<>();
        Map<Long, Map<String, String>> table = tableOf(html);
        if (table.isEmpty()) {
            bad.add("内嵌观测表为空 ⇒ 空集不得判通过（无任何值可查）");
            return bad;
        }
        int seen = 0;
        Matcher m = SPAN.matcher(html);
        while (m.find()) {
            seen++;
            long t = Long.parseLong(m.group(1));
            String k = m.group(2);
            String shown = m.group(3).trim();
            Map<String, String> row = table.get(Long.valueOf(t));
            if (row == null) {
                bad.add("展示值 (t=" + t + ", " + k + ") 的表里没有该时刻");
                continue;
            }
            String expect = row.get(k);
            if (expect == null) {
                bad.add("展示值 (t=" + t + ", " + k + ") 不在观测表键集合内");
                continue;
            }
            if (!expect.equals(shown)) {
                bad.add("展示值 (t=" + t + ", " + k + ") 与表内不一致：显示 " + shown + " / 表内 " + expect);
            }
        }
        if (seen == 0) {
            bad.add("页面里没有任何可追溯展示值（data-t/data-k 缺失）⇒ 不得判通过");
        }
        return bad;
    }

    public static List<String> checkFallback(String html) {
        List<String> bad = new ArrayList<>();
        if (!html.contains("<noscript>")) {
            bad.add("缺少 <noscript> 回退段");
            return bad;
        }
        int snapshots = count(html, "data-fallback-snapshot=\"");
        if (snapshots < KiteViewHtml.FALLBACK_SNAPSHOTS) {
            bad.add("静态 SVG 快照数 = " + snapshots + " < 要求 " + KiteViewHtml.FALLBACK_SNAPSHOTS);
        }
        Matcher sn = Pattern.compile("<figure data-fallback-snapshot=\"[^\"]+\">(.*?)</figure>",
                Pattern.DOTALL).matcher(html);
        int seenSnap = 0;
        while (sn.find()) {
            seenSnap++;
            String body = sn.group(1);
            for (String axis : List.of("PROG", "SPON", "GUID")) {
                int c = count(body, "data-tick=\"" + axis + ":");
                if (c < 5) {
                    bad.add("快照#" + seenSnap + " 的 " + axis + " 轴刻度数 = " + c + "（要求 ≥5）");
                }
            }
            for (String lab : List.of("进度", "自发性", "引导性")) {
                if (!body.contains("data-axis-label=\"" + lab + "\"")) {
                    bad.add("快照#" + seenSnap + " 缺轴标签 " + lab);
                }
            }
            if (count(body, "data-grid-plane=") < 1) {
                bad.add("快照#" + seenSnap + " 缺阶段网格平面（data-grid-plane）");
            }
        }
        return bad;
    }

    public static List<String> checkOffline(String html) {
        List<String> bad = new ArrayList<>();
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("http" + "://") || lower.contains("https" + "://")) {
            bad.add("出现远程地址字面量");
        }
        if (lower.contains("<link")) {
            bad.add("出现 <link>（外部样式/字体）");
        }
        if (lower.contains("@import")) {
            bad.add("出现 @import");
        }
        Matcher m = Pattern.compile("\\ssrc=\"([^\"]*)\"").matcher(lower);
        while (m.find()) {
            bad.add("出现外链 src：" + m.group(1));
        }
        return bad;
    }

    public static List<String> checkDashPairing(String html) {
        List<String> bad = new ArrayList<>();
        if (html.contains("data-drawable-segments=\"0\"")) {
            bad.add("[不适用] 上游数据下三轴同时可用且相邻的采样点对 = 0 ⇒ 没有可画段 ⇒ 线型第二载体**无作用对象**"
                    + "（不记通过；也不得读作实现缺陷）");
            return bad;
        }
        List<String> svgDash = KiteChannels.validateDashSecondCarrier(KiteViewHtml.dashValues(html));
        if (svgDash.isEmpty()) {
            return bad;
        }
        boolean canvasDash = html.contains("setLineDash(");
        if (canvasDash) {
            return bad;
        }
        bad.addAll(svgDash);
        return bad;
    }

    public static boolean isNotApplicable(List<String> violations) {
        if (violations.isEmpty()) {
            return false;
        }
        for (String v : violations) {
            if (!v.startsWith("[不适用]")) {
                return false;
            }
        }
        return true;
    }

    public static List<String> checkVisibleFace(String html) {
        return KiteChannels.validateVisibleFace(html);
    }

    public static List<String> checkStageChannels(String html) {
        return KiteChannels.validateStageChannels(html);
    }

    public static List<String> checkChannelMappings() {
        return KiteChannels.validateChannelMappings("U_t", "v");
    }

    public static List<String> checkUiToggles(String html) {
        List<String> bad = new ArrayList<>();
        if (html.contains("<details")) {
            bad.add("出现 <details>（本页用 hidden 布尔属性开合）");
        }
        if (html.contains("display:none")) {
            bad.add("出现内联 display:none");
        }
        if (html.contains("visibility:hidden")) {
            bad.add("出现内联 visibility:hidden");
        }
        if (html.contains("opacity:0") || html.contains("opacity: 0")) {
            bad.add("出现 opacity:0");
        }
        if (html.contains("aria-hidden")) {
            bad.add("出现 aria-hidden");
        }
        return bad;
    }

    public static List<String> checkDeterministic(String a, String b) {
        List<String> bad = new ArrayList<>();
        if (!a.equals(b)) {
            bad.add("两次渲染不一致（长度 " + a.length() + " vs " + b.length() + "）");
        }
        return bad;
    }

    public static List<String> checkHues(String html) {
        List<String> bad = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        Matcher m = Pattern.compile("#[0-9A-Fa-f]{6}").matcher(html);
        while (m.find()) {
            seen.add(m.group(0).toLowerCase(java.util.Locale.ROOT));
        }
        for (String hex : seen) {
            if (!ALLOWED_HEX.contains(hex)) {
                bad.add("出现允许集合外的色相 " + hex + "（色相只承 U_t；阶段不得另立色相）");
            }
        }
        if (seen.isEmpty()) {
            bad.add("全页没有任何颜色声明 ⇒ 空集不得判通过");
        }
        if (!html.contains("data-valence-neutral=\"true\"")) {
            bad.add("未声明 valenceNeutral = true");
        }
        return bad;
    }

    public static Map<String, List<String>> runAll(String html, Series series, String htmlSecondRender) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put(CHECK_NAMES.get(0), checkTraceable(html, series));
        out.put(CHECK_NAMES.get(1), checkFallback(html));
        out.put(CHECK_NAMES.get(2), checkOffline(html));
        out.put(CHECK_NAMES.get(3), checkDashPairing(html));
        out.put(CHECK_NAMES.get(4), checkVisibleFace(html));
        out.put(CHECK_NAMES.get(5), checkStageChannels(html));
        out.put(CHECK_NAMES.get(6), checkChannelMappings());
        out.put(CHECK_NAMES.get(7), checkUiToggles(html));
        out.put(CHECK_NAMES.get(8), checkDeterministic(html, htmlSecondRender));
        out.put(CHECK_NAMES.get(9), checkHues(html));
        return out;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    public static String describe(Series s) {
        double[] q = s.densityQuantiles();
        return String.format(java.util.Locale.ROOT, "样点=%d 夹具=%s v 分位=%.2f/%.2f 中位Δt=%.0f s",
                Integer.valueOf(s.size()), Boolean.valueOf(s.fixture()), Double.valueOf(q[0]),
                Double.valueOf(q[1]), Double.valueOf(KiteShapeCriteria.medianDeltaSec(s)));
    }

    public static String sampleText(Sample p) {
        return String.format(java.util.Locale.ROOT, "t=%d PROG=%.3f SPON=%.3f GUID=%.3f v=%.3f @%s:%d",
                Long.valueOf(p.tSec()), Double.valueOf(p.prog()), Double.valueOf(p.spon()),
                Double.valueOf(p.guid()), Double.valueOf(p.densityPerMin()), p.sourceFile(), Integer.valueOf(p.sourceLine()));
    }
}
