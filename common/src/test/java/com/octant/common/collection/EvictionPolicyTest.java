package com.octant.common.collection;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.EventSource;
import com.octant.common.model.RawEventSchema;
import com.octant.common.model.TruncationLedger;
import com.octant.common.session.EvictionPolicy;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvictionPolicyTest {

    private static final String KEY = "7f3a1c9b04d2e6a5";
    private static final long DAY_MS = 86_400_000L;
    private static final long NOW = 1_800_000_000_000L;

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static CaptureEvent event(String sessionId, int seq, long tRelMs) {
        return new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(seq), sessionId, KEY,
                CaptureEventType.DIMENSION_ENTERED, tRelMs, CaptureEvent.tickOf(tRelMs),
                CaptureEventType.DIMENSION_ENTERED.category(), EventSource.FABRIC, true, null,
                map("dimension", "minecraft:overworld"));
    }

    private static void writeSession(Path eventsRoot, String sessionId, long startTRel, int n,
                                     boolean withBoundary) throws IOException {
        Path f = eventsRoot.resolve(KEY).resolve(sessionId + ".jsonl");
        for (int i = 0; i < n; i++) {
            EvictionPolicy.appendRawLine(f, event(sessionId, i + 1, startTRel + i * 1000L));
        }
        if (withBoundary) {
            EvictionPolicy.appendRawLine(eventsRoot.resolve(KEY).resolve(sessionId + "-b.jsonl"),
                    new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(1), sessionId, KEY,
                            CaptureEventType.SESSION_START, startTRel, CaptureEvent.tickOf(startTRel),
                            CaptureEventType.SESSION_START.category(), EventSource.FABRIC, true, null,
                            map("privacyClass", "singleplayer", "gameVersion", "1.21.1",
                                    "loader", "neoforge", "sessionStartDate", "2026-01-01",
                                    "cheatsEnabled", false)));
        }
    }

    private static long diskBytes(Path root) throws IOException {
        long t = 0;
        try (var w = Files.walk(root)) {
            for (Path p : (Iterable<Path>) w.filter(Files::isRegularFile)::iterator) {
                t += Files.size(p);
            }
        }
        return t;
    }

    private static long biggestFile(Path root) throws IOException {
        long b = 0;
        try (var w = Files.walk(root)) {
            for (Path p : (Iterable<Path>) w.filter(Files::isRegularFile)::iterator) {
                b = Math.max(b, Files.size(p));
            }
        }
        return b;
    }

    private static TruncationLedger coherentCap(Path eventsRoot) throws IOException {
        long disk = diskBytes(eventsRoot);
        long biggest = biggestFile(eventsRoot);
        long afterOneDeletion = Math.max(1L, disk - biggest);
        long cap = Math.max(disk + 32L, (long) (afterOneDeletion / 0.80));
        TruncationLedger ledger = new TruncationLedger(RawEventSchema.VERSION,
                RawEventSchema.IF_FULL_POLICY, cap);
        ledger.setUsedBytes(disk);
        return ledger;
    }

    @Nested
    @DisplayName("R1：归档过期会话（判据是事件时间，不是墙钟）")
    class RetentionAge {

        @Test
        @DisplayName("过期会话被删、未过期会话幸存；原因码为 RETENTION_AGE 且计入 droppedEvents")
        void onlyExpiredSessionsRemoved(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            long expiredAnchor = NOW - 400 * DAY_MS;
            long freshAnchor = NOW - 5 * DAY_MS;
            writeSession(store.eventsRoot(), "s0001", 0L, 5, false);
            writeSession(store.eventsRoot(), "s0003", 0L, 5, false);

            RawEventStore small = new RawEventStore(worldDir, coherentCap(store.eventsRoot()));
            EvictionPolicy policy = new EvictionPolicy(small, NOW, expiredAnchor);
            EvictionPolicy.Result r = policy.evictIfNeeded(KEY);

            assertTrue(r.removedEvents() > 0,
                    "过期会话必须被淘汰（实际 reason=" + r.reason() + "）");
            assertEquals(TruncationLedger.REASON_RETENTION_AGE, r.reason(),
                    "R1 必须先于 R2/R3 生效");
            assertTrue(small.truncation().droppedEvents() > 0,
                    "被淘汰的事件必须计入 droppedEvents，不得静默");

            EvictionPolicy forFresh = new EvictionPolicy(small, NOW, freshAnchor);
            assertTrue(forFresh.ageDaysOf(0L) <= RawEventSchema.RETENTION_MAX_AGE_DAYS,
                    "同一份 tRelMs 在 5 天前的锚点下不得过期 —— 证明判据是事件时间而非墙钟");
            assertEquals(400L, policy.ageDaysOf(0L), "400 天前的锚点应算出 400 天");
        }

        @Test
        @DisplayName("未达软阈值时不淘汰")
        void belowSoftThresholdDoesNothing(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            writeSession(store.eventsRoot(), "s0001", 0L, 3, false);
            store.truncation().setUsedBytes(1000L);
            EvictionPolicy policy = new EvictionPolicy(store, NOW, NOW - 400 * DAY_MS);

            EvictionPolicy.Result r = policy.evictIfNeeded(KEY);
            assertEquals(0L, r.removedEvents());
            assertFalse(r.capExhausted());
            assertTrue(Files.exists(store.eventsRoot().resolve(KEY).resolve("s0001.jsonl")));
        }
    }

    @Nested
    @DisplayName("R3：会话内前缀删除的三项保护条件")
    class PrefixTruncationProtection {

        @Test
        @DisplayName("含 session_start/session_end 的会话受保护，不做前缀删除")
        void boundaryLinesAreProtected(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            writeSession(store.eventsRoot(), "s0009", 0L, 5, true);
            EvictionPolicy policy = new EvictionPolicy(store, NOW, NOW);

            long removed = policy.truncatePrefix(
                    store.eventsRoot().resolve(KEY).resolve("s0009.jsonl"));
            assertEquals(0L, removed, "含会话边界的文件不得做前缀删除（保护条件①②）");
        }

        @Test
        @DisplayName("最近一小时内的行受保护，只允许删更早的前缀")
        void recentWindowProtected(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            Path f = store.eventsRoot().resolve(KEY).resolve("s0010.jsonl");
            for (int i = 0; i <= 12; i++) {
                EvictionPolicy.appendRawLine(f, event("s0010", i + 1, i * 600_000L));
            }
            EvictionPolicy policy = new EvictionPolicy(store, NOW, NOW);
            long removed = policy.truncatePrefix(f);

            assertTrue(removed > 0, "更早的前缀应被删除");
            assertTrue(removed < 13, "最近一小时的行必须被保留");
            List<String> kept = Files.readAllLines(f, StandardCharsets.UTF_8);
            long minKept = kept.stream().mapToLong(EvictionPolicyTest::tRelOf).min().orElse(-1L);
            assertTrue(minKept >= 3_600_000L,
                    "被保留的最早行必须落在保护窗（最后一小时）内，实际 tRelMs=" + minKept);
        }
    }

    private static long tRelOf(String line) {
        int i = line.indexOf("\"tRelMs\":");
        if (i < 0) {
            return -1L;
        }
        int j = i + 9;
        int end = j;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(line.substring(j, end));
    }

    @Nested
    @DisplayName("R4、单次上限与如实记账")
    class CapExhaustedAndMaxFraction {

        @Test
        @DisplayName("无可淘汰量时不做删除、也不把自己锁死为 capExhausted")
        void nothingRemovableIsNoop(@TempDir Path worldDir) {
            RawEventStore store = new RawEventStore(worldDir);
            store.truncation().setUsedBytes((long) (RawEventSchema.WORLD_STORAGE_CAP_BYTES * 0.95));
            EvictionPolicy policy = new EvictionPolicy(store, NOW, NOW);

            EvictionPolicy.Result r = policy.evictIfNeeded(KEY);
            assertEquals(0L, r.removedEvents());
            assertFalse(r.capExhausted(),
                    "磁盘上没有可淘汰事件时不应把采集永久锁死；写盘拒绝由 store 的硬上限负责");
        }

        @Test
        @DisplayName("R4：受保护会话无可删除行 → 停止写入并记账本")
        void capExhaustedWhenProtected(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            writeSession(store.eventsRoot(), "s0001", 0L, 5, true);
            RawEventStore small = new RawEventStore(worldDir, coherentCap(store.eventsRoot()));
            EvictionPolicy policy = new EvictionPolicy(small, NOW, NOW - 5 * DAY_MS);

            policy.evictIfNeeded(KEY);
            assertTrue(small.truncation().dataQuality().containsKey("reasonCode"));
            assertEquals("CAP_DATA_TRUNCATED", small.truncation().dataQuality().get("reasonCode"));
        }

        @Test
        @DisplayName("capExhausted 之后不得再发生淘汰（幂等且不删账本）")
        void alreadyExhaustedIsNoop(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            writeSession(store.eventsRoot(), "s0001", 0L, 3, false);
            store.truncation().markCapExhausted(0L);
            EvictionPolicy policy = new EvictionPolicy(store, NOW, NOW);

            EvictionPolicy.Result r = policy.evictIfNeeded(KEY);
            assertEquals(0L, r.removedEvents());
            assertTrue(Files.exists(store.eventsRoot().resolve(KEY).resolve("s0001.jsonl")),
                    "已 capExhausted 时不得继续删除");
        }

        @Test
        @DisplayName("单次淘汰不得超过当时总事件量的 50%（禁止一次清空）")
        void maxFractionRespected(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            for (int s = 1; s <= 4; s++) {
                writeSession(store.eventsRoot(), String.format("s%04d", s), 0L, 5, false);
            }
            RawEventStore small = new RawEventStore(worldDir, coherentCap(store.eventsRoot()));
            EvictionPolicy policy = new EvictionPolicy(small, NOW, NOW - 400 * DAY_MS);
            EvictionPolicy.Result r = policy.evictIfNeeded(KEY);

            assertTrue(r.removedEvents() <= 10,
                    "单次淘汰不得超过总事件量的 50%，实际删除 " + r.removedEvents());
            assertTrue(r.removedEvents() > 0, "应至少删掉一个过期会话");
        }

        @Test
        @DisplayName("原因码取闭集并写进 lastEviction；淘汰事件计入 droppedEvents")
        void reasonCodesAreClosedSet(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            writeSession(store.eventsRoot(), "s0001", 0L, 5, false);
            RawEventStore small = new RawEventStore(worldDir, coherentCap(store.eventsRoot()));
            EvictionPolicy policy = new EvictionPolicy(small, NOW, NOW - 400 * DAY_MS);
            EvictionPolicy.Result r = policy.evictIfNeeded(KEY);

            assertTrue(r.removedEvents() > 0, "过期会话必须被淘汰（实际 reason=" + r.reason() + "）");
            TruncationLedger.LastEviction le = small.truncation().lastEviction();
            assertNotNull(le, "淘汰必须写 lastEviction");
            assertTrue(List.of(TruncationLedger.REASON_RETENTION_AGE,
                            TruncationLedger.REASON_SESSION_EVICT,
                            TruncationLedger.REASON_PREFIX_TRUNCATE,
                            TruncationLedger.REASON_CAP_EXHAUSTED).contains(le.reason()),
                    "淘汰原因必须属于 R1–R4 闭集，实际 " + le.reason());
            assertTrue(small.truncation().droppedEvents() > 0,
                    "被淘汰的事件必须计入 droppedEvents，不得静默");
            assertEquals(r.removedEvents(), le.removedEvents(),
                    "报告值必须与实际删除量一致（不得多算没被删的事件）");
        }

        @Test
        @DisplayName("容量淘汰不得卷入账本（§2.8 R4：淘汰保留账本，与玩家主动清除相反）")
        void evictionNeverDeletesLedgers(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            writeSession(store.eventsRoot(), "s0001", 0L, 5, false);
            store.truncation().recordDroppedEvent();
            store.saveTruncationLedger();
            Path truncationFile = store.truncationFile();
            assertTrue(Files.isRegularFile(truncationFile));

            RawEventStore small = new RawEventStore(worldDir, coherentCap(store.eventsRoot()));
            small.saveTruncationLedger();
            EvictionPolicy policy = new EvictionPolicy(small, NOW, NOW - 400 * DAY_MS);
            policy.evictIfNeeded(KEY);

            assertTrue(Files.isRegularFile(truncationFile),
                    "容量淘汰**必须保留**截断账本（保护证据链，dc §2.8 R4）");
            assertTrue(Files.isDirectory(store.eventsRoot()),
                    "淘汰只删事件文件，不得删目录结构");
        }

        @Test
        @DisplayName("单次调用不得删除超过该次总量的 50%（防止一次几乎清空历史）")
        void maxFractionIsPerCallNotCumulative(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            for (int s = 1; s <= 8; s++) {
                writeSession(store.eventsRoot(), String.format("s%04d", s), 0L, 5, false);
            }
            long disk = diskBytes(store.eventsRoot());
            TruncationLedger ledger = new TruncationLedger(RawEventSchema.VERSION,
                    RawEventSchema.IF_FULL_POLICY, (long) (disk / 0.90));
            ledger.setUsedBytes(disk);
            RawEventStore small = new RawEventStore(worldDir, ledger);
            EvictionPolicy policy = new EvictionPolicy(small, NOW, NOW - 400 * DAY_MS);

            EvictionPolicy.Result r = policy.evictIfNeeded(KEY);

            assertTrue(r.removedEvents() > 0,
                    "超阈值后必须真的删（实际 reason=" + r.reason() + "）");
            assertTrue(r.removedEvents() <= 20,
                    "单次调用不得超过当时总量(40)的 50%，实际 " + r.removedEvents());
            assertTrue(r.reachedTarget() || r.capExhausted() || r.removedEvents() < 20,
                    "单次调用不得在未达标时仍把额度用到超过上限");

            if (!r.reachedTarget() && !r.capExhausted()) {
                RawEventStore second = new RawEventStore(worldDir, ledger);
                EvictionPolicy.Result r2 = new EvictionPolicy(second, NOW, NOW - 400 * DAY_MS)
                        .evictIfNeeded(KEY);
                assertTrue(r2.removedEvents() > 0,
                        "预算应每次调用重算（非累计），第二次仍应能删，实际=" + r2.removedEvents());
            }
        }

        @Test
        @DisplayName("下限不变量：存在可淘汰对象时，淘汰必须真的至少删掉一个（否则容量永远降不下来）")
        void atLeastOneRemovalWhenSomethingIsEvictable(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            for (int s = 1; s <= 6; s++) {
                writeSession(store.eventsRoot(), String.format("s%04d", s), 0L, 10, false);
            }
            RawEventStore small = new RawEventStore(worldDir, coherentCap(store.eventsRoot()));
            EvictionPolicy policy = new EvictionPolicy(small, NOW, NOW - 400 * DAY_MS);
            EvictionPolicy.Result r = policy.evictIfNeeded(KEY);

            assertTrue(r.removedEvents() > 0,
                    "存在过期会话时必须真的删除；否则容量永不回落，capExhausted 会变成永久停止写入"
                            + "（实际 reason=" + r.reason() + ", removed=" + r.removedEvents() + "）");
            assertFalse(r.capExhausted(),
                    "有可淘汰对象时不得误报 CAP_EXHAUSTED");
            assertNotEquals(TruncationLedger.REASON_CAP_EXHAUSTED, r.reason(),
                    "R1（归档过期）与 R4（容量耗尽）两条原因码分支不得互相吞掉");
        }

        @Test
        @DisplayName("事件量极小（50% 向下取整为 0）时不得误判为「无可淘汰」而卡死")
        void tinyTotalDoesNotDeadlockCapacity(@TempDir Path worldDir) throws IOException {
            RawEventStore store = new RawEventStore(worldDir);
            writeSession(store.eventsRoot(), "s0001", 0L, 1, false);
            RawEventStore small = new RawEventStore(worldDir, coherentCap(store.eventsRoot()));
            EvictionPolicy policy = new EvictionPolicy(small, NOW, NOW - 400 * DAY_MS);

            policy.evictIfNeeded(KEY);
            assertFalse(small.truncation().capExhausted(),
                    "50% 取整为 0 时不得把采集锁死为永久 capExhausted");
            assertTrue(small.truncation().dataQuality().containsKey("reasonCode"));
        }

        @Test
        @DisplayName("dataQuality 携带 lastEvictionReason，淘汰不被静默表达")
        void dataQualityCarriesReason(@TempDir Path worldDir) {
            RawEventStore store = new RawEventStore(worldDir);
            store.truncation().recordEviction(0L, TruncationLedger.REASON_SESSION_EVICT, 7L, 1L);
            EvictionPolicy policy = new EvictionPolicy(store, 1L, 1L);

            Map<String, Object> dq = policy.dataQuality();
            assertEquals("SESSION_EVICT", dq.get("lastEvictionReason"));
            assertEquals(true, dq.get("dataTruncated"));
            assertEquals("CAP_DATA_TRUNCATED", dq.get("reasonCode"));
            assertEquals(7L, dq.get("droppedEvents"));
        }
    }
}
