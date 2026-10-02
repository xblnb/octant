package com.octant.common.privacy.adapter;

import com.octant.common.model.Json;
import com.octant.common.privacy.OctantPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ConsentLedger {

    public static final String FILE_NAME = "consent-ledger.jsonl";

    public static final String CONFIG_DIR = "config";

    private final Path ledgerFile;

    public ConsentLedger(Path gameDir) {
        this.ledgerFile = consentDirOf(gameDir).resolve(FILE_NAME);
    }

    public static Path consentDirOf(Path gameDir) {
        return OctantPaths.dataDir(gameDir.resolve(CONFIG_DIR));
    }

    public Path ledgerFile() {
        return ledgerFile;
    }

    public int lineCount() {
        List<ConsentRecord> all = read();
        return all.size();
    }

    public void append(ConsentRecord record) throws IOException {
        Path dir = ledgerFile.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        String line = record.toJson() + "\n";
        try (var channel = java.nio.channels.FileChannel.open(ledgerFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        }
    }

    public record ConsentRecord(Op op,
                                String privacyInstanceId,
                                String consentVersion,
                                String atDate,
                                long atEpochMs,
                                List<String> categories,
                                Map<String, Map<String, Object>> changes,
                                String revokedAtDate,
                                Map<String, Integer> removedCounts) {

        public ConsentRecord {
            List<String> normalized = new ArrayList<>(
                    categories == null ? List.of() : categories);
            java.util.Collections.sort(normalized);
            categories = List.copyOf(normalized);
            changes = changes == null ? Map.of() : Map.copyOf(changes);
            removedCounts = removedCounts == null ? null : Map.copyOf(removedCounts);
            if (op == null) {
                throw new IllegalArgumentException("账本记录必须有 op");
            }
        }

        public static ConsentRecord grant(String instanceId, String consentVersion,
                                         String atDate, long atEpochMs, Set<String> categories) {
            return new ConsentRecord(Op.GRANT, instanceId, consentVersion, atDate, atEpochMs,
                    categories == null ? List.of() : new ArrayList<>(categories),
                    null, null, null);
        }

        public static ConsentRecord update(String instanceId, String consentVersion,
                                          String atDate, long atEpochMs, Set<String> categories,
                                          Map<String, Map<String, Object>> changes) {
            return new ConsentRecord(Op.UPDATE, instanceId, consentVersion, atDate, atEpochMs,
                    categories == null ? List.of() : new ArrayList<>(categories),
                    changes, null, null);
        }

        public static ConsentRecord revoke(String instanceId, String consentVersion,
                                          String atDate, long atEpochMs, String revokedAtDate) {
            return new ConsentRecord(Op.REVOKE, instanceId, consentVersion, atDate, atEpochMs,
                    List.of(), null, revokedAtDate, null);
        }

        public static ConsentRecord delete(String instanceId, String consentVersion,
                                          String atDate, long atEpochMs,
                                          Map<String, Integer> removedCounts) {
            return new ConsentRecord(Op.DELETE, instanceId, consentVersion, atDate, atEpochMs,
                    List.of(), null, null, removedCounts);
        }

        public String toJson() {
            Map<String, Object> m = new TreeMap<>();
            m.put("op", op.wire());
            if (atDate != null && !atDate.isBlank()) {
                m.put("atDate", atDate);
            }
            if (atEpochMs > 0L) {
                m.put("atEpochMs", atEpochMs);
            }
            if (privacyInstanceId != null && !privacyInstanceId.isBlank()) {
                m.put("privacyInstanceId", privacyInstanceId);
            }
            if (consentVersion != null && !consentVersion.isBlank()) {
                m.put("consentVersion", consentVersion);
            }
            if (!categories.isEmpty()) {
                m.put("categories", List.copyOf(categories));
            }
            if (changes != null && !changes.isEmpty()) {
                m.put("changes", changes);
            }
            if (revokedAtDate != null && !revokedAtDate.isBlank()) {
                m.put("revokedAtDate", revokedAtDate);
            }
            if (removedCounts != null) {
                m.put("removedCounts", new TreeMap<>(removedCounts));
            }
            return Json.encode(m);
        }
    }

    public enum Op {
        GRANT("grant"),
        UPDATE("update"),
        REVOKE("revoke"),
        DELETE("delete");

        private final String wire;

        Op(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public List<ConsentRecord> read() {
        List<ConsentRecord> out = new ArrayList<>();
        if (!Files.isRegularFile(ledgerFile)) {
            return out;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(ledgerFile, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return out;
        }
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            try {
                var obj = Json.decodeObject(line);
                out.add(fromJson(obj, lineNo));
            } catch (RuntimeException ex) {
                throw new IllegalStateException("账本第 " + lineNo + " 行非法：" + ex.getMessage(), ex);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static ConsentRecord fromJson(Map<String, Object> m, int lineNo) {
        Object opRaw = m.get("op");
        Op op = null;
        for (Op candidate : Op.values()) {
            if (candidate.wire().equals(opRaw)) {
                op = candidate;
                break;
            }
        }
        if (op == null) {
            throw new IllegalArgumentException("未知 op=" + opRaw);
        }
        List<String> cats = new ArrayList<>();
        if (m.get("categories") instanceof List<?> list) {
            for (Object o : list) {
                cats.add(String.valueOf(o));
            }
        }
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        if (m.get("changes") instanceof Map<?, ?> ch) {
            for (Map.Entry<?, ?> e : ch.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> v) {
                    Map<String, Object> pair = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> p : v.entrySet()) {
                        pair.put(String.valueOf(p.getKey()), p.getValue());
                    }
                    changes.put(String.valueOf(e.getKey()), pair);
                }
            }
        }
        Map<String, Integer> removed = null;
        if (m.get("removedCounts") instanceof Map<?, ?> rc) {
            removed = new TreeMap<>();
            for (Map.Entry<?, ?> e : rc.entrySet()) {
                Object v = e.getValue();
                removed.put(String.valueOf(e.getKey()),
                        v instanceof Number n ? n.intValue() : 0);
            }
        }
        return new ConsentRecord(op,
                str(m.get("privacyInstanceId")),
                str(m.get("consentVersion")),
                str(m.get("atDate")),
                m.get("atEpochMs") instanceof Number n ? n.longValue() : 0L,
                cats, changes, str(m.get("revokedAtDate")), removed);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
