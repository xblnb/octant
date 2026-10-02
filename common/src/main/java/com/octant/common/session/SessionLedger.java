package com.octant.common.session;

import com.octant.common.model.Json;
import com.octant.common.model.RawEventSchema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SessionLedger {

    public record Record(String sessionId,
                         String playerKey,
                         String startDate,
                         long wallMs,
                         long activeMs,
                         long afkMs,
                         boolean openSession,
                         long eventCount) {

        public Record {
            if (sessionId == null || !sessionId.matches("s\\d{4}")) {
                throw new IllegalArgumentException("sessionId 必须是 s<4 位>：" + sessionId);
            }
            if (startDate == null || !startDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new IllegalArgumentException("startDate 必须是日粒度 UTC 日期 YYYY-MM-DD：" + startDate);
            }
            if (activeMs < 0 || afkMs < 0 || activeMs + afkMs != wallMs) {
                throw new IllegalArgumentException(
                        "必须满足 activeMs + afkMs == wallMs：" + activeMs + "+" + afkMs + " != " + wallMs);
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", sessionId);
            m.put("playerKey", playerKey);
            m.put("startDate", startDate);
            m.put("wallMs", wallMs);
            m.put("activeMs", activeMs);
            m.put("afkMs", afkMs);
            m.put("openSession", openSession);
            m.put("eventCount", eventCount);
            return m;
        }

        static Record fromMap(Map<String, Object> m) {
            return new Record(
                    String.valueOf(m.get("sessionId")),
                    String.valueOf(m.get("playerKey")),
                    String.valueOf(m.get("startDate")),
                    asLong(m.get("wallMs")),
                    asLong(m.get("activeMs")),
                    asLong(m.get("afkMs")),
                    Boolean.TRUE.equals(m.get("openSession")),
                    asLong(m.get("eventCount")));
        }

        private static long asLong(Object o) {
            return o instanceof Number n ? n.longValue() : 0L;
        }
    }

    private final Path ledgerFile;
    private final Map<String, Record> records = new LinkedHashMap<>();

    public SessionLedger(Path worldDir) {
        this.ledgerFile = com.octant.common.privacy.OctantPaths.dataDir(worldDir).resolve("state").resolve("sessions.json");
    }

    public Path ledgerFile() {
        return ledgerFile;
    }

    public int load() {
        records.clear();
        if (!Files.isRegularFile(ledgerFile)) {
            return 0;
        }
        try {
            Map<String, Object> root = Json.decodeObject(
                    Files.readString(ledgerFile, StandardCharsets.UTF_8));
            Object sessions = root.get("sessions");
            if (sessions instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> raw) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) raw;
                        Record r = Record.fromMap(m);
                        records.put(r.sessionId(), r);
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            records.clear();
        }
        return records.size();
    }

    public Record put(Record record) {
        records.put(record.sessionId(), record);
        return record;
    }

    public int size() {
        return records.size();
    }

    public Optional<Record> get(String sessionId) {
        return Optional.ofNullable(records.get(sessionId));
    }

    public List<Record> all() {
        List<Record> out = new ArrayList<>(records.values());
        out.sort(java.util.Comparator.comparing(Record::sessionId));
        return Collections.unmodifiableList(out);
    }

    public long nextSessionSequence() {
        long max = 0;
        for (String id : records.keySet()) {
            max = Math.max(max, Long.parseLong(id.substring(1)));
        }
        return max + 1;
    }

    public void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", RawEventSchema.VERSION);
        List<Object> sessions = new ArrayList<>();
        for (Record r : all()) {
            sessions.add(r.toMap());
        }
        root.put("sessions", sessions);
        com.octant.common.privacy.adapter.ConsentStore.writeAtomically(ledgerFile, Json.encode(root));
    }
}
