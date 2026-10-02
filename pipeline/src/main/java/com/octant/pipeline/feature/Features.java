package com.octant.pipeline.feature;

import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.ContentCatalog.UnitRef;
import com.octant.pipeline.raw.EventStream;
import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;
import com.octant.pipeline.raw.Session;
import com.octant.pipeline.raw.UnitKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

public final class Features {

    public record Context(EventStream stream, ContentCatalog catalog, String source) {
    }

    public record Supports(String metricId, String featureId, String expr,
                           SampleBasis basis, long minRequired, String unit) {
    }

    private final Context ctx;
    private final EventStream stream;
    private final ContentCatalog catalog;

    public Features(Context ctx) {
        this.ctx = ctx;
        this.stream = ctx.stream();
        this.catalog = ctx.catalog();
    }

    public Context context() {
        return ctx;
    }

    public EventStream stream() {
        return stream;
    }

    public ContentCatalog catalog() {
        return catalog;
    }

    public int eventCount() {
        return stream.events().size();
    }

    public int sessionCount() {
        return stream.sessions().size();
    }

    public int effectiveSessionCount() {
        return stream.effectiveSessions().size();
    }

    public long activeSeconds() {
        return stream.totalActiveSeconds();
    }

    public double activeHours() {
        return activeSeconds() / 3600.0d;
    }

    public int relativeDayCount() {
        return stream.relativeDayCount();
    }

    public int playerCount() {
        return 1;
    }

    public int reachableUnitCount() {
        return catalog.reachableUnits().size();
    }

    public Optional<FeatureValue> m1a() {
        List<RawEvent> progress = progressEvents();
        if (progress.isEmpty()) {
            return Optional.empty();
        }
        int reachable = reachableOf(UnitKind.ADVANCEMENT, UnitKind.QUEST);
        if (reachable <= 0) {
            return Optional.empty();
        }
        int achieved = Combinators.distinctCount(progress,
                e -> e.type() == EventType.ADVANCEMENT_GAINED ? e.str("advancementId") : e.str("questId"));
        return Optional.of(FeatureValue.of(M1A.metricId, M1A.featureId,
                "M1A_PROGRESS_COMPLETION = ratio(distinctCount(EV_PROGRESS, unitId), |reachable(advancement,quest)|)",
                percent(achieved, reachable)));
    }

