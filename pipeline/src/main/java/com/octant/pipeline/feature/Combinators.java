package com.octant.pipeline.feature;

import com.octant.pipeline.raw.RawEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

public final class Combinators {

    private Combinators() {
    }

    public static int count(List<RawEvent> events) {
        return events.size();
    }

    public static int count(List<RawEvent> events, EventFilter filter) {
        int n = 0;
        for (RawEvent e : events) {
            if (filter.test(e)) {
                n++;
            }
        }
        return n;
    }

    public static int distinctCount(List<RawEvent> events, Function<RawEvent, String> keyFn) {
        Set<String> seen = new LinkedHashSet<>();
        for (RawEvent e : events) {
            String k = keyFn.apply(e);
            if (k != null && !k.isEmpty()) {
                seen.add(k);
            }
        }
        return seen.size();
    }

    public static List<String> distinctKeys(List<RawEvent> events, Function<RawEvent, String> keyFn) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (RawEvent e : events) {
            String k = keyFn.apply(e);
            if (k != null && !k.isEmpty()) {
                seen.add(k);
            }
        }
        List<String> out = new ArrayList<>(seen);
        Collections.sort(out);
        return out;
    }

    public static double sum(List<RawEvent> events, ToDoubleFunction<RawEvent> f) {
        double s = 0.0d;
        for (RawEvent e : events) {
            s += f.applyAsDouble(e);
        }
        return s;
    }

    public static OptionalDouble mean(List<RawEvent> events, ToDoubleFunction<RawEvent> f) {
        if (events.isEmpty()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(sum(events, f) / events.size());
    }

    public static OptionalDouble mean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return OptionalDouble.empty();
        }
        double s = 0.0d;
        for (double v : values) {
            s += v;
        }
        return OptionalDouble.of(s / values.size());
    }

    public static OptionalDouble median(List<Double> values) {
        return p(50, values);
    }

    public static OptionalDouble p(int k, List<Double> values) {
        if (values == null || values.isEmpty()) {
            return OptionalDouble.empty();
        }
        if (k < 0 || k > 100) {
            throw new IllegalArgumentException("分位数 k 必须 ∈ [0,100]，实际 " + k);
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return OptionalDouble.of(percentileSorted(k, sorted));
    }

    public static double percentileSorted(int k, List<Double> sorted) {
        int n = sorted.size();
        if (n == 1) {
            return sorted.get(0);
        }
        double rank = (k / 100.0d) * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) {
            return sorted.get(lo);
        }
        double frac = rank - lo;
        return sorted.get(lo) * (1.0d - frac) + sorted.get(hi) * frac;
    }

    public static OptionalDouble rate(double count, double duration) {
        if (duration <= 0.0d) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(count / duration);
    }

    public static OptionalDouble ratio(double a, double b) {
        if (b == 0.0d) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(a / b);
    }

    public static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    public static OptionalDouble normalize(double x, double lo, double hi) {
        if (hi == lo) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((x - lo) / (hi - lo));
    }

    public static OptionalDouble logNormalize(double x, double tLo, double tHi) {
        double lo = Math.log(1.0d + tLo);
        double hi = Math.log(1.0d + tHi);
        if (hi == lo) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(clamp((Math.log(1.0d + x) - lo) / (hi - lo), 0.0d, 1.0d));
    }

    public static OptionalDouble entropyNorm(List<Double> vec) {
        double total = 0.0d;
        for (double v : vec) {
            if (v < 0.0d) {
                throw new IllegalArgumentException("熵的输入不得为负：" + v);
            }
            total += v;
        }
        int k = 0;
        for (double v : vec) {
            if (v > 0.0d) {
                k++;
            }
        }
        if (k <= 0 || total <= 0.0d) {
            return OptionalDouble.empty();
        }
        if (k == 1) {
            return OptionalDouble.of(0.0d);
        }
        double h = 0.0d;
        for (double v : vec) {
            if (v <= 0.0d) {
                continue;
            }
            double p = v / total;
            h -= p * Math.log(p);
        }
        return OptionalDouble.of(h / Math.log(k));
    }

    public static OptionalDouble gini(List<Double> vec) {
        if (vec == null || vec.isEmpty()) {
            return OptionalDouble.empty();
        }
        List<Double> sorted = new ArrayList<>(vec);
        Collections.sort(sorted);
        int n = sorted.size();
        double total = 0.0d;
        for (double v : sorted) {
            total += v;
        }
        if (total <= 0.0d) {
            return OptionalDouble.empty();
        }
        double weighted = 0.0d;
        for (int i = 0; i < n; i++) {
            weighted += (i + 1) * sorted.get(i);
        }
        double g = (2.0d * weighted) / (n * total) - (n + 1.0d) / n;
        return OptionalDouble.of(clamp(g, 0.0d, 1.0d));
    }

    public static Named argMax(Map<String, Double> vec) {
        return arg(vec, true);
    }

    public static Named argMin(Map<String, Double> vec) {
        return arg(vec, false);
    }

    private static Named arg(Map<String, Double> vec, boolean max) {
        if (vec == null || vec.isEmpty()) {
            return null;
        }
        List<String> keys = new ArrayList<>(vec.keySet());
        Collections.sort(keys);
        String bestKey = null;
        double best = max ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (String k : keys) {
            double v = vec.get(k);
            if (bestKey == null || (max ? v > best : v < best)) {
                bestKey = k;
                best = v;
            }
        }
        return new Named(bestKey, best);
    }

    public record Named(String key, double value) {
    }

    public static Pairs pairs(List<RawEvent> all, com.octant.pipeline.raw.EventType open,
                              com.octant.pipeline.raw.EventType close, Function<RawEvent, String> keyFn) {
        List<Interval> intervals = new ArrayList<>();
        Map<String, List<RawEvent>> openByKey = new LinkedHashMap<>();
        int openIntervals = 0;
        List<RawEvent> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(RawEvent::sessionId)
                .thenComparingLong(RawEvent::tRelMs)
                .thenComparing(RawEvent::eventId));
        for (RawEvent e : sorted) {
            if (e.type() == open) {
                openByKey.computeIfAbsent(keyFn.apply(e), k -> new ArrayList<>()).add(e);
            } else if (e.type() == close) {
                List<RawEvent> candidates = openByKey.get(keyFn.apply(e));
                if (candidates == null || candidates.isEmpty()) {
                    openIntervals++;
                    continue;
                }
                RawEvent startEv = candidates.remove(0);
                intervals.add(new Interval(startEv, e, durationOf(startEv, e)));
            }
        }
        for (List<RawEvent> rest : openByKey.values()) {
            openIntervals += rest.size();
        }
        return new Pairs(intervals, openIntervals);
    }

    private static long durationOf(RawEvent start, RawEvent end) {
        long d = Math.max(start.durMs(), end.durMs());
        return d > 0L ? d : Math.max(0L, end.tRelMs() - start.tRelMs());
    }

    public record Interval(RawEvent start, RawEvent end, long durationMs) {    }

    public record Pairs(List<Interval> intervals, int openIntervals) {
    }

    public enum Flag {
        FLAG_OPEN_SESSION, FLAG_AFK_SUSPECT, FLAG_CONTAMINATED, FLAG_IMPORTED_PROGRESS,
        FLAG_SHARED_WORLD, FLAG_BUILDING_PHASE, FLAG_DIFFICULTY_WALL, FLAG_INTENTIONAL_HOLD,
        FLAG_PREMATURE, FLAG_BUILD_EXEMPT, FLAG_FARM_COMBAT, FLAG_INTENTIONAL_DEATH,
        FLAG_ENVIRONMENT_KILL, FLAG_UNKNOWN_DEVICE_SHARE, FLAG_UNKNOWN_CATEGORY_SHARE,
        FLAG_PARTIAL, FLAG_PROXY
    }
}
