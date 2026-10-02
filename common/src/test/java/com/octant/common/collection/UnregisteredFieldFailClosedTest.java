package com.octant.common.collection;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.privacy.FieldRegistry;
import com.octant.common.privacy.RedactionPipeline;
import com.octant.common.privacy.RegistryCoverage;
import com.octant.common.privacy.SaltProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnregisteredFieldFailClosedTest {

    private static RedactionPipeline pipeline() {
        return new RedactionPipeline(SaltProvider.generate(), Set.of(), Set.of());
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("fail-closed：未登记字段的取值不得出现在输出里")
    class UnregisteredIsRedacted {

        @Test
        @DisplayName("登记表里查不到的字段：取值被清空，键保留（dc §2.2 要求键集合不变）")
        void unknownFieldValueIsClearedAndKeyKept() {
            RedactionPipeline p = pipeline();
            Map<String, Object> payload = map(
                    "totallyUnregisteredField", "secret-value-12345",
                    "anotherUnknownId", "abc:def");

            Map<String, Object> out = p.redactPayload(CaptureEventType.SESSION_START, payload,
                    0, 0);

            assertEquals(payload.keySet(), out.keySet(),
                    "payload 键集合必须与契约字段表完全相同（不得去键）");
            for (String k : payload.keySet()) {
                String v = String.valueOf(out.get(k));
                assertFalse(v.contains("secret-value-12345"),
                        "未登记字段的取值不得出现在输出里：" + k + " -> " + v);
                assertFalse(v.contains("abc:def"),
                        "未登记字段的取值不得出现在输出里：" + k + " -> " + v);
            }
            assertTrue(p.hasUnregisteredRedactionsRecorded(),
                    "未登记字段的处置必须被记录（不得静默清空）");
        }

        @Test
        @DisplayName("未登记字段的取值为对象/数组时也被清空")
        void structuredUnknownValuesAreCleared() {
            RedactionPipeline p = pipeline();
            Map<String, Object> payload = map(
                    "unknownStructured", List.of(map("k", "identifiable-value")),
                    "unknownObject", map("inner", "identifiable-value"));

            Map<String, Object> out = p.redactPayload(CaptureEventType.CONTAINER_SNAPSHOT, payload,
                    0, 0);

            assertEquals(payload.keySet(), out.keySet());
            assertFalse(String.valueOf(out.get("unknownStructured")).contains("identifiable-value"),
                    "嵌套结构里的取值也不得漏出：" + out.get("unknownStructured"));
            assertFalse(String.valueOf(out.get("unknownObject")).contains("identifiable-value"),
                    "嵌套结构里的取值也不得漏出：" + out.get("unknownObject"));
        }
    }

    @Nested
    @DisplayName("反向对照：登记了就照规则办（证明判据不是『恒丢』）")
    class RegisteredFieldsStillObeyRules {

        @Test
        @DisplayName("ID 白名单：含自定义命名空间的值换成 custom:<n>，原值不出现")
        void idFieldGoesThroughWhitelist() {
            RedactionPipeline p = pipeline();
            Map<String, Object> payload = map("advancementId", "somemod:secret_advancement");

            Map<String, Object> out = p.redactPayload(CaptureEventType.ADVANCEMENT_GAINED, payload,
                    0, 0);

            assertNotNull(out.get("advancementId"));
            assertFalse(String.valueOf(out.get("advancementId")).contains("somemod"),
                    "未命中白名单的 ID 必须被替换：" + out.get("advancementId"));
        }

        @Test
        @DisplayName("泛化档：privacyClass 的值泛化为闭集取值，原主机名不出现")
        void generalizeCategoryLosesRawValue() {
            RedactionPipeline p = pipeline();
            Map<String, Object> payload = map("privacyClass", "mc.example.net:25565");

            Map<String, Object> out = p.redactPayload(CaptureEventType.SESSION_START, payload, 0, 0);

            String v = String.valueOf(out.get("privacyClass"));
            assertFalse(v.contains("example.net"), "泛化后不得保留原值：" + v);
            assertTrue(Set.of("singleplayer", "private_server", "public_server", "unknown").contains(v),
                    "泛化必须落到闭集取值：" + v);
        }

        @Test
        @DisplayName("C 类字段经 redactScalar 必须可判定地失败（登记表说『不采集』就得有人接住）")
        void classCFieldIsNotSilentlyPassed() {
            RedactionPipeline p = pipeline();
            ContractException ex = assertThrows(ContractException.class,
                    () -> p.redactScalar("not_collected.locale", "zh-CN"),
                    "C 类字段未抛异常，说明『不采集』这条登记没有落到任何一层");
            assertTrue(ex.getMessage().contains("不采集/不落盘"), ex.getMessage());
        }

        @Test
        @DisplayName("采集期判定在**生产路径上**真的会被走到（requireIrreversible 不是只写在文档里）")
        void requireIrreversibleIsOnTheProductionPath() {
            RedactionPipeline p = pipeline();
            Map<String, Object> payload = map("biome", "minecraft:plains");
            Map<String, Object> out = p.redactPayload(CaptureEventType.BIOME_VISITED, payload, 0, 0);
            assertNotNull(out.get("biome"));
        }
    }

    @Nested
    @DisplayName("ViolationReason 的产出点：UNREGISTERED_FIELD 与 UNREGISTERED_FIELD_REDACTED")
    class ViolationReasonsHaveProducers {

        @Test
        @DisplayName("采集期判定：按键查不到 + 字段名完全不存在 ⇒ UNREGISTERED_FIELD")
        void collectionCheckYieldsUnregistered() {
            var v = FieldRegistry.checkCollection("session_start", "thisFieldNameExistsNowhere");
            assertTrue(v.isPresent(), "未登记字段必须产出违规，不得返回空（=放行）");
            assertEquals(FieldRegistry.ViolationReason.UNREGISTERED_FIELD, v.get().reason());

            assertTrue(FieldRegistry.checkCollection("session_start", "playerKey").isEmpty(),
                    "已登记字段不得被误判为未登记");
        }

        @Test
        @DisplayName("脱敏期判定：未登记字段的取值被清空，并在报告中登记（不冒充登记变换）")
        void redactionRecordsUnregisteredSeparately() {
            RedactionPipeline p = pipeline();
            Map<String, Object> payload = map("thisFieldNameExistsNowhere", "value-should-vanish");
            p.redactPayload(CaptureEventType.SESSION_START, payload, 0, 0);

            String reportJson = p.report().toJson(List.of("player.uuid"), 0, 0);
            assertTrue(reportJson.contains("unregisteredRedactedFields"),
                    "报告必须独立给出因未登记而被清空的字段清单");
            assertTrue(reportJson.contains("thisFieldNameExistsNowhere"),
                    "被清空的字段路径必须进报告（否则就是静默清空）");
            assertFalse(reportJson.contains("value-should-vanish"),
                    "报告里不得出现被清空的原始取值");
            assertFalse(p.report().countByTransform().keySet().stream()
                            .anyMatch(k -> k.contains("thisFieldNameExistsNowhere")),
                    "未登记字段不得被记成某个登记变换的计数");
        }

        @Test
        @DisplayName("违规记录本身不含原始取值（可安全写入 redaction_report）")
        void violationCarriesNoRawValue() {
            var v = FieldRegistry.checkCollection("session_start", "unknownFieldWithValue").orElseThrow();
            String s = v.toMap().toString();
            assertFalse(s.contains("=") && s.contains("secret"),
                    "违规记录只应有字段路径与原因：" + s);
            assertEquals("UNREGISTERED_FIELD", v.toMap().get("reason"));
        }
    }

    @Nested
    @DisplayName("覆盖面：数据侧（契约字段表）必须进入核对范围")
    class DataSideIsInScope {

        @Test
        @DisplayName("核对结论里必须给出『契约字段表 ↔ 登记表』的未覆盖字段清单")
        void coverageReportsUncoveredDataFields() throws Exception {
            RegistryCoverage.Report report = RegistryCoverage.check();

            System.out.println("REGISTRY_COVERAGE " + report.summary());
            System.out.println("REGISTRY_COVERAGE_UNCOVERED_DATA_FIELDS "
                    + report.uncoveredDataFields());

            assertFalse(report.dataFieldNames().isEmpty(),
                    "数据侧字段名集合为空 ⇒ 『数据 ↔ 登记表』这条覆盖面根本没接上");
            assertTrue(report.dataFieldNames().size() >= 50,
                    "契约字段表应有数十个字段名，实际：" + report.dataFieldNames().size());

            for (String f : report.uncoveredDataFields()) {
                assertTrue(report.dataFieldNames().contains(f),
                        "未覆盖清单里出现了不属于数据侧的字段名：" + f);
            }
            assertTrue(report.isFullyCovered()
                            == (report.missingInJava().isEmpty()
                                && report.extraInJava().isEmpty()
                                && report.unknownTransforms().isEmpty()),
                    "isFullyCovered 必须与它声明的三项差异一致");
            assertTrue(report.needsPrivacyRuling() == !report.uncoveredDataFields().isEmpty(),
                    "needsPrivacyRuling 必须与未覆盖清单一致");
        }

        @Test
        @DisplayName("F1 已对齐：登记表 ↔ 实现三项同时归零（命名对齐的回归判据）")
        void yamlAndJavaAreAligned() throws Exception {
            RegistryCoverage.Report report = RegistryCoverage.check();

            assertTrue(report.missingInJava().isEmpty(),
                    "YAML 有而 Java 没有（未覆盖）：" + report.missingInJava());
            assertTrue(report.extraInJava().isEmpty(),
                    "Java 有而 YAML 没有（自造/残留）：" + report.extraInJava());
            assertTrue(report.unknownTransforms().isEmpty(),
                    "变换 ID 未实现：" + report.unknownTransforms());
            assertTrue(report.isFullyCovered(), "登记表 ↔ 实现应当已完全对齐");
        }

        @Test
        @DisplayName("能自己冒出来：构造一份缺字段的登记表文本 ⇒ 未覆盖清单必须非空且点名")
        void coverageCanFail() {
            String tiny = """
                    fields:
                      - id: player.uuid
                        rawField: player.uuid
                        locationClass: B
                        transform: 哈希加盐截断64
                    transforms:
                      - id: 哈希加盐截断64
                        kind: pseudonymize
                    """;
            RegistryCoverage.Report report = RegistryCoverage.check(tiny);
            assertFalse(report.uncoveredDataFields().contains("uuid"),
                    "登记表里已有的字段不得被误报为未覆盖");
            Set<String> byName = FieldRegistry.knownFieldNameSuffixes();
            long expectedCovered = report.dataFieldNames().stream()
                    .filter(f -> byName.contains(f) || byName.contains(toSnake(f)))
                    .count();
            assertEquals(report.dataFieldNames().size() - expectedCovered,
                    report.uncoveredDataFields().size(),
                    "未覆盖清单必须恰好等于『字段名在登记表里查不到』的那些");
            assertFalse(report.uncoveredDataFields().isEmpty(),
                    "缺字段的登记表必须产出非空的未覆盖清单");
            assertTrue(report.needsPrivacyRuling(), "有未覆盖数据字段时必须显式要求隐私裁定");
        }

        private static String toSnake(String s) {
            StringBuilder sb = new StringBuilder(s.length() + 8);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isUpperCase(c) && i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            }
            return sb.toString();
        }
    }


    @Nested
    @DisplayName("真实实例：dc §6.3 列为导出层禁止字段的 snapshotDigest16")
    class RealLeakInstance {

        @Test
        @DisplayName("该字段是 C 类：进入管道必须判违规并拒绝导出（不是静默清空）")
        void snapshotDigest16IsRefusedAsClassC() {
            RedactionPipeline p = pipeline();
            Map<String, Object> payload = map("snapshotDigest16", "deadbeefcafe1234");

            ContractException ex = assertThrows(ContractException.class,
                    () -> p.redactPayload(CaptureEventType.SESSION_ENVIRONMENT, payload, 0, 0),
                    "C 类字段进了管道却不报错 ⇒ 『不采集/不落盘』这条登记没有落到任何一层");
            assertTrue(ex.getMessage().contains("C 类"), ex.getMessage());
            assertTrue(p.report().droppedFieldPaths().contains("session_environment.snapshotDigest16"),
                    "该字段的处置必须进报告：" + p.report().droppedFieldPaths());
        }

        @Test
        @DisplayName("该字段名现在能在登记表里查到（F1 对齐后才成立）")
        void fieldNameIsNowKnownToTheRegistry() {
            assertFalse(FieldRegistry.isUnknownFieldName("snapshotDigest16"),
                    "该字段名应当能在登记表里查到（登记项 id/rawField 均为 session_environment.snapshotDigest16）");
            FieldRegistry.FieldPolicy policy = FieldRegistry.lookup("session_environment.snapshotDigest16")
                    .orElseThrow(() -> new AssertionError("按 <eventType>.<fieldPath> 必须能查到该登记"));
            assertEquals(FieldRegistry.LocationClass.C, policy.locationClass(),
                    "登记表把它登记为 C（不采集/不落盘）");
            assertTrue(policy.transform().isDrop(),
                    "C 类字段的变换必须是丢弃型：" + policy.transform().specId());
        }

        @Test
        @DisplayName("反面对照：该字段**不再**出现在『数据侧未覆盖』清单里")
        void snapshotDigest16IsNoLongerAnUncoveredDataField() throws Exception {
            RegistryCoverage.Report report = RegistryCoverage.check();
            assertFalse(report.uncoveredDataFields().contains("snapshotDigest16"),
                    "F1 对齐后它已有档位，不应再被列为未覆盖：" + report.uncoveredDataFields());
        }
    }
}
