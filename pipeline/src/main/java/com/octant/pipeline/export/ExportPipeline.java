package com.octant.pipeline.export;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.analysis.ContextReplay;
import com.octant.pipeline.analysis.MetricOutcome;
import com.octant.pipeline.json.Json;
import com.octant.pipeline.json.JsonReader;
import com.octant.pipeline.json.JsonWriter;
import com.octant.pipeline.kiteembed.KiteEmbed;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;
import com.octant.pipeline.raw.RawEventSchema;
import com.octant.pipeline.report.ReportDocument;
import com.octant.pipeline.report.html.HtmlReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class ExportPipeline {

    public static final String EXPORT_SCHEMA_VERSION = "export-bundle@1.0.0";

    public static final String PRIVACY_SPEC_VERSION = ExportVersions.SPEC_VERSION_UNVERIFIED;
    public static final String REPORT_FORMAT_VERSION = "HIG-1.2";
    public static final String CONTENT_ORIGIN = "local_only_no_upload";
    public static final String RECIPIENT_NOTICE =
            "本报告由 Octant（卦限）在本地生成，仅包含经脱敏的存档内行为统计；已排除原始事件、玩家名、"
                    + "服务器地址、聊天文本、绝对路径与绝对时间戳。";

    public interface ConsentSource {
        ConsentState read() throws Exception;
    }

    public record ConsentState(boolean collectionEnabled, boolean exportPermitted,
                              boolean acknowledged, List<String> categories) {
        public ConsentState {
            categories = categories == null ? List.of() : List.copyOf(categories);
        }

        public boolean permitsExport() {
            return collectionEnabled && exportPermitted && acknowledged && !categories.isEmpty();
        }
    }

    public record Input(
            AnalysisReport report,
            EventStreamSource events,
            ContentCatalog catalog,
            String modVersion,
            String gameVersion,
            String loader,
            String saltSeed,
            List<String> consentCategories,
            List<String> modsList,

            List<RawEvent> replayEvents) {

        public Input(AnalysisReport report, EventStreamSource events, ContentCatalog catalog,
                     String modVersion, String gameVersion, String loader, String saltSeed,
                     List<String> consentCategories, List<String> modsList) {
            this(report, events, catalog, modVersion, gameVersion, loader, saltSeed,
                    consentCategories, modsList, null);
        }
    }

    public interface EventStreamSource {
        List<RawEvent> events();
    }

    public record Result(
            String exportId,
            Path directory,
            Map<String, Long> fileBytes,
            Map<String, String> fileSha256_16,
            int reportUnits,
            List<String> gateViolations,
            boolean written,
            String failureReason,
            String embedNote) {

        public boolean ok() {
            return written && gateViolations.isEmpty();
        }

        @Deprecated(since = "carrier-html", forRemoval = true)
        public int pdfPages() {
            return -1;
        }
    }

    public record Bundle(
            String exportId,
            Map<String, String> textFiles,
            Map<String, byte[]> binaryFiles,
            Json.JsonObject manifest,
            Json.JsonObject redactionReport,
            List<String> gateViolations,
            EmbedRun embed) {
    }

    public record EmbedRun(String kiteUpstream, String kiteUpstreamSha16, long kiteUpstreamBytes,
                           int kiteUpstreamPoints, int kiteUpstreamUsable, String kiteUpstreamVerdict,
                           String dataSourceNote, Map<String, Object> extraction,
                           List<Map<String, String>> checks, boolean kiteAllPassed, List<String> files) {

        public static EmbedRun none() {
            return new EmbedRun("", "", 0L, 0, 0, "unavailable", "", Map.of(), List.of(), false, List.of());
        }
    }

    private ExportPipeline() {
    }

    public static Bundle build(Input input, String exportId, Instant generatedAt, Long deterministicSeed) {
        return build(input, exportId, generatedAt, deterministicSeed, null, false, null);
    }

    public static Bundle build(Input input, String exportId, Instant generatedAt, Long deterministicSeed,
                               KiteEmbed.KitePage kite, boolean strictKite) {
        return build(input, exportId, generatedAt, deterministicSeed, kite, strictKite, null);
    }

    public static Bundle build(Input input, String exportId, Instant generatedAt, Long deterministicSeed,
                               KiteEmbed.KitePage kite, boolean strictKite, String contentInsightsJson) {
        AnalysisReport report = input.report();
        PrivacyRedactor redactor = PrivacyRedactor.withSalt(input.saltSeed(), input.catalog());

        ReportDocument.ExportMeta meta = new ReportDocument.ExportMeta(
                exportId,
                DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(generatedAt),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC).format(generatedAt),
                input.modVersion(), input.gameVersion(), input.loader(),
                REPORT_FORMAT_VERSION, PRIVACY_SPEC_VERSION,
                input.consentCategories(), defaultPrivacyFlags(), defaultExclusions(),
                RECIPIENT_NOTICE, input.modsList());

        ReportDocument doc = new ReportDocument(report, meta);
        String markdown = doc.markdown();
        List<RawEvent> replaySrc = input.replayEvents() != null
                ? input.replayEvents() : input.events().events();
        String replaySource = input.replayEvents() != null ? "真机采集流" : "存档派生事件";
        Json.JsonObject replayJson = AnalysisJson.replay(
                ContextReplay.replay(replaySrc), replaySource, replaySrc.size());

        HtmlReport.Result htmlResult = new HtmlReport(report, meta, contentInsightsJson,
                JsonWriter.pretty(replayJson)).render();
        byte[] html = htmlResult.html();

        Json.JsonObject analysis = AnalysisJson.toJson(report);
        String snapshotId = snapshotIdOf(analysis);
        analysis.put("analysisSnapshotId", snapshotId);
        analysis.put("generatedAtDateBucket", meta.generatedAtBucket());

        analysis.put("replay", replayJson);

        Json.JsonObject metrics = AnalysisJson.metricsProjection(report);
        metrics.put("analysisSnapshotId", snapshotId);
        Json.JsonObject conclusions = AnalysisJson.conclusionsProjection(report);
        conclusions.put("analysisSnapshotId", snapshotId);

        String anonymized = anonymizedJsonl(input.events().events(), redactor);
        Json.JsonObject redactionReport = redactor.report(exportId);
        String privacyReadme = privacyReadme(input, exportId);

        Map<String, String> textFiles = new LinkedHashMap<>();
        textFiles.put("report.md", markdown);
        textFiles.put("analysis.json", JsonWriter.pretty(analysis));
        textFiles.put("metrics.json", JsonWriter.pretty(metrics));
        textFiles.put("conclusions.json", JsonWriter.pretty(conclusions));
        textFiles.put("events.anonymized.jsonl", anonymized);
        textFiles.put("redaction_report.json", JsonWriter.pretty(redactionReport));
        textFiles.put("PRIVACY-README.txt", privacyReadme);

        textFiles.put("report.html", htmlResult.htmlText());

        EmbedRun embedRun = EmbedRun.none();
        if (kite != null) {
            if (!kite.available() && strictKite) {
                throw new IllegalStateException("上游产物不可用，拒绝并进：" + kite.unavailableReason());
            }
            KiteEmbed.KitePage done = kite.html().isEmpty() ? kite
                    : KiteEmbed.embedOnPage(htmlResult.htmlText(), kite);
            if (done.html().isEmpty()) {
                Map<String, Object> extAbsent = new LinkedHashMap<>();
                Json.JsonObject absent = new Json.JsonObject();
                absent.put("kiteUpstream", kite.upstreamPath());
                absent.put("kiteUpstreamAvailable", Boolean.FALSE);
                absent.put("kiteUpstreamUnavailableReason", kite.unavailableReason());
                absent.put("dataSourceNote", kite.dataSourceNote());
                absent.put("kiteEmbedded", Boolean.FALSE);
                absent.put("kiteEmbedSkippedBecause", "上游产物不可用 ⇒ 不并进气囊块（不得用夹具冒充）");
                textFiles.put("kite.json", JsonWriter.pretty(absent));
                embedRun = new EmbedRun(kite.upstreamPath(), kite.upstreamSha16(), kite.upstreamBytes(),
                        0, 0, kite.upstreamVerdict(), kite.dataSourceNote(), extAbsent, List.of(),
                        false, List.of("kite.json"));
            } else {
                if (!done.allPassed()) {
                    throw new IllegalStateException("风筝并进判据不通过："
                            + (done.embedded() == null ? "（无并进结果）" : done.embedded().checks()));
                }
                textFiles.put("report.html", done.embedded().html());
                for (Map.Entry<String, String> e : done.mergedFiles().entrySet()) {
                    textFiles.put(e.getKey(), e.getValue());
                }
                Map<String, Object> ext = new LinkedHashMap<>(done.embedded().extraction());
                Json.JsonObject run = new Json.JsonObject();
                run.put("kiteUpstream", kite.upstreamPath());
                run.put("kiteUpstreamSha16", kite.upstreamSha16());
                run.put("kiteUpstreamBytes", kite.upstreamBytes());
                run.put("kiteUpstreamPoints", (long) kite.upstreamPoints());
                run.put("kiteUpstreamUsable", (long) kite.upstreamUsable());
                run.put("kiteUpstreamVerdict", kite.upstreamVerdict());
                run.put("kiteUpstreamAvailable", Boolean.TRUE);
                run.put("kiteUpstreamUnavailableReason", "");
                run.put("dataSourceNote", kite.dataSourceNote());
                run.put("shapeHits", (long) kite.shapeHits());
                run.put("kiteEmbedded", Boolean.TRUE);
                run.put("extraction", new Json.JsonObject().putAll(ext));
                run.put("kiteAllPassed", done.allPassed());
                textFiles.put("kite.json", JsonWriter.pretty(run));
                embedRun = new EmbedRun(kite.upstreamPath(), kite.upstreamSha16(), kite.upstreamBytes(),
                        kite.upstreamPoints(), kite.upstreamUsable(), kite.upstreamVerdict(),
                        kite.dataSourceNote(), ext, done.embedded().checks(), done.allPassed(),
                        embeddingFileNames(done.mergedFiles()));
            }
        }

        Map<String, byte[]> binaryFiles = new LinkedHashMap<>();

        redactor.scanArtifact("report.md", markdown);
        redactor.scanArtifact("analysis.json", textFiles.get("analysis.json"));
        redactor.scanArtifact("metrics.json", textFiles.get("metrics.json"));
        redactor.scanArtifact("conclusions.json", textFiles.get("conclusions.json"));
        redactor.scanArtifact("events.anonymized.jsonl", anonymized);
        redactor.scanArtifact("redaction_report.json", textFiles.get("redaction_report.json"));
        redactor.scanArtifact("PRIVACY-README.txt", privacyReadme);
        redactor.scanArtifact("report.html(text)", htmlWithoutEmbeddedPayloads(textFiles.get("report.html")));
        for (Map.Entry<String, String> e : textFiles.entrySet()) {
            if (e.getKey().startsWith("report.html.kite") || "kite.json".equals(e.getKey())) {
                redactor.scanArtifact(e.getKey(), e.getValue());
            }
        }
        for (DecodedPayload payload : htmlDecodedPayloads(textFiles.get("report.html"))) {
            redactor.scanArtifact("report.html(decoded:" + payload.label() + ")",
                    decodedScanProjection(payload));
        }

        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, Long> bytes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : textFiles.entrySet()) {
            byte[] b = e.getValue().getBytes(StandardCharsets.UTF_8);
            hashes.put(e.getKey(), sha256_16(b));
            bytes.put(e.getKey(), (long) b.length);
        }
        for (Map.Entry<String, byte[]> e : binaryFiles.entrySet()) {
            hashes.put(e.getKey(), sha256_16(e.getValue()));
            bytes.put(e.getKey(), (long) e.getValue().length);
        }

        Json.JsonObject manifest = manifest(meta, input, report, snapshotId, bytes, hashes,
                htmlResult);
        String manifestText = JsonWriter.pretty(manifest);
        redactor.scanArtifact("manifest.json", manifestText);
        textFiles.put("manifest.json", manifestText);

        return new Bundle(exportId, ordered(textFiles), ordered(binaryFiles), manifest,
                redactionReport, List.copyOf(redactor.violations()), embedRun);
    }

    private static String kiteEmbedNote(KiteEmbed.KitePage kite) {
        if (kite == null) {
            return null;
        }
        return "上游=" + kite.upstreamPath()
                + " 上游可用=" + kite.available()
                + (kite.available() ? "" : "（原因：" + kite.unavailableReason() + "）")
                + " sha16=" + (kite.upstreamSha16().isEmpty() ? "（无）" : kite.upstreamSha16())
                + " 字节=" + kite.upstreamBytes()
                + " N=" + kite.upstreamPoints()
                + " 三轴齐备=" + kite.upstreamUsable()
                + " 上游OR-1=" + kite.upstreamVerdict()
                + " 形态命中=" + kite.shapeHits();
    }

    private static List<String> embeddingFileNames(Map<String, String> mergedFiles) {
        List<String> names = new ArrayList<>();
        names.add("report.html");
        names.add("kite.json");
        for (String k : mergedFiles.keySet()) {
            names.add(k);
        }
        return List.copyOf(names);
    }

    public static Result write(Input input, Path exportRoot, Instant generatedAt, Long deterministicSeed) {
        return write(input, exportRoot, generatedAt, deterministicSeed, null, null, true);
    }

    public static Result write(Input input, Path exportRoot, Instant generatedAt,
                               Long deterministicSeed, ConsentSource consentSource) {
        return write(input, exportRoot, generatedAt, deterministicSeed, consentSource, null, true);
    }

    public static Result write(Input input, Path exportRoot, Instant generatedAt, Long deterministicSeed,
                               ConsentSource consentSource, Path kiteUpstream) {
        return write(input, exportRoot, generatedAt, deterministicSeed, consentSource, kiteUpstream, true);
    }

    public static Result write(Input input, Path exportRoot, Instant generatedAt, Long deterministicSeed,
                               ConsentSource consentSource, Path kiteUpstream, boolean strictKite) {
        return write(input, exportRoot, generatedAt, deterministicSeed, consentSource, kiteUpstream, strictKite, null);
    }

    public static Result write(Input input, Path exportRoot, Instant generatedAt, Long deterministicSeed,
                               ConsentSource consentSource, Path kiteUpstream, boolean strictKite,
                               String contentInsightsJson) {
        String exportId = exportId(generatedAt, deterministicSeed);
        Path intendedDir = exportRoot.resolve(exportId);

        if (consentSource != null) {
            String refusal = consentRefusal(consentSource);
            if (refusal != null) {
                return new Result(exportId, intendedDir, Map.of(), Map.of(), 0, List.of(), false,
                        "无有效同意状态，导出被拒绝：" + refusal, null);
            }
        }

        KiteEmbed.KitePage kitePage = kiteUpstream == null ? null : KiteEmbed.renderKitePageOrUnavailable(kiteUpstream);
        Bundle bundle;
        try {
            bundle = build(input, exportId, generatedAt, deterministicSeed, kitePage, strictKite, contentInsightsJson);
        } catch (IllegalStateException e) {
            return new Result(exportId, intendedDir, Map.of(), Map.of(), 0, List.of(), false,
                    "并进被拒绝（本次不产出任何文件）：" + e.getMessage(), kiteEmbedNote(kitePage));
        }
        if (!bundle.gateViolations().isEmpty()) {
            return new Result(exportId, intendedDir, Map.of(), Map.of(), reportUnits(bundle),
                    bundle.gateViolations(), false,
                    "隐私门禁失败：本次导出不产出任何文件（T1 命中 " + bundle.gateViolations().size() + " 项）",
                    kiteEmbedNote(kitePage));
        }
        Path dir = intendedDir;
        try {
            Files.createDirectories(dir);
            Map<String, Long> bytes = new LinkedHashMap<>();
            Map<String, String> hashes = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : bundle.textFiles().entrySet()) {
                byte[] data = e.getValue().getBytes(StandardCharsets.UTF_8);
                writeAtomic(dir.resolve(e.getKey()), data);
                bytes.put(e.getKey(), (long) data.length);
                hashes.put(e.getKey(), sha256_16(data));
            }
            for (Map.Entry<String, byte[]> e : bundle.binaryFiles().entrySet()) {
                writeAtomic(dir.resolve(e.getKey()), e.getValue());
                bytes.put(e.getKey(), (long) e.getValue().length);
                hashes.put(e.getKey(), sha256_16(e.getValue()));
            }
            List<String> post = postScan(dir);
            if (!post.isEmpty()) {
                deleteRecursively(dir);
                return new Result(exportId, dir, Map.of(), Map.of(), reportUnits(bundle), post, false,
                        "落盘后自检失败，已删除整个导出目录", kiteEmbedNote(kitePage));
            }
            return new Result(exportId, dir, ordered(bytes), ordered(hashes), reportUnits(bundle),
                    List.of(), true, null, kiteEmbedNote(kitePage));
        } catch (IOException e) {
            deleteRecursively(dir);
            return new Result(exportId, dir, Map.of(), Map.of(), reportUnits(bundle), List.of(), false,
                    "写入失败：" + e.getMessage(), kiteEmbedNote(kitePage));
        }
    }

    private static int reportUnits(Bundle bundle) {
        String html = bundle.textFiles().get("report.html");
        if (html == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<section data-c=\"").matcher(html);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    public static final String REPORT_UNIT_NAME = "chartItems";

    public static String consentRefusal(ConsentSource source) {
        if (source == null) {
            return "CONSENT_SOURCE_MISSING（未提供同意状态来源，无法确认玩家同意）";
        }
        ConsentState state;
        try {
            state = source.read();
        } catch (Exception e) {
            return "CONSENT_READ_FAILED（" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "）";
        }
        if (state == null) {
            return "CONSENT_UNREADABLE（同意状态为空）";
        }
        if (!state.acknowledged()) {
            return "CONSENT_NOT_ACKNOWLEDGED";
        }
        if (!state.collectionEnabled()) {
            return "CONSENT_COLLECTION_DISABLED";
        }
        if (!state.exportPermitted()) {
            return "CONSENT_EXPORT_NOT_PERMITTED";
        }
        if (state.categories().isEmpty()) {
            return "CONSENT_NO_CATEGORY";
        }
        return null;
    }

    private static <V> java.util.Map<String, V> ordered(java.util.Map<String, V> map) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public static String exportId(Instant generatedAt, Long deterministicSeed) {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
                .format(generatedAt);
        String suffix;
        if (deterministicSeed != null) {
            suffix = String.format("%06x", Math.floorMod(deterministicSeed, 0xFFFFFF));
        } else {
            suffix = String.format("%06x", new Random().nextInt(0xFFFFFF));
        }
        return stamp + "-" + suffix;
    }

    private static String anonymizedJsonl(List<RawEvent> events, PrivacyRedactor redactor) {
        StringBuilder sb = new StringBuilder();
        for (RawEvent e : events) {
            sb.append(JsonWriter.compact(redactor.redactEvent(e))).append('\n');
        }
        return sb.toString();
    }

    private static Json.JsonObject manifest(ReportDocument.ExportMeta meta, Input input,
                                            AnalysisReport report, String snapshotId,
                                            Map<String, Long> bytes,
                                            Map<String, String> hashes,
                                            HtmlReport.Result htmlResult) {
        Json.JsonObject m = new Json.JsonObject();
        m.put("schemaVersion", 1L);
        m.put("bundleVersion", EXPORT_SCHEMA_VERSION);
        m.put("analysisSnapshotId", snapshotId);
        m.put("contractVersions", Json.of(
                "rawEventSchema", report.rawEventSchemaVersion(),
                "featureContract", report.featureContractVersion(),
                "analysisOutputModel", report.schemaVersion()));
        m.put("privacySpecVersion", ExportVersions.privacySpecVersion());
        m.put("versionSource", ExportVersions.sourceLabel());
        m.put("metricVersion", report.metricVersion());
        m.put("catalogVersion", report.catalogVersion());
        m.put("reportFormatVersion", meta.reportFormatVersion());
        m.put("exportId", meta.exportId());
        m.put("generatedAtDate", meta.generatedAtDate());
        m.put("generatedAtBucket", meta.generatedAtBucket());
        m.put("generatedBy", "mc-insight-mod/" + meta.modVersion());
        m.put("gameVersion", meta.gameVersion());
        m.put("loader", meta.loader());
        m.put("loaderVersion", "unknown");
        if (input.modsList() != null && !input.modsList().isEmpty()) {
            Json.JsonArray mods = new Json.JsonArray();
            for (String mod : input.modsList()) {
                mods.add(Json.of("modId", mod, "version", "unknown"));
            }
            m.put("modsList", mods);
        }
        m.put("contentOrigin", CONTENT_ORIGIN);
        m.put("reportCarrier", Json.of(
                "format", HtmlReport.FORMAT_ID,
                "file", "report.html",
                "singleFileSelfContained", Boolean.TRUE,
                "encoding", "UTF-8"));
        m.put("reportFont", Json.of(
                "familyName", htmlResult.fontFamily(),
                "provenance", "bundled-ofl-asset",
                "licence", "SIL-OFL-1.1",
                "licenceFilesBundled", Boolean.TRUE,
                "licensedUnderRfnRename", Boolean.TRUE,
                "embeddedAs", "data:font/ttf;base64",
                "subsetBytes", (long) htmlResult.fontSubsetBytes(),
                "subsetSha256", htmlResult.fontSha256(),
                "assetBytesOnDisk", (long) htmlResult.fontFullBytes(),
                "glyphsUsed", (long) htmlResult.glyphCount()));
        Json.JsonArray forms = new Json.JsonArray();
        for (String form : htmlResult.chartForms()) {
            forms.add(form);
        }
        m.put("reportUnits", Json.of(
                "name", REPORT_UNIT_NAME,
                "count", (long) htmlResult.chartsDrawn(),
                "chartForms", forms));
        m.put("sharing", Json.of(
                "medium", "player_manual",
                "uploadedByMod", Boolean.FALSE,
                "networkCallsMade", 0L));
        m.put("consent", Json.of(
                "consentedCategories", meta.consentCategories(),
                "consentAcknowledged", Boolean.TRUE));
        m.put("privacyFlags", meta.privacyFlags());
        m.put("aggregation", Json.of(
                "suppressionApplied", Boolean.TRUE,
                "minPlayersForGroupConclusion",
                com.octant.pipeline.feature.Thresholds.MIN_PLAYERS_FOR_GROUP_CONCLUSION,
                "minSessionsForDistribution",
                com.octant.pipeline.feature.Thresholds.MIN_SESSIONS_FOR_DISTRIBUTION,
                "minSessionsForTrend", com.octant.pipeline.feature.Thresholds.MIN_SESSIONS_FOR_TREND,
                "evaluatedMetrics", (long) report.metrics().size(),
                "suppressedMetrics", (long) report.suppressedMetrics().size(),
                "minRequiredTable", "§5.4"));
        m.put("exclusions", meta.exclusions());
        m.put("recipientNotice", meta.recipientNotice());
        Json.JsonArray files = new Json.JsonArray();
        for (Map.Entry<String, Long> e : new java.util.TreeMap<>(bytes).entrySet()) {
            files.add(Json.of("name", e.getKey(), "bytes", e.getValue(),
                    "sha256_16", hashes.get(e.getKey())));
        }
        m.put("files", files);
        m.put("contributingPlayers", 1L);
        m.put("eventTypeCatalogSize", (long) EventType.values().length);
        m.put("schemaRegisteredTypes", (long) RawEventSchema.registeredTypeCount());
        return m;
    }

    private static List<String> defaultPrivacyFlags() {
        return List.of("pseudonym:per_export", "coords:quantized_512", "timestamps:relative_only");
    }

    private static List<String> defaultExclusions() {
        return List.of("raw_events", "player_names", "player_uuid", "server_address", "chat_text",
                "absolute_paths", "absolute_timestamps", "precise_coordinates");
    }

    private static String privacyReadme(Input input, String exportId) {
        AnalysisReport r = input.report();
        StringBuilder sb = new StringBuilder();
        sb.append("Octant（卦限） / 导出物隐私说明\n");
        sb.append("exportId: ").append(exportId).append('\n');
        sb.append("====================\n\n");
        sb.append("1) 本地生成、零上传：本目录由模组在本地生成，模组不进行任何网络访问"
                + "（manifest.sharing.networkCallsMade = 0）。\n");
        sb.append("2) 自愿导出、手动分享：只有在玩家明确同意后才会生成；分享完全由玩家手动完成。\n");
        sb.append("3) 假名化：玩家标识以每份导出独立的假名（per_export，HMAC-SHA256 截断 64 bit）出现，"
                + "两份导出之间不可链接。\n");
        sb.append("4) 坐标量化：位置仅以 512 格（水平）/ 32 格（垂直）网格的区域键出现，不含精确坐标。\n");
        sb.append("5) 已排除类别：").append(String.join(", ", defaultExclusions())).append("。\n");
        sb.append("6) 样本不足的表达：一律以「已抑制 + 原因码」给出，不使用 0 或均值代填；"
                + "本次共 ").append(r.metrics().size()).append(" 项输出单元，其中 ")
                .append(r.suppressedMetrics().size()).append(" 项已抑制。\n");
        sb.append("7) 再识别提示：与其它信息结合仍可能推断游玩习惯，请在分享前自行评估。\n");
        sb.append('\n');
        sb.append("接收者告知：").append(RECIPIENT_NOTICE).append('\n');
        return sb.toString();
    }

    private static void writeAtomic(Path path, byte[] data) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static List<String> postScan(Path dir) throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : Files.list(dir).sorted().toList()) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            String name = p.getFileName().toString();
            byte[] data = Files.readAllBytes(p);
            String rawText = new String(data, StandardCharsets.UTF_8);
            boolean isHtml = name.endsWith(".html");
            scanForHits(name + (isHtml ? "(text)" : ""),
                    isHtml ? htmlWithoutEmbeddedPayloads(rawText) : rawText, hits);
            if (isHtml) {
                for (DecodedPayload payload : htmlDecodedPayloads(rawText)) {
                    scanForHits(name + "(decoded:" + payload.label() + ")",
                            decodedScanProjection(payload), hits);
                }
            }
        }
        return hits;
    }

    private static void scanForHits(String name, String text, List<String> hits) {
        for (String patternClass : RedactionMatrix.allPatternClasses()) {
            if (RedactionMatrix.firstHit(patternClass, text) >= 0) {
                hits.add("POST_SCAN_T1_HIT: file=" + name + " class=" + patternClass);
            }
        }
        for (String forbidden : RedactionMatrix.FORBIDDEN_FIELDS) {
            if (text.contains("\"" + forbidden + "\"")) {
                hits.add("POST_SCAN_FORBIDDEN_FIELD: file=" + name + " field=" + forbidden);
            }
        }
    }

    public record DecodedPayload(String label, String text) {
    }

    public static String htmlWithoutEmbeddedPayloads(String html) {
        return java.util.regex.Pattern.compile("(data:[a-zA-Z0-9.+/-]+;base64,)[A-Za-z0-9+/=]+")
                .matcher(html).replaceAll("$1<BASE64_PAYLOAD>");
    }

    public static String decodedScanProjection(DecodedPayload payload) {
        String text = payload.text();
        StringBuilder sb = new StringBuilder();
        int window = 64 * 1024;
        if (text.length() <= 2 * window) {
            sb.append(text);
        } else {
            sb.append(text, 0, window).append('\n')
                    .append(text, text.length() - window, text.length()).append('\n');
        }
        sb.append(printableRuns(text)).append('\n');
        return sb.toString();
    }

    private static String printableRuns(String text) {
        StringBuilder out = new StringBuilder();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            int start = i;
            while (i < n) {
                char c = text.charAt(i);
                if (c >= 0x20 && c < 0x7F) {
                    i++;
                } else {
                    break;
                }
            }
            if (i - start >= 16) {
                String run = text.substring(start, i);
                if (seen.add(run)) {
                    out.append(run).append('\n');
                }
            }
            i++;
        }
        return out.toString();
    }

    public static java.util.Map<String, Object> htmlScanCoverage(String html) {
        java.util.List<DecodedPayload> payloads = htmlDecodedPayloads(html);
        String textFace = htmlWithoutEmbeddedPayloads(html);
        long decodedBytes = 0L;
        long projectedBytes = 0L;
        for (DecodedPayload p : payloads) {
            decodedBytes += p.text().length();
            projectedBytes += decodedScanProjection(p).length();
        }
        java.util.Map<String, Object> cov = new java.util.LinkedHashMap<>();
        cov.put("htmlBytes", (long) html.length());
        cov.put("textFaceChars", (long) textFace.length());
        cov.put("decodedPayloadCount", (long) payloads.size());
        cov.put("decodedPayloadBytes", decodedBytes);
        cov.put("decodedPayloadProjectedChars", projectedBytes);
        cov.put("notReachableFromTextFace", Math.max(0L, html.length() - textFace.length()));
        return cov;
    }

    public static List<DecodedPayload> htmlDecodedPayloads(String html) {
        List<DecodedPayload> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("data:([a-zA-Z0-9.+/-]+);base64,([A-Za-z0-9+/=]+)").matcher(html);
        int i = 0;
        while (m.find()) {
            String mime = m.group(1);
            byte[] decoded;
            try {
                decoded = java.util.Base64.getDecoder().decode(m.group(2));
            } catch (IllegalArgumentException e) {
                out.add(new DecodedPayload("undecodable-" + i + "-" + mime, "UNDECODABLE_PAYLOAD"));
                i++;
                continue;
            }
            out.add(new DecodedPayload(mime + "-" + i,
                    new String(decoded, StandardCharsets.ISO_8859_1)));
            i++;
        }
        return out;
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (!Files.exists(dir)) {
                return;
            }
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }

    public static String sha256_16(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("本地 SHA-256 不可用", e);
        }
    }

    public static String snapshotIdOf(Json.JsonObject analysis) {
        return sha256_16(JsonWriter.compact(analysis).getBytes(StandardCharsets.UTF_8));
    }

    public static Json.JsonObject readAnalysis(String json) {
        return JsonReader.parseObject(json);
    }

    public static long suppressedCount(AnalysisReport r) {
        long n = 0;
        for (MetricOutcome m : r.metrics()) {
            if (m.isSuppressedLike()) {
                n++;
            }
        }
        return n;
    }
}
