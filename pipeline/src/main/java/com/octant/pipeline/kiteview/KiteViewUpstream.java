package com.octant.pipeline.kiteview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.octant.pipeline.kiteview.KiteViewData.Sample;
import com.octant.pipeline.kiteview.KiteViewData.Series;

public final class KiteViewUpstream {

    private KiteViewUpstream() {
    }

    public static final String DEFAULT_ARTIFACT = "pipeline/build/kite/kite-selfcheck.json";

    public record Reading(String id, String outcome, String detail) {
    }

    public static final class Info {

        private final boolean available;
        private final String reason;
        private final String path;
        private final String sha16;
        private final long bytes;
        private final String schema;
        private final String instrument;
        private final String seriesKind;
        private final int pointCount;
        private final int usableCount;
        private final String firstSuppression;
        private final List<Reading> readings;
        private final List<String> axisMeta;
        private final double orthoMax;
        private final String orthVerdict;
        private final Series series;
        private final double deltaMaxSec;

        Info(boolean available, String reason, String path, String sha16, long bytes, String schema, String instrument,
             String seriesKind, int pointCount, int usableCount, String firstSuppression, List<Reading> readings,
             List<String> axisMeta, double orthoMax, String orthVerdict, Series series, double deltaMaxSec) {
            this.available = available;
            this.reason = reason;
            this.path = path;
            this.sha16 = sha16;
            this.bytes = bytes;
            this.schema = schema;
            this.instrument = instrument;
            this.seriesKind = seriesKind;
            this.pointCount = pointCount;
            this.usableCount = usableCount;
            this.firstSuppression = firstSuppression;
            this.readings = List.copyOf(readings);
            this.axisMeta = List.copyOf(axisMeta);
            this.orthoMax = orthoMax;
            this.orthVerdict = orthVerdict;
            this.series = series;
            this.deltaMaxSec = deltaMaxSec;
        }

        public static Info unavailable(String path, String reason) {
            return new Info(false, reason, path, "", 0L, "", "", "", 0, 0, "", new ArrayList<>(),
                    new ArrayList<>(), Double.NaN, "unavailable", null, 0.0d);
        }

        public double deltaMaxSec() {
            return deltaMaxSec;
        }

        public boolean available() {
            return available;
        }

        public String reason() {
            return reason;
        }

        public String path() {
            return path;
        }

        public String sha16() {
            return sha16;
        }

        public long bytes() {
            return bytes;
        }

        public String schema() {
            return schema;
        }

        public String instrument() {
            return instrument;
        }

        public String seriesKind() {
            return seriesKind;
        }

        public int pointCount() {
            return pointCount;
        }

        public int usableCount() {
            return usableCount;
        }

        public String firstSuppression() {
            return firstSuppression;
        }

        public List<Reading> readings() {
            return readings;
        }

        public List<String> axisMeta() {
            return axisMeta;
        }

        public double orthoMax() {
            return orthoMax;
        }

        public String orthVerdict() {
            return orthVerdict;
        }

        public Series series() {
            return series;
        }
    }

