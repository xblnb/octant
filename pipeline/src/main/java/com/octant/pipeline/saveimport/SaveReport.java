package com.octant.pipeline.saveimport;

import com.octant.common.model.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class SaveReport {

    public static final String SCHEMA = "save-analysis@1.0.0";

    private SaveReport() {
    }

    public record Analysis(
            String schema,
            Map<String, Object> progress,
            Map<String, Object> timeline,
            Map<String, Object> activity,
            Map<String, Object> breadth,
            Map<String, Object> sessionDerived,
            Map<String, Object> coverage,
            List<Map<String, Object>> notApplicable,
            List<String> anomalies,
            Map<String, Object> dataQuality,
            Map<String, Object> caveats) {

        public boolean passed() {
            return anomalies.isEmpty();
        }
    }

    public static Analysis analyze(SaveReader.SaveSnapshot save, AdvancementCatalog.Catalog cat,
                                  String worldNameForRedaction, String playerNameForRedaction) {
        Set<String> doneIds = save.doneTimestampsIso().keySet();
        int done = save.done();
        int total = cat.total();
        int covered = cat.coveredBy(doneIds);

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("done", done);
        progress.put("undone", save.undone());
        progress.put("undoNe", save.undone());
        progress.put("denominator", total);
        progress.put("denominatorSource", "真实整合包扫描（" + cat.jars() + " 个 mod jar + kubejs"
                + (cat.extraJars() > 0 ? " + 额外数据包 jar " + cat.extraJars() + " 个" : "")
                + "），见 denominatorCaveats");
        double rate = total == 0 ? -1.0 : 100.0 * covered / total;
        progress.put("completionPercent", round2(rate));
        progress.put("completionPercentNumerator", "doneCoveredByDenominator（与分母同层）");
        progress.put("doneCoveredByDenominator", covered);
        progress.put("doneOutsideDenominator", done - covered);
        progress.put("denominatorCaveats", cat.caveats());
        progress.put("denominatorScopeWarning",
                cat.extraJars() > 0
                        ? "分母**已含原版成就定义**（额外数据包 jar 已计入）⇒ 本百分率不再有"
                                + "「分母缺原版」造成的偏高偏置；仍有 "
                                + (done - covered) + " 条达成落在分母之外（整合包外/未登记内容）"
                        : "分母**只统计数据包提供的成就**（mod jar + kubejs 的 `data/<ns>/advancements/`），"
                                + "**不含原版成就**；而玩家的达成数**包含原版成就** ⇒ 已完成里有 "
                                + (done - covered) + " 条落在分母之外 ⇒ 本百分率是**偏高**的估计"
                                + "（真分母更大 ⇒ 真实完成率更低）。"
                                + "把原版补进分母的办法：传 `--version-jar <版本jar>`"
                                + "（原版定义就在整合包的版本 jar 里，实测 1.20.1 = 1,271 条）。");
        progress.put("applicable", total > 0);

        List<Map.Entry<String, String>> scored = new ArrayList<>();
        for (Map.Entry<String, String> e : save.doneTimestampsIso().entrySet()) {
            if (e.getValue() != null) {
                scored.add(e);
            }
        }
        scored.sort(Comparator
                .comparing(Map.Entry<String, String>::getValue)
                .thenComparing(Map.Entry::getKey));
        List<String> anomalies = detectTimelineAnomalies(scored);

        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("firstAchievementAt", save.earliestDoneIso());
        timeline.put("lastAchievementAt", save.latestDoneIso());
        timeline.put("spanDays", round2(save.spanDays()));
        timeline.put("achievementsWithTimestamp", scored.size());
        timeline.put("achievementsWithoutTimestamp", save.doneWithoutTimestamp());
        timeline.put("medianGapDays", round2(medianGapDays(scored)));
        timeline.put("topGaps", topGaps(scored, 5));
        timeline.put("applicable", !scored.isEmpty());

        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("playTimeTickSource", "stats/minecraft:custom/minecraft:play_time");
        activity.put("playTimeTicks", save.playTimeTicks());
        activity.put("playHours", round2(save.playHours()));
        activity.put("totalWorldTimeHours", save.totalWorldTimeTicks() < 0
                ? -1.0 : round2(save.totalWorldTimeTicks() / 20.0 / 3600.0));
        activity.put("mobKills", counter(save.custom(), "minecraft:mob_kills"));
        activity.put("deaths", counter(save.custom(), "minecraft:deaths"));
        activity.put("damageDealt", counter(save.custom(), "minecraft:damage_dealt"));
        activity.put("damageTaken", counter(save.custom(), "minecraft:damage_taken"));
        activity.put("walkOneCm", counter(save.custom(), "minecraft:walk_one_cm"));
        activity.put("distinctMobTypesKilled", save.killed().size());
        activity.put("distinctBlocksMined", save.mined().size());
        activity.put("distinctItemsCrafted", save.crafted().size());
        activity.put("distinctItemsUsed", save.used().size());
        activity.put("distinctTypes", distinctModNamespaces(save));

        long sessions = counter(save.custom(), "minecraft:leave_game");
        activity.put("sessionCount", sessions);
        activity.put("sessionCountSource", "stats.minecraft:custom.minecraft:leave_game（登出次数）");
        activity.put("avgSessionMinutes", (sessions > 0 && save.playTimeTicks() >= 0)
                ? round2(save.playTimeTicks() / 20.0 / 60.0 / sessions) : -1.0);
        activity.put("avgSessionNote", "由「总 tick ÷ 20 ÷ 60 ÷ 会话数」除出的**均值**；"
                + "存档没有逐会话边界 ⇒ 得不到每次会话各自的长度、中位数与分布");
        activity.put("applicable", save.playTimeTicks() >= 0);

        Set<String> touchedNs = new TreeSet<>();
        touchedNs.addAll(modNamespacesOf(save.killed().keySet()));
        touchedNs.addAll(modNamespacesOf(save.mined().keySet()));
        touchedNs.addAll(modNamespacesOf(save.crafted().keySet()));
        touchedNs.addAll(modNamespacesOf(save.used().keySet()));
        Set<String> doneNs = new TreeSet<>();
        for (String id : doneIds) {
            doneNs.add(namespaceOf(id));
        }
        Set<String> definitionNs = new TreeSet<>(cat.perNamespace().keySet());
        Set<String> reached = new TreeSet<>(doneNs);
        reached.retainAll(definitionNs);
        Set<String> untouched = new TreeSet<>(definitionNs);
        untouched.removeAll(doneNs);

        Map<String, Object> breadth = new LinkedHashMap<>();
        breadth.put("contentNamespacesTotal", cat.namespaceCount());
        breadth.put("contentNamespacesReachedByAchievements", reached.size());
        breadth.put("contentNamespacesUnreached", untouched.size());
        breadth.put("contentNamespacesTouchedByStats", touchedNs.size());
        breadth.put("topNamespacesByAchievements", nsList(cat.topNamespaces(15)));
        breadth.put("unreachedExamples", firstN(untouched, 12));
        breadth.put("unreachedAll", new ArrayList<>(untouched));
        breadth.put("applicable", !definitionNs.isEmpty());

        Map<String, Object> sessionDerived = new LinkedHashMap<>();
        sessionDerived.put("applicable", true);
        sessionDerived.put("partiallyAvailable", List.of(
                "会话数（= stats.minecraft:custom.minecraft:leave_game 登出次数）",
                "人均会话时长（总时长 ÷ 会话数，**均值**）"));
        sessionDerived.put("stillUnavailable", List.of(
                "每次会话各自的长度与分布（中位数/长尾）",
                "会话起始时刻与时段分布",
                "逐事件时间线（因此风筝的分钟级/10 分钟级采样无法从存档重建）"));
        sessionDerived.put("reason", "存档保留**累计**统计与成就达成时刻，**没有逐步会话边界** ⇒ "
                + "「会话数」与「均值时长」可由累计量得出（已给），**但每次会话的边界不可得**。"
                + "我第一版把整类判为「不适用」是**过度声明**，此处已更正。");

        List<Map<String, Object>> notApplicable = new ArrayList<>();
        notApplicable.add(na("每次会话各自的长度与分布（中位数/长尾）",
                "逐次会话边界（登录-登出成对事件，或带 sessionId 的事件流）—— 存档只有登出**总次数**"));
        notApplicable.add(na("事件密度 / 分钟级活跃度", "带时间戳的逐事件流（本存档只有累计计数）"));
        notApplicable.add(na("挂机与暂停的区分", "刻级推进量与暂停边界（本存档无 tick 级记录）"));
        notApplicable.add(na("节奏/时段分布（几点在玩）", "逐会话起始时刻列表（achievements 的时刻是离散达成点，不能代表在线区间）"));
        notApplicable.add(na("重复失败与卡点重试次数", "带结局的尝试事件（本存档无失败记录）"));
        notApplicable.add(na("资源瓶颈 / 机器与任务进度", "容器与任务完成事件（本数据源无此观测）"));

        Map<String, Object> coveredMetrics = new LinkedHashMap<>();
        coveredMetrics.put("progress.completionPercent", "真分母扫描（严格口径 `data/<ns>/advancements/`）+ 已达成成就数");
        coveredMetrics.put("timeline.firstAt/lastAt/spanDays/medianGapDays/topGaps", "advancements 的 criteria 时间戳");
        coveredMetrics.put("activity.playHours", "stats.minecraft:play_time（tick）");
        coveredMetrics.put("activity.sessionCount / avgSessionMinutes", "stats.minecraft:custom.minecraft:leave_game + play_time");
        coveredMetrics.put("activity.mobKills/deaths/damage/walkOneCm", "stats.minecraft:custom 的累计值");
        coveredMetrics.put("activity.distinct*", "stats 各分类的**键**数（种类而非次数）");
        coveredMetrics.put("breadth.*", "成就与 stats 里的命名空间集合 ∩ 真分母命名空间集合");
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("covered", coveredMetrics);
        coverage.put("coveredCount", coveredMetrics.size());
        coverage.put("notApplicableCount", notApplicable.size());
        coverage.put("rule", "**不允许**用累计量凑出会话级指标；不可得者一律列 notApplicable 并写明缺哪个观测量");

        Map<String, Object> dataQuality = new LinkedHashMap<>();
        dataQuality.put("advancementRecords", save.advancementRecords());
        dataQuality.put("malformedRecords", save.malformed());
        dataQuality.put("fileLevelMetadataKeys", save.fileLevelMetadata());
        dataQuality.put("fileLevelMetadataNote",
                "成就 json 顶层除成就条目外还有文件级元数据键（本存档 = `DataVersion`）；"
                        + "它**不是成就**，不计入达成数、也不计入分母");
        dataQuality.put("statsDataVersion", save.statsDataVersion());
        dataQuality.put("levelDataVersion", save.levelDataVersion());
        dataQuality.put("levelNameReadable", save.levelNamePresent());
        dataQuality.put("statsSectionsPresent", statsSections(save));

        Map<String, Object> caveats = new LinkedHashMap<>();
        caveats.put("playTimeUnit", "tick（20 tick = 1 秒）");
        caveats.put("achievementTimestampBasis", "criteria 时间戳中的**最早一个**（保守：不会把达成时刻推后）");
        caveats.put("whatThisIsNot", "这不是事件流分析：没有会话、没有逐事件时间线 ⇒ 见 notApplicable");

        return new Analysis(SCHEMA, progress, timeline, activity, breadth, sessionDerived,
                coverage, notApplicable, anomalies, dataQuality, caveats);
    }

    static final String PLAUSIBLE_FLOOR = "2010-01-01T00:00:00Z";
    static final double MAX_PLAUSIBLE_GAP_DAYS = 365.0;

    static List<String> detectTimelineAnomalies(List<Map.Entry<String, String>> sortedByTime) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : sortedByTime) {
            if (e.getValue().compareTo(PLAUSIBLE_FLOOR) < 0) {
                out.add("implausible-early: " + e.getKey() + " 的达成时刻 " + e.getValue()
                        + " 早于合理下界 " + PLAUSIBLE_FLOOR + "（MC 1.20.1 于 2023 年发布）");
            }
        }
        for (int i = 1; i < sortedByTime.size(); i++) {
            long a = Instant.parse(sortedByTime.get(i - 1).getValue()).toEpochMilli();
            long b = Instant.parse(sortedByTime.get(i).getValue()).toEpochMilli();
            double days = (b - a) / 86400_000.0;
            if (days > MAX_PLAUSIBLE_GAP_DAYS) {
                out.add("implausible-gap: " + sortedByTime.get(i - 1).getKey() + " → "
                        + sortedByTime.get(i).getKey() + " 相隔 " + round2(days)
                        + " 天（超过上限 " + MAX_PLAUSIBLE_GAP_DAYS + " 天）");
            }
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (var e : sortedByTime) {
            seen.merge(e.getValue(), 1, Integer::sum);
        }
        List<String> dup = new ArrayList<>();
        for (var e : seen.entrySet()) {
            if (e.getValue() > 1) {
                dup.add(e.getKey() + "×" + e.getValue());
            }
        }
        if (!dup.isEmpty()) {
            INFORMATION_NOTES.add("duplicate-timestamp: 有 " + dup.size()
                    + " 个时刻被多个成就共用（例：" + firstN(dup, 3) + "）");
        }
        return out;
    }

    static final List<String> INFORMATION_NOTES = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static List<String> scanForLeaks(String artifactText, List<String> sensitive) {
        List<String> hits = new ArrayList<>();
        for (String s : sensitive) {
            if (s != null && !s.isBlank() && artifactText.contains(s)) {
                hits.add("leak: 产物里出现了敏感串（长度 " + s.length() + "）");
            }
        }
        if (artifactText.matches("(?s).*\\b[A-Za-z]:[\\\\/].*")) {
            hits.add("leak: 产物里出现了盘符绝对路径");
        }
        if (artifactText.contains("/saves/") || artifactText.contains("\\saves\\")) {
            hits.add("leak: 产物里出现了 saves/ 路径片段");
        }
        return hits;
    }

    static String redact(String text, List<String> sensitive) {
        String out = text;
        for (String s : sensitive) {
            if (s != null && !s.isBlank()) {
                out = out.replace(s, "<redacted>");
            }
        }
        return out;
    }

    public static Map<String, Object> write(Analysis a, Path outDir,
                                           SaveReader.SaveSnapshot save,
                                           AdvancementCatalog.Catalog cat) throws IOException {
        Files.createDirectories(outDir);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("progress", a.progress());
        root.put("timeline", a.timeline());
        root.put("activity", a.activity());
        root.put("breadth", a.breadth());
        root.put("sessionDerived", a.sessionDerived());
        root.put("coverage", a.coverage());
        root.put("notApplicable", a.notApplicable());
        root.put("anomalies", a.anomalies());
        root.put("passed", a.passed());
        root.put("dataQuality", a.dataQuality());
        root.put("caveats", a.caveats());

        Path jsonFile = outDir.resolve("save-analysis.json");
        Files.writeString(jsonFile, Json.encode(root) + "\n", StandardCharsets.UTF_8);

        Path htmlFile = outDir.resolve("report.html");
        Files.writeString(htmlFile, renderHtml(a, cat), StandardCharsets.UTF_8);

        Map<String, Object> files = new LinkedHashMap<>();
        files.put("save-analysis.json", fingerprint(jsonFile));
        files.put("report.html", fingerprint(htmlFile));
        return files;
    }

    static Map<String, Object> fingerprint(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bytes", b.length);
        try {
            m.put("sha256_16", java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(b), 0, 8));
        } catch (Exception ex) {
            m.put("sha256_16", "?");
        }
        return m;
    }

    static String renderHtml(Analysis a, AdvancementCatalog.Catalog cat) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n");
        sb.append("<meta name=\"report-schema\" content=\"").append(SCHEMA).append("\">\n");
        sb.append("<title>真实存档分析报告</title>\n<style>\n");
        sb.append("body{font-family:system-ui,'Segoe UI','Microsoft YaHei',sans-serif;margin:28px;color:#1c2530;background:#fafbfc}\n");
        sb.append("h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:26px 0 8px;border-left:4px solid #336bad;padding-left:8px}\n");
        sb.append("table{border-collapse:collapse;margin:8px 0}td,th{border:1px solid #d7dde4;padding:4px 9px;font-size:13px;text-align:left}\n");
        sb.append("th{background:#eef3f8}.k{color:#5a6b7c}.na{color:#a05a00}ul{margin:6px 0 6px 18px}li{font-size:13px;margin:2px 0}\n");
        sb.append(".big{font-size:26px;font-weight:600;color:#1b4f86}.ok{color:#1a7f37;font-weight:600}.bad{color:#b42318;font-weight:600}\n");
        sb.append("</style>\n</head>\n<body>\n");
        sb.append("<h1>真实存档分析报告</h1>\n");
        sb.append("<p class=\"k\">数据来源 = 真实整合包的真实存档（<b>只读</b>）。")
                .append("会话<b>数与均值</b>可得（见 ③）；<b>每次会话各自的边界与逐事件时间线不可得</b>，")
                .append("受影响指标列在「因缺数据不适用」。</p>\n");

        Object pct = a.progress().get("completionPercent");
        sb.append("<h2>① 进度完成率（真分母）</h2>\n");
        sb.append("<p><span class=\"big\">").append(fmt(pct)).append("%</span> ")
                .append("= 已完成 <b>").append(a.progress().get("done")).append("</b> / 总可达 <b>")
                .append(a.progress().get("denominator")).append("</b>（真分母）</p>\n");
        sb.append("<p class=\"k\">分母来源：").append(esc(String.valueOf(a.progress().get("denominatorSource"))))
                .append("</p>\n");
        sb.append(singleBar(pct));
        sb.append("<table><tr><th>项</th><th>值</th></tr>");
        sb.append(row("已完成（done=true）", a.progress().get("done")));
        sb.append(row("未完成（undone）", a.progress().get("undoNe")));
        sb.append(row("分母内已完成", a.progress().get("doneCoveredByDenominator")));
        sb.append(row("已完成但不在分母内（整合包外/原版特殊）", a.progress().get("doneOutsideDenominator")));
        sb.append("</table>\n");
        sb.append("<p class=\"k\">⚠️ <b>口径（决定这个数偏高还是偏低，必须读）</b>：")
                .append(esc(String.valueOf(a.progress().get("denominatorScopeWarning"))))
                .append("</p>\n");

        sb.append("<h2>② 玩家卡在哪（达成之间最长的等待）</h2>\n");
        sb.append("<p class=\"k\">读法：<b>「在 X 之后，过了 N 天才又拿到下一个成就」</b>"
                + " —— N 越大，越可能是这一步卡住了玩家（或这一段他去做别的事了）。</p>\n");
        Object gapsObj = a.timeline().get("topGaps");
        if (gapsObj instanceof List<?> gaps && !gaps.isEmpty()) {
            sb.append("<table><tr><th>排名</th><th>卡在哪个成就之后</th>"
                    + "<th>等待（天）</th><th>下次达成时刻</th></tr>");
            int rank = 0;
            for (Object o : gaps) {
                if (!(o instanceof Map<?, ?> g)) {
                    continue;
                }
                rank++;
                sb.append("<tr><td>").append(rank).append("</td><td><code>")
                        .append(esc(String.valueOf(g.get("afterAchievement"))))
                        .append("</code></td><td><b>").append(fmt(g.get("days")))
                        .append("</b></td><td>").append(esc(String.valueOf(g.get("at"))))
                        .append("</td></tr>");
            }
            sb.append("</table>\n");
            sb.append("<p class=\"k\">共 ").append(gaps.size())
                    .append(" 条（这是本报告里收录的全部间隔，不是抽样）—— "
                            + "把名字翻成中文/人话的工序归呈现层（本件不改口径）。</p>\n");
        } else {
            sb.append("<p class=\"k\">（不足两条带时间戳的达成 ⇒ 算不出间隔；见 ⑤ 不适用清单）</p>\n");
        }

        sb.append("<h2>③ 真实时间序（来自成就达成时刻）</h2>\n");
        sb.append("<table><tr><th>项</th><th>值</th></tr>");
        sb.append(row("首次达成", a.timeline().get("firstAchievementAt")));
        sb.append(row("最近达成", a.timeline().get("lastAchievementAt")));
        sb.append(row("跨度（天）", a.timeline().get("spanDays")));
        sb.append(row("相邻达成间隔中位数（天）", a.timeline().get("medianGapDays")));
        sb.append(row("有时间戳的成就数", a.timeline().get("achievementsWithTimestamp")));
        sb.append("</table>\n");
        sb.append("<p class=\"k\">时间序异常检测：");
        if (a.anomalies().isEmpty()) {
            sb.append("<span class=\"ok\">0 项 —— 通过</span>");
        } else {
            sb.append("<span class=\"bad\">").append(a.anomalies().size()).append(" 项 —— 不通过</span>");
        }
        sb.append("</p>\n");
        if (!a.anomalies().isEmpty()) {
            sb.append("<ul>");
            for (String x : a.anomalies()) {
                sb.append("<li>").append(esc(x)).append("</li>");
            }
            sb.append("</ul>\n");
        }

        sb.append("<h2>④ 活动总量（stats 实测）</h2>\n");
        sb.append("<table><tr><th>指标</th><th>值</th></tr>");
        for (String k : List.of("playHours", "totalWorldTimeHours", "sessionCount",
                "avgSessionMinutes", "mobKills", "deaths",
                "distinctMobTypesKilled", "distinctBlocksMined", "distinctItemsCrafted",
                "distinctItemsUsed", "walkOneCm")) {
            sb.append(row(labelOf(k), a.activity().get(k)));
        }
        sb.append("</table>\n");
        sb.append("<p class=\"k\">游玩时长单位 = tick（20 tick = 1 秒），来源 ")
                .append(esc(String.valueOf(a.activity().get("playTimeTickSource")))).append("。")
                .append(esc(String.valueOf(a.activity().get("sessionCountSource"))))
                .append("；人均值为**均值**：")
                .append(esc(String.valueOf(a.activity().get("avgSessionNote"))))
                .append("</p>\n");

        sb.append("<h2>⑤ 内容面广度（触达了多少模组/命名空间）</h2>\n");
        sb.append("<table><tr><th>项</th><th>值</th></tr>");
        sb.append(row("内容面总数（命名空间）", a.breadth().get("contentNamespacesTotal")));
        sb.append(row("被成就触达", a.breadth().get("contentNamespacesReachedByAchievements")));
        sb.append(row("尚未触达", a.breadth().get("contentNamespacesUnreached")));
        sb.append(row("被 stats 触达", a.breadth().get("contentNamespacesTouchedByStats")));
        sb.append("</table>\n");
        Object topNs = a.breadth().get("topNamespacesByAchievements");
        if (topNs instanceof List<?> nl && !nl.isEmpty()) {
            sb.append("<p class=\"k\">触达榜（按<b>该模组定义了</b>多少条成就排序；"
                    + "这是内容量榜，不等于玩家做了多少）：</p>\n");
            sb.append("<table><tr><th>#</th><th>命名空间</th><th>本模组成就定义条数</th></tr>");
            int i = 0;
            for (Object o : nl) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                i++;
                sb.append("<tr><td>").append(i).append("</td><td><code>")
                        .append(esc(String.valueOf(m.get("namespace")))).append("</code></td><td>")
                        .append(esc(fmt(m.get("count")))).append("</td></tr>");
            }
            sb.append("</table>\n");
        }
        sb.append(nsBar(a.breadth().get("topNamespacesByAchievements")));

        Object unreachedAll = a.breadth().get("unreachedAll");
        sb.append("<p class=\"k\"><b>还未被任何成就触达的命名空间</b>（共 ")
                .append(esc(fmt(a.breadth().get("contentNamespacesUnreached"))))
                .append(" 个）—— 对作者而言，这份清单比触达率更可执行：</p>\n");
        if (unreachedAll instanceof List<?> ua && !ua.isEmpty()) {
            sb.append("<table><tr><th>#</th><th>未触达命名空间</th></tr>");
            int i = 0;
            for (Object o : ua) {
                i++;
                sb.append("<tr><td>").append(i).append("</td><td><code>")
                        .append(esc(String.valueOf(o))).append("</code></td></tr>");
            }
            sb.append("</table>\n");
        } else {
            sb.append("<p class=\"k\">（本产物无 <code>unreachedAll</code> 字段 —— "
                    + "只有前 12 个示例；完整清单需用当前版本的分析器重新导出）</p>\n");
        }

        sb.append("<h2>⑤ 因缺数据<span class=\"na\">不适用</span>的指标（"
                ).append(a.notApplicable().size()).append(" 条）</h2>\n");
        sb.append("<p class=\"k\">").append(esc(String.valueOf(
                a.sessionDerived().get("reason")))).append("</p>\n");
        sb.append("<table><tr><th>指标</th><th>缺少的观测量</th></tr>");
        for (Map<String, Object> m : a.notApplicable()) {
            sb.append("<tr><td>").append(esc(String.valueOf(m.get("metric"))))
                    .append("</td><td>").append(esc(String.valueOf(m.get("missingObservation"))))
                    .append("</td></tr>");
        }
        sb.append("</table>\n");

        Object covered = a.coverage().get("covered");
        sb.append("<h2>⑥ 实际覆盖了哪些指标</h2>\n<table><tr><th>指标</th><th>来源</th></tr>");
        if (covered instanceof Map<?, ?> cm) {
            for (Map.Entry<?, ?> e : cm.entrySet()) {
                sb.append("<tr><td>").append(esc(String.valueOf(e.getKey())))
                        .append("</td><td>").append(esc(String.valueOf(e.getValue()))).append("</td></tr>");
            }
        }
        sb.append("</table>\n");

        sb.append("<h2>⑦ 数据质量与口径</h2>\n<table><tr><th>项</th><th>值</th></tr>");
        for (Map.Entry<String, Object> e : new TreeMap<>(a.dataQuality()).entrySet()) {
            sb.append(row(e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, Object> e : new TreeMap<>(a.caveats()).entrySet()) {
            sb.append(row(e.getKey(), e.getValue()));
        }
        sb.append("</table>\n");
        sb.append("<p class=\"k\">扫描口径：").append(esc(cat.caveats().toString())).append("</p>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    static String singleBar(Object pct) {
        double v = toDouble(pct, 0);
        double w = Math.max(2, Math.min(600, 600 * v / 100.0));
        StringBuilder sb = new StringBuilder();
        sb.append("<svg width=\"620\" height=\"34\" role=\"img\" aria-label=\"完成率条形\">\n");
        sb.append("  <rect x=\"0\" y=\"8\" width=\"600\" height=\"16\" fill=\"#eef3f8\" stroke=\"#d7dde4\"/>\n");
        sb.append("  <rect x=\"0\" y=\"8\" width=\"").append(fmt1(w))
                .append("\" height=\"16\" fill=\"#336bad\" data-value=\"").append(fmt(v)).append("\"/>\n");
        sb.append("  <text x=\"606\" y=\"21\" font-size=\"12\" fill=\"#5a6b7c\">").append(fmt(v)).append("%</text>\n");
        sb.append("</svg>\n");
        return sb.toString();
    }

    static String nsBar(Object list) {
        if (!(list instanceof List<?> l) || l.isEmpty()) {
            return "<p class=\"k\">（无命名空间数据）</p>\n";
        }
        int max = 1;
        for (Object o : l) {
            if (o instanceof Map<?, ?> m && m.get("count") instanceof Number n) {
                max = Math.max(max, n.intValue());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<svg width=\"620\" height=\"").append(l.size() * 22 + 10)
                .append("\" role=\"img\" aria-label=\"触达模组榜\">\n");
        int y = 6;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String ns = String.valueOf(m.get("namespace"));
            int c = m.get("count") instanceof Number n ? n.intValue() : 0;
            double w = 380.0 * c / max;
            sb.append("  <text x=\"0\" y=\"").append(y + 11)
                    .append("\" font-size=\"11\" fill=\"#1c2530\">").append(esc(ns)).append("</text>\n");
            sb.append("  <rect x=\"190\" y=\"").append(y).append("\" width=\"").append(fmt1(w))
                    .append("\" height=\"13\" fill=\"#4e8cc9\" data-value=\"").append(c).append("\"/>\n");
            sb.append("  <text x=\"").append(fmt1(192 + w)).append("\" y=\"").append(y + 11)
                    .append("\" font-size=\"11\" fill=\"#5a6b7c\">").append(c).append("</text>\n");
            y += 22;
        }
        sb.append("</svg>\n");
        return sb.toString();
    }

    private static Map<String, Object> na(String metric, String missing) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric);
        m.put("missingObservation", missing);
        return m;
    }

    private static String row(String k, Object v) {
        return "<tr><td>" + esc(k) + "</td><td>" + esc(fmt(v)) + "</td></tr>";
    }

    private static String labelOf(String key) {
        return switch (key) {
            case "playHours" -> "游玩时长（小时）";
            case "totalWorldTimeHours" -> "世界总时间（小时）";
            case "sessionCount" -> "会话数（= 登出次数）";
            case "avgSessionMinutes" -> "人均会话时长（分钟，均值）";
            case "mobKills" -> "击杀生物总数";
            case "deaths" -> "死亡次数";
            case "distinctMobTypesKilled" -> "击杀过的生物种类";
            case "distinctBlocksMined" -> "挖过的方块种类";
            case "distinctItemsCrafted" -> "制作过的物品种类";
            case "distinctItemsUsed" -> "使用过的物品种类";
            case "walkOneCm" -> "步行距离（cm）";
            default -> key;
        };
    }

    private static List<Map<String, Object>> nsList(List<Map.Entry<String, Integer>> in) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : in) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("namespace", e.getKey());
            m.put("count", e.getValue());
            out.add(m);
        }
        return out;
    }

    private static List<String> firstN(Iterable<String> it, int n) {
        List<String> out = new ArrayList<>();
        for (String s : it) {
            if (out.size() >= n) {
                break;
            }
            out.add(s);
        }
        return out;
    }

    private static long counter(Map<String, Long> m, String k) {
        Long v = m.get(k);
        return v == null ? -1L : v;
    }

    private static Set<String> modNamespacesOf(Set<String> ids) {
        Set<String> out = new TreeSet<>();
        for (String id : ids) {
            String ns = namespaceOf(id);
            if (!ns.isBlank() && !"minecraft".equals(ns)) {
                out.add(ns);
            }
        }
        return out;
    }

    private static String namespaceOf(String id) {
        int i = id.indexOf(':');
        return i < 0 ? "" : id.substring(0, i);
    }

    private static Map<String, Object> distinctModNamespaces(SaveReader.SaveSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("killed", modNamespacesOf(s.killed().keySet()).size());
        m.put("mined", modNamespacesOf(s.mined().keySet()).size());
        m.put("crafted", modNamespacesOf(s.crafted().keySet()).size());
        m.put("used", modNamespacesOf(s.used().keySet()).size());
        return m;
    }

    private static Map<String, Object> statsSections(SaveReader.SaveSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("custom", s.custom().size());
        m.put("killed", s.killed().size());
        m.put("mined", s.mined().size());
        m.put("crafted", s.crafted().size());
        m.put("used", s.used().size());
        m.put("pickedUp", s.pickedUp().size());
        m.put("dropped", s.dropped().size());
        return m;
    }

    private static double medianGapDays(List<Map.Entry<String, String>> sorted) {
        if (sorted.size() < 2) {
            return -1.0;
        }
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            long a = Instant.parse(sorted.get(i - 1).getValue()).toEpochMilli();
            long b = Instant.parse(sorted.get(i).getValue()).toEpochMilli();
            gaps.add(b - a);
        }
        gaps.sort(Comparator.naturalOrder());
        int n = gaps.size();
        long median = n % 2 == 1 ? gaps.get(n / 2)
                : (gaps.get(n / 2 - 1) + gaps.get(n / 2)) / 2;
        return median / 86400_000.0;
    }

    private static List<Map<String, Object>> topGaps(List<Map.Entry<String, String>> sorted, int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (sorted.size() < 2) {
            return out;
        }
        List<Object[]> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            long a = Instant.parse(sorted.get(i - 1).getValue()).toEpochMilli();
            long b = Instant.parse(sorted.get(i).getValue()).toEpochMilli();
            gaps.add(new Object[]{b - a, sorted.get(i).getKey(), sorted.get(i).getValue()});
        }
        gaps.sort((x, y) -> {
            int c = Long.compare((Long) y[0], (Long) x[0]);
            return c != 0 ? c : String.valueOf(x[1]).compareTo(String.valueOf(y[1]));
        });
        for (int i = 0; i < Math.min(n, gaps.size()); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("days", round2(((Long) gaps.get(i)[0]) / 86400_000.0));
            m.put("afterAchievement", gaps.get(i)[1]);
            m.put("at", gaps.get(i)[2]);
            out.add(m);
        }
        return out;
    }

    private static double toDouble(Object o, double dflt) {
        return o instanceof Number n ? n.doubleValue() : dflt;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String fmt1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String fmt(Object v) {
        if (v == null) {
            return "-";
        }
        if (v instanceof Double d) {
            return String.format(java.util.Locale.ROOT, "%.2f", d);
        }
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            return String.valueOf(v).length() > 300
                    ? String.valueOf(v).substring(0, 300) + "…" : String.valueOf(v);
        }
        return String.valueOf(v);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String human(double hours) {
        if (hours < 0) {
            return "不适用";
        }
        Duration d = Duration.ofMinutes(Math.round(hours * 60));
        return d.toDays() + " 天 " + (d.toHours() % 24) + " 小时";
    }
}
