package com.octant.common.collection;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.model.EventSource;
import com.octant.common.model.Json;
import com.octant.common.model.PayloadSchema;
import com.octant.common.model.RawEventSchema;
import com.octant.common.privacy.FieldRegistry;
import com.octant.common.privacy.RedactionPipeline;
import com.octant.common.privacy.RedactionReport;
import com.octant.common.privacy.SaltProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionPipelineBodyTest {

    private static final String RAW_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final String RAW_PLAYER_NAME = "NotchThePlayer";
    private static final String CUSTOM_NS_DIM = "acme_superpack:the_deep";
    private static final String VANILLA_DIM = "minecraft:overworld";

    private RedactionPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new RedactionPipeline(SaltProvider.generate(),
                Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                Set.of("acmepack"));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("封套假名化：产物不含原始 UUID")
    class EnvelopeRedaction {

        @Test
        @DisplayName("redactEnvelope 后 playerKey 是 16 位十六进制，不含原始 UUID/玩家名")
        void envelopeHidesRawIdentity() {
            CaptureEvent raw = new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(1),
                    CaptureEvent.sessionId(1), "0000000000000000", CaptureEventType.DIMENSION_ENTERED,
                    1000L, CaptureEvent.tickOf(1000L), CaptureEventType.DIMENSION_ENTERED.category(),
                    EventSource.FABRIC, true, null, map("dimension", VANILLA_DIM));

            CaptureEvent redacted = pipeline.redactEnvelope(raw, RAW_UUID);
            assertTrue(redacted.playerKey().matches("[0-9a-f]{16}"));
            assertNotEquals(RAW_UUID, redacted.playerKey());
            String serialized = Json.encode(redacted.toOrderedMap());
            assertFalse(serialized.contains(RAW_UUID), "产物不得含原始 UUID");
            assertFalse(serialized.contains("069a79f4"), "产物不得含 UUID 片段");
            assertFalse(serialized.toLowerCase(java.util.Locale.ROOT)
                    .contains(RAW_PLAYER_NAME.toLowerCase(java.util.Locale.ROOT)));
        }

        @Test
        @DisplayName("同盐同输入可复算；不同盐不可关联")
        void deterministicUnderSameSalt() {
            SaltProvider a = SaltProvider.generate();
            RedactionPipeline p1 = new RedactionPipeline(a, Set.of(), Set.of());
            RedactionPipeline p2 = new RedactionPipeline(a, Set.of(), Set.of());
            RedactionPipeline p3 = new RedactionPipeline(SaltProvider.generate(), Set.of(), Set.of());
            assertEquals(p1.redactEnvelope(event(VANILLA_DIM), RAW_UUID).playerKey(),
                    p2.redactEnvelope(event(VANILLA_DIM), RAW_UUID).playerKey());
            assertNotEquals(p1.redactEnvelope(event(VANILLA_DIM), RAW_UUID).playerKey(),
                    p3.redactEnvelope(event(VANILLA_DIM), RAW_UUID).playerKey());
        }

        private static CaptureEvent event(String dim) {
            return new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(1),
                    CaptureEvent.sessionId(1), "0000000000000000", CaptureEventType.DIMENSION_ENTERED,
                    1000L, CaptureEvent.tickOf(1000L), CaptureEventType.DIMENSION_ENTERED.category(),
                    EventSource.FABRIC, true, null, map("dimension", dim));
        }
    }

    @Nested
    @DisplayName("payload 逐字段处置：键集合必须不变（dc §2.2）")
    class PayloadKeysPreserved {

        @Test
        @DisplayName("处置后键集合与键序与原始完全一致（变换值而非删键）")
        void keysAndOrderUnchanged() {
            Map<String, Object> payload = map("dimension", VANILLA_DIM,
                    "fromDimension", "minecraft:the_nether");
            Map<String, Object> out = pipeline.redactPayload(CaptureEventType.DIMENSION_ENTERED,
                    payload, 0, 0);
            assertEquals(List.copyOf(payload.keySet()), List.copyOf(out.keySet()),
                    "管道的输出键集合与键序必须与输入一致；删键会让事件不再是合法契约事件");
        }

        @Test
        @DisplayName("处置后的 payload 仍能通过 §2.4 schema 校验")
        void outputStillValidContractEvent() {
            Map<String, Object> payload = map("dimension", VANILLA_DIM,
                    "fromDimension", "minecraft:the_nether");
            Map<String, Object> out = pipeline.redactPayload(CaptureEventType.DIMENSION_ENTERED,
                    payload, 0, 0);
            Map<String, Object> encoded = PayloadSchema.encode(CaptureEventType.DIMENSION_ENTERED,
                    out, 1000L);
            assertEquals(VANILLA_DIM, encoded.get("dimension"));
        }

        @Test
        @DisplayName("白名单内 ID 原样；白名单外（自定义命名空间）替换为 custom:<n>，原文不出现")
        void whitelistOrCustom() {
            assertEquals(VANILLA_DIM, pipeline.whitelistOrCustom("dimension", VANILLA_DIM,
                    CaptureEventType.DIMENSION_ENTERED));
            assertEquals("acmepack:thing", pipeline.whitelistOrCustom("dimension", "acmepack:thing",
                    CaptureEventType.DIMENSION_ENTERED));

            String mapped = pipeline.whitelistOrCustom("dimension", CUSTOM_NS_DIM,
                    CaptureEventType.DIMENSION_ENTERED);
            assertEquals("custom:1", mapped);
            assertFalse(mapped.contains("acme_superpack"), "自定义命名空间不得出现在产物里");
            assertEquals("custom:2", pipeline.whitelistOrCustom("dimension", CUSTOM_NS_DIM,
                    CaptureEventType.DIMENSION_ENTERED));
        }

        @Test
        @DisplayName("自定义维度用 custom_dimension#<n>（登记表 noteId）")
        void customDimensionRelabel() {
            assertEquals(VANILLA_DIM, pipeline.customDimension(VANILLA_DIM));
            assertEquals("custom_dimension#1", pipeline.customDimension(CUSTOM_NS_DIM));
            assertFalse(pipeline.customDimension(CUSTOM_NS_DIM).contains("acme"));
        }

        @Test
        @DisplayName("服务器类别字段被泛化为闭集枚举，域名与端口不留")
        void serverClassGeneralized() {
            Map<String, Object> payload = map("privacyClass", "mc.example-server.net:25565");
            Map<String, Object> out = pipeline.redactPayload(CaptureEventType.SESSION_START, payload, 0, 0);
            assertEquals("public_server", out.get("privacyClass"));
            assertFalse(String.valueOf(out.get("privacyClass")).contains("example-server"));
        }
    }

    @Nested
    @DisplayName("标量处置：C 类拒绝、丢弃型返回 null 并登记")
    class ScalarRedaction {

        @Test
        @DisplayName("C 类字段在导出层也必须拒绝（不采集/不落盘）")
        void classCRejected() {
            ContractException ex = assertThrows(ContractException.class,
                    () -> pipeline.redactScalar("not_collected.locale", "zh-CN"));
            assertTrue(ex.getMessage().contains("not_collected.locale"));
        }

        @Test
        @DisplayName("丢弃型变换返回 null 并在报告中记为 dropped")
        void dropReturnsNullAndIsRecorded() {
            assertNull(pipeline.redactScalar("local.config.anyPath", "/home/alice/world"));
            assertTrue(pipeline.report().droppedFieldPaths().contains("local.config.anyPath"));
        }

        @Test
        @DisplayName("假名化/泛化/量化类标量产物均不含原值")
        void scalarsLoseRawValues() {
            RedactionPipeline p = new RedactionPipeline(SaltProvider.generate(),
                    Set.of("minecraft:overworld"), Set.of());

            Object key = p.redactScalar("envelope.playerKey", RAW_UUID);
            assertTrue(String.valueOf(key).matches("[0-9a-f]{16}"));

            Object cls = p.redactScalar("session_start.privacyClass", "mc.example.net:25565");
            assertEquals("public_server", cls);

            Object os = p.redactScalar("local.meta.envSnapshot", "Windows 11 Pro 22H2 build 22621");
            assertFalse(String.valueOf(os).contains("22621"), "具体版本号不得保留：" + os);

        }

        @Test
        @DisplayName("裁定后 regionKey 按 B 档量化：永不落盘精确坐标，且废弃条目不得决定最严档")
        void regionKeyIsQuantizedPerRuling() {
            Object x = pipeline.redactScalar("region_first_visit.regionKey", 12_345L);
            assertFalse(String.valueOf(x).contains("12345"), "精确坐标不得落盘：" + x);
            assertEquals(0L, ((Number) x).longValue() & 511L, "水平量化必须落在 512 格网格上");

            assertTrue(FieldRegistry.deprecatedFieldIds().contains("position.precise_optin"));
            FieldRegistry.FieldPolicy effective =
                    FieldRegistry.strictestFor("region_first_visit.regionKey").orElseThrow();
            assertFalse(FieldRegistry.isDeprecated(effective),
                    "生效档位不得是 deprecated_* 条目");
            assertEquals(FieldRegistry.LocationClass.B, effective.locationClass(),
                    "regionKey 的权威档位应为 B（量化），而非废弃条目的 C");
            assertTrue(FieldRegistry.strictestDrivenByDeprecated().contains("region_first_visit.regionKey"),
                    "该 rawField 必须出现在『若不排除废弃条目则会误判』的清单里，"
                            + "因为过滤规则正是为它而生");

            ContractException c = assertThrows(ContractException.class,
                    () -> pipeline.redactScalar("not_collected.locale", "zh-CN"));
            assertTrue(c.getMessage().contains("不采集/不落盘"), c.getMessage());
        }

        @Test
        @DisplayName("批量处置：丢弃项不进结果，其余保留")
        void batchRedaction() {
            Map<String, Object> batch = map(
                    "envelope.playerKey", RAW_UUID,
                    "local.config.anyPath", "/home/alice/world",
                    "session_start.privacyClass", "192.168.1.20:25565");
            Map<String, Object> out = pipeline.redactScalarBatch(batch);
            assertFalse(out.containsKey("local.config.anyPath"), "丢弃型字段不得出现在结果里");
            assertEquals("private_server", out.get("session_start.privacyClass"));
            assertTrue(String.valueOf(out.get("envelope.playerKey")).matches("[0-9a-f]{16}"));
        }
    }

    @Nested
    @DisplayName("redaction_report：只含字段路径与计数，不含原文")
    class ReportContract {

        @Test
        @DisplayName("报告 JSON 不含任何原始值，且按变换汇总计数")
        void reportHasNoRawValues() {
            pipeline.redactScalar("envelope.playerKey", RAW_UUID);
            pipeline.redactScalar("local.config.anyPath", "/home/alice/world");
            pipeline.redactScalar("session_start.privacyClass", "mc.example.net:25565");

            String json = pipeline.report().toJson(
                    List.of("player.uuid", "raw.raw_events", "raw.pseudonym_salt"), 6, 6);

            for (String raw : List.of(RAW_UUID, "069a79f4", "/home/alice/world", "mc.example.net", "25565")) {
                assertFalse(json.contains(raw), "报告不得包含原始值：" + raw);
            }
            assertTrue(RedactionReport.containsNoRawValues(json,
                    List.of(RAW_UUID, "/home/alice/world", "mc.example.net:25565")));
            assertTrue(json.contains("哈希加盐截断64"), "报告必须给出变换 ID");
            assertTrue(pipeline.report().countOf("哈希加盐截断64") >= 1);
        }

        @Test
        @DisplayName("s3AbsentFields 的元素必须是 field-registry 的字段 id")
        void s3AbsentFieldsMustBeRegisteredIds() {
            assertThrows(ContractException.class, () -> pipeline.report().toJson(
                    List.of("player.uuid", "这不是一个登记 id"), 0, 0));
            String json = pipeline.report().toJson(List.of("player.uuid", "raw.pseudonym_salt"), 0, 0);
            assertTrue(json.contains("player.uuid"));
        }

        @Test
        @DisplayName("s2Confirmations 的 required 必须等于 confirmed")
        void s2RequiredEqualsConfirmed() {
            assertThrows(ContractException.class, () -> pipeline.report().toJson(List.of(), 6, 5));
            String json = pipeline.report().toJson(List.of(), 6, 6);
            assertTrue(json.contains("\"required\":6") && json.contains("\"confirmed\":6"));
        }

        @Test
        @DisplayName("空字段名或空变换必须被拒绝（防止报告里出现无名条目）")
        void recordRejectsBlanks() {
            RedactionReport r = new RedactionReport();
            assertThrows(ContractException.class,
                    () -> r.record("", FieldRegistry.Transform.KEEP, false));
            assertThrows(ContractException.class,
                    () -> r.record("x", null, false));
        }

        @Test
        @DisplayName("droppedFieldPaths 与 transformCounts 可从报告读出，供验证任务消费")
        void reportIsMachineReadable() {
            pipeline.redactScalar("local.config.anyPath", "/tmp/x");
            pipeline.redactScalar("envelope.playerKey", RAW_UUID);
            String json = pipeline.report().toJson(List.of("player.uuid"), 0, 0);
            Map<String, Object> root = Json.decodeObject(json);
            assertTrue(root.containsKey("applied"));
            assertTrue(root.containsKey("transformCounts"));
            assertTrue(root.containsKey("droppedFieldPaths"));
            assertTrue(root.containsKey("redaction"));
            @SuppressWarnings("unchecked")
            var dropped = (List<String>) root.get("droppedFieldPaths");
            assertTrue(dropped.contains("local.config.anyPath"));
        }
    }
}
