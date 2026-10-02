package com.octant.common.platforms;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;

import java.util.Map;

public final class PlatformAdapter {

    private PlatformAdapter() {
    }

    @FunctionalInterface
    public interface EventTranslator {
        CaptureEvent translate(Object platformObservation);
    }

    @FunctionalInterface
    public interface EventSink {
        boolean emit(CaptureEvent event);
    }

    @FunctionalInterface
    public interface CatalogProvider {
        CatalogSnapshot catalog();
    }

    record CatalogSnapshot(java.util.List<String> units, String source, boolean approximate) {

        CatalogSnapshot {
            if (units == null) {
                throw new ContractException("内容单元清单不得为 null（拿不到请用可得性三态表达）");
            }
            if (source == null || source.isBlank()) {
                throw new ContractException("必须说明清单来源（作者维护 / 长尾近似 / 平台注册表）");
            }
        }

        static CatalogSnapshot unavailable(String reason) {
            throw new ContractException("内容目录不可得：" + reason
                    + " —— 该情形必须用 PlatformCapabilities.Availability 表达，不得返回空清单");
        }
    }

    @FunctionalInterface
    public interface SessionClockProvider {
        long gameTicksElapsed();
    }

    public static void requireConsistent(CaptureEventType type, Map<String, Object> payload,
                                         int eventIdSeed) {
        if (type == null) {
            throw new ContractException("事件类型不得为空");
        }
        if (payload == null) {
            throw new ContractException("payload 不得为空（键集合必须与 dc §2.4 一致）");
        }
        new CaptureEvent(
                com.octant.common.model.RawEventSchema.VERSION,
                CaptureEvent.eventId(eventIdSeed < 1 || eventIdSeed > 999 ? 1 : eventIdSeed),
                CaptureEvent.sessionId(1),
                "0000000000000000",
                type,
                0L,
                CaptureEvent.tickOf(0L),
                type.category(),
                com.octant.common.model.EventSource.NEOFORGE,
                true,
                type.isInterval() ? 0L : null,
                payload);
    }

    public static void requireConsistent(CaptureEventType type, Map<String, Object> payload) {
        requireConsistent(type, payload, 1);
    }
}
