package com.octant.pipeline;

import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.export.PrivacyRedactor;
import com.octant.pipeline.export.RedactionMatrix;
import com.octant.pipeline.json.Json;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivacyRedactionTest {

    private static final String UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final String IPv4 = "203.0.113.7";
    private static final String URL = "https://example.invalid/leak";
    private static final String WINPATH = "C:\\Users\\leaky\\saves\\world\\level.dat";
    private static final String EMAIL = "leaky.player@example.invalid";
    private static final String HOSTPORT = "mc.play.example.invalid:25565";
    private static final String NAME = "LeakyPlayerName";
    private static final String CHAT_TEXT = "hello everyone, I live in Berlin";

    private static ContentCatalog catalog() {
        return SyntheticCorpus.catalog();
    }

    @Nested
    @DisplayName("T1 门禁（硬标识扫描）")
    class T1Gate {

        @Test
        @DisplayName("UUID / IPv4 / URL / Windows 路径 / 邮箱 / 主机:端口 六类模式全部可命中")
        void detectsAllPatternClasses() {
            PrivacyRedactor r = PrivacyRedactor.withSalt("salt-a", catalog());
            r.scanText("probe", String.join(" ", UUID, IPv4, URL, WINPATH, EMAIL, HOSTPORT));
            java.util.Set<String> classes = new java.util.TreeSet<>();
            for (String v : r.violations()) {
                assertTrue(v.contains("patternClass="), "违规记录必须说明模式类别");
                classes.add(v.substring(v.indexOf("patternClass=") + 13).split(" ")[0]);
            }
            assertEquals(java.util.Set.of("uuid", "ipv4", "url", "winpath", "email",
                    "hostport", "mcserver"), classes, "六类探针应全部命中：" + r.violations());
            assertFalse(r.clean(), "命中后不得判定为 clean");
        }

        @Test
        @DisplayName("干净语料的导出物不含任何 T1 命中，且不含 26 项禁止字段名")
        void cleanCorpusPassesGate() {
            ExportPipeline.Input in = EndToEndExportTest.input();
            ExportPipeline.Bundle b = ExportPipeline.build(in, "20260926-140800-aaa111",
                    Instant.parse("2026-09-26T14:08:00Z"), 2L);
            assertTrue(b.gateViolations().isEmpty(), "干净语料被门禁误判：" + b.gateViolations());
            for (String text : b.textFiles().values()) {
                for (String forbidden : RedactionMatrix.FORBIDDEN_FIELDS) {
                    assertFalse(text.contains("\"" + forbidden + "\""),
                            "导出物出现禁止字段：" + forbidden);
                }
            }
            Json.JsonObject gate = (Json.JsonObject) b.redactionReport().get("gate");
            assertEquals(0L, ((Number) gate.get("t1Hits")).longValue());
            assertEquals("pass", gate.str("result"));
            assertEquals(Boolean.FALSE, b.redactionReport().get("rawHitsIncluded"),
                    "脱敏报告不得包含命中原文");
            assertEquals(0L, ((Number) b.redactionReport().get("networkCallsMade")).longValue());
        }

        @Test
        @DisplayName("一旦事件 payload 里混入硬标识，整次导出失败且不产出任何文件（全或无）")
        void gateFailureAbortsWholeExport() throws Exception {
            ContentCatalog catalog = catalog();
            List<RawEvent> poisoned = new java.util.ArrayList<>(SyntheticCorpus.events());
            poisoned.add(new RawEvent("e999", "s0001", UUID, EventType.RECIPE_ATTEMPTED, 100_000L,
                    100_000L, 0L, true,
                    Map.of("recipeId", "minecraft:iron_pickaxe",
                            "station", "minecraft:crafting_table",
                            "outcome", "success",
                            "chapterId", URL)));
            var report = com.octant.pipeline.analysis.AnalysisReport.analyze(poisoned, catalog,
                    SyntheticCorpus.SOURCE);
            ExportPipeline.Input in = new ExportPipeline.Input(report, () -> poisoned, catalog, "0.1.0",
                    "1.20.1", "forge", "salt-b", List.of("C1", "C2"), List.of());
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mcinsight-gate-");
            ExportPipeline.Result result = ExportPipeline.write(in, root, Instant.parse("2026-09-26T14:09:00Z"), 3L);
            assertFalse(result.written(), "门禁失败时不得落盘");
            assertFalse(result.gateViolations().isEmpty(), "必须给出违规清单");
            assertTrue(result.fileBytes().isEmpty(), "不得产出任何文件");
            try (var walk = java.nio.file.Files.walk(root)) {
                assertEquals(0L, walk.filter(java.nio.file.Files::isRegularFile).count(),
                        "导出失败后不得残留文件");
            }
            System.out.println("门禁拦截取证：violations=" + result.gateViolations().size()
                    + " reason=" + result.failureReason());
            java.nio.file.Files.deleteIfExists(root);
        }
    }

    @Nested
    @DisplayName("身份重识别检查（对真实导出物做字符串扫描）")
    class ReidentificationCheck {

        @Test
        @DisplayName("导出物中不存在六类原始值（含报告载体 report.html —— CD-P5 的产物级覆盖）")
        void artifactsContainNoRawIdentifiers() {
            ExportPipeline.Input in = EndToEndExportTest.input();
            ExportPipeline.Bundle b = ExportPipeline.build(in, "20260926-140900-bbb222",
                    Instant.parse("2026-09-26T14:09:00Z"), 4L);
            assertTrue(b.gateViolations().isEmpty());

            List<String> markers = List.of(UUID, NAME, IPv4, URL, WINPATH, EMAIL, HOSTPORT, CHAT_TEXT,
                    SyntheticCorpus.PLAYER_KEY, "069a79f4", "level.dat",
                    "full.uuid.with.dots", "ChatMessageText", "player-name-literal");
            for (Map.Entry<String, String> e : b.textFiles().entrySet()) {
                String lower = e.getValue();
                for (String marker : markers) {
                    assertFalse(lower.contains(marker),
                            "导出物 " + e.getKey() + " 命中原始标识：" + marker);
                }
                assertFalse(lower.contains("C:\\"), e.getKey() + " 含 Windows 绝对路径");
                assertFalse(lower.contains("/home/"), e.getKey() + " 含 Unix 绝对路径");
            }

            String html = b.textFiles().get("report.html");
            assertNotNull(html, "报告载体应为 report.html（CD-P5 的覆盖面必须包含它）");
            assertFalse(b.binaryFiles().containsKey("report.pdf"),
                    "PDF 载体已退役，不得再产出 report.pdf");

            for (String marker : markers) {
                assertFalse(html.contains(marker), "report.html 命中原始标识：" + marker);
            }
            List<String> attributes = new HtmlTextExtractor(html).attributeValues();
            for (String value : attributes) {
                for (String marker : markers) {
                    assertFalse(value.contains(marker),
                            "report.html 的属性值命中原始标识：" + marker + "（属性面是 HTML 新增的绕过面）");
                }
            }
            byte[] fontBytes = new HtmlTextExtractor(html).embeddedFontBytes();
            assertNotNull(fontBytes, "report.html 必须内联字体资产（否则本覆盖缺少解码面）");
            String fontLatin = new String(fontBytes, StandardCharsets.ISO_8859_1);
            for (String marker : markers) {
                assertFalse(fontLatin.contains(marker),
                        "内联字体载荷命中原始标识：" + marker + "（解码面必须被覆盖）");
            }
            System.out.println("CD-P5 产物级覆盖（report.html）= 文本面 " + html.length()
                    + " 字符 / 属性值 " + attributes.size() + " 项 / 解码载荷 " + fontBytes.length + " 字节");
        }

        @Test
        @DisplayName("脱敏事件流：playerKey 被替换为每导出假名，坐标保持 512/32 网格量化")
        void anonymizedStreamShape() {
            ExportPipeline.Input in = EndToEndExportTest.input();
            ExportPipeline.Bundle b = ExportPipeline.build(in, "20260926-141000-ccc333",
                    Instant.parse("2026-09-26T14:10:00Z"), 5L);
            String jsonl = b.textFiles().get("events.anonymized.jsonl");
            assertFalse(jsonl.contains("\"playerKey\""), "L0 的 playerKey 不得出现在导出物");
            assertTrue(jsonl.contains("\"playerPseudonym\":\"p_"), "必须给出每导出假名");
            assertFalse(jsonl.contains(SyntheticCorpus.PLAYER_KEY), "假名不得等于原始键");

            long lines = jsonl.lines().filter(l -> !l.isBlank()).count();
            assertTrue(lines > 100, "脱敏事件流应包含全部事件，实际 " + lines);
            for (String line : jsonl.lines().toList()) {
                if (line.isBlank()) {
                    continue;
                }
                Json.JsonObject o = com.octant.pipeline.json.JsonReader.parseObject(line);
                String pseudo = o.str("playerPseudonym");
                assertTrue(pseudo != null && pseudo.startsWith("p_") && pseudo.length() == 18,
                        "假名形态应为 p_ + 16 位十六进制：" + pseudo);
                Json.JsonObject payload = (Json.JsonObject) o.get("payload");
                if (payload != null && payload.has("regionKey")) {
                    String regionKey = payload.str("regionKey");
                    for (String part : regionKey.split("[#:]")) {
                        if (part.matches("-?\\d+")) {
                            long v = Long.parseLong(part);
                            assertTrue(RedactionMatrix.isQuantizedX(v) || RedactionMatrix.isQuantizedY(v),
                                    "区域键分量未按 512/32 网格量化：" + part + " in " + regionKey);
                        }
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("假名化参数（不可链接性）")
    class PseudonymMode {

        @Test
        @DisplayName("每导出独立盐 ⇒ 同一玩家的假名在两份导出中不同（不可链接）")
        void perExportSaltBreaksLinkability() {
            PrivatizerProbe a = new PrivatizerProbe("salt-1");
            PrivatizerProbe b = new PrivatizerProbe("salt-2");
            String pa = a.redactor.pseudonymFor(SyntheticCorpus.PLAYER_KEY);
            String pb = b.redactor.pseudonymFor(SyntheticCorpus.PLAYER_KEY);
            assertNotEquals(pa, pb, "per_export 模式下两份导出的假名必须不同");
            assertEquals(18, pa.length());
            assertTrue(pa.startsWith("p_"));

            assertEquals(pa, new PrivatizerProbe("salt-1").redactor.pseudonymFor(SyntheticCorpus.PLAYER_KEY));
            assertEquals(1, a.redactor.pseudonyms().size(), "同一玩家只应产生一个假名");
        }

        @Test
        @DisplayName("ID 白名单：命中目录的 ID 原样保留，未命中的替换为 custom:<n>")
        void idWhitelistApplied() {
            PrivacyRedactor r = PrivacyRedactor.withSalt("salt-w", catalog());
            assertEquals("minecraft:furnace", r.whitelistId("machineBlock", "minecraft:furnace"));
            assertEquals("custom:0", r.whitelistId("machineBlock", "weirdmod:secret_machine"));
            assertEquals("custom:1", r.whitelistId("machineBlock", "othermod:another_machine"));
            assertEquals("custom:0", r.whitelistId("machineBlock", "weirdmod:secret_machine"),
                    "同一字段同一 ID 必须稳定映射到同一别名");
            assertFalse(RedactionMatrix.isIdField("regionKey"));
            assertFalse(RedactionMatrix.isIdField("containerKey"));
        }

        @Test
        @DisplayName("禁止字段矩阵：playerKey 走假名化，身份类字段一律 DROP")
        void matrixIsExplicit() {
            assertEquals(RedactionMatrix.Transformation.PSEUDONYMIZE,
                    RedactionMatrix.transformationFor("playerKey"));
            for (String f : List.of("player.uuid", "player.name", "client.ip", "server.address",
                    "chat.message", "pos.x", "time.epochMs")) {
                assertEquals(RedactionMatrix.Transformation.DROP,
                        RedactionMatrix.transformationFor(f), f + " 必须物理丢弃");
            }
            assertEquals(26, RedactionMatrix.FORBIDDEN_FIELDS.size(), "禁止字段清单应为 26 项");
        }
    }

    private record PrivatizerProbe(String salt, PrivacyRedactor redactor) {
        PrivatizerProbe(String salt) {
            this(salt, PrivacyRedactor.withSalt(salt, catalog()));
        }
    }
}
