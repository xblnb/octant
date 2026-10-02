package com.octant.common.metrics;

import com.octant.common.Confidence;
import com.octant.common.MetricResult;
import com.octant.common.SuppressionPolicy;
import com.octant.common.SuppressionReason;
import com.octant.common.session.Session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlaytimeMetrics {

    private final List<Session> sessions;
    private final long totalActiveSeconds;
    private final long totalWallSeconds;
    private final int afkSuspectCount;
    private final double afkSuspectRatio;
    private final Confidence baseConfidence;

    public PlaytimeMetrics(List<Session> sessions) {
        this.sessions = List.copyOf(sessions);
        long active = 0;
        long wall = 0;
        int afk = 0;
        for (Session s : this.sessions) {
            active += s.activeSeconds();
            wall += s.wallSeconds();
            if (s.afkSuspect()) {
                afk++;
            }
        }
        this.totalActiveSeconds = active;
        this.totalWallSeconds = wall;
        this.afkSuspectCount = afk;
        this.afkSuspectRatio = this.sessions.isEmpty() ? 0.0d : (double) afk / this.sessions.size();
        this.baseConfidence = this.afkSuspectRatio > SuppressionPolicy.AFK_SUSPECT_RATIO
                ? Confidence.C : Confidence.B;
    }

    public long totalActiveSeconds() {
        return totalActiveSeconds;
    }

    public long totalWallSeconds() {
        return totalWallSeconds;
    }

    public Map<String, MetricResult> compute() {
        Map<String, MetricResult> out = new LinkedHashMap<>();
        out.put("M3a", m3a());
        out.put("M3b_p50", m3b(0.5d));
        out.put("M3b_p90", m3b(0.9d));
        out.put("M3c_p50", m3c());
        out.put("M3d", m3d());
        out.put("M3e", m3e());
        return com.octant.common.privacy.OrderedCollections.copyOf(out);
    }

    public MetricResult m3a() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("totalWallHours", round(totalWallSeconds / 3600.0d));
        extras.put("sessionCount", sessions.size());
        extras.put("afkSuspectSessions", afkSuspectCount);
        return MetricResult.of("M3a", round(totalActiveSeconds / 3600.0d),
                sessions.isEmpty() ? Confidence.A : baseConfidence, extras);
    }

    private MetricResult m3b(double p) {
        String id = p == 0.5d ? "M3b_p50" : "M3b_p90";
        MetricResult gate = sessionLevelGate(id);
        if (gate != null) {
            return gate;
        }
        double v = Stats.percentile(Stats.activeMinutes(sessions), p);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("unit", "min");
        if (p == 0.9d && v > 180.0d) {
            extras.put("reviewRequired", true);
            extras.put("reviewReason", "P90_EXCEEDS_180MIN_AFK_RECHECK");
        }
        return MetricResult.of(id, round(v), baseConfidence, extras);
    }

    private MetricResult m3c() {
        MetricResult gate = sessionLevelGate("M3c_p50");
        if (gate != null) {
            return gate;
        }
        List<Double> gaps = Stats.offlineGapsHours(sessions);
        if (gaps.isEmpty()) {
            return MetricResult.suppressed("M3c_p50", SuppressionReason.INSUFFICIENT_SESSION_COUNT,
                    SuppressionReason.INSUFFICIENT_SESSION_COUNT.describeCount(0, 1));
        }
        return MetricResult.of("M3c_p50", round(Stats.median(gaps)), Confidence.B);
    }

    private MetricResult m3d() {
        MetricResult gate = sessionLevelGate("M3d");
        if (gate != null) {
            return gate;
        }
        List<Long> effectiveDays = new ArrayList<>();
        int effectiveSessions = 0;
        for (Session s : sessions) {
            if (s.isEffective()) {
                effectiveSessions++;
                long day = Math.floorDiv(s.startSecond(), 86_400L);
                if (!effectiveDays.contains(day)) {
                    effectiveDays.add(day);
                }
            }
        }
        if (effectiveDays.isEmpty()) {
            return MetricResult.suppressed("M3d", SuppressionReason.INSUFFICIENT_SESSION_COUNT,
                    "无有效会话日（全部会话均疑似挂机或零活跃）");
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("effectiveSessions", effectiveSessions);
        extras.put("effectiveDays", effectiveDays.size());
        extras.put("unit", "次/日");
        return MetricResult.of("M3d", round((double) effectiveSessions / effectiveDays.size()),
                Confidence.B, extras);
    }

    public MetricResult m3e() {
        if (totalActiveSeconds < SuppressionPolicy.MIN_ACTIVE_S) {
            return MetricResult.suppressed("M3e", SuppressionReason.INSUFFICIENT_ACTIVE_TIME,
                    SuppressionReason.INSUFFICIENT_ACTIVE_TIME
                            .describe(totalActiveSeconds, SuppressionPolicy.MIN_ACTIVE_S));
        }
        int required = SuppressionPolicy.MIN_SESSION_N_FATIGUE;
        if (sessions.size() < required) {
            return MetricResult.suppressed("M3e",
                    SuppressionReason.INSUFFICIENT_SESSION_COUNT_FOR_FATIGUE,
                    SuppressionReason.INSUFFICIENT_SESSION_COUNT_FOR_FATIGUE
                            .describeCount(sessions.size(), required));
        }
        List<Double> all = Stats.activeMinutes(sessions);
        int n = all.size();
        List<Double> recent = new ArrayList<>(all.subList(n - 3, n));
        List<Double> earlier = new ArrayList<>(all.subList(0, n - 3));
        double recentMedian = Stats.median(recent);
        double baselineMedian = Stats.median(earlier);
        if (baselineMedian <= 0) {
            return MetricResult.suppressed("M3e", SuppressionReason.INSUFFICIENT_SESSION_COUNT,
                    "早前会话基线中位数为 0，无法计算衰减比");
        }
        double value = 1.0d - (recentMedian / baselineMedian);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("recentMedianMin", round(recentMedian));
        extras.put("baselineMedianMin", round(baselineMedian));
        extras.put("warn", value > 0.5d);
        return MetricResult.of("M3e", round(value), Confidence.B, extras);
    }

    private MetricResult sessionLevelGate(String id) {
        if (totalActiveSeconds < SuppressionPolicy.MIN_ACTIVE_S) {
            return MetricResult.suppressed(id, SuppressionReason.INSUFFICIENT_ACTIVE_TIME,
                    SuppressionReason.INSUFFICIENT_ACTIVE_TIME
                            .describe(totalActiveSeconds, SuppressionPolicy.MIN_ACTIVE_S));
        }
        if (sessions.size() < SuppressionPolicy.MIN_SESSION_N) {
            return MetricResult.suppressed(id, SuppressionReason.INSUFFICIENT_SESSION_COUNT,
                    SuppressionReason.INSUFFICIENT_SESSION_COUNT
                            .describeCount(sessions.size(), SuppressionPolicy.MIN_SESSION_N));
        }
        return null;
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0d) / 10_000.0d;
    }
}
