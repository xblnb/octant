package com.octant.common.session;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.RawEventSchema;
import com.octant.common.model.TruncationLedger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class EvictionPolicy {

    public record Result(long removedEvents,
                         long removedSessions,
                         long freedBytes,
                         String reason,
                         boolean capExhausted,
                         boolean reachedTarget) {

        public static Result none() {
            return new Result(0L, 0L, 0L, "", false, true);
        }
    }

    private record SessionFile(Path path, String sessionId, long earliestTRelMs, long bytes) {
    }

    private final RawEventStore store;
    private final TruncationLedger ledger;
    private final long nowEpochMillis;
    private final long sessionAnchorEpochMillis;

    public EvictionPolicy(RawEventStore store, long nowEpochMillis, long sessionAnchorEpochMillis) {
        this.store = store;
        this.ledger = store.truncation();
        this.nowEpochMillis = nowEpochMillis;
        this.sessionAnchorEpochMillis = sessionAnchorEpochMillis;
    }

    public boolean targetReached() {
        long used = recomputeUsedBytes(null);
        ledger.setUsedBytes(used);
        return used <= (long) (ledger.capBytes() * RawEventSchema.STORAGE_SOFT_RATIO);
    }

    public Result evictIfNeeded(String playerKey) {
        Result last = Result.none();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Result r = evictOnce(playerKey);
            if (r.removedEvents() == 0 && !r.capExhausted()) {
                return round == 0 ? r : last;
            }
            last = r;
            if (r.capExhausted() || r.reachedTarget() || r.removedEvents() == 0) {
                return r;
            }
        }
        return last;
    }

    private static final int MAX_ROUNDS = 4;

    private Result evictOnce(String playerKey) {
        if (!ledger.aboveSoftThreshold() || ledger.capExhausted()) {
            return Result.none();
        }
        long totalEvents = countAllEvents(playerKey);
        long maxRemovable = (long) (totalEvents * RawEventSchema.EVICTION_MAX_FRACTION);
        if (maxRemovable <= 0) {
            return Result.none();
        }

        List<SessionFile> sessions = listSessions(playerKey);
        long removedEvents = 0;
        long removedSessions = 0;
        long freed = 0;

        String r1Reason = TruncationLedger.REASON_RETENTION_AGE;
        for (SessionFile s : sessions) {
            if (targetReached() || removedEvents >= maxRemovable) {
                break;
            }
            if (ageDays(s.earliestTRelMs) > RawEventSchema.RETENTION_MAX_AGE_DAYS) {
                long events = countEvents(s.path());
                if (deleteFile(s.path())) {
                    removedEvents += events;
                    removedSessions++;
                    freed += s.bytes();
                }
            }
        }
        if (removedEvents > 0 && targetReached()) {
            return finish(removedEvents, removedSessions, freed, r1Reason, false);
        }

        List<SessionFile> remaining = listSessions(playerKey);
        for (SessionFile s : remaining) {
            if (targetReached() || removedEvents >= maxRemovable) {
                break;
            }
            long events = countEvents(s.path());
            if (deleteFile(s.path())) {
                removedEvents += events;
                removedSessions++;
                freed += s.bytes();
            }
        }
        if (removedEvents > 0 && targetReached()) {
            return finish(removedEvents, removedSessions, freed, TruncationLedger.REASON_SESSION_EVICT, false);
        }

        List<SessionFile> left = listSessions(playerKey);
        long r3Removed = 0;
        for (SessionFile s : left) {
            if (targetReached() || removedEvents >= maxRemovable) {
                break;
            }
            long n = truncatePrefix(s.path());
            r3Removed += n;
            removedEvents += n;
        }
        if (r3Removed > 0) {
            long delta = recomputeUsedBytes(playerKey) - ledger.usedBytes();
            freed += Math.max(0L, delta);
            ledger.setUsedBytes(ledger.usedBytes() + Math.max(0L, delta));
            if (targetReached()) {
                return finish(removedEvents, removedSessions, freed,
                        TruncationLedger.REASON_PREFIX_TRUNCATE, false);
            }
        }

        ledger.markCapExhausted(0L);
        return new Result(removedEvents, removedSessions, freed,
                TruncationLedger.REASON_CAP_EXHAUSTED, true, false);
    }

    private Result finish(long removedEvents, long removedSessions, long freed, String reason,
                          boolean exhausted) {
        ledger.setUsedBytes(recomputeUsedBytes(null));
        ledger.recordEviction(0L, reason, removedEvents, removedSessions);
        return new Result(removedEvents, removedSessions, freed, reason, exhausted, targetReached());
    }

    public long truncatePrefix(Path sessionFile) {
        if (!Files.isRegularFile(sessionFile)) {
            return 0L;
        }
        List<String> lines;
        try {
            lines = new ArrayList<>(Files.readAllLines(sessionFile, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return 0L;
        }
        boolean hasStart = lines.stream().anyMatch(l -> l.contains("\"session_start\""));
        boolean hasEnd = lines.stream().anyMatch(l -> l.contains("\"session_end\""));
        if (hasStart || hasEnd) {
            return 0L;
        }
        long lastTRel = lastTRelMs(lines);
        long protectFrom = lastTRel - 3_600_000L;
        List<String> kept = new ArrayList<>();
        long removed = 0;
        for (String line : lines) {
            long t = tRelOf(line);
            if (t >= 0 && t < protectFrom) {
                removed++;
            } else {
                kept.add(line);
            }
        }
        if (removed == 0) {
            return 0L;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (String k : kept) {
                sb.append(k).append('\n');
            }
            Files.write(sessionFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return 0L;
        }
        return removed;
    }

    private static long tRelOf(String line) {
        int i = line.indexOf("\"tRelMs\":");
        if (i < 0) {
            return -1L;
        }
        int j = i + 9;
        int end = j;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
            end++;
        }
        try {
            return Long.parseLong(line.substring(j, end));
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static long lastTRelMs(List<String> lines) {
        long max = 0L;
        for (String l : lines) {
            max = Math.max(max, tRelOf(l));
        }
        return max;
    }

    private long ageDays(long tRelMs) {
        return ageDaysOf(tRelMs);
    }

    public long ageDaysOf(long tRelMs) {
        long eventEpoch = sessionAnchorEpochMillis + Math.max(0L, tRelMs);
        LocalDate eventDay = Instant.ofEpochMilli(eventEpoch).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate nowDay = Instant.ofEpochMilli(nowEpochMillis).atZone(ZoneOffset.UTC).toLocalDate();
        return nowDay.toEpochDay() - eventDay.toEpochDay();
    }

    private List<SessionFile> listSessions(String playerKey) {
        Path root = store.eventsRoot();
        List<SessionFile> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .filter(p -> playerKey == null || p.getParent().getFileName().toString().equals(playerKey))
                    .forEach(p -> {
                        try {
                            out.add(new SessionFile(p, sessionIdOf(p), earliestTRel(p), Files.size(p)));
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
            return List.of();
        }
        out.sort(Comparator.comparingLong(SessionFile::earliestTRelMs));
        return out;
    }

    private static String sessionIdOf(Path p) {
        String n = p.getFileName().toString();
        int dash = n.indexOf('-');
        return dash > 0 ? n.substring(0, dash) : n.replace(".jsonl", "");
    }

    private long earliestTRel(Path p) {
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                long t = tRelOf(line);
                if (t >= 0) {
                    return t;
                }
            }
        } catch (IOException ignored) {
        }
        return 0L;
    }

    private long countEvents(Path p) {
        try (Stream<String> lines = Files.lines(p, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).count();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private long countAllEvents(String playerKey) {
        long total = 0;
        for (SessionFile s : listSessions(playerKey)) {
            total += countEvents(s.path());
        }
        return total;
    }

    private long recomputeUsedBytes(String playerKey) {
        Path root = store.eventsRoot();
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        long total = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                total += Files.size(p);
            }
        } catch (IOException ignored) {
            return ledger.usedBytes();
        }
        return total;
    }

    private static boolean deleteFile(Path p) {
        try {
            Files.delete(p);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    public static void appendRawLine(Path file, CaptureEvent event) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, (com.octant.common.model.Json.encode(event.toOrderedMap()) + "\n")
                        .getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    public boolean stopped() {
        return ledger.capExhausted();
    }

    public Map<String, Object> dataQuality() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.putAll(ledger.dataQuality());
        Optional<TruncationLedger.LastEviction> le = Optional.ofNullable(ledger.lastEviction());
        m.put("lastEvictionReason", le.map(TruncationLedger.LastEviction::reason).orElse(""));
        return m;
    }
}
