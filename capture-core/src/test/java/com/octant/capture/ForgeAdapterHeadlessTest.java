package com.octant.capture;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.PayloadSchema;
import com.octant.common.privacy.SaltProvider;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.export.FileConsentSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeAdapterHeadlessTest {

    private record FakeHost(Path gameDir, Path worldDir) implements OctantHost {
        @Override
        public String modVersion() {
            return "0.1.0-test";
        }

        @Override
        public String gameVersion() {
            return "1.20.1";
        }

        @Override
        public String loader() {
            return "forge";
        }

        @Override
        public List<String> modsList() {
            return List.of("minecraft:1.20.1", "forge:47.4.22", "mcinsight:0.1.0-test");
        }

        @Override
        public String privacyClass() {
            return "singleplayer";
        }

        @Override
        public boolean cheatsEnabled() {
            return true;
        }
    }

    private static FakeHost host(Path root) {
        return new FakeHost(root, root.resolve("world"));
    }

    private static final Set<String> ALL = Set.of("C1", "C2", "C3", "C5", "C6", "C7", "C8");

    @Test
    void everyWiredObservationProducesAContractValidEvent() {
        String key = "0123456789abcdef";
        String session = CaptureEvent.sessionId(1);
        int seq = 1;

        List<ObservationAdapter.Observation> observations = List.of(
                new ObservationAdapter.SessionStart("2026-09-30", "singleplayer", "1.20.1",
                        "forge", true),
                new ObservationAdapter.SessionHeartbeat(true, 0L, 600L),
                new ObservationAdapter.SessionEnd(7_200_000L, 7_200_000L, 0L, "none", "logout",
                        false, 120, 42),
                new ObservationAdapter.AdvancementGained("minecraft:story/root", "", false, false),
                new ObservationAdapter.PlayerDeath("minecraft:fall", "fall", "", false),
                new ObservationAdapter.BiomeChanged("minecraft:plains", "minecraft:overworld", true));

        for (ObservationAdapter.Observation obs : observations) {
            CaptureEvent e = ObservationAdapter.translate(obs, key, session, seq++, 1_000L);
            assertNotNull(e, "已接线的观测不得翻译失败：" + obs.type());
            assertEquals(obs.type(), e.type());
            Map<String, Object> normalized = PayloadSchema.encode(e.type(), e.payload(), e.tRelMs());
            assertEquals(normalized.keySet(), e.payload().keySet(),
                    "payload 键集合必须与 dc §2.4 的字段表完全相同：" + e.type());
        }
    }

    @Test
    void translationFailureReturnsNullInsteadOfThrowing() {
        CaptureEvent e = ObservationAdapter.translate(
                new ObservationAdapter.SessionStart("2026-09-30", "singleplayer", "1.19.2",
                        "forge", false),
                "0123456789abcdef", CaptureEvent.sessionId(1), 1, 0L);
        assertNull(e, "闭集外取值必须表现为翻译失败（null），不得上抛");
    }

    @Test
    void wiredAndUnwiredTypesPartitionTheContractCatalog() {
        var wired = ObservationAdapter.wiredTypes();
        var unwired = ObservationAdapter.unwiredTypes();
        assertEquals(CaptureEventType.CONTRACT_TYPE_COUNT, wired.size() + unwired.size(),
                "已接线 + 未接线必须恰好覆盖契约 §2.4 的全部事件类型");
        for (CaptureEventType t : wired) {
            assertFalse(unwired.contains(t), "类型不得同时出现在两侧：" + t);
        }
    }

    @Test
    void defaultConsentIsDeniedAndNothingIsWritten(@TempDir Path root) throws Exception {
        CaptureRuntime rt = new CaptureRuntime(host(root));
        assertFalse(rt.collecting(), "默认态必须是『不采集』（fail-closed）");

        UUID id = UUID.randomUUID();
        rt.onPlayerJoin(id);
        for (int i = 0; i < 1_200; i++) {
            rt.onServerTick(id);
        }
        rt.onAdvancement(id, "minecraft:story/root", "", false, false);
        rt.onPlayerDeath(id, "minecraft:fall", "fall", "", false);

        assertEquals(0L, rt.writtenCount(), "未同意时不得写入任何事件");
        assertTrue(rt.droppedByConsentCount() > 0,
                "被同意门拒绝的事件必须**计数**，不得静默按 0");
        assertEquals(0L, rt.translationFailureCount(),
                "被同意门拒绝 ≠ 翻译失败：两者必须分开计数");
        assertFalse(Files.exists(rt.store().eventsRoot()),
                "未同意时事件目录都不应被创建：" + rt.store().eventsRoot());
    }

    @Test
    void grantedConsentEnablesWrites(@TempDir Path root) throws Exception {
        CaptureRuntime rt = new CaptureRuntime(host(root));
        UUID id = UUID.randomUUID();
        rt.consent().grant("pi_test0001", ALL, true);
        assertTrue(rt.collecting());

        rt.onPlayerJoin(id);
        for (int i = 0; i < 1_200; i++) {
            rt.onServerTick(id);
        }
        assertTrue(rt.writtenCount() >= 2,
                "同意后应当写入 session_start 与至少一条心跳，实际 " + rt.writtenCount());

        List<CaptureEvent> back = rt.store().readAll(rt.knownPlayerKeys().get(0));
        assertTrue(back.size() >= 2, "读回事件数 " + back.size());
        for (CaptureEvent e : back) {
            assertEquals(ObservationAdapter.SOURCE, e.source());
            assertNotNull(PayloadSchema.encode(e.type(), e.payload(), e.tRelMs()));
        }
    }

    @Test
    void revokeStopsCollectionWithinTheSameSession(@TempDir Path root) throws Exception {
        CaptureRuntime rt = new CaptureRuntime(host(root));
        UUID id = UUID.randomUUID();
        rt.consent().grant("pi_test0001", ALL, true);
        rt.onPlayerJoin(id);
        for (int i = 0; i < 600; i++) {
            rt.onServerTick(id);
        }
        long before = rt.writtenCount();
        assertTrue(before > 0);

        rt.consent().revoke();
        assertFalse(rt.collecting(), "撤回后同一次会话内必须立即停止采集");
        for (int i = 0; i < 1_200; i++) {
            rt.onServerTick(id);
        }
        assertEquals(before, rt.writtenCount(), "撤回后不得再写入任何事件");
    }

    @Test
    void playerKeyIsAStablePseudonymWithoutTheRawUuid(@TempDir Path root) throws Exception {
        CaptureRuntime rt = new CaptureRuntime(host(root));
        UUID id = UUID.fromString("3f7a91c2-d45b-4e80-9a11-0123456789ab");
        String key = rt.playerKeyOf(id);
        assertTrue(key.matches("[0-9a-f]{16}"), "playerKey 必须是 16 位小写十六进制：" + key);
        assertEquals(key, rt.playerKeyOf(id), "同一存档内同一玩家必须得到同一假名");
        String raw = id.toString();
        assertFalse(raw.contains(key));
        assertFalse(key.contains(raw.substring(0, 8)), "假名不得包含原始 UUID 的任何片段");

        Path saltFile = com.octant.common.privacy.OctantPaths.dataDir(root.resolve("world")).resolve("meta")
                .resolve("salt.bin");
        assertTrue(Files.isRegularFile(saltFile), "每存档盐必须落盘：" + saltFile);

        Path other = Files.createDirectories(root.resolve("world2"));
        CaptureRuntime rt2 = new CaptureRuntime(new FakeHost(root, other));
        assertNotEquals(key, rt2.playerKeyOf(id), "不同存档的同一玩家不得得到同一假名");
    }

    @Test
    void redactionSeedIsSaltDerivedAndStable(@TempDir Path root) throws Exception {
        CaptureRuntime rt = new CaptureRuntime(host(root));
        rt.onPlayerJoin(UUID.randomUUID());
        String seed = rt.saltSeedForRedaction();
        assertEquals(16, seed.length());
        assertEquals(seed, rt.saltSeedForRedaction(), "脱敏种子必须可复算（同一存档同一值）");

        Path saltFile = com.octant.common.privacy.OctantPaths.dataDir(root.resolve("world")).resolve("meta")
                .resolve("salt.bin");
        String saltHex = hex(Files.readAllBytes(saltFile));
        assertFalse(saltHex.contains(seed), "脱敏种子不得是盐的本体");
    }

    @Test
    void consentFileIsReadableByTheCollectionSideReader(@TempDir Path root) throws Exception {
        CaptureRuntime rt = new CaptureRuntime(host(root));
        rt.consent().grant("pi_test0001", ALL, true);
        Path privacy = rt.consent().privacyFile();
        assertTrue(Files.isRegularFile(privacy), "同意文件必须落盘：" + privacy);

        com.octant.common.privacy.adapter.ConsentStore store =
                new com.octant.common.privacy.adapter.ConsentStore(root,
                        root.resolve("world"));
        assertTrue(store.load().collectionEnabled(), "采集侧读取器必须读到『已开启』");

        rt.consent().revoke();
        assertFalse(store.load().collectionEnabled(), "撤回后采集侧必须读到『已关闭』");
    }

    @Test
    void theTwoConsentReadersAreMutuallyExclusiveOnSchemaVersion(@TempDir Path root)
            throws Exception {
        CaptureRuntime rt = new CaptureRuntime(host(root));
        rt.consent().grant("pi_test0001", ALL, true);
        Path privacy = rt.consent().privacyFile();

        assertEquals(2, com.octant.common.privacy.adapter.ConsentState
                .fromJson(Files.readString(privacy)).schemaVersion());

        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new FileConsentSource(privacy).read(),
                "若此断言失败，说明 FileConsentSource 已能接受整数 schemaVersion；"
                        + "此时应删除 ModExportRunner 里的 ConsentFileSource 适配层");
        assertTrue(refused.getMessage().contains("schemaVersion"),
                "拒绝原因应当是 schemaVersion 不被识别，实际：" + refused.getMessage());
    }

    @Test
    void exportProducesRealArtifactsFromCapturedEvents(@TempDir Path root) throws Exception {
        CaptureRuntime rt = new CaptureRuntime(host(root));
        CapturingHost h = new CapturingHost(host(root));
        rt.consent().grant("pi_test0001", ALL, true);

        UUID id = UUID.randomUUID();
        rt.onPlayerJoin(id);
        for (int i = 0; i < 48_000; i++) {
            rt.onServerTick(id);
        }
        for (int i = 0; i < 6; i++) {
            rt.onBiome(id, "minecraft:biome_" + i, "minecraft:overworld");
            rt.onAdvancement(id, "minecraft:story/adv_" + i, "", false, false);
        }
        rt.onPlayerDeath(id, "minecraft:fall", "fall", "", false);
        assertTrue(rt.writtenCount() > 40, "前置条件：应已写入数十条事件，实际 " + rt.writtenCount());

        ModExportRunner.Summary s = ModExportRunner.run(rt, h, java.time.Instant.parse(
                "2026-09-30T12:00:00Z"));

        assertTrue(s.ok(), "导出应成功；失败原因：" + s.failureReason()
                + " 门禁命中：" + s.gateViolations());
        assertTrue(s.captureEventCount() > 40, "采集事件数 " + s.captureEventCount());
        assertTrue(s.totalBytes() > 1_000, "产物总字节 " + s.totalBytes());

        for (Map.Entry<String, Long> e : s.fileBytes().entrySet()) {
            Path f = s.directory().resolve(e.getKey());
            assertTrue(Files.isRegularFile(f), "产物缺失：" + f);
            assertEquals(e.getValue().longValue(), Files.size(f),
                    "产物体积与报告不一致：" + e.getKey());
        }
        assertTrue(s.fileBytes().containsKey("report.html"),
                "必须产出 HTML 报告载体；实际产物：" + s.fileBytes().keySet());
        assertTrue(s.fileBytes().get("report.html") > 10_000,
                "HTML 报告过小：图表或样式渲染可能静默退化：" + s.fileBytes().get("report.html"));
        assertTrue(s.fileBytes().containsKey("report.md"), "报告的人话层（Markdown）必须同时产出");
        assertTrue(s.fileBytes().containsKey("events.anonymized.jsonl"), "脱敏事件流必须产出");

        String rawUuid = id.toString();
        for (String name : s.fileBytes().keySet()) {
            String text = Files.readString(s.directory().resolve(name));
            assertFalse(text.contains(rawUuid),
                    "产物 " + name + " 里出现了原始 UUID");
            assertFalse(text.toLowerCase().contains(rawUuid.replace("-", "")),
                    "产物 " + name + " 里出现了无连字符形态的原始 UUID");
        }
    }

    @Test
    void exportIsRefusedWhenConsentIsAbsent(@TempDir Path root) throws Exception {
        CaptureRuntime rt = new CaptureRuntime(host(root));
        rt.onPlayerJoin(UUID.randomUUID());
        ModExportRunner.Summary s = ModExportRunner.run(rt, host(root),
                java.time.Instant.parse("2026-09-30T12:00:00Z"));
        assertFalse(s.ok(), "未同意时导出必须被拒绝");
        assertFalse(Files.exists(com.octant.common.privacy.OctantPaths.dataDir(root).resolve("exports")),
                "被拒绝的导出**不得**创建导出目录");
    }

    @Test
    void veryShortSessionDoesNotEmitSessionEnd(@TempDir Path root) throws Exception {
        CaptureRuntime rt = new CaptureRuntime(host(root));
        rt.consent().grant("pi_test0001", ALL, true);
        UUID id = UUID.randomUUID();
        rt.onPlayerJoin(id);
        for (int i = 0; i < 200; i++) {
            rt.onServerTick(id);
        }
        rt.onPlayerLeave(id);
        String key = rt.knownPlayerKeys().get(0);
        boolean hasEnd = rt.store().readAll(key).stream()
                .anyMatch(e -> e.type() == CaptureEventType.SESSION_END);
        assertFalse(hasEnd, "不足契约最短时长的会话不得写 session_end（会让会话数虚高）");
    }

    private static final class CapturingHost implements OctantHost {
        private final OctantHost delegate;

        CapturingHost(OctantHost delegate) {
            this.delegate = delegate;
        }

        @Override
        public Path gameDir() {
            return delegate.gameDir();
        }

        @Override
        public Path worldDir() {
            return delegate.worldDir();
        }

        @Override
        public String modVersion() {
            return delegate.modVersion();
        }

        @Override
        public String gameVersion() {
            return delegate.gameVersion();
        }

        @Override
        public String loader() {
            return delegate.loader();
        }

        @Override
        public List<String> modsList() {
            return delegate.modsList();
        }

        @Override
        public String privacyClass() {
            return delegate.privacyClass();
        }

        @Override
        public boolean cheatsEnabled() {
            return delegate.cheatsEnabled();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void pseudonymMatchesTheCommonSideImplementation(@TempDir Path root) throws Exception {
        CaptureRuntime rt = new CaptureRuntime(host(root));
        UUID id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        String mine = rt.playerKeyOf(id);
        SaltProvider direct = SaltProvider.loadOrCreate(
                com.octant.common.privacy.OctantPaths.dataDir(root.resolve("world")).resolve("meta").resolve("salt.bin"));
        assertEquals(direct.pseudonymizeUuid(id.toString()), mine,
                "适配层不得自己另写一套假名化");
    }
}
