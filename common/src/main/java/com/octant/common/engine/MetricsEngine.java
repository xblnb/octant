package com.octant.common.engine;

import com.octant.common.ContentAxis;
import com.octant.common.MetricResult;
import com.octant.common.SuppressionReason;
import com.octant.common.metrics.DispersionMetrics;
import com.octant.common.metrics.PlaytimeMetrics;
import com.octant.common.model.ContentCatalog;
import com.octant.common.model.ContentUnit;
import com.octant.common.model.EventType;
import com.octant.common.model.RawEvent;
import com.octant.common.session.Session;
import com.octant.common.session.Sessionizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MetricsEngine {

    private final Sessionizer sessionizer;

    public MetricsEngine() {
        this(new Sessionizer());
    }

    public MetricsEngine(Sessionizer sessionizer) {
        this.sessionizer = sessionizer;
    }

    public AnalysisReport analyze(List<RawEvent> events) {
        return analyze(events, new ContentCatalog(), List.of());
    }

    public AnalysisReport analyze(List<RawEvent> events, ContentCatalog catalog, List<ContentUnit> units) {
        List<RawEvent> safeEvents = events == null ? List.of() : events;
        List<Session> sessions = sessionizer.sessionize(safeEvents);

        PlaytimeMetrics playtime = new PlaytimeMetrics(sessions);
        Map<String, MetricResult> all = new LinkedHashMap<>();
        all.putAll(playtime.compute());

        DispersionMetrics dispersion = new DispersionMetrics(units, catalog, playtime.totalActiveSeconds());
        all.putAll(dispersion.compute());

        return new AnalysisReport(
                sessions.size(),
                playtime.totalActiveSeconds(),
                playtime.totalWallSeconds(),
                all,
                collectSuppressed(all),
                collectWarnings(sessions, units, catalog));
    }

    private static List<String> collectSuppressed(Map<String, MetricResult> metrics) {
        List<String> out = new ArrayList<>();
        for (MetricResult r : metrics.values()) {
            if (r.isSuppressed()) {
                out.add(r.id() + ": " + r.reasonDetail().orElseGet(() ->
                        r.reason().map(SuppressionReason::name).orElse("UNKNOWN")));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> collectWarnings(List<Session> sessions, List<ContentUnit> units,
                                                ContentCatalog catalog) {
        List<String> warnings = new ArrayList<>();
        long open = sessions.stream().filter(Session::openSession).count();
        if (open > 0) {
            warnings.add("OPEN_SESSION: " + open + " 个会话无 LOGOUT 闭合，已按最后事件时间闭合并标注");
        }
        if (sessions.stream().anyMatch(s -> s.afkSuspect() && s.activeSeconds() > 0)) {
            warnings.add("AFK_SUSPECT: 存在疑似挂机会话，时长类结论已按 §3.4 降级或附墙钟对照");
        }
        if (units != null && !units.isEmpty()) {
            for (ContentAxis axis : ContentAxis.values()) {
                if (catalog.visibleCount(axis) == 0) {
                    continue;
                }
                if (catalog.isApproximate(axis)) {
                    warnings.add("DENOMINATOR_APPROX: " + axis.label()
                            + " 轴分母为长尾近似而非作者维护清单，广度可能被系统性低估");
                }
            }
        }
        if (catalog.keysOf(ContentAxis.ITEM).isEmpty()
                && units != null && !units.isEmpty()) {
            warnings.add("CATALOG_MISSING: 未登记可达内容清单，M4 广度与 M5 离散度分母不可用");
        }
        return List.copyOf(warnings);
    }

    public static boolean hasLogout(List<RawEvent> events) {
        return events != null && events.stream().anyMatch(e -> e.type() == EventType.LOGOUT);
    }
}
