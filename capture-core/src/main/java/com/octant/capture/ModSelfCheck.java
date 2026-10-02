package com.octant.capture;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.EventSource;
import com.octant.common.model.RawEventSchema;
import com.octant.common.privacy.adapter.ConsentGate;
import com.octant.common.privacy.adapter.ConsentState;
import com.octant.common.session.RawEventStore;
import com.octant.pipeline.adapter.CaptureEventAdapter;
import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.RawEvent;
import com.octant.pipeline.raw.UnitKind;
import com.octant.pipeline.report.html.HtmlReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ModSelfCheck {

    public record Result(boolean ok, List<String> lines) {
    }

    private ModSelfCheck() {
    }

    public static Result run(OctantHost host) {
        return run(host, null);
    }

    public static Result run(OctantHost host, CaptureRuntime runtime) {
        List<String> lines = new ArrayList<>();
        boolean ok = true;

        List<CaptureEvent> captured;
        try {
            captured = syntheticEvents();
            lines.add("OK  :common 契约封套可构造（" + captured.size() + " 条事件）");
        } catch (RuntimeException ex) {
            lines.add("FAIL 契约封套构造失败：" + ex.getClass().getSimpleName() + " " + ex.getMessage());
            return new Result(false, lines);
        }

        CaptureEventAdapter.Conversion conv;
        try {
            conv = CaptureEventAdapter.toPipelineTolerant(captured);
            lines.add("OK  :pipeline 适配器可消费（接受 " + conv.acceptedCount()
                    + "，拒绝 " + conv.rejectedCount() + "）");
            if (conv.rejectedCount() > 0) {
                ok = false;
                lines.add("FAIL 自造语料存在被拒事件（适配器与采集模型不一致）："
                        + conv.rejections().subList(0, Math.min(3, conv.rejections().size())));
            }
        } catch (RuntimeException ex) {
            lines.add("FAIL 适配器调用失败：" + ex.getClass().getSimpleName() + " " + ex.getMessage());
            return new Result(false, lines);
        }

        ContentCatalog catalog = new ContentCatalog("selftest");
        catalog.register(UnitKind.ADVANCEMENT, List.of("minecraft:story/root"), true);
        catalog.register(UnitKind.BIOME, List.of("minecraft:plains"), true);
        catalog.register(UnitKind.DIMENSION, List.of("minecraft:overworld"), true);
        AnalysisReport report;
        try {
            List<RawEvent> events = conv.events();
            report = AnalysisReport.analyze(events, catalog, host.loader());
            lines.add("OK  :pipeline 分析完成：指标 " + report.metrics().size()
                    + " 项（其中抑制 " + report.suppressedMetrics().size()
                    + "，全抑制=" + report.allSuppressed() + "）");
        } catch (RuntimeException ex) {
            lines.add("FAIL 分析失败：" + ex.getClass().getSimpleName() + " " + ex.getMessage());
            return new Result(false, lines);
        }

        try {
            ExportPipeline.Input input = new ExportPipeline.Input(
                    report, conv::events, catalog, host.modVersion(), host.gameVersion(),
                    host.loader(), "selftest-salt", List.of("C1", "C2", "C3", "C5", "C6", "C7", "C8"),
                    host.modsList());
            HtmlReport.Result html = new HtmlReport(report,
                    new com.octant.pipeline.report.ReportDocument.ExportMeta(
                            "selftest", "1970-01-01", "1970-01-01T00:00Z",
                            host.modVersion(), host.gameVersion(), host.loader(),
                            ExportPipeline.REPORT_FORMAT_VERSION, ExportPipeline.PRIVACY_SPEC_VERSION,
                            List.of("C1", "C2", "C3", "C5", "C6", "C7", "C8"),
                            List.of("local_only_no_upload"), List.of("raw_events"),
                            ExportPipeline.RECIPIENT_NOTICE, host.modsList()),
                    null).render();
            String text = html.htmlText();
            lines.add("OK  HtmlReport 与字体资产可用：HTML " + html.html().length
                    + " 字节、图 " + html.chartsDrawn() + " 张、字体子集 " + html.fontSubsetBytes()
                    + " B、字形 " + html.glyphCount() + " 个");
            if (!structuralCheck(text, html, lines)) {
                ok = false;
            }
            if (input.report() == null) {
                ok = false;
                lines.add("FAIL 导出输入构造异常");
            }
        } catch (Throwable ex) {
            lines.add("FAIL HTML 渲染失败（通常意味着产品路径的依赖没被打进 jar）："
                    + ex.getClass().getName() + " " + ex.getMessage());
            return new Result(false, lines);
        }

        try {
            Path dir = com.octant.common.privacy.OctantPaths.dataDir(host.worldDir());
            Files.createDirectories(dir);
            lines.add("OK  事件目录可写：" + dir);
        } catch (Exception ex) {
            ok = false;
            lines.add("FAIL 事件目录不可写：" + ex.getMessage());
        }

        try {
            ConsentGate gate = new ConsentGate(ConsentState.denied(), System::currentTimeMillis);
            if (gate.isCollecting()) {
                ok = false;
                lines.add("FAIL 默认同意态不是关闭的（fail-closed 被破坏）");
            } else {
                lines.add("OK  默认同意态为关闭（fail-closed）");
            }
        } catch (RuntimeException ex) {
            ok = false;
            lines.add("FAIL 同意门构造失败：" + ex.getMessage());
        }

        String integrity = eventStreamIntegrity(host, runtime, lines);
        lines.add("—— 事件流完整性：" + integrity);
        if (integrity.startsWith("FAIL")) {
            ok = false;
        }
        lines.add("—— 已接线事件类型：" + new TreeSet<>(namesOf(ObservationAdapter.wiredTypes())));
        lines.add("—— 未接线事件类型（本阶段无采集来源，如实登记）："
                + new TreeSet<>(namesOf(ObservationAdapter.unwiredTypes())));
        lines.add("—— 自检只证明『引擎随 jar 可运行』，不证明『真实存档里采到了数据』；");
        lines.add("   后者需真实游玩后看 /octant status 的计数。");
        return new Result(ok, lines);
    }

    private static boolean structuralCheck(String text, HtmlReport.Result html, List<String> lines) {
        boolean ok = true;
        int h2 = countOf(text, "<h2");
        if (h2 < 6) {
            ok = false;
            lines.add("FAIL 报告应有 ≥6 个 <h2>（正文四节 + 隐私条款 + 机器附录），实测 " + h2);
        } else {
            lines.add("OK  报告结构：<h2> = " + h2);
        }
        int figs = countOf(text, "<figure");
        int noFigs = countOf(text, "data-no-figure");
        if (figs + noFigs < 1) {
            ok = false;
            lines.add("FAIL 渲染器对图没有任何交代（<figure> = 0 且 data-no-figure = 0）");
        } else {
            lines.add("OK  图有交代：<figure> = " + figs + "、带原因码 no-figure = " + noFigs);
        }
        int styles = countOf(text, "<style");
        if (styles != 1) {
            ok = false;
            lines.add("FAIL <style> 必须恰有 1 个（单文件、样式内联），实测 " + styles);
        }
        for (String bad : new String[] {"src=\"http", "href=\"http", "src='http", "href='http"}) {
            if (text.contains(bad)) {
                ok = false;
                lines.add("FAIL 报告出现远程外链引用：" + bad);
            }
        }
        if (html.fontSubsetBytes() <= 0) {
            ok = false;
            lines.add("FAIL 字体子集为空（字形可能整体缺字）");
        }
        if (html.glyphCount() <= 0) {
            ok = false;
            lines.add("FAIL 字形数为 0（字体资产没被真正用上）");
        }
        return ok;
    }

    private static String eventStreamIntegrity(OctantHost host, CaptureRuntime runtime,
                                              List<String> lines) {
        try {
            Path raw = com.octant.common.privacy.OctantPaths.dataDir(host.worldDir())
                    .resolve("events").resolve("raw");
            if (!Files.isDirectory(raw)) {
                return "未判定（该存档还没有事件目录）";
            }
            int files = 0;
            int events = 0;
            int gaps = 0;
            int orphanEnds = 0;
            int openStarts = 0;
            int curGaps = 0;
            String curFile = null;
            String curSessionId = null;
            try {
                Object v = runtime == null ? null : runtime.statusMap().get("currentSessionId");
                curSessionId = v == null ? null : String.valueOf(v);
            } catch (RuntimeException ignore) {
                curSessionId = null;
            }
            try (var players = Files.list(raw)) {
                for (Path pd : (Iterable<Path>) players::iterator) {
                    if (!Files.isDirectory(pd)) {
                        continue;
                    }
                    try (var fs = Files.list(pd)) {
                        for (Path f : (Iterable<Path>) fs::iterator) {
                            if (!f.getFileName().toString().endsWith(".jsonl")) {
                                continue;
                            }
                            files++;
                            int maxSeq = 0;
                            java.util.Set<Integer> seen = new java.util.TreeSet<>();
                            java.util.Set<String> opened = new java.util.LinkedHashSet<>();
                            for (String line : Files.readAllLines(f)) {
                                events++;
                                java.util.regex.Matcher m = java.util.regex.Pattern
                                        .compile("\"eventId\":\"e(\\d{3})\"").matcher(line);
                                if (m.find()) {
                                    int seq = Integer.parseInt(m.group(1));
                                    seen.add(seq);
                                    maxSeq = Math.max(maxSeq, seq);
                                }
                                java.util.regex.Matcher t = java.util.regex.Pattern
                                        .compile("\"type\":\"(combat_started|combat_ended)\"").matcher(line);
                                if (t.find()) {
                                    java.util.regex.Matcher k = java.util.regex.Pattern
                                            .compile("\"opponentKey\":\"([^\"]+)\"").matcher(line);
                                    String key = k.find() ? k.group(1) : "?";
                                    if ("combat_started".equals(t.group(1))) {
                                        opened.add(key);
                                    } else if (!opened.remove(key)) {
                                        orphanEnds++;
                                    }
                                }
                            }
                            int fileGaps = 0;
                            for (int i = 1; i <= maxSeq; i++) {
                                if (!seen.contains(i)) {
                                    fileGaps++;
                                }
                            }
                            gaps += fileGaps;
                            if (curSessionId != null
                                    && f.getFileName().toString().equals(curSessionId + ".jsonl")) {
                                curGaps += fileGaps;
                                curFile = f.getFileName().toString();
                            }
                            openStarts += opened.size();
                        }
                    }
                }
            }
            if (files == 0 || events == 0) {
                return "未判定（读不到事件）";
            }

            long counted = 0L;
            String detail = "（计数不可用）";
            try {
                Map<String, Object> st = runtime == null ? Map.of() : runtime.statusMap();
                long byConsent = num(st.get("droppedByConsent"));
                long trans = num(st.get("translationFailures"));
                long refused = num(st.get("writeRefused"));
                long failed = num(st.get("writeFailed"));
                counted = byConsent + trans + refused + failed;
                detail = "翻译失败 " + trans + " + 同意门 " + byConsent + " + 写盘被拒 " + refused
                        + " + 写盘失败 " + failed + " = " + counted;
                if (num(st.get("stampRaised")) > 0) {
                    detail += "；另有 " + num(st.get("stampRaised"))
                            + " 条时间戳被单调化抬高（**不丢事件**，但那条的时刻已不是真实时刻）";
                }
                Object lwf = st.get("lastWriteFailure");
                if (lwf != null) {
                    lines.add("提示 最近一次写盘失败原因：" + lwf);
                }
            } catch (RuntimeException ignore) {
                counted = -1L;
            }
            if (runtime == null) {
                counted = -1L;
                detail = "（自检未拿到采集运行时，无法对账）";
            }

            String head = "文件 " + files + " 个、事件 " + events + " 条、缺号 " + gaps
                    + "、无配对 ended " + orphanEnds + "、未闭合 start " + openStarts;

            if (curFile != null && curGaps > 0 && counted >= 0) {
                if (curGaps > counted) {
                    lines.add("FAIL 本次会话（" + curFile + "）缺号 " + curGaps + " 个，计数只解释了 "
                            + counted + " 个 ⇒ 有 " + (curGaps - counted)
                            + " 个缺口**没有任何计数器认领**（真丢）；对账：" + detail);
                    return "FAIL：" + head + "；本次会话缺号 " + curGaps + " / 已解释 " + counted;
                }
                return "OK（" + head + "；本次会话缺号 " + curGaps + " 个，全部已解释：" + detail + "）";
            }
            if (orphanEnds > 0 && curFile != null) {
                int curOrphans = 0;
                Path f = null;
                try (var players = Files.list(raw)) {
                    for (Path pd : (Iterable<Path>) players::iterator) {
                        Path cand = pd.resolve(curFile);
                        if (Files.isRegularFile(cand)) {
                            f = cand;
                            break;
                        }
                    }
                } catch (Exception ignore) {
                    f = null;
                }
                if (f != null) {
                    java.util.Set<String> opened = new java.util.LinkedHashSet<>();
                    for (String line : Files.readAllLines(f)) {
                        java.util.regex.Matcher t = java.util.regex.Pattern
                                .compile("\"type\":\"(combat_started|combat_ended)\"").matcher(line);
                        if (!t.find()) {
                            continue;
                        }
                        java.util.regex.Matcher k = java.util.regex.Pattern
                                .compile("\"opponentKey\":\"([^\"]+)\"").matcher(line);
                        String key = k.find() ? k.group(1) : "?";
                        if ("combat_started".equals(t.group(1))) {
                            opened.add(key);
                        } else if (!opened.remove(key)) {
                            curOrphans++;
                        }
                    }
                    if (curOrphans > 0) {
                        lines.add("FAIL 本次会话有 " + curOrphans + " 条 combat_ended 找不到配对的 "
                                + "combat_started ⇒ 区间对不完整，M8 系列会漏计这些交战");
                        return "FAIL：" + head + "；本次会话无配对 ended " + curOrphans;
                    }
                }
            }
            String note = gaps > 0
                    ? "（缺号 " + gaps + " 个来自**历史文件**：计数是进程内的、不跨进程 ⇒ 判不了，"
                            + "**既不算通过也不算失败**；本次会话缺号 " + (curFile == null ? "未读到活动会话" : curGaps + " 个")
                            + "）"
                    : "";
            return "OK（" + head + "）" + note;
        } catch (Exception ex) {
            return "未判定（读取失败：" + ex.getClass().getSimpleName() + " " + ex.getMessage() + "）";
        }
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static List<String> namesOf(Set<CaptureEventType> types) {
        List<String> out = new ArrayList<>();
        for (CaptureEventType t : types) {
            out.add(t.eventId());
        }
        return out;
    }

    public static List<CaptureEvent> syntheticEvents() {
        String session = CaptureEvent.sessionId(1);
        String playerKey = "0123456789abcdef";
        long tick = 0L;
        List<CaptureEvent> out = new ArrayList<>();
        out.add(new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(1), session, playerKey,
                CaptureEventType.SESSION_START, 0L, CaptureEvent.tickOf(0L),
                CaptureEventType.SESSION_START.category(), EventSource.FORGE, true, null,
                ordered(java.util.Map.of(
                        "privacyClass", "singleplayer",
                        "gameVersion", "1.20.1",
                        "loader", "forge",
                        "sessionStartDate", "1970-01-01",
                        "cheatsEnabled", Boolean.FALSE))));
        tick += 1200L;
        out.add(new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(2), session, playerKey,
                CaptureEventType.SESSION_HEARTBEAT, tick, CaptureEvent.tickOf(tick),
                CaptureEventType.SESSION_HEARTBEAT.category(), EventSource.FORGE, true, null,
                ordered(java.util.Map.of(
                        "activity", "active", "sinceLastInputMs", 0L, "tickProgress", tick / 50L))));
        tick += 600L;
        out.add(new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(3), session, playerKey,
                CaptureEventType.ADVANCEMENT_GAINED, tick, CaptureEvent.tickOf(tick),
                CaptureEventType.ADVANCEMENT_GAINED.category(), EventSource.FORGE, true, null,
                ordered(java.util.Map.of(
                        "advancementId", "minecraft:story/root",
                        "parentId", "",
                        "isRecipe", Boolean.FALSE,
                        "grantedByCommand", Boolean.FALSE))));
        return out;
    }

    private static Map<String, Object> ordered(Map<String, Object> m) {
        return new java.util.LinkedHashMap<>(m);
    }

    public static String stampNow() {
        return ModExportRunner.stamp(Instant.now());
    }

    public static List<String> pipelineStages() {
        return List.of("capture-envelope", "capture->pipeline-adapter", "analysis", "pdf-render",
                "world-dir-writable", "consent-failclosed");
    }

    public static int readableEventCount(CaptureRuntime rt) {
        int n = 0;
        for (String key : rt.knownPlayerKeys()) {
            n += rt.store().readAll(key).size();
        }
        return n;
    }

    public static Path eventsRoot(CaptureRuntime rt) {
        return rt.store().eventsRoot();
    }

    public static long storageCapBytes() {
        return RawEventSchema.WORLD_STORAGE_CAP_BYTES;
    }

    public static int maxEventBytes() {
        return RawEventSchema.MAX_EVENT_BYTES;
    }

    public static String privacySpecVersion() {
        return ExportPipeline.PRIVACY_SPEC_VERSION;
    }

    public static String reportFormatVersion() {
        return ExportPipeline.REPORT_FORMAT_VERSION;
    }

    public static String truncationSummary(CaptureRuntime rt) {
        return String.valueOf(rt.store().truncation().toJson());
    }

    public static long consentDrops(CaptureRuntime rt) {
        return rt.droppedByConsentCount();
    }

    public static int roundTripCount(Path worldDir) {
        RawEventStore store = new RawEventStore(worldDir);
        int n = 0;
        try {
            Path root = store.eventsRoot();
            if (Files.isDirectory(root)) {
                try (var dirs = Files.list(root)) {
                    for (Path d : (Iterable<Path>) dirs::iterator) {
                        if (Files.isDirectory(d)) {
                            n += store.readAll(d.getFileName().toString()).size();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            return -1;
        }
        return n;
    }
}
