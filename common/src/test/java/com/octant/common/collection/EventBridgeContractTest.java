package com.octant.common.collection;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.model.EventSource;
import com.octant.common.model.Json;
import com.octant.common.model.PayloadSchema;
import com.octant.common.model.RawEventSchema;
import com.octant.common.platforms.EventBridgeCore;
import com.octant.common.platforms.PlatformAdapter;
import com.octant.common.privacy.SaltProvider;
import com.octant.common.privacy.adapter.ConsentGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBridgeContractTest {

    private static final String RAW_PLAYER = "player-uuid-069a79f4-44e9-4726";

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private record FakeObservation(String kind, boolean playerInitiated, String rawPlayerId,
                                   Map<String, Object> payload) {
    }

    private static PlatformAdapter.EventTranslator fakeTranslator(long tRelMs, SaltProvider salt) {
        return obs -> {
            FakeObservation o = (FakeObservation) obs;
            String pseudonym = salt.pseudonymize(o.rawPlayerId());
            return new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(1),
                    CaptureEvent.sessionId(1), pseudonym, CaptureEventType.DIMENSION_ENTERED,
                    tRelMs, CaptureEvent.tickOf(tRelMs), CaptureEventType.DIMENSION_ENTERED.category(),
                    EventSource.FABRIC, o.playerInitiated(), null, o.payload());
        };
    }

    private static final class FakeWorld {
        final List<CaptureEvent> accepted = new ArrayList<>();
        final ConsentGate gate = new ConsentGate();
        final SaltProvider salt = SaltProvider.generate();
        final EventBridgeCore bridge;

        FakeWorld(boolean consented) {
            if (consented) {
                gate.grant("pi_3f7a91c4", Set.of("C1"), 268_435_456L, 180);
            }
            PlatformAdapter.EventSink sink = event -> {
                if (!gate.assertCollecting()) {
                    gate.recordDroppedByConsent();
                    return false;
                }
                accepted.add(event);
                return true;
            };
            this.bridge = new EventBridgeCore(sink);
        }

        FakeObservation ok() {
            return new FakeObservation("player", true, RAW_PLAYER, map("dimension", "minecraft:overworld"));
        }
    }

    @Nested
    @DisplayName("① 事件形状必须合 §2.4")
    class EventShape {

        @Test
        @DisplayName("缺字段的观测：翻译期抛错 → 被吞掉并计数，不上抛")
        void missingFieldIsSwallowedAndCounted() {
            FakeWorld w = new FakeWorld(true);
            FakeObservation bad = new FakeObservation("dimension_entered", true, RAW_PLAYER, map());
            assertFalse(w.bridge.translateAndSubmit(fakeTranslator(1000L, w.salt), bad),
                    "翻译失败必须返回 false");
            assertEquals(1L, w.bridge.translationFailures(), "必须计数");
            assertTrue(w.accepted.isEmpty(), "失败事件不得进入汇");
        }

        @Test
        @DisplayName("合法观测通过；产物封套字段与 §2.4 一致，payload 键集合与字段表相同")
        void wellFormedObservationPasses() {
            FakeWorld w = new FakeWorld(true);
            assertTrue(w.bridge.translateAndSubmit(fakeTranslator(1000L, w.salt), w.ok()));

            CaptureEvent e = w.accepted.get(0);
            assertEquals(RawEventSchema.VERSION, e.schemaVersion());
            assertEquals(CaptureEventType.DIMENSION_ENTERED, e.type());
            assertEquals(CaptureEventType.DIMENSION_ENTERED.category(), e.category());
            assertEquals(CaptureEvent.tickOf(e.tRelMs()), e.tTick(), "tTick 必须由 tRelMs 折算");
            Map<String, Object> encoded = PayloadSchema.encode(CaptureEventType.DIMENSION_ENTERED,
                    e.payload(), e.tRelMs());
            assertEquals(List.of("dimension", "fromDimension"), List.copyOf(encoded.keySet()),
                    "缺省补齐后键集合须与 §2.4 字段表一致");
        }
    }

    @Nested
    @DisplayName("② confirmed 语义：玩家主动 vs 机器自主")
    class ConfirmedSemantics {

        @Test
        @DisplayName("玩家主动 → true；机器自主 → false（原样透传，平台层不推断）")
        void confirmedIsPassedThroughNotInferred() {
            FakeWorld w = new FakeWorld(true);
            w.bridge.translateAndSubmit(fakeTranslator(1000L, w.salt),
                    new FakeObservation("player", true, RAW_PLAYER, map("dimension", "minecraft:overworld")));
            w.bridge.translateAndSubmit(fakeTranslator(2000L, w.salt),
                    new FakeObservation("machine", false, RAW_PLAYER, map("dimension", "minecraft:the_nether")));

            assertEquals(2, w.accepted.size());
            assertTrue(w.accepted.get(0).confirmed(), "玩家主动必须为 true");
            assertFalse(w.accepted.get(1).confirmed(),
                    "机器自主必须为 false —— 且**不得由平台层推断**，只能来自事件源");
        }
    }

    @Nested
    @DisplayName("③④ 失败与拒绝都必须计数、不得静默")
    class CountingDiscipline {

        @Test
        @DisplayName("翻译器抛 RuntimeException → 吞掉并计数（不上抛到游戏主循环）")
        void translatorThrowingIsSwallowed() {
            FakeWorld w = new FakeWorld(true);
            PlatformAdapter.EventTranslator throwing = obs -> {
                throw new IllegalStateException("模拟平台侧解析失败");
            };
            assertFalse(w.bridge.translateAndSubmit(throwing, "anything"));
            assertEquals(1L, w.bridge.translationFailures());
            assertEquals(0L, w.bridge.rejectedByConsent(), "这不是同意门拒绝，不应计入那一项");
        }

        @Test
        @DisplayName("翻译器返回 null → 计数（不把 null 送到汇）")
        void nullEventIsCounted() {
            FakeWorld w = new FakeWorld(true);
            assertFalse(w.bridge.translateAndSubmit(obs -> null, "x"));
            assertEquals(1L, w.bridge.translationFailures());
        }

        @Test
        @DisplayName("未同意时：事件被拒并计入 rejectedByConsent（不静默按 0）")
        void rejectedEventsAreCounted() {
            FakeWorld w = new FakeWorld(false);
            for (int i = 0; i < 3; i++) {
                assertFalse(w.bridge.translateAndSubmit(fakeTranslator(1000L + i, w.salt), w.ok()),
                        "未同意时不得有任何事件被接受");
            }
            assertEquals(3L, w.bridge.rejectedByConsent(), "被拒绝的事件必须计数");
            assertEquals(0L, w.bridge.submitted(), "未同意时不得有成功提交");
            assertTrue(w.accepted.isEmpty(), "未同意时汇必须为空（零产出）");
            assertEquals(3L, w.gate.droppedByConsent(), "同意门侧也要如实计数");
        }

        @Test
        @DisplayName("把 null 直接送 submit 必须抛错（防'翻译失败却照常落盘'）")
        void submitNullIsRejected() {
            FakeWorld w = new FakeWorld(true);
            assertThrows(ContractException.class, () -> w.bridge.submit(null));
        }
    }

    @Nested
    @DisplayName("⑤ 埋点不得绕过同意门（最关键的一条）")
    class NoConsentBypass {

        @Test
        @DisplayName("反复提交、换变体，都不能在未同意时产生任何落盘事件")
        void noBypassByVolumeOrVariety() {
            FakeWorld w = new FakeWorld(false);
            for (int i = 0; i < 50; i++) {
                w.bridge.translateAndSubmit(fakeTranslator(1000L + i, w.salt),
                        new FakeObservation("player", true, RAW_PLAYER,
                                map("dimension", i % 2 == 0 ? "minecraft:overworld" : "minecraft:the_nether")));
            }
            assertTrue(w.accepted.isEmpty(), "未同意时任何数量/任何变体都不得落盘");
            assertEquals(50L, w.bridge.rejectedByConsent());
            assertEquals(0L, w.bridge.submitted());
        }

        @Test
        @DisplayName("撤回后立即停止采集（桥不得缓存'刚才允许过'）")
        void revokeStopsImmediately() {
            FakeWorld w = new FakeWorld(true);
            assertTrue(w.bridge.translateAndSubmit(fakeTranslator(1000L, w.salt), w.ok()),
                    "已同意时应正常");
            assertEquals(1, w.accepted.size());

            w.gate.revoke();

            assertFalse(w.bridge.translateAndSubmit(fakeTranslator(2000L, w.salt), w.ok()),
                    "撤回后必须立即拒绝（桥不得缓存'刚才允许过'）");
            assertEquals(1, w.accepted.size(), "撤回后不得再新增落盘事件");
            assertEquals(1L, w.bridge.rejectedByConsent());
        }

        @Test
        @DisplayName("桥没有'当前是否允许采集'的状态查询（计数器不算；计数是我们要的）")
        void bridgeHasNoConsentStateQuery() {
            java.util.List<String> forbidden = java.util.List.of(
                    "iscollecting", "allows", "canoollect", "canCollect".toLowerCase(java.util.Locale.ROOT),
                    "assertcollecting", "grantedcategories", "isgranted", "consentstate");
            for (java.lang.reflect.Method m : EventBridgeCore.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(forbidden.contains(n),
                        "桥不得提供同意**状态查询**（否则成为第二判定点）：" + m.getName());
                assertFalse(m.getReturnType() == ConsentGate.class
                                || m.getReturnType() == com.octant.common.privacy.adapter.ConsentState.class,
                        "桥不得暴露同意对象：" + m.getName());
            }
            assertTrue(java.util.Arrays.stream(EventBridgeCore.class.getDeclaredMethods())
                            .anyMatch(m -> m.getName().equals("rejectedByConsent")),
                    "被拒绝的计数必须可查 —— 否则就变成静默按 0");
        }
    }

    @Nested
    @DisplayName("身份：原始玩家标识在类型层面就进不来")
    class NoRawIdentityLeak {

        @Test
        @DisplayName("经假名化后才进汇；产物中不含原值")
        void rawIdentifierNeverReachesSink() {
            FakeWorld w = new FakeWorld(true);
            w.bridge.translateAndSubmit(fakeTranslator(1000L, w.salt), w.ok());

            CaptureEvent e = w.accepted.get(0);
            assertTrue(e.playerKey().matches("[0-9a-f]{16}"),
                    "playerKey 必须是 16 位十六进制假名，实际：" + e.playerKey());
            String json = Json.encode(e.toOrderedMap());
            assertFalse(json.contains(RAW_PLAYER), "产物不得含原始玩家标识");
            assertFalse(json.contains("069a79f4"), "产物不得含 UUID 片段");
        }

        @Test
        @DisplayName("原始标识 / 形态非法的键在构造期被拒（让违规写不出来）")
        void rawIdentifierIsUnrepresentableInEvent() {
            assertThrows(ContractException.class, () -> new CaptureEvent(
                    RawEventSchema.VERSION, CaptureEvent.eventId(1), CaptureEvent.sessionId(1),
                    RAW_PLAYER, CaptureEventType.DIMENSION_ENTERED,
                    1000L, CaptureEvent.tickOf(1000L), CaptureEventType.DIMENSION_ENTERED.category(),
                    EventSource.FABRIC, true, null, map("dimension", "minecraft:overworld")),
                    "原始玩家标识不得成为 playerKey —— 让违规写不出来，而不是被抓住");
            assertThrows(ContractException.class, () -> new CaptureEvent(
                    RawEventSchema.VERSION, CaptureEvent.eventId(1), CaptureEvent.sessionId(1),
                    "069a79f4", CaptureEventType.DIMENSION_ENTERED,
                    1000L, CaptureEvent.tickOf(1000L), CaptureEventType.DIMENSION_ENTERED.category(),
                    EventSource.FABRIC, true, null, map("dimension", "minecraft:overworld")),
                    "非 16 位十六进制的键也必须拒绝");
        }
    }
}
