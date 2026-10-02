package com.octant.common.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TruncationLedger {

    public static final String REASON_RETENTION_AGE = "RETENTION_AGE";

    public static final String REASON_SESSION_EVICT = "SESSION_EVICT";

    public static final String REASON_PREFIX_TRUNCATE = "PREFIX_TRUNCATE";

    public static final String REASON_CAP_EXHAUSTED = "CAP_EXHAUSTED";

    public static final String ANALYSIS_REASON_CODE = "CAP_DATA_TRUNCATED";

    private final String schemaVersion;
    private final String policy;
    private final long capBytes;
    private long usedBytes;
    private boolean capExhausted;
    private long eventsWritten;
    private long droppedEvents;
    private long droppedSessions;
    private long truncatedStreams;
    private long unknownType;
    private LastEviction lastEviction;

    public record LastEviction(long atTick, String reason, long removedEvents, long removedSessions) {

        public LastEviction {
            if (reason == null || reason.isBlank()) {
                throw new ContractException("淘汰原因必须是非空闭集取值");
            }
        }
    }

    public TruncationLedger() {
        this(RawEventSchema.VERSION, RawEventSchema.IF_FULL_POLICY, RawEventSchema.WORLD_STORAGE_CAP_BYTES);
    }

    public TruncationLedger(String schemaVersion, String policy, long capBytes) {
        this.schemaVersion = schemaVersion;
        this.policy = policy;
        this.capBytes = capBytes;
    }

    public long capBytes() {
        return capBytes;
    }

    public long usedBytes() {
        return usedBytes;
    }

    public boolean capExhausted() {
        return capExhausted;
    }

    public long eventsWritten() {
        return eventsWritten;
    }

    public long droppedEvents() {
        return droppedEvents;
    }

    public long droppedSessions() {
        return droppedSessions;
    }

    public long truncatedStreams() {
        return truncatedStreams;
    }

    public long unknownType() {
        return unknownType;
    }

    public LastEviction lastEviction() {
        return lastEviction;
    }

    public void recordEventWritten(long bytes) {
        eventsWritten++;
        usedBytes += Math.max(0L, bytes);
    }

    public void setUsedBytes(long bytes) {
        this.usedBytes = Math.max(0L, bytes);
    }

    public void recordDroppedEvent() {
        droppedEvents++;
    }

    public void recordDroppedSessions(long count) {
        droppedSessions += Math.max(0L, count);
    }

    public void recordTruncatedStream() {
        truncatedStreams++;
    }

    public void recordUnknownType() {
        unknownType++;
    }

    public void recordEviction(long atTick, String reason, long removedEvents, long removedSessions) {
        this.lastEviction = new LastEviction(atTick, reason, removedEvents, removedSessions);
        this.droppedEvents += Math.max(0L, removedEvents);
        this.droppedSessions += Math.max(0L, removedSessions);
    }

    public void markCapExhausted(long atTick) {
        this.capExhausted = true;
        this.lastEviction = new LastEviction(atTick, REASON_CAP_EXHAUSTED, 0L, 0L);
    }

    public boolean aboveSoftThreshold() {
        return usedBytes >= (long) (capBytes * RawEventSchema.STORAGE_SOFT_RATIO);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", schemaVersion);
        m.put("policy", policy);
        m.put("capBytes", capBytes);
        m.put("usedBytes", usedBytes);
        m.put("capExhausted", capExhausted);
        m.put("eventsWritten", eventsWritten);
        m.put("droppedEvents", droppedEvents);
        m.put("droppedSessions", droppedSessions);
        m.put("truncatedStreams", truncatedStreams);
        m.put("unknownType", unknownType);
        if (lastEviction != null) {
            Map<String, Object> le = new LinkedHashMap<>();
            le.put("atTick", lastEviction.atTick());
            le.put("reason", lastEviction.reason());
            le.put("removedEvents", lastEviction.removedEvents());
            le.put("removedSessions", lastEviction.removedSessions());
            m.put("lastEviction", le);
        }
        return m;
    }

    public String toJson() {
        return Json.encode(toMap());
    }

    public static TruncationLedger fromJson(String text) {
        Map<String, Object> m = Json.decodeObject(text);
        TruncationLedger ledger = new TruncationLedger(
                String.valueOf(m.getOrDefault("schemaVersion", RawEventSchema.VERSION)),
                String.valueOf(m.getOrDefault("policy", RawEventSchema.IF_FULL_POLICY)),
                asLong(m.get("capBytes"), RawEventSchema.WORLD_STORAGE_CAP_BYTES));
        ledger.usedBytes = asLong(m.get("usedBytes"), 0L);
        ledger.capExhausted = Boolean.TRUE.equals(m.get("capExhausted"));
        ledger.eventsWritten = asLong(m.get("eventsWritten"), 0L);
        ledger.droppedEvents = asLong(m.get("droppedEvents"), 0L);
        ledger.droppedSessions = asLong(m.get("droppedSessions"), 0L);
        ledger.truncatedStreams = asLong(m.get("truncatedStreams"), 0L);
        ledger.unknownType = asLong(m.get("unknownType"), 0L);
        if (m.get("lastEviction") instanceof Map<?, ?> le) {
            ledger.lastEviction = new LastEviction(
                    asLong(le.get("atTick"), 0L),
                    String.valueOf(le.get("reason")),
                    asLong(le.get("removedEvents"), 0L),
                    asLong(le.get("removedSessions"), 0L));
        }
        return ledger;
    }

    public Map<String, Object> dataQuality() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("droppedEvents", droppedEvents);
        m.put("droppedSessions", droppedSessions);
        m.put("truncatedStreams", truncatedStreams);
        m.put("unknownType", unknownType);
        m.put("dataTruncated", droppedEvents > 0 || droppedSessions > 0 || truncatedStreams > 0);
        m.put("reasonCode", ANALYSIS_REASON_CODE);
        return m;
    }

    private static long asLong(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }
}
