package com.octant.common.collection;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.model.EventSource;
import com.octant.common.model.RawEventSchema;
import com.octant.common.model.TruncationLedger;
import com.octant.common.session.RawEventStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceAndRecoveryTest {

    private static final String KEY = "7f3a1c9b04d2e6a5";
    private static final String SID = "s0001";

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static CaptureEvent dimEvent(long tRelMs) {
        return new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(1), SID, KEY,
                CaptureEventType.DIMENSION_ENTERED, tRelMs, CaptureEvent.tickOf(tRelMs),
                CaptureEventType.DIMENSION_ENTERED.category(), EventSource.FABRIC, true, null,
                map("dimension", "minecraft:overworld"));
    }

    @Nested
    @DisplayName("§2.1 NDJSON 形态与追加写")
    class NdjsonShape {

        @Test
        @DisplayName("每行恰一个 JSON 对象、行尾 \\n、UTF-8 无 BOM、按会话顺序追加")
        void lineFormatAndAppend(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            long b1 = store.append(dimEvent(1000L), 0);
            long b2 = store.append(dimEvent(2000L), 1);
            assertTrue(b1 > 0 && b2 > 0);

            Path file = store.sessionFile(KEY, SID, 0);
            assertTrue(Files.isRegularFile(file), "事件文件必须落在 events/raw/<playerKey>/<session>.jsonl");
            byte[] raw = Files.readAllBytes(file);
            assertFalse(raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB
                    && (raw[2] & 0xFF) == 0xBF, "不得使用 BOM");

            String text = new String(raw, StandardCharsets.UTF_8);
            List<String> lines = text.stripTrailing().split("\n", -1) == null ? List.of()
                    : List.of(text.stripTrailing().split("\n", -1));
            assertEquals(2, lines.size(), "两次追加必须产生两行");
            assertTrue(text.endsWith("\n"), "行尾必须是 \\n");
            for (String line : lines) {
                assertTrue(line.startsWith("{") && line.endsWith("}"),
                        "每行必须恰一个 JSON 对象（不允许跨行 JSON）：" + line);
                assertFalse(line.contains("\n"));
            }
            assertTrue(text.contains("1000") && text.contains("2000"));
        }

        @Test
        @DisplayName("§2.1 分片：每 20000 事件切一个文件，会话 ID 不变")
        void sessionFileSplit() {
            RawEventStore store = new RawEventStore(Path.of("."));
            assertEquals("s0001.jsonl", store.sessionFile(KEY, SID, 0).getFileName().toString());
            assertEquals("s0001.jsonl", store.sessionFile(KEY, SID, 19_999).getFileName().toString());
            assertEquals("s0001-a.jsonl", store.sessionFile(KEY, SID, 20_000).getFileName().toString());
            assertEquals("s0001-b.jsonl", store.sessionFile(KEY, SID, 40_000).getFileName().toString());
            assertTrue(store.sessionFile(KEY, SID, 40_000).getFileName().toString().startsWith("s0001"));
        }

        @Test
        @DisplayName("拒绝含路径穿越成分的 playerKey/sessionId")
        void rejectsPathTraversal() {
            RawEventStore store = new RawEventStore(Path.of("."));
            assertThrows(ContractException.class, () -> store.sessionFile("../evil", SID, 0));
            assertThrows(ContractException.class, () -> store.sessionFile(KEY, "s/0001", 0));
            assertThrows(ContractException.class, () -> store.sessionFile(KEY, "..", 0));
        }

        @Test
        @DisplayName("同一流内 tRelMs 必须单调非递减（§2.1）")
        void monotonicWithinStream(@TempDir Path worldDir) {
            RawEventStore store = new RawEventStore(worldDir);
            assertEquals(RawEventSchema.VERSION, RawEventSchema.VERSION);
            store.append(dimEvent(5000L), 0);
            assertThrows(ContractException.class, () -> store.append(dimEvent(4999L), 1),
                    "倒退的 tRelMs 必须被拒绝");
        }
    }

    @Nested
    @DisplayName("§2.1 崩溃恢复：只丢坏行")
    class CrashRecovery {

        @Test
        @DisplayName("最后一个不可解析行被跳过（只丢该行），其余事件仍可读回")
        void skipsOnlyBadLine(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            store.append(dimEvent(1000L), 0);
            store.append(dimEvent(2000L), 1);
            Path file = store.sessionFile(KEY, SID, 0);
            Files.write(file, "{\"type\":\"dimension_ente".getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);

            List<CaptureEvent> events = store.readFile(file);
            assertEquals(2, events.size(), "坏行必须被跳过，好的两行必须完整读回");
            assertTrue(store.truncation().truncatedStreams() >= 1,
                    "不可解析行必须计入 truncatedStreams，不得静默");
        }

        @Test
        @DisplayName("未知事件类型必须计入 unknownType 并丢弃（禁止落盘为未知事件）")
        void unknownTypeCounted(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            store.append(dimEvent(1000L), 0);
            Path file = store.sessionFile(KEY, SID, 0);
            Files.write(file, ("{\"schemaVersion\":\"" + RawEventSchema.VERSION
                            + "\",\"eventId\":\"e002\",\"sessionId\":\"s0001\",\"playerKey\":\"" + KEY
                            + "\",\"type\":\"mob_engaged\",\"tRelMs\":2000,\"tTick\":40,\"cat\":\"combat\","
                            + "\"src\":\"fabric\",\"confirmed\":true,\"payload\":{}}\n")
                            .getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);

            List<CaptureEvent> events = store.readFile(file);
            assertEquals(1, events.size(), "未知类型必须被丢弃");
            assertEquals(1L, store.truncation().unknownType(), "必须计入 unknownType");
        }

        @Test
        @DisplayName("重启恢复：recover() 后 usedBytes 与磁盘真实字节一致")
        void recoverMatchesDisk(@TempDir Path worldDir) throws IOException {
            RawEventStore first = new RawEventStore(worldDir);
            first.append(dimEvent(1000L), 0);
            first.append(dimEvent(2000L), 1);

            RawEventStore second = new RawEventStore(worldDir);
            assertEquals(0L, second.truncation().usedBytes(), "新实例账本应为空");
            long events = second.recover();
            assertEquals(2L, events, "recover 必须数出磁盘上的真实事件数");

            long onDisk = 0;
            try (var walk = Files.walk(second.eventsRoot())) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    onDisk += Files.size(p);
                }
            }
            assertEquals(onDisk, second.truncation().usedBytes(), "usedBytes 必须等于磁盘真实字节");
        }

        @Test
        @DisplayName("截断账本落盘后可读回（截断账本本身是快照，不是事件）")
        void truncationLedgerRoundTrip(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            store.append(dimEvent(1000L), 0);
            store.truncation().recordUnknownType();
            store.truncation().recordDroppedEvent();
            store.saveTruncationLedger();

            assertTrue(Files.isRegularFile(store.truncationFile()));
            TruncationLedger reloaded = TruncationLedger.fromJson(
                    Files.readString(store.truncationFile(), StandardCharsets.UTF_8));
            assertEquals(store.truncation().eventsWritten(), reloaded.eventsWritten());
            assertEquals(1L, reloaded.unknownType());
            assertEquals(1L, reloaded.droppedEvents());
            assertEquals(RawEventSchema.WORLD_STORAGE_CAP_BYTES, reloaded.capBytes());
        }
    }

    @Nested
    @DisplayName("§2.8 容量上限与超限行为")
    class CapacityAndEviction {

        @Test
        @DisplayName("常量回显：256 MiB 硬上限、0.90 软阈值、R1–R4 原因码")
        void capacityConstants() {
            assertEquals(268_435_456L, RawEventSchema.WORLD_STORAGE_CAP_BYTES);
            assertEquals(0.90d, RawEventSchema.STORAGE_SOFT_RATIO, 1e-9);
            assertEquals("drop_oldest", RawEventSchema.IF_FULL_POLICY);
            assertEquals(180, RawEventSchema.RETENTION_MAX_AGE_DAYS);
            assertEquals(0.50d, RawEventSchema.EVICTION_MAX_FRACTION, 1e-9);
            assertEquals(2_048, RawEventSchema.MAX_EVENT_BYTES);
        }

        @Test
        @DisplayName("超过硬上限：拒绝写入、计数丢弃、不上抛（R4 停止写入）")
        void overCapRejectsWithoutThrowing(@TempDir Path worldDir) {
            long smallCap = 1_200L;
            TruncationLedger ledger = new TruncationLedger(RawEventSchema.VERSION,
                    RawEventSchema.IF_FULL_POLICY, smallCap);
            RawEventStore store = new RawEventStore(worldDir, ledger);

            long written = 0;
            for (int i = 0; i < 10; i++) {
                written += store.append(dimEvent(1000L + i), i);
            }
            assertTrue(ledger.droppedEvents() > 0, "超限后必须计入 droppedEvents，不得静默丢弃");
            assertTrue(ledger.usedBytes() <= ledger.capBytes(), "usedBytes 不得超过硬上限");
            assertTrue(written > 0, "上限内仍应写入成功");
        }

        @Test
        @DisplayName("capExhausted 后必须停止写入事件，且保留账本")
        void capExhaustedStopsWriting(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            store.truncation().markCapExhausted(99L);
            long b = store.append(dimEvent(1000L), 0);
            assertEquals(0L, b, "R4 之后不得再写入事件");
            assertEquals(1L, store.truncation().droppedEvents());
            assertTrue(store.truncation().capExhausted());
            assertEquals(TruncationLedger.REASON_CAP_EXHAUSTED,
                    store.truncation().lastEviction().reason());

            store.saveTruncationLedger();
            TruncationLedger reloaded = TruncationLedger.fromJson(
                    Files.readString(store.truncationFile(), StandardCharsets.UTF_8));
            assertTrue(reloaded.capExhausted(), "账本必须被保留且如实标记 capExhausted");
        }

        @Test
        @DisplayName("软阈值判定：达到 0.90 才启动淘汰")
        void softThreshold() {
            TruncationLedger ledger = new TruncationLedger();
            ledger.setUsedBytes((long) (RawEventSchema.WORLD_STORAGE_CAP_BYTES * 0.89));
            assertFalse(ledger.aboveSoftThreshold(), "0.89 未达软阈值");
            ledger.setUsedBytes((long) (RawEventSchema.WORLD_STORAGE_CAP_BYTES * 0.90));
            assertTrue(ledger.aboveSoftThreshold(), "恰好 0.90 即应启动淘汰");
        }

        @Test
        @DisplayName("淘汰记录必须落进账本（atTick/reason/removedEvents/removedSessions）")
        void evictionIsRecorded() {
            TruncationLedger ledger = new TruncationLedger();
            ledger.recordEviction(992_134L, TruncationLedger.REASON_RETENTION_AGE, 512L, 2L);
            assertEquals(512L, ledger.droppedEvents());
            assertEquals(2L, ledger.droppedSessions());
            var le = ledger.lastEviction();
            assertEquals(992_134L, le.atTick());
            assertEquals("RETENTION_AGE", le.reason());
            assertEquals(512L, le.removedEvents());
            assertEquals(2L, le.removedSessions());
            assertTrue(ledger.toJson().contains("\"lastEviction\""));
        }
    }

    @Nested
    @DisplayName("§2.8.4 / §5.6 截断必须表达为 CAP_DATA_TRUNCATED")
    class TruncationIsExpressed {

        @Test
        @DisplayName("无丢弃时 dataTruncated=false，但仍给出原因码字段")
        void cleanLedger() {
            Map<String, Object> dq = new TruncationLedger().dataQuality();
            assertEquals(false, dq.get("dataTruncated"));
            assertEquals("CAP_DATA_TRUNCATED", dq.get("reasonCode"));
            assertEquals(0L, dq.get("droppedEvents"));
        }

        @Test
        @DisplayName("有任何丢弃/截断时必须 dataTruncated=true（禁止静默按 0 处理）")
        void truncatedIsExplicit() {
            TruncationLedger ledger = new TruncationLedger();
            ledger.recordDroppedEvent();
            Map<String, Object> dq = ledger.dataQuality();
            assertEquals(true, dq.get("dataTruncated"));
            assertEquals("CAP_DATA_TRUNCATED", dq.get("reasonCode"));
            assertEquals(1L, dq.get("droppedEvents"));

            TruncationLedger onlyStreams = new TruncationLedger();
            onlyStreams.recordTruncatedStream();
            assertEquals(true, onlyStreams.dataQuality().get("dataTruncated"));

            TruncationLedger onlySessions = new TruncationLedger();
            onlySessions.recordDroppedSessions(1L);
            assertEquals(true, onlySessions.dataQuality().get("dataTruncated"));
        }

        @Test
        @DisplayName("空原因码或自造原因码必须被拒绝")
        void reasonCodeClosedSet() {
            assertThrows(ContractException.class,
                    () -> new TruncationLedger.LastEviction(1L, "", 0L, 0L));
            assertThrows(ContractException.class,
                    () -> new TruncationLedger.LastEviction(1L, null, 0L, 0L));
        }
    }
}
