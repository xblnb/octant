package com.octant.common.collection;

import com.octant.common.model.ContractException;
import com.octant.common.model.Json;
import com.octant.common.privacy.SaltProvider;
import com.octant.common.privacy.adapter.ConsentGate;
import com.octant.common.privacy.adapter.ConsentState;
import com.octant.common.privacy.adapter.ConsentStore;
import com.octant.common.privacy.adapter.DataEraser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsentAndErasureTest {

    private static final String INSTANCE = "pi_3f7a91c4";

    private static String currentPrivacySpecVersion() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve("docs").resolve("privacy").resolve("SPEC-VERSION.txt");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8).trim();
            }
            dir = dir.getParent();
        }
        throw new AssertionError("未找到 docs/privacy/SPEC-VERSION.txt —— 版本交叉核对无法进行，"
                + "拒绝退回硬编码值");
    }

    private static Path fixture(String name) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve("docs").resolve("privacy").resolve("contract-tests").resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return Path.of("docs", "privacy", "contract-tests", name);
    }

    @Nested
    @DisplayName("默认不采集（fail-closed，INV-3）")
    class FailClosed {

        @Test
        @DisplayName("全新状态：collection 关闭、无类别、门返回 false")
        void defaultDenied() {
            ConsentState denied = ConsentState.denied();
            assertFalse(denied.collectionEnabled(), "默认必须不采集");
            assertTrue(denied.collectionCategories().isEmpty());
            assertFalse(denied.isRevoked());

            ConsentGate gate = new ConsentGate();
            assertFalse(gate.isCollecting());
            assertFalse(gate.assertCollecting(), "同意门默认必须拒绝写入");
            assertFalse(gate.allowsCategory("C1"));
        }

        @Test
        @DisplayName("缺失键按 false 处理；consentVersion 与权威 SPEC-VERSION.txt 交叉核对")
        void missingKeysTreatedAsFalse() throws IOException {
            String specVersion = currentPrivacySpecVersion();
            assertTrue(specVersion.matches("privacy-spec@\\d+\\.\\d+\\.\\d+"),
                    "SPEC-VERSION.txt 必须是单行 privacy-spec@X.Y.Z，实际：" + specVersion);
            assertTrue(specVersion.startsWith(ConsentState.CONSENT_VERSION_PREFIX),
                    "ConsentState 的前缀 " + ConsentState.CONSENT_VERSION_PREFIX
                            + " 必须与当前权威版本 " + specVersion + " 同 major");

            ConsentState s = ConsentState.fromJson("{\"schemaVersion\":2,\"privacyInstanceId\":\"pi_3f7a91c4\","
                    + "\"consentVersion\":\"" + specVersion + "\"}");
            assertFalse(s.collectionEnabled(), "缺失 collection 必须按 false 处理");
            assertTrue(s.collectionCategories().isEmpty());
        }

        @Test
        @DisplayName("未知 schemaVersion 必须拒绝（fail-closed），不得静默当已同意")
        void unknownSchemaVersionRejected() {
            ContractException ex = assertThrows(ContractException.class, () -> ConsentState.fromJson(
                    "{\"schemaVersion\":99,\"collection\":{\"enabled\":true,\"categories\":{\"C1\":true}}}"));
            assertTrue(ex.getMessage().contains("99"), "错误必须回显实际版本：" + ex.getMessage());
        }

        @Test
        @DisplayName("enabled=true 但类别全为 false、或 enabled=false 却残留 true，均不得启用采集")
        void inconsistentEnabledCategoriesFailClosed() {
            ConsentState a = ConsentState.fromJson(
                    "{\"schemaVersion\":2,\"collection\":{\"enabled\":true,"
                            + "\"categories\":{\"C1\":false,\"C2\":false}}}");
            assertFalse(a.collectionEnabled(), "没有任何类别被授予时不得采集");
            assertTrue(a.collectionCategories().isEmpty());

            ConsentState b = ConsentState.fromJson(
                    "{\"schemaVersion\":2,\"collection\":{\"enabled\":false,"
                            + "\"categories\":{\"C1\":true,\"C2\":false}}}");
            assertFalse(b.collectionEnabled(), "关闭总闸时不得有任何类别生效");
            assertTrue(b.collectionCategories().isEmpty(), "关闭态不得残留已授予类别");

            assertThrows(ContractException.class, () -> ConsentState.granted(
                    INSTANCE, Set.of(), "2026-09-26", 1L, 1));
            assertThrows(ContractException.class, () -> ConsentState.denied().revoked(null));
        }

        @Test
        @DisplayName("读取契约 fixture：initial-fail-closed 必须解析为全关态")
        void parsesFailClosedFixture() throws IOException {
            Path f = fixture("consent.initial-fail-closed.json");
            org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(f),
                    "契约 fixture 不在工作区，跳过跨文档核对");
            ConsentState s = ConsentState.fromJson(Files.readString(f, StandardCharsets.UTF_8));
            assertFalse(s.collectionEnabled(), "fixture 期望 collection.enabled=false");
            assertTrue(s.collectionCategories().isEmpty(), "fixture 期望全部类别为 false");
            expectAll(CATEGORIES, false, s);
            assertEquals(268_435_456L, s.retentionMaxBytes());
            assertEquals(180, s.retentionMaxAgeDays());
        }

        @Test
        @DisplayName("未同意时零产出：assertCollecting 为 false 且丢弃被计数")
        void zeroOutputBeforeConsent() {
            ConsentGate gate = new ConsentGate();
            assertEquals(0L, gate.droppedByConsent());
            int attempted = 0;
            for (int i = 0; i < 5; i++) {
                if (!gate.assertCollecting()) {
                    gate.recordDroppedByConsent();
                } else {
                    attempted++;
                }
            }
            assertEquals(0, attempted, "未同意时不得有任何一次写入被放行");
            assertEquals(5L, gate.droppedByConsent(), "丢弃必须计数，不得静默按 0 处理");
        }
    }

    private static final java.util.List<String> CATEGORIES = ConsentState.CATEGORY_IDS;

    private static void expectAll(java.util.List<String> ids, boolean value, ConsentState state) {
        for (String id : ids) {
            assertEquals(value, state.allowsCategory(id), "类别 " + id + " 期望 " + value);
        }
    }

    @Nested
    @DisplayName("显式同意后才采集")
    class ExplicitGrant {

        @Test
        @DisplayName("grant 后门开启，且仅授予的类别可用")
        void grantOpensOnlyGrantedCategories() {
            ConsentGate gate = new ConsentGate();
            gate.grant(INSTANCE, Set.of("C1", "C2", "C5"), 268_435_456L, 180);
            assertTrue(gate.isCollecting());
            assertTrue(gate.assertCollecting());
            assertTrue(gate.allowsCategory("C1"));
            assertTrue(gate.allowsCategory("C5"));
            assertFalse(gate.allowsCategory("C4"), "未授予的类别必须仍被拒绝");
        }

        @Test
        @DisplayName("空类别/未知类别的同意必须被拒绝（不得以空集开启）")
        void emptyOrUnknownCategoriesRejected() {
            ConsentGate gate = new ConsentGate();
            assertThrows(ContractException.class,
                    () -> gate.grant(INSTANCE, Set.of(), 268_435_456L, 180));
            assertThrows(ContractException.class,
                    () -> gate.grant(INSTANCE, Set.of("C9"), 268_435_456L, 180));
            assertFalse(gate.isCollecting(), "被拒绝的授予不得改变状态");
        }

        @Test
        @DisplayName("grantedAtDate 是日粒度 UTC 日期")
        void grantedDateIsDayGranularity() {
            ConsentGate gate = new ConsentGate(ConsentState.DENIED, () -> 1_772_000_000_000L);
            ConsentState s = gate.grant(INSTANCE, Set.of("C1"), 268_435_456L, 180);
            assertNotNull(s.grantedAtDate());
            assertTrue(s.grantedAtDate().matches("\\d{4}-\\d{2}-\\d{2}"),
                    "必须是 YYYY-MM-DD，实际 " + s.grantedAtDate());
        }
    }

    @Nested
    @DisplayName("撤回：立即停止采集")
    class Revocation {

        @Test
        @DisplayName("撤回后门关闭、四态语义全 true、不追溯")
        void revokeStopsCollection() {
            ConsentGate gate = new ConsentGate(ConsentState.DENIED, () -> 1_790_000_000_000L);
            gate.grant(INSTANCE, Set.of("C1", "C2"), 268_435_456L, 180);
            assertTrue(gate.isCollecting());

            ConsentState revoked = gate.revoke();
            assertFalse(gate.isCollecting(), "撤回后必须立即停止采集（同一次会话内生效）");
            assertFalse(gate.assertCollecting());
            assertTrue(revoked.isRevoked());
            assertTrue(revoked.revocation().isCompliant(), "四项语义必须全为 true");
            assertTrue(revoked.revocation().collectionStopped());
            assertTrue(revoked.revocation().exportDisabled());
            assertTrue(revoked.revocation().notRetroactive());
            assertTrue(revoked.revocation().retroactiveNoticeAcknowledged());
        }

        @Test
        @DisplayName("撤回后丢弃继续计数，且不会因类别仍在而放行")
        void revokedDiscardsAndCounts() {
            ConsentGate gate = new ConsentGate();
            gate.grant(INSTANCE, Set.of("C1"), 268_435_456L, 180);
            gate.revoke();
            assertFalse(gate.assertCollecting());
            gate.recordDroppedByConsent();
            assertEquals(1L, gate.droppedByConsent());
            assertFalse(gate.allowsCategory("C1"), "撤回后任何类别都不得放行");
            assertTrue(gate.grantedCategories().isEmpty());
        }

        @Test
        @DisplayName("撤回不写 null 对象：未撤回时 revocation 必须整体省略")
        void revocationOmittedWhenNotRevoked() {
            ConsentState granted = ConsentState.granted(INSTANCE, Set.of("C1"), "2026-09-26",
                    268_435_456L, 180);
            assertNull(granted.revocation());
            assertFalse(granted.toJson().contains("revocation"),
                    "未撤回时 JSON 中不得出现 revocation 键（更不得为 null）");
        }

        @Test
        @DisplayName("读取契约 fixture：revoked 必须解析为已撤回且四态全 true")
        void parsesRevokedFixture() throws IOException {
            Path f = fixture("consent.revoked.json");
            org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(f),
                    "契约 fixture 不在工作区，跳过跨文档核对");
            ConsentState s = ConsentState.fromJson(Files.readString(f, StandardCharsets.UTF_8));
            assertTrue(s.isRevoked());
            assertTrue(s.revocation().isCompliant());
            assertFalse(s.collectionEnabled(), "已撤回 → 不得采集");
            assertEquals("2026-09-26", s.revocation().revokedAtDate());
        }
    }

    @Nested
    @DisplayName("状态持久化（原子写 + fail-closed 读）")
    class Persistence {

        @Test
        @DisplayName("round-trip 保真，镜像同步，文件缺失读回默认不采集")
        void roundTripAndFailClosedLoad(@TempDir Path gameDir, @TempDir Path worldDir) throws IOException {
            ConsentStore store = new ConsentStore(gameDir, worldDir);
            assertFalse(store.load().collectionEnabled(), "文件缺失必须 fail-closed 为不采集");

            ConsentState granted = ConsentState.granted(INSTANCE, Set.of("C1", "C6"),
                    "2026-09-26", 268_435_456L, 180);
            store.save(granted);

            ConsentState loaded = store.load();
            assertTrue(loaded.collectionEnabled());
            assertEquals(granted, loaded, "round-trip 必须逐字段保真");
            assertTrue(Files.isRegularFile(store.mirrorFile()), "必须写出 meta/consent.json 镜像");

            try (var list = Files.list(store.privacyFile().getParent())) {
                assertTrue(list.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                        "原子改名后不得残留 .tmp");
            }
        }

        @Test
        @DisplayName("状态文件损坏时按 fail-closed 处理，不得抛出到游戏主循环")
        void corruptFileFailsClosed(@TempDir Path gameDir, @TempDir Path worldDir) throws IOException {
            ConsentStore store = new ConsentStore(gameDir, worldDir);
            Files.createDirectories(store.privacyFile().getParent());
            Files.writeString(store.privacyFile(), "{ 这不是合法 JSON ", StandardCharsets.UTF_8);
            assertFalse(store.load().collectionEnabled(), "不可解析必须退化为不采集");
        }

        @Test
        @DisplayName("磁盘上「总闸关闭却残留类别开关」必须 fail-closed：不得存在绕过同意的路径")
        void tamperedFileWithStaleCategoriesCannotBypassConsent(@TempDir Path gameDir,
                                                               @TempDir Path worldDir)
                throws IOException {
            ConsentStore store = new ConsentStore(gameDir, worldDir);
            Files.createDirectories(store.privacyFile().getParent());
            Files.writeString(store.privacyFile(),
                    "{\"schemaVersion\":2,\"privacyInstanceId\":\"pi_3f7a91c4\","
                            + "\"consentVersion\":\"" + currentPrivacySpecVersion() + "\","
                            + "\"collection\":{\"enabled\":false,\"categories\":{\"C1\":true,\"C2\":true}},"
                            + "\"export\":{\"enabled\":false,\"categories\":{}},"
                            + "\"analysis\":{\"includeRecommendations\":false}}",
                    StandardCharsets.UTF_8);

            ConsentState loaded = store.load();
            assertFalse(loaded.collectionEnabled(), "总闸关闭时绝不允许采集");
            assertTrue(loaded.collectionCategories().isEmpty(), "关闭态不得残留已授予类别");

            ConsentGate gate = new ConsentGate(loaded, () -> 1_790_000_000_000L);
            assertFalse(gate.isCollecting(), "同意门不得放行");
            assertFalse(gate.assertCollecting());
            assertFalse(gate.allowsCategory("C1"), "残留类别不得生效");
            gate.recordDroppedByConsent();
            assertEquals(1L, gate.droppedByConsent(), "被拒绝的写入必须计数，不得静默按 0");
            assertTrue(gate.grantedCategories().isEmpty());
        }

        @Test
        @DisplayName("撤回后磁盘状态同样不得复活：全类别必须为 false 且 revocation 四态全 true")
        void revokedOnDiskStaysRevoked(@TempDir Path gameDir, @TempDir Path worldDir)
                throws IOException {
            ConsentStore store = new ConsentStore(gameDir, worldDir);
            ConsentGate gate = new ConsentGate(ConsentState.DENIED, () -> 1_790_000_000_000L);
            gate.grant(INSTANCE, Set.of("C1", "C2"), 268_435_456L, 180);
            ConsentState revoked = gate.revoke();
            store.save(revoked);

            ConsentState loaded = store.load();
            assertTrue(loaded.isRevoked());
            assertTrue(loaded.revocation().isCompliant(), "撤回四态必须全 true 才视为合规");
            assertFalse(loaded.collectionEnabled(), "撤回后重新加载不得复活采集");
            assertTrue(loaded.collectionCategories().isEmpty());
            Map<String, Object> root = Json.decodeObject(
                    Files.readString(store.privacyFile(), StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            Map<String, Object> collection = (Map<String, Object>) root.get("collection");
            assertEquals(Boolean.FALSE, collection.get("enabled"));
            @SuppressWarnings("unchecked")
            Map<String, Object> cats = (Map<String, Object>) collection.get("categories");
            assertTrue(cats.values().stream().noneMatch(Boolean.TRUE::equals),
                    "撤回后落盘的类别不得残留任何 true：" + cats);
        }

        @Test
        @DisplayName("落盘 JSON 的键集合与 consent.schema.json 要求一致，且 consentVersion 等于权威版本")
        void onDiskShapeMatchesSchema() throws IOException {
            String specVersion = currentPrivacySpecVersion();
            ConsentState s = ConsentState.granted(INSTANCE, Set.of("C1", "C2"), "2026-09-26",
                    268_435_456L, 180);
            Map<String, Object> root = Json.decodeObject(s.toJson());
            for (String key : java.util.List.of("schemaVersion", "privacyInstanceId", "consentVersion",
                    "collection", "export", "analysis")) {
                assertTrue(root.containsKey(key), "落盘状态必须含 required 键 " + key);
            }
            assertEquals(specVersion, root.get("consentVersion"),
                    "consentVersion 必须等于 " + currentPrivacySpecVersion() + "（不得拼接或硬编码）");
            assertEquals(specVersion, ConsentState.currentSpecVersion());
            assertEquals(2, ((Number) root.get("schemaVersion")).intValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> collection = (Map<String, Object>) root.get("collection");
            assertTrue(collection.containsKey("enabled"));
            assertTrue(collection.containsKey("categories"));
            assertTrue(collection.containsKey("retention"));
            @SuppressWarnings("unchecked")
            Map<String, Object> retention = (Map<String, Object>) collection.get("retention");
            assertTrue(retention.containsKey("maxBytes") && retention.containsKey("maxAgeDays"));
            @SuppressWarnings("unchecked")
            Map<String, Object> cats = (Map<String, Object>) collection.get("categories");
            assertEquals(ConsentState.CATEGORY_IDS, java.util.List.copyOf(cats.keySet()),
                    "八个类别必须全部存在且顺序固定");
        }
    }

    @Nested
    @DisplayName("一键清除已采集数据（§2.9 删除语义）")
    class Erasure {

        @Test
        @DisplayName("清空 events/ 与 state/、覆盖删除并重新生成 salt.bin，收据不含路径")
        void eraseAllClearsCollectedData(@TempDir Path worldDir) throws IOException {
            Path base = com.octant.common.privacy.OctantPaths.dataDir(worldDir);
            Path events = base.resolve("events").resolve("raw").resolve("7f3a1c9b04d2e6a5");
            Files.createDirectories(events);
            Files.writeString(events.resolve("s0001.jsonl"), "{\"type\":\"session_start\"}\n");
            Files.writeString(events.resolve("s0002.jsonl"), "{\"type\":\"session_end\"}\n");
            Files.createDirectories(base.resolve("state"));
            Files.writeString(base.resolve("state").resolve("sessions.json"), "{\"sessions\":[]}\n");
            Files.createDirectories(base.resolve("meta"));
            Files.writeString(base.resolve("meta").resolve("consent.json"), "{}");

            Path saltFile = base.resolve("meta").resolve("salt.bin");
            SaltProvider original = SaltProvider.generate();
            original.persist(saltFile);
            byte[] before = Files.readAllBytes(saltFile);

            DataEraser.Receipt receipt = new DataEraser(worldDir, worldDir.resolveSibling("gamedir")).eraseAll();

            assertTrue(receipt.eventsDirEmpty(), "删除后 events 目录必须为空（契约的可判定条件）");
            assertTrue(receipt.pseudonymSaltRegenerated(), "删除后 salt.bin 必须重新生成");
            assertTrue(Files.isRegularFile(saltFile), "salt.bin 必须存在");
            byte[] after = Files.readAllBytes(saltFile);
            assertNotEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(after),
                    "盐必须被重置，否则删除前的假名仍可跨导出关联");
            assertTrue(isEmptyDir(base.resolve("state")), "state 目录内容必须清空");
            assertTrue(Files.notExists(base.resolve("meta").resolve("consent.json")),
                    "同意状态镜像必须一并清理");

            String json = receipt.toJson();
            assertFalse(json.contains(worldDir.toString()), "收据禁止包含真实路径");
            assertFalse(json.contains("salt.bin"), "收据禁止包含文件名");
            assertTrue(json.contains("EVENTS_DIR"), "收据只允许类别枚举与计数");
            assertTrue(json.contains("\"eventsDirEmpty\":true"));
        }

        @Test
        @DisplayName("删除是幂等的：无数据时再次执行不报错且 events 为空")
        void eraseIsIdempotent(@TempDir Path worldDir) {
            DataEraser eraser = new DataEraser(worldDir, worldDir.resolveSibling("gamedir"));
            DataEraser.Receipt first = eraser.eraseAll();
            assertTrue(first.eventsDirEmpty());
            DataEraser.Receipt second = eraser.eraseAll();
            assertTrue(second.eventsDirEmpty(), "重复删除必须幂等成功");
        }

        private static boolean isEmptyDir(Path dir) {
            if (!Files.exists(dir)) {
                return true;
            }
            try (var list = Files.list(dir)) {
                return list.findAny().isEmpty();
            } catch (IOException ex) {
                return false;
            }
        }
    }

    @Nested
    @DisplayName("加盐假名化（§2.2 playerKey 形态）")
    class Pseudonymization {

        private static final String RAW_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

        @Test
        @DisplayName("产物是 16 位小写十六进制，且不含原始 UUID / 玩家名")
        void productContainsNoRawIdentifier() {
            SaltProvider salt = SaltProvider.generate();
            String key = salt.pseudonymizeUuid(RAW_UUID);

            assertTrue(key.matches("[0-9a-f]{16}"),
                    "playerKey 必须是 16 位小写十六进制（dc §2.2），实际：" + key);
            assertFalse(key.contains("069a79f4"), "产物不得包含原始 UUID 片段");
            assertFalse(key.contains(RAW_UUID));
            assertFalse(key.toLowerCase().contains("notch"), "产物不得包含玩家名");
        }

        @Test
        @DisplayName("同盐同输入确定；换盐即不可关联（跨存档不可关联）")
        void saltMakesCrossWorldUnlinkable() throws IOException {
            SaltProvider a = SaltProvider.generate();
            SaltProvider b = SaltProvider.generate();
            String ka1 = a.pseudonymizeUuid(RAW_UUID);
            String ka2 = a.pseudonymizeUuid(RAW_UUID);
            String kb = b.pseudonymizeUuid(RAW_UUID);
            assertEquals(ka1, ka2, "同存档同玩家必须确定（可复算）");
            assertNotEquals(ka1, kb, "不同存档的盐不同 → 同一玩家不得可关联");

            Path saltFile = Files.createTempDirectory("salt").resolve("salt.bin");
            a.persist(saltFile);
            SaltProvider reloaded = SaltProvider.of(Files.readAllBytes(saltFile));
            assertEquals(ka1, reloaded.pseudonymizeUuid(RAW_UUID),
                    "由磁盘读回的盐必须复算出同一假名");
        }

        @Test
        @DisplayName("UUID 规范化：连字符/大小写不影响结果（跨平台一致）")
        void canonicalizationIsStable() {
            SaltProvider salt = SaltProvider.generate();
            assertEquals(salt.pseudonymizeUuid(RAW_UUID),
                    salt.pseudonymizeUuid(RAW_UUID.toUpperCase(java.util.Locale.ROOT)));
            assertEquals("069a79f444e94726a5befca90e38aaf5", SaltProvider.canonicalizeUuid(RAW_UUID));
        }

        @Test
        @DisplayName("拒绝弱盐与空输入")
        void rejectsWeakSaltAndBlankInput() {
            assertThrows(ContractException.class, () -> SaltProvider.of(new byte[8]));
            assertThrows(ContractException.class, () -> SaltProvider.generate().pseudonymize("  "));
        }

        @Test
        @DisplayName("盐文件必须可覆盖删除（删除路径不留可恢复字节）")
        void overwriteAndDeleteSalt(@TempDir Path dir) throws IOException {
            Path saltFile = dir.resolve("salt.bin");
            SaltProvider.generate().persist(saltFile);
            assertTrue(Files.isRegularFile(saltFile));
            SaltProvider.overwriteAndDelete(saltFile);
            assertFalse(Files.exists(saltFile), "覆盖删除后不得残留盐文件");
            SaltProvider.overwriteAndDelete(saltFile);
        }
    }
}
