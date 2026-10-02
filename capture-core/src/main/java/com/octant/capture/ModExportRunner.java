package com.octant.capture;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.pipeline.adapter.CaptureEventAdapter;
import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;
import com.octant.pipeline.raw.UnitKind;
import com.octant.pipeline.saveimport.SaveCorpus;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ModExportRunner {

    private ModExportRunner() {
    }

    public record Summary(
            String exportId,
            Path directory,
            Map<String, Long> fileBytes,
            Map<String, String> fileSha256_16,
            int pdfPages,
            boolean ok,
            String failureReason,
            List<String> gateViolations,
            int captureEventCount,
            int acceptedEventCount,
            int rejectedEventCount,
            int sessionCount) {

        public long totalBytes() {
            return fileBytes.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    public static Summary run(CaptureRuntime runtime, OctantHost host, Instant now) {
        return run(runtime, host, now, msg -> { });
    }

    public static Summary run(CaptureRuntime runtime, OctantHost host, Instant now,
                              java.util.function.Consumer<String> progress) {
        runtime.consent().reload();
        if (!runtime.consent().exportPermitted()) {
            return new Summary("", com.octant.common.privacy.OctantPaths.dataDir(host.gameDir()).resolve("exports"), Map.of(),
                    Map.of(), 0, false,
                    "同意门关闭：采集或导出未获授予（fail-closed，未创建任何目录）",
                    List.of(), 0, 0, 0, 0);
        }

        Path exportRoot = com.octant.common.privacy.OctantPaths.dataDir(host.gameDir()).resolve("exports");
        Path consentFile = runtime.consent().privacyFile();

        List<CaptureEvent> captured = new ArrayList<>();
        for (String playerKey : runtime.knownPlayerKeys()) {
            captured.addAll(runtime.store().readAll(playerKey));
        }

        CaptureEventAdapter.Conversion conv = CaptureEventAdapter.toPipelineTolerant(captured);
        List<RawEvent> liveEvents = conv.events();

        EventSource src;
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "octant-derive-events");
            th.setDaemon(true);
            return th;
        });
        try {
            src = ex.submit(() -> deriveEvents(host, liveEvents))
                    .get(90L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            List<RawEvent> onlyLive = List.copyOf(liveEvents);
            ContentCatalog lc = catalogOf(onlyLive);
            src = new EventSource(onlyLive, lc, AnalysisReport.analyze(onlyLive, lc, host.loader()),
                    "⛔ 存档派生超时/失败（上限 90 s）⇒ 本次报告**只含实时事件**（" + onlyLive.size()
                            + " 条）⇒ 图表与指标会明显偏少：" + t);
        } finally {
            ex.shutdownNow();
        }
        final List<RawEvent> pipelineEvents = src.events();
        ContentCatalog catalog = src.catalog();
        AnalysisReport report = src.report();
        int sessionCount = report.window() == null ? 0 : report.window().sessionIds().size();
        if (!src.note().isEmpty()) {
            java.util.logging.Logger.getLogger("octant")
                    .info("[Octant（卦限）] " + src.note());
        }

        ExportPipeline.Input input = new ExportPipeline.Input(
                report,
                () -> pipelineEvents,
                catalog,
                host.modVersion(),
                host.gameVersion(),
                host.loader(),
                runtime.saltSeedForRedaction(),
                runtime.consent().consentedCategories(),
                host.modsList());

        Long seed = (long) pipelineEvents.size();

        progress.accept("步骤④：正在读取模组内容与配方（这一步最慢，可能需要几分钟）…");
        String contentJson = null;
        try {
java.util.logging.Logger.getLogger("octant").info("[Octant（卦限）] 步骤③-1：列实例 jar…");
            java.nio.file.Path gameDir = host.gameDir();
            java.nio.file.Path worldDir = host.worldDir();
            if (worldDir != null) {
                java.util.List<String> a = new java.util.ArrayList<>(java.util.List.of(
                        "--save", worldDir.toString(), "--json-only",
                        "--mods", gameDir.resolve("mods").toString(),
                        "--kubejs", gameDir.resolve("kubejs").resolve("data").toString(),
                        "--with-recipes"));
                java.nio.file.Path quests = gameDir.resolve("config").resolve("ftbquests")
                        .resolve("quests").resolve("chapters");
                if (java.nio.file.Files.isDirectory(quests)) {
                    a.add("--quests");
                    a.add(quests.toString());
                }
                java.nio.file.Path xaero = gameDir.resolve("xaero");
                if (java.nio.file.Files.isDirectory(xaero)) {
                    a.add("--xaero");
                    a.add(xaero.toString());
                }
                contentJson = com.octant.pipeline.content.ContentInsights.jsonForArgs(a.toArray(new String[0]));
                java.util.logging.Logger.getLogger("octant")
                        .info("[Octant（卦限）] 内容层已生成：" + contentJson.length() + " 字符");
            }
        } catch (Throwable e) {
            java.util.logging.Logger.getLogger("octant")
                    .warning("[Octant（卦限）] 内容层不可用（本次报告不含该块）：" + e);
            progress.accept("⚠ 内容层不可用（" + e.getClass().getSimpleName()
                    + "）⇒ 本次报告只有机器层，四节游戏内容缺失；原因已记入产物目录。");
        }

        progress.accept("步骤⑤：开始生成报告文件（HTML / PDF / 机器可读）…");
        ExportPipeline.Result result = ExportPipeline.write(
                input, exportRoot, now, seed, new ConsentFileSource(consentFile),
                null, true, contentJson);

        return new Summary(
                result.exportId(),
                result.directory(),
                result.fileBytes(),
                result.fileSha256_16(),
                result.reportUnits(),
                result.ok(),
                result.failureReason(),
                result.gateViolations(),
                captured.size(),
                conv.acceptedCount(),
                conv.rejectedCount(),
                sessionCount);
    }

    private record EventSource(List<RawEvent> events, ContentCatalog catalog,
                               AnalysisReport report, String note) {
    }

    private static EventSource deriveEvents(OctantHost host, List<RawEvent> liveEvents) {
        ContentCatalog liveCatalog = catalogOf(liveEvents);
        AnalysisReport liveReport = AnalysisReport.analyze(liveEvents, liveCatalog, host.loader());
        try {
            Path instanceDir = host.gameDir();
            List<Path> extraJars = new ArrayList<>();
            try (var s = java.nio.file.Files.list(instanceDir)) {
                s.filter(java.nio.file.Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .forEach(extraJars::add);
            }
java.util.logging.Logger.getLogger("octant").info("[Octant（卦限）] 步骤③-2：开始扫内容目录 + 读存档（本包 mods jar 越多越慢）…");
                        SaveCorpus.Conversion corpus = SaveCorpus.of(host.worldDir(), instanceDir.resolve("mods"),
                    instanceDir.resolve("kubejs").resolve("data"), extraJars);
            List<RawEvent> liveExtra = new ArrayList<>();
            for (RawEvent e : liveEvents) {
                if (e.type() != EventType.ADVANCEMENT_GAINED) {
                    liveExtra.add(e);
                }
            }
            List<RawEvent> merged = new ArrayList<>(corpus.conv().events());
            merged.addAll(liveExtra);
            merged.sort(java.util.Comparator.comparing(RawEvent::tRelMs));
            ContentCatalog catalog = mergeCatalogs(corpus.catalog(), catalogOf(merged));
            AnalysisReport report = AnalysisReport.analyze(merged, catalog, host.loader());
            return new EventSource(List.copyOf(merged), catalog, report,
                    "事件来源：存档派生 " + corpus.conv().events().size() + " 条 + 实时补充 "
                            + liveExtra.size() + " 条 = " + merged.size() + " 条；额外 jar "
                            + extraJars.size() + " 个计入分母");
        } catch (Throwable t) {
            return new EventSource(List.copyOf(liveEvents), liveCatalog, liveReport,
                    "⛔ 存档派生事件不可用，本次报告**只含实时事件**（" + liveEvents.size()
                            + " 条）⇒ 图表与指标会明显偏少：" + t);
        }
    }

    private static ContentCatalog mergeCatalogs(ContentCatalog base, ContentCatalog extra) {
        ContentCatalog out = new ContentCatalog(base.version());
        for (UnitKind k : UnitKind.values()) {
            Set<String> ids = new LinkedHashSet<>();
            boolean approx = false;
            boolean any = false;
            if (base.hasKind(k)) {
                ids.addAll(base.keysOf(k));
                approx = base.isApproximate(k);
                any = true;
            }
            if (extra.hasKind(k)) {
                ids.addAll(extra.keysOf(k));
                approx = approx || extra.isApproximate(k);
                any = true;
            }
            if (any) {
                out.register(k, List.copyOf(ids), approx);
            }
        }
        return out;
    }

    private static ContentCatalog catalogOf(List<RawEvent> events) {
        Set<String> advancements = new LinkedHashSet<>();
        Set<String> quests = new LinkedHashSet<>();
        Set<String> biomes = new LinkedHashSet<>();
        Set<String> dimensions = new LinkedHashSet<>();
        for (RawEvent e : events) {
            Map<String, Object> p = e.payload();
            if (p == null) {
                continue;
            }
            Object a = p.get("advancementId");
            if (a instanceof String s && !s.isBlank()) {
                advancements.add(s);
            }
            Object q = p.get("questId");
            if (q instanceof String s && !s.isBlank()) {
                quests.add(s);
            }
            Object b = p.get("biome");
            if (b instanceof String s && !s.isBlank()) {
                biomes.add(s);
            }
            Object d = p.get("dimension");
            if (d instanceof String s && !s.isBlank()) {
                dimensions.add(s);
            }
        }
        ContentCatalog c = new ContentCatalog("observed-" + events.size());
        c.register(UnitKind.ADVANCEMENT, List.copyOf(advancements), true);
        c.register(UnitKind.QUEST, List.copyOf(quests), true);
        c.register(UnitKind.BIOME, List.copyOf(biomes), true);
        c.register(UnitKind.DIMENSION, List.copyOf(dimensions), true);
        return c;
    }

    private static final class ConsentFileSource implements ExportPipeline.ConsentSource {
        private final Path file;

        ConsentFileSource(Path file) {
            this.file = file;
        }

        @Override
        public ExportPipeline.ConsentState read() throws Exception {
            if (file == null) {
                throw new java.io.IOException("同意状态文件路径未提供");
            }
            if (!java.nio.file.Files.isRegularFile(file)) {
                throw new java.io.IOException("同意状态文件不存在：" + file);
            }
            String text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            if (text.isBlank()) {
                throw new IllegalStateException("同意状态文件为空（视为解析失败）：" + file);
            }
            var root = com.octant.pipeline.json.JsonReader.parseObject(text);
            Object permitRaw = root.get(PrivacyConsent.PERMIT_BLOCK);
            if (!(permitRaw instanceof com.octant.pipeline.json.Json.JsonObject permit)) {
                throw new IllegalArgumentException(
                        "同意状态缺少 " + PrivacyConsent.PERMIT_BLOCK + " 块（拒绝按其它形状猜）");
            }
            String permitSchema = permit.str("schemaVersion");
            if (permitSchema == null || !PrivacyConsent.PERMIT_SCHEMA_VERSION.equals(permitSchema)) {
                throw new IllegalArgumentException("permit 块的可识别版本不匹配："
                        + permitSchema + "（期望 " + PrivacyConsent.PERMIT_SCHEMA_VERSION + "）");
            }
            boolean enabled = requireBool(permit, "enabled");
            boolean exportEnabled = requireBool(permit, "exportEnabled");
            boolean acknowledged = requireBool(permit, "acknowledged");
            List<String> categories = new ArrayList<>();
            if (permit.get("consentedCategories")
                    instanceof com.octant.pipeline.json.Json.JsonArray arr) {
                for (Object item : arr.items()) {
                    if (item instanceof String s) {
                        categories.add(s);
                    }
                }
            }
            return new ExportPipeline.ConsentState(enabled, exportEnabled, acknowledged, categories);
        }

        private static boolean requireBool(com.octant.pipeline.json.Json.JsonObject o, String key) {
            Object v = o.get(key);
            if (!(v instanceof Boolean b)) {
                throw new IllegalArgumentException("permit 块缺少布尔字段 " + key
                        + "（实际 " + (v == null ? "缺失" : v.getClass().getSimpleName()) + "）");
            }
            return b;
        }
    }

    public static List<String> describeFiles(Summary s) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Long> e : new LinkedHashMap<>(s.fileBytes()).entrySet()) {
            lines.add(String.format("  %-30s %9d B  sha256_16=%s",
                    e.getKey(), e.getValue(), s.fileSha256_16().get(e.getKey())));
        }
        return lines;
    }

    public static String stamp(Instant t) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'")
                .withZone(ZoneOffset.UTC)
                .format(t);
    }

    public static Map<String, Integer> histogram(List<RawEvent> events) {
        Map<String, Integer> h = new LinkedHashMap<>();
        for (RawEvent e : events) {
            h.merge(String.valueOf(e.type()), 1, Integer::sum);
        }
        return h;
    }

    public static Set<CaptureEventType> observedTypes(List<CaptureEvent> events) {
        Set<CaptureEventType> s = new LinkedHashSet<>();
        for (CaptureEvent e : events) {
            s.add(e.type());
        }
        return s;
    }
}
