package com.octant.pipeline.kiteview;

import java.util.ArrayList;
import java.util.List;

import com.octant.pipeline.kiteview.KiteViewData.Sample;
import com.octant.pipeline.kiteview.KiteViewData.Series;

public final class KiteViewFixtures {

    private KiteViewFixtures() {
    }

    private static final String FIXTURE_FILE = "pipeline/src/main/java/com/octant/pipeline/kiteview/KiteViewFixtures.java";

    private static Sample s(int i, long t, double p, double sp, double gu, double v, int line) {
        return new Sample(i, t, p, sp, gu, v, FIXTURE_FILE, line);
    }

    public static Series positiveFixture() {
        List<Sample> p = new ArrayList<>();
        int i = 0;
        long t = 0L;
        double prog = 0.05d;
        double spon = 0.50d;
        double guid = 0.50d;
        double v = 1.0d;
        for (int k = 0; k < 4; k++) {
            p.add(s(i++, t, prog, spon, guid, v, 40 + i));
            prog += 0.06d;
            spon += 0.004d;
            guid += 0.002d;
            t += 600L;
            v += 0.4d;
        }
        double ang = 0.0d;
        for (int k = 0; k < 4; k++) {
            p.add(s(i++, t, prog, spon, guid, v, 48 + i));
            ang += 2.0d;
            prog += 0.02d * Math.cos(ang);
            spon += 0.02d * Math.sin(ang);
            guid += 0.01d * Math.cos(ang);
            t += 600L;
            v -= 0.3d;
        }
        for (int k = 0; k < 5; k++) {
            p.add(s(i++, t, prog, spon, guid, v, 56 + i));
            double turn = (k % 2 == 0) ? 0.05d : 1.40d;
            ang += turn;
            prog += 0.05d * Math.cos(ang);
            spon += 0.05d * Math.sin(ang);
            guid += 0.03d * Math.cos(2.0d * ang);
            t += 600L;
            v += ((k % 2 == 0) ? 0.7d : -0.6d);
        }
        p.add(s(i, t + 2400L, prog + 0.05d, spon + 0.05d, guid, Math.max(0.5d, v), 64 + i));
        return new Series("夹具·四形态正控", true, p);
    }

    public static Series uniformCadenceTrack() {
        List<Sample> p = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            p.add(s(k, k * 600L, 0.05d + 0.05d * k, 0.30d + 0.02d * k, 0.40d, 1.0d + 0.2d * k, 70 + k));
        }
        return new Series("夹具·等间隔（断线负控）", true, p);
    }

    public static Series straightRunTrack() {
        List<Sample> p = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            p.add(s(k, k * 600L, 0.05d + 0.09d * k, 0.50d + 0.001d * k, 0.50d, 1.0d, 80 + k));
        }
        return new Series("夹具·平直推进（打转负控）", true, p);
    }

    public static Series serpentineTrack() {
        List<Sample> p = new ArrayList<>();
        double x = 0.5d;
        double y = 0.5d;
        double ang = 0.0d;
        for (int k = 0; k < 10; k++) {
            p.add(s(k, k * 600L, x, y, 0.45d, 1.0d, 90 + k));
            ang += (k % 2 == 0) ? 0.55d : -0.55d;
            x += 0.05d * Math.cos(ang);
            y += 0.05d * Math.sin(ang);
        }
        return new Series("夹具·蛇形（紧绷直线负控）", true, p);
    }

    public static Series constantCurvatureTrack() {
        List<Sample> p = new ArrayList<>();
        double x = 0.5d;
        double y = 0.5d;
        double ang = 0.0d;
        for (int k = 0; k < 10; k++) {
            p.add(s(k, k * 600L, x, y, 0.50d, 1.0d, 100 + k));
            ang += 0.60d;
            x += 0.04d * Math.cos(ang);
            y += 0.04d * Math.sin(ang);
        }
        return new Series("夹具·等曲率圆（震荡负控）", true, p);
    }

    public static Series adaptiveCadenceTrack() {
        List<Sample> p = new ArrayList<>();
        long t = 0L;
        long[] deltas = {600L, 450L, 150L, 150L, 150L, 300L, 600L, 900L, 300L, 150L, 150L, 600L};
        for (int k = 0; k < deltas.length; k++) {
            p.add(s(k, t, 0.05d + 0.05d * k, 0.30d + 0.01d * k, 0.40d, 1.0d + 0.1d * k, 120 + k));
            t += deltas[k];
        }
        return new Series("夹具·自适应 Δ（断线地板负控）", true, p);
    }

    public static Series realGapTrack() {
        List<Sample> p = new ArrayList<>();
        long t = 0L;
        for (int k = 0; k < 6; k++) {
            p.add(s(k, t, 0.05d + 0.08d * k, 0.40d + 0.01d * k, 0.45d, 1.0d + 0.2d * k, 140 + k));
            t += (k == 3) ? 3000L : 600L;
        }
        return new Series("夹具·真缺口（断线正控）", true, p);
    }

    public static Series progOnlyTrack() {
        List<Sample> p = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            p.add(s(k, k * 600L, 0.10d + 0.12d * k, Double.NaN, Double.NaN, 1.0d, 110 + k));
        }
        return new Series("夹具·仅进度轴（其余轴不可判定）", true, p);
    }
}