    public static Info load(Path artifact) {
        String pathText = artifact.toString().replace('\\', '/');
        if (!Files.isRegularFile(artifact)) {
            return Info.unavailable(pathText, "上游产物不存在：" + pathText + "（上游未产出 ⇒ 本页按 §1 显示 不适用）");
        }
        final byte[] bytes;
        final String text;
        try {
            bytes = Files.readAllBytes(artifact);
            text = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Info.unavailable(pathText, "上游产物不可读：" + e.getClass().getSimpleName());
        }
        String sha16 = sha16(bytes);
        String schema = firstGroup(text, "\"schema\"\\s*:\\s*\"([^\"]+)\"");
        String instrument = firstGroup(text, "\"instrument\"\\s*:\\s*\"([^\"]+)\"");
        double orthoMax = parseDouble(firstGroup(text, "\"name\"\\s*:\\s*\"KITE_ORTHO_MAX\"\\s*,\\s*\"value\"\\s*:\\s*([0-9.eE+-]+)"), Double.NaN);
        double dtMax = parseDouble(firstGroup(text, "\"name\"\\s*:\\s*\"KITE_DT_MAX_S\"\\s*,\\s*\"value\"\\s*:\\s*([0-9.eE+-]+)"), Double.NaN);

        String seriesSection = section(text, "\"series\"\\s*:\\s*\\{", "\"snapshotSeries\"");
        List<double[]> axesValues = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        Matcher line = Pattern.compile("\\{\"i\"\\s*:\\s*(\\d+).*?\"startSec\"\\s*:\\s*(\\d+).*?\"deltaSec\"\\s*:\\s*(\\d+)"
                + ".*?\"activeSec\"\\s*:\\s*(\\d+).*?\"density\"\\s*:\\s*([0-9.eE+-]+).*?\"axes\"\\s*:\\s*\\{(.*?)\\}\\s*\\}")
                .matcher(seriesSection);
        int i = 0;
        while (line.find()) {
            String axesText = line.group(6);
            double prog = axisValue(axesText, "PROG");
            double spon = axisValue(axesText, "SPON");
            double guid = axisValue(axesText, "GUID");
            axesValues.add(new double[] {prog, spon, guid});
            int lineNo = 1;
            int start = line.start();
            for (int k = 0; k < start && k < text.length(); k++) {
                if (text.charAt(k) == '\n') {
                    lineNo++;
                }
            }
            samples.add(new Sample(i, parseLong(line.group(2)), prog, spon, guid, parseDouble(line.group(5), Double.NaN),
                    pathText, lineNo));
            i++;
        }
        if (samples.isEmpty()) {
            return Info.unavailable(pathText, "上游产物里解析不到 series.points（schema 变更或产物为空）⇒ 不得用夹具冒充");
        }
        int usable = 0;
        for (double[] v : axesValues) {
            if (!Double.isNaN(v[0]) && !Double.isNaN(v[1]) && !Double.isNaN(v[2])) {
                usable++;
            }
        }
        String kind = firstGroup(seriesSection, "\"kind\"\\s*:\\s*\"([^\"]+)\"");
        String firstSupp = firstGroup(text, "\"firstSuppression\"\\s*:\\s*\"([^\"]*)\"");
        List<Reading> readings = new ArrayList<>();
        for (String id : List.of("OR-1", "OR-1r", "OR-CF2", "OR-PW", "AX-P", "T21-1", "SEG-1", "OR-6", "OR-7")) {
            String outcome = firstGroup(text, "\\{\"id\"\\s*:\\s*\"" + Pattern.quote(id)
                    + "\"\\s*,\\s*\"outcome\"\\s*:\\s*\"([A-Z]+)\"");
            if (outcome == null) {
                readings.add(new Reading(id, "MISSING", "上游产物里没有该判据读数"));
            } else {
                String detail = firstGroup(text, "\\{\"id\"\\s*:\\s*\"" + Pattern.quote(id)
                        + "\"\\s*,\\s*\"outcome\"\\s*:\\s*\"[A-Z]+\"\\s*,\\s*\"detail\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
                readings.add(new Reading(id, outcome, detail == null ? "" : unescape(detail)));
            }
        }
        String orthOutcome = "MISSING";
        String orthDetail = "";
        for (Reading r : readings) {
            if ("OR-1".equals(r.id())) {
                orthOutcome = r.outcome();
                orthDetail = r.detail();
            }
        }
        String verdict;
        if ("PASS".equals(orthOutcome)) {
            verdict = "PASS";
        } else if ("FAIL".equals(orthOutcome)) {
            verdict = "FAIL";
        } else if ("UNVERIFIED".equals(orthOutcome)) {
            verdict = "UNVERIFIED";
        } else {
            verdict = "unavailable";
        }
        List<String> axisMeta = new ArrayList<>();
        Matcher ax = Pattern.compile("\"axis\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"orientation\"\\s*:\\s*\"([^\"]+)\""
                + "\\s*,\\s*\"domain\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
        while (ax.find() && axisMeta.size() < 3) {
            axisMeta.add(ax.group(1) + "：朝向=" + ax.group(2) + "，值域=" + ax.group(3));
        }
        Series series = new Series("上游产物：" + pathText + "（" + kind + "）", false, samples);
        Info info = new Info(true, "", pathText, sha16, bytes.length, nvl(schema), nvl(instrument), nvl(kind),
                samples.size(), usable, nvl(firstSupp), readings, axisMeta, orthoMax, verdict, series, dtMax);
        if (!orthDetail.isEmpty()) {
            readings.add(new Reading("OR-1.detail", "INFO", orthDetail.length() > 400 ? orthDetail.substring(0, 400) + "…" : orthDetail));
        }
        return info;
    }

    public static String orthGate(String outcome, List<Double> rho2Values, double orthoMax) {
        if ("FAIL".equals(outcome)) {
            return "FAIL";
        }
        if ("UNVERIFIED".equals(outcome) || outcome == null || outcome.isEmpty() || "MISSING".equals(outcome)) {
            return "UNVERIFIED";
        }
        if (!Double.isNaN(orthoMax)) {
            for (Double r : rho2Values) {
                if (r != null && !Double.isNaN(r.doubleValue()) && r.doubleValue() > orthoMax) {
                    return "FAIL";
                }
            }
        }
        return "PASS";
    }

    public static List<Double> rho2Of(String detail) {
        List<Double> out = new ArrayList<>();
        Matcher m = Pattern.compile("ρ²\\s*=\\s*([0-9.eE+-]+)").matcher(detail == null ? "" : detail);
        while (m.find()) {
            out.add(Double.valueOf(parseDouble(m.group(1), Double.NaN)));
        }
        return out;
    }

    private static String section(String text, String startRegex, String endMarker) {
        Matcher m = Pattern.compile(startRegex).matcher(text);
        if (!m.find()) {
            return text;
        }
        int from = m.end();
        int to = text.indexOf(endMarker, from);
        return (to > from) ? text.substring(from, to) : text.substring(from);
    }

    private static double axisValue(String axesText, String key) {
        int k = axesText.indexOf("\"" + key + "\"");
        if (k < 0) {
            return Double.NaN;
        }
        int c = axesText.indexOf(':', k);
        if (c < 0) {
            return Double.NaN;
        }
        int p = c + 1;
        while (p < axesText.length() && Character.isWhitespace(axesText.charAt(p))) {
            p++;
        }
        if (p >= axesText.length()) {
            return Double.NaN;
        }
        if (axesText.charAt(p) == '{') {
            return Double.NaN;
        }
        int q = p;
        while (q < axesText.length() && "-+.eE0123456789".indexOf(axesText.charAt(q)) >= 0) {
            q++;
        }
        try {
            return Double.parseDouble(axesText.substring(p, q));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String firstGroup(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\");
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static double parseDouble(String s, double dflt) {
        if (s == null) {
            return dflt;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    static String sha16(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.ROOT, "%02X", Byte.valueOf(d[i])));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String summarize(Map<String, String> outcomeById) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new LinkedHashMap<>(outcomeById).entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
