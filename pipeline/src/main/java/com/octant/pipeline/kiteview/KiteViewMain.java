package com.octant.pipeline.kiteview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.octant.pipeline.kiteview.KiteShapeCriteria.Hit;
import com.octant.pipeline.kiteview.KiteViewData.Series;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KiteViewMain {

    private static final int OK = 0;
    private static final int FAIL = 1;
    private static final int ARGS = 2;

    private KiteViewMain() {
    }

    public static void main(String[] args) throws IOException {
        List<String> a = List.of(args);
        if (a.contains("--self-test")) {
            System.exit(selfTest());
        }
        if (a.contains("--criteria")) {
            int k = a.indexOf("--criteria");
            if (k + 1 >= a.size()) {
                System.out.println("ARGS-MISSING: --criteria 缺少 TSV 路径");
                System.exit(ARGS);
            }
            double floorArg = 0.0d;
            int fi = a.indexOf("--delta-floor");
            if (fi >= 0 && fi + 1 < a.size()) {
                floorArg = Double.parseDouble(a.get(fi + 1));
            }
            System.exit(criteriaRun(Paths.get(a.get(k + 1)), floorArg));
        }
        if (a.contains("--help")) {
            System.out.println("用法：--self-test | --out <目录>（默认 samples/behavior-kite）[--upstream <上游产物>]"
                    + "（须在仓库根运行）");
            System.exit(OK);
        }
        String out = "samples/behavior-kite";
        int i = a.indexOf("--out");
        if (i >= 0) {
            if (i + 1 >= a.size()) {
                System.out.println("ARGS-MISSING: --out 缺少目录参数");
                System.exit(ARGS);
            }
            out = a.get(i + 1);
        }
        Path t11 = Paths.get(KiteViewUpstream.DEFAULT_ARTIFACT);
        int j = a.indexOf("--upstream");
        if (j >= 0) {
            if (j + 1 >= a.size()) {
                System.out.println("ARGS-MISSING: --upstream 缺少文件参数");
                System.exit(ARGS);
            }
            t11 = Paths.get(a.get(j + 1));
        }
        System.exit(generate(Paths.get(out), t11));
    }

    static int criteriaRun(Path tsv, double floor) throws IOException {
        if (!Files.isRegularFile(tsv)) {
            System.out.println("TARGET-MISSING: " + tsv);
            return ARGS;
        }
        Series s = KiteViewTsv.read(readText(tsv), tsv.toString().replace('\\', '/'), "自定义轨迹（--criteria）", true);
        List<Hit> hits = KiteShapeCriteria.detectAll(s, floor);
        System.out.println("轨道文件：" + tsv.toString().replace('\\', '/'));
        System.out.println("样点 = " + s.size() + "；中位 Δ = " + String.format(java.util.Locale.ROOT, "%.0f",
                Double.valueOf(KiteShapeCriteria.medianDeltaSec(s))) + " s；"
                + KiteViewPageChecks.describe(s));
        if (KiteShapeCriteria.medianDeltaSec(s) <= 0.0d) {
            System.out.println("不适用：中位 Δ = 0 s ⇒ **「断线」判据无法判定**（既非通过、也非不通过）；"
                    + "常见成因：`t_sec` 用了非整数字面量被旧实现吞成 0。");
        }
        System.out.println("形态命中 = " + hits.size() + " 条");
        for (Hit h : hits) {
            System.out.println("  - " + h.label() + "（" + h.window() + "，步 " + h.fromIndex() + "–" + h.toIndex()
                    + "）：" + h.geometry());
        }
        for (String name : KiteShapeCriteria.SHAPE_NAMES) {
            int n = 0;
            for (Hit h : hits) {
                if (h.shape().equals(name)) {
                    n++;
                }
            }
            System.out.println("  " + name + " = " + n + " 次");
        }
        return hits.isEmpty() ? OK : FAIL;
    }

    static int generate(Path outDir, Path t11Artifact) throws IOException {
        Files.createDirectories(outDir);
        boolean allPass = true;
        StringBuilder report = new StringBuilder();
        report.append("# 行为风筝页导出指纹与判据结果\n\n");
        report.append("> 两份导出（同一输入渲染两次）用于证明**确定性**；上游指纹用于证明**数据来源**。\n");

        KiteViewUpstream.Info up = KiteViewUpstream.load(t11Artifact);
        report.append("\n## 一、上游接入\n\n| 项 | 值 |\n| --- | --- |\n");
        report.append("| 产物路径 | `").append(up.path()).append("` |\n");
        if (up.available()) {
            report.append("| 指纹 | sha16 `").append(up.sha16()).append("` / ").append(up.bytes()).append(" 字节 |\n");
            report.append("| schema / instrument | ").append(up.schema()).append(" / ").append(up.instrument()).append(" |\n");
            report.append("| 序列 kind / 样点 / 三轴齐备 | ").append(up.seriesKind()).append(" / ")
                  .append(up.pointCount()).append(" / ").append(up.usableCount()).append(" |\n");
            report.append("| **三轴正交区分度（上游 `OR-1`）** | **").append(up.orthVerdict())
                  .append("**；阈值 KITE_ORTHO_MAX = ")
                  .append(Double.isNaN(up.orthoMax()) ? "（未取到）" : Double.toString(up.orthoMax())).append(" |\n");
            for (KiteViewUpstream.Reading r : up.readings()) {
                report.append("| 上游读数 `").append(r.id()).append("` | ").append(r.outcome()).append("：")
                      .append(r.detail().replace("|", "\\|")).append(" |\n");
            }
            if (!up.firstSuppression().isEmpty()) {
                report.append("| 抑制说明（原文） | ").append(up.firstSuppression().replace("|", "\\|")).append(" |\n");
            }
        } else {
            report.append("| 状态 | **不适用**：").append(up.reason()).append(" |\n");
        }

        Map<String, List<String>> realChecksOut = null;
        if (up.available()) {
            List<Hit> realHits = KiteShapeCriteria.detectAll(up.series(), up.deltaMaxSec());
            String note = "上游产物（" + up.path() + "，sha16 " + up.sha16()
                    + "）逐点驱动；被抑制的轴记 `不可判定`（不记 0）。上游仍在收口 ⇒ 收口后须重跑本页并更新指纹。";
            String realHtml = KiteViewHtml.render(up.series(), realHits, up, note);
            String realHtml2 = KiteViewHtml.render(up.series(), realHits, up, note);
            writeText(outDir.resolve("kite.html"), realHtml);
            writeText(outDir.resolve("kite-noscript.html"), noScriptPage(up.series(), realHits, note, up));
            Map<String, List<String>> realChecks = KiteViewPageChecks.runAll(realHtml, up.series(), realHtml2);
            realChecksOut = realChecks;
            allPass &= allOk(realChecks);
            report.append("\n## 二、真实数据页（`kite.html`）\n\n");
            report.append(fingerprintTable(outDir, List.of("kite.html", "kite-noscript.html"), realHtml, realHtml2));
            report.append(checkTable(realChecks));
            report.append(shapeTable(realHits));
            System.out.println("真实数据页（上游 " + up.path() + "，sha16 " + up.sha16() + "）：");
            System.out.println("  三轴正交区分度（上游 OR-1）⇒ 本页裁决 = " + up.orthVerdict());
            printChecks(realChecks);
            System.out.println("  形态命中：" + realHits.size() + " 条（真实数据；被抑制的轴不参与）");
        } else {
            report.append("\n## 二、真实数据页\n\n**不适用**：").append(up.reason()).append("\n");
            System.out.println("真实数据页：**不适用** —— " + up.reason());
        }

        Series fixture = KiteViewFixtures.positiveFixture();
        Path demo = outDir.resolve("fixture-demo");
        Files.createDirectories(demo);
        Path tsv = demo.resolve("fixture-track.tsv");
        writeText(tsv, KiteViewTsv.write(fixture));
        String tsvRel = tsv.toString().replace('\\', '/');
        Series fs = KiteViewTsv.read(readText(tsv), tsvRel, "夹具·四形态正控（来自 " + tsvRel + "）", true);
        List<Hit> fh = KiteShapeCriteria.detectAll(fs);
        String fnote = "**夹具轨迹**（合成数据，来源 " + tsvRel + "）：用于验证形态判据与页面级判据自身，"
                + "**不是玩家真实数据**，不得据以得出关于玩家的任何结论。";
        String f1 = KiteViewHtml.render(fs, fh, fnote);
        String f2 = KiteViewHtml.render(fs, fh, fnote);
        writeText(demo.resolve("kite.html"), f1);
        writeText(demo.resolve("kite-noscript.html"),
                noScriptPage(fs, fh, fnote, KiteViewUpstream.Info.unavailable(tsvRel, fnote)));
        Map<String, List<String>> fChecks = KiteViewPageChecks.runAll(f1, fs, f2);
        allPass &= allOk(fChecks);
        report.append("\n## 三、夹具页（`fixture-demo/`，验证判据自身）\n\n");
        report.append(fingerprintTable(demo, List.of("fixture-track.tsv", "kite.html", "kite-noscript.html"), f1, f2));
        report.append(checkTable(fChecks));
        report.append(shapeTable(fh));
        writeText(outDir.resolve("EXPORT-FINGERPRINTS.md"), report.toString());
        System.out.println("\n夹具页（" + tsvRel + "）：");
        printChecks(fChecks);
        System.out.println("  形态命中：" + fh.size() + " 条");
        for (Hit x : fh) {
            System.out.println("  - " + x.label() + "（" + x.window() + "，步 " + x.fromIndex() + "–" + x.toIndex()
                    + "）：" + x.geometry());
        }
        System.out.println("\n页面级判据：" + verdictWord(allPass, fChecks, realChecksOut));
        System.out.println("报告：" + outDir.resolve("EXPORT-FINGERPRINTS.md").toString().replace('\\', '/'));
        return exitCode(allPass, fChecks, realChecksOut);
    }

    private static int exitCode(boolean allPass, Map<String, List<String>>... many) {
        boolean na = false;
        for (Map<String, List<String>> m : many) {
            if (m == null) {
                continue;
            }
            for (List<String> v : m.values()) {
                if (KiteViewPageChecks.isNotApplicable(v)) {
                    na = true;
                }
            }
        }
        if (allPass) {
            return na ? 3 : OK;
        }
        return FAIL;
    }

    private static String verdictWord(boolean allPass, Map<String, List<String>>... many) {
        if (allPass) {
            boolean na = false;
            for (Map<String, List<String>> m : many) {
                if (m != null) {
                    for (List<String> v : m.values()) {
                        na |= KiteViewPageChecks.isNotApplicable(v);
                    }
                }
            }
            return na ? "全部通过/不适用（无缺陷项）" : "全部通过";
        }
        return "**有不通过项**";
    }

    static String noScriptPage(Series s, List<Hit> hits, String note, KiteViewUpstream.Info up) {
        String full = KiteViewHtml.render(s, hits, up, note);
        int from = full.indexOf("<section id=\"fallback\">");
        int to = (from >= 0) ? full.indexOf("</section>", from) : -1;
        String body = (from >= 0 && to > from)
                ? full.substring(from, to + "</section>".length()) : "";
        String vb = KiteViewHtml.verdictSection(up, true);
        return "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<title>行为风筝：无脚本回退（静态快照）</title>\n"
                + "<style>body{font-family:system-ui,sans-serif;margin:0;padding:1.5rem;background:#101418;color:#e8eef2}"
                + "h1{font-size:1.25rem}h2{font-size:1rem}figure{margin:1rem 0}</style>\n</head>\n<body>\n"
                + "<h1>行为风筝：无脚本回退（静态快照）</h1>\n"
                + "<p>本页**不含任何脚本**，只呈现同一数据在 " + KiteViewHtml.FALLBACK_SNAPSHOTS
                + " 个时间点的静态 SVG 快照、形态标记与三轴刻度。数据来源：" + esc(note) + "</p>\n"
                + vb + body + "</body>\n</html>\n";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static int checkConstants() {
        double runtime = KiteShapeCriteria.TAUT_CURVATURE_TAU;
        String srcRel = System.getProperty("kite.constants.source",
                "pipeline/src/main/java/com/octant/pipeline/kiteview/KiteShapeCriteria.java");
        String clauseRel = System.getProperty("kite.constants.clause",
                "docs/design/behavior-kite-presentation.md");
        Double src = readConstant(srcRel, "TAUT_CURVATURE_TAU");
        Double clause = readConstant(clauseRel, "TAUT_CURVATURE_TAU");
        System.out.printf(Locale.ROOT,
                "CONST-CHECK TAUT_CURVATURE_TAU 源码=%s 运行体=%.5f 条款=%s class-sha16=%s%n",
                src == null ? "(不可读)" : String.format(Locale.ROOT, "%.5f", src),
                runtime,
                clause == null ? "(不可读)" : String.format(Locale.ROOT, "%.5f", clause),
                classSha16("com/octant/pipeline/kiteview/KiteShapeCriteria.class"));
        if (src == null) {
            System.out.println("CONST-CHECK [UNVERIFIED] 源码不可读 ⇒ **未判定**（不得当作通过）：" + srcRel);
            return 1;
        }
        boolean ok = Math.abs(src - runtime) < 1e-12
                && (clause == null || Math.abs(clause - src) < 1e-12);
        if (!ok) {
            System.out.println("CONST-CHECK [FAIL] **三处不一致**：源码="
                    + String.format(Locale.ROOT, "%.5f", src)
                    + " 运行体=" + String.format(Locale.ROOT, "%.5f", runtime)
                    + (clause == null ? "" : " 条款=" + String.format(Locale.ROOT, "%.5f", clause))
                    + " ⇒ 应重编译（**不改源码**），否则一次重编译就会让阈值回退/前移");
            return 1;
        }
        System.out.println("CONST-CHECK [OK] 源码 / 运行体 / 条款 三处一致");
        return 0;
    }

    private static Double readConstant(String rel, String name) {
        try {
            String text = new String(Files.readAllBytes(Paths.get(rel)), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*([0-9.]+)").matcher(text);
            if (m.find()) {
                return Double.valueOf(Double.parseDouble(m.group(1)));
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static String classSha16(String res) {
        try (java.io.InputStream in = KiteViewMain.class.getClassLoader().getResourceAsStream(res)) {
            if (in == null) {
                return "(不可读)";
            }
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) {
                sb.append(String.format(Locale.ROOT, "%02X", x));
            }
            return sb.substring(0, 16);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return "(不可读)";
        }
    }

    static int selfTest() {
        int failures = 0;
        failures += checkConstants();
        System.out.println("=== 行为风筝呈现层自检===");
        System.out.println("仪器：com.octant.pipeline.kiteview.KiteViewMain（本文件）");

        System.out.println("\n① 正控（夹具应命中四类形态）：");
        Series pos = KiteViewFixtures.positiveFixture();
        System.out.println("  " + KiteViewPageChecks.describe(pos));
        List<Hit> hits = KiteShapeCriteria.detectAll(pos);
        for (String name : KiteShapeCriteria.SHAPE_NAMES) {
            int n = 0;
            for (Hit h : hits) {
                if (h.shape().equals(name)) {
                    n++;
                }
            }
            boolean ok = n > 0;
            failures += ok ? 0 : 1;
            System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] 形态「" + name + "」命中 " + n + " 次");
            for (Hit h : hits) {
                if (h.shape().equals(name)) {
                    System.out.println("         几何量：" + h.geometry());
                    break;
                }
            }
        }

        System.out.println("\n② 假阳性负控（人造不该命中的轨迹 ⇒ 必须不命中）：");
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[] {"断线", KiteViewFixtures.uniformCadenceTrack()});
        cases.add(new Object[] {"原地打转", KiteViewFixtures.straightRunTrack()});
        cases.add(new Object[] {"紧绷直线", KiteViewFixtures.serpentineTrack()});
        cases.add(new Object[] {"剧烈震荡", KiteViewFixtures.constantCurvatureTrack()});
        for (Object[] c : cases) {
            String shape = (String) c[0];
            Series neg = (Series) c[1];
            List<Hit> hh = KiteShapeCriteria.detectAll(neg);
            int n = 0;
            for (Hit h : hh) {
                if (h.shape().equals(shape)) {
                    n++;
                }
            }
            boolean ok = n == 0;
            failures += ok ? 0 : 1;
            System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + neg.label() + " ⇒ 「" + shape + "」命中 "
                    + n + " 次（期望 0）" + (n > 0 ? "  **假阳性**" : ""));
        }

        System.out.println("\n②b 断线判据的**设计内 Δ 地板**（真实数据上抓到的假阳性；双向对照）：");
        Series adaptive = KiteViewFixtures.adaptiveCadenceTrack();
        int noFloor = countShape(KiteShapeCriteria.detectAll(adaptive, 0.0d), "断线");
        int withFloor = countShape(KiteShapeCriteria.detectAll(adaptive, 900.0d), "断线");
        failures += expectTrue("自适应 Δ（150–900 s）在**无地板**时会被误报 ⇒ 期望 >0", noFloor > 0, "实测 " + noFloor + " 条");
        failures += expectTrue("同一序列加**上游设计上界 900 s 地板** ⇒ 期望 0（不得误报）", withFloor == 0, "实测 " + withFloor + " 条");
        int gapHits = countShape(KiteShapeCriteria.detectAll(KiteViewFixtures.realGapTrack(), 900.0d), "断线");
        failures += expectTrue("真缺口（Δt=3000 s > 地板 900 s）⇒ 仍必须命中（地板不得掩盖真缺口）", gapHits > 0, "实测 " + gapHits + " 条");

        System.out.println("\n③ 页面级判据（正例应全通过）：");
        String note = "夹具轨迹（合成数据）。";
        String p1 = KiteViewHtml.render(pos, hits, note);
        String p2 = KiteViewHtml.render(pos, hits, note);
        Map<String, List<String>> checks = KiteViewPageChecks.runAll(p1, pos, p2);
        for (Map.Entry<String, List<String>> e : checks.entrySet()) {
            boolean ok = e.getValue().isEmpty();
            failures += ok ? 0 : 1;
            System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + e.getKey()
                    + (ok ? "" : " ⇒ " + String.join("；", e.getValue())));
        }

        System.out.println("\n④ 负控（每条都必须让对应判据变红）：");
        String tampered = p1.replaceFirst(">(\\d\\.\\d{3})</span>", ">0.999</span>");
        failures += expectViolation("把某个展示值改成查不到的值", KiteViewPageChecks.checkTraceable(tampered, pos));
        String noFallback = p1.replace("<noscript>", "<div>").replace("</noscript>", "</div>");
        failures += expectViolation("删掉无脚本回退", KiteViewPageChecks.checkFallback(noFallback));
        String hueOnly = p1.replace("stroke-dasharray=\"" + KiteChannels.DASH_URGENT + "\"",
                "stroke-dasharray=\"" + KiteChannels.DASH_SMOOTH + "\"")
                .replace("dashUrgent='" + KiteChannels.DASH_URGENT + "'", "dashUrgent='none'");
        failures += expectViolation("只有色相、没有线型", KiteViewPageChecks.checkDashPairing(hueOnly));
        failures += expectViolation("两条映射的被编码量同名（颜色 → U_t；线宽 → U_t）",
                KiteChannels.validateChannelMappings("U_t", "U_t"));
        String ext = p1.replace("</head>", "<link rel=\"stylesheet\" href=\"x.css\"></head>");
        failures += expectViolation("注入外链样式", KiteViewPageChecks.checkOffline(ext));
        String extraHue = p1.contains("#0d1216") ? p1.replace("#0d1216", "#00FF00") : p1.replace("#101418", "#00FF00");
        failures += expectViolation("给阶段/任意元素另立色相（#00FF00）", KiteViewPageChecks.checkHues(extraHue));
        String noValence = p1.replace("data-valence-neutral=\"true\"", "data-valence-neutral=\"false\"");
        failures += expectViolation("把 valenceNeutral 改成 false", KiteViewPageChecks.checkHues(noValence));

        System.out.println("\n⑤ 正交区分度门（上游 OR-1 读数 ⇒ 本页裁决；双向都要能红）：");
        failures += expectVerdict("上游 OR-1 = PASS 且 ρ² 均在阈值内",
                KiteViewUpstream.orthGate("PASS", List.of(0.01d, 0.02d), 0.25d), "PASS");
        failures += expectVerdict("上游 OR-1 = FAIL", KiteViewUpstream.orthGate("FAIL", List.of(0.71d), 0.25d), "FAIL");
        failures += expectVerdict("上游 OR-1 = UNVERIFIED", KiteViewUpstream.orthGate("UNVERIFIED", List.of(), 0.25d), "UNVERIFIED");
        failures += expectVerdict("上游 OR-1 缺失", KiteViewUpstream.orthGate("MISSING", List.of(), 0.25d), "UNVERIFIED");
        failures += expectVerdict("上游 OR-1 = PASS 但 ρ² = 0.718 > 阈值 0.25（数值独立兜底）",
                KiteViewUpstream.orthGate("PASS", List.of(0.718d), 0.25d), "FAIL");

        System.out.println("\n⑥ 上游接入（信息性；缺产物 ⇒ 不适用，本身不算判据失败）：");
        KiteViewUpstream.Info up = KiteViewUpstream.load(Paths.get(KiteViewUpstream.DEFAULT_ARTIFACT));
        if (up.available()) {
            System.out.println("  [INFO] 上游 " + up.path() + " sha16=" + up.sha16() + " / " + up.bytes() + " 字节");
            System.out.println("  [INFO] 样点=" + up.pointCount() + "，三轴齐备=" + up.usableCount());
            System.out.println("  [INFO] 三轴正交区分度裁决 = " + up.orthVerdict()
                    + "（阈值 KITE_ORTHO_MAX=" + up.orthoMax() + "）");
        } else {
            System.out.println("  [INFO] 上游不可用 ⇒ 页面按 §1 显示 `不适用`：" + up.reason());
        }

        System.out.println("\n自检小结：" + (failures == 0 ? "全部符合预期" : ("**" + failures + " 项不符合预期**")));
        return failures == 0 ? OK : FAIL;
    }

    private static int expectViolation(String what, List<String> violations) {
        boolean ok = !violations.isEmpty();
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] 负控「" + what + "」⇒ "
                + (ok ? "判据变红（" + violations.get(0) + "）" : "**判据没有变红（判据失效）**"));
        return ok ? 0 : 1;
    }

    private static int expectVerdict(String what, String got, String want) {
        boolean ok = want.equals(got);
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] 门控「" + what + "」⇒ 裁决 = " + got + "（期望 " + want + "）");
        return ok ? 0 : 1;
    }

    private static int countShape(List<Hit> hits, String shape) {
        int n = 0;
        for (Hit h : hits) {
            if (h.shape().equals(shape)) {
                n++;
            }
        }
        return n;
    }

    private static int expectTrue(String what, boolean ok, String evidence) {
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + what + "（" + evidence + "）");
        return ok ? 0 : 1;
    }

    private static boolean allOk(Map<String, List<String>> checks) {
        boolean ok = true;
        for (List<String> v : checks.values()) {
            if (!v.isEmpty() && !KiteViewPageChecks.isNotApplicable(v)) {
                ok = false;
            }
        }
        return ok;
    }

    private static void printChecks(Map<String, List<String>> checks) {
        for (Map.Entry<String, List<String>> e : checks.entrySet()) {
            String mark = e.getValue().isEmpty() ? "PASS"
                    : (KiteViewPageChecks.isNotApplicable(e.getValue()) ? "N/A" : "FAIL");
            System.out.println("  [" + mark + "] " + e.getKey()
                    + (e.getValue().isEmpty() ? "" : " ⇒ " + String.join("；", e.getValue())));
        }
    }

    private static String checkTable(Map<String, List<String>> checks) {
        StringBuilder sb = new StringBuilder("\n### 页面级判据\n\n| 判据 | 结果 | 证据 |\n| --- | --- | --- |\n");
        for (Map.Entry<String, List<String>> e : checks.entrySet()) {
            String mark = e.getValue().isEmpty() ? "通过"
                    : (KiteViewPageChecks.isNotApplicable(e.getValue()) ? "**不适用**（域空；不计入通过）" : "不通过");
            sb.append("| ").append(e.getKey()).append(" | ").append(mark)
              .append(" | ").append(e.getValue().isEmpty() ? "—" : String.join("；", e.getValue()).replace("|", "\\|"))
              .append(" |\n");
        }
        return sb.toString();
    }

    private static String shapeTable(List<Hit> hits) {
        StringBuilder sb = new StringBuilder("\n### 形态命中（几何判据）\n\n| 形态 | 窗口 | 步区间 | 几何量 |\n| --- | --- | --- | --- |\n");
        if (hits.isEmpty()) {
            sb.append("| （无） | — | — | — |\n");
        }
        for (Hit h : hits) {
            sb.append("| ").append(h.label()).append(" | ").append(h.window()).append(" | 步 ")
              .append(h.fromIndex()).append("–").append(h.toIndex()).append(" | ")
              .append(h.geometry().replace("|", "\\|")).append(" |\n");
        }
        return sb.toString();
    }

    private static String fingerprintTable(Path dir, List<String> names, String html1, String html2) {
        StringBuilder sb = new StringBuilder("| 产物 | 字节 | sha256（前 16 位） | 行数（LF） |\n| --- | --- | --- | --- |\n");
        for (String n : names) {
            try {
                Path p = dir.resolve(n);
                byte[] b = Files.readAllBytes(p);
                sb.append("| `").append(p.toString().replace('\\', '/')).append("` | ").append(b.length)
                  .append(" | `").append(sha16(b)).append("` | ")
                  .append(new String(b, StandardCharsets.UTF_8).split("\n", -1).length).append(" |\n");
            } catch (IOException e) {
                sb.append("| `").append(n).append("` | （不可读） | — | — |\n");
            }
        }
        sb.append("\n两次渲染逐字节一致：**").append(html1.equals(html2) ? "是" : "否").append("**\n");
        return sb.toString();
    }

    static void writeText(Path p, String text) throws IOException {
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    static String readText(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    static String sha16(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.ROOT, "%02X", Byte.valueOf(d[i])));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
