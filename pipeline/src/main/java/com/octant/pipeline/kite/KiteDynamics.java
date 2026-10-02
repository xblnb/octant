package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class KiteDynamics {

    private KiteDynamics() {
    }

    public static final double SIGMA_COMPONENT_BOUND = KiteConstants.KITE_SOFT_BETA;

    public static double softClamp(double z, double beta) {
        if (!(beta > 0d)) {
            throw new IllegalArgumentException("β 必须 > 0：" + beta);
        }
        if (z >= 0d) {
            return beta * (1d - Math.exp(-z / beta));
        }
        return -beta * (1d - Math.exp(z / beta));
    }

    public static double[] sigma(double[] d) {
        double[] out = new double[d.length];
        for (int j = 0; j < d.length; j++) {
            out[j] = softClamp(d[j] - KiteConstants.KITE_SOFT_ALPHA, KiteConstants.KITE_SOFT_BETA);
        }
        return out;
    }

    public static double[] displacement(double[] p0, double[] p1) {
        if (p0.length != p1.length) {
            throw new IllegalArgumentException("两点维数必须相同：" + p0.length + " vs " + p1.length);
        }
        double[] d = new double[p0.length];
        for (int j = 0; j < d.length; j++) {
            d[j] = p1[j] - p0[j];
        }
        return d;
    }

    public static double arcLength(double[] d) {
        double sum = 0d;
        for (double v : d) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    public static double[] unitTangent(double[] d) {
        double len = arcLength(d);
        double[] t = new double[d.length];
        if (len < KiteConstants.KITE_ZERO_LENGTH_EPS) {
            return t;
        }
        for (int j = 0; j < d.length; j++) {
            t[j] = d[j] / len;
        }
        return t;
    }

    public static double[] force(double[] d, double kappa, double c) {
        double len = arcLength(d);
        double[] f = new double[d.length];
        if (len == 0d) {
            return f;
        }
        double[] sigma = sigma(d);
        double[] t = unitTangent(d);
        for (int j = 0; j < f.length; j++) {
            f[j] = -kappa * sigma[j] - c * len * t[j];
        }
        return f;
    }

    public static double[] acceleration(double[] p0, double[] p1, double kappa, double c, double mass) {
        if (!(mass > 0d)) {
            throw new IllegalArgumentException("m 必须 > 0：" + mass);
        }
        double[] d = displacement(p0, p1);
        double[] f = force(d, kappa, c);
        double[] a = new double[f.length];
        for (int j = 0; j < a.length; j++) {
            double v = f[j] / mass;
            if (v > KiteConstants.KITE_ACC_MAX) {
                v = KiteConstants.KITE_ACC_MAX;
            } else if (v < -KiteConstants.KITE_ACC_MAX) {
                v = -KiteConstants.KITE_ACC_MAX;
            }
            a[j] = v;
        }
        return a;
    }

    public static double unsaturatedAccelerationMagnitude(double[] p0, double[] p1, double kappa, double c,
                                                          double mass) {
        double[] d = displacement(p0, p1);
        return magnitude(force(d, kappa, c)) / mass;
    }

    public static double magnitude(double[] v) {
        return arcLength(v);
    }

    public static double worstCaseAccelerationMagnitude() {
        final int steps = 21;
        double worst = 0d;
        double[] d = new double[3];
        for (int i = 0; i < steps; i++) {
            d[0] = -1d + 0.1d * i;
            for (int j = 0; j < steps; j++) {
                d[1] = -1d + 0.1d * j;
                for (int k = 0; k < steps; k++) {
                    d[2] = -1d + 0.1d * k;
                    double[] a = force(d, KiteConstants.KITE_SPRING_KAPPA, KiteConstants.KITE_DAMPING_C);
                    worst = Math.max(worst, magnitude(a) / KiteConstants.KITE_MASS_M);
                }
            }
        }
        return worst;
    }

    public static List<Double> accelerationMagnitudes(List<double[]> series) {
        List<Double> out = new ArrayList<>();
        for (int k = 0; k + 1 < series.size(); k++) {
            double[] a = acceleration(series.get(k), series.get(k + 1),
                    KiteConstants.KITE_SPRING_KAPPA, KiteConstants.KITE_DAMPING_C, KiteConstants.KITE_MASS_M);
            out.add(magnitude(a));
        }
        return Collections.unmodifiableList(out);
    }

    public static int acceleratedSegments(List<double[]> series, double kappa, double c) {
        int n = 0;
        for (int k = 0; k + 1 < series.size(); k++) {
            double[] a = acceleration(series.get(k), series.get(k + 1), kappa, c, KiteConstants.KITE_MASS_M);
            if (magnitude(a) >= KiteConstants.KITE_ACC_DETECT) {
                n++;
            }
        }
        return n;
    }

    public static boolean allFinite(List<double[]> series) {
        for (int k = 0; k + 1 < series.size(); k++) {
            double[] d = displacement(series.get(k), series.get(k + 1));
            if (!Double.isFinite(arcLength(d))) {
                return false;
            }
            double[] f = force(d, KiteConstants.KITE_SPRING_KAPPA, KiteConstants.KITE_DAMPING_C);
            for (double v : f) {
                if (!Double.isFinite(v)) {
                    return false;
                }
            }
        }
        return true;
    }
}
