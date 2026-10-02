package com.octant.common.collection;

import com.octant.common.model.Json;
import com.octant.common.privacy.adapter.ConsentLedger;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsentLedgerTest {

    private static final String INSTANCE = "pi_0123456789abcdef";
    private static final String VERSION = "privacy-spec@2.5.3";
    private static final String DATE = "2026-10-01";

    @Nested
    @DisplayName("① 落点与删除侧一致")
    class PathAlignment {

        @Test
        void ledgerPathEqualsThePathTheDeletionScopeDeclares(@TempDir Path gameDir) {
            Path expected = com.octant.common.privacy.OctantPaths.dataDir(gameDir.resolve("config"))
                    .resolve("consent-ledger.jsonl");
            assertEquals(expected, new ConsentLedger(gameDir).ledgerFile(),
                    "账本落点必须与规范 §1 目录图一致");
        }

        @Test
        void privacyJsonAndLedgerShareTheSameDirectory(@TempDir Path gameDir) {
            Path world = gameDir.resolve("world");
            ConsentStore store = new ConsentStore(gameDir, world);
            assertEquals(new ConsentLedger(gameDir).ledgerFile().getParent(),
                    store.privacyFile().getParent(),
                    "privacy.json 与 consent-ledger.jsonl 必须同目录（否则删除会只删一半）");
        }

        @Test
        void pathKeyMatchesTheEraserCategory(@TempDir Path gameDir) {
            String expectedKey = null;
            for (DataEraser.Category c : DataEraser.Category.values()) {
                if (c.name().equals("CONSENT_LEDGER")) {
                    expectedKey = c.pathKey();
                }
            }
            assertEquals("gameDir.config.octant.consent-ledger.jsonl", expectedKey,
                    "删除清单里的账本 pathKey 变了 ⇒ 两侧口径已分家，必须同步");
            String actual = "gameDir." + gameDir.relativize(new ConsentLedger(gameDir).ledgerFile())
                    .toString().replace('\\', '.');
            assertEquals(expectedKey, actual,
                    "账本真实落点与删除清单 pathKey 必须逐字一致（实际 " + actual + "）");
        }
    }

    @Nested
    @DisplayName("② 记录只含同意元数据")
    class ContentIsMetadataOnly {

        private static final Set<String> ALLOWED_KEYS = Set.of(
                "op", "atDate", "atEpochMs", "privacyInstanceId", "consentVersion",
                "categories", "changes", "revokedAtDate", "removedCounts");

        private static final List<String> BEHAVIOR_KEYS = List.of(
                "type", "eventId", "playerKey", "sessionId", "tRelMs", "tick",
                "payload", "advancementId", "biome", "cause", "metrics", "events");

        @Test
        void grantRecordHasOnlyAllowedKeys(@TempDir Path gameDir) throws IOException {
            ConsentLedger ledger = new ConsentLedger(gameDir);
            ledger.append(ConsentLedger.ConsentRecord.grant(
                    INSTANCE, VERSION, DATE, 1_700_000_000_000L, Set.of("C1", "C2")));

            String line = Files.readAllLines(ledger.ledgerFile(), StandardCharsets.UTF_8).get(0);
            Map<String, Object> obj = Json.decodeObject(line);

            for (String key : obj.keySet()) {
                assertTrue(ALLOWED_KEYS.contains(key),
                        "账本出现了未登记的键 " + key + " ⇒ 只含同意元数据的约束被破坏");
            }
            for (String bad : BEHAVIOR_KEYS) {
                assertFalse(obj.containsKey(bad), "账本不得含行为数据键：" + bad);
            }
            assertEquals("grant", obj.get("op"));
            assertEquals(DATE, obj.get("atDate"));
            assertEquals(INSTANCE, obj.get("privacyInstanceId"));
            assertEquals(VERSION, obj.get("consentVersion"));
            assertEquals(List.of("C1", "C2"), obj.get("categories"),
                    "类别必须按**排序后**的规范顺序写入（不是 Set 的迭代顺序）："
                            + "账本跨机核对依赖逐字节相同的行");
        }

        @Test
        void ledgerNeverContainsAbsolutePaths(@TempDir Path gameDir) throws IOException {
            ConsentLedger ledger = new ConsentLedger(gameDir);
            ledger.append(ConsentLedger.ConsentRecord.grant(
                    INSTANCE, VERSION, DATE, 1L, Set.of("C1")));
            String text = Files.readString(ledger.ledgerFile(), StandardCharsets.UTF_8);
            assertFalse(text.contains(gameDir.toString()),
                    "账本不得出现绝对路径（含：" + gameDir + "）");
            assertFalse(text.contains(gameDir.getFileName().toString()),
                    "账本不得出现真实目录名");
        }

        @Test
        void deleteReceiptCarriesOnlyCategoryEnumsAndCounts(@TempDir Path gameDir) throws IOException {
            ConsentLedger ledger = new ConsentLedger(gameDir);
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("raw_events", 42);
            counts.put("pseudonym_salt", 1);
            ledger.append(ConsentLedger.ConsentRecord.delete(
                    INSTANCE, VERSION, DATE, 2L, counts));

            Map<String, Object> obj = Json.decodeObject(
                    Files.readAllLines(ledger.ledgerFile(), StandardCharsets.UTF_8).get(0));
            assertEquals("delete", obj.get("op"));
            assertTrue(obj.containsKey("removedCounts"),
                    "§5.3 D4 要求删除收据含「被删路径的类别枚举 + 条目数」");
            @SuppressWarnings("unchecked")
            Map<String, Object> rc = (Map<String, Object>) obj.get("removedCounts");
            assertEquals(42, ((Number) rc.get("raw_events")).intValue());
        }
    }

    @Nested
    @DisplayName("③ 追加与读回")
    class AppendAndReadBack {

        @Test
        void fourOperationsAppendFourLinesInOrder(@TempDir Path gameDir) throws IOException {
            ConsentLedger ledger = new ConsentLedger(gameDir);

            ledger.append(ConsentLedger.ConsentRecord.grant(
                    INSTANCE, VERSION, DATE, 1L, Set.of("C1", "C2")));
            Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
            changes.put("categories", Map.of("from", List.of("C1", "C2"), "to", List.of("C1")));
            ledger.append(ConsentLedger.ConsentRecord.update(
                    INSTANCE, VERSION, DATE, 2L, Set.of("C1"), changes));
            ledger.append(ConsentLedger.ConsentRecord.revoke(
                    INSTANCE, VERSION, DATE, 3L, DATE));
            ledger.append(ConsentLedger.ConsentRecord.delete(
                    INSTANCE, VERSION, DATE, 4L, Map.of("raw_events", 7)));

            List<ConsentLedger.ConsentRecord> back = ledger.read();
            assertEquals(4, back.size(), "四次操作必须留下四条记录");
            assertEquals(List.of(ConsentLedger.Op.GRANT, ConsentLedger.Op.UPDATE,
                            ConsentLedger.Op.REVOKE, ConsentLedger.Op.DELETE),
                    back.stream().map(ConsentLedger.ConsentRecord::op).toList(),
                    "账本顺序必须等于操作顺序（履历的时序是它的全部价值）");
            assertEquals(INSTANCE, back.get(0).privacyInstanceId());
            assertEquals(VERSION, back.get(0).consentVersion());
            assertEquals(List.of("C1", "C2"), back.get(0).categories(),
                    "读回的类别顺序必须是规范（排序）顺序，与 Set 迭代顺序无关");
            assertEquals(DATE, back.get(0).atDate());
            assertEquals(Set.of("C1"), Set.copyOf(back.get(1).categories()));
            assertTrue(back.get(1).changes().containsKey("categories"),
                    "P3 要求 update 含变更字段名与前后值");
            assertEquals(DATE, back.get(2).revokedAtDate());
            assertEquals(7, back.get(3).removedCounts().get("raw_events"));
            assertEquals(4, Files.readAllLines(ledger.ledgerFile(), StandardCharsets.UTF_8).size());
        }

        @Test
        void appendingDoesNotRewriteEarlierLines(@TempDir Path gameDir) throws IOException {
            ConsentLedger ledger = new ConsentLedger(gameDir);
            ledger.append(ConsentLedger.ConsentRecord.grant(
                    INSTANCE, VERSION, DATE, 1L, Set.of("C1")));
            String afterFirst = Files.readString(ledger.ledgerFile(), StandardCharsets.UTF_8);

            ledger.append(ConsentLedger.ConsentRecord.revoke(
                    INSTANCE, VERSION, DATE, 2L, DATE));
            String afterSecond = Files.readString(ledger.ledgerFile(), StandardCharsets.UTF_8);

            assertTrue(afterSecond.startsWith(afterFirst),
                    "追加必须只增长（履历不得被重写：被改写的履历不再是审计依据）");
        }

        @Test
        void missingLedgerReadsAsEmptyNotAsError(@TempDir Path gameDir) {
            assertEquals(0, new ConsentLedger(gameDir).read().size(),
                    "账本缺席 = 还没有任何同意操作，不是错误");
        }

        @Test
        void corruptLineIsRejectedLoudlyAndPointedly(@TempDir Path gameDir) throws IOException {
            ConsentLedger ledger = new ConsentLedger(gameDir);
            Files.createDirectories(ledger.ledgerFile().getParent());
            Files.writeString(ledger.ledgerFile(), "{\"op\":\"grant\"}\nnot-json-at-all\n");
            IllegalStateException ex = assertThrows(IllegalStateException.class, ledger::read);
            assertTrue(ex.getMessage().contains("第 2 行"),
                    "损坏必须指到具体行（否则无法核查）：" + ex.getMessage());
        }

        @Test
        void unknownOpIsRejected(@TempDir Path gameDir) throws IOException {
            ConsentLedger ledger = new ConsentLedger(gameDir);
            Files.createDirectories(ledger.ledgerFile().getParent());
            Files.writeString(ledger.ledgerFile(), "{\"op\":\"teleport\"}\n");
            assertThrows(IllegalStateException.class, ledger::read,
                    "op 是闭集（grant/update/revoke/delete），未知值必须被拒");
        }
    }

    @Nested
    @DisplayName("④ 追加失败必须抛出（不得静默）")
    class FailClosed {

        @Test
        void appendOntoADirectoryThrows(@TempDir Path gameDir) throws IOException {
            ConsentLedger ledger = new ConsentLedger(gameDir);
            Files.createDirectories(ledger.ledgerFile());

            assertThrows(IOException.class,
                    () -> ledger.append(ConsentLedger.ConsentRecord.grant(
                            INSTANCE, VERSION, DATE, 1L, Set.of("C1"))),
                    "账本追加失败必须抛 IOException ⇒ 调用方才能保持 fail-closed"
                            + "（§5.1.1：不得只更新状态文件、账本留空）");
        }

        @Test
        void appendUnderAReadOnlyFileParentThrows(@TempDir Path gameDir) throws IOException {
            Path cfgParent = gameDir.resolve("config");
            Files.writeString(cfgParent, "not a directory");
            ConsentLedger ledger = new ConsentLedger(gameDir);
            assertThrows(IOException.class,
                    () -> ledger.append(ConsentLedger.ConsentRecord.grant(
                            INSTANCE, VERSION, DATE, 1L, Set.of("C1"))));
        }
    }

    @Nested
    @DisplayName("⑤ 未授权不写 · 删除时真被删")
    class GatingAndErasure {

        @Test
        void noLedgerBeforeGrantAndNoneAddedAfterRevoke(@TempDir Path gameDir, @TempDir Path worldDir)
                throws IOException {
            ConsentLedger ledger = new ConsentLedger(gameDir);
            assertFalse(Files.exists(ledger.ledgerFile()),
                    "授权之前账本文件就不该存在（未授权不写）");

            assertEquals(0, ledger.read().size());
            assertFalse(Files.exists(ledger.ledgerFile()));

            ledger.append(ConsentLedger.ConsentRecord.grant(
                    INSTANCE, VERSION, DATE, 1L, Set.of("C1")));
            ledger.append(ConsentLedger.ConsentRecord.revoke(
                    INSTANCE, VERSION, DATE, 2L, DATE));
            int linesAfterRevoke = Files.readAllLines(ledger.ledgerFile(),
                    StandardCharsets.UTF_8).size();
            assertEquals(2, linesAfterRevoke, "grant + revoke 各一条，不多不少");

            assertEquals(2, ledger.read().size());
            assertEquals(linesAfterRevoke, Files.readAllLines(ledger.ledgerFile(),
                    StandardCharsets.UTF_8).size(), "读回不得追加任何行");
        }

        @Test
        void eraseAllReallyRemovesTheLedgerFile(@TempDir Path gameDir, @TempDir Path worldDir)
                throws IOException {
            ConsentLedger ledger = new ConsentLedger(gameDir);
            ledger.append(ConsentLedger.ConsentRecord.grant(
                    INSTANCE, VERSION, DATE, 1L, Set.of("C1", "C2")));
            assertTrue(Files.isRegularFile(ledger.ledgerFile()), "先确认账本真的写出来了");

            DataEraser.Receipt receipt = new DataEraser(worldDir, gameDir).eraseAll();

            assertFalse(Files.exists(ledger.ledgerFile()),
                    "「一键清除」之后账本必须不在磁盘上："
                            + ledger.ledgerFile() + " —— 若仍在，说明写入侧与删除侧路径没对齐");
            assertTrue(receipt.removedEntries().getOrDefault(
                            DataEraser.Category.CONSENT_LEDGER, 0) >= 1,
                    "删除收据里必须记到 CONSENT_LEDGER 这一类（否则删除侧「看不见」它）："
                            + receipt.toJson());
        }

        @Test
        void unwritableLedgerThrowsAndLeavesNoFile(@TempDir Path gameDir) throws IOException {
            Path cfgParent = gameDir.resolve("config");
            Files.writeString(cfgParent, "i am a file, not a directory", StandardCharsets.UTF_8);
            ConsentLedger ledger = new ConsentLedger(gameDir);

            IOException ex = assertThrows(IOException.class,
                    () -> ledger.append(ConsentLedger.ConsentRecord.grant(
                            INSTANCE, VERSION, DATE, 1L, Set.of("C1"))),
                    "写不进去必须抛 IOException（可判定痕迹），不得静默吞掉");
            assertNotNull(ex, "异常本身即为痕迹");
            assertFalse(Files.exists(ledger.ledgerFile()),
                    "失败的追加不得留下半份账本文件");
        }
    }

    @Test
    void sameRecordProducesByteIdenticalLine(@TempDir Path a, @TempDir Path b) throws IOException {
        ConsentLedger.ConsentRecord rec = ConsentLedger.ConsentRecord.grant(
                INSTANCE, VERSION, DATE, 1_700_000_000_000L, Set.of("C2", "C1"));
        ConsentLedger la = new ConsentLedger(a);
        ConsentLedger lb = new ConsentLedger(b);
        la.append(rec);
        lb.append(rec);

        String lineA = Files.readAllLines(la.ledgerFile(), StandardCharsets.UTF_8).get(0);
        String lineB = Files.readAllLines(lb.ledgerFile(), StandardCharsets.UTF_8).get(0);
        assertEquals(lineA, lineB,
                "同一份记录必须给出逐字节相同的行（否则账本无法跨机器核对）");
        List<String> cats = new ArrayList<>();
        for (Object o : ((List<?>) Json.decodeObject(lineA).get("categories"))) {
            cats.add(String.valueOf(o));
        }
        assertEquals(List.of("C1", "C2"), cats.stream().sorted().toList());
    }
}
