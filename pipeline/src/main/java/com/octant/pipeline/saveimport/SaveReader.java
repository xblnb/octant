package com.octant.pipeline.saveimport;

import com.octant.common.model.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class SaveReader {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private SaveReader() {
    }

    public static SaveSnapshot read(Path saveDir) throws IOException {
        if (!Files.isDirectory(saveDir)) {
            throw new IOException("存档目录不存在：" + saveDir);
        }
        AdvancementData adv = readAdvancements(saveDir.resolve("advancements"));
        StatsData stats = readStats(saveDir.resolve("stats"));
        LevelData level = readLevelDat(saveDir.resolve("level.dat"));

        return new SaveSnapshot(adv.records, adv.done, adv.undone, adv.malformed,
                adv.fileLevelMetadata,
                adv.doneWithoutTimestamp, adv.earliest, adv.latest, adv.timestamps,
                stats.playTimeTicks, stats.totalWorldTimeTicks,
                stats.custom, stats.killed, stats.mined, stats.crafted,
                stats.used, stats.pickedUp, stats.dropped,
                stats.dataVersion, level.dataVersion, level.levelNamePresent);
    }

    private static AdvancementData readAdvancements(Path dir) throws IOException {
        AdvancementData out = new AdvancementData();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        for (Path p : listJson(dir)) {
            Map<String, Object> doc = decode(readString(p));
            for (Map.Entry<String, Object> e : doc.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> body)) {
                    if ("DataVersion".equals(e.getKey()) && e.getValue() instanceof Number) {
                        out.fileLevelMetadata++;
                    } else {
                        out.malformed++;
                    }
                    continue;
                }
                out.records++;
                if (!Boolean.TRUE.equals(body.get("done"))) {
                    out.undone++;
                    continue;
                }
                out.done++;
                String iso = earliestCriteriaTime(body);
                if (iso == null) {
                    out.doneWithoutTimestamp++;
                } else {
                    out.timestamps.put(e.getKey(), iso);
                }
            }
        }
        for (String iso : out.timestamps.values()) {
            if (iso == null) {
                continue;
            }
            if (out.earliest == null || iso.compareTo(out.earliest) < 0) {
                out.earliest = iso;
            }
            if (out.latest == null || iso.compareTo(out.latest) > 0) {
                out.latest = iso;
            }
        }
        return out;
    }

    private static String earliestCriteriaTime(Map<?, ?> body) {
        Object crit = body.get("criteria");
        if (!(crit instanceof Map<?, ?> criteria)) {
            return null;
        }
        String best = null;
        for (Object v : criteria.values()) {
            if (!(v instanceof String raw) || raw.isBlank()) {
                continue;
            }
            String iso = toIsoUtc(raw);
            if (iso != null && (best == null || iso.compareTo(best) < 0)) {
                best = iso;
            }
        }
        return best;
    }

    static String toIsoUtc(String raw) {
        String s = raw.trim();
        try {
            String core;
            ZoneOffset off = ZoneOffset.UTC;
            int sp = s.lastIndexOf(' ');
            String tail = sp > 0 ? s.substring(sp + 1) : "";
            if (tail.matches("[+-]\\d{4}")) {
                off = ZoneOffset.of(tail.substring(0, 3) + ":" + tail.substring(3));
                core = s.substring(0, sp);
            } else {
                core = s;
            }
            core = core.replace(' ', 'T');
            return LocalDateTime.parse(core).atOffset(off)
                    .withOffsetSameInstant(ZoneOffset.UTC).format(ISO);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static StatsData readStats(Path dir) throws IOException {
        StatsData out = new StatsData();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        for (Path p : listJson(dir)) {
            Map<String, Object> doc = decode(readString(p));
            if (doc.get("DataVersion") instanceof Number n) {
                out.dataVersion = n.intValue();
            }
            if (!(doc.get("stats") instanceof Map<?, ?> stats)) {
                continue;
            }
            out.custom = counters(stats.get("minecraft:custom"));
            out.killed = counters(stats.get("minecraft:killed"));
            out.mined = counters(stats.get("minecraft:mined"));
            out.crafted = counters(stats.get("minecraft:crafted"));
            out.used = counters(stats.get("minecraft:used"));
            out.pickedUp = counters(stats.get("minecraft:picked_up"));
            out.dropped = counters(stats.get("minecraft:dropped"));
            out.playTimeTicks = numberOf(out.custom, "minecraft:play_time");
            out.totalWorldTimeTicks = numberOf(out.custom, "minecraft:total_world_time");
        }
        return out;
    }

    private static Map<String, Long> counters(Object raw) {
        Map<String, Long> m = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> mm) {
            for (Map.Entry<?, ?> e : mm.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    m.put(String.valueOf(e.getKey()), n.longValue());
                }
            }
        }
        return m;
    }

    private static long numberOf(Map<String, Long> m, String key) {
        Long v = m.get(key);
        return v == null ? -1L : v;
    }

    private static LevelData readLevelDat(Path file) {
        LevelData out = new LevelData();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            Nbt nbt = new Nbt(in.readAllBytes());
            nbt.startRoot();
            while (nbt.more()) {
                Nbt.Tag t = nbt.nextTag();
                if (t.type() == Nbt.TAG_COMPOUND && "Data".equals(t.name())) {
                    nbt.scanCompoundForLevelFacts(out);
                } else {
                    nbt.skipBody(t.type());
                }
            }
        } catch (IOException | RuntimeException ex) {
            out.unreadable = true;
        }
        return out;
    }

    private static List<Path> listJson(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json")) {
                    out.add(p);
                }
            }
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static String readString(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(String text) {
        return Json.decodeObject(text);
    }

    public record SaveSnapshot(
            int advancementRecords, int done, int undone, int malformed,
            int fileLevelMetadata,
            int doneWithoutTimestamp,
            String earliestDoneIso, String latestDoneIso,
            Map<String, String> doneTimestampsIso,
            long playTimeTicks, long totalWorldTimeTicks,
            Map<String, Long> custom, Map<String, Long> killed, Map<String, Long> mined,
            Map<String, Long> crafted, Map<String, Long> used, Map<String, Long> pickedUp,
            Map<String, Long> dropped,
            int statsDataVersion, int levelDataVersion, boolean levelNamePresent) {

        public SaveSnapshot {
            doneTimestampsIso = Map.copyOf(doneTimestampsIso);
            custom = Map.copyOf(custom);
            killed = Map.copyOf(killed);
            mined = Map.copyOf(mined);
            crafted = Map.copyOf(crafted);
            used = Map.copyOf(used);
            pickedUp = Map.copyOf(pickedUp);
            dropped = Map.copyOf(dropped);
        }

        public double playHours() {
            return playTimeTicks < 0 ? -1.0 : playTimeTicks / 20.0 / 3600.0;
        }

        public double spanDays() {
            if (earliestDoneIso == null || latestDoneIso == null) {
                return -1.0;
            }
            return (Instant.parse(latestDoneIso).toEpochMilli()
                    - Instant.parse(earliestDoneIso).toEpochMilli()) / 86400_000.0;
        }
    }

    private static final class AdvancementData {
        int records;
        int done;
        int undone;
        int malformed;
        int fileLevelMetadata;
        int doneWithoutTimestamp;
        String earliest;
        String latest;
        final Map<String, String> timestamps = new LinkedHashMap<>();
    }

    private static final class StatsData {
        long playTimeTicks = -1L;
        long totalWorldTimeTicks = -1L;
        int dataVersion = -1;
        Map<String, Long> custom = Map.of();
        Map<String, Long> killed = Map.of();
        Map<String, Long> mined = Map.of();
        Map<String, Long> crafted = Map.of();
        Map<String, Long> used = Map.of();
        Map<String, Long> pickedUp = Map.of();
        Map<String, Long> dropped = Map.of();
    }

    private static final class LevelData {
        int dataVersion = -1;
        boolean levelNamePresent;
        boolean unreadable;
    }

    static final class Nbt {
        static final byte TAG_END = 0;
        static final byte TAG_BYTE = 1;
        static final byte TAG_SHORT = 2;
        static final byte TAG_INT = 3;
        static final byte TAG_LONG = 4;
        static final byte TAG_FLOAT = 5;
        static final byte TAG_DOUBLE = 6;
        static final byte TAG_BYTE_ARRAY = 7;
        static final byte TAG_STRING = 8;
        static final byte TAG_LIST = 9;
        static final byte TAG_COMPOUND = 10;
        static final byte TAG_INT_ARRAY = 11;
        static final byte TAG_LONG_ARRAY = 12;

        record Tag(byte type, String name) {
        }

        private final byte[] d;
        private int i;
        private boolean stopped;

        Nbt(byte[] data) {
            this.d = data;
        }

        void startRoot() {
            if (d.length > 0 && d[0] == TAG_COMPOUND) {
                i = 1;
                int n = u16(i);
                i += 2 + Math.max(0, n);
            }
        }

        boolean more() {
            return !stopped && i < d.length && d[i] != TAG_END;
        }

        Tag nextTag() {
            byte t = d[i++];
            int n = u16(i);
            i += 2;
            String name = new String(d, i, n, StandardCharsets.UTF_8);
            i += Math.max(0, n);
            return new Tag(t, name);
        }

        void scanCompoundForLevelFacts(LevelData out) {
            while (more()) {
                Tag t = nextTag();
                if (t.type() == TAG_INT && "DataVersion".equals(t.name())) {
                    out.dataVersion = readInt();
                } else if (t.type() == TAG_STRING && "LevelName".equals(t.name())) {
                    int len = u16(i);
                    i += 2;
                    if (len > 0) {
                        out.levelNamePresent = true;
                    }
                    i += Math.max(0, len);
                } else {
                    skipBody(t.type());
                }
            }
            if (!stopped && i < d.length && d[i] == TAG_END) {
                i++;
            }
        }

        void skipBody(byte t) {
            if (stopped) {
                return;
            }
            try {
                doSkip(t);
            } catch (RuntimeException ex) {
                stopped = true;
            }
        }

        private void doSkip(byte t) {
            int tag = t;
            switch (tag) {
                case TAG_END -> {
                }
                case TAG_BYTE -> i += 1;
                case TAG_SHORT -> i += 2;
                case TAG_INT, TAG_FLOAT -> i += 4;
                case TAG_LONG, TAG_DOUBLE -> i += 8;
                case TAG_BYTE_ARRAY -> i += Math.max(0, readInt());
                case TAG_STRING -> {
                    int n = u16(i);
                    i += 2 + Math.max(0, n);
                }
                case TAG_LIST -> {
                    byte et = d[i++];
                    int n = readInt();
                    for (int k = 0; k < n; k++) {
                        if (et == TAG_END) {
                            break;
                        }
                        if (et == TAG_COMPOUND) {
                            while (i < d.length && d[i] != TAG_END) {
                                Tag mt = nextTag();
                                doSkip(mt.type());
                            }
                            if (i < d.length) {
                                i++;
                            }
                        } else {
                            doSkip(et);
                        }
                    }
                }
                case TAG_COMPOUND -> {
                    while (i < d.length && d[i] != TAG_END) {
                        Tag mt = nextTag();
                        doSkip(mt.type());
                    }
                    if (i < d.length) {
                        i++;
                    }
                }
                case TAG_INT_ARRAY -> i += 4 * Math.max(0, readInt());
                case TAG_LONG_ARRAY -> i += 8 * Math.max(0, readInt());
                default -> throw new IllegalArgumentException("未支持的 NBT 标签 " + tag);
            }
        }

        int position() {
            return i;
        }

        private int readInt() {
            if (i + 4 > d.length) {
                throw new IllegalArgumentException("NBT 越界");
            }
            int v = (d[i] & 0xff) << 24 | (d[i + 1] & 0xff) << 16
                    | (d[i + 2] & 0xff) << 8 | (d[i + 3] & 0xff);
            i += 4;
            return v;
        }

        private int u16(int at) {
            if (at + 2 > d.length) {
                throw new IllegalArgumentException("NBT 越界");
            }
            return ((d[at] & 0xff) << 8) | (d[at + 1] & 0xff);
        }
    }
}
