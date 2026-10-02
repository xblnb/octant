package com.octant.capture;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItemActionContractTest {

    private static final String PLAYER_KEY = "0123456789abcdef";
    private static final String SESSION = "s0001";

    private static CaptureEvent translate(ObservationAdapter.ItemAction obs) {
        return ObservationAdapter.translate(obs, PLAYER_KEY, SESSION, 1, 120_000L, null);
    }

    @Test
    @DisplayName("正例：合法 item_action 必须通过契约校验，且是标量型（不带 durMs）")
    void validItemActionPasses() {
        CaptureEvent e = translate(new ObservationAdapter.ItemAction(
                "minecraft:oak_planks", "craft_output", 4, false));
        assertNotNull(e, "合法取值必须翻译成功；失败原因：" + ObservationAdapter.lastTranslationFailure());
        assertEquals(CaptureEventType.ITEM_ACTION, e.type());
    }

    @Test
    @DisplayName("负控制 A：count=0 必须被拒（契约下界为 1）")
    void zeroCountIsRejected() {
        CaptureEvent e = translate(new ObservationAdapter.ItemAction(
                "minecraft:oak_planks", "craft_output", 0, false));
        assertNull(e, "count 低于下界必须翻译失败（否则下游会出现 0 件的物品动作）");
        assertNotNull(ObservationAdapter.lastTranslationFailure(), "拒绝必须留下原因（不得静默）");
    }

    @Test
    @DisplayName("负控制 B：闭集外的 action 必须被拒（证明枚举真的在判）")
    void unknownActionIsRejected() {
        CaptureEvent e = translate(new ObservationAdapter.ItemAction(
                "minecraft:apple", "eat", 1, false));
        assertNull(e, "闭集外的 action 必须翻译失败（eat 不在 obtain|consume|drop|craft_output 内）");
    }
}
