package com.octant.common.collection;

import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.model.PayloadSchema;
import com.octant.common.privacy.FieldRegistry;
import com.octant.common.privacy.RegistryCoverage;
import com.octant.common.privacy.SaltProvider;
import com.octant.common.privacy.Transforms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionPipelineTest {

    private static final String RAW_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final String RAW_PLAYER_NAME = "NotchThePlayer";
    private static final String RAW_SERVER = "mc.example-server.net:25565";

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("登记表覆盖率：与 docs/privacy/field-registry.yaml 双向核对")
    class Coverage {

        @Test
        @DisplayName("YAML 每条 rawField 都有 Java 实现；Java 不得自造字段；变换 ID 全部实现")
        void javaCoversEntireRegistry() throws Exception {
            assertTrue(RegistryCoverage.locate().isPresent(),
                    "工作区无 docs/privacy/field-registry.yaml ⇒ 覆盖率核对无法进行。"
                            + "缺证据不得被读作通过：这是配置/检出错误，必须修，不是跳过");

            RegistryCoverage.Report report = RegistryCoverage.check();
            System.out.println("REGISTRY_COVERAGE " + report.summary());
            System.out.println("REGISTRY_COVERAGE_MISSING " + report.missingInJava());
            System.out.println("REGISTRY_COVERAGE_EXTRA " + report.extraInJava());
            System.out.println("REGISTRY_COVERAGE_UNKNOWN_TRANSFORMS " + report.unknownTransforms());

            assertTrue(report.yamlFieldCount() >= 80,
                    "登记表字段条目数异常偏少（疑似解析失败）：" + report.yamlFieldCount());

            assertTrue(report.missingInJava().isEmpty(),
                    "以下登记字段在 Java 侧缺失（脱敏管道未覆盖）：" + report.missingInJava());
            assertTrue(report.extraInJava().isEmpty(),
                    "Java 侧登记了登记表之外的字段（自造字段）：" + report.extraInJava());
            assertTrue(report.unknownTransforms().isEmpty(),
                    "以下变换 ID 未实现：" + report.unknownTransforms());
        }

        @Test
        @DisplayName("26 种变换 ID 与登记表逐字一致（禁止别名）")
        void transformIdsAreVerbatim() {
            assertEquals(26, FieldRegistry.Transform.values().length,
                    "登记表 transforms 段共 26 条，实现必须逐一对应");
            assertEquals(FieldRegistry.Transform.HASH_SALT_TRUNC64,
                    FieldRegistry.Transform.bySpecId("哈希加盐截断64"));
            assertThrows(ContractException.class,
                    () -> FieldRegistry.Transform.bySpecId("哈希加盐截断64_别名"),
                    "未知变换 ID 必须抛错，不得近似匹配");
        }

        @Test
        @DisplayName("同一 rawField 多登记时取最严档（不采集不得被放宽）")
        void strictestClassWins() {
            FieldRegistry.FieldPolicy env = FieldRegistry.lookup("local.meta.envSnapshot").orElseThrow();
            assertEquals(FieldRegistry.LocationClass.B, env.locationClass(),
                    "A 与 B 共用载体时必须取更严的 B，否则泛化会被静默放宽");
        }
    }

    @Nested
    @DisplayName("C 类字段：采集期必须被拒绝（不采集、不落盘）")
    class NotCollected {

        @Test
        @DisplayName("locale / 皮肤 / 披风 / IP / MOTD / 聊天原文 / 命令 / 告示牌 / 书 均被拦下")
        void classCFieldsAreBlocked() {
            List<String[]> blocked = List.of(
                    new String[] {"not_collected", "locale"},
                    new String[] {"not_collected", "skin_texture"},
                    new String[] {"not_collected", "cape"},
                    new String[] {"not_collected", "client_ip"},
                    new String[] {"not_collected", "server_motd"},
                    new String[] {"not_collected", "chat_text"},
                    new String[] {"not_collected", "command_text"},
                    new String[] {"not_collected", "sign_text"},
                    new String[] {"not_collected", "book_text"},
                    new String[] {"not_collected", "entity_custom_name"},
                    new String[] {"not_collected", "item_display_name"},
                    new String[] {"not_collected", "quest_title"},
                    new String[] {"not_collected", "crash_report"});

            for (String[] pair : blocked) {
                Optional<FieldRegistry.Violation> v = FieldRegistry.checkCollection(pair[0], pair[1]);
                assertTrue(v.isPresent(), pair[0] + "." + pair[1] + " 必须被判定为不采集");
                assertEquals(FieldRegistry.ViolationReason.NOT_COLLECTED_CLASS_C,
                        v.orElseThrow().reason());
                assertFalse(v.orElseThrow().toMap().toString().contains(RAW_UUID),
                        "违规记录不得包含原始值");
            }
        }

        @Test
        @DisplayName("违规记录是结构化的且不含原始值，可安全写入 redaction_report")
        void violationIsStructuredAndValueFree() {
            FieldRegistry.Violation v =
                    FieldRegistry.checkCollection("not_collected", "locale").orElseThrow();
            Map<String, Object> m = v.toMap();
            assertTrue(m.containsKey("blocked") && m.containsKey("reason") && m.containsKey("fieldPath"));
            assertEquals("NOT_COLLECTED_CLASS_C", m.get("reason"));
            assertEquals("locale", m.get("fieldPath"));
            assertEquals("not_collected.locale", v.rawField());
            assertFalse(m.toString().contains("zh-CN"), "不得把被拒原始值写进违规记录");
        }
    }

    @Nested
    @DisplayName("B 类变换：产物不含原始可识别值")
    class TransformsEraseRawValues {

        @Test
        @DisplayName("哈希加盐截断64：UUID → 16 位十六进制，且不含原值任何形式")
        void hashSaltTrunc64() {
            SaltProvider salt = SaltProvider.generate();
            String out = salt.pseudonymizeUuid(RAW_UUID);
            assertTrue(out.matches("[0-9a-f]{16}"));
            assertFalse(out.contains(RAW_UUID));
            assertFalse(out.contains("069a79f4"));
            assertFalse(out.toLowerCase(java.util.Locale.ROOT)
                    .contains(RAW_PLAYER_NAME.toLowerCase(java.util.Locale.ROOT)));
        }

        @Test
        @DisplayName("量化512 / 量化32：精确坐标不落盘（格网对齐且与精确值不同）")
        void coordinateQuantization() {
            long x = 12_345L;
            long z = -67_890L;
            long y = 63L;
            long qx = Transforms.quantizeHorizontal(x);
            long qz = Transforms.quantizeHorizontal(z);
            long qy = Transforms.quantizeVertical(y);

            assertEquals(0L, qx & 511L, "水平量化必须落在 512 格网格上");
            assertEquals(0L, qz & 511L);
            assertEquals(0L, qy & 31L, "垂直量化必须落在 32 格网格上");
            assertNotEquals(x, qx, "量化后不得等于精确坐标");
            assertNotEquals(y, qy);

            long ox = Transforms.quantizeHorizontalWithOffset(x, 137);
            assertNotEquals(qx, ox);
            assertTrue(ox - qx >= 0 && ox - qx < 512);
            assertThrows(ContractException.class,
                    () -> Transforms.quantizeHorizontalWithOffset(x, 512), "偏移越界必须拒绝");
        }

        @Test
        @DisplayName("区域键只含量化格网号，不含精确坐标")
        void regionKeyHasNoPreciseCoordinates() {
            String key = Transforms.regionKey("minecraft:overworld", 12_345L, 67_890L);
            assertFalse(key.contains("12345"), "区域键不得包含精确 x：" + key);
            assertFalse(key.contains("67890"), "区域键不得包含精确 z：" + key);
            assertEquals("minecraft:overworld#24:132", key);
        }

        @Test
        @DisplayName("丢弃-泛化为类别：服务器地址 → 闭集枚举，原文一个字都不留")
        void serverAddressGeneralized() {
            String cls = Transforms.privacyClass(RAW_SERVER);
            assertEquals("public_server", cls);
            assertFalse(cls.contains("example-server"), "不得保留域名片段");
            assertFalse(cls.contains("25565"), "不得保留端口");
            assertEquals("private_server", Transforms.privacyClass("192.168.1.20:25565"));
            assertEquals("singleplayer", Transforms.privacyClass("singleplayer"));
            assertEquals("unknown", Transforms.privacyClass(null));
        }

        @Test
        @DisplayName("丢弃-泛化为OS族/GPU族/硬件桶：只留族或桶，不留型号与驱动版本")
        void hardwareGeneralized() {
            assertEquals("windows", Transforms.osFamily("Windows 11 Pro 22H2 build 22621"));
            assertEquals("linux", Transforms.osFamily("Linux 6.8.0-45-generic"));
            assertEquals("macos", Transforms.osFamily("Mac OS X 14.5"));
            assertEquals("other", Transforms.osFamily("Haiku R1"));

            assertEquals("nvidia", Transforms.gpuFamily("NVIDIA GeForce RTX 4070 Ti driver 552.22"));
            assertEquals("amd", Transforms.gpuFamily("AMD Radeon RX 7900 XTX"));
            assertEquals("intel", Transforms.gpuFamily("Intel(R) UHD Graphics 630"));
            assertEquals("apple", Transforms.gpuFamily("Apple M2 Pro"));
            assertEquals("other", Transforms.gpuFamily(null));

            assertEquals("16", Transforms.ramBucket(16L * 1024 * 1024 * 1024));
            assertEquals("32+", Transforms.ramBucket(64L * 1024 * 1024 * 1024));
            assertEquals("2", Transforms.ramBucket(1024L * 1024 * 1024));
        }

        @Test
        @DisplayName("截断-去子标签：地区子标签必须丢弃")
        void languageSubtagDropped() {
            assertEquals("zh", Transforms.languageMainTag("zh-CN"));
            assertEquals("zh", Transforms.languageMainTag("zh_TW"));
            assertEquals("en", Transforms.languageMainTag("en"));
            assertFalse(Transforms.languageMainTag("zh-CN").contains("CN"));
        }

        @Test
        @DisplayName("时间粗化：UTC 日期 / 相对毫秒 / 时间段桶 / 长度桶")
        void timeAndLengthCoarsening() {
            assertTrue(Transforms.utcDate(1_772_000_000_000L).matches("\\d{4}-\\d{2}-\\d{2}"));
            assertEquals(0L, Transforms.relativize(1_000L, 5_000L), "早于锚点时必须夹到 0");
            assertEquals(2_500L, Transforms.relativize(7_500L, 5_000L));
            assertEquals("night", Transforms.dayBucket(3));
            assertEquals("morning", Transforms.dayBucket(9));
            assertEquals("afternoon", Transforms.dayBucket(15));
            assertEquals("evening", Transforms.dayBucket(21));
            assertThrows(ContractException.class, () -> Transforms.dayBucket(24));
            assertEquals("0", Transforms.lengthBucket(""));
            assertEquals("1-8", Transforms.lengthBucket("12345678"));
            assertEquals("9-32", Transforms.lengthBucket("123456789"));
            assertEquals("33+", Transforms.lengthBucket("x".repeat(33)));
        }

        @Test
        @DisplayName("丢弃-重标记为序号：标签由序号生成，不由原标识符派生")
        void relabelIsOrdinalBased() {
            assertEquals("P1", Transforms.relabelSequence(1));
            assertEquals("P7", Transforms.relabelSequence(7));
            assertFalse(Transforms.relabelSequence(1).contains("N"), "标签不得携带原值信息");
            assertThrows(ContractException.class, () -> Transforms.relabelSequence(0));
        }

        @Test
        @DisplayName("丢弃-仅保留ID：带空白/超长的自由文本一律丢弃为空")
        void keepIdOnlyRejectsFreeText() {
            assertEquals("minecraft:overworld", Transforms.keepIdOnly("minecraft:overworld"));
            assertEquals("", Transforms.keepIdOnly("这是一段自由文本 带空格"));
            assertEquals("", Transforms.keepIdOnly("x".repeat(129)));
            assertEquals("", Transforms.keepIdOnly(null));
        }

        @Test
        @DisplayName("摘要截断：按 count 降序取前 32 并置 truncated=true")
        void digestTruncation() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 1; i <= 40; i++) {
                items.add(map("item", "minecraft:item_" + i, "count", i));
            }
            Transforms.TruncatedDigest r = Transforms.truncateDigest(items, 32);
            assertEquals(32, r.items().size());
            assertTrue(r.truncated());
            assertEquals(40, r.items().get(0).get("count"), "必须按 count 降序，最大值在前");

            Transforms.TruncatedDigest small = Transforms.truncateDigest(items.subList(0, 5), 32);
            assertFalse(small.truncated());
            assertEquals(5, small.items().size());
        }

        @Test
        @DisplayName("泛化为原因码：只允许 ASCII 结构化 detail，禁止自由文本/方向性措辞")
        void reasonCodeGeneralization() {
            String detail = Transforms.reasonDetail("INSUFFICIENT_ACTIVE_TIME", "2400s", "3600s");
            assertTrue(Transforms.isAsciiDetail(detail));
            assertEquals("INSUFFICIENT_ACTIVE_TIME: observed=2400s required=3600s", detail);
            assertThrows(ContractException.class, () -> Transforms.reasonDetail("太低", "1", "2"));
            assertFalse(Transforms.isAsciiDetail("observed=2400s required=3600s —— 该玩家太懒"));
            assertThrows(ContractException.class,
                    () -> Transforms.reasonDetail("INSUFFICIENT_ACTIVE_TIME", "2400s", "3600s —— 太懒"));
        }

        @Test
        @DisplayName("丢弃型变换生成的占位声明显式且不含值")
        void dropPlaceholderIsExplicit() {
            Map<String, Object> p = Transforms.dropPlaceholder(FieldRegistry.Transform.DROP);
            assertEquals(true, p.get("dropped"));
            assertEquals("丢弃", p.get("transform"));
            assertFalse(p.containsKey("value"));
            assertThrows(ContractException.class,
                    () -> Transforms.dropPlaceholder(FieldRegistry.Transform.KEEP));
        }

        @Test
        @DisplayName("describe 只记录形态与长度，不记录内容")
        void describeNeverLeaksContent() {
            String d = Transforms.describe(RAW_PLAYER_NAME);
            assertTrue(d.startsWith("string(len="));
            assertFalse(d.contains("Notch"), "describe 不得包含原文");
        }
    }

    @Nested
    @DisplayName("变换与 schema 串联：变换后的值仍满足 dc §2.4 取值范围")
    class TransformedValuesStillValid {

        @Test
        @DisplayName("量化区域键可直接作为 region_first_visit.regionKey 通过 schema")
        void quantizedRegionKeyPassesSchema() {
            String key = Transforms.regionKey("minecraft:overworld", 12_345L, 67_890L);
            Map<String, Object> encoded = PayloadSchema.encode(
                    CaptureEventType.REGION_FIRST_VISIT,
                    map("dimension", "minecraft:overworld", "regionKey", key,
                            "biome", "minecraft:plains"), 1000L);
            assertEquals(key, encoded.get("regionKey"));
        }

        @Test
        @DisplayName("泛化后的服务器类别满足 session_start.privacyClass 闭集")
        void generalizedClassPassesSchema() {
            String cls = Transforms.privacyClass(RAW_SERVER);
            Map<String, Object> payload = map("privacyClass", cls, "gameVersion", "1.21.1",
                    "loader", "neoforge", "sessionStartDate", "2026-09-26", "cheatsEnabled", false);
            Map<String, Object> encoded = PayloadSchema.encode(
                    CaptureEventType.SESSION_START, payload, 0L);
            assertEquals("public_server", encoded.get("privacyClass"));
        }
    }
}
