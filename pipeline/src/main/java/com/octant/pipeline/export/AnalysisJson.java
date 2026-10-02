package com.octant.pipeline.export;

import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.analysis.Chart;
import com.octant.pipeline.analysis.Conclusion;
import com.octant.pipeline.analysis.ConfidenceLevel;
import com.octant.pipeline.analysis.Evidence;
import com.octant.pipeline.analysis.MetricOutcome;
import com.octant.pipeline.analysis.Recommendation;
import com.octant.pipeline.analysis.Segment;
import com.octant.pipeline.json.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnalysisJson {

    private AnalysisJson() {
    }

    public static Json.JsonObject toJson(AnalysisReport r) {
        Json.JsonObject o = new Json.JsonObject();
        o.put("schemaVersion", r.schemaVersion());
        o.put("featureContractVersion", r.featureContractVersion());
        o.put("rawEventSchemaVersion", r.rawEventSchemaVersion());
        o.put("catalogVersion", r.catalogVersion());
        o.put("metricVersion", r.metricVersion());
        o.put("generatedAtDateBucket", r.dataQuality().get("generatedAtDateBucket"));
        o.put("metrics", array(r.metrics(), AnalysisJson::metric));
        o.put("conclusions", array(r.conclusions(), AnalysisJson::conclusion));
        o.put("segments", array(r.segments(), AnalysisJson::segment));
        o.put("recommendations", array(r.recommendations(), AnalysisJson::recommendation));
        o.put("evidence", array(r.evidence(), AnalysisJson::evidence));
        o.put("charts", array(r.charts(), AnalysisJson::chart));
        o.put("suppressionSummary", fromMap(r.suppressionSummary()));
        o.put("dataQuality", fromMap(r.dataQuality()));
        o.put("window", fromMap(r.window().asMap()));
        o.put("runStats", fromMap(r.runStats()));
        return o;
    }

    public static Json.JsonObject withQuality(Json.JsonObject analysis, Map<String, Object> extra) {
        for (Map.Entry<String, Object> e : extra.entrySet()) {
            analysis.put(e.getKey(), e.getValue());
        }
        return analysis;
    }

    public static Json.JsonObject replay(com.octant.pipeline.analysis.ContextReplay.Timeline t) {
        return replay(t, "存档派生事件", -1);
    }

    public static Json.JsonObject replay(com.octant.pipeline.analysis.ContextReplay.Timeline t,
                                         String source, int eventCount) {
        Json.JsonObject o = new Json.JsonObject();
        o.put("schemaVersion", "replay@1.0.0");
        o.put("sourceLabel", source);
        if (eventCount >= 0) {
            o.put("eventCount", eventCount);
        }
        o.put("sessionTotal", t.sessionTotal());
        o.put("sessionsWithSegments", t.sessionsWithSegments());
        o.put("segmentCount", t.segments().size());
        o.put("signalSource", t.signalSource());
        o.put("note", t.note());
        Json.JsonArray sessions = new Json.JsonArray();
        for (com.octant.pipeline.analysis.ContextReplay.SessionContext sc : t.sessions()) {
            Json.JsonObject s = new Json.JsonObject();
            s.put("sessionId", sc.sessionId());
            s.put("durationMs", sc.durationMs());
            s.put("eventCount", sc.eventCount());
            Json.JsonArray order = new Json.JsonArray();
            for (String lane : sc.laneOrder()) {
                order.add(lane);
            }
            s.put("laneOrder", order);
            Json.JsonObject lanes = new Json.JsonObject();
            for (String lane : sc.laneOrder()) {
                lanes.put(lane, sc.lanes().getOrDefault(lane, 0L));
            }
            s.put("lanesMs", lanes);
            sessions.add(s);
        }
        o.put("sessions", sessions);
        Json.JsonArray segs = new Json.JsonArray();
        for (com.octant.pipeline.analysis.ReplaySegments.Segment s : t.segments()) {
            Json.JsonObject g = new Json.JsonObject();
            g.put("sessionId", s.sessionId());
            g.put("startMs", s.startMs());
            g.put("endMs", s.endMs());
            g.put("lengthMs", s.lengthMs());
            g.put("kind", s.kind().label());
            g.put("marks", s.marks());
            Json.JsonArray why = new Json.JsonArray();
            for (String r : s.reasons()) {
                why.add(r);
            }
            g.put("reasons", why);
            Json.JsonArray ev = new Json.JsonArray();
            for (String r : s.evidence()) {
                ev.add(r);
            }
            g.put("evidenceRefs", ev);
            segs.add(g);
        }
        o.put("segments", segs);
        return o;
    }

    private static <T> Json.JsonArray array(List<T> items, java.util.function.Function<T, Json.JsonObject> f) {
        Json.JsonArray a = new Json.JsonArray();
        for (T item : items) {
            a.add(f.apply(item));
        }
        return a;
    }

    public static Json.JsonObject metric(MetricOutcome m) {
        Json.JsonObject o = new Json.JsonObject();
        o.put("metricId", m.metricId());
        o.put("featureId", m.featureId());
        o.put("featureExpr", m.featureExpr());
        o.put("outputType", m.outputType());
        o.put("unit", m.unit());
        o.put("status", m.status().wireName());
        if (m.hasValue()) {
            switch (m.outputType()) {
                case "quantiles" -> {
                    o.put("value", m.value());
                    if (m.quantiles() != null) {
                        o.put("quantiles", fromMapDouble(m.quantiles()));
                    }
                }
                case "series" -> {
                    if (m.series() != null) {
                        o.put("series", fromMapDouble(m.series()));
                    }
                }
                case "named" -> {
                    o.put("namedKey", m.namedKey());
                    o.put("value", m.value());
                }
                default -> o.put("value", m.value());
            }
        }
        o.put("sampleSize", fromMap(m.sampleSize().asMap()));
        o.put("confidence", m.confidence().wireName());
        o.put("confidenceDetail", fromMap(m.confidenceDetail().asMap()));
        o.put("window", fromMap(m.window().asMap()));
        if (m.reasonCode() != null) {
            o.put("reasonCode", m.reasonCode());
        }
        if (m.reasonText() != null) {
            o.put("reasonText", m.reasonText());
        }
        if (m.zeroCode() != null) {
            o.put("zeroCode", m.zeroCode());
        }
        if (m.numerator() != null) {
            o.put("numerator", m.numerator());
        }
        if (m.denominator() != null) {
            o.put("denominator", m.denominator());
        }
        if (!m.dataQualityFlags().isEmpty()) {
            o.put("dataQualityFlags", m.dataQualityFlags());
        }
        return o;
    }

    public static Json.JsonObject conclusion(Conclusion c) {
        return fromMap(c.asMap());
    }

    public static Json.JsonObject segment(Segment s) {
        return fromMap(s.asMap());
    }

    public static Json.JsonObject recommendation(Recommendation r) {
        return fromMap(r.asMap());
    }

    public static Json.JsonObject evidence(Evidence e) {
        return fromMap(e.asMap());
    }

    public static Json.JsonObject chart(Chart c) {
        return fromMap(c.asMap());
    }

    public static Json.JsonObject fromMap(Map<String, ?> map) {
        Json.JsonObject o = new Json.JsonObject();
        for (Map.Entry<String, ?> e : map.entrySet()) {
            o.put(e.getKey(), convert(e.getValue()));
        }
        return o;
    }

    private static Json.JsonObject fromMapDouble(Map<String, Double> map) {
        Json.JsonObject o = new Json.JsonObject();
        for (Map.Entry<String, Double> e : map.entrySet()) {
            o.put(e.getKey(), e.getValue());
        }
        return o;
    }

    private static Object convert(Object v) {
        if (v == null) {
            return Json.MISSING;
        }
        if (v instanceof Map<?, ?> m) {
            Json.JsonObject o = new Json.JsonObject();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                o.put(String.valueOf(e.getKey()), convert(e.getValue()));
            }
            return o;
        }
        if (v instanceof List<?> l) {
            Json.JsonArray a = new Json.JsonArray();
            for (Object item : l) {
                a.add(convert(item));
            }
            return a;
        }
        return v;
    }

    public static Json.JsonObject metricsProjection(AnalysisReport r) {
        Json.JsonObject o = new Json.JsonObject();
        o.put("schemaVersion", r.schemaVersion());
        o.put("metricVersion", r.metricVersion());
        o.put("generatedAtDateBucket", r.dataQuality().get("generatedAtDateBucket"));
        o.put("metrics", array(r.metrics(), AnalysisJson::metric));
        o.put("suppressionSummary", fromMap(r.suppressionSummary()));
        return o;
    }

    public static Json.JsonObject conclusionsProjection(AnalysisReport r) {
        Json.JsonObject o = new Json.JsonObject();
        o.put("schemaVersion", r.schemaVersion());
        o.put("generatedAtDateBucket", r.dataQuality().get("generatedAtDateBucket"));
        o.put("conclusions", array(r.conclusions(), AnalysisJson::conclusion));
        o.put("evidence", array(r.evidence(), AnalysisJson::evidence));
        o.put("segments", array(r.segments(), AnalysisJson::segment));
        o.put("recommendations", array(r.recommendations(), AnalysisJson::recommendation));
        o.put("charts", array(r.charts(), AnalysisJson::chart));
        return o;
    }

    public static Map<String, Object> headlineSample(AnalysisReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playerCount", (long) 1);
        m.put("sessionCount", (long) r.window().sessionIds().size());
        m.put("eventCount", r.dataQuality().get("eventCount"));
        m.put("confidenceLabel", ConfidenceLevel.ESTIMATED.localizedLabel());
        return m;
    }
}
