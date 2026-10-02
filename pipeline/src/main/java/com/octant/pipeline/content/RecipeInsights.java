package com.octant.pipeline.content;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class RecipeInsights {

    private static final Pattern ID = Pattern.compile("[a-z0-9_\\-]+:[a-z0-9_\\-/.]{1,80}");
    private static final Pattern RESULT = Pattern.compile(
            "\"(?:result|output|results|outputs)\"\\s*:\\s*(?:\"([a-z0-9_\\-]+:[a-z0-9_\\-/.]{1,80})\"|\\{[^}]{0,200}?\"(?:item|id|name)\"\\s*:\\s*\"([a-z0-9_\\-]+:[a-z0-9_\\-/.]{1,80})\")");
    private static final Pattern ING_KEY = Pattern.compile(
            "\"(?:item|id|tag|ingredient|base|addition|template)\"\\s*:\\s*\"([a-z0-9_\\-#]+:[a-z0-9_\\-/.]{1,80})\"");
    private static final Pattern SB_TITLE = Pattern.compile("(?m)^\\s*title:\\s*\"([^\"]+)\"");
    private static final Pattern SB_ITEM = Pattern.compile("\"([a-z0-9_]+:[a-z0-9_\\-/.]{2,80})\"");

    private RecipeInsights() {
    }

    private record Recipe(String file, String ns, String result, List<String> ins) {
    }

    public static Map<String, Object> analyze(Path modsDir, Path questsDir) throws IOException {
        List<Recipe> recipes = new ArrayList<>();
        int unparsed = 0;
        if (modsDir != null && Files.isDirectory(modsDir)) {
            List<Path> jars = new ArrayList<>();
            try (var s = Files.list(modsDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
            }
            for (Path jar : jars) {
                try (ZipFile zf = new ZipFile(jar.toFile())) {
                    for (var e = zf.entries(); e.hasMoreElements(); ) {
                        ZipEntry ze = e.nextElement();
                        String n = ze.getName();
                        if (!(n.startsWith("data/") && n.contains("/recipes/") && n.endsWith(".json"))) {
                            continue;
                        }
                        String text;
                        try (InputStream in = zf.getInputStream(ze)) {
                            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        }
                        String res = firstGroup(RESULT.matcher(text));
                        List<String> ins = new ArrayList<>();
                        Matcher im = ING_KEY.matcher(text);
                        while (im.find()) {
                            ins.add(im.group(1));
                        }
                        if (res == null && ins.isEmpty()) {
                            unparsed++;
                            continue;
                        }
                        recipes.add(new Recipe(n, n.split("/")[1], res, ins));
                    }
                } catch (Exception ignored) {
                    unparsed++;
                }
            }
        }

        Map<String, List<Integer>> prod = new LinkedHashMap<>();
        for (int i = 0; i < recipes.size(); i++) {
            Recipe r = recipes.get(i);
            if (r.result() != null) {
                prod.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(i);
            }
        }

        Map<String, Integer> kinds = new LinkedHashMap<>();
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> e : prod.entrySet()) {
            Recipe r = recipes.get(e.getValue().get(0));
            List<String> ins = r.ins().stream().filter(x -> !x.startsWith("#")).toList();
            kinds.put(e.getKey(), new LinkedHashSet<>(ins).size());
            count.put(e.getKey(), ins.size());
        }

        Map<String, Integer> depth = new LinkedHashMap<>();
        Map<String, Set<String>> modsOf = new LinkedHashMap<>();
        List<String> all = new ArrayList<>(prod.keySet());
        for (String item : all) {
            depthOf(item, prod, recipes, depth, modsOf, new LinkedHashSet<>(), 0);
        }

        List<Map<String, Object>> dups = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : prod.entrySet()) {
            if (e.getValue().size() > 1 && !e.getKey().contains("jei_dummy")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("item", e.getKey());
                m.put("recipes", e.getValue().size());
                dups.add(m);
            }
        }
        dups.sort(Comparator.comparing((Map<String, Object> m) -> -(Integer) m.get("recipes")));

        List<Map<String, Object>> top = new ArrayList<>();
        for (String item : prod.keySet()) {
            int d = depth.getOrDefault(item, 0);
            int k = kinds.getOrDefault(item, 0);
            int md = modsOf.getOrDefault(item, Set.of()).size();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", item);
            m.put("depth", d);
            m.put("kinds", k);
            m.put("count", count.getOrDefault(item, 0));
            m.put("mods", md);
            m.put("score", score(d, k, md));
            top.add(m);
        }
        top.sort(Comparator.comparing((Map<String, Object> m) -> -(Integer) m.get("score")));

        Map<String, List<String>> questItems = new LinkedHashMap<>();
        List<Map<String, Object>> jumps = new ArrayList<>();
        List<Map<String, Object>> spans = new ArrayList<>();
        if (questsDir != null && Files.isDirectory(questsDir)) {
            List<Path> files = PackSources.listSnbt(PackSources.questChapterDir(questsDir));
            for (Path f : files) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Matcher tm = SB_TITLE.matcher(text);
                String chapter = tm.find() ? tm.group(1) : f.getFileName().toString().replace(".snbt", "");
                List<int[]> seq = new ArrayList<>();
                List<String> seqItem = new ArrayList<>();
                for (String block : questBlocks(text)) {
                    int bestScore = -1;
                    String hot = null;
                    Set<String> ids = new LinkedHashSet<>();
                    Matcher im = SB_ITEM.matcher(block);
                    while (im.find()) {
                        ids.add(im.group(1));
                    }
                    for (String id : ids) {
                        questItems.computeIfAbsent(id, k -> new ArrayList<>()).add(chapter);
                        if (prod.containsKey(id)) {
                            int sc = score(depth.getOrDefault(id, 0), kinds.getOrDefault(id, 0),
                                    modsOf.getOrDefault(id, Set.of()).size());
                            if (sc > bestScore) {
                                bestScore = sc;
                                hot = id;
                            }
                        }
                    }
                    if (hot != null) {
                        seq.add(new int[]{bestScore});
                        seqItem.add(hot);
                    }
                }
                if (seq.size() >= MIN_CHAPTER_NODES) {
                    int lo = seq.get(0)[0], hi = 0;
                    for (int[] x : seq) {
                        hi = Math.max(hi, x[0]);
                    }
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("chapter", chapter);
                    sm.put("quests", seq.size());
                    sm.put("min", lo);
                    sm.put("max", hi);
                    spans.add(sm);
                    for (int i = 1; i < seq.size(); i++) {
                        int dv = seq.get(i)[0] - seq.get(i - 1)[0];
                        if (dv >= MIN_JUMP_DELTA) {
                            Map<String, Object> jm = new LinkedHashMap<>();
                            jm.put("delta", dv);
                            jm.put("chapter", chapter);
                            jm.put("from", seqItem.get(i - 1));
                            jm.put("to", seqItem.get(i));
                            jm.put("score", seq.get(i)[0]);
                            jumps.add(jm);
                        }
                    }
                }
            }
        }
        jumps.sort(Comparator.comparing((Map<String, Object> m) -> -(Integer) m.get("delta")));
        spans.sort(Comparator.comparing((Map<String, Object> m) -> -((Integer) m.get("max") - (Integer) m.get("min"))));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "recipe-complexity@1.0.0");
        root.put("recipes", recipes.size());
        root.put("unparsed", unparsed);
        root.put("itemsProduced", prod.size());
        root.put("duplicateOutputItems", dups.size());
        root.put("composite", "score = depth + kinds + " + MOD_WEIGHT + "*(mods-1)（本文件自定义口径，非行业标准）");
        root.put("lowerBound", true);
        root.put("topComplex", top.subList(0, Math.min(60, top.size())));
        root.put("duplicates", dups.subList(0, Math.min(40, dups.size())));
        root.put("jumps", jumps.subList(0, Math.min(50, jumps.size())));
        root.put("chapterSpans", spans.subList(0, Math.min(40, spans.size())));
        root.put("topComplexTotal", top.size());
        root.put("duplicatesTotal", dups.size());
        root.put("jumpsTotal", jumps.size());
        root.put("chapterSpansTotal", spans.size());
        root.put("minJumpDelta", MIN_JUMP_DELTA);
        root.put("minChapterNodes", MIN_CHAPTER_NODES);
        root.put("questReferencedItems", questItems.size());
        return root;
    }

    private static final int MOD_WEIGHT = 2;

    private static final int MIN_JUMP_DELTA = 4;

    private static final int MIN_CHAPTER_NODES = 3;

    private static int score(int depth, int kinds, int mods) {
        return depth + kinds + MOD_WEIGHT * Math.max(0, mods - 1);
    }

    private static int depthOf(String item, Map<String, List<Integer>> prod, List<Recipe> recipes,
                               Map<String, Integer> depth, Map<String, Set<String>> modsOf,
                               Set<String> stack, int guard) {
        if (depth.containsKey(item)) {
            return depth.get(item);
        }
        if (stack.contains(item) || guard > 64 || !prod.containsKey(item)) {
            return 0;
        }
        Set<String> nextStack = new LinkedHashSet<>(stack);
        nextStack.add(item);
        int best = 0;
        Set<String> mods = new LinkedHashSet<>();
        mods.add(nsOf(item));
        List<Integer> idxs = prod.get(item);
        for (int t = 0; t < Math.min(4, idxs.size()); t++) {
            for (String in : recipes.get(idxs.get(t)).ins()) {
                if (in.startsWith("#")) {
                    continue;
                }
                mods.add(nsOf(in));
                best = Math.max(best, depthOf(in, prod, recipes, depth, modsOf, nextStack, guard + 1));
                mods.addAll(modsOf.getOrDefault(in, Set.of()));
            }
        }
        depth.put(item, best + 1);
        modsOf.put(item, mods);
        return best + 1;
    }

    private static String nsOf(String id) {
        int i = id.indexOf(':');
        return i < 0 ? "minecraft" : id.substring(0, i);
    }

    private static String firstGroup(Matcher m) {
        if (!m.find()) {
            return null;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static List<String> questBlocks(String text) {
        List<String> out = new ArrayList<>();
        int i = text.indexOf("quests:");
        if (i < 0) {
            return out;
        }
        int b = text.indexOf('[', i);
        if (b < 0) {
            return out;
        }
        int depth = 0;
        boolean inStr = false;
        int start = -1;
        for (int k = b; k < text.length(); k++) {
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
                if (depth == 0) {
                    start = k;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(text.substring(start, k + 1));
                    start = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    public static String toJson(Object o) {
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
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> o = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            o.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        Path mods = o.containsKey("mods") ? Path.of(o.get("mods")) : null;
        Path q = o.containsKey("quests") ? Path.of(o.get("quests")) : null;
        Map<String, Object> r = analyze(mods, q);
        System.out.println("配方 " + r.get("recipes") + " 条（未解析 " + r.get("unparsed") + "）· 有产出 "
                + r.get("itemsProduced") + " 件 · 多配方产出 " + r.get("duplicateOutputItems") + " 件");
        System.out.println("口径：" + r.get("composite"));
        if (o.containsKey("out")) {
            Files.writeString(Path.of(o.get("out")), toJson(r), StandardCharsets.UTF_8);
            System.out.println("写出：" + o.get("out"));
        }
    }
}
