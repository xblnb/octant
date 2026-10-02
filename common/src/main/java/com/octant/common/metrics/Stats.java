package com.octant.common.metrics;

import com.octant.common.session.Session;

import java.util.ArrayList;
import java.util.List;

public final class Stats {

    private Stats() {
    }

    public static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("分位数要求至少一个样本");
        }
        if (p < 0 || p > 1) {
            throw new IllegalArgumentException("p 必须在 [0,1] 内");
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double rank = p * (sorted.size() - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) {
            return sorted.get(lo);
        }
        double frac = rank - lo;
        return sorted.get(lo) * (1 - frac) + sorted.get(hi) * frac;
    }

    public static double median(List<Double> values) {
        return percentile(values, 0.5);
    }

    public static List<Double> activeMinutes(List<Session> sessions) {
        List<Double> out = new ArrayList<>(sessions.size());
        for (Session s : sessions) {
            out.add(s.activeSeconds() / 60.0d);
        }
        return out;
    }

    public static List<Double> offlineGapsHours(List<Session> sessions) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < sessions.size(); i++) {
            long gap = sessions.get(i).startSecond() - sessions.get(i - 1).endSecond();
            if (gap > 0) {
                out.add(gap / 3600.0d);
            }
        }
        return out;
    }

    public static double gini(List<Double> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("基尼系数要求至少一个样本");
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        double sum = 0;
        for (double v : sorted) {
            if (v < 0) {
                throw new IllegalArgumentException("基尼系数要求非负样本");
            }
            sum += v;
        }
        if (sum == 0) {
            return 0.0d;
        }
        int n = sorted.size();
        double weighted = 0;
        for (int i = 0; i < n; i++) {
            weighted += (i + 1) * sorted.get(i);
        }
        return (2.0d * weighted) / (n * sum) - (n + 1.0d) / n;
    }

    public static double normalizedShannonEntropy(List<Double> values) {
        double sum = 0;
        for (double v : values) {
            if (v < 0) {
                throw new IllegalArgumentException("熵要求非负样本");
            }
            sum += v;
        }
        int k = values.size();
        if (sum == 0 || k < 2) {
            return 0.0d;
        }
        double h = 0;
        for (double v : values) {
            if (v > 0) {
                double p = v / sum;
                h -= p * Math.log(p);
            }
        }
        return h / Math.log(k);
    }
}
