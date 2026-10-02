package com.octant.common.collection;

import com.octant.common.model.ContractException;
import com.octant.common.model.Json;
import com.octant.common.privacy.PackSnapshot;
import com.octant.common.platforms.PlatformCapabilities;
import com.octant.common.platforms.PlatformCapabilities.Availability;
import com.octant.common.privacy.SaltProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackSnapshotTest {

    private static final String RAW_RP_NAME = "Notch 的手绘材质包 v3";
    private static final String RAW_DP_NAME = "acme_superpack-custom-recipes";

    private static final PlatformCapabilities.Combo CAN_ENUMERATE =
            PlatformCapabilities.Combo.of(PlatformCapabilities.Loader.NEOFORGE,
                    PlatformCapabilities.McVersion.V1_21_1);

    private static final PlatformCapabilities.Combo CANNOT_ENUMERATE =
            PlatformCapabilities.Combo.of(PlatformCapabilities.Loader.FABRIC,
                    PlatformCapabilities.McVersion.V1_21_1);

    private static PackSnapshot fresh() {
        return new PackSnapshot(SaltProvider.generate()).setEnumerationAvailable(CAN_ENUMERATE);
    }

    private static PackSnapshot enumerationUnavailable() {
        return new PackSnapshot(SaltProvider.generate()).setEnumerationAvailable(CANNOT_ENUMERATE);
    }

    @Test
    @DisplayName("前置：能/不能枚举的两个组合，在事实表里必须真的分处两态")
    void fixtureCombosAreGenuinelyInDifferentStates() {
        assertEquals(Availability.VERIFIED,
                PlatformCapabilities.packEnumerationAvailability(CAN_ENUMERATE),
                "CAN_ENUMERATE 必须是 VERIFIED，否则所有『能力可得』用例都在测一个不可枚举对象");
        assertEquals(Availability.UNVERIFIED,
                PlatformCapabilities.packEnumerationAvailability(CANNOT_ENUMERATE),
                "CANNOT_ENUMERATE 必须是 UNVERIFIED（未实测不得写 VERIFIED）");
        assertNotEquals(PlatformCapabilities.packEnumerationAvailability(CAN_ENUMERATE),
                PlatformCapabilities.packEnumerationAvailability(CANNOT_ENUMERATE),
                "两个夹具若同态，整组对比测试就没有区分力");
    }

    @Test
    @DisplayName("置位只吃平台身份：VERIFIED 开，UNVERIFIED/UNAVAILABLE 一律关")
    void availabilityOnlyVerified() {
        assertTrue(fresh().enumerationAvailable(), "VERIFIED ⇒ 开");
        assertFalse(enumerationUnavailable().enumerationAvailable(), "UNVERIFIED ⇒ 关");
        assertFalse(new PackSnapshot(SaltProvider.generate())
                .setEnumerationAvailable(PlatformCapabilities.Combo.of(
                        PlatformCapabilities.Loader.FORGE, PlatformCapabilities.McVersion.V1_20_1))
                .enumerationAvailable(), "第四个组合（无 jar、未实测）⇒ 关");
    }

    @Test
    @DisplayName("适配器无法自行裁定：唯一的公开置位点只收组合，且 null 组合直接抛")
    void adapterCannotSelfJudge() throws Exception {
        for (java.lang.reflect.Method m : PackSnapshot.class.getMethods()) {
            if (!m.getName().equals("setEnumerationAvailable")) {
                continue;
            }
            assertEquals(1, m.getParameterCount(), "置位点应恰好只有一个参数");
            assertEquals(PlatformCapabilities.Combo.class, m.getParameterTypes()[0],
                    "参数必须是平台身份（Combo）；"
                            + "一旦退回 boolean/Availability，适配器就能自述能力、判断可被绕过");
        }
        assertThrows(NullPointerException.class,
                () -> new PackSnapshot(SaltProvider.generate()).setEnumerationAvailable(null),
                "null 组合不得被静默降级成『不可用』——那是把一个开发期缺陷伪装成平台事实");
    }

    @Nested
    @DisplayName("包标识：只用内容哈希，不落任何名称")
    class IdentifiersAreContentDerived {

        @Test
        @DisplayName("packId 形如 rp#/dp# + 16 位十六进制（定向断言 2）")
        void packIdShape() {
            PackSnapshot s = fresh();
            String rp = s.addResourcePack("hash-of-resourcepack-a", 120, 4_096_000L).packId();
            String dp = s.addDataPack("hash-of-datapack-b", 40, 512_000L).packId();
            assertTrue(rp.matches("rp#[0-9a-f]{16}"), "实际：" + rp);
            assertTrue(dp.matches("dp#[0-9a-f]{16}"), "实际：" + dp);
        }

        @Test
        @DisplayName("同一内容哈希 → 同假名（可做'同一包'关联）；不同盐 → 不可关联")
        void sameContentSamePseudonym() {
            SaltProvider salt = SaltProvider.generate();
            String a = new PackSnapshot(salt).addResourcePack("same-content", 1, 1L).packId();
            String b = new PackSnapshot(salt).addResourcePack("same-content", 1, 1L).packId();
            String c = new PackSnapshot(SaltProvider.generate()).addResourcePack("same-content", 1, 1L).packId();
            assertEquals(a, b, "同一存档内相同内容必须得到相同假名");
            assertTrue(!a.equals(c), "换盐后不可关联");
        }

        @Test
        @DisplayName("用途分离：同一输入在 rp#/dp#/set/mod 用途下必须得到不同的假名")
        void purposesAreSeparated() {
            SaltProvider salt = SaltProvider.generate();
            String rp = salt.pseudonym("resourcepack", "x");
            String dp = salt.pseudonym("datapack", "x");
            String set = salt.pseudonym("set", "x");
            String mod = salt.pseudonym("mod", "x");
            assertEquals(4, java.util.Set.of(rp, dp, set, mod).size(),
                    "四类用途必须互不相同，否则跨用途可关联");
            assertThrows(ContractException.class, () -> salt.pseudonym("", "x"),
                    "空用途标签必须拒绝（用途分离是强制项）");
        }

        @Test
        @DisplayName("空内容哈希必须拒绝：包标识只能由内容派生，不得由名称派生")
        void rejectsEmptyContentHash() {
            PackSnapshot s = fresh();
            assertThrows(ContractException.class, () -> s.addResourcePack("", 1, 1L));
            assertThrows(ContractException.class, () -> s.addDataPack(null, 1, 1L));
        }
    }

    @Nested
    @DisplayName("产物不含任何包名原文（定向断言 1）")
    class NoRawNamesLeak {

        @Test
        @DisplayName("落盘形态与导出形态都不含包名/路径/自由文本")
        void neitherFormContainsNames() {
            PackSnapshot s = fresh();
            s.addResourcePack("content-hash-rp", 10, 100L);
            s.addDataPack("content-hash-dp", 5, 50L);

            for (String form : List.of(Json.encode(s.toMap()),
                    Json.encode(s.toExportForm(9, true)))) {
                for (String raw : List.of(RAW_RP_NAME, RAW_DP_NAME, "Notch", "acme_superpack",
                        "resourcepacks/", ".zip", "assets/", "pack.mcmeta")) {
                    assertFalse(form.contains(raw), "产物不得包含：" + raw);
                }
            }
        }

        @Test
        @DisplayName("采集期绝不落盘的字段清单是显式的（供审计引用）")
        void neverCollectedFieldsAreExplicit() {
            List<String> never = PackSnapshot.neverCollectedFields();
            assertTrue(never.contains("fileName"));
            assertTrue(never.contains("displayName"));
            assertTrue(never.contains("description"));
            assertTrue(never.contains("icon"));
        }

        @Test
        @DisplayName("mod 许可规则（modPermission 单条规则）：T1 出 id@version 且**不出哈希**；T3/T4 只出截断哈希且**不出 modid**")
        void unifiedModPermissionRule() {
            PackSnapshot s = fresh();
            s.addMod(PackSnapshot.SourceCategory.T1_PUBLIC_VERIFIED, "jei", null, "15.2.0.27");
            String jsonT1 = Json.encode(s.toMap());
            assertTrue(jsonT1.contains("\"modId\":\"jei\""));
            assertFalse(jsonT1.contains("modHash"), "T1 不得输出内容哈希（会增加跨导出可链接面）");
            assertThrows(ContractException.class, () -> fresh().addMod(
                            PackSnapshot.SourceCategory.T1_PUBLIC_VERIFIED, "jei", "some-content-hash", "15.2.0"),
                    "T1 传了内容哈希必须拒绝，而不是静默忽略");

            PackSnapshot t3 = fresh();
            t3.addMod(PackSnapshot.SourceCategory.T3_CUSTOM_SOURCE, null, "private-mod-content-hash", "2.3.1");
            String jsonT3 = Json.encode(t3.toMap());
            assertTrue(jsonT3.contains("modHash"));
            assertFalse(jsonT3.contains("modId"), "T3 绝不输出 modid");
            assertTrue(jsonT3.contains("\"sourceCategory\":\"T3\""), "必须携带来源类别，供机械判定");
            assertThrows(ContractException.class, () -> fresh().addMod(
                            PackSnapshot.SourceCategory.T3_CUSTOM_SOURCE, "private:mod", "h", "1"),
                    "T3 传了 modid 必须拒绝");

            assertThrows(ContractException.class, () -> PackSnapshot.representationOf(
                    PackSnapshot.SourceCategory.T2_PUBLIC_UNVERIFIED, PackSnapshot.PackKind.MOD, false));
        }

        @Test
        @DisplayName("形态分派表与 14 行清单一致（不降级、不视情况而定）")
        void representationDispatchMatchesRegistry() {
            assertEquals(PackSnapshot.RepresentationForm.MOD_ID_VERBATIM,
                    PackSnapshot.representationOf(PackSnapshot.SourceCategory.T1_PUBLIC_VERIFIED,
                            PackSnapshot.PackKind.MOD, false));
            assertEquals(PackSnapshot.RepresentationForm.MOD_CONTENT_HASH,
                    PackSnapshot.representationOf(PackSnapshot.SourceCategory.T4_SELF_AUTHORED,
                            PackSnapshot.PackKind.MOD, false));
            assertEquals(PackSnapshot.RepresentationForm.DATAPACK_BUILTIN,
                    PackSnapshot.representationOf(PackSnapshot.SourceCategory.T5_BUILTIN,
                            PackSnapshot.PackKind.DATA, true));
            assertEquals("dp#builtin", PackSnapshot.BUILTIN_DATAPACK_ID);
            assertFalse(PackSnapshot.RepresentationForm.SET_CLASS.requiresGroupThreshold(),
                    "降级形态不含标识，因此无阈值");
            assertTrue(PackSnapshot.RepresentationForm.SET_FINGERPRINT.requiresGroupThreshold());
            Map<String, Object> un = PackSnapshot.unavailableEntry("datapacks");
            assertEquals("unavailable", un.get("status"));
            assertEquals("SOURCE_UNAVAILABLE_ON_PLATFORM", un.get("reasonCode"));
        }

        @Test
        @DisplayName("反向对照：改一个包的内容，digest 必须变（否则'不含包名'可能只是因为 digest 对什么都不敏感）")
        void digestIsSensitiveToContent() {
            PackSnapshot a = fresh();
            String idA = a.addResourcePack("content-hash-v1", 3, 300L).packId();
            PackSnapshot b = fresh();
            SaltProvider shared = SaltProvider.generate();
            String first = new PackSnapshot(shared).addResourcePack("content-v1", 3, 300L).packId();
            String second = new PackSnapshot(shared).addResourcePack("content-v2", 3, 300L).packId();
            assertTrue(first.startsWith("rp#") && second.startsWith("rp#"));
            assertFalse(first.equals(second),
                    "内容变了 digest 必须变；若不变，说明 digest 对内容不敏感（" + idA + " 仅用于确保未抛错）");
        }

        @Test
        @DisplayName("modId 含空白/控制字符（自由文本）必须拒绝，不得静默保留")
        void freeTextModIdRejected() {
            PackSnapshot s = fresh();
            assertThrows(ContractException.class, () -> s.addMod(
                    PackSnapshot.SourceCategory.T1_PUBLIC_VERIFIED, "my mod with spaces", null, "1.0"));
            assertThrows(ContractException.class, () -> s.addMod(
                    PackSnapshot.SourceCategory.T1_PUBLIC_VERIFIED, "x".repeat(129), null, "1.0"));
        }
    }

    @Nested
    @DisplayName("组合指纹：nPlayers < 5 必须抑制且字段不出现（定向断言 3）")
    class FingerprintSuppression {

        @Test
        @DisplayName("contributingPlayers = 1 时 setFingerprint 不出现，且带 reasonCode 与 RULE-3")
        void suppressedForSinglePlayer() {
            PackSnapshot s = fresh();
            s.addResourcePack("h1", 1, 1L);
            s.addDataPack("h2", 1, 1L);

            assertNull(s.setFingerprintOrNull(1), "单玩家时组合指纹必须被抑制");
            Map<String, Object> exp = s.toExportForm(1, true);
            assertFalse(exp.containsKey("setFingerprint"),
                    "抑制时该字段必须**不出现**（不是 null、不是空串）");
            @SuppressWarnings("unchecked")
            Map<String, Object> sup = (Map<String, Object>) exp.get("setFingerprintSuppressed");
            assertNotNull(sup);
            assertEquals("suppressed", sup.get("status"));
            assertEquals("INSUFFICIENT_GROUP_SIZE", sup.get("reasonCode"));
            assertEquals("RULE-3", sup.get("suppressedByRule"));
        }

        @Test
        @DisplayName("边界：4 抑制、5 输出；输出时形态为 16 位十六进制且顺序无关")
        void boundaryAtFive() {
            SaltProvider sharedSalt = SaltProvider.generate();
            PackSnapshot s = new PackSnapshot(sharedSalt);
            s.addResourcePack("h1", 1, 1L);
            s.addDataPack("h2", 1, 1L);
            assertNull(s.setFingerprintOrNull(4), "4 < 5 必须抑制");
            String fp = s.setFingerprintOrNull(5);
            assertNotNull(fp, "5 >= 5 才允许输出");
            assertTrue(fp.matches("[0-9a-f]{16}"), "实际：" + fp);

            PackSnapshot reversed = new PackSnapshot(sharedSalt);
            reversed.addDataPack("h2", 1, 1L);
            reversed.addResourcePack("h1", 1, 1L);
            assertEquals(fp, reversed.setFingerprintOrNull(5), "指纹必须与登记顺序无关");
        }

        @Test
        @DisplayName("组合被抑制时仍给低定向性上下文：setClass 不含具体标识，单玩家也允许输出")
        void setClassAlwaysAvailable() {
            PackSnapshot s = fresh();
            s.addResourcePack("h1", 1, 1L);
            Map<String, Object> exp = s.toExportForm(1, true);
            @SuppressWarnings("unchecked")
            Map<String, Object> cls = (Map<String, Object>) exp.get("setClass");
            assertEquals("custom_packs_present", cls.get("kind"));
            assertEquals("1_10", cls.get("sizeBucket"));
            assertEquals("custom_packs_present", s.setKind(true).wire());
            assertEquals("public_addons_only", s.setKind(false).wire());
        }

        @Test
        @DisplayName("⑭⑬ 纯客户端：「观测不到」不得落成 vanilla_only（privacy-model §4.4.8 / AN-REG-08）")
        void enumerationUnavailableNeverClaimsVanillaOnly() {
            PackSnapshot unavailable = enumerationUnavailable();
            assertEquals("unknown", unavailable.setKind(false).wire(),
                    "能力不可得 ⇒ 必须 unknown，不得回落到 vanilla_only");
            assertEquals("unknown", unavailable.setKind(true).wire(),
                    "即使观测到『有自定义包』的迹象，能力不可得也仍须 unknown（fail-closed）");

            @SuppressWarnings("unchecked")
            Map<String, Object> exp = unavailable.toExportForm(1, true);
            @SuppressWarnings("unchecked")
            Map<String, Object> cls = (Map<String, Object>) exp.get("setClass");
            assertEquals("unknown", cls.get("kind"), "落盘形态同样不得谎报 vanilla_only");
            assertNotNull(cls.get("enumerationUnavailable"),
                    "能力不可得必须在落盘形态里显式可见，而不是用一个空列表代替");

            PackSnapshot available = fresh();
            assertEquals("vanilla_only", available.setKind(false).wire(),
                    "能力可得且确实为空 ⇒ 才是真的 vanilla_only");
        }

        @Test
        @DisplayName("规模桶闭集：0 / 1_10 / 11_30 / 31_60 / 61_plus")
        void sizeBucketsAreClosedSet() {
            assertEquals("0", PackSnapshot.sizeBucket(0));
            assertEquals("1_10", PackSnapshot.sizeBucket(10));
            assertEquals("11_30", PackSnapshot.sizeBucket(11));
            assertEquals("31_60", PackSnapshot.sizeBucket(60));
            assertEquals("61_plus", PackSnapshot.sizeBucket(61));
        }

    }
}
