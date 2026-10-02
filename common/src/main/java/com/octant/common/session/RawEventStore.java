package com.octant.common.session;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.model.EventCategory;
import com.octant.common.model.EventSource;
import com.octant.common.model.Json;
import com.octant.common.model.RawEventSchema;
import com.octant.common.model.TruncationLedger;
import com.octant.common.privacy.adapter.ConsentStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RawEventStore {

    private final Path baseDir;
    private final TruncationLedger truncation;
    private long lastTRelMs = Long.MIN_VALUE;
    private String currentFileKey = "";

    public RawEventStore(Path worldDir) {
        this(worldDir, new TruncationLedger());
    }

    public RawEventStore(Path worldDir, TruncationLedger truncation) {
        this.baseDir = com.octant.common.privacy.OctantPaths.dataDir(worldDir);
        this.truncation = truncation;
    }

    public Path eventsRoot() {
        return baseDir.resolve("events").resolve("raw");
    }

    public Path truncationFile() {
        return baseDir.resolve("state").resolve("truncation.json");
    }

    public TruncationLedger truncation() {
        return truncation;
    }

    public long lastTRelMs() {
        return lastTRelMs;
    }

    public Path sessionFile(String playerKey, String sessionId, int eventIndexInSession) {        requireToken(playerKey, "playerKey");
        requireToken(sessionId, "sessionId");
        int part = eventIndexInSession / RawEventSchema.SESSION_FILE_SPLIT_N;
        String suffix = part == 0 ? "" : "-" + (char) ('a' + (part - 1));
        return eventsRoot().resolve(playerKey).resolve(sessionId + suffix + ".jsonl");
    }

    public long append(CaptureEvent event, int eventIndexInSession) {
        if (truncation.capExhausted()) {
            truncation.recordDroppedEvent();
            return 0L;
        }
        if (event.tRelMs() < lastTRelMs) {
            throw new ContractException("同一流内 tRelMs 必须单调非递减（§2.1）："
                    + event.tRelMs() + " < " + lastTRelMs);
        }
        byte[] line = (Json.encode(event.toOrderedMap()) + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.length > RawEventSchema.MAX_EVENT_BYTES) {
            truncation.recordDroppedEvent();
            return 0L;
        }
        long projected = truncation.usedBytes() + line.length;
        if (projected > truncation.capBytes()) {
            truncation.recordDroppedEvent();
            truncation.setUsedBytes(Math.max(truncation.usedBytes(), truncation.capBytes()));
            return 0L;
        }
        Path file = sessionFile(event.playerKey(), event.sessionId(), eventIndexInSession);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            truncation.recordDroppedEvent();
            return 0L;
        }
        lastTRelMs = event.tRelMs();
        currentFileKey = file.toString();
        truncation.recordEventWritten(line.length);
        return line.length;
    }

    public List<CaptureEvent> readAll(String playerKey) {
        Path dir = eventsRoot().resolve(playerKey);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().forEach(files::add);
        } catch (IOException ex) {
            return List.of();
        }
        List<CaptureEvent> out = new ArrayList<>();
        for (Path f : files) {
            out.addAll(readFile(f));
        }
        return List.copyOf(out);
    }

    public List<CaptureEvent> readFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<CaptureEvent> out = new ArrayList<>();
        long badLines = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Optional<CaptureEvent> parsed = parseLine(line);
                if (parsed.isPresent()) {
                    out.add(parsed.get());
                } else {
                    badLines++;
                }
            }
        } catch (IOException ex) {
            truncation.recordTruncatedStream();
            return List.copyOf(out);
        }
        if (badLines > 0) {
            truncation.recordTruncatedStream();
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    public Optional<CaptureEvent> parseLine(String line) {
        Map<String, Object> m;
        try {
            m = Json.decodeObject(line);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        try {
            String typeId = String.valueOf(m.get("type"));
            Optional<CaptureEventType> type = CaptureEventType.byEventId(typeId);
            if (type.isEmpty()) {
                truncation.recordUnknownType();
                return Optional.empty();
            }
            String catId = String.valueOf(m.get("cat"));
            Optional<EventCategory> category = EventCategory.byCode(catId);
            if (category.isEmpty()) {
                truncation.recordTruncatedStream();
                return Optional.empty();
            }
            String srcId = String.valueOf(m.get("src"));
            Optional<EventSource> source = EventSource.byCode(srcId);
            if (source.isEmpty()) {
                truncation.recordTruncatedStream();
                return Optional.empty();
            }
            Object payload = m.get("payload");
            if (!(payload instanceof Map)) {
                truncation.recordTruncatedStream();
                return Optional.empty();
            }
            Object dur = m.get("durMs");
            Long durMs = dur instanceof Number n ? n.longValue() : null;
            return Optional.of(new CaptureEvent(
                    String.valueOf(m.get("schemaVersion")),
                    String.valueOf(m.get("eventId")),
                    String.valueOf(m.get("sessionId")),
                    String.valueOf(m.get("playerKey")),
                    type.get(),
                    asLong(m.get("tRelMs")),
                    asLong(m.get("tTick")),
                    category.get(),
                    source.get(),
                    Boolean.TRUE.equals(m.get("confirmed")),
                    durMs,
                    (Map<String, Object>) payload));
        } catch (RuntimeException ex) {
            truncation.recordTruncatedStream();
            return Optional.empty();
        }
    }

    public long recover() {
        long total = 0;
        long events = 0;
        if (Files.isDirectory(eventsRoot())) {
            try (var walk = Files.walk(eventsRoot())) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    total += Files.size(p);
                    events += countLines(p);
                }
            } catch (IOException ex) {
                truncation.recordTruncatedStream();
            }
        }
        truncation.setUsedBytes(total);
        return events;
    }

    public void saveTruncationLedger() throws IOException {
        ConsentStore.writeAtomically(truncationFile(), truncation.toJson());
    }

    private static long countLines(Path p) {
        try (var lines = Files.lines(p, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).count();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static void requireToken(String value, String what) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")
                || value.contains("..")) {
            throw new ContractException(what + " 含非法路径成分（拒绝路径穿越）：" + value);
        }
    }

    public String currentFileKey() {
        return currentFileKey;
    }

    static Map<String, Object> emptyOrdered() {
        return new LinkedHashMap<>();
    }

    static UncheckedIOException unchecked(IOException ex) {
        return new UncheckedIOException(ex);
    }
}
