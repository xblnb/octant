package com.octant.pipeline.kite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KiteLabelRegistry {

    public record Label(String family, String id, boolean judgmentFormulaEstablished, String note) {
    }

    public enum Mirror {
        ARGUMENT,
        IN_REPO_READER,
        IN_REPO_READER_OUT,
        NO_SOURCE
    }

    public static final String IN_REPO_READER = "docs/design/tools/read_label_observation_map.py";

    public static final String IN_REPO_READER_OUT = "docs/design/tools/read_label_observation_map.out.txt";

    public static final List<String> SPEC_BEHAVIOR_LABELS = List.of(
            "探索", "战斗", "建造与自动化", "采集与耕作", "推进冲刺", "停顿");

    public static final List<String> SPEC_HEALTH_STATE_LABELS = List.of(
            "spiral", "oscillation", "stall");

    private static final Pattern FAMILY = Pattern.compile(
            "^\\s*family\\s+([A-Za-z0-9_-]+)\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern LABEL_JSON = Pattern.compile(
            "\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"fiveElementsComplete\"\\s*:\\s*(true|false)");

    private final Map<String, Label> labels;
    private final Map<String, int[]> familyCounts;
    private final Mirror mirror;
    private final String mirrorNote;

    private KiteLabelRegistry(Map<String, Label> labels, Map<String, int[]> familyCounts, Mirror mirror,
                              String mirrorNote) {
        this.labels = Collections.unmodifiableMap(labels);
        this.familyCounts = Collections.unmodifiableMap(familyCounts);
        this.mirror = mirror;
        this.mirrorNote = mirrorNote;
    }

    public static KiteLabelRegistry load(Path argumentPath, Path workingDir) {
        if (argumentPath != null) {
            String text = readIfExists(argumentPath);
            if (text != null) {
                return new KiteLabelRegistry(parse(text), parseFamilies(text), Mirror.ARGUMENT,
                        argumentPath.toAbsolutePath().toString());
            }
        }
        Path out = workingDir == null ? Path.of(IN_REPO_READER_OUT) : workingDir.resolve(IN_REPO_READER_OUT);
        String text = readIfExists(out);
        if (text != null) {
            return new KiteLabelRegistry(parse(text), parseFamilies(text), Mirror.IN_REPO_READER_OUT,
                    out.toAbsolutePath().toString());
        }
        String executed = runReader(workingDir);
        if (executed != null && !executed.isEmpty()) {
            return new KiteLabelRegistry(parse(executed), parseFamilies(executed), Mirror.IN_REPO_READER,
                    "运行 " + IN_REPO_READER + " 的输出（exit 0）");
        }
        return new KiteLabelRegistry(Map.of(), Map.of(), Mirror.NO_SOURCE,
                "无权威输出可取：--label-map 未给/不可读、找不到 " + out + "、且无法运行 " + IN_REPO_READER
                        + "（**本实现不提供冻结兜底**，见类注释）");
    }

    static String runReader(Path workingDir) {
        try {
            Path script = workingDir == null ? Path.of(IN_REPO_READER) : workingDir.resolve(IN_REPO_READER);
            if (!Files.isRegularFile(script)) {
                return null;
            }
            ProcessBuilder pb = new ProcessBuilder("python", IN_REPO_READER)
                    .directory(workingDir == null ? Path.of(".").toAbsolutePath().toFile() : workingDir.toFile())
                    .redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUTF8", "1");
            Process p = pb.start();
            byte[] raw = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            String utf8 = new String(raw, StandardCharsets.UTF_8);
            String out;
            if (utf8.indexOf('\uFFFD') >= 0) {
                String gbk = new String(raw, java.nio.charset.Charset.forName("GBK"));
                out = gbk.indexOf('\uFFFD') >= 0 ? utf8 : gbk;
                lastReaderRaw = "（UTF-8 解出替换字符 ⇒ 回退 GBK 解码）";
            } else {
                out = utf8;
            }
            lastReaderRaw = "exit=" + code + " bytes=" + raw.length + " chars=" + out.length() + " :: "
                    + out.replace("\n", " ⏎ ").substring(0, Math.min(500, out.length()));
            return code == 0 && !out.isBlank() ? out : null;
        } catch (IOException | InterruptedException e) {
            lastReaderRaw = "<" + e.getClass().getSimpleName() + "> " + e.getMessage();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static String lastReaderRaw = "";

    public static String lastReaderRaw() {
        return lastReaderRaw;
    }

    public static KiteLabelRegistry ofText(String text, Mirror mirror, String note) {
        return new KiteLabelRegistry(parse(text), parseFamilies(text), mirror, note);
    }

    public static KiteLabelRegistry noSource(String note) {
        return new KiteLabelRegistry(Map.of(), Map.of(), Mirror.NO_SOURCE, note);
    }

    public Mirror mirror() {
        return mirror;
    }

    public String mirrorNote() {
        return mirrorNote;
    }

    public Map<String, Label> labels() {
        return labels;
    }

    public boolean hasSource() {
        return mirror != Mirror.NO_SOURCE;
    }

    public int[] familyCount(String family) {
        int[] c = familyCounts.get(family);
        return c == null ? new int[] {0, 0} : c.clone();
    }

    public boolean judgmentEstablished(String reference) {
        Label l = labels.get(reference);
        return l != null && l.judgmentFormulaEstablished();
    }

    public boolean registered(String reference) {
        return labels.containsKey(reference);
    }

    public void requireForAggregation(String reference) {
        if (!hasSource()) {
            throw new IllegalStateException("LV-10 fail-closed：无权威标签登记输出（" + mirrorNote
                    + "）⇒ 任何标签都不得当作已可判定使用（本实现不提供冻结兜底）");
        }
        Label l = labels.get(reference);
        if (l == null) {
            throw new IllegalStateException("LV-10 fail-closed：标签未在唯一来源登记（" + reference
                    + "）⇒ 不得当作已可判定使用");
        }
        if (!l.judgmentFormulaEstablished()) {
            throw new IllegalStateException("LV-10 fail-closed：标签 " + reference
                    + " 的 ②③④⑤ 在唯一来源内**未建立** ⇒ 不得计入 X̄（补齐顺序：§20 建判定式 → dc 侧登记 → 实现冻结）");
        }
    }

    public Set<String> aggregatableRefs() {
        Set<String> out = new LinkedHashSet<>();
        for (Label l : labels.values()) {
            if (l.judgmentFormulaEstablished()) {
                out.add(l.family() + ":" + l.id());
            }
        }
        return out;
    }

    public int behaviorEstablishedCount() {
        int n = 0;
        for (String id : SPEC_BEHAVIOR_LABELS) {
            if (judgmentEstablished("behavior:" + id)) {
                n++;
            }
        }
        return n;
    }

    public int healthStateEstablishedCount() {
        int n = 0;
        for (String id : SPEC_HEALTH_STATE_LABELS) {
            if (judgmentEstablished("health-state:" + id)) {
                n++;
            }
        }
        return n;
    }

    public List<String> dump() {
        List<String> out = new ArrayList<>();
        out.add("镜像来源 = " + mirror + "（" + mirrorNote + "）");
        if (!hasSource()) {
            out.add("（无权威输出 ⇒ 镜像为空；一切「可判定」结论不得成立）");
            return out;
        }
        List<Label> sorted = new ArrayList<>(labels.values());
        sorted.sort((a, b) -> (a.family() + ":" + a.id()).compareTo(b.family() + ":" + b.id()));
        for (Label l : sorted) {
            out.add((l.family() + ":" + l.id()) + " 判定式" + (l.judgmentFormulaEstablished() ? "已建立" : "未建立"));
        }
        out.add("behavior 判定式齐备 = " + behaviorEstablishedCount() + "/" + SPEC_BEHAVIOR_LABELS.size()
                + "；health-state = " + healthStateEstablishedCount() + "/" + SPEC_HEALTH_STATE_LABELS.size()
                + "；登记标签总数 = " + labels.size());
        return out;
    }

    static Map<String, Label> parse(String text) {
        Map<String, Label> out = new LinkedHashMap<>();
        String family = "";
        for (String raw : text.split("\r?\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher fam = FAMILY.matcher(line);
            if (fam.find()) {
                family = fam.group(1);
                continue;
            }
            Matcher json = LABEL_JSON.matcher(line);
            if (json.find()) {
                String id = json.group(1);
                String fam2 = family.isEmpty() ? guessFamily(id) : family;
                out.put(fam2 + ":" + id, new Label(fam2, id, "true".equals(json.group(2)), ""));
                continue;
            }
            if (!line.startsWith("-")) {
                continue;
            }
            String body = line.substring(1).strip();
            int cut = indexOfWhitespaceRun(body);
            if (cut < 0) {
                continue;
            }
            String id = body.substring(0, cut);
            String tail = body.substring(cut).strip();
            String fam2 = family.isEmpty() ? guessFamily(id) : family;
            if (tail.startsWith("待补")) {
                String note = tail.length() > 2 ? tail.substring(2).replaceFirst("^[:：]\\s*", "") : "";
                out.put(fam2 + ":" + id, new Label(fam2, id, false, note));
            } else if (tail.startsWith("五要素完整") || tail.startsWith("完")) {
                out.put(fam2 + ":" + id, new Label(fam2, id, true, ""));
            }
        }
        return out;
    }

    private static int indexOfWhitespaceRun(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                return i;
            }
        }
        return -1;
    }

    static Map<String, int[]> parseFamilies(String text) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (String raw : text.split("\r?\n", -1)) {
            Matcher m = FAMILY.matcher(raw.strip());
            if (m.find()) {
                out.put(m.group(1), new int[] {Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))});
            }
        }
        return out;
    }

    private static String guessFamily(String id) {
        if (SPEC_HEALTH_STATE_LABELS.contains(id)) {
            return "health-state";
        }
        if (SPEC_BEHAVIOR_LABELS.contains(id)) {
            return "behavior";
        }
        if ("smooth".equals(id) || "urgent".equals(id)) {
            return "kinematic-state";
        }
        return "pref";
    }

    private static String readIfExists(Path p) {
        try {
            if (p == null || !Files.isRegularFile(p)) {
                return null;
            }
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
