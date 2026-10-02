package com.octant.pipeline;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.export.ExportVersions;
import com.octant.pipeline.export.FileConsentSource;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailClosedConsentTest {

    private static final Instant FIXED = Instant.parse("2026-09-26T14:20:00Z");
    private static final String CONSENT_SCHEMA = "privacy-consent@2.0.0";

    private static Path exportRoot() {
        String base = System.getProperty("octant.e2e.out");
        if (base == null || base.isEmpty()) {
            base = Path.of("build", "e2e-out").toAbsolutePath().toString();
        }
        return Path.of(base);
    }

    private static String validConsentJson(boolean enabled, boolean exportEnabled,
                                           boolean acknowledged, List<String> categories) {
        StringBuilder cats = new StringBuilder();
        for (int i = 0; i < categories.size(); i++) {
            cats.append(i == 0 ? "" : ", ").append('"').append(categories.get(i)).append('"');
        }
        return "{\n"
                + "  \"schemaVersion\": \"" + CONSENT_SCHEMA + "\",\n"
                + "  \"permit\": {\n"
                + "    \"enabled\": " + enabled + ",\n"
                + "    \"exportEnabled\": " + exportEnabled + ",\n"
                + "    \"acknowledged\": " + acknowledged + ",\n"
                + "    \"consentedCategories\": [" + cats + "]\n"
                + "  }\n"
                + "}\n";
    }

    private static Map<String, String> buildScenarios(Path consentDir) throws Exception {
        Files.createDirectories(consentDir);
        Map<String, String> expect = new java.util.LinkedHashMap<>();

        expect.put("missing-consent", "CONSENT_READ_FAILED");

        Files.writeString(consentDir.resolve("corrupt-consent.json"),
                "{ \"schemaVersion\": \"privacy-consent@2.0.0\", \"permit\": { \"enabled\": tr", StandardCharsets.UTF_8);
        expect.put("corrupt-consent", "CONSENT_READ_FAILED");

        Files.writeString(consentDir.resolve("unknown-schema.json"),
                validConsentJson(true, true, true, List.of("C1")).replace(CONSENT_SCHEMA,
                        "privacy-consent@9.9.9"), StandardCharsets.UTF_8);
        expect.put("unknown-schema", "CONSENT_READ_FAILED");

        Files.writeString(consentDir.resolve("consent-disabled.json"),
                validConsentJson(false, false, true, List.of("C1", "C2")), StandardCharsets.UTF_8);
        expect.put("consent-disabled", "CONSENT_COLLECTION_DISABLED");

        Files.writeString(consentDir.resolve("consent-acknowledged.json"),
                validConsentJson(true, true, false, List.of("C1")), StandardCharsets.UTF_8);
        expect.put("consent-acknowledged", "CONSENT_NOT_ACKNOWLEDGED");

        Files.writeString(consentDir.resolve("consent-no-category.json"),
                validConsentJson(true, true, true, List.of()), StandardCharsets.UTF_8);
        expect.put("consent-no-category", "CONSENT_NO_CATEGORY");

        Files.writeString(consentDir.resolve("consent-export-off.json"),
                validConsentJson(true, false, true, List.of("C1")), StandardCharsets.UTF_8);
        expect.put("consent-export-off", "CONSENT_EXPORT_NOT_PERMITTED");

        Files.writeString(consentDir.resolve("consent-malformed.json"),
                "{\n  \"schemaVersion\": \"" + CONSENT_SCHEMA + "\",\n  \"permit\": { \"enabled\": true }\n}\n",
                StandardCharsets.UTF_8);
        expect.put("consent-malformed", "CONSENT_READ_FAILED");

        return expect;
    }

    @Test
    @DisplayName("8 类同意故障场景（真实读盘）：导出全部被拒，且负向目录内零文件（EX-16 判定形态）")
    void consentFailuresRefuseExportAndLeaveNoFiles() throws Exception {
        Path root = exportRoot();
        Files.createDirectories(root);
        Path negativeRoot = root.resolve("negative");
        Path consentDir = root.resolve("consent-fixtures");

        Map<String, String> expect = buildScenarios(consentDir);

        if (Files.isDirectory(negativeRoot)) {
            try (Stream<Path> walk = Files.walk(negativeRoot)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        Files.createDirectories(negativeRoot);

        System.out.println("== fail-closed 同意门禁取证（真实入口 + 真实读盘）==");
        for (Map.Entry<String, String> e : expect.entrySet()) {
            String scenario = e.getKey();
            Path scenarioDir = negativeRoot.resolve(scenario);
            Files.createDirectories(scenarioDir);
            try (Stream<Path> s = Files.list(scenarioDir)) {
                assertEquals(0L, s.count(), "场景目录前置条件不成立（应为空）：" + scenarioDir);
            }

            ExportPipeline.ConsentSource source =
                    new FileConsentSource(consentDir.resolve(scenario + ".json"));

            ExportPipeline.Result r = ExportPipeline.write(EndToEndExportTest.input(), scenarioDir,
                    FIXED, 100L + scenario.hashCode(), source);

            assertFalse(r.written(), scenario + "：同意无效时不得落盘");
            assertTrue(r.fileBytes().isEmpty(), scenario + "：不得产出任何文件");
            assertNotNull(r.failureReason(), scenario + "：必须给出拒绝理由");
            assertTrue(r.failureReason().contains("无有效同意状态"),
                    scenario + "：拒绝理由应说明是同意门禁拦下，实际：" + r.failureReason());
            assertTrue(r.failureReason().contains(e.getValue()),
                    scenario + "：期望原因 " + e.getValue() + "，实际 " + r.failureReason());

            try (Stream<Path> s = Files.walk(scenarioDir)) {
                assertEquals(0L, s.filter(Files::isRegularFile).count(),
                        scenario + "：目录内不得残留任何文件");
            }
            System.out.println(String.format("  %-22s refused=%-5s files=0  reason=%s",
                    scenario, !r.written(), r.failureReason()));
        }
        System.out.println("== 取证结束（negative/ 下 " + expect.size() + " 个场景目录均为空）==");
    }

    @Test
    @DisplayName("对照组：磁盘上的合法同意文件 → 正常产出 9 个文件（防止 fail-closed 退化为 fail-always）")
    void validConsentFileAllowsExport() throws Exception {
        Path root = exportRoot();
        Path consentDir = root.resolve("consent-fixtures");
        Files.createDirectories(consentDir);
        Path consentFile = consentDir.resolve("consent-valid.json");
        Files.writeString(consentFile,
                validConsentJson(true, true, true, List.of("C1", "C2", "C3", "C5", "C6", "C7", "C8")),
                StandardCharsets.UTF_8);

        ExportPipeline.Result r = ExportPipeline.write(EndToEndExportTest.input(),
                root.resolve("negative-control"), FIXED, 999L, new FileConsentSource(consentFile));
        assertTrue(r.written(), "同意有效时必须正常导出：" + r.failureReason());
        assertEquals(9, r.fileBytes().size(), "应产出 9 个文件");
        assertTrue(r.gateViolations().isEmpty());
        System.out.println("对照组取证：written=" + r.written() + " files=" + r.fileBytes().size()
                + " dir=" + r.directory().toAbsolutePath());
    }

    @Test
    @DisplayName("兼容路径（未传同意来源）行为不变，仍走 T1 门禁")
    void nullConsentSourceKeepsLegacyBehaviour() {
        ExportPipeline.Result r = ExportPipeline.write(EndToEndExportTest.input(),
                exportRoot().resolve("legacy-path"), FIXED, 777L, null);
        assertTrue(r.written(), "兼容路径应正常导出");
    }

    @Test
    @DisplayName("隐私规范版本来自规范侧机器可读来源，而不是源码里写死的常量")
    void privacySpecVersionFollowsSpecSource() throws Exception {
        String resolved = ExportVersions.privacySpecVersion();
        Path file = Path.of(System.getProperty(ExportVersions.SPEC_VERSION_FILE_PROPERTY,
                "docs/privacy/SPEC-VERSION.txt"));
        assertTrue(Files.isReadable(file),
                "规范版本文件应存在（SPEC-VERSION.txt）：" + file.toAbsolutePath());
        String expected = Files.readString(file, StandardCharsets.UTF_8).trim();
        assertEquals(expected, resolved,
                "导出物声明的规范版本必须等于规范侧当前版本（否则收件人会按旧规则理解产物）");
        System.out.println("规范版本解析取证：resolved=" + resolved + " source=" + file.toAbsolutePath()
                + " versionSource=" + ExportVersions.sourceLabel());
        ExportPipeline.Bundle b = ExportPipeline.build(EndToEndExportTest.input(),
                "20260926-142100-abc999", FIXED, 11L);
        assertEquals(expected, b.manifest().str("privacySpecVersion"));
        assertEquals("injected", b.manifest().str("versionSource"),
                "Gradle 运行下版本应来自构建期注入");
        assertFalse(ExportVersions.isUnverified());
    }

    @Test
    @DisplayName("版本来源未接线时：声明降级为 UNVERIFIED 并置 versionSource=fallback（不伪装成具体版本）")
    void unverifiedVersionIsLabelledNotFaked() {
        String injected = System.getProperty(ExportVersions.SPEC_VERSION_PROPERTY);
        String fileProp = System.getProperty(ExportVersions.SPEC_VERSION_FILE_PROPERTY);
        try {
            System.clearProperty(ExportVersions.SPEC_VERSION_PROPERTY);
            System.setProperty(ExportVersions.SPEC_VERSION_FILE_PROPERTY,
                    exportRoot().resolve("does-not-exist.txt").toAbsolutePath().toString());

            assertTrue(ExportVersions.isUnverified(), "两个来源都不可用时必须判定为未验证");
            assertEquals(ExportVersions.SPEC_VERSION_UNVERIFIED, ExportVersions.privacySpecVersion(),
                    "未知时必须显式标记，而不是回退到一个可能过期的具体版本号");
            assertEquals("fallback", ExportVersions.sourceLabel());

            ExportPipeline.Bundle b = ExportPipeline.build(EndToEndExportTest.input(),
                    "20260926-142200-def888", FIXED, 12L);
            assertEquals(ExportVersions.SPEC_VERSION_UNVERIFIED,
                    b.manifest().str("privacySpecVersion"),
                    "产物不得声明未经证实的版本");
            assertEquals("fallback", b.manifest().str("versionSource"));
        } finally {
            if (injected == null) {
                System.clearProperty(ExportVersions.SPEC_VERSION_PROPERTY);
            } else {
                System.setProperty(ExportVersions.SPEC_VERSION_PROPERTY, injected);
            }
            if (fileProp == null) {
                System.clearProperty(ExportVersions.SPEC_VERSION_FILE_PROPERTY);
            } else {
                System.setProperty(ExportVersions.SPEC_VERSION_FILE_PROPERTY, fileProp);
            }
        }
    }

    @Test
    @DisplayName("版本来源的兜底链：构建期注入不可用时，运行时仍能从版本文件解析（非 Gradle 运行也正确）")
    void versionFallsBackToFileAtRuntime() throws Exception {
        String injected = System.getProperty(ExportVersions.SPEC_VERSION_PROPERTY);
        String fileProp = System.getProperty(ExportVersions.SPEC_VERSION_FILE_PROPERTY);
        try {
            System.clearProperty(ExportVersions.SPEC_VERSION_PROPERTY);
            Files.createDirectories(exportRoot());
            Path copy = exportRoot().resolve("SPEC-VERSION-copy.txt");
            Files.writeString(copy, "privacy-spec@9.9.9-test\n", StandardCharsets.UTF_8);
            System.setProperty(ExportVersions.SPEC_VERSION_FILE_PROPERTY, copy.toAbsolutePath().toString());

            assertEquals("privacy-spec@9.9.9-test", ExportVersions.privacySpecVersion(),
                    "注入缺席时必须退到读版本文件，而不是标记为未验证、更不是回退常量");
            assertFalse(ExportVersions.isUnverified(), "该路径已成功解析出版本，不是未验证路径");
            assertEquals("file", ExportVersions.sourceLabel());
        } finally {
            if (injected == null) {
                System.clearProperty(ExportVersions.SPEC_VERSION_PROPERTY);
            } else {
                System.setProperty(ExportVersions.SPEC_VERSION_PROPERTY, injected);
            }
            if (fileProp == null) {
                System.clearProperty(ExportVersions.SPEC_VERSION_FILE_PROPERTY);
            } else {
                System.setProperty(ExportVersions.SPEC_VERSION_FILE_PROPERTY, fileProp);
            }
        }
    }

    @Test
    @DisplayName("分析快照号不含任何玩家标识：只改 playerKey 不改变快照号")
    void snapshotIdIsIndependentOfPlayerIdentity() {
        ContentCatalog catalog = SyntheticCorpus.catalog();

        AnalysisReport base = AnalysisReport.analyze(SyntheticCorpus.events(), catalog,
                SyntheticCorpus.SOURCE);
        String idBase = ExportPipeline.snapshotIdOf(
                com.octant.pipeline.export.AnalysisJson.toJson(base));

        List<RawEvent> renamed = new ArrayList<>();
        for (RawEvent e : SyntheticCorpus.events()) {
            renamed.add(e.withPlayerKey("0123456789abcdef"));
        }
        AnalysisReport other = AnalysisReport.analyze(renamed, catalog, SyntheticCorpus.SOURCE);
        String idOther = ExportPipeline.snapshotIdOf(
                com.octant.pipeline.export.AnalysisJson.toJson(other));

        assertEquals(idBase, idOther,
                "快照号不得由含玩家标识的输入派生（否则它本身就成了可链接标识）");
        System.out.println("快照号身份无关性取证：base=" + idBase + " renamed=" + idOther);

        List<RawEvent> extra = new ArrayList<>(SyntheticCorpus.events());
        extra.add(new RawEvent("e900", "s0001", SyntheticCorpus.PLAYER_KEY, EventType.STALL_SEGMENT,
                90_000L, 1_801L, 60_000L, true,
                Map.of("unitId", "pack:chapter_end", "unitKind", "quest",
                        "dwellMs", 60_000L, "activeDensityPm", 1.0d, "proxy", Boolean.FALSE)));
        AnalysisReport changed = AnalysisReport.analyze(extra, catalog, SyntheticCorpus.SOURCE);
        String idChanged = ExportPipeline.snapshotIdOf(
                com.octant.pipeline.export.AnalysisJson.toJson(changed));
        assertFalse(idChanged.equals(idBase), "内容变化必须换号");
    }
}
