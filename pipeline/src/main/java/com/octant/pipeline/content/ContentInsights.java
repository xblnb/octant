package com.octant.pipeline.content;

import com.octant.pipeline.saveimport.AdvancementCatalog;
import com.octant.pipeline.stats.WilsonInterval;

import java.io.InputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ContentInsights {

    private static final int MIN_GAP_HOURS = 6;
    private static final int TOP_GAPS_LIMIT = 8;
    private static final int IDLE_MODS_LIMIT = 8;

    private static final int SHALLOW_MAX_PERCENT = 25;

    private static final int GROUP_MIN_TOTAL = 5;
    private static final int GROUP_LIMIT = 12;

    private static final Pattern TS = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})(?:Z|\\s*([+-]\\d{4}))?");
    private static final Pattern KV_INT = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(\\d+)");
    private static final Pattern TITLE_TRANSLATE = Pattern.compile(
            "\"title\"\\s*:\\s*\\{\\s*\"translate\"\\s*:\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        Map<String, Object> root = buildRoot(args);
        Path out = null;
        boolean jsonOnly = false;
        for (int i = 0; i < args.length; i++) {
            if ("--json-only".equals(args[i])) {
                jsonOnly = true;
            } else if ("--out".equals(args[i]) && i + 1 < args.length) {
                out = Path.of(args[++i]);
            }
        }
        String json = toJson(root);
        if (out != null) {
            Files.createDirectories(out);
            Files.writeString(out.resolve("content-insights.json"), json, StandardCharsets.UTF_8);
        }
        if (!jsonOnly) {
            System.out.println(human(root));
        }
    }

    public static String jsonFor(Path save, Path mods, Path kjs, List<Path> extraJars,
                                 Path assets, Path assetIndex) throws Exception {
        return jsonFor(save, mods, kjs, extraJars, assets, assetIndex, false);
    }

    public static String jsonForArgs(String[] args) throws Exception {
        return withCache(args);
    }

    private static String withCache(String[] args) throws Exception {
        Path cache = null;
        try {
            List<String> noCache = new ArrayList<>(List.of(args));
            if (noCache.remove("--no-cache")) {
            } else {
                cache = contentCacheFile(args);
                if (cache != null && Files.isRegularFile(cache)) {
                    String hit = Files.readString(cache, StandardCharsets.UTF_8);
                    if (!hit.isBlank()) {
                        return hit;
                    }
                }
            }
        } catch (Exception ignore) {
            cache = null;
        }
        String json = toJson(buildRoot(args));
        if (cache != null) {
            try {
                Files.createDirectories(cache.getParent());
                Files.writeString(cache, json, StandardCharsets.UTF_8);
            } catch (Exception ignore) {
            }
        }
        return json;
    }

    static final String CACHE_VERSION = "ci-2";

    static Path contentCacheFile(String[] args) {
        Map<String, String> o = parseArgs(args);
        String save = o.get("save");
        if (save == null || save.isBlank()) {
            return null;
        }
        StringBuilder fp = new StringBuilder("ci-cache@1|code=").append(CACHE_VERSION).append('|');
        fp.append("mods=").append(dirFingerprint(o.get("mods")));
        fp.append("|kjs=").append(dirFingerprint(o.get("kubejs")));
        fp.append("|quests=").append(dirFingerprint(o.get("quests")));
        fp.append("|xaero=").append(dirFingerprint(o.get("xaero")));
        fp.append("|vjar=").append(o.getOrDefault("version-jar", "-"));
        Path sp = Path.of(save);
        fp.append("|adv=").append(dirFingerprint(sp.resolve("advancements").toString()));
        fp.append("|stats=").append(dirFingerprint(sp.resolve("stats").toString()));
        fp.append("|questprog=").append(dirFingerprint(sp.resolve("ftbquests").toString()));
        fp.append("|assets=").append(dirFingerprint(o.get("assets")));
        String name = "content-" + Integer.toHexString(fp.toString().hashCode()) + ".json";
        return com.octant.common.privacy.OctantPaths.dataDir(Path.of(save)).resolve("cache").resolve(name);
    }

    static String dirFingerprint(String dir) {
        if (dir == null || dir.isBlank()) {
            return "-";
        }
        Path p = Path.of(dir);
        if (!Files.isDirectory(p)) {
            return "missing";
        }
        long count = 0;
        long bytes = 0;
        long newest = 0;
        try (var s = Files.walk(p, 3)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                count++;
                try {
                    bytes += Files.size(f);
                    newest = Math.max(newest, Files.getLastModifiedTime(f).toMillis());
                } catch (Exception ignore) {
                }
            }
        } catch (Exception e) {
            return "walk-failed";
        }
        return count + "/" + bytes + "/" + newest;
    }

    public static String jsonFor(Path save, Path mods, Path kjs, List<Path> extraJars,
                                 Path assets, Path assetIndex, boolean withRecipes) throws Exception {
        return jsonFor(save, mods, kjs, extraJars, assets, assetIndex, withRecipes, null, null);
    }

    public static String jsonFor(Path save, Path mods, Path kjs, List<Path> extraJars,
                                 Path assets, Path assetIndex, boolean withRecipes,
                                 Path quests, Path xaero) throws Exception {
        List<String> a = new ArrayList<>(List.of("--save", save.toString(), "--json-only"));
        if (mods != null) {
            a.add("--mods");
            a.add(mods.toString());
        }
        if (kjs != null) {
            a.add("--kubejs");
            a.add(kjs.toString());
        }
        if (assets != null) {
            a.add("--assets");
            a.add(assets.toString());
        }
        if (assetIndex != null) {
            a.add("--asset-index");
            a.add(assetIndex.toString());
        }
        if (extraJars != null && !extraJars.isEmpty()) {
            a.add("--version-jar");
            a.add(String.join(";", extraJars.stream().map(Path::toString).toList()));
        }
        if (withRecipes) {
            a.add("--with-recipes");
        }
        if (quests != null) {
            a.add("--quests");
            a.add(quests.toString());
        }
        if (xaero != null) {
            a.add("--xaero");
            a.add(xaero.toString());
        }
        return withCache(a.toArray(new String[0]));
    }

    private static Map<String, String> parseArgs(String[] args) {
        Set<String> booleanFlags = Set.of("json-only", "with-recipes");
        Map<String, String> o = new TreeMap<>();
        for (int i = 0; i < args.length; i++) {
            String cur = args[i];
            if (!cur.startsWith("--")) {
                continue;
            }
            String key = cur.substring(2);
            boolean valueless = booleanFlags.contains(key)
                    || i + 1 >= args.length
                    || args[i + 1].startsWith("--");
            o.put(key, valueless ? "1" : args[++i]);
        }
        return o;
    }

    private static Map<String, Object> buildRoot(String[] args) throws Exception {
        Map<String, String> o = parseArgs(args);
        Path save = Path.of(req(o, "save"));
        Path mods = o.containsKey("mods") ? Path.of(o.get("mods")) : null;
        Path kjs = o.containsKey("kubejs") ? Path.of(o.get("kubejs")) : null;
        Path assets = o.containsKey("assets") ? Path.of(o.get("assets")) : null;
        Path assetIndex = o.containsKey("asset-index") ? Path.of(o.get("asset-index")) : null;
        Path out = o.containsKey("out") ? Path.of(o.get("out")) : null;

        Map<String, Instant> done = readAdvancements(save);
        Path statsFile = firstFile(save.resolve("stats"));
        String statsText = statsFile == null ? "{}" : Files.readString(statsFile, StandardCharsets.UTF_8);
        Map<String, String> statsCats = topLevel(slice(statsText, "stats") == null ? statsText : slice(statsText, "stats"));

        List<Path> extraJars = new ArrayList<>();
        for (String v : o.getOrDefault("version-jar", "").split(";")) {
            if (!v.isBlank()) {
                extraJars.add(Path.of(v.trim()));
            }
        }
        AdvancementCatalog.Catalog cat = AdvancementCatalog.scan(mods, kjs, extraJars);
        Set<String> definition = cat.definition();

        LangIndex lang = LangIndex.build(mods, assets, assetIndex);

        Instant ref = done.values().stream().max(Comparator.naturalOrder()).orElse(Instant.EPOCH);

        Instant firstEv = done.values().stream().min(Comparator.naturalOrder()).orElse(ref);
        Map<Integer, Integer> perDay = new TreeMap<>();
        for (Instant t : done.values()) {
            perDay.merge((int) Duration.between(firstEv, t).toDays(), 1, Integer::sum);
        }
        int lastDay = perDay.keySet().stream().max(Comparator.naturalOrder()).orElse(0);
        List<Map<String, Object>> dayRows = new ArrayList<>();
        int cum = 0;
        int peakDay = 0;
        int peak = -1;
        int idleDays = 0;
        int denom = Math.max(1, definition.size());
        for (int d = 0; d <= lastDay; d++) {
            int gained = perDay.getOrDefault(d, 0);
            cum += gained;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", d);
            row.put("gained", gained);
            row.put("cumulative", cum);
            row.put("completionPct", round1(100.0 * cum / denom));
            dayRows.add(row);
            if (gained > peak) {
                peak = gained;
                peakDay = d;
            }
            if (gained == 0 && d > 0) {
                idleDays++;
            }
        }
        Map<String, Object> gTimeline = new LinkedHashMap<>();
        gTimeline.put("xAxis", "游戏日（第 0 天 = 这个存档里最早的一次成就）");
        gTimeline.put("seriesNames", List.of("当日推进量", "累计完成度"));
        gTimeline.put("dayCount", dayRows.size());
        gTimeline.put("peakDay", peakDay);
        gTimeline.put("peakGained", Math.max(0, peak));
        gTimeline.put("idleDays", idleDays);
        gTimeline.put("totalDone", cum);
        gTimeline.put("totalDefinition", definition.size());
        gTimeline.put("days", dayRows);

        Map<String, Map<Integer, Integer>> perNsDay = new TreeMap<>();
        for (Map.Entry<String, Instant> en : done.entrySet()) {
            int d = (int) Duration.between(firstEv, en.getValue()).toDays();
            perNsDay.computeIfAbsent(ns(en.getKey()), k -> new TreeMap<>()).merge(d, 1, Integer::sum);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, Integer>> en : perNsDay.entrySet()) {
            int total = en.getValue().values().stream().mapToInt(Integer::intValue).sum();
            if (total < GROUP_MIN_TOTAL) {
                continue;
            }
            List<Object> gains = new ArrayList<>();
            List<Object> cumSeries = new ArrayList<>();
            int run = 0;
            for (int d = 0; d <= lastDay; d++) {
                int gain = en.getValue().getOrDefault(d, 0);
                run += gain;
                gains.add(gain);
                cumSeries.add(run);
            }
            Map<String, Object> grp = new LinkedHashMap<>();
            grp.put("modId", en.getKey());
            grp.put("modName", lang.mod(en.getKey()));
            grp.put("total", total);
            grp.put("gains", gains);
            grp.put("cumulative", cumSeries);
            groups.add(grp);
        }
        groups.sort(Comparator.comparing((Map<String, Object> m) -> -(Integer) m.get("total")));
        if (groups.size() > GROUP_LIMIT) {
            groups = new ArrayList<>(groups.subList(0, GROUP_LIMIT));
        }
        gTimeline.put("groups", groups);
        gTimeline.put("groupMinTotal", GROUP_MIN_TOTAL);
        gTimeline.put("groupLimit", GROUP_LIMIT);

        Map<String, Integer> donePerNs = new TreeMap<>();
        for (String id : done.keySet()) {
            donePerNs.merge(ns(id), 1, Integer::sum);
        }
        List<Map<String, Object>> untouched = new ArrayList<>();
        List<Map<String, Object>> shallow = new ArrayList<>();
        List<Map<String, Object>> touched = new ArrayList<>();
        for (Map.Entry<String, Integer> e : new TreeMap<>(cat.perNamespace()).entrySet()) {
            String ns = e.getKey();
            int total = e.getValue();
            int d = donePerNs.getOrDefault(ns, 0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("modId", ns);
            row.put("modName", lang.mod(ns));
            row.put("done", d);
            row.put("total", total);
            if (d == 0) {
                List<String> ex = exampleTitles(lang, ns, 3);
                if (ex.isEmpty()) {
                    row.put("note", "该模组的成就没有显示标题（隐藏/技术性内容）");
                } else {
                    row.put("examples", ex);
                }
                untouched.add(row);
            } else if (d * 100 <= total * SHALLOW_MAX_PERCENT) {
                row.put("share", Math.round(100.0 * d / total));
                shallow.add(row);
            } else {
                touched.add(row);
            }
        }
        untouched.sort(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("total")).reversed()
                .thenComparing(m -> (String) m.get("modName")));
        shallow.sort(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("total")).reversed());

        List<Map.Entry<String, Instant>> seq = new ArrayList<>(done.entrySet());
        seq.sort(Map.Entry.comparingByValue());
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (int i = 1; i < seq.size(); i++) {
            Duration g = Duration.between(seq.get(i - 1).getValue(), seq.get(i).getValue());
            if (g.toHours() < MIN_GAP_HOURS) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("advancement", seq.get(i).getKey());
            row.put("name", advName(lang, seq.get(i).getKey()));
            row.put("modName", lang.mod(ns(seq.get(i).getKey())));
            row.put("after", advName(lang, seq.get(i - 1).getKey()));
            row.put("gapHours", g.toHours());
            row.put("gapDays", round1(g.toMinutes() / 1440.0));
            gaps.add(row);
        }
        gaps.sort(Comparator.comparing((Map<String, Object> m) -> (Long) m.get("gapHours")).reversed());
        List<Map<String, Object>> topGaps = gaps.subList(0, Math.min(TOP_GAPS_LIMIT, gaps.size()));

        List<Map<String, Object>> idleMods = new ArrayList<>();
        for (Map<String, Object> row : shallow) {
            String ns = (String) row.get("modId");
            Instant last = done.entrySet().stream().filter(e -> ns(e.getKey()).equals(ns))
                    .map(Map.Entry::getValue).max(Comparator.naturalOrder()).orElse(null);
            if (last == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>(row);
            m.put("daysSinceLast", round1(Duration.between(last, ref).toMinutes() / 1440.0));
            idleMods.add(m);
        }
        idleMods.sort(Comparator.comparing((Map<String, Object> m) -> (Double) m.get("daysSinceLast")).reversed());

        Map<String, Integer> killed = intMap(statsCats.get("minecraft:killed"));
        Map<String, Integer> killedBy = intMap(statsCats.get("minecraft:killed_by"));
        Map<String, Integer> crafted = intMap(statsCats.get("minecraft:crafted"));
        Map<String, Integer> mined = intMap(statsCats.get("minecraft:mined"));
        Map<String, Integer> used = intMap(statsCats.get("minecraft:used"));
        Map<String, Integer> custom = intMap(statsCats.get("minecraft:custom"));

        List<Map<String, Object>> playerKillers = named(killedBy, lang, true);
        List<Map<String, Object>> topKills = named(killed, lang, true);
        if (topKills.size() > 12) {
            topKills = new ArrayList<>(topKills.subList(0, 12));
        }
        long playTicks = custom.getOrDefault("minecraft:play_time", 0).longValue();
        long sinceDeath = custom.getOrDefault("minecraft:time_since_death", 0).longValue();

        Map<String, Object> combat = new LinkedHashMap<>();
        combat.put("deaths", custom.getOrDefault("minecraft:deaths", 0));
        combat.put("playerKillers", playerKillers);
        combat.put("playerKillersTotal", playerKillers.size());
        int killerSum = 0;
        int killerTop = 0;
        if (!playerKillers.isEmpty()) {
            killerTop = ((Number) playerKillers.get(0).get("count")).intValue();
            for (Map<String, Object> k : playerKillers) {
                killerSum += ((Number) k.get("count")).intValue();
            }
        }
        combat.put("killerSum", killerSum);
        combat.put("topKillerCount", killerTop);
        int[] ci = WilsonInterval.percent95(killerTop, killerSum);
        if (ci != null) {
            combat.put("topKillerShareCiLow", ci[0]);
            combat.put("topKillerShareCiHigh", ci[1]);
            combat.put("topKillerShareCiMethod", "Wilson 95%");
        }
        combat.put("topKills", topKills);
        combat.put("mobTypesKilled", killed.size());
        combat.put("playHours", round1(playTicks / 20.0 / 3600.0));
        combat.put("sinceLastDeathHours", round1(sinceDeath / 20.0 / 3600.0));
        combat.put("damageDealt", custom.getOrDefault("minecraft:damage_dealt", 0));
        combat.put("damageTaken", custom.getOrDefault("minecraft:damage_taken", 0));
        combat.put("craftedKinds", crafted.size());
        combat.put("minedKinds", mined.size());
        combat.put("usedKinds", used.size());
        combat.put("walkKm", round1(custom.getOrDefault("minecraft:walk_one_cm", 0) / 100000.0));
        List<Map<String, Object>> craftTop = named(crafted, lang, true);
        combat.put("craftedTop", craftTop.subList(0, Math.min(8, craftTop.size())));
        List<Map<String, Object>> mineTop = named(mined, lang, true);
        combat.put("minedTop", mineTop.subList(0, Math.min(8, mineTop.size())));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "content-insights@1.0.0");
        root.put("contentLayerVersion", CACHE_VERSION);
        root.put("save", save.getFileName().toString());
        root.put("reference", "以该存档最后一次成就活动为时间基准：" + ref);
        root.put("names", lang.stats());
        root.put("advancementsDone", done.size());
        root.put("advancementsTotal", definition.size());
        root.put("namespacesTotal", cat.namespaceCount());
        root.put("namespacesTouched", donePerNs.size());

        Map<String, Object> a = new LinkedHashMap<>();
        a.put("untouchedCount", untouched.size());
        a.put("untouched", untouched);
        a.put("shallowCount", shallow.size());
        a.put("shallowMaxPercent", SHALLOW_MAX_PERCENT);
        a.put("shallow", shallow);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("minGapHours", MIN_GAP_HOURS);
        b.put("gapsTotal", gaps.size());
        b.put("topGapsLimit", TOP_GAPS_LIMIT);
        b.put("topGaps", topGaps);
        b.put("idleModsLimit", IDLE_MODS_LIMIT);
        b.put("idleModsTotal", idleMods.size());
        b.put("idleMods", idleMods.subList(0, Math.min(IDLE_MODS_LIMIT, idleMods.size())));
        root.put("A_untouched", a);
        root.put("B_stalls", b);
        root.put("C_combat", combat);
        root.put("G_timeline", gTimeline);

        Path quests = o.containsKey("quests") ? Path.of(o.get("quests")) : null;
        if (quests != null) {
            Path prog = o.containsKey("quest-progress") ? Path.of(o.get("quest-progress"))
                    : PackSources.findProgressFile(save);
            root.put("D_quests", PackSources.ftbQuests(quests, prog));
        }
        Path xaero = o.containsKey("xaero") ? Path.of(o.get("xaero")) : null;
        if (xaero != null) {
            root.put("E_waypoints", PackSources.xaeroWaypoints(xaero, o.get("world")));
        }
        if (o.containsKey("with-recipes") && mods != null) {
            root.put("F_recipes", RecipeInsights.analyze(mods, quests));
        }

        return root;
    }

    @SuppressWarnings("unchecked")
    private static String human(Map<String, Object> root) {
        Map<String, Object> a = (Map<String, Object>) root.get("A_untouched");
        Map<String, Object> b = (Map<String, Object>) root.get("B_stalls");
        Map<String, Object> combat = (Map<String, Object>) root.get("C_combat");
        List<Map<String, Object>> untouched = (List<Map<String, Object>>) a.get("untouched");
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) b.get("topGaps");
        List<Map<String, Object>> idle = (List<Map<String, Object>>) b.get("idleMods");
        List<Map<String, Object>> killers = (List<Map<String, Object>>) combat.get("playerKillers");
        List<Map<String, Object>> topKills = (List<Map<String, Object>>) combat.get("topKills");
        StringBuilder sb = new StringBuilder();
        sb.append("=== 内容层洞察（").append(root.get("save")).append("）===\n");
        sb.append("名字层：中文键 ").append(((Map<?, ?>) root.get("names")).get("zhKeys"))
                .append(" / 模组名 ").append(((Map<?, ?>) root.get("names")).get("modsNamed")).append("\n");
        sb.append("内容总量：").append(root.get("advancementsTotal")).append(" 条内容线，分属 ")
                .append(root.get("namespacesTotal")).append(" 个模组；你做完了 ")
                .append(root.get("advancementsDone")).append(" 条\n\n");

        sb.append("【A 你一次都没碰过的模组】共 ").append(root.get("A_untouched") instanceof Map
                ? ((Map<?, ?>) root.get("A_untouched")).get("untouchedCount") : "?").append(" 个\n");
        for (Map<String, Object> m : untouched) {
            sb.append("  · ").append(m.get("modName")).append("（").append(m.get("modId")).append("）—— ")
                    .append(m.get("total")).append(" 条内容一条没做");
            Object ex = m.get("examples");
            if (ex instanceof List<?> l && !l.isEmpty()) {
                sb.append("，例如：").append(String.join("、", l.stream().map(Object::toString).toList()));
            }
            sb.append('\n');
        }
        sb.append("\n【B 卡得最久的推进】\n");
        for (Map<String, Object> m : gaps) {
            sb.append("  · 停了 ").append(m.get("gapDays")).append(" 天（").append(m.get("gapHours")).append(" 小时）才推进《")
                    .append(m.get("name")).append("》—— 它属于 ").append(m.get("modName"))
                    .append("，上一步是《").append(m.get("after")).append("》\n");
        }
        sb.append("\n【B 这些模组你动过但很久没再动】\n");
        for (Map<String, Object> m : idle) {
            sb.append("  · ").append(m.get("modName")).append("：已完成 ").append(m.get("done")).append("/")
                    .append(m.get("total")).append(" 条，最后一次动它是 ").append(m.get("daysSinceLast")).append(" 天前\n");
        }
        sb.append("\n【C 你怎么死的】共 ").append(combat.get("deaths")).append(" 次死亡，凶手：\n");
        for (Map<String, Object> m : killers) {
            sb.append("  · ").append(m.get("name")).append(" 杀了你 ").append(m.get("count")).append(" 次");
            if (m.get("id") != null && !m.get("id").equals(m.get("name"))) {
                sb.append("（").append(m.get("id")).append("）");
            }
            sb.append('\n');
        }
        sb.append("\n【C 你杀得最多的】共击杀 ").append(combat.get("mobTypesKilled")).append(" 种怪物\n");
        for (Map<String, Object> m : topKills) {
            sb.append("  · ").append(m.get("name")).append(" ").append(m.get("count")).append(" 只\n");
        }
        sb.append("\n【C 其它】游玩 ").append(combat.get("playHours")).append(" 小时；距上次死亡 ")
                .append(combat.get("sinceLastDeathHours")).append(" 小时；造成伤害 ").append(combat.get("damageDealt"))
                .append(" / 承受 ").append(combat.get("damageTaken")).append("；走过 ").append(combat.get("walkKm"))
                .append(" 公里；合成过 ").append(combat.get("craftedKinds")).append(" 种物品、挖过 ")
                .append(combat.get("minedKinds")).append(" 种方块\n");
        return sb.toString();
    }

    private static Map<String, Instant> readAdvancements(Path save) throws Exception {
        Path f = firstFile(save.resolve("advancements"));
        Map<String, Instant> out = new TreeMap<>();
        if (f == null) {
            return out;
        }
        String text = Files.readString(f, StandardCharsets.UTF_8);
        int doneEntries = 0;
        int stamped = 0;
        String sample = null;
        for (Map.Entry<String, String> e : topLevel(text).entrySet()) {
            if (!e.getValue().contains("\"done\"") || !e.getValue().contains("true")) {
                continue;
            }
            doneEntries++;
            Matcher m = TS.matcher(e.getValue());
            Instant best = null;
            while (m.find()) {
                Instant t = parseTs(m);
                if (t == null) {
                    continue;
                }
                if (best == null || t.isBefore(best)) {
                    best = t;
                }
            }
            if (best == null) {
                if (sample == null) {
                    sample = e.getKey() + " -> " + e.getValue().replaceAll("\\s+", " ").trim();
                }
                continue;
            }
            stamped++;
            out.put(e.getKey(), best);
        }
        if (doneEntries > 0 && stamped == 0) {
            System.out.println("CRASH: 成就时间戳一条都解析不出来（已完成条目 " + doneEntries + " 条）");
            System.out.println("  样本：" + sample);
            System.out.println("  ⇒ 拒绝输出空的“卡点”块（空表会被误读成“没有卡点”）");
            System.exit(4);
        }
        if (doneEntries != stamped) {
            System.out.println("[content] 注意：已完成 " + doneEntries + " 条里有 " + (doneEntries - stamped)
                    + " 条没有可解析时间戳 ⇒ 它们不参与卡点计算（如实标注，不猜）");
        }
        return out;
    }

    private static Instant parseTs(Matcher m) {
        try {
            String off = m.group(7);
            java.time.ZoneOffset zo = java.time.ZoneOffset.UTC;
            if (off != null) {
                zo = java.time.ZoneOffset.of(off.substring(0, 3) + ":" + off.substring(3));
            }
            return java.time.LocalDateTime.of(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)))
                    .toInstant(zo);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> exampleTitles(LangIndex lang, String ns, int n) {
        List<String> out = new ArrayList<>();
        Path jar = lang.jarOf(ns);
        if (jar == null) {
            return out;
        }
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            String prefix = "data/" + ns + "/advancements/";
            List<String> names = new ArrayList<>();
            for (var e = zf.entries(); e.hasMoreElements(); ) {
                String nm = e.nextElement().getName();
                if (nm.startsWith(prefix) && nm.endsWith(".json")) {
                    names.add(nm);
                }
            }
            names.sort(Comparator.naturalOrder());
            int scanned = 0;
            for (String nm : names) {
                if (out.size() >= n || scanned >= 80) {
                    break;
                }
                scanned++;
                ZipEntry ze = zf.getEntry(nm);
                try (InputStream in = zf.getInputStream(ze)) {
                    String t = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher tm = TITLE_TRANSLATE.matcher(t);
                    if (tm.find()) {
                        String v = lang.byKey(tm.group(1));
                        if (v != null) {
                            out.add(v);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static final Map<String, String> TITLE_CACHE = new LinkedHashMap<>();

    private static String advName(LangIndex lang, String id) {
        String cached = TITLE_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        String v = lang.advancement(id);
        if (v.equals(id)) {
            String ns = ns(id);
            Path jar = lang.jarOf(ns);
            if (jar != null) {
                String entry = "data/" + ns + "/advancements/" + id.substring(id.indexOf(':') + 1) + ".json";
                try (ZipFile zf = new ZipFile(jar.toFile())) {
                    ZipEntry ze = zf.getEntry(entry);
                    if (ze != null) {
                        try (InputStream in = zf.getInputStream(ze)) {
                            String t = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                            Matcher tm = TITLE_TRANSLATE.matcher(t);
                            if (tm.find()) {
                                String byKey = lang.byKey(tm.group(1));
                                if (byKey != null) {
                                    v = byKey;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        v = clean(v);
        TITLE_CACHE.put(id, v);
        return v;
    }

    private static String clean(String s) {
        return s == null ? null : s.replaceAll("§.", "").trim();
    }

    private static List<Map<String, Object>> named(Map<String, Integer> src, LangIndex lang, boolean entity) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : src.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("name", clean(entity ? lang.entity(e.getKey()) : lang.itemOrBlock(e.getKey())));
            m.put("count", e.getValue());
            list.add(m);
        }
        list.sort(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("count")).reversed()
                .thenComparing(m -> (String) m.get("id")));
        return list;
    }

    private static Map<String, Integer> intMap(String inner) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (inner == null) {
            return m;
        }
        Matcher mm = KV_INT.matcher(inner);
        while (mm.find()) {
            try {
                m.put(mm.group(1), Integer.parseInt(mm.group(2)));
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    private static String slice(String text, String key) {
        if (text == null) {
            return null;
        }
        int i = text.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        int b = text.indexOf('{', i);
        if (b < 0) {
            return null;
        }
        int end = matchBrace(text, b);
        return end < 0 ? null : text.substring(b + 1, end);
    }

    private static Map<String, String> topLevel(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            int q = text.indexOf('"', i);
            if (q < 0) {
                break;
            }
            StringBuilder sb = new StringBuilder();
            int k = q + 1;
            while (k < n) {
                char c = text.charAt(k);
                if (c == '\\' && k + 1 < n) {
                    sb.append(c).append(text.charAt(k + 1));
                    k += 2;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                sb.append(c);
                k++;
            }
            int after = k + 1;
            while (after < n && Character.isWhitespace(text.charAt(after))) {
                after++;
            }
            if (after < n && text.charAt(after) == ':') {
                int b = after + 1;
                while (b < n && Character.isWhitespace(text.charAt(b))) {
                    b++;
                }
                if (b < n && text.charAt(b) == '{') {
                    int end = matchBrace(text, b);
                    if (end < 0) {
                        break;
                    }
                    m.put(sb.toString(), text.substring(b + 1, end));
                    i = end + 1;
                    continue;
                }
            }
            i = k + 1;
        }
        return m;
    }

    private static int matchBrace(String text, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int k = open; k < text.length(); k++) {
            char c = text.charAt(k);
            if (inStr) {
                if (c == '\\') {
                    k++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return k;
                }
            }
        }
        return -1;
    }

    private static Path firstFile(Path dir) throws Exception {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (var s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).sorted().findFirst().orElse(null);
        }
    }

    private static String ns(String id) {
        int i = id.indexOf(':');
        return i < 0 ? "minecraft" : id.substring(0, i);
    }

    private static Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String req(Map<String, String> o, String k) {
        String v = o.get(k);
        if (v == null) {
            System.out.println("ARGS-MISSING: --" + k);
            System.exit(2);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, l.get(i));
            }
            sb.append(']');
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else {
            str(sb, String.valueOf(o));
        }
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
