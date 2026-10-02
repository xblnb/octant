package com.octant.common.collection;

import com.octant.common.privacy.SaltProvider;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeletionScopeTest {

    private static final String KEY = "7f3a1c9b04d2e6a5";

    private static void populateAllScopePaths(Path worldDir, Path gameDir) throws IOException {
        Path w = com.octant.common.privacy.OctantPaths.dataDir(worldDir);
        Path g = com.octant.common.privacy.OctantPaths.dataDir(gameDir);
        Path cfg = com.octant.common.privacy.OctantPaths.dataDir(gameDir.resolve("config"));

        Path ev = w.resolve("events").resolve("raw").resolve(KEY);
        Files.createDirectories(ev);
        Files.writeString(ev.resolve("s0001.jsonl"), "{\"type\":\"session_start\"}\n");
        Files.createDirectories(w.resolve("state"));
        Files.writeString(w.resolve("state").resolve("sessions.json"), "{\"sessions\":[]}\n");
        Files.writeString(w.resolve("state").resolve("truncation.json"), "{\"policy\":\"drop_oldest\"}\n");
        Files.writeString(w.resolve("state").resolve("0001.state.json"), "{\"consentState\":\"granted\"}\n");
        Files.writeString(w.resolve("state").resolve("0002.state.json"), "{\"consentState\":\"granted\"}\n");
        Files.writeString(w.resolve("state").resolve("0003.state.json"), "{\"consentState\":\"revoked\"}\n");
        Files.createDirectories(w.resolve("meta"));
        SaltProvider.generate().persist(w.resolve("meta").resolve("salt.bin"));
        Files.writeString(w.resolve("meta").resolve("consent.json"), "{\"schemaVersion\":2}\n");
        Files.writeString(w.resolve("meta").resolve("schema.json"), "{\"schemaVersion\":\"x\"}\n");
        Path an = w.resolve("analysis");
        Files.createDirectories(an);
        Files.writeString(an.resolve("metrics.cache.json"), "{}\n");
        Path ex = w.resolve("export").resolve("20260926-190938-a3f19b");
        Files.createDirectories(ex);
        Files.writeString(ex.resolve("report.md"), "# report\n");
        Files.writeString(ex.resolve("manifest.json"), "{}\n");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg.getParent().resolve("privacy.json"), "{\"schemaVersion\":2}\n");
        Files.createDirectories(cfg);
        Files.writeString(cfg.resolve("consent-ledger.jsonl"), "{}\n");
        SaltProvider.generate().persist(cfg.resolve("salt.bin"));
        Files.writeString(w.resolve("meta").resolve("pack-snapshot.json"), "{\"schemaVersion\":1}\n");
        Files.createDirectories(w.resolve("consent"));
        Files.writeString(w.resolve("consent").resolve("notice.md"), "local notice\n");
        Files.writeString(cfg.resolve("deletion-ledger.jsonl"), "{}\n");
        Files.createDirectories(gameDir.resolve("logs"));
        Files.writeString(gameDir.resolve("logs").resolve("mcinsight-2026-09-26.log"), "log\n");
        Files.writeString(gameDir.resolve("logs").resolve("othermod.log"), "must-survive\n");
        Files.createDirectories(gameDir.resolve("mcinsight-local"));
        Files.createDirectories(com.octant.common.privacy.OctantPaths.dataDir(worldDir).resolve("meta").resolve("env"));
        Files.writeString(com.octant.common.privacy.OctantPaths.dataDir(worldDir).resolve("meta").resolve("env")
                .resolve("1.json"), "{\"rev\":1}\n");
        Files.writeString(gameDir.resolve("mcinsight-local").resolve("scratch.json"), "{}\n");
    }

    @Nested
    @DisplayName("§2.1 / registry 18 类路径：逐类造文件、逐类断言被清除")
    class FullScope {

        @Test
        @DisplayName("删除范围恰为 18 类，且与登记表类别一一对应（机器可读）")
        void scopeIsEighteenCategories() {
            Map<String, Object> scope = DataEraser.deletionScope();
            assertEquals(18, DataEraser.SCOPE_SIZE);
            assertEquals(18, DataEraser.Category.values().length, "类别数必须恰为 18（spec 2.5.3 新增 ⑧）");
            assertEquals(18, scope.get("count"));
            @SuppressWarnings("unchecked")
            var keys = (java.util.List<String>) scope.get("categories");
            assertEquals(18, keys.size());
            assertTrue(keys.contains("world.octant.state/<epoch>.state.json"));
            assertTrue(keys.contains("world.octant.meta.consent.json"));
            assertTrue(keys.contains("world.octant.meta.schema.json"));
            assertTrue(keys.contains("world.octant.meta.pack-snapshot.json"));
            assertTrue(keys.contains("world.octant.consent/"));
            assertTrue(keys.contains("gameDir.config.octant.deletion-ledger.jsonl"));
            assertTrue(keys.contains("gameDir.logs.octant*.log"),
                    "模组日志在存档之外（<gameDir>/logs/），最容易被漏清，且含绝对路径与玩家名");
            assertTrue(keys.contains("world.octant.analysis/"));
            assertTrue(keys.contains("world.octant.export/"));
            assertTrue(keys.contains("gameDir.octant-local/"));
            assertTrue(keys.contains("world.octant.meta.env/"),
                    "spec 2.5.3 新增的 ⑧：环境快照明细目录（新增落盘位置 = 新的漏清点）");
        }

        @Test
        @DisplayName("三方一致：清单条目数 == Category 数 == SCOPE_SIZE（任一侧扩条目即报警）")
        void threeWayScopeConsistency() {
            assertTrue(DataEraser.scopeMatchesDeclared(18),
                    DataEraser.scopeMismatchReason(18));
            assertEquals("", DataEraser.scopeMismatchReason(18));
            assertFalse(DataEraser.scopeMatchesDeclared(19),
                    "清单一侧扩条目而实现未跟上时必须判为不一致——这正是兜底会掩盖的漏登记");
            assertTrue(DataEraser.scopeMismatchReason(19).contains("清单声明 19"));
            assertFalse(DataEraser.scopeMatchesDeclared(17),
                    "旧值 17 已过期：spec 2.5.3 起为 18");
        }

        @Test
        @DisplayName("earseAll 清空全部 18 类路径，且每存档盐被覆盖删除并重新生成")
        void erasesEveryScopePath(@TempDir Path worldDir, @TempDir Path gameDir) throws IOException {
            populateAllScopePaths(worldDir, gameDir);
            Path w = com.octant.common.privacy.OctantPaths.dataDir(worldDir);
            Path g = com.octant.common.privacy.OctantPaths.dataDir(gameDir);
            Path cfg = com.octant.common.privacy.OctantPaths.dataDir(gameDir.resolve("config"));

            byte[] saltBefore = Files.readAllBytes(w.resolve("meta").resolve("salt.bin"));

            DataEraser.Receipt receipt = new DataEraser(worldDir, gameDir).eraseAll();

            assertTrue(receipt.eventsDirEmpty(), "events 目录必须为空");
            assertFalse(Files.exists(w.resolve("state").resolve("sessions.json")),
                    "会话账本必须被删除（玩家主动清除语境）");
            assertFalse(Files.exists(w.resolve("state").resolve("truncation.json")), "截断账本必须被删除");
            assertFalse(Files.exists(w.resolve("state").resolve("0001.state.json")));
            assertFalse(Files.exists(w.resolve("state").resolve("0002.state.json")));
            assertFalse(Files.exists(w.resolve("state").resolve("0003.state.json")),
                    "三个 epoch 快照必须全部删除，不得只删最新的");
            Path saltFile = w.resolve("meta").resolve("salt.bin");
            assertTrue(Files.isRegularFile(saltFile), "删除后 salt.bin 必须重新生成");
            assertNotEquals(java.util.Arrays.toString(saltBefore),
                    java.util.Arrays.toString(Files.readAllBytes(saltFile)),
                    "盐必须被重置，否则删除前的假名仍可关联");
            assertTrue(receipt.pseudonymSaltRegenerated());
            assertFalse(Files.exists(w.resolve("meta").resolve("consent.json")), "同意镜像必须被删除");
            assertFalse(Files.exists(w.resolve("meta").resolve("schema.json")), "schema 快照必须被删除");
            assertFalse(Files.exists(w.resolve("analysis")),
                    "本地分析缓存必须被删除（兜底会连目录一起清掉，这是允许的）");
            assertFalse(Files.exists(w.resolve("export").resolve("20260926-190938-a3f19b")),
                    "存档内导出物必须被删除");
            assertFalse(Files.exists(cfg.getParent().resolve("privacy.json")),
                    "权威同意状态必须被删除/重置");
            assertFalse(Files.exists(cfg.resolve("consent-ledger.jsonl")), "同意账本必须被删除");
            assertFalse(Files.exists(cfg.resolve("salt.bin")),
                    "cross_export 全安装盐必须被删除（不得与每存档盐互相替代）");
            assertFalse(Files.exists(gameDir.resolve("logs").resolve("mcinsight-2026-09-26.log")),
                    "本模组日志必须被删除（含绝对路径与玩家名）");
            assertTrue(Files.exists(gameDir.resolve("logs").resolve("othermod.log")),
                    "不得越界删除同一个 logs/ 目录下其他模组的日志");
            assertFalse(Files.exists(w.resolve("meta").resolve("pack-snapshot.json")),
                    "包构成快照必须被删除");
            assertTrue(isEmptyDir(w.resolve("consent")), "world/consent/ 内容必须被清空");
            assertFalse(Files.exists(cfg.resolve("deletion-ledger.jsonl")), "删除收据账本必须被删除");
            assertTrue(isEmptyDir(gameDir.resolve("mcinsight-local")),
                    "仅本地产物目录的**内容**必须被清空（目录本身可保留），实际："
                            + listDir(gameDir.resolve("mcinsight-local")));
        }

        @Test
        @DisplayName("删除收据只含类别枚举与计数：不含真实路径、不含文件名")
        void receiptContainsNoPaths(@TempDir Path worldDir, @TempDir Path gameDir) throws IOException {
            populateAllScopePaths(worldDir, gameDir);
            DataEraser.Receipt receipt = new DataEraser(worldDir, gameDir).eraseAll();
            String json = receipt.toJson();

            assertFalse(json.contains(worldDir.toString()), "收据禁止包含真实路径");
            assertFalse(json.contains(gameDir.toString()));
            assertFalse(json.contains("s0001.jsonl"), "收据禁止包含文件名");
            assertFalse(json.contains("salt.bin"));
            assertFalse(json.contains(KEY), "收据禁止包含假名键");
            assertTrue(json.contains("EVENTS_DIR") && json.contains("PERIODIC_STATE_SNAPSHOTS"));
            assertTrue(json.contains("\"eventsDirEmpty\":true"));
        }

        @Test
        @DisplayName("幂等：无数据时重复执行不报错，events 仍为空")
        void idempotent(@TempDir Path worldDir, @TempDir Path gameDir) {
            DataEraser eraser = new DataEraser(worldDir, gameDir);
            assertTrue(eraser.eraseAll().eventsDirEmpty());
            assertTrue(eraser.eraseAll().eventsDirEmpty(), "重复清除必须幂等成功");
        }

        @Test
        @DisplayName("未知新增文件也被⑬兜底清除（防止「以为清空却仍有残留」）")
        void unknownFilesAreSwept(@TempDir Path worldDir, @TempDir Path gameDir) throws IOException {
            Path w = com.octant.common.privacy.OctantPaths.dataDir(worldDir);
            Files.createDirectories(w.resolve("some-future-dir"));
            Files.writeString(w.resolve("some-future-dir").resolve("future.bin"), "secret");
            Files.writeString(w.resolve("unregistered.tmp"), "secret");

            new DataEraser(worldDir, gameDir).eraseAll();

            assertTrue(onlySaltRemains(w), "world/mcinsight 下只允许残留 meta/salt.bin，实际："
                    + listDir(w));
        }

        private static boolean onlySaltRemains(Path w) {
            Path salt = w.resolve("meta").resolve("salt.bin");
            try (var s = Files.walk(w)) {
                return s.filter(Files::isRegularFile)
                        .allMatch(p -> p.equals(salt));
            } catch (IOException ex) {
                return false;
            }
        }

        private static String listDir(Path dir) {
            if (!Files.isDirectory(dir)) {
                return "(不存在)";
            }
            try (var s = Files.list(dir)) {
                return s.map(p -> p.getFileName().toString()).toList().toString();
            } catch (IOException ex) {
                return "(读取失败)";
            }
        }

        private static boolean isEmptyDir(Path dir) {
            if (!Files.exists(dir)) {
                return true;
            }
            try (var s = Files.walk(dir)) {
                return s.filter(p -> Files.isRegularFile(p)).findAny().isEmpty();
            } catch (IOException ex) {
                return false;
            }
        }
    }

    @Nested
    @DisplayName("语境区分（privacy-model §5.3 D2）")
    class EvictionVsPurge {

        @Test
        @DisplayName("容量淘汰语境下账本必须保留（R4：不删账本，保护证据链）")
        void evictionKeepsLedgers(@TempDir Path worldDir) throws IOException {
            var store = new com.octant.common.session.RawEventStore(worldDir);
            store.truncation().markCapExhausted(1234L);
            store.saveTruncationLedger();
            assertTrue(Files.isRegularFile(store.truncationFile()),
                    "R4 停止写入时截断账本必须保留（容量淘汰语境）");
        }

        @Test
        @DisplayName("玩家主动清除语境下账本必须一并删除（与淘汰相反）")
        void purgeDeletesLedgers(@TempDir Path worldDir, @TempDir Path gameDir) throws IOException {
            var store = new com.octant.common.session.RawEventStore(worldDir);
            store.truncation().recordDroppedEvent();
            store.saveTruncationLedger();
            assertTrue(Files.isRegularFile(store.truncationFile()));

            new DataEraser(worldDir, gameDir).eraseAll();
            assertFalse(Files.exists(store.truncationFile()),
                    "玩家主动清除时账本必须删除，与容量淘汰语境相反");
        }
    }

    @Nested
    @DisplayName("同意状态写入失败必须 fail-closed（INV-3）")
    class ConsentWriteFailureKeepsCollectingOff {

        @Test
        @DisplayName("状态文件路径不可写时，门必须保持关闭且不把「写不进」当作已授予")
        void writeFailureDoesNotOpenGate(@TempDir Path gameDir, @TempDir Path worldDir)
                throws IOException {
            Path cfgDir = gameDir.resolve("config");
            Files.createDirectories(cfgDir.getParent());
            Files.writeString(cfgDir, "i am a file, not a directory", StandardCharsets.UTF_8);

            ConsentStore store = new ConsentStore(gameDir, worldDir);
            var gate = new com.octant.common.privacy.adapter.ConsentGate();
            gen(gate, store);

            assertFalse(gate.isCollecting(),
                    "状态写不进时采集必须保持关闭：不得把「写失败」当作已授予（INV-3）");
            assertFalse(gate.assertCollecting());
        }

        @Test
        @DisplayName("保存失败不改变内存态的前提是「未授予」；已授予态写失败必须被调用方感知")
        void grantedStateWithFailedWriteIsVisibleToCaller(@TempDir Path gameDir, @TempDir Path worldDir)
                throws IOException {
            Path cfgDir = gameDir.resolve("config");
            Files.createDirectories(cfgDir.getParent());
            Files.writeString(cfgDir, "not a directory", StandardCharsets.UTF_8);
            ConsentStore store = new ConsentStore(gameDir, worldDir);

            var state = com.octant.common.privacy.adapter.ConsentState.granted(
                    "pi_3f7a91c4", java.util.Set.of("C1"), "2026-09-26", 268_435_456L, 180);
            boolean threw = false;
            try {
                store.save(state);
            } catch (IOException expected) {
                threw = true;
            }
            assertTrue(threw, "写盘失败必须显式抛出，禁止静默吞掉（否则会出现「以为记下了」）");
            assertFalse(store.load().collectionEnabled(), "未成功落盘即不得视为已授予");
        }

        private static void gen(com.octant.common.privacy.adapter.ConsentGate gate,
                                ConsentStore store) {
            var granted = com.octant.common.privacy.adapter.ConsentState.granted(
                    "pi_3f7a91c4", java.util.Set.of("C1"), "2026-09-26", 268_435_456L, 180);
            try {
                store.save(granted);
                gate.refresh(granted);
            } catch (IOException ex) {
                gate.refresh(com.octant.common.privacy.adapter.ConsentState.denied());
            }
        }
    }
}
