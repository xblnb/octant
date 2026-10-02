package com.octant.pipeline;

import com.octant.common.model.CaptureEvent;
import com.octant.pipeline.adapter.CaptureEventAdapter;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.export.FileConsentSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleExportTest {

    private static final Instant FIXED = Instant.parse("2026-09-26T15:00:00Z");

    private static Path exportRoot() {
        String explicit = System.getProperty("octant.samples.out");
        if (explicit != null && !explicit.isEmpty()) {
            return Path.of(explicit).toAbsolutePath();
        }
        String base = System.getProperty("octant.e2e.out");
        if (base == null || base.isEmpty()) {
            base = Path.of("build", "e2e-out").toAbsolutePath().toString();
        }
        return Path.of(base).resolve("samples");
    }

    @Test
    @DisplayName("真实采集模型驱动的样品包：9 个文件产出，全部事件通过采集层构造期校验")
    void samplePackageFromRealCaptureModel() throws Exception {
        Path root = exportRoot();
        Path samples = root;
        Files.createDirectories(samples);

        if (Files.isDirectory(samples)) {
            try (Stream<Path> walk = Files.walk(samples)) {
                walk.filter(p -> !p.equals(samples))
                        .filter(p -> Files.isDirectory(p) && Files.isRegularFile(p.resolve("manifest.json")))
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try (Stream<Path> inner = Files.walk(p)) {
                                inner.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
                                    try {
                                        Files.deleteIfExists(q);
                                    } catch (Exception ignored) {
                                    }
                                });
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
        Files.createDirectories(samples);

        List<CaptureEvent> captureEvents = RealCaptureCorpus.events();
        assertTrue(captureEvents.size() > 1000, "语料规模异常：" + captureEvents.size());

        CaptureEventAdapter.Conversion conv = RealCaptureCorpus.toPipeline(captureEvents);
        assertEquals(0, conv.rejectedCount(), "真实采集语料不应有被拒事件：" + conv.rejections());
        assertEquals(captureEvents.size(), conv.acceptedCount());

        Path consentDir = Path.of(System.getProperty("octant.e2e.out",
                Path.of("build", "e2e-out").toAbsolutePath().toString())).resolve("consent-fixtures");
        Files.createDirectories(consentDir);
        Path consentFile = consentDir.resolve("samples-consent.json");
        Files.writeString(consentFile, "{\n"
                + "  \"schemaVersion\": \"privacy-consent@2.0.0\",\n"
                + "  \"permit\": {\n"
                + "    \"enabled\": true,\n"
                + "    \"exportEnabled\": true,\n"
                + "    \"acknowledged\": true,\n"
                + "    \"consentedCategories\": [\"C1\",\"C2\",\"C3\",\"C5\",\"C6\",\"C7\",\"C8\"]\n"
                + "  }\n}\n", StandardCharsets.UTF_8);

        ExportPipeline.Input input = RealCaptureCorpus.input();
        ExportPipeline.Result result = ExportPipeline.write(input, samples, FIXED, 0x5A17E5L,
                new FileConsentSource(consentFile));

        System.out.println("== 真实采集模型样品导出取证 ==");
        System.out.println("captureEvents = " + captureEvents.size()
                + "  accepted = " + conv.acceptedCount() + "  rejected = " + conv.rejectedCount());
        System.out.println("exportId     = " + result.exportId());
        System.out.println("directory    = " + result.directory().toAbsolutePath());
        System.out.println("written      = " + result.written() + "  pdfPages = " + result.pdfPages());
        System.out.println("versions     = " + result.fileBytes().size() + " 个文件");
        for (var e : new java.util.TreeMap<>(result.fileBytes()).entrySet()) {
            System.out.println(String.format("  %-28s %8d bytes  sha256_16=%s", e.getKey(), e.getValue(),
                    result.fileSha256_16().get(e.getKey())));
            assertEquals(e.getValue().longValue(),
                    Files.size(result.directory().resolve(e.getKey())), "字节数不一致：" + e.getKey());
        }

        assertTrue(result.written(), "样品导出未落盘：" + result.failureReason());
        assertTrue(result.gateViolations().isEmpty(), "样品触发门禁：" + result.gateViolations());
        assertEquals(9, result.fileBytes().size());

        String manifest = Files.readString(result.directory().resolve("manifest.json"),
                StandardCharsets.UTF_8);
        assertNotNull(manifest);
        String snap = com.octant.pipeline.json.JsonReader
                .parseObject(manifest).str("analysisSnapshotId");
        for (String name : List.of("metrics.json", "conclusions.json", "analysis.json")) {
            assertEquals(snap, com.octant.pipeline.json.JsonReader
                            .parseObject(Files.readString(result.directory().resolve(name),
                                    StandardCharsets.UTF_8)).str("analysisSnapshotId"),
                    name + " 与分析快照号不同源");
        }
        System.out.println("== 取证结束（samples/ 下 1 个真实采集模型样品包）==");
    }
}
