package com.octant.pipeline.analysis;

import com.octant.pipeline.feature.FeatureEval;
import com.octant.pipeline.feature.Features;
import com.octant.pipeline.feature.SampleBasis;
import com.octant.pipeline.feature.Thresholds;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.EventStream;
import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;
import com.octant.pipeline.raw.Session;
import com.octant.pipeline.raw.UnitKind;
import com.octant.pipeline.rule.RuleCatalog;
import com.octant.pipeline.rule.RuleSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record AnalysisReport(
        String schemaVersion,
        String featureContractVersion,
        String rawEventSchemaVersion,
        String catalogVersion,
        String metricVersion,
        List<MetricOutcome> metrics,
        List<Conclusion> conclusions,
        List<Segment> segments,
        List<Recommendation> recommendations,
        List<Evidence> evidence,
        List<Chart> charts,
        Map<String, Object> suppressionSummary,
        Map<String, Object> dataQuality,
        AnalysisWindow window,
        Map<String, Object> runStats) {

    public static final String ANALYSIS_OUTPUT_MODEL = "analysis-output-model@2.0.0";
    public static final String FEATURE_CONTRACT = "feature-contract@2.0.0";
    public static final String RAW_EVENT_SCHEMA = "raw-event-schema@1.0.0";
    public static final String METRIC_VERSION = "MS-1 v2.0.0";

    public AnalysisReport {
        metrics = List.copyOf(metrics);
        conclusions = List.copyOf(conclusions);
        segments = List.copyOf(segments);
        recommendations = List.copyOf(recommendations);
        evidence = List.copyOf(evidence);
        charts = List.copyOf(charts);
        suppressionSummary = ordered(suppressionSummary);
        dataQuality = ordered(dataQuality);
        runStats = ordered(runStats);
    }

    static Map<String, Object> ordered(Map<String, Object> map) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public MetricOutcome metric(String metricId) {
        for (MetricOutcome m : metrics) {
            if (m.metricId().equals(metricId)) {
                return m;
            }
        }
        return null;
    }

    public Evidence evidenceById(String evidenceId) {
        for (Evidence e : evidence) {
            if (e.evidenceId().equals(evidenceId)) {
                return e;
            }
        }
        return null;
    }

    public Chart chartById(String chartId) {
        for (Chart c : charts) {
            if (c.chartId().equals(chartId)) {
                return c;
            }
        }
        return null;
    }

    public List<MetricOutcome> suppressedMetrics() {
        List<MetricOutcome> out = new ArrayList<>();
        for (MetricOutcome m : metrics) {
            if (m.isSuppressedLike()) {
                out.add(m);
            }
        }
        return out;
    }

    public boolean allSuppressed() {
        for (MetricOutcome m : metrics) {
            if (!m.isSuppressedLike()) {
                return false;
            }
        }
        return true;
    }

    public static AnalysisReport analyze(List<RawEvent> events, ContentCatalog catalog, String source) {
        return analyze(EventStream.of(events), catalog, source);
    }

    public static AnalysisReport analyze(List<RawEvent> events, ContentCatalog catalog, String source,
                                         long declaredSeconds) {
        return analyze(EventStream.of(events).withDeclaredPlaySeconds(declaredSeconds), catalog, source);
    }

    public static AnalysisReport analyze(EventStream stream, ContentCatalog catalog, String source) {
        Features features = new Features(new Features.Context(stream, catalog, source));
        List<FeatureEval> evals = features.buildEvals();
        loadSessionSeries(stream, catalog, source);

        AnalysisWindow activeWindow = buildWindow(stream, features);
        List<MetricOutcome> metrics = new ArrayList<>();
        Map<String, List<GateCheck>> gateSink = new LinkedHashMap<>();
        for (FeatureEval e : evals) {
            metrics.add(toOutcome(e, features, stream, gateSink, activeWindow));
        }
        sortMetrics(metrics);

        List<Evidence> evidence = buildEvidence(metrics, activeWindow, stream);
        RuleSet ruleSet = RuleCatalog.standard();
        Map<String, Object> ruleInput = ruleInput(metrics);
        RuleSet.Evaluation evaluation = ruleSet.evaluate(ruleInput);

        List<Conclusion> conclusions = buildConclusions(metrics, evidence, evaluation, activeWindow, features, stream);
        List<Recommendation> recommendations = buildRecommendations(conclusions, evidence);
        List<Chart> charts = buildCharts(metrics, evidence, activeWindow);

        conclusions = rewireCharts(conclusions, charts);

        List<Segment> segments = buildSegments(features, evals, metrics, stream, activeWindow);
        Map<String, Object> suppressionSummary = buildSuppressionSummary(metrics);
        Map<String, Object> dataQuality = buildDataQuality(stream, features, catalog);
        dataQuality.put("activeSecondsSource",
                stream.declaredPlaySeconds() > 0L ? "save_play_time" : "event_derived");
        dataQuality.put("activeSecondsDeclared", stream.declaredPlaySeconds());
        dataQuality.put("activeSecondsDerived", stream.derivedActiveSeconds());
        dataQuality.put("activeSecondsUsed", stream.totalActiveSeconds());
        Map<String, Object> gateJson = new LinkedHashMap<>();
        for (Map.Entry<String, List<GateCheck>> en : gateSink.entrySet()) {
            List<Object> rows = new ArrayList<>();
            for (GateCheck c : en.getValue()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("basis", c.basis());
                row.put("current", c.current());
                row.put("required", c.required());
                row.put("passed", c.passed());
                rows.add(row);
            }
            gateJson.put(en.getKey(), rows);
        }
        dataQuality.put("gateChecks", gateJson);
        Map<String, Object> runStats = buildRunStats(stream, features, evaluation, ruleSet);

        return new AnalysisReport(ANALYSIS_OUTPUT_MODEL, FEATURE_CONTRACT, RAW_EVENT_SCHEMA,
                catalog.version(), METRIC_VERSION,
                sortedCopy(metrics, MetricOutcome::metricId),
                sortedCopy(conclusions, Conclusion::conclusionId),
                sortedCopy(segments, Segment::segmentId),
                sortedCopy(recommendations, Recommendation::recommendationId),
                sortedCopy(evidence, Evidence::evidenceId),
                sortedCopy(charts, Chart::chartId),
                suppressionSummary, dataQuality, activeWindow, runStats);
    }

    private static <T> List<T> sortedCopy(List<T> in, java.util.function.Function<T, String> key) {
        List<T> out = new ArrayList<>(in);
        out.sort(java.util.Comparator.comparing(key));
        return out;
    }

    private static void sortMetrics(List<MetricOutcome> metrics) {
        metrics.sort(java.util.Comparator.comparing(MetricOutcome::metricId));
    }

    private static AnalysisWindow buildWindow(EventStream stream, Features features) {
        List<String> sessionIds = new ArrayList<>();
        for (Session s : stream.sessions()) {
            sessionIds.add(s.sessionId());
        }
        long startTick = stream.sessions().isEmpty() ? 0L : stream.sessions().get(0).startTRelMs() / 50L;
        long endTick = stream.sessions().isEmpty() ? 0L
                : stream.sessions().get(stream.sessions().size() - 1).endTRelMs() / 50L;
        return AnalysisWindow.active(startTick, endTick, 0, Math.max(0, features.relativeDayCount() - 1),
                stream.totalWallMs(), sessionIds);
    }

    private static MetricOutcome toOutcome(FeatureEval e, Features features, EventStream stream,
                                           Map<String, List<GateCheck>> gateSink,
                                           AnalysisWindow window) {
        long activeS = features.activeSeconds();
        int sessionN = features.sessionCount();
        int effectiveN = features.effectiveSessionCount();
        Integer unitCount = e.unitCount();
        long k = kOf(e);

        String reason = null;
        String zeroCode = null;

        Gate g = gate(e.metricId(), stream, features, activeS, effectiveN, unitCount);
        gateSink.put(e.metricId(), g.checks());
        if (e.value() == null) {
            reason = g.reasonCode();
            if (reason == null) {
                reason = gateForMissing(e, stream);
            }
        } else {
            reason = g.reasonCode();
            if (reason == null && e.zeroCode() != null) {
                zeroCode = e.zeroCode();
            }
        }
        if (reason == null && "M3a".equals(e.metricId()) && stream.sessionCount() == 0) {
            reason = ReasonCodes.INPUT_MISSING;
        }
        MetricStatus status;
        if (reason != null) {
            status = reason.startsWith("INPUT_") ? MetricStatus.UNAVAILABLE : MetricStatus.SUPPRESSED;
        } else if (zeroCode != null) {
            status = MetricStatus.ZERO;
        } else if (e.value() == null) {
            status = MetricStatus.UNAVAILABLE;
            reason = ReasonCodes.INPUT_MISSING;
        } else {
            status = MetricStatus.AVAILABLE;
        }

        SampleSize sample = SampleSize.of(stream.events().size(), sessionN, unitCount,
                List.of(e.metricId()),
                "M3a".equals(e.metricId()) ? SampleBasis.ACTIVE_TIME : basisOf(e.metricId()),
                k, activeS);
        long current = sample.currentSample();
        boolean suppressedLike = status.valueMustBeAbsent();

        double afkShare = stream.afkShare();
        double inputCoverage = coverageOf(e, features);
        double proxyQuality = e.heuristic() ? 0.6d : 1.0d;
        int nEff = ConfidenceDetail.nEffOf(stream.events().size(), sessionN, unitCount, k);
        double adequacy = ConfidenceDetail.sampleAdequacy(nEff, k);
        List<String> degraded = degradeCodes(e, status, reason, stream, activeS, afkShare);
        ConfidenceLevel level = ConfidenceDetail.levelFor(suppressedLike, stream.events().size(),
                e.heuristic(), estimatedModel(e.metricId()), degraded);
        double score = ConfidenceDetail.computeScore(adequacy, inputCoverage, proxyQuality, afkShare);
        ConfidenceDetail detail = new ConfidenceDetail(level, score, nEff, k, unitCount,
                inputCoverage, proxyQuality, afkShare, adequacy, false, degraded);

        Object value = null;
        Map<String, Double> quantiles = null;
        Map<String, Double> series = null;
        String namedKey = null;
        if (!suppressedLike) {
            switch (e.outputType()) {
                case "quantiles" -> {
                    quantiles = e.quantiles();
                    value = e.value();
                }
                case "series" -> series = e.series();
                case "named" -> {
                    namedKey = e.namedKey();
                    value = e.value();
                }
                default -> value = e.value();
            }
            if (series == null) {
                if (SERIES_ATTACH) {
                    Map<String, Double> sess = perSessionSeries(e.metricId(), features, stream);
                    if (sess != null) {
                        series = sess;
                    }
                }
            }
        }

        String reasonText = null;
        if (reason != null) {
            reasonText = reason + ": " + e.metricId() + " k=" + k + " current="
                    + current + " " + sample.basis().unitLabel();
        }

        return new MetricOutcome(e.metricId(), e.featureId(), e.expr(), e.outputType(),
                unitOf(e.metricId()), status, value, quantiles, series, namedKey, null, null,
                sample, level, detail, window, reason, reasonText, zeroCode,
                numeratorOrNull(e), denominatorOrNull(e), null, e.dataQualityFlags());
    }

    private static Double numeratorOrNull(FeatureEval e) {
        double d = e.denominator();
        return d == 0.0d && e.numerator() == 0.0d ? null : e.numerator();
    }

    private static final List<String> SERIES_FIRST_SLICE = List.of("M1a", "M1b", "M1c");

    static final boolean SERIES_ATTACH = true;

    static Map<String, Double> perSessionSeries(String metricId, Features features, EventStream stream) {
        if (!SERIES_FIRST_SLICE.contains(metricId)) {
            return null;
        }
        List<Session> sessions = new ArrayList<>(stream.sessions());
        if (sessions.size() < 2) {
            return null;
        }
        sessions.sort(java.util.Comparator.comparingLong(Session::startTRelMs)
                .thenComparing(Session::sessionId));
        Map<String, Double> out = new LinkedHashMap<>();
        List<RawEvent> prefix = new ArrayList<>();
        for (Session s : sessions) {
            prefix.addAll(stream.eventsOf(s));
            if (prefix.isEmpty()) {
                continue;
            }
            EventStream sub = EventStream.of(List.copyOf(prefix));
            Features f = new Features(new Features.Context(sub, features.catalog(),
                    features.context().source()));
            Optional<com.octant.pipeline.feature.FeatureValue> v = switch (metricId) {
                case "M1a" -> f.m1a();
                case "M1b" -> f.m1b();
                case "M1c" -> f.m1c();
                default -> Optional.empty();
            };
            if (v.isPresent() && v.get().isNumber()) {
                out.put(s.sessionId(), round3(v.get().asDouble()));
            }
        }
        return out.size() >= 2 ? out : null;
    }

    static String seriesBasisOf(String metricId) {
        return SERIES_FIRST_SLICE.contains(metricId)
                ? "cumulative_prefix_sessions（第 1..i 个会话为止的累计窗口；点=sampleSize.sessionId）"
                : null;
    }

    private static Double denominatorOrNull(FeatureEval e) {
        return e.denominator() == 0.0d ? null : e.denominator();
    }

    private static String gateForMissing(FeatureEval e, EventStream stream) {
        return switch (e.metricId()) {
            case "M3b", "M3c", "M3d" -> stream.sessionCount() == 0
                    ? ReasonCodes.INPUT_MISSING : ReasonCodes.SAMPLE_INSUFFICIENT_SESSIONS;
            case "M3e" -> ReasonCodes.SAMPLE_INSUFFICIENT_SESSIONS_FOR_TREND;
            case "M7d", "M7e" -> ReasonCodes.WINDOW_NOT_ELAPSED;
            case "M7c" -> ReasonCodes.INPUT_DEVICE_EVIDENCE_MISSING;
            case "M8a", "M8b", "M8c", "M8d", "M8e", "M8f", "D6_COMBAT" ->
                    ReasonCodes.SAMPLE_INSUFFICIENT_COMBAT;
            case "M8g" -> ReasonCodes.SAMPLE_BELOW_DEATH_CAUSE_OCCURRENCES;
            case "M2a", "M2b", "M2c" -> ReasonCodes.SAMPLE_INSUFFICIENT_OCCURRENCES;
            case "M2d" -> ReasonCodes.SAMPLE_PROXY_UNAVAILABLE;
            case "M4", "M5a", "M5b", "M5c", "M5d" -> ReasonCodes.SAMPLE_INSUFFICIENT_UNITS;
            case "M6a", "M6b", "M6c", "M6d", "M6e" -> ReasonCodes.SAMPLE_INSUFFICIENT_OCCURRENCES;
            default -> ReasonCodes.SAMPLE_BELOW_MIN;
        };
    }

    public record GateCheck(String basis, long current, long required) {
        public boolean passed() {
            return current >= required;
        }
    }

    private record Gate(String reasonCode, List<GateCheck> checks) {
    }

    private static boolean chk(List<GateCheck> sink, String basis, long current, long required) {
        sink.add(new GateCheck(basis, current, required));
        return current >= required;
    }

    private static Gate gate(String metricId, EventStream stream, Features features,
                             long activeS, int effectiveN, Integer unitCount) {
        List<GateCheck> c = new ArrayList<>();
        int units = unitCount == null ? 0 : unitCount;
        switch (metricId) {
            case "M1a", "M1b", "M1c", "M1-tail", "M4", "M7d", "M7e",
                 "D2_DEPTH", "D3_BREADTH", "D4_DISPERSION", "D1_PACE" -> {
                if (!chk(c, "内容单元数", units, Thresholds.MIN_CONTENT_UNIT)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_UNITS, c);
                }
                if (!chk(c, "活跃时长(秒)", activeS, Thresholds.MIN_ACTIVE_S)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_ACTIVE_TIME, c);
                }
            }
            case "M2a", "M2b", "M2c", "M2d" -> {
                if (!chk(c, "活跃时长(秒)", activeS, Thresholds.MIN_ACTIVE_S)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_ACTIVE_TIME, c);
                }
                if (!chk(c, "合成尝试单元数", units, Thresholds.MIN_UNIT_ATTEMPTS)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_ATTEMPTS, c);
                }
            }
            case "M3a" -> {
            }
            case "M3b", "M3c", "M3d" -> {
                if (!chk(c, "有效会话数", effectiveN, Thresholds.MIN_SESSIONS_FOR_DISTRIBUTION)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_SESSIONS_FOR_DISTRIBUTION, c);
                }
            }
            case "M3e", "D7_PERSIST" -> {
                if (!chk(c, "有效会话数", effectiveN, Thresholds.MIN_SESSIONS_FOR_TREND)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_SESSIONS_FOR_TREND, c);
                }
                if (!chk(c, "相对天数", stream.relativeDayCount(), 2)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_SESSIONS_FOR_TREND, c);
                }
            }
            case "M5a", "M5b", "M5c", "M5d" -> {
                if (!chk(c, "内容单元数", units, Thresholds.MIN_CONTENT_UNIT)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_UNITS, c);
                }
                if (!chk(c, "活跃时长(秒)", activeS, Thresholds.MIN_ACTIVE_S_FOR_M5)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_ACTIVE_TIME, c);
                }
            }
            case "M6a", "M6b", "M6c", "M6d", "M6e" -> {
                if (!chk(c, "类别出现次数", units, Thresholds.MIN_CATEGORY_OCCURRENCES)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_OCCURRENCES, c);
                }
                if (!chk(c, "活跃时长(秒)", activeS, Thresholds.MIN_ACTIVE_S)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_ACTIVE_TIME, c);
                }
            }
            case "M7a" -> {
                if (!chk(c, "重复片段数", units, Thresholds.MIN_REPEAT_SEGMENTS)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_OCCURRENCES, c);
                }
                if (!chk(c, "活跃时长(秒)", activeS, Thresholds.MIN_ACTIVE_S)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_ACTIVE_TIME, c);
                }
            }
            case "M7b" -> {
                if (!chk(c, "有效会话数", effectiveN, Thresholds.MIN_SESSIONS_FOR_TREND)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_SESSIONS_FOR_TREND, c);
                }
            }
            case "M7c" -> {
                if (!chk(c, "自动化设备数", units, Thresholds.MIN_AUTOMATION_DEVICES)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_UNITS, c);
                }
            }
            case "M8a", "M8b", "D6_COMBAT" -> {
                if (!chk(c, "战斗遭遇数", units, Thresholds.MIN_COMBAT_ENCOUNTERS_PARTIAL)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_COMBAT, c);
                }
            }
            case "M8c", "M8d", "M8e", "M8f" -> {
                if (eIsFarmOnly(stream, units)) {
                    return new Gate(ReasonCodes.ACTION_FARM_COMBAT_EXCLUDED, c);
                }
                if (!chk(c, "战斗遭遇数", units, Thresholds.MIN_COMBAT_ENCOUNTERS)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_COMBAT, c);
                }
            }
            case "M8g" -> {
                if (!chk(c, "死因出现次数", units, Thresholds.MIN_DEATH_CAUSE_OCCURRENCES)) {
                    return new Gate(ReasonCodes.SAMPLE_BELOW_DEATH_CAUSE_OCCURRENCES, c);
                }
                if (!chk(c, "有效会话数", effectiveN, Thresholds.MIN_SESSION_N)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_SESSIONS, c);
                }
            }
            case "D5_CRAFT" -> {
                if (!chk(c, "活跃时长(秒)", activeS, Thresholds.MIN_ACTIVE_S)) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_ACTIVE_TIME, c);
                }
            }
            case "M9_SEGMENT" -> {
                boolean byTime = chk(c, "活跃时长(秒)", activeS, Thresholds.MIN_ACTIVE_S_FOR_M5);
                boolean bySess = chk(c, "有效会话数", effectiveN, Thresholds.MIN_PROFILE_SESSIONS);
                if (!byTime && !bySess) {
                    return new Gate(ReasonCodes.SAMPLE_INSUFFICIENT_EVIDENCE_ITEMS, c);
                }
            }
            default -> {
            }
        }
        return new Gate(null, c);
    }

    private static boolean eIsFarmOnly(EventStream stream, int units) {
        return units == 0 && stream.count(EventType.COMBAT_STARTED) > 0;
    }

    private static double coverageOf(FeatureEval e, Features features) {
        double required = 1.0d;
        double got = e.value() == null ? 0.0d : 1.0d;
        if (e.denominator() <= 0.0d && e.denominatorSource() != null
                && !"-".equals(e.denominatorSource())) {
            required = 2.0d;
            got = got + (e.numerator() > 0.0d ? 1.0d : 0.0d);
        }
        return Math.max(0.0d, Math.min(1.0d, got / required));
    }

    private static boolean estimatedModel(String metricId) {
        return switch (metricId) {
            case "M1b", "M2b", "M3d", "M4", "M5a", "M5b", "M5c", "M5d", "M6b", "M6c", "M6d",
                 "M7a", "M7b", "M7c", "M7e", "M8a", "M8b", "M8c", "M8e", "M8f", "M8g" -> true;
            default -> false;
        };
    }

    private static List<String> degradeCodes(FeatureEval e, MetricStatus status, String reason,
                                             EventStream stream, long activeS, double afkShare) {
        Set<String> codes = new java.util.TreeSet<>();
        if (stream.events().size() < Thresholds.MIN_EVENTS_FOR_CONFIDENCE) {
            codes.add("events_below_30");
        }
        if (afkShare > 0.30d) {
            codes.add("afk_share_over_30pct");
        }
        for (Session s : stream.sessions()) {
            if (s.openSession()) {
                codes.add("open_session_closed_at_last_event");
                break;
            }
        }
        if (stream.discardedCount() > 0) {
            codes.add("truncated_stream");
        }
        if (e.heuristic()) {
            codes.add("proxy_below_threshold");
        } else if ("M4".equals(e.metricId()) && stream.events().size() > 0 && codeProxySubstituted(e)) {
            codes.add("input_substituted");
        }
        if (reason != null && ConfidenceDetail.DEGRADE_CODES.contains(reason.toLowerCase())) {
            codes.add(reason.toLowerCase());
        }
        return List.copyOf(codes);
    }

    private static boolean codeProxySubstituted(FeatureEval e) {
        return e.heuristic();
    }

    private static long kOf(FeatureEval e) {
        for (Features.Supports s : Features.registry()) {
            if (s.metricId().equals(e.metricId())) {
                return s.minRequired();
            }
        }
        return Thresholds.MIN_CONTENT_UNIT;
    }

    private static SampleBasis basisOf(String metricId) {
        for (Features.Supports s : Features.registry()) {
            if (s.metricId().equals(metricId)) {
                return s.basis();
            }
        }
        return SampleBasis.EVENTS;
    }

    private static String unitOf(String metricId) {
        for (Features.Supports s : Features.registry()) {
            if (s.metricId().equals(metricId)) {
                return s.unit();
            }
        }
        return "-";
    }

    private static List<Evidence> buildEvidence(List<MetricOutcome> metrics, AnalysisWindow window,
                                                EventStream stream) {
        List<Evidence> out = new ArrayList<>();
        int seq = 1;
        for (MetricOutcome m : metrics) {
            Evidence ev = evidenceFor(m, window, stream, String.format("E%03d", seq++));
            out.add(ev);
        }
        return out;
    }

    private static volatile Map<String, Map<String, Double>> sessionSeriesByMetric = Map.of();

    private static void loadSessionSeries(EventStream stream, ContentCatalog catalog, String source) {
        List<Session> sessions = new ArrayList<>(stream.sessions());
        sessions.sort(java.util.Comparator.comparingLong(Session::startTRelMs)
                .thenComparing(Session::sessionId));
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        List<RawEvent> prefix = new ArrayList<>();
        for (Session s : sessions) {
            prefix.addAll(stream.eventsOf(s));
            if (prefix.isEmpty()) {
                continue;
            }
            EventStream sub = EventStream.of(List.copyOf(prefix));
            Features f = new Features(new Features.Context(sub, catalog, source));
            for (FeatureEval ev : f.buildEvals()) {
                Double v = ev.value();
                if (v == null) {
                    continue;
                }
                out.computeIfAbsent(ev.metricId(), k -> new LinkedHashMap<>())
                        .put(s.sessionId(), round3(v));
            }
        }
        sessionSeriesByMetric = out;
    }

    static Map<String, Double> perMetricStatistics(MetricOutcome m, EventStream stream) {
        Map<String, Double> statistic = new LinkedHashMap<>();
        List<Double> dist = new ArrayList<>();
        Map<String, Double> own = sessionSeriesByMetric.get(m.metricId());
        boolean fromOwnSeries = own != null && own.size() >= 2;
        if (fromOwnSeries) {
            dist.addAll(own.values());
        } else if (m.series() != null && !m.series().isEmpty()
                && "series".equals(m.outputType())) {
            dist.addAll(m.series().values());
        } else {
            List<Session> sessions = stream.sessions();
            long totalActive = 0L;
            for (Session s : sessions) {
                totalActive += Math.max(0L, s.activeMs());
            }
            long units = m.sampleSize().unitCount() > 0
                    ? m.sampleSize().unitCount()
                    : Math.round(m.denominator() == null ? 0.0d : m.denominator());
            if (sessions.isEmpty() || totalActive <= 0L) {
                dist.add((double) units);
            } else {
                long assigned = 0L;
                for (Session s : sessions) {
                    long v = Math.floorDiv(units * Math.max(0L, s.activeMs()), totalActive);
                    dist.add((double) v);
                    assigned += v;
                }
                int i = 0;
                while (assigned < units) {
                    dist.set(i, dist.get(i) + 1.0d);
                    assigned++;
                    i = (i + 1) % sessions.size();
                }
            }
        }
        List<Double> sorted = new ArrayList<>(dist);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        double median = n == 0 ? 0.0d
                : (n % 2 == 1 ? sorted.get(n / 2)
                   : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0d);
        double sum = 0.0d;
        for (double v : sorted) {
            sum += v;
        }
        double mean = n == 0 ? 0.0d : sum / n;
        statistic.put("median", round3(median));
        statistic.put("mean", round3(mean));
        statistic.put("n", (double) n);
        double units = m.sampleSize().unitCount() > 0
                ? m.sampleSize().unitCount()
                : (m.denominator() == null ? 0.0d : m.denominator());
        statistic.put("units", round3(units));
        double num = m.numerator() == null ? 0.0d : m.numerator();
        double den = m.denominator() == null ? 0.0d : m.denominator();
        statistic.put("share", den > 0.0d ? round3(num / den) : 0.0d);
        if (m.value() instanceof Number v) {
            statistic.put("value", round3(v.doubleValue()));
        }
        if (m.quantiles() != null) {
            for (Map.Entry<String, Double> e : m.quantiles().entrySet()) {
                statistic.put(e.getKey(), round3(e.getValue()));
            }
        }
        String basisWire = m.sampleSize().basis() == null ? "" : m.sampleSize().basis().wireName();
        if (!basisWire.isBlank()) {
            statistic.put("basisCode", (double) basisCode(basisWire));
        }
        return statistic;
    }

    private static int basisCode(String source) {
        String s = source == null ? "" : source;
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = h * 31 + s.charAt(i);
        }
        return Math.abs(h % 1000);
    }

    private static Evidence evidenceFor(MetricOutcome m, AnalysisWindow window, EventStream stream,
                                        String evidenceId) {
        Map<String, Double> statistic = perMetricStatistics(m, stream);
        if (m.quantiles() != null) {
            for (Map.Entry<String, Double> e : m.quantiles().entrySet()) {
                statistic.put(e.getKey(), round3(e.getValue()));
            }
        }
        if (m.value() instanceof Number n) {
            statistic.put("value", round3(n.doubleValue()));
        }
        double numerator = m.numerator() == null ? 0.0d : m.numerator();
        double denominator = m.denominator() == null ? 0.0d : m.denominator();

        Map<String, Object> dataQuality = new LinkedHashMap<>();
        dataQuality.put("missingPoints", (long) stream.deduplicatedCount());
        dataQuality.put("truncated", stream.discardedCount() > 0);
        dataQuality.put("handling", "omitted_not_imputed");
        dataQuality.put("droppedEvents", (long) stream.discardedCount());
        dataQuality.put("deduplicatedEvents", (long) stream.deduplicatedCount());
        if (!m.dataQualityFlags().isEmpty()) {
            dataQuality.put("flags", m.dataQualityFlags());
        }

        String uncertainty = m.isSuppressedLike()
                ? "k=" + m.sampleSize().minRequired() + " " + m.sampleSize().minRequiredUnit()
                  + "; currentSample=" + m.currentSample()
                : "k=" + m.sampleSize().minRequired() + " " + m.sampleSize().minRequiredUnit()
                  + "; nEff=" + m.confidenceDetail().nEff();
        String sourceStr = m.denominator() == null ? "unavailable" : sourceOf(m.metricId());
        return new Evidence(evidenceId, ruleIdOf(m.metricId()), List.of(m.featureId()),
                numerator, denominator, sourceStr, statistic, "linear", uncertainty, window,
                dataQuality, List.of(), m.confidence(), m.status(), m.reasonCode());
    }

    private static String sourceOf(String metricId) {
        return switch (metricId) {
            case "M1a", "M1b", "M1-tail", "M4", "M7d", "M7e", "M5a", "M5b", "M5c", "M5d" -> "reachable";
            case "M2a", "M2b", "M2c", "M7a" -> "attempted";
            case "M3a", "M3d", "M6e", "M8a", "D1_PACE", "D5_CRAFT", "M3b", "M3c", "M3e",
                 "D7_PERSIST" -> "observed_active";
            case "M6a", "M6b", "M6c", "M6d", "M7b", "M7c", "M8b", "M8c", "M8d", "M8e", "M8f",
                 "D6_COMBAT" -> "observed_events";
            case "M8g" -> "deaths";
            default -> "observed";
        };
    }

    private static String ruleIdOf(String metricId) {
        return switch (metricId) {
            case "M1a", "M1b", "M1c", "M1-tail" -> "RS_M1_CONC";
            case "M2a", "M2b", "M2c", "M2d" -> "RS_M1_STALL_CONC";
            case "M3a", "M3b", "M3c", "M3d" -> "RS_M3_CONC";
            case "M3e", "D7_PERSIST" -> "RS_M3E_CONC";
            case "M4", "D3_BREADTH" -> "RS_M4_CONC";
            case "M5a", "M5b", "M5c", "M5d", "D4_DISPERSION" -> "RS_M5_CONC";
            case "M6a", "M6b", "M6c", "M6d", "M6e" -> "RS_M6_CONC";
            case "M7a", "M7b", "M7c", "M7d", "M7e" -> "RS_M7_CONC";
            case "M8g" -> "RS_M8_DEATH_CONC";
            case "M8a", "M8b", "M8c", "M8d", "M8e", "M8f", "D6_COMBAT" -> "RS_M8_CONC";
            case "M9_SEGMENT" -> "RS_SEG_01";
            default -> "RS_M9_CONC";
        };
    }

    private static Map<String, Object> ruleInput(List<MetricOutcome> metrics) {
        Map<String, Object> f = new LinkedHashMap<>();
        for (MetricOutcome m : metrics) {
            f.put(RuleCatalog.K_STATUS + ":" + m.metricId(), m.status().wireName());
            if (m.value() instanceof Number n) {
                f.put(RuleCatalog.K_VALUE + ":" + m.metricId(), n.doubleValue());
            } else if (m.namedKey() != null) {
                f.put(RuleCatalog.K_VALUE + ":" + m.metricId(), 0.0d);
            }
            if (m.reasonCode() != null) {
                f.put(RuleCatalog.K_REASON + ":" + m.metricId(), m.reasonCode());
            }
        }
        return f;
    }

    private static List<Conclusion> buildConclusions(List<MetricOutcome> metrics, List<Evidence> evidence,
                                                     RuleSet.Evaluation evaluation, AnalysisWindow window,
                                                     Features features, EventStream stream) {
        List<Conclusion> out = new ArrayList<>();
        int seq = 1;
        for (Map.Entry<String, com.octant.pipeline.rule.Rule> entry : evaluation.effective().entrySet()) {
            com.octant.pipeline.rule.Rule rule = entry.getValue();
            if (!rule.id().startsWith("RS_M")) {
                continue;
            }
            String primaryMetric = rule.metricIds().get(0);
            MetricOutcome primary = find(metrics, primaryMetric);
            if (primary == null) {
                continue;
            }
            boolean suppressed = primary.isSuppressedLike();
            List<String> evidenceIds = new ArrayList<>();
            for (Evidence ev : evidence) {
                if (rule.metricIds().contains(ev.featureIds().isEmpty() ? "" : metricOfFeature(ev.featureIds().get(0)))
                        || rule.featureIds().contains(ev.featureIds().get(0))) {
                    evidenceIds.add(ev.evidenceId());
                }
            }
            if (suppressed) {
                Conclusion suppressedConclusion = new Conclusion(String.format("C%03d", seq++),
                        MetricStatus.SUPPRESSED,
                        rule.metricIds(), rule.featureIds(), List.of(rule.id()), rule.statementKey(),
                        Map.of(), primary.sampleSize(), primary.confidence(),
                        primary.confidenceDetail(), window, List.of(), List.of(),
                        primary.reasonCode(), List.of(), List.of());
                out.add(suppressedConclusion);
                continue;
            }
            if (evidenceIds.isEmpty()) {
                continue;
            }
            List<String> chartIds = List.of(chartIdOf(metrics, primaryMetric));
            Conclusion c = new Conclusion(String.format("C%03d", seq++),
                    MetricStatus.AVAILABLE,
                    rule.metricIds(), rule.featureIds(), List.of(rule.id()), rule.statementKey(),
                    statementArgs(rule, metrics),
                    primary.sampleSize(), primary.confidence(), primary.confidenceDetail(),
                    window, evidenceIds, chartIds,
                    null,
                    limitationsOf(primary),
                    primary.confidence() == ConfidenceLevel.HEURISTIC
                            ? List.of("alternative.observation_window_partial") : List.of());
            out.add(c);
        }
        return out;
    }

    private static String metricOfFeature(String featureId) {
        for (Features.Supports s : Features.registry()) {
            if (s.featureId().equals(featureId)) {
                return s.metricId();
            }
        }
        return featureId;
    }

    private static Map<String, Object> statementArgs(com.octant.pipeline.rule.Rule rule,
                                                     List<MetricOutcome> metrics) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (String metricId : rule.metricIds()) {
            MetricOutcome m = find(metrics, metricId);
            if (m == null || m.isSuppressedLike()) {
                continue;
            }
            if (m.value() instanceof Number n) {
                args.put(camel(metricId), round3(n.doubleValue()));
            }
            if (m.namedKey() != null) {
                args.put(camel(metricId) + "Key", m.namedKey());
            }
        }
        return args;
    }

    private static String camel(String metricId) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : metricId.toCharArray()) {
            if (c == '-' || c == '_') {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = false;
        }
        return sb.toString();
    }

    private static List<String> limitationsOf(MetricOutcome m) {
        List<String> out = new ArrayList<>();
        for (String code : m.confidenceDetail().degradedBy()) {
            out.add(code);
        }
        return out;
    }

    private static MetricOutcome find(List<MetricOutcome> metrics, String metricId) {
        for (MetricOutcome m : metrics) {
            if (m.metricId().equals(metricId)) {
                return m;
            }
        }
        return null;
    }

    private static String chartIdOf(List<MetricOutcome> metrics, String metricId) {
        return String.format("C%02d", indexOf(metrics, metricId) + 1);
    }

    private static List<Conclusion> rewireCharts(List<Conclusion> conclusions, List<Chart> charts) {
        Set<String> available = new LinkedHashSet<>();
        for (Chart c : charts) {
            available.add(c.chartId());
        }
        List<Conclusion> out = new ArrayList<>();
        for (Conclusion c : conclusions) {
            if (c.chartIds().isEmpty() || available.containsAll(c.chartIds())) {
                out.add(c);
                continue;
            }
            out.add(new Conclusion(c.conclusionId(), c.status(), c.metricIds(), c.featureIds(),
                    c.ruleIds(), c.statementKey(), c.statementArgs(), c.sampleSize(), c.confidence(),
                    c.confidenceDetail(), c.window(), c.evidenceIds(), List.of(), c.reasonCode(),
                    c.limitations(), c.alternativeExplanations()));
        }
        return out;
    }

    private static List<Recommendation> buildRecommendations(List<Conclusion> conclusions,
                                                             List<Evidence> evidence) {
        List<Recommendation> out = new ArrayList<>();
        int seq = 1;
        for (Conclusion c : conclusions) {
            if (c.status() != MetricStatus.AVAILABLE) {
                continue;
            }
            if (c.evidenceIds().isEmpty()) {
                continue;
            }
            for (int i = 0; i < Math.min(1, c.metricIds().size()); i++) {
                String metricId = c.metricIds().get(i);
                String kind;
                String targetId;
                String actionKey;
                if (metricId.startsWith("M2")) {
                    kind = "checkpoint";
                    targetId = metricId;
                    actionKey = "action.check_stalled_unit";
                } else if (metricId.startsWith("M6")) {
                    kind = "category";
                    targetId = metricId;
                    actionKey = "action.review_preference_spread";
                } else if (metricId.startsWith("M8")) {
                    kind = "mechanic";
                    targetId = metricId;
                    actionKey = "action.review_combat_difficulty";
                } else {
                    kind = "mechanic";
                    targetId = metricId;
                    actionKey = "action.review_observed_distribution";
                }
                out.add(new Recommendation(String.format("A%02d", seq++), actionKey,
                        new Recommendation.Target(kind, targetId),
                        List.of(c.conclusionId()), List.of(c.evidenceIds().get(0)),
                        "effect.observe_only",
                        "以下信息可能不适用于其它存档：本建议基于本存档的观测窗口",
                        "观察",
                        new Recommendation.Provenance(c.ruleIds(), c.featureIds())));
            }
        }
        return out;
    }

    private static List<Chart> buildCharts(List<MetricOutcome> metrics, List<Evidence> evidence,
                                           AnalysisWindow window) {
        List<Chart> out = new ArrayList<>();
        for (MetricOutcome m : metrics) {
            String chartId = chartIdOf(metrics, m.metricId());
            List<String> evidenceIds = new ArrayList<>();
            for (Evidence ev : evidence) {
                if (ev.featureIds().contains(m.featureId())) {
                    evidenceIds.add(ev.evidenceId());
                }
            }
            if (m.isSuppressedLike()) {
                out.add(new Chart(chartId, ChartForms.SUPPRESSED_PLACEHOLDER, List.of(m.metricId()),
                        "chart." + m.featureId(), m.unit(), null, m.sampleSize(), ConfidenceLevel.SUPPRESSED,
                        m.confidenceDetail(), window, 0.0d, 0, chartId + "-T", null,
                        m.reasonCode() + " (k=" + m.sampleSize().minRequired() + " "
                                + m.sampleSize().minRequiredUnit() + ", current=" + m.currentSample() + ")",
                        null, null, "reading.suppressed", evidenceIds));
            } else {
                List<Map<String, Object>> data = new ArrayList<>();
                if (m.series() != null && !m.series().isEmpty()) {
                    for (Map.Entry<String, Double> e : m.series().entrySet()) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("x", e.getKey());
                        p.put("y", e.getValue());
                        data.add(p);
                    }
                } else if (m.quantiles() != null && !m.quantiles().isEmpty()) {
                    for (Map.Entry<String, Double> e : m.quantiles().entrySet()) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("x", e.getKey());
                        p.put("y", e.getValue());
                        data.add(p);
                    }
                } else {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("x", m.metricId());
                    p.put("y", m.value() instanceof Number n ? n.doubleValue() : 0.0d);
                    data.add(p);
                }
                Map<String, Object> axes = new LinkedHashMap<>();
                axes.put("x", orderedMap("name", "label", "type", "nominal", "unit", "-"));
                axes.put("y", orderedMap("name", m.metricId(), "type", "quantitative",
                        "unit", m.unit(), "fromZero", Boolean.TRUE));
                out.add(new Chart(chartId, ChartForms.forMetric(m.metricId()), List.of(m.metricId()),
                        "chart." + m.featureId(), m.unit(), axes, m.sampleSize(), m.confidence(),
                        m.confidenceDetail(), window, 1.0d, 0, chartId + "-T", data, null,
                        null, null, "reading." + m.featureId(), evidenceIds));
            }
        }
        return out;
    }

    static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static List<Segment> buildSegments(Features features, List<FeatureEval> evals,
                                               List<MetricOutcome> metrics, EventStream stream,
                                               AnalysisWindow window) {
        List<String> dimensions = new ArrayList<>();
        for (String dim : List.of("D1_PACE", "D2_DEPTH", "D3_BREADTH", "D4_DISPERSION", "D5_CRAFT",
                "D6_COMBAT", "D7_PERSIST")) {
            MetricOutcome m = find(metrics, dim);
            if (m != null && !m.isSuppressedLike()) {
                dimensions.add(dim);
            }
        }
        boolean gate = dimensions.size() >= Thresholds.MIN_PROFILE_EVIDENCE_ITEMS
                && stream.effectiveSessions().size() >= Thresholds.MIN_PROFILE_EVIDENCE_SESSIONS
                && (features.activeSeconds() >= Thresholds.MIN_ACTIVE_S_FOR_M5
                    || stream.effectiveSessionCount() >= Thresholds.MIN_PROFILE_SESSIONS);

        SampleSize sample = SampleSize.of(stream.events().size(), stream.sessionCount(),
                stream.effectiveSessionCount(), List.of("M9_SEGMENT"), SampleBasis.SESSIONS,
                Thresholds.MIN_PROFILE_SESSIONS, stream.totalActiveSeconds());
        ConfidenceDetail detail = ConfidenceDetail.suppressedDetail(stream.events().size(),
                stream.sessionCount(), stream.effectiveSessionCount(), Thresholds.MIN_PROFILE_SESSIONS,
                dimensions.size() / 7.0d, 0.6d, stream.afkShare(), ReasonCodes.SAMPLE_INSUFFICIENT_EVIDENCE_ITEMS);

        List<Segment> out = new ArrayList<>();
        if (!gate) {
            out.add(Segment.suppressed("S01", ReasonCodes.SAMPLE_INSUFFICIENT_EVIDENCE_ITEMS,
                    dimensions, sample, ConfidenceLevel.SUPPRESSED, detail, window));
            return out;
        }
        String segmentKey = segmentKeyOf(metrics);
        Map<String, Double> scores = new LinkedHashMap<>();
        List<String> evidenceIds = new ArrayList<>();
        for (String dim : dimensions) {
            MetricOutcome m = find(metrics, dim);
            if (m != null && m.value() instanceof Number n) {
                scores.put(dim, n.doubleValue());
            }
            evidenceIds.add(String.format("E%03d", indexOf(metrics, dim) + 1));
        }
        ConfidenceLevel level = ConfidenceDetail.levelFor(false, stream.events().size(), true, false,
                List.of("proxy_below_threshold"));
        int nEff = ConfidenceDetail.nEffOf(stream.events().size(), stream.sessionCount(),
                stream.effectiveSessionCount(), Thresholds.MIN_PROFILE_SESSIONS);
        ConfidenceDetail profileDetail = new ConfidenceDetail(level,
                ConfidenceDetail.computeScore(ConfidenceDetail.sampleAdequacy(nEff,
                        Thresholds.MIN_PROFILE_SESSIONS), dimensions.size() / 7.0d, 0.6d,
                        stream.afkShare()), nEff, Thresholds.MIN_PROFILE_SESSIONS,
                stream.effectiveSessionCount(), dimensions.size() / 7.0d, 0.6d, stream.afkShare(),
                ConfidenceDetail.sampleAdequacy(nEff, Thresholds.MIN_PROFILE_SESSIONS), false,
                List.of("proxy_below_threshold"));
        out.add(Segment.available("S01", segmentKey, dimensions, scores, evidenceIds, sample, level,
                profileDetail, window));
        return out;
    }

    private static int indexOf(List<MetricOutcome> metrics, String metricId) {
        for (int i = 0; i < metrics.size(); i++) {
            if (metrics.get(i).metricId().equals(metricId)) {
                return i;
            }
        }
        return 0;
    }

    private static String segmentKeyOf(List<MetricOutcome> metrics) {
        MetricOutcome breadth = find(metrics, "D3_BREADTH");
        MetricOutcome dispersion = find(metrics, "D4_DISPERSION");
        MetricOutcome automation = find(metrics, "M7c");
        MetricOutcome combat = find(metrics, "M8a");
        MetricOutcome persist = find(metrics, "D7_PERSIST");
        if (automation != null && automation.value() instanceof Number n && n.doubleValue() >= 0.30d) {
            return "segment.automation_builder";
        }
        if (combat != null && combat.value() instanceof Number n && n.doubleValue() >= 0.15d) {
            return "segment.combat_oriented";
        }
        if (breadth != null && dispersion != null
                && breadth.value() instanceof Number b && dispersion.value() instanceof Number d) {
            if (b.doubleValue() >= 0.5d && d.doubleValue() >= 0.6d) {
                return "segment.broad_explorer";
            }
            if (d.doubleValue() < 0.6d) {
                return "segment.focused_specialist";
            }
        }
        if (persist != null && persist.value() instanceof Number p && p.doubleValue() >= 0.8d) {
            return "segment.persistent_progressor";
        }
        return "segment.early_session";
    }

    private static Map<String, Object> buildSuppressionSummary(List<MetricOutcome> metrics) {
        List<Map<String, Object>> evaluated = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("available", 0);
        counts.put("zero", 0);
        counts.put("suppressed", 0);
        counts.put("unavailable", 0);
        counts.put("error", 0);
        counts.put("unknown", 0);
        for (MetricOutcome m : metrics) {
            evaluated.add(m.summaryItem());
            counts.merge(m.status().wireName(), 1, Integer::sum);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("evaluated", evaluated);
        Map<String, Object> countMap = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            countMap.put(e.getKey(), (long) e.getValue());
        }
        summary.put("counts", countMap);
        summary.put("minRequiredTable", "§5.4");
        summary.put("allEvaluated", Boolean.TRUE);
        summary.put("evaluatedMetricCount", (long) metrics.size());
        summary.put("suppressedMetricCount", (long) metrics.stream().filter(MetricOutcome::isSuppressedLike).count());
        return summary;
    }

    private static Map<String, Object> buildDataQuality(EventStream stream, Features features,
                                                        ContentCatalog catalog) {
        Map<String, Object> dq = new LinkedHashMap<>();
        dq.put("eventCount", (long) stream.events().size());
        dq.put("sessionCount", (long) stream.sessionCount());
        dq.put("effectiveSessionCount", (long) stream.effectiveSessionCount());
        dq.put("openSessionCount", (long) stream.sessions().stream().filter(Session::openSession).count());
        dq.put("afkShare", ConfidenceDetail.round2(stream.afkShare()));
        dq.put("deduplicatedEvents", (long) stream.deduplicatedCount());
        dq.put("droppedEvents", (long) stream.discardedCount());
        dq.put("truncated", stream.discardedCount() > 0);
        dq.put("idleGapThresholdMs", EventStream.IDLE_GAP_THRESHOLD_MS);
        dq.put("relativeDayCount", (long) stream.relativeDayCount());
        dq.put("denominatorApproxAxes", catalog.allApproximate());
        List<String> flags = new ArrayList<>();
        for (com.octant.pipeline.raw.Session s : stream.sessions()) {
            if (s.openSession()) {
                flags.add("FLAG_OPEN_SESSION");
                break;
            }
        }
        if (stream.afkShare() > 0.30d) {
            flags.add("FLAG_AFK_SUSPECT");
        }
        if (catalog.allApproximate()) {
            flags.add("FLAG_PROXY");
        }
        dq.put("flags", List.copyOf(flags));
        return dq;
    }

    private static Map<String, Object> buildRunStats(EventStream stream, Features features,
                                                     RuleSet.Evaluation evaluation, RuleSet ruleSet) {
        Map<String, Object> rs = new LinkedHashMap<>();
        rs.put("ruleCount", (long) ruleSet.rules().size());
        rs.put("rulesMatched", (long) evaluation.matchedCount());
        rs.put("rulesShadowed", (long) evaluation.suppressedShadowed().size());
        rs.put("registeredMetrics", (long) Features.registry().size());
        rs.put("registeredEventTypes", (long) com.octant.pipeline.raw.EventType.values().length);
        rs.put("activeSeconds", features.activeSeconds());
        rs.put("deterministic", Boolean.TRUE);
        return rs;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0d) / 1000.0d;
    }
}
