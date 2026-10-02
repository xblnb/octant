package com.octant.pipeline.adapter;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CaptureEventAdapter {

    private static final Map<String, EventType> BY_WIRE_NAME = new LinkedHashMap<>();

    static {
        for (EventType t : EventType.values()) {
            BY_WIRE_NAME.put(t.wireName(), t);
        }
    }

    public record Conversion(List<RawEvent> events, List<String> rejections) {
        public Conversion {
            events = List.copyOf(events);
            rejections = List.copyOf(rejections);
        }

        public int acceptedCount() {
            return events.size();
        }

        public int rejectedCount() {
            return rejections.size();
        }
    }

    private CaptureEventAdapter() {
    }

    public static Optional<EventType> typeOf(String wireName) {
        return Optional.ofNullable(BY_WIRE_NAME.get(wireName));
    }

    public static RawEvent toPipeline(CaptureEvent e) {
        if (e == null) {
            throw new IllegalArgumentException("采集事件为 null");
        }
        String wireName = e.type().eventId();
        EventType type = BY_WIRE_NAME.get(wireName);
        if (type == null) {
            throw new IllegalArgumentException("分析层未知的采集事件线名：" + wireName
                    + "（两侧类型目录已漂移，需同步 dc §2.4 与 EventType）");
        }
        if (!type.category().equals(e.category().code())) {
            throw new IllegalArgumentException("类别漂移：" + wireName + " 采集侧="
                    + e.category().code() + "，分析侧=" + type.category());
        }
        if (type.interval() != e.type().isInterval()) {
            throw new IllegalArgumentException("区间性漂移：" + wireName + " 采集侧="
                    + e.type().isInterval() + "，分析侧=" + type.interval());
        }
        boolean durPresent = e.durMs() != null;
        if (durPresent != type.interval()) {
            throw new IllegalArgumentException("durMs 存在性与区间性不一致：" + wireName
                    + " durMs=" + e.durMs() + " interval=" + type.interval());
        }
        long durMs = durPresent ? e.durMs() : 0L;
        Object payloadDur = e.payload().get("durMs");
        if (type.interval()) {
            if (!(payloadDur instanceof Number n)) {
                throw new IllegalArgumentException("区间型事件的 payload 必须含 durMs（dc §2.4）：" + wireName);
            }
            if (n.longValue() != durMs) {
                throw new IllegalArgumentException("封套 durMs(" + durMs + ") 与 payload durMs("
                        + n.longValue() + ") 不一致：" + wireName);
            }
        } else if (payloadDur != null) {
            throw new IllegalArgumentException("标量型事件不得携带 durMs（dc §2.5）：" + wireName);
        }
        return new RawEvent(e.eventId(), e.sessionId(), e.playerKey(), type, e.tRelMs(), e.tTick(),
                durMs, e.confirmed(), e.payload());
    }

    public static Conversion toPipelineTolerant(List<CaptureEvent> events) {
        List<RawEvent> out = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        if (events == null) {
            return new Conversion(List.of(), List.of("输入事件列表为 null"));
        }
        for (CaptureEvent e : events) {
            if (e == null) {
                rejections.add("null 事件");
                continue;
            }
            try {
                out.add(toPipeline(e));
            } catch (ContractException | IllegalArgumentException ex) {
                rejections.add(e.eventId() + "(" + e.type().eventId() + "): "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        return new Conversion(out, rejections);
    }

    public static List<String> typeCatalogDivergence() {
        List<String> out = new ArrayList<>();
        Map<String, CaptureEventType> commonByName = CaptureEventType.all();
        for (Map.Entry<String, EventType> e : BY_WIRE_NAME.entrySet()) {
            CaptureEventType c = commonByName.get(e.getKey());
            if (c == null) {
                out.add("分析侧有、采集侧无：" + e.getKey());
                continue;
            }
            if (!e.getValue().category().equals(c.category().code())) {
                out.add("类别不一致：" + e.getKey() + " 分析=" + e.getValue().category()
                        + " 采集=" + c.category().code());
            }
            if (e.getValue().interval() != c.isInterval()) {
                out.add("区间性不一致：" + e.getKey() + " 分析=" + e.getValue().interval()
                        + " 采集=" + c.isInterval());
            }
        }
        for (String name : commonByName.keySet()) {
            if (!BY_WIRE_NAME.containsKey(name)) {
                out.add("采集侧有、分析侧无：" + name);
            }
        }
        return out;
    }

    public static Map<String, String> typeCatalogTable() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, CaptureEventType> e : CaptureEventType.all().entrySet()) {
            EventType mine = BY_WIRE_NAME.get(e.getKey());
            out.put(e.getKey(), mine == null ? "MISSING_IN_PIPELINE"
                    : mine.category() + "/" + (mine.interval() ? "interval" : "scalar"));
        }
        return out;
    }

    public static Map<EventType, Integer> histogram(List<RawEvent> events) {
        Map<EventType, Integer> out = new EnumMap<>(EventType.class);
        for (RawEvent e : events) {
            out.merge(e.type(), 1, Integer::sum);
        }
        return out;
    }

    public static List<String> pipelineWireNames() {
        List<String> out = new ArrayList<>(BY_WIRE_NAME.keySet());
        return List.copyOf(out);
    }
}
