package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KiteSelfCheck {

    public static final String VERSION = "kite-selfcheck/1.0.0";

    public enum Outcome {
        PASS,
        FAIL,
        UNVERIFIED
    }

    public record Result(String id, Outcome outcome, String detail) {
    }

    static final class Runner {
        private final List<Result> results = new ArrayList<>();

        void pass(String id, String detail) {
            results.add(new Result(id, Outcome.PASS, detail));
        }

        void fail(String id, String detail) {
            results.add(new Result(id, Outcome.FAIL, detail));
        }

        void unverified(String id, String detail) {
            results.add(new Result(id, Outcome.UNVERIFIED, detail));
        }

        void require(String id, boolean ok, String detail) {
            if (ok) {
                pass(id, detail);
            } else {
                fail(id, detail);
            }
        }

        List<Result> results() {
            return results;
        }

        int count(Outcome o) {
            int n = 0;
            for (Result r : results) {
                if (r.outcome() == o) {
                    n++;
                }
            }
            return n;
        }
    }

    static int exitCode(boolean crashed, boolean missingTarget, int fails, int unverified) {
        if (crashed) {
            return 4;
        }
        if (missingTarget) {
            return 2;
        }
        if (unverified > 0) {
            return 3;
        }
        if (fails > 0) {
            return 1;
        }
        return 0;
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            java.io.PrintStream err = new java.io.PrintStream(System.err, true,
                    java.nio.charset.StandardCharsets.US_ASCII);
            err.println("CRASH: " + t.getClass().getName());
            err.println("       " + String.valueOf(t.getMessage()));
            err.flush();
            System.exit(4);
        }
    }

    private static void run(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        java.io.PrintStream out = asciiSafe(System.out);
        boolean hashOnly = opt.containsKey("hash-only");

        if (opt.containsKey("axis-profile")) {
            KiteAxisProfile p = KiteAxisProfile.of(opt.get("axis-profile"));
            if (p == null) {
                out.println("KITE-SELFCHECK 参数错：未知 --axis-profile=" + opt.get("axis-profile")
                        + "（允许值：legacy / redefined-2026）（退出码 2）");
                out.flush();
                System.exit(2);
            }
            KiteAxisProfile.setCurrent(p);
        }
        out.println("轴定义档位 = " + KiteAxisProfile.current().wireName() + "（"
                + KiteAxisProfile.current().note() + "）");

        String fixturePath = opt.get("fixture");
        if (fixturePath == null || fixturePath.isEmpty()) {
            out.println("KITE-SELFCHECK 参数缺失：必须给 --fixture <events.jsonl>（退出码 2）");
            out.flush();
            System.exit(2);
        }
        java.nio.file.Path fixture = java.nio.file.Path.of(fixturePath).toAbsolutePath();
        if (!java.nio.file.Files.isRegularFile(fixture)) {
            out.println("KITE-SELFCHECK 目标缺失：fixture 不存在或不可读：" + fixture + "（退出码 2）");
            out.flush();
            System.exit(2);
        }

        List<String> nonEmpty = new ArrayList<>();
        for (String l : java.nio.file.Files.readAllLines(fixture, java.nio.charset.StandardCharsets.UTF_8)) {
            if (!l.trim().isEmpty()) {
                nonEmpty.add(l);
            }
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (String l : nonEmpty) {
            Object o = MiniJson.parse(l);
            if (o instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) m;
                events.add(mm);
            } else {
                throw new IllegalArgumentException("fixture 行不是 JSON 对象：" + l.substring(0, Math.min(60, l.length())));
            }
        }

        long origin = opt.containsKey("origin-ms") ? Long.parseLong(opt.get("origin-ms")) : minTRelMs(events);
        java.nio.file.Path workingDir = java.nio.file.Path.of("").toAbsolutePath();
        KiteLabelRegistry registry = KiteLabelRegistry.load(
                opt.containsKey("label-map") ? java.nio.file.Path.of(opt.get("label-map")) : null, workingDir);

        Runner r = new Runner();
        byte[] fixtureBytes = java.nio.file.Files.readAllBytes(fixture);
        String fixtureSha = Fingerprint.sha256(fixtureBytes);
        out.println("KITE-SELFCHECK " + VERSION);
        out.println("对象指纹 --fixture: bytes=" + fixtureBytes.length
                + " sha16=" + Fingerprint.sha16(fixtureSha) + " sha256=" + fixtureSha
                + " 行数口径(LF)=" + Fingerprint.lineCount(fixtureBytes)
                + " CRLF=" + Fingerprint.countCrlf(fixtureBytes)
                + " 非空行=" + nonEmpty.size() + " 解析事件=" + events.size());
        out.println("仪器指纹 " + instrumentFingerprint(workingDir));
        out.println("标签登记镜像 = " + registry.mirror() + "（" + registry.mirrorNote() + "）");
        out.println("时基 t_origin = " + origin + " ms（"
                + (opt.containsKey("origin-ms") ? "由 --origin-ms 显式给定" : "由输入事件最小 tRelMs 给定")
                + "；**不读墙上时钟**，规格 §7 DT-0）");

        List<String> asserted;
        try {
            asserted = KiteConstants.verifyConstants();
            r.pass("AX-C", "常量表 " + KiteConstants.registry().size() + " 行；可断言关系 " + asserted.size()
                    + " 条全部成立：" + asserted);
        } catch (AssertionError e) {
            r.fail("AX-C", "常量表可断言关系被破坏：" + e.getMessage());
            asserted = List.of();
        }

        KiteObservationSource source = KiteObservationSource.of(events, origin);
        int bins = Math.max(1, (int) Math.ceil((double) spanSec(events) / (double) KiteConstants.KITE_DT_REF_S));
        KiteSeries series = KiteSeries.fixedBins(source, KiteConstants.KITE_DT_REF_S, bins, false);
        KiteSeries snapshotSeries = KiteSeries.fixedBins(source, KiteConstants.KITE_DT_REF_S, bins, true);

        String axisManifest = KiteArtifact.axisManifest(KiteAxisProfile.current(), source);
        if (opt.containsKey("axis-manifest")) {
            java.nio.file.Path mp = java.nio.file.Path.of(opt.get("axis-manifest")).toAbsolutePath();
            if (mp.getParent() != null) {
                java.nio.file.Files.createDirectories(mp.getParent());
            }
            java.nio.file.Files.write(mp, axisManifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] mb = java.nio.file.Files.readAllBytes(mp);
            out.println("轴定义记录已落盘：" + mp + " bytes=" + mb.length
                    + " sha16=" + Fingerprint.sha16(Fingerprint.sha256(mb))
                    + " LF=" + Fingerprint.lineCount(mb) + " CRLF=" + Fingerprint.countCrlf(mb));
        }

        java.nio.file.Path diagDir = opt.containsKey("diag-dir")
                ? java.nio.file.Path.of(opt.get("diag-dir")).toAbsolutePath() : null;
        KiteAxisDiagnostics.Result diag = KiteAxisDiagnostics.run(source, series);
        if (diagDir != null) {
            java.nio.file.Files.createDirectories(diagDir);
            byte[] repBytes = diag.reportText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] csvBytes = diag.csvText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.write(diagDir.resolve("axis-candidates.txt"), repBytes);
            java.nio.file.Files.write(diagDir.resolve("axis-candidates.csv"), csvBytes);
            out.println("轴候选诊断已落盘：" + diagDir.resolve("axis-candidates.txt") + "（" + repBytes.length
                    + " B）与 axis-candidates.csv（" + csvBytes.length + " B）");
        }
        if (!hashOnly) {
            out.println();
            out.println(diag.reportText());
        }

        if (!hashOnly) {
            out.println();
            out.println("── 常量冻结（" + KiteConstants.registry().size() + " 项）──");
            for (String s : KiteConstants.dump()) {
                out.println("  " + s);
            }
            out.println("  可断言关系：" + asserted);
            out.println();
            out.println("── 三轴登记（键 → 原语）──");
            for (String s : KiteAxisDefinition.dumpKeys()) {
                out.println("  " + s);
            }
            out.println("  归一化式：");
            for (String s : KiteAxisDefinition.dumpAxes()) {
                out.println("    " + s);
            }
            out.println("  原语级两两相交读数：");
            for (String s : KiteAxisDefinition.dumpPrimitiveCollision()) {
                out.println("    " + s);
            }
        }

        KiteSelfCheckChecks.run(r, source, series, snapshotSeries, registry, workingDir, out, hashOnly);

        String canonical = KiteArtifact.build(source, series, snapshotSeries, registry, r, fixtureSha, asserted,
                VERSION, origin);
        byte[] canonicalBytes = canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String artifactSha = Fingerprint.sha256(canonicalBytes);
        String outPath = opt.get("out");
        if (outPath != null && !outPath.isEmpty()) {
            java.nio.file.Path p = java.nio.file.Path.of(outPath).toAbsolutePath();
            if (p.getParent() != null) {
                java.nio.file.Files.createDirectories(p.getParent());
            }
            java.nio.file.Files.write(p, canonicalBytes);
            byte[] ab = java.nio.file.Files.readAllBytes(p);
            out.println("工件指纹 --out: path=" + p + " bytes=" + ab.length
                    + " sha16=" + Fingerprint.sha16(Fingerprint.sha256(ab))
                    + " sha256=" + Fingerprint.sha256(ab)
                    + " 行数口径(LF)=" + Fingerprint.lineCount(ab)
                    + " CRLF=" + Fingerprint.countCrlf(ab));
        }
        out.println("CANONICAL-SHA256=" + artifactSha);
        out.println("CANONICAL-BYTES=" + canonicalBytes.length);

        int pass = r.count(Outcome.PASS);
        int fail = r.count(Outcome.FAIL);
        int unv = r.count(Outcome.UNVERIFIED);
        int code = exitCode(false, false, fail, unv);
        if (!hashOnly) {
            out.println();
            out.println("── 判据结果（" + r.results().size() + " 条）──");
            for (Result res : r.results()) {
                out.println("  [" + res.outcome() + "] " + res.id() + " — " + res.detail());
            }
        }
        out.println("KITE-SELFCHECK 汇总：PASS=" + pass + " FAIL=" + fail + " UNVERIFIED=" + unv
                + " 退出码=" + code + (code == 0 ? "（全部判据通过）"
                : code == 1 ? "（判据失败）" : code == 3 ? "（有项未判定）" : ""));
        out.flush();
        System.exit(code);
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                continue;
            }
            String key = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opt.put(key, args[i + 1]);
                i++;
            } else {
                opt.put(key, "true");
            }
        }
        return opt;
    }

    static long minTRelMs(List<Map<String, Object>> events) {
        long min = Long.MAX_VALUE;
        for (Map<String, Object> e : events) {
            Object t = e.get("tRelMs");
            if (t instanceof Number n) {
                min = Math.min(min, n.longValue());
            }
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }

    static long maxTRelMs(List<Map<String, Object>> events) {
        long max = Long.MIN_VALUE;
        for (Map<String, Object> e : events) {
            Object t = e.get("tRelMs");
            if (t instanceof Number n) {
                max = Math.max(max, n.longValue());
            }
        }
        return max == Long.MIN_VALUE ? 0L : max;
    }

    static long spanSec(List<Map<String, Object>> events) {
        return Math.max(0L, (maxTRelMs(events) - minTRelMs(events)) / 1000L);
    }

    static String instrumentFingerprint(java.nio.file.Path workingDir) {
        StringBuilder sb = new StringBuilder();
        String[] classes = {"KiteSelfCheck.class", "KiteSelfCheckChecks.class", "KiteArtifact.class"};
        for (String c : classes) {
            java.nio.file.Path p = workingDir.resolve("pipeline/build/classes/java/main/com/octant/pipeline/kite")
                    .resolve(c);
            sb.append(c).append("=").append(Fingerprint.instrument(p)).append(' ');
        }
        return "classSha16{" + sb.toString().trim() + "}";
    }

    static java.io.PrintStream asciiSafe(java.io.PrintStream original) {
        try {
            java.io.PrintStream utf8 = new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                    java.nio.charset.StandardCharsets.UTF_8);
            utf8.print("");
            utf8.flush();
            return utf8;
        } catch (Throwable t) {
            return original;
        }
    }
}
