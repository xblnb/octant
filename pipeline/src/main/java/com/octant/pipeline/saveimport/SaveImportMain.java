package com.octant.pipeline.saveimport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.octant.common.model.Json;

public final class SaveImportMain {

    private SaveImportMain() {
    }

    public static void main(String[] args) {
        Map<String, String> opt = new LinkedHashMap<>();
        List<String> expects = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--expect-leak".equals(args[i]) && i + 1 < args.length) {
                expects.add(args[++i]);
            } else if (args[i].startsWith("--") && i + 1 < args.length) {
                opt.put(args[i].substring(2), args[++i]);
            }
        }
        Path saveDir = required(opt, "save");
        Path modsDir = opt.containsKey("mods") ? Path.of(opt.get("mods")) : null;
        Path kubejsDir = opt.containsKey("kubejs") ? Path.of(opt.get("kubejs")) : null;
        List<Path> extraJars = new ArrayList<>();
        for (String v : opt.getOrDefault("version-jar", "").split(";")) {
            if (!v.isBlank()) {
                extraJars.add(Path.of(v));
            }
        }
        Path outDir = required(opt, "out");

        try {
            System.out.println("[saveimport] 读存档（只读）: " + saveDir);
            SaveReader.SaveSnapshot save = SaveReader.read(saveDir);
            System.out.printf("[saveimport] 成就记录 %d（done %d / undone %d / 异常 %d）%n",
                    save.advancementRecords(), save.done(), save.undone(), save.malformed());
            System.out.printf("[saveimport] play_time = %d tick（%.2f 小时）%n",
                    save.playTimeTicks(), save.playHours());

            System.out.println("[saveimport] 扫真分母（mod jar + kubejs"
                    + (extraJars.isEmpty() ? "）…" : " + 额外数据包 jar " + extraJars.size() + " 个）…"));
            AdvancementCatalog.Catalog cat = AdvancementCatalog.scan(modsDir, kubejsDir, extraJars);
            System.out.printf("[saveimport] 真分母 = %d（命名空间 %d；jar %d 个，不可读 %d；额外 jar %d 个）%n",
                    cat.total(), cat.namespaceCount(), cat.jars(), cat.unreadableJars().size(),
                    cat.extraJars());

            SaveReport.Analysis a = SaveReport.analyze(save, cat,
                    saveDir.getFileName() == null ? "" : saveDir.getFileName().toString(), null);

            System.out.printf("[saveimport] 完成率 = %.2f%%（%s / %d）%n",
                    a.progress().get("completionPercent"), a.progress().get("done"),
                    a.progress().get("denominator"));
            System.out.printf("[saveimport] 时间序异常 = %d 项%n", a.anomalies().size());
            for (String s : a.anomalies()) {
                System.out.println("    ! " + s);
            }

            Map<String, Object> files = SaveReport.write(a, outDir, save, cat);
            System.out.println("[saveimport] 产物：");
            for (Map.Entry<String, Object> e : files.entrySet()) {
                System.out.println("    " + e.getKey() + "  " + e.getValue());
            }

            List<String> sensitive = new ArrayList<>(expects);
            if (saveDir.getFileName() != null) {
                sensitive.add(saveDir.getFileName().toString());
            }
            StringBuilder all = new StringBuilder();
            for (String f : List.of("report.html", "save-analysis.json")) {
                Path p = outDir.resolve(f);
                if (Files.isRegularFile(p)) {
                    all.append(Files.readString(p));
                }
            }
            List<String> leaks = SaveReport.scanForLeaks(all.toString(), sensitive);
            System.out.println("[saveimport] 脱敏扫描：命中 " + leaks.size() + " 项");
            for (String l : leaks) {
                System.out.println("    ! " + l);
            }

            if (!leaks.isEmpty()) {
                System.exit(2);
            }
            if (!a.anomalies().isEmpty()) {
                System.exit(1);
            }
            System.out.println("[saveimport] 结论：通过（时间序无异常 + 脱敏 0 命中）");
            System.exit(0);
        } catch (Exception ex) {
            System.out.println("[saveimport] 失败：" + ex);
            ex.printStackTrace(System.out);
            System.exit(3);
        }
    }

    private static Path required(Map<String, String> opt, String key) {
        String v = opt.get(key);
        if (v == null || v.isBlank()) {
            System.out.println("[saveimport] 缺少必要参数 --" + key);
            System.exit(3);
        }
        return Path.of(v);
    }

    static String toJson(SaveReport.Analysis a) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SaveReport.SCHEMA);
        root.put("progress", a.progress());
        root.put("anomalies", a.anomalies());
        root.put("passed", a.passed());
        return Json.encode(root);
    }
}
