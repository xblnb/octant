package com.octant.pipeline.kiteview;

import java.util.ArrayList;
import java.util.List;

public final class KiteViewData {

    private KiteViewData() {
    }

    public static final double THETA_DIR = 0.35d;
    public static final double THETA_MAG = 0.15d;
    public static final double UT_BOUNDARY = 1.0d;

    public record Sample(int index, long tSec, double prog, double spon, double guid,
                         double densityPerMin, String sourceFile, int sourceLine) {

        public double[] vector() {
            return new double[] {prog, spon, guid};
        }
    }

    public static final class Series {

        private final String label;
        private final boolean fixture;
        private final List<Sample> samples;

        public Series(String label, boolean fixture, List<Sample> samples) {
            this.label = label;
            this.fixture = fixture;
            this.samples = List.copyOf(samples);
        }

        public String label() {
            return label;
        }

        public boolean fixture() {
            return fixture;
        }

        public List<Sample> samples() {
            return samples;
        }

        public int size() {
            return samples.size();
        }

        public List<double[]> displacements() {
            List<double[]> out = new ArrayList<>();
            for (int i = 0; i + 1 < samples.size(); i++) {
                double[] a = samples.get(i).vector();
                double[] b = samples.get(i + 1).vector();
                out.add(new double[] {b[0] - a[0], b[1] - a[1], b[2] - a[2]});
            }
            return out;
        }

        public static double norm(double[] v) {
            return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        }

        public static double arcLength(List<double[]> d) {
            double s = 0.0d;
            for (double[] x : d) {
                s += norm(x);
            }
            return s;
        }

        public static double displacement(List<double[]> d) {
            if (d.isEmpty()) {
                return 0.0d;
            }
            double[] acc = new double[] {0, 0, 0};
            for (double[] x : d) {
                acc[0] += x[0];
                acc[1] += x[1];
                acc[2] += x[2];
            }
            return norm(acc);
        }

        public List<Double> ut() {
            List<double[]> d = displacements();
            List<Double> out = new ArrayList<>();
            for (int i = 1; i < d.size(); i++) {
                out.add(Double.valueOf(utStep(d.get(i - 1), d.get(i))));
            }
            return out;
        }

        public static double utStep(double[] dPrev, double[] dNext) {
            double nPrev = norm(dPrev);
            double nNext = norm(dNext);
            if (nPrev <= 0.0d || nNext <= 0.0d) {
                return Double.NaN;
            }
            double dot = dPrev[0] * dNext[0] + dPrev[1] * dNext[1] + dPrev[2] * dNext[2];
            double cos = Math.max(-1.0d, Math.min(1.0d, dot / (nPrev * nNext)));
            double dDir = Math.acos(cos);
            double dMag = Math.abs(nNext - nPrev) / nPrev;
            return Math.max(dDir / THETA_DIR, dMag / THETA_MAG);
        }

        public static String utState(double ut) {
            if (Double.isNaN(ut)) {
                return null;
            }
            return ut < UT_BOUNDARY ? "smooth" : "urgent";
        }

        public static int widthBin(double v, double q33, double q66) {
            if (v < q33) {
                return 1;
            }
            return v < q66 ? 2 : 3;
        }

        public double[] densityQuantiles() {
            List<Double> vs = new ArrayList<>();
            for (Sample s : samples) {
                vs.add(Double.valueOf(s.densityPerMin()));
            }
            vs.sort(null);
            if (vs.isEmpty()) {
                return new double[] {0.0d, 0.0d};
            }
            int n = vs.size();
            int i33 = Math.min(n - 1, Math.max(0, (int) Math.floor(0.33d * (n - 1))));
            int i66 = Math.min(n - 1, Math.max(0, (int) Math.floor(0.66d * (n - 1))));
            return new double[] {vs.get(i33).doubleValue(), vs.get(i66).doubleValue()};
        }
    }

    public static double[] midpoint(Sample a, Sample b) {
        return new double[] {(a.prog() + b.prog()) / 2.0d, (a.spon() + b.spon()) / 2.0d, (a.guid() + b.guid()) / 2.0d};
    }
}
