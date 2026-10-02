package com.octant.common.collection;

import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.model.Json;
import com.octant.common.model.PayloadSchema;
import com.octant.common.session.OpponentKeyAllocator;
import com.octant.common.session.SessionLedger;
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

class SessionLedgerAndOpponentKeyTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("冲突 1：会话属性不得进入事件 payload")
    class SessionAttributeBoundary {

        private static final List<String> SESSION_START_FIELDS = List.of(
                "worldCreateDay", "loadMs", "privacyClass", "gameVersion", "loader",
                "sessionStartDate", "sessionStartLocalBucket", "cheatsEnabled");

        private Map<String, Object> fullPayload() {
            return map("worldCreateDay", 12, "loadMs", 4200, "privacyClass", "singleplayer",
                    "gameVersion", "1.21.1", "loader", "neoforge", "sessionStartDate", "2026-09-26",
                    "sessionStartLocalBucket", "evening", "cheatsEnabled", false);
        }

        @Test
        @DisplayName("session_start payload 键集合与 §2.4 完全相同，且不含 locale")
        void sessionStartHasNoLocale() {
            Map<String, Object> encoded = PayloadSchema.encode(
                    CaptureEventType.SESSION_START, fullPayload(), 0L);
            assertEquals(SESSION_START_FIELDS, List.copyOf(encoded.keySet()),
                    "session_start 的键集合与键序必须与 dc §2.4 完全一致");
            assertFalse(encoded.containsKey("locale"),
                    "locale 是会话属性，登记为 not_collected.locale，不得进入事件 payload");
        }

        @Test
        @DisplayName("写入 locale 必须被 schema 拒绝（多一个键即违反）")
        void localeInPayloadRejected() {
            Map<String, Object> withLocale = fullPayload();
            withLocale.put("locale", "zh-CN");
            ContractException ex = assertThrows(ContractException.class, () -> PayloadSchema.encode(
                    CaptureEventType.SESSION_START, withLocale, 0L));
            assertTrue(ex.getMessage().contains("locale"), "错误必须指出越界键：" + ex.getMessage());
        }

        @Test
        @DisplayName("会话账本记录 startDate，且账本 JSON 不含任何事件 payload 键")
        void startDateLivesInLedgerOnly(@TempDir Path worldDir) throws IOException {
            SessionLedger ledger = new SessionLedger(worldDir);
            ledger.put(new SessionLedger.Record("s0001", "7f3a1c9b04d2e6a5", "2026-09-26",
                    5_400_000L, 4_800_000L, 600_000L, false, 4213));
            ledger.save();

            String json = Files.readString(ledger.ledgerFile(), StandardCharsets.UTF_8);
            Map<String, Object> root = Json.decodeObject(json);
            assertTrue(root.containsKey("sessions"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sessions = (List<Map<String, Object>>) root.get("sessions");
            assertEquals(1, sessions.size());
            Map<String, Object> row = sessions.get(0);
            assertEquals(List.of("sessionId", "playerKey", "startDate", "wallMs", "activeMs",
                            "afkMs", "openSession", "eventCount"),
                    List.copyOf(row.keySet()),
                    "账本字段序必须与 dc §2.6 一致");
            assertEquals("2026-09-26", row.get("startDate"), "会话开始 UTC 日期落在账本里");
        }

        @Test
        @DisplayName("账本仅本地：不得出现在任何事件 payload 键集合里")
        void ledgerFieldsAbsentFromPayloads() {
            for (String ledgerOnly : List.of("startDate", "wallMs", "afkMs", "openSession", "eventCount")) {
                ContractException ex = assertThrows(ContractException.class, () -> {
                    Map<String, Object> p = fullPayload();
                    p.put(ledgerOnly, 1L);
                    PayloadSchema.encode(CaptureEventType.SESSION_START, p, 0L);
                }, ledgerOnly + " 属会话账本字段，不得进入 session_start payload");
                assertTrue(ex.getMessage().contains(ledgerOnly));
            }
        }
    }

    @Nested
    @DisplayName("冲突 2：opponentKey = 会话内序号键")
    class OpponentKeys {

        @Test
        @DisplayName("同类型按首现顺序递增，不同类型各自计数，形态为 <entityType>#<n>")
        void allocatesPerTypeInSessionOrder() {
            OpponentKeyAllocator alloc = new OpponentKeyAllocator();
            assertEquals("minecraft:zombie#1", alloc.allocate("minecraft:zombie"));
            assertEquals("minecraft:zombie#2", alloc.allocate("minecraft:zombie"));
            assertEquals("minecraft:skeleton#1", alloc.allocate("minecraft:skeleton"));
            assertEquals("minecraft:zombie#3", alloc.allocate("minecraft:zombie"));
            assertEquals(3, alloc.allocatedCount("minecraft:zombie"));
            assertEquals(2, alloc.distinctTypes());
            assertEquals(4, alloc.totalAllocated());
        }

        @Test
        @DisplayName("每会话重置：新会话从 #1 重新开始（跨会话不可追踪）")
        void resetsPerSession() {
            OpponentKeyAllocator session1 = new OpponentKeyAllocator();
            assertEquals("minecraft:zombie#1", session1.allocate("minecraft:zombie"));
            assertEquals("minecraft:zombie#2", session1.allocate("minecraft:zombie"));
            assertEquals("minecraft:zombie#3", session1.allocate("minecraft:zombie"));

            OpponentKeyAllocator session2 = new OpponentKeyAllocator();
            assertEquals("minecraft:zombie#1", session2.allocate("minecraft:zombie"),
                    "新会话必须从 #1 重新开始，否则退化为跨会话可追踪的身份键");
            assertEquals(1, session2.allocatedCount("minecraft:zombie"));
        }

        @Test
        @DisplayName("合法形态通过检查；UUID / 自由文本 / 自定义名被拒绝")
        void shapeChecks() {
            OpponentKeyAllocator.checkShape("minecraft:zombie#1");
            OpponentKeyAllocator.checkShape("minecraft:zombie#42");

            assertThrows(ContractException.class, () -> OpponentKeyAllocator.checkShape("minecraft:zombie"),
                    "缺少 #<n> 必须拒绝");
            assertThrows(ContractException.class, () -> OpponentKeyAllocator.checkShape("minecraft:zombie#"),
                    "序号为空必须拒绝");
            assertThrows(ContractException.class, () -> OpponentKeyAllocator.checkShape("minecraft:zombie#0"),
                    "序号 0 必须拒绝");
            assertThrows(ContractException.class, () -> OpponentKeyAllocator.checkShape(
                    "069a79f4-44e9-4726-a5be-fca90e38aaf5#1"), "实体 UUID 不得作为键（REQ-PRIV-01）");
            assertThrows(ContractException.class, () -> OpponentKeyAllocator.checkShape("我的宠物 小白#1"),
                    "自由文本/自定义名必须拒绝");
        }

        @Test
        @DisplayName("entityType 含空白即拒绝（自定义名在类型层面不可表达）")
        void rejectFreeTextEntityType() {
            OpponentKeyAllocator alloc = new OpponentKeyAllocator();
            assertThrows(ContractException.class, () -> alloc.allocate("my pet zombie"));
            assertThrows(ContractException.class, () -> alloc.allocate(""));
        }

        @Test
        @DisplayName("「对手是另一名玩家」只能用枚举表达，不指向具体是谁")
        void playerOpponentIsEnumOnly() {
            assertEquals("other_player", OpponentKeyAllocator.OpponentKind.OTHER_PLAYER.code());
            assertEquals("self", OpponentKeyAllocator.OpponentKind.SELF.code());
            assertEquals(2, OpponentKeyAllocator.OpponentKind.values().length,
                    "闭集只允许 other_player / self 两个取值");
        }

        @Test
        @DisplayName("分配的键可直接通过 §2.4 的 payload 校验（与 schema 串联）")
        void allocatedKeyPassesSchema() {
            OpponentKeyAllocator alloc = new OpponentKeyAllocator();
            String key = alloc.allocate("minecraft:zombie");
            Map<String, Object> encoded = PayloadSchema.encode(CaptureEventType.COMBAT_STARTED,
                    map("opponentKey", key, "entityType", "minecraft:zombie", "opponentThreat", 20.0d,
                            "farmPattern", false, "shared", false, "durMs", 8_400L), 10_000L);
            assertEquals(key, encoded.get("opponentKey"));
        }
    }

    @Nested
    @DisplayName("会话账本：重启可恢复")
    class LedgerRecovery {

        @Test
        @DisplayName("保存后重新构造即可读回，且会话序号单调递增")
        void survivesRestart(@TempDir Path worldDir) throws IOException {
            SessionLedger first = new SessionLedger(worldDir);
            first.put(new SessionLedger.Record("s0001", "7f3a1c9b04d2e6a5", "2026-09-26",
                    5_400_000L, 4_800_000L, 600_000L, false, 4213));
            first.put(new SessionLedger.Record("s0002", "7f3a1c9b04d2e6a5", "2026-09-27",
                    3_600_000L, 3_600_000L, 0L, true, 1900));
            first.save();

            SessionLedger reloaded = new SessionLedger(worldDir);
            assertEquals(2, reloaded.load(), "重启后必须能恢复会话账本");
            assertEquals("2026-09-27", reloaded.get("s0002").orElseThrow().startDate());
            assertTrue(reloaded.get("s0002").orElseThrow().openSession());
            assertEquals(3L, reloaded.nextSessionSequence(),
                    "会话序号必须沿用历史最大值，不得重号");
        }

        @Test
        @DisplayName("账本缺失读回空；损坏时不得抛出且不破坏原文件")
        void missingAndCorruptAreSafe(@TempDir Path worldDir) throws IOException {
            SessionLedger ledger = new SessionLedger(worldDir);
            assertEquals(0, ledger.load(), "文件缺失不是错误");

            Files.createDirectories(ledger.ledgerFile().getParent());
            Files.writeString(ledger.ledgerFile(), "{ 损坏 ", StandardCharsets.UTF_8);
            assertEquals(0, ledger.load(), "损坏账本按空处理，不上抛");
            assertTrue(Files.exists(ledger.ledgerFile()), "不得删除/破坏既有账本文件");
        }

        @Test
        @DisplayName("不变量：activeMs + afkMs 必须等于 wallMs")
        void afkInvariant() {
            assertThrows(IllegalArgumentException.class, () -> new SessionLedger.Record(
                    "s0001", "7f3a1c9b04d2e6a5", "2026-09-26", 100L, 50L, 40L, false, 1));
        }
    }
}
