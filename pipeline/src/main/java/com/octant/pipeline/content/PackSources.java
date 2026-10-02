package com.octant.pipeline.content;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

public final class PackSources {

    private static final Pattern SCALAR = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(.+?)\\s*$");
    private static final Pattern QUOTED = Pattern.compile("^\"((?:[^\"\\\\]|\\\\.)*)\"$");
    private static final Pattern PROGRESS = Pattern.compile("^\\s*([0-9A-Fa-f]{8,})\\s*:\\s*(\\d+)L?\\s*$");

    private PackSources() {
    }

    public static void main(String[] args) throws Exception {
        Path questsRoot = null;
        Path progress = null;
        Path xaeroRoot = null;
        String worldName = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--quests" -> questsRoot = Path.of(args[++i]);
                case "--progress" -> progress = Path.of(args[++i]);
                case "--xaero" -> xaeroRoot = Path.of(args[++i]);
                case "--world" -> worldName = args[++i];
                default -> { }
            }
        }
        if (questsRoot != null) {
            System.out.println(text(ftbQuests(questsRoot, progress)));
        }
        if (xaeroRoot != null) {
            System.out.println(text(xaeroWaypoints(xaeroRoot, worldName)));
        }
    }

    public static Path findProgressFile(Path save) {
        if (save == null) {
            return null;
        }
        Path dir = save.resolve("ftbquests");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path best = null;
        long bestLen = -1;
        try (var s = Files.list(dir)) {
            for (Path f : s.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".snbt")).toList()) {
                long len = Files.size(f);
                if (len > bestLen) {
                    bestLen = len;
                    best = f;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return best;
    }

    private static int countProgressCandidates(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (var s = Files.list(dir)) {
            return (int) s.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".snbt")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    public static Map<String, Object> ftbQuests(Path questsRoot, Path progressFile) throws IOException {
        Set<String> doneTasks = new LinkedHashSet<>();
        long progressBytes = 0;
        if (progressFile != null && Files.isRegularFile(progressFile)) {
            progressBytes = Files.size(progressFile);
            String p = Files.readString(progressFile, StandardCharsets.UTF_8);
            String body = objectBody(p, "task_progress", 1);
            if (body != null) {
                for (String line : body.split("\n")) {
                    Matcher m = PROGRESS.matcher(line);
                    if (m.find()) {
                        doneTasks.add(m.group(1).toUpperCase());
                    }
                }
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "pack-quests@1.0.0");
        root.put("progressFileBytes", progressBytes);
        root.put("completedTaskIds", doneTasks.size());
        root.put("progressRead", progressBytes > 0);
        root.put("progressFileDir", "ftbquests/*.snbt");
        root.put("progressFileCandidates", countProgressCandidates(progressFile == null
                ? null : progressFile.getParent()));

        List<Map<String, Object>> chapters = new ArrayList<>();
        int totalTasks = 0;
        int doneTotal = 0;
        int totalQuests = 0;
        int doneQuests = 0;
        String chaptersDirUsed = null;
        if (questsRoot != null && Files.isDirectory(questsRoot)) {
            Path dir = questChapterDir(questsRoot);
            List<Path> files = listSnbt(dir);
            chaptersDirUsed = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();
            for (Path f : files) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Map<String, String> chScalars = topLevelScalars(text, 1);
                String chId = f.getFileName().toString().replace(".snbt", "");
                String chTitle = plain(chScalars.getOrDefault("title", chId));
                List<Map<String, Object>> quests = new ArrayList<>();
                int cTotal = 0;
                int cDone = 0;
                String body = arrayBody(text, "quests", 1);
                if (body != null) {
                    for (String qb : topLevelBodies(body)) {
                        Map<String, String> q = topLevelScalars(qb, 0);
                        String qid = q.getOrDefault("id", "?");
                        String qtitle = q.get("title");
                        List<Map<String, Object>> tasks = new ArrayList<>();
                        int qTotal = 0;
                        int qDone = 0;
                        String tb = arrayBody(qb, "tasks", 0);
                        if (tb != null) {
                            for (String taskObj : topLevelBodies(tb)) {
                                Map<String, String> t = topLevelScalars(taskObj, 0);
                                String tid = t.getOrDefault("id", "?");
                                boolean d = doneTasks.contains(tid.toUpperCase());
                                Map<String, Object> tm = new LinkedHashMap<>();
                                tm.put("id", tid);
                                tm.put("title", plain(t.getOrDefault("title", tid)));
                                tm.put("type", t.getOrDefault("type", "?"));
                                tm.put("done", d);
                                tasks.add(tm);
                                qTotal++;
                                if (d) {
                                    qDone++;
                                }
                            }
                        }
                        if (qTotal == 0) {
                            continue;
                        }
                        Map<String, Object> qm = new LinkedHashMap<>();
                        qm.put("id", qid);
                        qm.put("title", plain(qtitle == null ? qid : qtitle));
                        qm.put("subtitle", q.get("subtitle"));
                        qm.put("tasksTotal", qTotal);
                        qm.put("tasksDone", qDone);
                        qm.put("done", qDone == qTotal);
                        qm.put("tasks", tasks);
                        quests.add(qm);
                        cTotal += qTotal;
                        cDone += qDone;
                    }
                }
                if (cTotal == 0) {
                    continue;
                }
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id", chId);
                cm.put("title", chTitle);
                cm.put("questsTotal", quests.size());
                cm.put("tasksTotal", cTotal);
                cm.put("tasksDone", cDone);
                cm.put("quests", quests);
                chapters.add(cm);
                totalTasks += cTotal;
                doneTotal += cDone;
                totalQuests += quests.size();
                for (Map<String, Object> qm : quests) {
                    if (Boolean.TRUE.equals(qm.get("done"))) {
                        doneQuests++;
                    }
                }
            }
        }
        chapters.sort(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("tasksTotal")).reversed());
        root.put("chapters", chapters);
        root.put("chaptersTotal", chapters.size());
        if (chaptersDirUsed != null) {
            root.put("chaptersDir", chaptersDirUsed);
        }
        root.put("questsTotal", totalQuests);
        root.put("questsDone", doneQuests);
        root.put("tasksTotal", totalTasks);
        root.put("tasksDone", doneTotal);
        return root;
    }

    static Path questChapterDir(Path root) {
        if (root == null) {
            return null;
        }
        Path sub = root.resolve("chapters");
        if (!listSnbt(sub).isEmpty()) {
            return sub;
        }
        return root;
    }

    static List<Path> listSnbt(Path dir) {
        List<Path> files = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return files;
        }
        try (var s = Files.list(dir)) {
            s.filter(f -> f.getFileName().toString().endsWith(".snbt")).sorted().forEach(files::add);
        } catch (IOException ignore) {
            return new ArrayList<>();
        }
        return files;
    }

    @SuppressWarnings("unchecked")
    public static String text(Map<String, Object> root) {
        StringBuilder sb = new StringBuilder();
        if (root.containsKey("chapters")) {
            sb.append("=== FTB 任务书（作者写的） ===\n");
            sb.append("章节 ").append(root.get("chaptersTotal")).append(" 个 · 任务 ")
                    .append(root.get("questsTotal")).append(" 条（含子任务 ").append(root.get("tasksTotal")).append(" 项）\n");
            sb.append("已完成：任务 ").append(root.get("questsDone")).append(" 条 · 子任务 ")
                    .append(root.get("tasksDone")).append(" 项\n\n");
            List<Map<String, Object>> chs = (List<Map<String, Object>>) root.get("chapters");
            sb.append("【进度最高的章节】\n");
            List<Map<String, Object>> byDone = new ArrayList<>(chs);
            byDone.sort(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("tasksDone")).reversed());
            for (Map<String, Object> c : byDone.subList(0, Math.min(8, byDone.size()))) {
                sb.append("  · ").append(c.get("title")).append("：").append(c.get("tasksDone")).append("/")
                        .append(c.get("tasksTotal")).append(" 项\n");
            }
            sb.append("\n【做了一半的章节（卡点最可能在这）】\n");
            int shown = 0;
            for (Map<String, Object> c : chs) {
                int d = (Integer) c.get("tasksDone");
                int t = (Integer) c.get("tasksTotal");
                if (d > 0 && d < t && shown < 8) {
                    shown++;
                    sb.append("  · ").append(c.get("title")).append("：").append(d).append("/").append(t).append(" 项；未完成的任务例如：");
                    List<String> und = new ArrayList<>();
                    for (Map<String, Object> q : (List<Map<String, Object>>) c.get("quests")) {
                        if (!Boolean.TRUE.equals(q.get("done")) && und.size() < 3) {
                            und.add("《" + q.get("title") + "》");
                        }
                    }
                    sb.append(String.join("、", und)).append('\n');
                }
            }
        }
        if (root.containsKey("dimensions")) {
            sb.append("\n=== 你在世界地图上自己标的点 ===\n");
            sb.append("共 ").append(root.get("waypointCount")).append(" 个标点，分布在 ")
                    .append(root.get("dimensionCount")).append(" 个维度：\n");
            for (Map<String, Object> d : (List<Map<String, Object>>) root.get("dimensions")) {
                sb.append("  · ").append(d.get("dimension")).append("（").append(d.get("count")).append(" 个）：");
                List<String> names = new ArrayList<>();
                for (Map<String, Object> w : (List<Map<String, Object>>) d.get("waypoints")) {
                    names.add(String.valueOf(w.get("name")));
                }
                sb.append(String.join("、", names)).append('\n');
            }
        }
        return sb.toString();
    }

    public static Map<String, Object> xaeroWaypoints(Path xaeroRoot, String worldName) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "pack-waypoints@1.0.0");
        List<Map<String, Object>> dims = new ArrayList<>();
        int total = 0;
        Path minimap = xaeroRoot.resolve("minimap");
        if (Files.isDirectory(minimap)) {
            List<Path> worldDirs = new ArrayList<>();
            try (var s = Files.list(minimap)) {
                s.filter(Files::isDirectory).forEach(worldDirs::add);
            }
            for (Path w : worldDirs) {
                if (worldName != null && !w.getFileName().toString().startsWith(worldName)) {
                    continue;
                }
                List<Path> dimDirs = new ArrayList<>();
                try (var s = Files.list(w)) {
                    s.filter(Files::isDirectory).forEach(dimDirs::add);
                }
                for (Path d : dimDirs) {
                    Path wp = d.resolve("waypoints.txt");
                    if (!Files.isRegularFile(wp)) {
                        continue;
                    }
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (String line : Files.readAllLines(wp, StandardCharsets.UTF_8)) {
                        String t = line.trim();
                        if (!t.startsWith("waypoint:")) {
                            continue;
                        }
                        String[] f = t.split(":", -1);
                        if (f.length < 14) {
                            continue;
                        }
                        StringBuilder name = new StringBuilder(f[1]);
                        for (int i = 2; i < f.length - 12; i++) {
                            name.append(':').append(f[i]);
                        }
                        Map<String, Object> wm = new LinkedHashMap<>();
                        wm.put("name", xaeroName(plain(name.toString())));
                        wm.put("x", f[f.length - 11]);
                        wm.put("y", f[f.length - 10]);
                        wm.put("z", f[f.length - 9]);
                        wm.put("disabled", f[f.length - 7]);
                        list.add(wm);
                    }
                    if (list.isEmpty()) {
                        continue;
                    }
                    String dim = d.getFileName().toString().replace("dim%", "");
                    Map<String, Object> dm = new LinkedHashMap<>();
                    dm.put("dimension", dimName(dim));
                    dm.put("dimensionId", dim);
                    dm.put("count", list.size());
                    dm.put("waypoints", list);
                    dims.add(dm);
                    total += list.size();
                }
            }
        }
        dims.sort(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("count")).reversed());
        root.put("dimensions", dims);
        root.put("dimensionCount", dims.size());
        root.put("waypointCount", total);
        return root;
    }

    static String xaeroName(String s) {
        if (s == null) {
            return null;
        }
        if (s.startsWith("gui.xaero_deathpoint")) {
            return "死亡点";
        }
        if (s.startsWith("gui.xaero_")) {
            return "（地图内置标点）";
        }
        return s;
    }

    private static String dimName(String raw) {
        if ("0".equals(raw)) {
            return "主世界";
        }
        if ("-1".equals(raw)) {
            return "下界";
        }
        if ("1".equals(raw)) {
            return "末地";
        }
        int i = raw.indexOf('$');
        String ns = i < 0 ? raw : raw.substring(0, i);
        String path = i < 0 ? "" : raw.substring(i + 1);
        Map<String, String> known = Map.of(
                "twilightforest", "暮色森林",
                "graemod", "诡厄（自定义维度）",
                "beyond_deepest_dark", "极深之暗",
                "alexscaves", "Alex 的洞穴",
                "cataclysm", "灾变",
                "undergarden", "深渊乐园");
        String name = known.get(ns);
        return (name == null ? ns : name) + (path.isEmpty() ? "" : "（" + path + "）");
    }

    static String objectBody(String text, String key, int wantDepth) {
        int at = findKeyAtDepth(text, key, wantDepth);
        if (at < 0) {
            return null;
        }
        int b = text.indexOf('{', at);
        if (b < 0) {
            return null;
        }
        int e = match(text, b, '{', '}');
        return e < 0 ? null : text.substring(b + 1, e);
    }

    static String arrayBody(String text, String key, int wantDepth) {
        int at = findKeyAtDepth(text, key, wantDepth);
        if (at < 0) {
            return null;
        }
        int b = text.indexOf('[', at);
        if (b < 0) {
            return null;
        }
        int e = match(text, b, '[', ']');
        return e < 0 ? null : text.substring(b + 1, e);
    }

    static Map<String, String> topLevelScalars(String body, int wantDepth) {
        Map<String, String> out = new LinkedHashMap<>();
        int depth = 0;
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (depth == wantDepth) {
                Matcher m = SCALAR.matcher(line);
                if (m.find()) {
                    String v = m.group(2).trim();
                    if (!v.isEmpty() && v.charAt(0) != '{' && v.charAt(0) != '[') {
                        Matcher q = QUOTED.matcher(v);
                        if (q.matches()) {
                            out.put(m.group(1), plain(unescape(q.group(1))));
                        } else if (!"null".equals(v)) {
                            out.put(m.group(1), v);
                        }
                    }
                }
            }
            depth += depthDelta(trimmed);
            if (depth < 0) {
                depth = 0;
            }
        }
        return out;
    }

    static List<String> topLevelBodies(String arrayInner) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = arrayInner.length();
        while (i < n) {
            char c = arrayInner.charAt(i);
            if (c == '{') {
                int e = match(arrayInner, i, '{', '}');
                if (e < 0) {
                    break;
                }
                out.add(arrayInner.substring(i + 1, e));
                i = e + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    private static int depthDelta(String line) {
        int d = 0;
        boolean inStr = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                d++;
            } else if (c == '}' || c == ']') {
                d--;
            }
        }
        return d;
    }

    private static int findKeyAtDepth(String text, String key, int wantDepth) {
        int depth = 0;
        boolean inStr = false;
        int lineStart = 0;
        String pat = key + ":";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (c == '\n') {
                lineStart = i + 1;
            }
            if (depth == wantDepth && !inStr && i >= lineStart && text.startsWith(pat, i)
                    && text.substring(lineStart, i).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static int match(String s, int open, char oc, char cc) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == oc) {
                depth++;
            } else if (c == cc) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    static String plain(String s) {
        if (s == null) {
            return null;
        }
        String out = s.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");
        return out.replace("&&", "&").trim();
    }
    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
    }
}