    public Optional<FeatureValue> m1b() {
        List<RawEvent> progress = confirmed();
        if (progress.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Integer> attempts = new TreeMap<>();
        for (RawEvent e : progress) {
            String key = unitKeyOf(e);
            if (key != null) {
                attempts.merge(key, 1, Integer::sum);
            }
        }
        if (attempts.isEmpty() || reachableUnitCount() <= 0) {
            return Optional.empty();
        }
        double sum = 0.0d;
        for (UnitRef ref : catalog.reachableUnits()) {
            int n = attempts.getOrDefault(ref.unitKey(), 0);
            OptionalDouble cover = Combinators.logNormalize(n, Thresholds.M1B_T_LO, Thresholds.M1B_T_HI);
            sum += cover.orElse(0.0d);
        }
        return Optional.of(FeatureValue.of(M1B.metricId, M1B.featureId,
                "M1B_CONTENT_CONVERSION = mean over units of logNormalize(unitAttempts, "
                        + (int) Thresholds.M1B_T_LO + ", " + (int) Thresholds.M1B_T_HI + ")",
                percent(sum, reachableUnitCount())));
    }

    public Optional<FeatureValue> m1c() {
        int reachable = reachableUnitCount();
        if (reachable <= 0) {
            return Optional.empty();
        }
        int stalled = stalledUnits().size();
        return Optional.of(FeatureValue.of(M1C.metricId, M1C.featureId,
                "M1C_STALL_RATE = ratio(|stalledUnits(EV_STALL_SEGMENT, EV_REPEATED_FAILURE)|, |reachableUnits|)",
                percent(stalled, reachable)));
    }

    public Optional<FeatureValue> m1Tail() {
        List<RawEvent> progress = confirmed();
        if (progress.isEmpty() || reachableUnitCount() <= 0) {
            return Optional.empty();
        }
        Map<String, Integer> attempts = new TreeMap<>();
        for (RawEvent e : progress) {
            String key = unitKeyOf(e);
            if (key != null) {
                attempts.merge(key, 1, Integer::sum);
            }
        }
        int tail = 0;
        for (UnitRef ref : catalog.reachableUnits()) {
            int n = attempts.getOrDefault(ref.unitKey(), 0);
            OptionalDouble cover = Combinators.logNormalize(n, Thresholds.M1B_T_LO, Thresholds.M1B_T_HI);
            if (cover.isPresent() && cover.getAsDouble() <= 1.0d) {
                tail++;
            }
        }
        return Optional.of(FeatureValue.of(M1TAIL.metricId, M1TAIL.featureId,
                "M1TAIL_ITEM_SPECTRUM = count(units where logNormalize(attempts) <= 1) / |reachableUnits|",
                percent(tail, reachableUnitCount())));
    }

    public Optional<FeatureValue> m2a() {
        if (stream.count(EventType.STALL_SEGMENT) == 0 && stream.count(EventType.REPEATED_FAILURE) == 0) {
            return Optional.empty();
        }
        List<String> units = stalledUnits();
        return Optional.of(FeatureValue.of(M2A.metricId, M2A.featureId,
                "M2A_STALL_UNITS = count(distinct unitId where EV_STALL_SEGMENT.dwellMs > 0"
                        + " or EV_REPEATED_FAILURE.countInWindow >= " + Thresholds.MIN_UNIT_ATTEMPTS + ")",
                (long) units.size()));
    }

    public Optional<FeatureValue> m2b() {
        int attempted = attemptedUnits().size();
        if (attempted <= 0) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M2B.metricId, M2B.featureId,
                "M2B_STALL_SHARE = ratio(|stalledUnits|, |attemptedUnits|)",
                percent(stalledUnits().size(), attempted)));
    }

    public Optional<FeatureValue> m2c() {
        Map<String, Double> dwell = new TreeMap<>();
        for (RawEvent e : stream.ofType(EventType.STALL_SEGMENT)) {
            if (e.num("activeDensityPm") < Thresholds.STALL_MIN_ACTIVE_DENSITY_PM) {
                continue;
            }
            dwell.merge(e.str("unitId"), (double) e.durMs(), Double::sum);
        }
        if (dwell.isEmpty()) {
            return Optional.empty();
        }
        for (RawEvent e : stream.ofType(EventType.REPEATED_FAILURE)) {
            if (e.intVal("countInWindow") >= Thresholds.MIN_UNIT_ATTEMPTS) {
                dwell.merge(e.str("unitId"), e.intVal("countInWindow") * 60_000.0d, Double::sum);
            }
        }
        Combinators.Named top = Combinators.argMax(dwell);
        if (top == null) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M2C.metricId, M2C.featureId,
                "M2C_HEAVIEST_STALL = argMax(Σ dwellMs by unitId where activeDensityPm >= "
                        + Thresholds.STALL_MIN_ACTIVE_DENSITY_PM + ")",
                new NamedResult(top.key(), top.value())));
    }

    public Optional<FeatureValue> m2d() {
        List<RawEvent> itemActions = stream.ofType(EventType.ITEM_ACTION);
        if (itemActions.isEmpty()) {
            return Optional.empty();
        }
        int blocked = 0;
        for (RawEvent e : stream.ofType(EventType.RESOURCE_BLOCKED)) {
            if ("resolved".equals(e.str("resolution"))) {
                blocked++;
            }
        }
        return Optional.of(FeatureValue.of(M2D.metricId, M2D.featureId,
                "M2D_RESOURCE_BLOCK_RATE = ratio(count(EV_RESOURCE_BLOCKED where resolution=resolved),"
                        + " count(EV_ITEM_ACTION))",
                percent(blocked, itemActions.size())));
    }

    public Optional<FeatureValue> m3a() {
        return Optional.of(FeatureValue.of(M3A.metricId, M3A.featureId,
                "M3A_ACTIVE_TOTAL = Σ session.activeMs / 3600000",
                round3(activeHours())));
    }

    public Optional<FeatureValue> m3b() {
        List<Double> minutes = effectiveSessionMinutes();
        if (minutes.isEmpty()) {
            return Optional.empty();
        }
        OptionalDouble p50 = Combinators.p(50, minutes);
        OptionalDouble p90 = Combinators.p(90, minutes);
        if (p50.isEmpty() || p90.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> quantiles = new TreeMap<>();
        quantiles.put("p50", round3(p50.getAsDouble()));
        quantiles.put("p90", round3(p90.getAsDouble()));
        return Optional.of(FeatureValue.of(M3B.metricId, M3B.featureId,
                "M3B_SESSION_MEDIAN = quantiles(p50,p90 of session.activeMs/60000, quantileMethod=linear)",
                quantiles));
    }

    public Optional<FeatureValue> m3c() {
        List<Session> sessions = new ArrayList<>(stream.sessions());
        sessions.sort(Comparator.comparingLong(Session::startTRelMs));
        if (sessions.size() < 2) {
            return Optional.empty();
        }
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < sessions.size(); i++) {
            long gapMs = sessions.get(i).startTRelMs() - sessions.get(i - 1).endTRelMs();
            gaps.add(Math.max(0L, gapMs) / 3_600_000.0d);
        }
        OptionalDouble p50 = Combinators.p(50, gaps);
        if (p50.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> quantiles = new TreeMap<>();
        quantiles.put("p50", round3(p50.getAsDouble()));
        return Optional.of(FeatureValue.of(M3C.metricId, M3C.featureId,
                "M3C_SESSION_CADENCE = quantiles(p50 of Δ session start, hour)",
                quantiles));
    }

    public Optional<FeatureValue> m3d() {
        int days = relativeDayCount();
        OptionalDouble r = Combinators.rate(sessionCount(), days);
        if (r.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M3D.metricId, M3D.featureId,
                "M3D_SESSION_FREQUENCY = rate(count(sessions), relative_day_count)",
                round3(r.getAsDouble())));
    }

    public Optional<FeatureValue> m3e() {
        List<Double> minutes = new ArrayList<>();
        for (Session s : stream.sessions()) {
            if (s.effective()) {
                minutes.add(s.activeMinutes());
            }
        }
        if (minutes.size() < Thresholds.MIN_SESSIONS_FOR_FATIGUE) {
            return Optional.empty();
        }
        int k = 3;
        List<Double> recent = minutes.subList(minutes.size() - k, minutes.size());
        List<Double> baseline = minutes.subList(0, minutes.size() - k);
        OptionalDouble base = Combinators.median(baseline);
        OptionalDouble rec = Combinators.median(recent);
        if (base.isEmpty() || rec.isEmpty() || base.getAsDouble() <= 0.0d) {
            return Optional.empty();
        }
        double idx = Combinators.clamp(rec.getAsDouble() / base.getAsDouble(), 0.0d, 1.0d);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("baselineMedianMin", round3(base.getAsDouble()));
        extras.put("recentMedianMin", round3(rec.getAsDouble()));
        extras.put("k", (long) k);
        return Optional.of(FeatureValue.of(M3E.metricId, M3E.featureId,
                "M3E_FATIGUE_INDEX = clamp(median(last " + k + " sessions) / median(baseline sessions), 0, 1)",
                SeriesResult.metadata(round3(idx), extras)));
    }

    public Optional<FeatureValue> m4() {
        List<UnitKind> axes = catalog.kinds();
        if (axes.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> axisValues = new TreeMap<>();
        Map<String, Integer> touched = touchedUnitsByKind();
        for (UnitKind kind : axes) {
            axisValues.put(kind.wireName(), (double) Math.min(
                    touched.getOrDefault(kind.wireName(), 0), catalog.visibleCount(kind)));
        }
        return Optional.of(FeatureValue.of(M4.metricId, M4.featureId,
                "M4_BREADTH = ratio(count(axes where touchedUnits >= 1), |reachableAxes|)",
                new SeriesResult(round3(nullSafeRatio(axisValues)), axisValues)));
    }

    public Optional<FeatureValue> m5a() {
        List<Double> weights = axisWeights();
        OptionalDouble h = Combinators.entropyNorm(weights);
        if (h.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M5A.metricId, M5A.featureId,
                "M5A_AXIS_ENTROPY = entropyNorm(Σ interactMs by axis)",
                round3(h.getAsDouble())));
    }

    public Optional<FeatureValue> m5b() {
        List<Double> weights = axisWeights();
        OptionalDouble g = Combinators.gini(weights);
        if (g.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M5B.metricId, M5B.featureId,
                "M5B_AXIS_GINI = gini(Σ interactMs by axis)",
                round3(g.getAsDouble())));
    }

    public Optional<FeatureValue> m5c() {
        Map<String, Double> w = axisWeightsByKind();
        if (w.isEmpty()) {
            return Optional.empty();
        }
        Combinators.Named top = Combinators.argMax(w);
        double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
        if (top == null || total <= 0.0d) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M5C.metricId, M5C.featureId,
                "M5C_DOMINANT_AXIS = argMax(Σ interactMs by axis)",
                new NamedResult(top.key(), top.value() / total)));
    }

    public Optional<FeatureValue> m5d() {
        Map<String, Double> w = axisWeightsByKind();
        if (w.isEmpty()) {
            return Optional.empty();
        }
        Combinators.Named low = Combinators.argMin(w);
        if (low == null) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M5D.metricId, M5D.featureId,
                "M5D_WEAKEST_AXIS = argMin(Σ interactMs by axis)",
                new NamedResult(low.key(), low.value())));
    }

    public Optional<FeatureValue> m6a() {
        Map<String, Double> seconds = categorySeconds();
        if (seconds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M6A.metricId, M6A.featureId,
                "M6A_CATEGORY_TIME_VECTOR = Σ durMs/1000 by category",
                new SeriesResult(null, seconds)));
    }

    public Optional<FeatureValue> m6b() {
        Map<String, Double> seconds = categorySeconds();
        OptionalDouble h = Combinators.entropyNorm(new ArrayList<>(seconds.values()));
        if (h.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M6B.metricId, M6B.featureId,
                "M6B_CATEGORY_ENTROPY = entropyNorm(Σ durMs by category)",
                round3(h.getAsDouble())));
    }

    public Optional<FeatureValue> m6c() {
        Map<String, Double> seconds = categorySeconds();
        if (seconds.isEmpty()) {
            return Optional.empty();
        }
        Combinators.Named top = Combinators.argMax(seconds);
        double total = seconds.values().stream().mapToDouble(Double::doubleValue).sum();
        if (top == null || total <= 0.0d) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M6C.metricId, M6C.featureId,
                "M6C_DOMINANT_CATEGORY = argMax(Σ durMs by category)",
                new NamedResult(top.key(), top.value() / total)));
    }

    public Optional<FeatureValue> m6d() {
        Map<String, Double> seconds = new TreeMap<>(categorySeconds());
        if (seconds.size() < 2) {
            return Optional.empty();
        }
        Combinators.Named top = Combinators.argMax(seconds);
        seconds.remove(top.key());
        Combinators.Named second = Combinators.argMax(seconds);
        double total = categorySeconds().values().stream().mapToDouble(Double::doubleValue).sum();
        if (second == null || total <= 0.0d) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M6D.metricId, M6D.featureId,
                "M6D_SECONDARY_CATEGORY = argMax(Σ durMs by category, excluding dominant)",
                new NamedResult(second.key(), second.value() / total)));
    }

    public Optional<FeatureValue> m6e() {
        double hours = activeHours();
        if (hours <= 0.0d) {
            return Optional.empty();
        }
        int outputs = 0;
        for (RawEvent e : stream.ofType(EventType.ITEM_ACTION)) {
            if ("craft_output".equals(e.str("action")) && !e.bool("creativeGiven")) {
                outputs += e.intVal("count");
            }
        }
        return Optional.of(FeatureValue.of(M6E.metricId, M6E.featureId,
                "M6E_CATEGORY_YIELD = rate(Σ item_action.count where action=craft_output, activeHours)",
                round3(outputs / hours)));
    }

    public Optional<FeatureValue> m7a() {
        int attempts = attemptedUnits().size();
        if (attempts <= 0) {
            return Optional.empty();
        }
        int repeats = repeatedSegmentCount();
        if (repeats == 0 && stream.count(EventType.REPEATED_FAILURE) == 0) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M7A.metricId, M7A.featureId,
                "M7A_REPETITION_SHARE = ratio(repeatedSegments(60s signature), |attemptedUnits|)",
                round3(Math.min(1.0d, (double) repeats / attempts))));
    }

    public Optional<FeatureValue> m7b() {
        List<RawEvent> stalls = stream.ofType(EventType.STALL_SEGMENT);
        if (stalls.isEmpty()) {
            return Optional.empty();
        }
        Set<String> achieved = new LinkedHashSet<>();
        for (RawEvent e : stream.ofType(EventType.ADVANCEMENT_GAINED)) {
            achieved.add(e.str("advancementId"));
        }
        for (RawEvent e : stream.ofType(EventType.QUEST_COMPLETED)) {
            achieved.add(e.str("questId"));
        }
        long productive = stalls.stream().filter(e -> achieved.contains(e.str("unitId"))).count();
        return Optional.of(FeatureValue.of(M7B.metricId, M7B.featureId,
                "M7B_UNPRODUCTIVE_REPEAT_RATE = ratio(1 - productiveSegments, allStallSegments)",
                round3((double) (stalls.size() - productive) / stalls.size())));
    }

    public Optional<FeatureValue> m7c() {
        List<RawEvent> observed = stream.ofType(EventType.MACHINE_OBSERVED);
        if (observed.isEmpty() && stream.ofType(EventType.AUTOMATION_CYCLE).isEmpty()) {
            return Optional.empty();
        }
        int qualified = 0;
        int unknownDevice = 0;
        for (RawEvent e : observed) {
            if ("unknown_device".equals(e.str("deviceClass"))) {
                unknownDevice++;
                continue;
            }
            boolean enoughGrowth = e.intVal("outputDelta10m") >= Thresholds.AUTOMATION_NET_GROWTH_PER_10MIN;
            boolean reused = e.has("reusedSessions")
                    && e.intVal("reusedSessions") >= Thresholds.MIN_DEVICE_REUSED_SESSIONS;
            if (enoughGrowth && reused) {
                qualified++;
            }
        }
        int manual = 0;
        for (RawEvent e : stream.ofType(EventType.ITEM_ACTION)) {
            if ("craft_output".equals(e.str("action")) && e.durMs() > 0) {
                manual++;
            }
        }
        if (qualified + manual <= 0) {
            return Optional.empty();
        }
        double share = (double) qualified / (qualified + manual);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("qualifiedDevices", (long) qualified);
        extras.put("unknownDevice", (long) unknownDevice);
        extras.put("unknownDeviceShare", round3(observed.isEmpty() ? 0.0d
                : (double) unknownDevice / observed.size()));
        return Optional.of(FeatureValue.of(M7C.metricId, M7C.featureId,
                "M7C_AUTOMATION_SUBSTITUTION = ratio(qualifiedDevices, qualifiedDevices + manualCrafts)",
                SeriesResult.metadata(round3(share), extras)));
    }

    public Optional<FeatureValue> m7d() {
        Set<String> attempted = attemptedUnitIds();
        if (attempted.isEmpty()) {
            return Optional.empty();
        }
        Set<String> done = new LinkedHashSet<>();
        for (RawEvent e : stream.ofType(EventType.ADVANCEMENT_GAINED)) {
            if (!e.bool("isRecipe")) {
                done.add(e.str("advancementId"));
            }
        }
        for (RawEvent e : stream.ofType(EventType.QUEST_COMPLETED)) {
            done.add(e.str("questId"));
        }
        long debt = attempted.stream().filter(u -> !done.contains(u)).count();
        return Optional.of(FeatureValue.of(M7D.metricId, M7D.featureId,
                "M7E_COMPLEXITY_DEBT = count(attemptedUnits not in completedUnits)",
                debt));
    }

    public Optional<FeatureValue> m7e() {
        Optional<FeatureValue> debt = m7d();
        int attempted = attemptedUnitIds().size();
        if (debt.isEmpty() || attempted <= 0) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M7E.metricId, M7E.featureId,
                "M7E_COMPLEXITY_DEBT_RATIO = ratio(complexityDebt, |attemptedUnits|)",
                round3(debt.get().asDouble() / attempted)));
    }

    public Optional<FeatureValue> m8a() {
        if (stream.count(EventType.COMBAT_STARTED) == 0) {
            return Optional.empty();
        }
        long totalActive = stream.totalActiveMs();
        if (totalActive <= 0L) {
            return Optional.empty();
        }
        long combatMs = 0L;
        for (Combinators.Interval iv : combatIntervals()) {
            long dur = iv.end().durMs();
            if (dur <= 0L) {
                dur = iv.start().durMs();
            }
            if (dur <= 0L) {
                dur = iv.end().tRelMs() - iv.start().tRelMs();
            }
            combatMs += Math.max(0L, dur);
        }
        return Optional.of(FeatureValue.of(M8A.metricId, M8A.featureId,
                "M8A_COMBAT_TIME_SHARE = ratio(Σ combat.durMs, activeMs)"
                        + "（区间长度取事件自带的 durMs；它缺失时才回落到 tRelMs 差）",
                round3(Math.min(1.0d, (double) combatMs / totalActive))));
    }

    public Optional<FeatureValue> m8b() {
        List<Combinators.Interval> ivs = combatIntervals();
        if (ivs.isEmpty()) {
            return Optional.empty();
        }
        long kills = ivs.stream()
                .filter(i -> "kill".equals(i.end().str("outcome")))
                .filter(i -> i.end().bool("lastHitByPlayer"))
                .count();
        return Optional.of(FeatureValue.of(M8B.metricId, M8B.featureId,
                "M8B_NON_LETHAL_RATE = ratio(count(combat_ended where outcome=kill and lastHitByPlayer),"
                        + " count(combat pairings))",
                round3((double) kills / ivs.size())));
    }

    public Optional<FeatureValue> m8c() {
        List<Combinators.Interval> ivs = farmFilteredIntervals();
        if (ivs.isEmpty()) {
            return Optional.empty();
        }
        long overtier = ivs.stream()
                .filter(i -> i.end().num("damageTaken") > OVERTIER_DAMAGE_TAKEN)
                .count();
        return Optional.of(FeatureValue.of(M8C.metricId, M8C.featureId,
                "M8C_OVERTIER_SHARE = ratio(count(encounters where damageTaken > "
                        + (int) OVERTIER_DAMAGE_TAKEN + "), validEncounters)",
                round3((double) overtier / ivs.size())));
    }

    public Optional<FeatureValue> m8d() {
        List<Double> ratios = new ArrayList<>();
        for (Combinators.Interval iv : farmFilteredIntervals()) {
            if ("resolved".equals(iv.end().str("resolved")) && !iv.start().bool("shared")) {
                double ratio = iv.start().num("opponentThreat");
                if (ratio >= 0.0d) {
                    ratios.add(ratio);
                }
            }
        }
        if (ratios.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> quantiles = new TreeMap<>();
        for (int k : new int[]{10, 50, 90}) {
            OptionalDouble v = Combinators.p(k, ratios);
            if (v.isPresent()) {
                quantiles.put("p" + k, round3(v.getAsDouble()));
            }
        }
        return Optional.of(FeatureValue.of(M8D.metricId, M8D.featureId,
                "M8D_POWER_RATIO_DIST = quantiles(p10,p50,p90 of opponentThreat where resolved)",
                quantiles));
    }

    public Optional<FeatureValue> m8e() {
        List<Combinators.Interval> ivs = farmFilteredIntervals();
        if (ivs.isEmpty()) {
            return Optional.empty();
        }
        double dealt = ivs.stream().mapToDouble(i -> i.end().num("damageDealt")).sum();
        double taken = ivs.stream().mapToDouble(i -> i.end().num("damageTaken")).sum();
        OptionalDouble r = Combinators.ratio(dealt, taken);
        if (r.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M8E.metricId, M8E.featureId,
                "M8E_DAMAGE_EXCHANGE_RATIO = ratio(Σ damageDealt, Σ damageTaken)",
                round3(r.getAsDouble())));
    }

    public Optional<FeatureValue> m8f() {
        Map<String, Double> tactics = new TreeMap<>();
        for (Combinators.Interval iv : farmFilteredIntervals()) {
            String tactic = iv.end().str("tactic");
            if (!tactic.isEmpty()) {
                tactics.merge(tactic, 1.0d, Double::sum);
            }
        }
        OptionalDouble h = Combinators.entropyNorm(new ArrayList<>(tactics.values()));
        if (h.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FeatureValue.of(M8F.metricId, M8F.featureId,
                "M8F_TACTIC_DIVERSITY = entropyNorm(count by tactic)",
                round3(h.getAsDouble())));
    }

    public Optional<FeatureValue> m8g() {
        List<RawEvent> deaths = new ArrayList<>();
        for (RawEvent e : stream.ofType(EventType.PLAYER_DEATH)) {
            if (!e.bool("intentional")) {
                deaths.add(e);
            }
        }
        if (deaths.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> byCause = new TreeMap<>();
        for (RawEvent e : deaths) {
            byCause.merge(e.str("causeClass"), 1.0d, Double::sum);
        }
        Map<String, Double> share = new TreeMap<>();
        for (Map.Entry<String, Double> e : byCause.entrySet()) {
            share.put(e.getKey(), round3(e.getValue() / deaths.size() * 100.0d));
        }
        return Optional.of(FeatureValue.of(M8G.metricId, M8G.featureId,
                "M8G_DEATH_CAUSE_DIST = share by causeClass where not intentional",
                new SeriesResult(null, share)));
    }

    public Optional<FeatureValue> m9Dimension(String dimension) {
        return switch (dimension) {
            case "D1_PACE" -> pace();
            case "D2_DEPTH" -> m1a().map(v -> rescale("D2_DEPTH", "M9_D2_DEPTH", v));
            case "D3_BREADTH" -> m4().map(v -> rescale("D3_BREADTH", "M9_D3_BREADTH", v));
            case "D4_DISPERSION" -> m5a().map(v -> rescale("D4_DISPERSION", "M9_D4_DISPERSION", v));
            case "D5_CRAFT" -> craft();
            case "D6_COMBAT" -> combat();
            case "D7_PERSIST" -> m3e().map(v -> rescale("D7_PERSIST", "M9_D7_PERSIST", v));
            default -> Optional.empty();
        };
    }

    private Optional<FeatureValue> pace() {
        OptionalDouble r = Combinators.rate(m1aRawShare(), activeHours());
        if (r.isEmpty()) {
            return Optional.empty();
        }
        OptionalDouble n = Combinators.normalize(r.getAsDouble(), 0.0d, PACE_MAX_PER_HOUR);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("ratePerHour", round3(r.getAsDouble()));
        extras.put("unit", "share/h");
        return Optional.of(FeatureValue.of("D1_PACE", "M9_D1_PACE",
                "M9_D1_PACE = normalize(progressShare / activeHours, 0, " + (int) PACE_MAX_PER_HOUR + ")",
                SeriesResult.metadata(round3(Combinators.clamp(n.getAsDouble(), 0.0d, 1.0d)), extras)));
    }

    private Optional<FeatureValue> craft() {
        OptionalDouble r = Combinators.rate(craftOutputCount(), activeHours());
        if (r.isEmpty()) {
            return Optional.empty();
        }
        OptionalDouble n = Combinators.normalize(r.getAsDouble(), 0.0d, CRAFT_MAX_PER_HOUR);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("ratePerHour", round3(r.getAsDouble()));
        extras.put("unit", "items/h");
        return Optional.of(FeatureValue.of("D5_CRAFT", "M9_D5_CRAFT",
                "M9_D5_CRAFT = normalize(craftOutputCount / activeHours, 0, " + (int) CRAFT_MAX_PER_HOUR + ")",
                SeriesResult.metadata(round3(Combinators.clamp(n.getAsDouble(), 0.0d, 1.0d)), extras)));
    }

    private Optional<FeatureValue> combat() {
        List<Combinators.Interval> ivs = farmFilteredIntervals();
        if (ivs.isEmpty()) {
            return Optional.empty();
        }
        double survival = 1.0d - ((double) countDeaths() / ivs.size());
        OptionalDouble n = Combinators.normalize(Combinators.clamp(survival, 0.0d, 1.0d), 0.0d, 1.0d);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("survivalShare", round3(Combinators.clamp(survival, 0.0d, 1.0d)));
        extras.put("encounters", (long) ivs.size());
        return Optional.of(FeatureValue.of("D6_COMBAT", "M9_D6_COMBAT",
                "M9_D6_COMBAT = clamp(1 - deaths / validEncounters, 0, 1)",
                SeriesResult.metadata(round3(Combinators.clamp(n.getAsDouble(), 0.0d, 1.0d)), extras)));
    }

    private FeatureValue rescale(String metricId, String featureId, FeatureValue source) {
        double v = scalarOf(java.util.Optional.of(source));
        return FeatureValue.of(metricId, featureId, featureId + " = " + source.expr(),
                round3(Combinators.clamp(v / 100.0d, 0.0d, 1.0d)));
    }

    public double m1aRawShare() {
        int reachable = reachableOf(UnitKind.ADVANCEMENT, UnitKind.QUEST);
        if (reachable <= 0) {
            return 0.0d;
        }
        List<RawEvent> progress = progressEvents();
        int achieved = Combinators.distinctCount(progress,
                e -> e.type() == EventType.ADVANCEMENT_GAINED ? e.str("advancementId") : e.str("questId"));
        return (double) achieved / reachable;
    }

    public int craftOutputCount() {
        int n = 0;
        for (RawEvent e : stream.ofType(EventType.ITEM_ACTION)) {
            if ("craft_output".equals(e.str("action")) && !e.bool("creativeGiven")) {
                n += e.intVal("count");
            }
        }
        return n;
    }

    public int countDeaths() {
        int n = 0;
        for (RawEvent e : stream.ofType(EventType.PLAYER_DEATH)) {
            if (!e.bool("intentional")) {
                n++;
            }
        }
        return n;
    }

    public List<Combinators.Interval> combatIntervals() {
        return Combinators.pairs(stream.events(), EventType.COMBAT_STARTED, EventType.COMBAT_ENDED,
                e -> e.str("opponentKey")).intervals();
    }

    public List<Combinators.Interval> farmFilteredIntervals() {
        List<Combinators.Interval> out = new ArrayList<>();
        for (Combinators.Interval iv : combatIntervals()) {
            if (iv.start().bool("farmPattern")) {
                continue;
            }
            if ("unresolved".equals(iv.end().str("resolved"))) {
                continue;
            }
            out.add(iv);
        }
        return out;
    }

    public int farmCombatCount() {
        int n = 0;
        for (Combinators.Interval iv : combatIntervals()) {
            if (iv.start().bool("farmPattern")) {
                n++;
            }
        }
        return n;
    }

    public int unresolvedCombatCount() {
        int n = 0;
        for (Combinators.Interval iv : combatIntervals()) {
            if ("unresolved".equals(iv.end().str("resolved"))) {
                n++;
            }
        }
        return n;
    }

    public Set<String> attemptedUnitIds() {
        Set<String> reachable = new LinkedHashSet<>();
        for (UnitRef ref : catalog.reachableUnits()) {
            reachable.add(ref.id());
        }
        Set<String> out = new LinkedHashSet<>();
        for (RawEvent e : confirmed()) {
            String id = unitIdOf(e);
            if (id != null && (reachable.isEmpty() || reachable.contains(id))) {
                out.add(id);
            }
        }
        return out;
    }

    public Set<String> attemptedUnits() {
        return attemptedUnitIds();
    }

    public List<String> stalledUnits() {
        Set<String> out = new LinkedHashSet<>();
        for (RawEvent e : stream.ofType(EventType.STALL_SEGMENT)) {
            if (e.num("activeDensityPm") >= Thresholds.STALL_MIN_ACTIVE_DENSITY_PM) {
                out.add(e.str("unitId"));
            }
        }
        for (RawEvent e : stream.ofType(EventType.REPEATED_FAILURE)) {
            if (e.intVal("countInWindow") >= Thresholds.MIN_UNIT_ATTEMPTS) {
                out.add(e.str("unitId"));
            }
        }
        List<String> sorted = new ArrayList<>(out);
        Collections.sort(sorted);
        return sorted;
    }

    public int repeatedSegmentCount() {
        Map<String, Integer> bySignature = new TreeMap<>();
        for (RawEvent e : stream.ofType(EventType.REPEATED_FAILURE)) {
            bySignature.merge(e.str("unitId") + "|" + e.str("failKind"), 1, Integer::sum);
        }
        for (RawEvent e : stream.ofType(EventType.STALL_SEGMENT)) {
            bySignature.merge(e.str("unitId") + "|stall", 1, Integer::sum);
        }
        int segments = 0;
        for (int n : bySignature.values()) {
            if (n >= (int) Thresholds.MIN_REPEAT_SEGMENTS) {
                segments += n;
            }
        }
        return segments;
    }

    public List<Double> axisWeights() {
        return new ArrayList<>(axisWeightsByKind().values());
    }

    public Map<String, Double> axisWeightsByKind() {
        Map<String, Double> out = new TreeMap<>();
        for (ContentCatalog.UnitRef ref : catalog.reachableUnits()) {
            out.putIfAbsent(ref.kind().wireName(), 0.0d);
        }
        for (RawEvent e : confirmed()) {
            UnitKind kind = kindOf(e);
            if (kind == null) {
                continue;
            }
            double ms = e.durMs() > 0 ? e.durMs() : defaultInteractMs(e.type());
            out.merge(kind.wireName(), ms, Double::sum);
        }
        return out;
    }

    public Map<String, Double> categorySeconds() {
        Map<String, Double> out = new TreeMap<>();
        for (RawEvent e : confirmed()) {
            String cat = categoryOf(e);
            if (cat == null) {
                continue;
            }
            double ms = e.durMs() > 0 ? e.durMs() : defaultInteractMs(e.type());
            out.merge(cat, ms / 1000.0d, Double::sum);
        }
        return out;
    }

    public List<RawEvent> confirmed() {
        List<RawEvent> out = new ArrayList<>();
        for (RawEvent e : stream.events()) {
            if (e.confirmed()) {
                out.add(e);
            }
        }
        return out;
    }

    public List<RawEvent> progressEvents() {
        List<RawEvent> out = new ArrayList<>();
        for (RawEvent e : stream.events()) {
            if (e.type() == EventType.ADVANCEMENT_GAINED || e.type() == EventType.QUEST_COMPLETED) {
                out.add(e);
            }
        }
        return out;
    }

    public Map<String, Integer> touchedUnitsByKind() {
        Map<String, Integer> out = new TreeMap<>();
        for (RawEvent e : confirmed()) {
            UnitKind kind = kindOf(e);
            if (kind == null) {
                continue;
            }
            String id = unitIdOf(e);
            if (id == null) {
                continue;
            }
            out.merge(kind.wireName(), 1, Integer::sum);
        }
        return out;
    }

    public int reachableOf(UnitKind... kinds) {
        int n = 0;
        for (UnitKind k : kinds) {
            n += catalog.visibleCount(k);
        }
        return n;
    }

    public static UnitKind kindOf(RawEvent e) {
        return switch (e.type()) {
            case ADVANCEMENT_GAINED -> UnitKind.ADVANCEMENT;
            case QUEST_COMPLETED, QUEST_PROGRESSED -> UnitKind.QUEST;
            case RECIPE_UNLOCKED, RECIPE_ATTEMPTED -> UnitKind.RECIPE;
            case DIMENSION_ENTERED -> UnitKind.DIMENSION;
            case BIOME_VISITED -> UnitKind.BIOME;
            case STRUCTURE_ENTERED, REGION_FIRST_VISIT -> UnitKind.STRUCTURE;
            case MACHINE_OBSERVED, MACHINE_INTERACTED, AUTOMATION_CYCLE -> UnitKind.MACHINE;
            case ITEM_ACTION, CONTAINER_SNAPSHOT -> UnitKind.ITEM;
            default -> null;
        };
    }

    public static String unitIdOf(RawEvent e) {
        return switch (e.type()) {
            case ADVANCEMENT_GAINED -> e.str("advancementId");
            case QUEST_COMPLETED, QUEST_PROGRESSED -> e.str("questId");
            case RECIPE_UNLOCKED, RECIPE_ATTEMPTED -> e.str("recipeId");
            case DIMENSION_ENTERED -> e.str("dimension");
            case BIOME_VISITED -> e.str("biome");
            case STRUCTURE_ENTERED -> e.str("structure");
            case REGION_FIRST_VISIT -> e.str("regionKey");
            case MACHINE_OBSERVED, MACHINE_INTERACTED, AUTOMATION_CYCLE -> e.str("machineBlock");
            case ITEM_ACTION -> e.str("item");
            case CONTAINER_SNAPSHOT -> e.str("containerKey");
            case STALL_SEGMENT, REPEATED_FAILURE, RESOURCE_BLOCKED -> e.str("unitId");
            default -> null;
        };
    }

    public static String unitKeyOf(RawEvent e) {
        UnitKind kind = kindOf(e);
        String id = unitIdOf(e);
        if (kind == null || id == null || id.isEmpty()) {
            return null;
        }
        return kind.wireName() + ":" + id;
    }

    public static String categoryOf(RawEvent e) {
        return switch (e.type()) {
            case ADVANCEMENT_GAINED, QUEST_COMPLETED, QUEST_PROGRESSED -> "progress";
            case DIMENSION_ENTERED, BIOME_VISITED, STRUCTURE_ENTERED, REGION_FIRST_VISIT -> "exploration";
            case COMBAT_STARTED, COMBAT_ENDED, PLAYER_DEATH -> "combat";
            case ITEM_ACTION, RECIPE_UNLOCKED, RECIPE_ATTEMPTED, CONTAINER_SNAPSHOT -> "production";
            case MACHINE_OBSERVED, MACHINE_INTERACTED, AUTOMATION_CYCLE -> "automation";
            case STALL_SEGMENT, REPEATED_FAILURE, RESOURCE_BLOCKED -> "stall";
            case SESSION_START, SESSION_END, SESSION_HEARTBEAT, SESSION_ENVIRONMENT -> null;
        };
    }

    private static double defaultInteractMs(EventType type) {
        return switch (type) {
            case ITEM_ACTION -> 2_000.0d;
            case RECIPE_ATTEMPTED, RECIPE_UNLOCKED -> 5_000.0d;
            case ADVANCEMENT_GAINED, QUEST_COMPLETED, QUEST_PROGRESSED -> 10_000.0d;
            case DIMENSION_ENTERED, BIOME_VISITED, STRUCTURE_ENTERED, REGION_FIRST_VISIT -> 5_000.0d;
            case COMBAT_STARTED, COMBAT_ENDED -> 15_000.0d;
            case PLAYER_DEATH -> 5_000.0d;
            case MACHINE_INTERACTED, MACHINE_OBSERVED, AUTOMATION_CYCLE -> 10_000.0d;
            case CONTAINER_SNAPSHOT -> 3_000.0d;
            case RESOURCE_BLOCKED, REPEATED_FAILURE -> 5_000.0d;
            case STALL_SEGMENT -> 30_000.0d;
            case SESSION_ENVIRONMENT -> 0.0d;
            default -> 1_000.0d;
        };
    }

    private List<Double> effectiveSessionMinutes() {
        List<Double> out = new ArrayList<>();
        for (Session s : stream.effectiveSessions()) {
            out.add(s.activeMinutes());
        }
        return out;
    }

    private static double nullSafeRatio(Map<String, Double> axisValues) {
        if (axisValues.isEmpty()) {
            return 0.0d;
        }
        long touched = axisValues.values().stream().filter(v -> v > 0.0d).count();
        return (double) touched / axisValues.size();
    }

    private static double percent(double numerator, double denominator) {
        return round3(numerator / denominator * 100.0d);
    }

    static double round3(double v) {
        return Math.round(v * 1000.0d) / 1000.0d;
    }

    public record NamedResult(String key, double value) {
    }

    public record SeriesResult(Double scalar, Map<String, ?> extras, boolean extrasAreSeries) {
        public SeriesResult(Double scalar, Map<String, ?> extras) {
            this(scalar, extras, true);
        }

        public static SeriesResult metadata(Double scalar, Map<String, ?> extras) {
            return new SeriesResult(scalar, extras, false);
        }
    }

    public static final Supports M1A = s("M1a", "M1A_PROGRESS_COMPLETION", SampleBasis.UNITS, 3L, "%");
    public static final Supports M1B = s("M1b", "M1B_CONTENT_CONVERSION", SampleBasis.UNITS, 3L, "%");
    public static final Supports M1C = s("M1c", "M1C_STALL_RATE", SampleBasis.UNITS, 3L, "%");
    public static final Supports M1TAIL = s("M1-tail", "M1TAIL_ITEM_SPECTRUM", SampleBasis.UNITS, 3L, "%");
    public static final Supports M2A = s("M2a", "M2A_STALL_UNITS", SampleBasis.UNITS, 3L, "个");
    public static final Supports M2B = s("M2b", "M2B_STALL_SHARE", SampleBasis.UNITS, 3L, "%");
    public static final Supports M2C = s("M2c", "M2C_HEAVIEST_STALL", SampleBasis.UNITS, 3L, "-");
    public static final Supports M2D = s("M2d", "M2D_RESOURCE_BLOCK_RATE", SampleBasis.UNITS, 3L, "%");
    public static final Supports M3A = s("M3a", "M3A_ACTIVE_TOTAL", SampleBasis.ACTIVE_TIME, 3600L, "h");
    public static final Supports M3B = s("M3b", "M3B_SESSION_MEDIAN", SampleBasis.SESSIONS, 5L, "min");
    public static final Supports M3C = s("M3c", "M3C_SESSION_CADENCE", SampleBasis.SESSIONS, 5L, "h");
    public static final Supports M3D = s("M3d", "M3D_SESSION_FREQUENCY", SampleBasis.SESSIONS, 5L, "次/日");
    public static final Supports M3E = s("M3e", "M3E_FATIGUE_INDEX", SampleBasis.SESSIONS, 10L, "-");
    public static final Supports M4 = s("M4", "M4_BREADTH", SampleBasis.UNITS, 3L, "-");
    public static final Supports M5A = s("M5a", "M5A_AXIS_ENTROPY", SampleBasis.UNITS, 3L, "-");
    public static final Supports M5B = s("M5b", "M5B_AXIS_GINI", SampleBasis.UNITS, 3L, "-");
    public static final Supports M5C = s("M5c", "M5C_DOMINANT_AXIS", SampleBasis.UNITS, 3L, "-");
    public static final Supports M5D = s("M5d", "M5D_WEAKEST_AXIS", SampleBasis.UNITS, 3L, "-");
    public static final Supports M6A = s("M6a", "M6A_CATEGORY_TIME_VECTOR", SampleBasis.UNITS, 3L, "s");
    public static final Supports M6B = s("M6b", "M6B_CATEGORY_ENTROPY", SampleBasis.UNITS, 3L, "-");
    public static final Supports M6C = s("M6c", "M6C_DOMINANT_CATEGORY", SampleBasis.UNITS, 3L, "-");
    public static final Supports M6D = s("M6d", "M6D_SECONDARY_CATEGORY", SampleBasis.UNITS, 3L, "-");
    public static final Supports M6E = s("M6e", "M6E_CATEGORY_YIELD", SampleBasis.ACTIVE_TIME, 3600L, "条/h");
    public static final Supports M7A = s("M7a", "M7A_REPETITION_SHARE", SampleBasis.EVENTS, 5L, "-");
    public static final Supports M7B = s("M7b", "M7B_UNPRODUCTIVE_REPEAT_RATE", SampleBasis.SESSIONS, 10L, "-");
    public static final Supports M7C = s("M7c", "M7C_AUTOMATION_SUBSTITUTION", SampleBasis.UNITS, 3L, "-");
    public static final Supports M7D = s("M7d", "M7D_COMPLEXITY_DEBT", SampleBasis.UNITS, 3L, "个");
    public static final Supports M7E = s("M7e", "M7E_COMPLEXITY_DEBT_RATIO", SampleBasis.UNITS, 3L, "-");
    public static final Supports M8A = s("M8a", "M8A_COMBAT_TIME_SHARE", SampleBasis.EVENTS, 5L, "-");
    public static final Supports M8B = s("M8b", "M8B_NON_LETHAL_RATE", SampleBasis.EVENTS, 5L, "-");
    public static final Supports M8C = s("M8c", "M8C_OVERTIER_SHARE", SampleBasis.EVENTS, 20L, "-");
    public static final Supports M8D = s("M8d", "M8D_POWER_RATIO_DIST", SampleBasis.EVENTS, 20L, "倍率");
    public static final Supports M8E = s("M8e", "M8E_DAMAGE_EXCHANGE_RATIO", SampleBasis.EVENTS, 20L, "-");
    public static final Supports M8F = s("M8f", "M8F_TACTIC_DIVERSITY", SampleBasis.EVENTS, 20L, "-");
    public static final Supports M8G = s("M8g", "M8G_DEATH_CAUSE_DIST", SampleBasis.EVENTS, 3L, "%");

    public static List<Supports> registry() {
        List<Supports> out = new ArrayList<>();
        out.add(M1A);
        out.add(M1B);
        out.add(M1C);
        out.add(M1TAIL);
        out.add(M2A);
        out.add(M2B);
        out.add(M2C);
        out.add(M2D);
        out.add(M3A);
        out.add(M3B);
        out.add(M3C);
        out.add(M3D);
        out.add(M3E);
        out.add(M4);
        out.add(M5A);
        out.add(M5B);
        out.add(M5C);
        out.add(M5D);
        out.add(M6A);
        out.add(M6B);
        out.add(M6C);
        out.add(M6D);
        out.add(M6E);
        out.add(M7A);
        out.add(M7B);
        out.add(M7C);
        out.add(M7D);
        out.add(M7E);
        out.add(M8A);
        out.add(M8B);
        out.add(M8C);
        out.add(M8D);
        out.add(M8E);
        out.add(M8F);
        out.add(M8G);
        out.add(s("D1_PACE", "M9_D1_PACE", SampleBasis.UNITS, 3L, "-"));
        out.add(s("D2_DEPTH", "M9_D2_DEPTH", SampleBasis.UNITS, 3L, "-"));
        out.add(s("D3_BREADTH", "M9_D3_BREADTH", SampleBasis.UNITS, 3L, "-"));
        out.add(s("D4_DISPERSION", "M9_D4_DISPERSION", SampleBasis.UNITS, 3L, "-"));
        out.add(s("D5_CRAFT", "M9_D5_CRAFT", SampleBasis.ACTIVE_TIME, 3600L, "-"));
        out.add(s("D6_COMBAT", "M9_D6_COMBAT", SampleBasis.EVENTS, 5L, "-"));
        out.add(s("D7_PERSIST", "M9_D7_PERSIST", SampleBasis.SESSIONS, 10L, "-"));
        return out;
    }

    private static Supports s(String metricId, String featureId, SampleBasis basis, long k, String unit) {
        return new Supports(metricId, featureId, featureId + "()", basis, k, unit);
    }

    private static final double OVERTIER_DAMAGE_TAKEN = 20.0d;
    private static final double PACE_MAX_PER_HOUR = 0.05d;
    private static final double CRAFT_MAX_PER_HOUR = 60.0d;

    public java.util.List<FeatureEval> buildEvals() {
        java.util.List<FeatureEval> out = new java.util.ArrayList<>();
        int reachableItems = catalog.visibleCount(UnitKind.ITEM);
        int reachableUnits = reachableUnitCount();
        int attempted = attemptedUnits().size();
        List<Combinators.Interval> combat = combatIntervals();
        List<Combinators.Interval> validCombat = farmFilteredIntervals();
        int deaths = countDeaths();

        out.add(ev(M1A, m1a(), m1aRawShare() * 100.0d, reachableUnits, "reachable")
                .withUnitCount(reachableUnits));
        out.add(ev(M1B, m1b(), scalarOf(m1b()), reachableUnits, "reachable")
                .withUnitCount(reachableUnits)
                .withHeuristic(reachableUnits > 0 && catalog.allApproximate()));
        out.add(ev(M1C, m1c(), stalledUnits().size(), reachableUnits, "reachable")
                .withUnitCount(reachableUnits));
        out.add(ev(M1TAIL, m1Tail(), tailCount(), reachableUnits, "reachable")
                .withUnitCount(reachableUnits));
        out.add(ev(M2A, m2a(), stalledUnits().size(), attempted, "attempted")
                .withUnitCount(attempted));
        out.add(ev(M2B, m2b(), stalledUnits().size(), attempted, "attempted")
                .withUnitCount(attempted));
        out.add(ev(M2C, m2c(), heaviestStallMs(), stalledUnits().size(), "attempted")
                .withUnitCount(attempted));
        out.add(ev(M2D, m2d(), resolvedBlockedCount(), stream.count(EventType.ITEM_ACTION),
                "observed").withUnitCount(resolvedBlockedCount()));
        out.add(ev(M3A, m3a(), activeSeconds(), activeSeconds(), "observed_active")
                .withUnitCount(sessionCount()));
        out.add(ev(M3B, m3b(), medianSessionMinutes(), effectiveSessionCount(), "sessions")
                .withUnitCount(effectiveSessionCount()).withQuantiles(m3b().map(Features::quantilesOf).orElse(null)));
        out.add(ev(M3C, m3c(), medianCadenceHours(), sessionCount(), "sessions")
                .withUnitCount(sessionCount()).withQuantiles(m3c().map(Features::quantilesOf).orElse(null)));
        out.add(ev(M3D, m3d(), sessionCount(), relativeDayCount(), "relative_days")
                .withUnitCount(sessionCount()));
        out.add(ev(M3E, m3e(), scalarOf(m3e()), sessionCount(),
                "sessions").withUnitCount(sessionCount()));
        out.add(ev(M4, m4(), touchedAxisCount(), axisWeightsByKind().size(), "reachable")
                .withUnitCount(catalog.kinds().size())
                .withHeuristic(catalog.allApproximate()));
        out.add(ev(M5A, m5a(), axisWeights().size(), axisWeights().size(), "units")
                .withUnitCount(axisWeights().size()));
        out.add(ev(M5B, m5b(), axisWeights().size(), axisWeights().size(), "units")
                .withUnitCount(axisWeights().size()));
        out.add(ev(M5C, m5c(), dominantAxisShare(), axisWeights().size(), "units")
                .withUnitCount(axisWeights().size()));
        out.add(ev(M5D, m5d(), axisWeights().size(), axisWeights().size(), "units")
                .withUnitCount(axisWeights().size()));
        out.add(ev(M6A, m6a(), categorySeconds().size(), categorySeconds().size(), "categories")
                .withUnitCount(categorySeconds().size()));
        out.add(ev(M6B, m6b(), categorySeconds().size(), categorySeconds().size(), "categories")
                .withUnitCount(categorySeconds().size()));
        out.add(ev(M6C, m6c(), dominantCategoryShare(), categorySeconds().size(), "categories")
                .withUnitCount(categorySeconds().size()));
        out.add(ev(M6D, m6d(), secondaryCategoryShare(), categorySeconds().size(), "categories")
                .withUnitCount(categorySeconds().size()));
        out.add(ev(M6E, m6e(), craftOutputCount(), activeSeconds(), "observed_active")
                .withUnitCount(categorySeconds().size()));
        out.add(ev(M7A, m7a(), repeatedSegmentCount(), attempted, "attempted")
                .withUnitCount(attempted));
        out.add(ev(M7B, m7b(), stream.count(EventType.STALL_SEGMENT), stream.count(EventType.STALL_SEGMENT),
                "observed").withUnitCount(effectiveSessionCount()));
        out.add(ev(M7C, m7c(), scalarOf(m7c()), qualifiedDeviceCount(),
                "observed_devices").withUnitCount(qualifiedDeviceCount()));
        out.add(ev(M7D, m7d(), scalarOf(m7d()), attempted, "attempted")
                .withUnitCount(attempted));
        out.add(ev(M7E, m7e(), scalarOf(m7d()), attempted, "attempted")
                .withUnitCount(attempted));
        out.add(ev(M8A, m8a(), combatIntervalMs(), stream.totalActiveMs(), "observed_active")
                .withUnitCount(combat.size()));
        out.add(ev(M8B, m8b(), killCount(), combat.size(), "encounters").withUnitCount(combat.size()));
        out.add(ev(M8C, m8c(), overtierCount(), validCombat.size(), "valid_encounters")
                .withUnitCount(validCombat.size()));
        out.add(ev(M8D, m8d(), validCombat.size(), validCombat.size(), "valid_encounters")
                .withUnitCount(validCombat.size())
                .withQuantiles(m8d().map(Features::quantilesOf).orElse(null)));
        out.add(ev(M8E, m8e(), damageDealtTotal(), damageTakenTotal(), "encounters")
                .withUnitCount(validCombat.size()));
        out.add(ev(M8F, m8f(), tacticCount(), validCombat.size(), "valid_encounters")
                .withUnitCount(tacticCount()));
        out.add(ev(M8G, m8g(), deaths, deaths, "deaths").withUnitCount(deaths));
        for (String dim : java.util.List.of("D1_PACE", "D2_DEPTH", "D3_BREADTH", "D4_DISPERSION",
                "D5_CRAFT", "D6_COMBAT", "D7_PERSIST")) {
            Supports sup = registry().stream().filter(s -> s.metricId().equals(dim)).findFirst().orElseThrow();
            Optional<FeatureValue> v = m9Dimension(dim);
            FeatureEval base = ev(sup, v, scalarOf(v),
                    reachableUnits, "reachable").withUnitCount(reachableUnits);
            out.add(base.withHeuristic(!dim.equals("D2_DEPTH") && !dim.equals("D3_BREADTH")));
        }
        return out;
    }

    static double scalarOf(java.util.Optional<FeatureValue> v) {
        if (v.isEmpty()) {
            return 0.0d;
        }
        Object raw = v.get().value();
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof NamedResult nr) {
            return nr.value();
        }
        if (raw instanceof SeriesResult sr && sr.scalar() != null) {
            return sr.scalar();
        }
        return 0.0d;
    }

    private static java.util.Map<String, Double> quantilesOf(FeatureValue v) {
        if (v.value() instanceof java.util.Map<?, ?> m) {
            java.util.Map<String, Double> out = new java.util.TreeMap<>();
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    out.put(String.valueOf(e.getKey()), n.doubleValue());
                }
            }
            return out;
        }
        return null;
    }

    private FeatureEval ev(Supports sup, Optional<FeatureValue> v,
                                                            double numerator, double denominator,
                                                            String source) {
        String expr = v.map(FeatureValue::expr).orElse(sup.featureId() + "()");
        if (v.isEmpty()) {
            return new FeatureEval(sup.metricId(), sup.featureId(), expr,
                    null, null, null, null, null, numerator, denominator, source, null, false,
                    java.util.List.of(), null, null);
        }
        Object raw = v.get().value();
        Double scalar = null;
        java.util.Map<String, Double> series = null;
        String namedKey = null;
        java.util.Map<String, Double> metadata = null;
        if (raw instanceof Number n) {
            scalar = n.doubleValue();
        } else if (raw instanceof NamedResult nr) {
            scalar = nr.value();
            namedKey = nr.key();
        } else if (raw instanceof SeriesResult sr) {
            scalar = sr.scalar();
            if (sr.extrasAreSeries()) {
                series = numericMap(sr.extras());
            } else {
                metadata = numericMap(sr.extras());
            }
        } else if (raw instanceof java.util.Map<?, ?> m) {
            series = numericMap(m);
        }
        String zeroCode = null;
        String zeroReason = null;
        if (scalar != null && scalar == 0.0d && zeroCandidate(sup.metricId()) != null) {
            zeroCode = zeroCandidate(sup.metricId());
            zeroReason = "ZERO_INPUT_PRESENT_NO_OBSERVATION";
        }
        return new FeatureEval(sup.metricId(), sup.featureId(), expr,
                scalar, null, series, metadata, namedKey, numerator, denominator, source, null,
                false, java.util.List.of(), zeroCode, zeroReason);
    }

    private static String zeroCandidate(String metricId) {
        return switch (metricId) {
            case "M2a", "M2b", "M2c" -> com.octant.pipeline.analysis.ReasonCodes.ZERO_NO_STALL_SEGMENT;
            case "M8a", "M8b", "M8g" -> com.octant.pipeline.analysis.ReasonCodes.ZERO_NO_COMBAT;
            case "M7c" -> com.octant.pipeline.analysis.ReasonCodes.ZERO_NO_AUTOMATION_DEVICE;
            case "M7a", "M7b" -> com.octant.pipeline.analysis.ReasonCodes.ZERO_NO_REPEAT_SEGMENT;
            default -> null;
        };
    }

    private static java.util.Map<String, Double> numericMap(java.util.Map<?, ?> m) {
        java.util.Map<String, Double> out = new java.util.TreeMap<>();
        for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getValue() instanceof Number n) {
                out.put(String.valueOf(e.getKey()), round3(n.doubleValue()));
            }
        }
        return out;
    }

    private int tailCount() {
        return catalog.reachableUnits().size() - accomplishedUnitCount();
    }

    private int accomplishedUnitCount() {
        java.util.Set<String> done = new LinkedHashSet<>();
        for (RawEvent e : stream.ofType(EventType.ADVANCEMENT_GAINED)) {
            done.add(e.str("advancementId"));
        }
        for (RawEvent e : stream.ofType(EventType.QUEST_COMPLETED)) {
            done.add(e.str("questId"));
        }
        return done.size();
    }

    private double heaviestStallMs() {
        double max = 0.0d;
        for (RawEvent e : stream.ofType(EventType.STALL_SEGMENT)) {
            max = Math.max(max, e.durMs());
        }
        return max;
    }

    private int resolvedBlockedCount() {
        int n = 0;
        for (RawEvent e : stream.ofType(EventType.RESOURCE_BLOCKED)) {
            if ("resolved".equals(e.str("resolution"))) {
                n++;
            }
        }
        return n;
    }

    private double medianSessionMinutes() {
        OptionalDouble p50 = Combinators.p(50, effectiveSessionMinutes());
        return p50.orElse(0.0d);
    }

    private double medianCadenceHours() {
        java.util.List<Session> sessions = new java.util.ArrayList<>(stream.sessions());
        sessions.sort(Comparator.comparingLong(Session::startTRelMs));
        if (sessions.size() < 2) {
            return 0.0d;
        }
        java.util.List<Double> gaps = new java.util.ArrayList<>();
        for (int i = 1; i < sessions.size(); i++) {
            gaps.add(Math.max(0L, sessions.get(i).startTRelMs() - sessions.get(i - 1).endTRelMs())
                    / 3_600_000.0d);
        }
        OptionalDouble p50 = Combinators.p(50, gaps);
        return p50.orElse(0.0d);
    }

    private int touchedAxisCount() {
        java.util.Map<String, Integer> touched = touchedUnitsByKind();
        int n = 0;
        for (UnitKind kind : catalog.kinds()) {
            if (touched.getOrDefault(kind.wireName(), 0) > 0) {
                n++;
            }
        }
        return n;
    }

    private double dominantAxisShare() {
        java.util.Map<String, Double> w = axisWeightsByKind();
        double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
        Combinators.Named top = Combinators.argMax(w);
        return (top == null || total <= 0.0d) ? 0.0d : top.value() / total;
    }

    private double dominantCategoryShare() {
        java.util.Map<String, Double> w = categorySeconds();
        double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
        Combinators.Named top = Combinators.argMax(w);
        return (top == null || total <= 0.0d) ? 0.0d : top.value() / total;
    }

    private double secondaryCategoryShare() {
        java.util.Map<String, Double> w = new TreeMap<>(categorySeconds());
        if (w.size() < 2) {
            return 0.0d;
        }
        double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
        Combinators.Named top = Combinators.argMax(w);
        w.remove(top.key());
        Combinators.Named second = Combinators.argMax(w);
        return (second == null || total <= 0.0d) ? 0.0d : second.value() / total;
    }

    private int qualifiedDeviceCount() {
        int n = 0;
        for (RawEvent e : stream.ofType(EventType.MACHINE_OBSERVED)) {
            if ("unknown_device".equals(e.str("deviceClass"))) {
                continue;
            }
            if (e.intVal("outputDelta10m") >= Thresholds.AUTOMATION_NET_GROWTH_PER_10MIN
                    && e.has("reusedSessions")
                    && e.intVal("reusedSessions") >= Thresholds.MIN_DEVICE_REUSED_SESSIONS) {
                n++;
            }
        }
        return n;
    }

    private long combatIntervalMs() {
        long ms = 0L;
        for (Combinators.Interval iv : combatIntervals()) {
            ms += Math.max(0L, iv.durationMs());
        }
        return ms;
    }

    private long killCount() {
        long n = 0L;
        for (Combinators.Interval iv : combatIntervals()) {
            if ("kill".equals(iv.end().str("outcome")) && iv.end().bool("lastHitByPlayer")) {
                n++;
            }
        }
        return n;
    }

    private long overtierCount() {
        long n = 0L;
        for (Combinators.Interval iv : farmFilteredIntervals()) {
            if (iv.end().num("damageTaken") > OVERTIER_DAMAGE_TAKEN) {
                n++;
            }
        }
        return n;
    }

    private double damageDealtTotal() {
        double s = 0.0d;
        for (Combinators.Interval iv : farmFilteredIntervals()) {
            s += iv.end().num("damageDealt");
        }
        return s;
    }

    private double damageTakenTotal() {
        double s = 0.0d;
        for (Combinators.Interval iv : farmFilteredIntervals()) {
            s += iv.end().num("damageTaken");
        }
        return s;
    }

    private int tacticCount() {
        java.util.Set<String> tactics = new LinkedHashSet<>();
        for (Combinators.Interval iv : farmFilteredIntervals()) {
            String t = iv.end().str("tactic");
            if (!t.isEmpty()) {
                tactics.add(t);
            }
        }
        return tactics.size();
    }

    public int segmentEvidenceItemCount(java.util.List<FeatureEval> evals) {
        int n = 0;
        for (FeatureEval e : evals) {
            if (isProfileDimension(e.metricId()) && e.value() != null) {
                n++;
            }
        }
        return n;
    }

    public static boolean isProfileDimension(String metricId) {
        return metricId.startsWith("D") && metricId.contains("_");
    }
}
