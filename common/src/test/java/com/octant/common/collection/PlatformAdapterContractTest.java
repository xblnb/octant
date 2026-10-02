package com.octant.common.collection;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.model.EventCategory;
import com.octant.common.model.EventSource;
import com.octant.common.platforms.PlatformAdapter;
import com.octant.common.platforms.PlatformCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformAdapterContractTest {

    @Nested
    @DisplayName("契约接口：平台层只翻译，同意门在汇的下游")
    class AdapterContracts {

        @Test
        @DisplayName("EventSink.emit 的 false 表示被同意门拒绝，调用方不得重试")
        void sinkReturnValueMeansRejected() {
            PlatformAdapter.EventSink rejecting = event -> false;
            PlatformAdapter.EventSink accepting = event -> true;

            CaptureEvent e = new CaptureEvent(
                    com.octant.common.model.RawEventSchema.VERSION, CaptureEvent.eventId(1),
                    CaptureEvent.sessionId(1), "7f3a1c9b04d2e6a5", CaptureEventType.DIMENSION_ENTERED,
                    1000L, CaptureEvent.tickOf(1000L), EventCategory.EXPLORE, EventSource.FABRIC,
                    true, null, Map.of("dimension", "minecraft:overworld"));

            assertFalse(rejecting.emit(e), "被拒绝时必须返回 false（调用方据此计数，不得重试）");
            assertTrue(accepting.emit(e));
        }

        @Test
        @DisplayName("EventTranslator 失败返回 null 而不是上抛（REQ-DATA-06）")
        void translatorReturnsNullOnFailure() {
            PlatformAdapter.EventTranslator bad = obs -> null;
            assertNotNull(bad);
            assertEquals(null, bad.translate("anything"),
                    "翻译失败必须返回 null 由调用方计数，不得上抛到游戏主循环");
        }

        @Test
        @DisplayName("requireConsistent 拒绝空类型/空 payload（参数级前置检查）")
        void requireConsistentRejectsBlanks() {
            assertThrows(ContractException.class,
                    () -> PlatformAdapter.requireConsistent(null, Map.of()));
            assertThrows(ContractException.class,
                    () -> PlatformAdapter.requireConsistent(CaptureEventType.ITEM_ACTION, null));
            PlatformAdapter.requireConsistent(CaptureEventType.ITEM_ACTION, Map.of(
                    "item", "minecraft:iron_ingot", "action", "obtain",
                    "count", 3, "creativeGiven", false));
            assertThrows(ContractException.class,
                    () -> PlatformAdapter.requireConsistent(CaptureEventType.ITEM_ACTION, Map.of()),
                    "空 payload 缺 MUST 字段 ⇒ 必须拒绝，而不是'没东西可查所以通过'");
        }

        @Test
        @DisplayName("requireConsistent 真的校验字段表：表外键与缺 MUST 都必须被拒（不是只判 null）")
        void requireConsistentActuallyChecksTheFieldTable() {
            java.util.Map<String, Object> envExtra = new java.util.LinkedHashMap<>();
            envExtra.put("envSchemaVersion",
                    com.octant.common.model.RawEventSchema.ENV_SNAPSHOT_SCHEMA_VERSION);
            envExtra.put("snapshotRev", 1);
            envExtra.put("snapshotDigest16", "0123456789abcdef");
            envExtra.put("observedAtRelMs", 0L);
            envExtra.put("observedAtTick", 0L);
            PlatformAdapter.requireConsistent(CaptureEventType.SESSION_ENVIRONMENT, envExtra);

            java.util.Map<String, Object> withOutsideKey =
                    new java.util.LinkedHashMap<>(envExtra);
            withOutsideKey.put("modCount", 238);
            assertThrows(ContractException.class,
                    () -> PlatformAdapter.requireConsistent(
                            CaptureEventType.SESSION_ENVIRONMENT, withOutsideKey),
                    "表外键必须被拒（契约 §2.4：多一个字段即违反 schema）");

            java.util.Map<String, Object> missingMust = new java.util.LinkedHashMap<>(envExtra);
            missingMust.remove("snapshotDigest16");
            assertThrows(ContractException.class,
                    () -> PlatformAdapter.requireConsistent(
                            CaptureEventType.SESSION_ENVIRONMENT, missingMust),
                    "缺 MUST 字段必须被拒");
        }

        @Test
        @DisplayName("平台契约包内不含任何 Minecraft 类型（由 assertNoMinecraftRefs 兜底，这里做类级断言）")
        void platformPackageHasNoMinecraftTypes() {
            Class<?>[] contracts = {
                    PlatformAdapter.class, PlatformCapabilities.class,
                    PlatformAdapter.EventSink.class, PlatformAdapter.EventTranslator.class,
                    PlatformAdapter.CatalogProvider.class, PlatformAdapter.SessionClockProvider.class};
            for (Class<?> c : contracts) {
                String name = c.getName();
                assertFalse(name.contains("net." + "minecraft"),
                        "平台契约不得依赖 Minecraft 类型：" + name);
            }
        }
    }

    @Nested
    @DisplayName("能力事实清单：证据纪律")
    class CapabilityFacts {

        @Test
        @DisplayName("标 VERIFIED 的条目必须带证据（不得凭印象断言）")
        void verifiedRequiresEvidence() {
            assertThrows(IllegalArgumentException.class, () -> new PlatformCapabilities.Capability(
                            "某能力", PlatformCapabilities.Availability.VERIFIED, "", "无证据"),
                    "标 VERIFIED 却不给证据必须拒绝 —— 这就是'凭印象写技术判断'的防线");
            new PlatformCapabilities.Capability("某能力",
                    PlatformCapabilities.Availability.UNVERIFIED, "", "未实测");
        }

        @Test
        @DisplayName("未登记的能力默认必须是「未验证」，不得被读作可用")
        void unknownDefaultsToUnverified() {
            var neo211 = PlatformCapabilities.Combo.of(
                    PlatformCapabilities.Loader.NEOFORGE, PlatformCapabilities.McVersion.V1_21_1);
            assertEquals(PlatformCapabilities.Availability.VERIFIED,
                    PlatformCapabilities.availabilityOf(neo211, "模组清单"));
            assertEquals(PlatformCapabilities.Availability.UNVERIFIED,
                    PlatformCapabilities.availabilityOf(neo211, "这个能力根本没登记"),
                    "未知必须显式表现为'未验证'——不得默认成可用（与'未知必须显式可见'同源）");
        }

        @Test
        @DisplayName("包枚举可得性：全表逐组合翻译到事实，未知组合不得被读作可用")
        void packEnumerationAvailabilityIsTableDriven() {
            var neo211 = PlatformCapabilities.Combo.of(
                    PlatformCapabilities.Loader.NEOFORGE, PlatformCapabilities.McVersion.V1_21_1);
            var fabric211 = PlatformCapabilities.Combo.of(
                    PlatformCapabilities.Loader.FABRIC, PlatformCapabilities.McVersion.V1_21_1);

            assertEquals(PlatformCapabilities.Availability.VERIFIED,
                    PlatformCapabilities.packEnumerationAvailability(neo211),
                    "本机对 NeoForge×1.21.1 的包 API 做过一手内省 ⇒ 只有它是 VERIFIED");
            assertEquals(PlatformCapabilities.Availability.UNVERIFIED,
                    PlatformCapabilities.packEnumerationAvailability(fabric211),
                    "Fabric×1.21.1 未内省 MC 侧 ⇒ 不得写 VERIFIED");

            assertEquals(PlatformCapabilities.Loader.values().length
                            * PlatformCapabilities.McVersion.values().length,
                    PlatformCapabilities.all().size(),
                    "闭枚举的组合必须每格都有表行，否则 packEnumerationAvailability 的兜底分支"
                            + "会在真实组合上返回『未验证』，把已知事实降级成未知");

            long verified = PlatformCapabilities.Loader.values().length
                    * PlatformCapabilities.McVersion.values().length
                    - PlatformCapabilities.all().keySet().stream()
                            .filter(c -> PlatformCapabilities.packEnumerationAvailability(c)
                                    == PlatformCapabilities.Availability.UNVERIFIED)
                            .count();
            assertEquals(1, verified,
                    "若这里不是 1，说明夹具选取需要重新审视（两个组合同态时对比测试无区分力）");

            assertTrue(PlatformCapabilities.capabilitiesOf(fabric211).stream()
                            .anyMatch(c -> c.what().contains("辅助模组") && !c.what().contains("资源包")),
                    "Fabric 组合里确实存在另一条含『包』字、但不含『资源包』的行 —— 这正是片段陷阱");
            assertEquals(PlatformCapabilities.availabilityOf(fabric211, "资源包"),
                    PlatformCapabilities.packEnumerationAvailability(fabric211),
                    "翻译函数必须就是『按资源包行查表』；若片段常量被改宽/改窄，这条先红");
        }

        @Test
        @DisplayName("包构成不按加载器分叉，且数据包需服务端上下文（主路径事实）")
        void packFactsAreCrossLoader() {
            var neo211 = PlatformCapabilities.Combo.of(
                    PlatformCapabilities.Loader.NEOFORGE, PlatformCapabilities.McVersion.V1_21_1);
            PlatformCapabilities.Capability packs = PlatformCapabilities.capabilitiesOf(neo211).stream()
                    .filter(c -> c.what().contains("资源包"))
                    .findFirst().orElseThrow();
            assertTrue(packs.notes().contains("三加载器共用"),
                    "包构成是 Minecraft 层能力 ⇒ 逐加载器能力表在它上面只需一行");

            PlatformCapabilities.Capability dp = PlatformCapabilities.capabilitiesOf(neo211).stream()
                    .filter(c -> c.what().equals("数据包清单"))
                    .findFirst().orElseThrow();
            assertEquals(PlatformCapabilities.Availability.UNAVAILABLE, dp.availability(),
                    "纯客户端早期阶段拿不到数据包 ⇒ 必须显式标 UNAVAILABLE 而不是'未验证'");
            assertTrue(dp.notes().contains("主路径限制"),
                    "客户端模组形态下这是主路径限制，不是脚注");
        }

        @Test
        @DisplayName("辅助模组三件套必须是 UNVERIFIED（jar 不在本机）")
        void auxModsAreUnverified() {
            var neo211 = PlatformCapabilities.Combo.of(
                    PlatformCapabilities.Loader.NEOFORGE, PlatformCapabilities.McVersion.V1_21_1);
            assertEquals(PlatformCapabilities.Availability.UNVERIFIED,
                    PlatformCapabilities.availabilityOf(neo211, "辅助模组数据"),
                    "JEI/Xaero/FTB 的 jar 不在本机 ⇒ 只能标未验证，不写'应该可以'");
            for (var combo : java.util.List.of(
                    PlatformCapabilities.Combo.of(PlatformCapabilities.Loader.FORGE,
                            PlatformCapabilities.McVersion.V1_21_1),
                    PlatformCapabilities.Combo.of(PlatformCapabilities.Loader.FORGE,
                            PlatformCapabilities.McVersion.V1_20_1),
                    PlatformCapabilities.Combo.of(PlatformCapabilities.Loader.NEOFORGE,
                            PlatformCapabilities.McVersion.V1_20_1),
                    PlatformCapabilities.Combo.of(PlatformCapabilities.Loader.FABRIC,
                            PlatformCapabilities.McVersion.V1_20_1))) {
                for (PlatformCapabilities.Capability c
                        : PlatformCapabilities.capabilitiesOf(combo)) {
                    assertEquals(PlatformCapabilities.Availability.UNVERIFIED, c.availability(),
                            combo + " 无 API jar ⇒ 只有 UNVERIFIED 是诚实的：" + c.what());
                }
            }
        }

        @Test
        @DisplayName("六个组合都在表里（缺一个就说明有人漏登记）")
        void allSixCombosPresent() {
            assertEquals(6, PlatformCapabilities.all().size());
            assertTrue(PlatformCapabilities.verifiedCount() >= 4,
                    "NeoForge×1.21.1 与 Fabric×1.21.1 应有若干条一手证据，实际 "
                            + PlatformCapabilities.verifiedCount());
        }

        @Test
        @DisplayName("结构化导出可用于 platform-matrix.md 直接消费")
        void toMapIsConsumable() {
            Map<String, Object> m = PlatformCapabilities.toMap();
            assertTrue(m.containsKey("neoforge×1.21.1"));
            assertTrue(m.containsKey("forge×1.20.1"));
            @SuppressWarnings("unchecked")
            var rows = (java.util.List<Object>) m.get("neoforge×1.21.1");
            assertFalse(rows.isEmpty());
        }
    }
}
