package com.octant.common.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record CaptureEvent(String schemaVersion,
                           String eventId,
                           String sessionId,
                           String playerKey,
                           CaptureEventType type,
                           long tRelMs,
                           long tTick,
                           EventCategory category,
                           EventSource source,
                           boolean confirmed,
                           Long durMs,
                           Map<String, Object> payload) {

    public static final int TICK_MS = 50;

    public static final long SESSION_MAX_TREL_MS = 604_800_000L;

    private static final int EVENT_ID_DIGITS = 3;
    private static final int SESSION_ID_DIGITS = 4;
    private static final int PLAYER_KEY_HEX_LEN = 16;

    public CaptureEvent {
        if (schemaVersion == null || !schemaVersion.equals(RawEventSchema.VERSION)) {
            throw new ContractException("schemaVersion 必须是常量 " + RawEventSchema.VERSION
                    + "，实际：" + schemaVersion);
        }
        eventId = requireFormat(eventId, 'e', EVENT_ID_DIGITS, "eventId");
        sessionId = requireFormat(sessionId, 's', SESSION_ID_DIGITS, "sessionId");
        if (playerKey == null || playerKey.length() != PLAYER_KEY_HEX_LEN || !isLowerHex(playerKey)) {
            throw new ContractException(
                    "playerKey 必须是 HMAC 前 64 bit 的小写十六进制（16 字符），实际：" + playerKey);
        }
        if (type == null) {
            throw new ContractException("type 必须 ∈ EventType 枚举（dc §2.2），实际 null");
        }
        if (category != type.category()) {
            throw new ContractException(
                    "cat 与 type 不一致：type=" + type + " 应为 cat=" + type.category() + "，实际 " + category);
        }
        if (source == null) {
            throw new ContractException("src 必须 ∈ {forge, neoforge, fabric}");
        }
        if (tRelMs < 0 || tRelMs > SESSION_MAX_TREL_MS) {
            throw new ContractException("tRelMs 越界（§2.2 要求 [0, " + SESSION_MAX_TREL_MS + "]）：" + tRelMs);
        }
        if (tTick != tickOf(tRelMs)) {
            throw new ContractException(
                    "tTick 必须等于 round(tRelMs / 50)：tRelMs=" + tRelMs + " 期望 " + tickOf(tRelMs)
                            + "，实际 " + tTick);
        }
        PayloadSchema.checkDurPresence(type, durMs != null);
        payload = PayloadSchema.encode(type, payload, tRelMs);
    }

    public static long tickOf(long tRelMs) {
        return Math.round(tRelMs / (double) TICK_MS);
    }

    public static String eventId(int seqInSession) {
        if (seqInSession < 1 || seqInSession > 999) {
            throw new ContractException("会话内事件序号必须在 1..999（契约 e<3 位>）：" + seqInSession);
        }
        return String.format("e%0" + EVENT_ID_DIGITS + "d", seqInSession);
    }

    public static String sessionId(long worldSessionSeq) {
        if (worldSessionSeq < 1 || worldSessionSeq > 9999) {
            throw new ContractException("存档内会话序号必须在 1..9999（契约 s<4 位>）：" + worldSessionSeq);
        }
        return String.format("s%0" + SESSION_ID_DIGITS + "d", worldSessionSeq);
    }

    public Map<String, Object> toOrderedMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", schemaVersion);
        m.put("eventId", eventId);
        m.put("sessionId", sessionId);
        m.put("playerKey", playerKey);
        m.put("type", type.eventId());
        m.put("tRelMs", tRelMs);
        m.put("tTick", tTick);
        m.put("cat", category.code());
        m.put("src", source.code());
        m.put("confirmed", confirmed);
        if (durMs != null) {
            m.put("durMs", durMs);
        }
        m.put("payload", payload);
        return Collections.unmodifiableMap(m);
    }

    private static String requireFormat(String value, char prefix, int digits, String field) {
        if (value == null || value.length() != digits + 1 || value.charAt(0) != prefix) {
            throw new ContractException(field + " 格式违反（期望 " + prefix + "<" + digits + " 位数字>）：" + value);
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                throw new ContractException(field + " 含非数字字符：" + value);
            }
        }
        return value;
    }

    private static boolean isLowerHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
