package com.octant.capture;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContainerSnapshotContractTest {

    private static final String PLAYER_KEY = "0123456789abcdef";
    private static final String SESSION = "s0001";

    private static Map<String, Object> row(String item, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", item);
        m.put("count", count);
        return m;
    }

    private static CaptureEvent translate(ObservationAdapter.ContainerSnapshot obs) {
        return ObservationAdapter.translate(obs, PLAYER_KEY, SESSION, 1, 120_000L, null);
    }

    @Test
    @DisplayName("正例：合法容器快照必须通过契约校验")
    void validSnapshotPasses() {
        CaptureEvent e = translate(new ObservationAdapter.ContainerSnapshot(
                "minecraft:generic_9x3", List.of(row("minecraft:stone", 64), row("minecraft:torch", 17)),
                false));
        assertNotNull(e, "合法取值必须翻译成功；原因：" + ObservationAdapter.lastTranslationFailure());
        assertEquals(CaptureEventType.CONTAINER_SNAPSHOT, e.type());
    }

    @Test
    @DisplayName("负控制 A：摘要元素数超过 32 必须被拒（上限真的在判）")
    void tooManyElementsIsRejected() {
        List<Map<String, Object>> big = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            big.add(row("minecraft:item_" + i, 1));
        }
        CaptureEvent e = translate(new ObservationAdapter.ContainerSnapshot(
                "minecraft:generic_9x3", big, true));
        assertNull(e, "33 个元素必须被拒（契约上限 32）");
        assertNotNull(ObservationAdapter.lastTranslationFailure(), "拒绝必须留下原因");
    }

    @Test
    @DisplayName("负控制 B：摘要元素缺少 count 键必须被拒（元素形态真的在判）")
    void malformedElementIsRejected() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("item", "minecraft:stone");
        CaptureEvent e = translate(new ObservationAdapter.ContainerSnapshot(
                "minecraft:generic_9x3", List.of(bad), false));
        assertNull(e, "元素缺 count 必须被拒（元素形态 {item, count} 是契约的一部分）");
    }
}
