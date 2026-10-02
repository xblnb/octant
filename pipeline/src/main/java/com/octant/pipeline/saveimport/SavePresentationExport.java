package com.octant.pipeline.saveimport;

import com.octant.pipeline.adapter.CaptureEventAdapter;
import com.octant.pipeline.content.ContentInsights;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.export.FileConsentSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SavePresentationExport {

    public static final String FIXED_GENERATED_AT = "2026-09-26T15:00:00Z";
    public static final long FIXED_SEED = 0x5A17E5L;
    public static final List<String> CONSENT_CATEGORIES =
            List.of("C1", "C2", "C3", "C5", "C6", "C7", "C8");

    private SavePresentationExport() {
    }

    private static List<com.octant.pipeline.raw.RawEvent> readLiveEvents(Path eventsRaw)
            throws Exception {
        if (!java.nio.file.Files.isDirectory(eventsRaw)) {
            System.out.println("[real-save] ⛔ --events-raw 不是目录：" + eventsRaw);
            System.exit(2);
        }
        com.octant.common.session.RawEventStore store =
                new com.octant.common.session.RawEventStore(eventsRaw);
        List<com.octant.common.model.CaptureEvent> cap = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        try (var players = java.nio.file.Files.list(eventsRaw)) {
            players.filter(java.nio.file.Files::isDirectory).forEach(dirs::add);
        }
        dirs.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        for (Path pd : dirs) {
            List<Path> files = new ArrayList<>();
            try (var fs = java.nio.file.Files.list(pd)) {
                fs.filter(f -> f.getFileName().toString().endsWith(".jsonl")).forEach(files::add);
            }
            files.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
            int n = 0;
            for (Path f : files) {
                List<com.octant.common.model.CaptureEvent> one = store.readFile(f);
                cap.addAll(one);
                n += one.size();
            }
            System.out.printf("[real-save] 采集流 %s：%d 个文件 / %d 条事件%n",
                    pd.getFileName(), files.size(), n);
        }
        cap.sort(java.util.Comparator
                .comparing((com.octant.common.model.CaptureEvent e) -> e.sessionId())
                .thenComparing(e -> e.eventId()));
        com.octant.pipeline.adapter.CaptureEventAdapter.Conversion conv =
                com.octant.pipeline.adapter.CaptureEventAdapter.toPipelineTolerant(cap);
        System.out.printf("[real-save] 采集流适配：接受 %d / 拒绝 %d%n",
                conv.acceptedCount(), conv.rejectedCount());
        for (String r : conv.rejections().subList(0, Math.min(8, conv.rejections().size()))) {
            System.out.println("      " + r);
        }
        System.out.printf("[real-save] 采集流可用：%d 条管道事件（**只用于回放那一段**）%n",
                conv.events().size());
        return conv.events();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new TreeMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                opt.put(args[i].substring(2), args[++i]);
            } else if ("--self-consent".equals(args[i])) {
                opt.put("self-consent", "1");
            }
        }
        Path saveDir = required(opt, "save");
        Path outDir = required(opt, "out");
        Path mods = opt.containsKey("mods") ? Path.of(opt.get("mods")) : null;
        Path kjs = opt.containsKey("kubejs") ? Path.of(opt.get("kubejs")) : null;
        Path eventsRaw = opt.containsKey("events-raw")
                ? Path.of(opt.get("events-raw")) : null;
        List<Path> extra = new ArrayList<>();
        for (String v : opt.getOrDefault("version-jar", "").split(";")) {
            if (!v.isBlank()) {
                extra.add(Path.of(v));
            }
        }

        System.out.println("[real-save] 读存档（只读）: " + saveDir);
        SaveReader.SaveSnapshot save = SaveReader.read(saveDir);
        AdvancementCatalog.Catalog denom = AdvancementCatalog.scan(mods, kjs, extra);
        System.out.printf("[real-save] 分母 = %d（命名空间 %d；mod jar %d；额外 jar %d）%n",
                denom.total(), denom.namespaceCount(), denom.jars(), denom.extraJars());

        final SaveCorpus.Conversion derived = SaveCorpus.of(save, denom);
        final List<com.octant.pipeline.raw.RawEvent> liveEvents =
                eventsRaw == null ? null : readLiveEvents(eventsRaw);
        final SaveCorpus.Conversion c = derived;
        System.out.printf("[real-save] 事件 %d 条（原始直方图 %s）%n",
                c.events().size(), SaveCorpus.histogram(c.events()));
        System.out.printf("[real-save] 适配器：接受 %d / 拒绝 %d%n",
                c.conv().acceptedCount(), c.conv().rejectedCount());
        if (c.conv().rejectedCount() > 0) {
            System.out.println("  ⛔ 有事件被拒（数据层问题，不得静默）:");
            for (String r : c.conv().rejections().subList(0, Math.min(5, c.conv().rejections().size()))) {
                System.out.println("      " + r);
            }
            System.exit(2);
        }
        System.out.printf("[real-save] 会话桶 = %s（含义：%s）%n",
                c.timelineFacts().get("sessionBuckets"), c.timelineFacts().get("sessionBucketMeaning"));
        System.out.printf("[real-save] AnalysisReport：抑制项 count=%s%n",
                c.report().suppressionSummary().get("counts"));

        Path consent = Path.of("pipeline/build/real-save-presentation/consent-fixtures/real-save-consent.json");
        Files.createDirectories(consent.getParent());
        Files.writeString(consent, "{\n"
                + "  \"schemaVersion\": \"privacy-consent@2.0.0\",\n"
                + "  \"permit\": {\n"
                + "    \"enabled\": true,\n"
                + "    \"exportEnabled\": true,\n"
                + "    \"acknowledged\": true,\n"
                + "    \"consentedCategories\": [\"C1\",\"C2\",\"C3\",\"C5\",\"C6\",\"C7\",\"C8\"]\n"
                + "  }\n}\n", StandardCharsets.UTF_8);

        ExportPipeline.Input input = new ExportPipeline.Input(
                c.report(), () -> c.conv().events(), c.catalog(), "0.1.0", "1.20.1", "forge",
                "real-save-salt-2026", CONSENT_CATEGORIES, List.of("minecraft"), liveEvents);
        Files.createDirectories(outDir);

        String contentJson = null;
        try {
            Path assets = opt.containsKey("assets") ? Path.of(opt.get("assets")) : null;
            Path assetIndex = opt.containsKey("asset-index") ? Path.of(opt.get("asset-index")) : null;
            Path quests = opt.containsKey("quests") ? Path.of(opt.get("quests")) : null;
            Path xaero = opt.containsKey("xaero") ? Path.of(opt.get("xaero")) : null;
            contentJson = ContentInsights.jsonFor(saveDir, mods, kjs, extra, assets, assetIndex, true,
                    quests, xaero);
            System.out.println("[real-save] 内容层 = 已生成 " + contentJson.length() + " 字符"
                    + (assets == null ? "（未给 --assets ⇒ 原版名字回落为 id）" : "")
                    + (quests == null ? "（未给 --quests ⇒ 无任务书那一节）" : "")
                    + (xaero == null ? "（未给 --xaero ⇒ 无地图标点那一节）" : ""));
        } catch (Exception ex) {
            System.out.println("[real-save] 内容层 = ⛔ 生成失败，本次报告不含该块：" + ex);
        }

        java.nio.file.Path kiteUp = opt.containsKey("kite-upstream")
                ? java.nio.file.Path.of(opt.get("kite-upstream"))
                : com.octant.pipeline.kiteembed.KiteEmbed.defaultUpstreamArtifact();
        System.out.println("[real-save] 风筝页（玩家画像）上游 = " + kiteUp
                + "（存在=" + java.nio.file.Files.isRegularFile(kiteUp) + "）");

        ExportPipeline.Result result = ExportPipeline.write(input, outDir,
                Instant.parse(FIXED_GENERATED_AT), FIXED_SEED, new FileConsentSource(consent),
                null, true, contentJson);

        System.out.println("[real-save] exportId  = " + result.exportId());
        System.out.println("[real-save] directory = " + result.directory().toAbsolutePath());
        System.out.println("[real-save] reportUnits = " + result.reportUnits());
        System.out.println("[real-save] written = " + result.written()
                + "  gateViolations = " + result.gateViolations());
        for (Map.Entry<String, Long> e : new TreeMap<>(result.fileBytes()).entrySet()) {
            System.out.printf("    %-30s %9d bytes  sha256_16=%s%n",
                    e.getKey(), e.getValue(), result.fileSha256_16().get(e.getKey()));
        }
        if (!result.written()) {
            System.out.println("[real-save] ⛔ 未写盘：" + result.failureReason());
            System.exit(4);
        }
        if (!result.gateViolations().isEmpty()) {
            System.out.println("[real-save] ⛔ 门禁不通过：" + result.gateViolations());
            System.exit(4);
        }
        System.out.println("[real-save] 事件类型直方图 = " + SaveCorpus.histogram(c.events()));
        System.out.println("[real-save] 结论：导出成功");
    }

    private static Path required(Map<String, String> opt, String key) {
        String v = opt.get(key);
        if (v == null || v.isBlank()) {
            System.out.println("[real-save] 缺少必要参数 --" + key);
            System.exit(3);
        }
        return Path.of(v);
    }

    public static Map<String, Object> summarize(SaveCorpus.Conversion c) {
        Map<String, Object> m = new TreeMap<>();
        m.put("captureEvents", c.events().size());
        m.put("accepted", c.conv().acceptedCount());
        m.put("rejected", c.conv().rejectedCount());
        m.put("eventTypes", SaveCorpus.histogram(c.events()));
        m.put("stats", c.statsSummary());
        m.put("timeline", c.timelineFacts());
        m.put("notApplicable", c.notApplicable());
        m.put("suppressionCounts", c.report().suppressionSummary().get("counts"));
        m.put("adapter", CaptureEventAdapter.class.getSimpleName());
        return m;
    }
}
