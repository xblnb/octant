package com.octant.capture;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.session.OpponentKeyAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatEncountersTest {

    private static final String PLAYER_KEY = "0123456789abcdef";
    private static final String SESSION = "s0001";

    private static CaptureEvent translate(ObservationAdapter.Observation obs, Long durMs) {
        return ObservationAdapter.translate(obs, PLAYER_KEY, SESSION, 1, 120_000L, durMs);
    }

    private static CombatEncounters table() {
        return new CombatEncounters(new OpponentKeyAllocator());
    }

    @Test
    @DisplayName("击杀：闭合出 kill/resolved，start 与 end 携带同一 durMs，且两条都过契约校验")
    void killClosesWithResolvedAndBothEventsValidate() {
        CombatEncounters t = table();
        t.onPlayerHit("u-1", "minecraft:zombie", 20.0d * 3.0d, 6.0d, false, 60_000L);
        t.onPlayerHit("u-1", "minecraft:zombie", 20.0d * 3.0d, 7.0d, false, 62_000L);
        CombatEncounters.Closed c = t.onOpponentDeath("u-1", 64_000L);

        assertNotNull(c, "闭合必须产出一次交战");
        assertEquals("kill", c.outcome());
        assertEquals("resolved", c.resolved());
        assertEquals("melee", c.tactic(), "非投射物伤害 ⇒ melee");
        assertEquals(60_000L, c.startMs(), "start 必须是**交战开始**的时刻，不是闭合时刻");
        assertEquals(4_000L, c.durMs());
        assertEquals("minecraft:zombie#1", c.opponentKey());
        OpponentKeyAllocator.checkShape(c.opponentKey());
        assertEquals(13.0d, c.damageDealt(), 1e-9);

        CaptureEvent started = translate(new ObservationAdapter.CombatStarted(
                c.opponentKey(), c.entityType(), c.opponentThreat(), c.farmPattern(), c.shared(),
                c.durMs()), c.durMs());
        CaptureEvent ended = translate(new ObservationAdapter.CombatEnded(
                c.opponentKey(), c.outcome(), c.damageDealt(), c.damageTaken(),
                c.lastHitByPlayer(), c.resolved(), c.tactic(), c.durMs()), c.durMs());
        assertNotNull(started, "combat_started 必须通过契约校验（键集合与 dc §2.4 一致）；原因："
                + ObservationAdapter.lastTranslationFailure());
        assertNotNull(ended, "combat_ended 必须通过契约校验；原因："
                + ObservationAdapter.lastTranslationFailure());
        assertEquals(CaptureEventType.COMBAT_STARTED, started.type());
        assertEquals(CaptureEventType.COMBAT_ENDED, ended.type());
    }

    @Test
    @DisplayName("负控制：区间型缺 durMs 必须被契约拒（否则'先握着再一起发'就是多余设计）")
    void intervalWithoutDurMsIsRejected() {
        CaptureEvent ev = translate(new ObservationAdapter.CombatStarted(
                "minecraft:zombie#1", "minecraft:zombie", 60.0d, false, false, 5_000L), null);
        assertNull(ev, "区间型缺 durMs 必须翻译失败（封套 ENVELOPE_DUR_REQUIRED_FOR_INTERVAL）");
    }

    @Test
    @DisplayName("契约上界：交战时长超过该事件自己的 tRelMs ⇒ 必须被拒（缺口的可判定形态）")
    void intervalDurationMayNotExceedItsOwnTimestamp() {
        CaptureEvent tooLong = ObservationAdapter.translate(
                new ObservationAdapter.CombatStarted("minecraft:zombie#1", "minecraft:zombie",
                        60.0d, false, false, 4_000L), PLAYER_KEY, SESSION, 1, 1_000L, 4_000L);
        assertNull(tooLong, "该组合必须被契约拒绝（durMs ≤ tRelMs）");
        String reason = ObservationAdapter.lastTranslationFailure();
        assertNotNull(reason, "拒绝必须留下原因（不得静默）");
        assertTrue(reason.contains("durMs"), "原因必须指向 durMs（实际：" + reason + "）");

        CaptureEvent ok = ObservationAdapter.translate(
                new ObservationAdapter.CombatStarted("minecraft:zombie#1", "minecraft:zombie",
                        60.0d, false, false, 4_000L), PLAYER_KEY, SESSION, 1, 60_000L, 4_000L);
        assertNotNull(ok, "开打时刻晚于时长时应当合法");
    }

    @Test
    @DisplayName("超时闭合记 timeout（不是 kill），未打到东西记 unresolved")
    void timeoutClosesAsTimeoutAndUnresolvedWhenNoDamage() {
        CombatEncounters t = table();
        t.onPlayerHit("u-2", "minecraft:cow", 10.0d, 0.0d, false, 1_000L);
        assertTrue(t.due(1_000L + CombatEncounters.TIMEOUT_MS - 1).isEmpty(),
                "未到超时阈值不得闭合");
        List<CombatEncounters.Closed> closed = t.due(1_000L + CombatEncounters.TIMEOUT_MS);
        assertEquals(1, closed.size());
        assertEquals("timeout", closed.get(0).outcome());
        assertEquals("unresolved", closed.get(0).resolved(), "一点伤害都没造成 ⇒ unresolved");
        assertEquals("", closed.get(0).tactic(), "MAY 字段判不出来时用空串（该列禁止 null）");
        assertFalse(t.hasOpen(), "闭合后不得再有未闭合交战");
    }

    @Test
    @DisplayName("共同参与：宠物/他人伤到同一目标 ⇒ shared=true 且威胁按其贡献累加")
    void foreignHitMarksSharedAndAddsThreat() {
        CombatEncounters t = table();
        t.onPlayerHit("u-3", "minecraft:skeleton", 20.0d * 2.0d, 5.0d, true, 1_000L);
        t.onForeignHit("u-3", "pet-1", 8.0d, 1_500L);
        CombatEncounters.Closed c = t.onOpponentDeath("u-3", 2_000L);
        assertNotNull(c);
        assertTrue(c.shared(), "有玩家以外的参与者 ⇒ shared 必须为真");
        assertEquals(48.0d, c.opponentThreat(), 1e-9, "威胁 = 目标贡献(40) + 参与者贡献(8)");
        assertEquals("ranged", c.tactic(), "玩家那一击来自投射物 ⇒ ranged");
    }

    @Test
    @DisplayName("刷怪式：同类目标在窗口内达到阈值 ⇒ farmPattern=true")
    void repeatedSameTypeWithinWindowIsFarmPattern() {
        CombatEncounters t = table();
        for (int i = 0; i < CombatEncounters.FARM_MIN_ENCOUNTERS; i++) {
            long at = 1_000L + i * 2_000L;
            t.onPlayerHit("farm-" + i, "minecraft:zombie", 30.0d, 4.0d, false, at);
        }
        CombatEncounters.Closed c = t.onOpponentDeath("farm-2", 6_000L);
        assertNotNull(c);
        assertTrue(c.farmPattern(), "同类型 " + CombatEncounters.FARM_MIN_ENCOUNTERS + " 次落在窗口内 ⇒ 刷怪式");
        assertEquals("minecraft:zombie#3", c.opponentKey(), "同类实例按键出现顺序递增");
    }

    @Test
    @DisplayName("玩家死亡：未闭合交战按 player_death 闭合（不是 timeout —— 两种打法不能混记）")
    void playerDeathClosesOpenEncountersAsPlayerDeath() {
        CombatEncounters t = table();
        t.onPlayerHit("u-4", "minecraft:creeper", 40.0d, 3.0d, false, 60_000L);
        t.onPlayerHurt("u-4", 12.0d, 61_000L);
        List<CombatEncounters.Closed> closed = t.closeAll(62_000L, "player_death");
        assertEquals(1, closed.size());
        CombatEncounters.Closed c = closed.get(0);
        assertEquals("player_death", c.outcome());
        assertEquals("unresolved", c.resolved());
        assertEquals(12.0d, c.damageTaken(), 1e-9);
        assertNotNull(translate(new ObservationAdapter.CombatEnded(c.opponentKey(), c.outcome(),
                c.damageDealt(), c.damageTaken(), c.lastHitByPlayer(), c.resolved(), c.tactic(),
                c.durMs()), c.durMs()), "player_death 是闭集内的合法取值，必须能翻译");
    }

    @Test
    @DisplayName("每会话重置：新会话的对手键必须重新从 #1 开始")
    void opponentKeysRestartPerSession() {
        CombatEncounters s1 = table();
        s1.onPlayerHit("a", "minecraft:zombie", 10.0d, 1.0d, false, 1_000L);
        s1.onPlayerHit("b", "minecraft:zombie", 10.0d, 1.0d, false, 1_100L);
        assertEquals("minecraft:zombie#2", s1.onOpponentDeath("b", 2_000L).opponentKey());

        CombatEncounters s2 = table();
        s2.onPlayerHit("c", "minecraft:zombie", 10.0d, 1.0d, false, 1_000L);
        assertEquals("minecraft:zombie#1", s2.onOpponentDeath("c", 2_000L).opponentKey(),
                "新会话必须从 #1 重新开始，否则退化为跨会话可追踪的身份键");
    }

    @Test
    @DisplayName("同刻秒杀：durMs 向上取到字段分辨率 1 ms（封套禁止 0，但不得编造时长）")
    void instantaneousKillStillCarriesNonZeroDuration() {
        CombatEncounters t = table();
        t.onPlayerHit("u-5", "minecraft:bat", 6.0d, 6.0d, false, 7_000L);
        CombatEncounters.Closed c = t.onOpponentDeath("u-5", 7_000L);
        assertEquals(1L, c.durMs());
        assertNotNull(translate(new ObservationAdapter.CombatStarted(c.opponentKey(), c.entityType(),
                c.opponentThreat(), c.farmPattern(), c.shared(), c.durMs()), c.durMs()));
    }
}
