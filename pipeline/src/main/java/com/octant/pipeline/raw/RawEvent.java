package com.octant.pipeline.raw;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RawEvent {

    public static final long SESSION_MAX_TREL_MS = 604_800_000L;
    public static final long TICK_MS = 50L;

    private final String eventId;
    private final String sessionId;

    private final String playerKey;
    private final EventType type;
    private final long tRelMs;
    private final long tTick;
    private final long durMs;
    private final boolean confirmed;
    private final Map<String, Object> payload;

    public RawEvent(String eventId, String sessionId, EventType type, long tRelMs, long tTick,
                    long durMs, boolean confirmed, Map<String, Object> payload) {
        this(eventId, sessionId, "", type, tRelMs, tTick, durMs, confirmed, payload);
    }

    public RawEvent(String eventId, String sessionId, String playerKey, EventType type, long tRelMs,
                    long tTick, long durMs, boolean confirmed, Map<String, Object> payload) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.playerKey = playerKey == null ? "" : playerKey;
        this.type = Objects.requireNonNull(type, "type");
        if (tRelMs < 0L || tRelMs > SESSION_MAX_TREL_MS) {
            throw new IllegalArgumentException("tRelMs 必须 ∈ [0, " + SESSION_MAX_TREL_MS + "]，实际 " + tRelMs);
        }
        if (tTick < 0L) {
            throw new IllegalArgumentException("tTick 必须 ≥ 0，实际 " + tTick);
        }
        if (durMs < 0L) {
            throw new IllegalArgumentException("durMs 必须 ≥ 0，实际 " + durMs);
        }
        if (durMs > 0L && !type.interval()) {
            throw new IllegalArgumentException(type.wireName() + " 非区间型事件，禁止携带 durMs");
        }
        this.tRelMs = tRelMs;
        this.tTick = tTick;
        this.durMs = durMs;
        this.confirmed = confirmed;
        this.payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static RawEvent of(String eventId, String sessionId, EventType type, long tRelMs, long tTick) {
        return new RawEvent(eventId, sessionId, type, tRelMs, tTick, 0L, true, Map.of());
    }

    public String eventId() {
        return eventId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String playerKey() {
        return playerKey;
    }

    public RawEvent withPlayerKey(String key) {
        return new RawEvent(eventId, sessionId, key, type, tRelMs, tTick, durMs, confirmed, payload);
    }

    public EventType type() {
        return type;
    }

    public long tRelMs() {
        return tRelMs;
    }

    public long tTick() {
        return tTick;
    }

    public long durMs() {
        return durMs;
    }

    public boolean confirmed() {
        return confirmed;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public String str(String key) {
        Object v = payload.get(key);
        return v instanceof String s ? s : "";
    }

    public boolean bool(String key) {
        Object v = payload.get(key);
        return v instanceof Boolean b && b;
    }

    public double num(String key) {
        Object v = payload.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException(type.wireName() + "." + key + " 不是数值型 payload 字段");
    }

    public int intVal(String key) {
        return (int) Math.round(num(key));
    }

    public boolean has(String key) {
        return payload.containsKey(key);
    }

    public RawEvent withConfirmed(boolean value) {
        return new RawEvent(eventId, sessionId, type, tRelMs, tTick, durMs, value, payload);
    }

    @Override
    public String toString() {
        return eventId + " " + type.wireName() + " @" + tRelMs + "ms/" + tTick + "t"
                + (durMs > 0 ? " dur=" + durMs : "") + (confirmed ? "" : " [unconfirmed]")
                + (payload.isEmpty() ? "" : " " + payload);
    }
}
