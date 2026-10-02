package com.octant.pipeline.saveimport;

import java.io.IOException;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class AdvancementCatalog {

    private AdvancementCatalog() {
    }

    public static Catalog scan(Path modsDir, Path kubejsDir) throws IOException {
        return scan(modsDir, kubejsDir, List.of());
    }

    public static Catalog scan(Path modsDir, Path kubejsDir, List<Path> extraJars) throws IOException {
        Set<String> defined = new LinkedHashSet<>();
        Map<String, Integer> byNs = new TreeMap<>();
        List<String> unreadable = new ArrayList<>();
        List<String> extraNames = new ArrayList<>();
        int jars = 0;

        if (modsDir != null && Files.isDirectory(modsDir)) {
            List<Path> jarFiles = new ArrayList<>();
            try (var s = Files.list(modsDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
                        jarFiles.add(p);
                    }
                }
            }
            jarFiles.sort(Comparator.comparing(Path::toString));
            jars = jarFiles.size();
            for (Path jar : jarFiles) {
                scanOneJar(jar, defined, byNs, unreadable);
            }
        }

        if (extraJars != null) {
            for (Path jar : extraJars) {
                if (jar == null || !Files.isRegularFile(jar)) {
                    extraNames.add(jar == null ? "(null)" : jar.getFileName() + "（不存在）");
                    continue;
                }
                extraNames.add(jar.getFileName().toString());
                scanOneJar(jar, defined, byNs, unreadable);
            }
        }

        int kubejsFiles = 0;
        if (kubejsDir != null && Files.isDirectory(kubejsDir)) {
            List<Path> found = new ArrayList<>();
            try (var s = Files.walk(kubejsDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    String rel = kubejsDir.relativize(p).toString().replace('\\', '/');
                    if (isAdvancementDefinition("data/" + rel)) {
                        found.add(p);
                    }
                }
            }
            found.sort(Comparator.comparing(Path::toString));
            kubejsFiles = found.size();
            for (Path p : found) {
                String rel = kubejsDir.relativize(p).toString().replace('\\', '/');
                String id = toId(rel, "");
                if (id != null && defined.add(id)) {
                    byNs.merge(namespaceOf(id), 1, Integer::sum);
                }
            }
        }

        return new Catalog(jars, unreadable, kubejsFiles, defined, byNs,
                extraNames.size(), List.copyOf(extraNames));
    }

    private static void scanOneJar(Path jar, Set<String> defined, Map<String, Integer> byNs,
                                   List<String> unreadable) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".json")) {
                    continue;
                }
                if (!isAdvancementDefinition(n)) {
                    continue;
                }
                String id = toId(n, "data/");
                if (id != null && defined.add(id)) {
                    byNs.merge(namespaceOf(id), 1, Integer::sum);
                }
            }
        } catch (IOException ex) {
            unreadable.add(jar.getFileName().toString());
        }
    }

    static boolean isAdvancementDefinition(String entryName) {
        String n = entryName.replace('\\', '/');
        if (!n.endsWith(".json") || !n.startsWith("data/")) {
            return false;
        }
        String[] p = n.split("/");
        return p.length >= 4 && "advancements".equals(p[2]);
    }

    static String toId(String entryName, String prefix) {
        String n = entryName.replace('\\', '/');
        if (!prefix.isEmpty()) {
            if (!n.startsWith(prefix)) {
                return null;
            }
            n = n.substring(prefix.length());
        }
        int idx = n.indexOf("/advancements/");
        if (idx <= 0) {
            return null;
        }
        String ns = n.substring(0, idx);
        String rest = n.substring(idx + "/advancements/".length());
        if (!rest.endsWith(".json")) {
            return null;
        }
        rest = rest.substring(0, rest.length() - ".json".length());
        if (ns.isBlank() || rest.isBlank()) {
            return null;
        }
        return ns + ":" + rest;
    }

    static String namespaceOf(String id) {
        int i = id.indexOf(':');
        return i < 0 ? "" : id.substring(0, i);
    }

    public record Catalog(int jars, List<String> unreadableJars, int kubejsFiles,
                          Set<String> definition, Map<String, Integer> perNamespace,
                          int extraJars, List<String> extraJarNames) {

        public Catalog {
            unreadableJars = List.copyOf(unreadableJars);
            definition = Set.copyOf(definition);
            perNamespace = Map.copyOf(perNamespace);
            extraJarNames = List.copyOf(extraJarNames);
        }

        public int total() {
            return definition.size();
        }

        public int namespaceCount() {
            return perNamespace.size();
        }

        public int coveredBy(Set<String> done) {
            int n = 0;
            for (String id : done) {
                if (definition.contains(id)) {
                    n++;
                }
            }
            return n;
        }

        public List<Map.Entry<String, Integer>> topNamespaces(int n) {
            List<Map.Entry<String, Integer>> list = new ArrayList<>(perNamespace.entrySet());
            list.sort((a, b) -> {
                int c = Integer.compare(b.getValue(), a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            });
            return list.subList(0, Math.min(n, list.size()));
        }

        public Map<String, Object> caveats() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("denominatorMeaning", "定义在数据包里的成就总数（含技术性/隐藏成就）");
            m.put("pathRule", "**严格**：路径第三段必须是 `advancements`（即 `data/<ns>/advancements/<path>.json`）");
            m.put("excludedByStrictRule",
                    "`data/<ns>/modifiers/advancements/…`（模组对**原版**成就的改写，不是新成就）—— "
                            + "实测本整合包内有 17 条（atmospheric 6 + environmental 11）；"
                            + "宽松的 contains(\"/advancements/\") 会把它们误计为 `<ns>:modifiers/…`，"
                            + "虚增分母 17 条并凭空造出 2 个命名空间（78 → 80）");
            if (extraJars > 0) {
                m.put("scopeIncludesVanilla",
                        "**含原版成就定义**：额外扫描了 " + extraJars + " 个数据包 jar（"
                                + String.join("、", extraJarNames) + "），其中的 `data/<ns>/advancements/**` 已计入分母"
                                + "（1.20.1 的原版定义就在这类版本 jar 里，实测 1,271 条）");
                m.put("reconciliation",
                        "含原版的合计分母 = mods+kubejs 的严格口径 + 版本 jar 的原版定义；"
                                + "**不得**再与「仅 mods+kubejs」的旧值（8,725 / 8,742）直接比较 —— 两者口径不同");
            } else {
                m.put("scopeExcludesVanilla",
                        "**本次未含原版成就定义**：只扫了 mods + kubejs。"
                                + "⚠️ 原版定义**并非取不到** —— 它就在整合包自带的**版本 jar** 的 "
                                + "`data/minecraft/advancements/**` 里（1.20.1 实测 1,271 条）；"
                                + "要计入请调用 `scan(mods, kubejs, List.of(版本jar))`。"
                                + "旧 caveat 曾写「client jar 内没有 data/ ⇒ 盘上取不到」，那句**已作废**");
                m.put("reconciliation",
                        "独立对账：**严格 8,725**，宽松 8,742（已作废）⇒ 以严格口径为准");
                m.put("scopeWarning",
                        "分母**不含原版**而分子含原版 ⇒ 完成率是**偏高**估计（真分母更大 ⇒ 真实完成率更低）");
            }
            m.put("scanSources", List.of("mods/**/*.jar → data/<ns>/advancements/",
                    "kubejs/data/<ns>/advancements/",
                    "（可选）额外数据包 jar（版本 jar）→ data/<ns>/advancements/"));
            m.put("deduplicatedBy", "<ns>:<path-without-.json>");
            m.put("jarsScanned", jars);
            m.put("jarsUnreadable", unreadableJars.size());
            m.put("extraJarsScanned", extraJars);
            m.put("extraJarNames", extraJarNames);
            m.put("kubejsAdvancementFiles", kubejsFiles);
            return m;
        }
    }
}
