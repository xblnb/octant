package com.octant.pipeline.kiteview;

import java.util.ArrayList;
import java.util.List;

import com.octant.pipeline.kiteview.KiteViewData.Sample;
import com.octant.pipeline.kiteview.KiteViewData.Series;

public final class KiteShapeCriteria {

    private KiteShapeCriteria() {
    }

    public static final double BREAK_DT_TAU = 2.5d;

    public static final int LOOP_WINDOW = 3;
    public static final double LOOP_TURN_TAU = 1.20d;
    public static final double LOOP_RATIO_TAU = 0.35d;

    public static final int TAUT_WINDOW = 3;
    public static final double TAUT_CONSISTENCY_TAU = 0.92d;
    public static final double TAUT_CURVATURE_TAU = 0.08d;

    public static final int JITTER_WINDOW = 5;
    public static final double JITTER_VARIANCE_TAU = 0.25d;

    public record Hit(String shape, String window, int fromIndex, int toIndex, String geometry) {
        public String label() {
            return "形态：" + shape;
        }
    }

    public static final List<String> SHAPE_NAMES = List.of("断线", "原地打转", "紧绷直线", "剧烈震荡");

    static List<Double> stepTurns(List<double[]> d) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < d.size(); i++) {
            double[] a = d.get(i - 1);
            double[] b = d.get(i);
            double na = Series.norm(a);
            double nb = Series.norm(b);
            if (na <= 0.0d || nb <= 0.0d) {
                out.add(Double.valueOf(Double.NaN));
                continue;
            }
            double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
            double cos = Math.max(-1.0d, Math.min(1.0d, dot / (na * nb)));
            out.add(Double.valueOf(Math.acos(cos)));
        }
        return out;
    }

    public static double medianDeltaSec(Series s) {
        List<Sample> p = s.samples();
        if (p.size() < 2) {
            return 0.0d;
        }
        List<Long> ds = new ArrayList<>();
        for (int i = 0; i + 1 < p.size(); i++) {
            ds.add(Long.valueOf(p.get(i + 1).tSec() - p.get(i).tSec()));
        }
        ds.sort(null);
        int n = ds.size();
        return (n % 2 == 1) ? ds.get(n / 2).doubleValue()
                : (ds.get(n / 2 - 1).doubleValue() + ds.get(n / 2).doubleValue()) / 2.0d;
    }

    public static List<Hit> detectBreak(Series s) {
        return detectBreak(s, 0.0d);
    }

    public static List<Hit> detectBreak(Series s, double maxDeltaFloorSec) {
        List<Hit> hits = new ArrayList<>();
        List<Sample> p = s.samples();
        double med = medianDeltaSec(s);
        if (med <= 0.0d) {
            return hits;
        }
        double tau = Math.max(BREAK_DT_TAU * med, maxDeltaFloorSec);
        for (int i = 0; i + 1 < p.size(); i++) {
            long dt = p.get(i + 1).tSec() - p.get(i).tSec();
            if (dt > tau) {
                hits.add(new Hit("断线", "相邻采样点",
                        p.get(i).index(), p.get(i + 1).index(),
                        String.format(java.util.Locale.ROOT,
                                "间隔=%d s > 阈值=%.1f s（= max(%.1f × 中位 %d s, 设计内上界 %.0f s)）",
                                Long.valueOf(dt), Double.valueOf(tau), Double.valueOf(BREAK_DT_TAU),
                                Long.valueOf((long) med), Double.valueOf(maxDeltaFloorSec))));
            }
        }
        return hits;
    }

    public static List<Hit> detectLoop(Series s) {
        List<Hit> hits = new ArrayList<>();
        List<double[]> d = s.displacements();
        List<Double> turns = stepTurns(d);
        if (d.size() < LOOP_WINDOW) {
            return hits;
        }
        for (int i = 0; i + LOOP_WINDOW <= d.size(); i++) {
            double cum = 0.0d;
            boolean usable = true;
            for (int j = i; j <= i + LOOP_WINDOW - 2; j++) {
                double t = turns.get(j).doubleValue();
                if (Double.isNaN(t)) {
                    usable = false;
                    break;
                }
                cum += t;
            }
            if (!usable) {
                continue;
            }
            List<double[]> win = d.subList(i, i + LOOP_WINDOW);
            double arc = Series.arcLength(win);
            double disp = Series.displacement(win);
            double ratio = (arc <= 0.0d) ? 1.0d : disp / arc;
            if (cum > LOOP_TURN_TAU && ratio < LOOP_RATIO_TAU) {
                hits.add(new Hit("原地打转", LOOP_WINDOW + " 步窗口",
                        s.samples().get(i).index(), s.samples().get(i + LOOP_WINDOW).index(),
                        String.format(java.util.Locale.ROOT,
                                "累计转向角=%.9f rad %s 阈值=%.6f rad 且 位移/弧长=%.9f %s 阈值=%.6f",
                                Double.valueOf(cum), (cum > LOOP_TURN_TAU ? ">" : "≤"),
                                Double.valueOf(LOOP_TURN_TAU),
                                Double.valueOf(ratio), (ratio < LOOP_RATIO_TAU ? "<" : "≥"),
                                Double.valueOf(LOOP_RATIO_TAU))));
            }
        }
        return hits;
    }

    public static List<Hit> detectTaut(Series s) {
        List<Hit> hits = new ArrayList<>();
        List<double[]> d = s.displacements();
        List<Double> turns = stepTurns(d);
        if (d.size() < TAUT_WINDOW) {
            return hits;
        }
        for (int i = 0; i + TAUT_WINDOW <= d.size(); i++) {
            List<double[]> win = d.subList(i, i + TAUT_WINDOW);
            double arc = Series.arcLength(win);
            double disp = Series.displacement(win);
            double consistency = (arc <= 0.0d) ? 0.0d : disp / arc;
            double curvSum = 0.0d;
            boolean usable = true;
            for (int k = i + 1; k < i + TAUT_WINDOW; k++) {
                double t = turns.get(k - 1).doubleValue();
                if (Double.isNaN(t)) {
                    usable = false;
                    break;
                }
                curvSum += t;
            }
            if (!usable) {
                continue;
            }
            double curv = curvSum / (TAUT_WINDOW - 1);
            if (consistency > TAUT_CONSISTENCY_TAU && curv < TAUT_CURVATURE_TAU) {
                hits.add(new Hit("紧绷直线", TAUT_WINDOW + " 步窗口",
                        s.samples().get(i).index(), s.samples().get(i + TAUT_WINDOW).index(),
                        String.format(java.util.Locale.ROOT,
                                "方向一致性=%.9f %s 阈值=%.6f 且 平均曲率=%.9f rad/步 %s 阈值=%.6f rad/步",
                                Double.valueOf(consistency), (consistency > TAUT_CONSISTENCY_TAU ? ">" : "≤"),
                                Double.valueOf(TAUT_CONSISTENCY_TAU),
                                Double.valueOf(curv), (curv < TAUT_CURVATURE_TAU ? "<" : "≥"),
                                Double.valueOf(TAUT_CURVATURE_TAU))));
            }
        }
        return hits;
    }

    public static List<Hit> detectJitter(Series s) {
        List<Hit> hits = new ArrayList<>();
        List<double[]> d = s.displacements();
        List<Double> turns = stepTurns(d);
        if (d.size() < JITTER_WINDOW) {
            return hits;
        }
        for (int i = 0; i + JITTER_WINDOW <= d.size(); i++) {
            List<Double> win = new ArrayList<>();
            boolean usable = true;
            for (int k = i + 1; k < i + JITTER_WINDOW; k++) {
                double t = turns.get(k - 1).doubleValue();
                if (Double.isNaN(t)) {
                    usable = false;
                    break;
                }
                win.add(Double.valueOf(t));
            }
            if (!usable || win.isEmpty()) {
                continue;
            }
            double mean = 0.0d;
            for (Double v : win) {
                mean += v.doubleValue();
            }
            mean /= win.size();
            double var = 0.0d;
            for (Double v : win) {
                double e = v.doubleValue() - mean;
                var += e * e;
            }
            var /= win.size();
            if (var > JITTER_VARIANCE_TAU) {
                hits.add(new Hit("剧烈震荡", JITTER_WINDOW + " 步窗口",
                        s.samples().get(i).index(), s.samples().get(i + JITTER_WINDOW).index(),
                        String.format(java.util.Locale.ROOT,
                                "方向变化率方差=%.9f rad²/步² %s 阈值=%.6f（窗口内 %d 个转向角的均值=%.9f）",
                                Double.valueOf(var), (var > JITTER_VARIANCE_TAU ? ">" : "≤"),
                                Double.valueOf(JITTER_VARIANCE_TAU),
                                Integer.valueOf(win.size()), Double.valueOf(mean))));
            }
        }
        return hits;
    }

    public static List<Hit> detectAll(Series s) {
        return detectAll(s, 0.0d);
    }

    public static List<Hit> detectAll(Series s, double maxDeltaFloorSec) {
        List<Hit> all = new ArrayList<>();
        all.addAll(detectBreak(s, maxDeltaFloorSec));
        all.addAll(detectLoop(s));
        all.addAll(detectTaut(s));
        all.addAll(detectJitter(s));
        return all;
    }
}
