package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KiteOrthogonality {

    private KiteOrthogonality() {
    }

    public record Pair(KiteAxis a, KiteAxis b, int n, double rhoSquared, Verdict verdict, String note) {
    }

    public enum Verdict {
        PASS,
        FAIL,
        UNVERIFIED
    }

    public static Double rhoSquared(double[] p, double[] q) {
        if (p.length != q.length || p.length == 0) {
            return null;
        }
        double scaleP = 0d;
        double scaleQ = 0d;
        for (int i = 0; i < p.length; i++) {
            scaleP = Math.max(scaleP, Math.abs(p[i]));
            scaleQ = Math.max(scaleQ, Math.abs(q[i]));
        }
        Centered cp = center(p, scaleP);
        Centered cq = center(q, scaleQ);
        if (cp.degenerate() || cq.degenerate()) {
            return null;
        }
        double cov = 0d;
        for (int i = 0; i < p.length; i++) {
            cov += cp.deviations()[i] * cq.deviations()[i];
        }
        double r2 = (cov * cov) / (cp.sumSquares() * cq.sumSquares());
        if (Double.isNaN(r2)) {
            return null;
        }
        return Math.min(1d, Math.max(0d, r2));
    }

    private record Centered(double[] deviations, double sumSquares, boolean degenerate) {
    }

    private static final double DEGENERATE_EPS = 1e-9d;

    public static double[] noisyConstantColumn(int n, double base, double amp) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = base * (1d + amp * ((i % 3) - 1));
        }
        return out;
    }

    private static Centered center(double[] v, double scale) {
        double m = mean(v);
        double[] d = new double[v.length];
        double ss = 0d;
        for (int i = 0; i < v.length; i++) {
            d[i] = v[i] - m;
            ss += d[i] * d[i];
        }
        double relativeVar = ss / v.length;
        double limit = DEGENERATE_EPS * Math.max(scale, DEGENERATE_EPS);
        boolean degenerate = relativeVar <= limit * limit;
        return new Centered(d, ss, degenerate);
    }

    static double mean(double[] v) {
        double s = 0d;
        for (double x : v) {
            s += x;
        }
        return v.length == 0 ? 0d : s / v.length;
    }

    public static double[] column(List<double[]> rows, int index) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            out[i] = rows.get(i)[index];
        }
        return out;
    }

    public static List<Pair> pairwise(List<double[]> rows) {
        int n = rows.size();
        double[] x = column(rows, 0);
        double[] y = column(rows, 1);
        double[] z = column(rows, 2);
        List<Pair> out = new ArrayList<>();
        out.add(pair(KiteAxis.PROG, KiteAxis.SPON, n, x, y));
        out.add(pair(KiteAxis.PROG, KiteAxis.GUID, n, x, z));
        out.add(pair(KiteAxis.SPON, KiteAxis.GUID, n, y, z));
        return Collections.unmodifiableList(out);
    }

    public static List<double[]> independentControlSeries(int n) {
        int[] bitWeights = {1, 2, 4};
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] row = new double[bitWeights.length];
            for (int j = 0; j < bitWeights.length; j++) {
                row[j] = (Integer.bitCount(i & bitWeights[j]) & 1) == 0 ? 0.2d : 0.8d;
            }
            out.add(row);
        }
        return Collections.unmodifiableList(out);
    }

    private static Pair pair(KiteAxis a, KiteAxis b, int n, double[] p, double[] q) {        if (n < KiteConstants.KITE_ORTHO_MIN_N) {
            return new Pair(a, b, n, Double.NaN, Verdict.UNVERIFIED,
                    "N=" + n + " < KITE_ORTHO_MIN_N=" + KiteConstants.KITE_ORTHO_MIN_N + "（OR-3）");
        }
        Double r2 = rhoSquared(p, q);
        if (r2 == null) {
            return new Pair(a, b, n, Double.NaN, Verdict.UNVERIFIED,
                    "ZERO_VARIANCE：中心化后方差为 0（常数列）⇒ ρ² 无定义，不得记 0、不得记通过（OR-2）");
        }
        Verdict v = r2 <= KiteConstants.KITE_ORTHO_MAX ? Verdict.PASS : Verdict.FAIL;
        return new Pair(a, b, n, r2, v, "ρ²=" + KiteConstants.trim(r2)
                + (v == Verdict.PASS ? " ≤ " : " > ") + "KITE_ORTHO_MAX=" + KiteConstants.trim(KiteConstants.KITE_ORTHO_MAX));
    }

    public static double maxRhoSquared(List<Pair> pairs) {
        double max = Double.NaN;
        for (Pair p : pairs) {
            if (Double.isNaN(p.rhoSquared())) {
                continue;
            }
            max = Double.isNaN(max) ? p.rhoSquared() : Math.max(max, p.rhoSquared());
        }
        return max;
    }

    public record Intersection(KiteAxis a, KiteAxis b, String level, Set<String> items) {
    }

    public static List<Intersection> sourceIntersections() {
        return sourceIntersections(KiteAxisDefinition.primitiveByKey(),
                KiteAxisDefinition.derivedKeysOf(KiteAxis.PROG),
                KiteAxisDefinition.derivedKeysOf(KiteAxis.SPON),
                KiteAxisDefinition.derivedKeysOf(KiteAxis.GUID));
    }

    public static List<Intersection> sourceIntersections(Map<String, String> primitiveByKey,
                                                         Set<String> progKeys, Set<String> sponKeys,
                                                         Set<String> guidKeys) {
        List<Intersection> out = new ArrayList<>();
        Map<KiteAxis, Set<String>> keys = new LinkedHashMap<>();
        Map<KiteAxis, Set<String>> prims = new LinkedHashMap<>();
        keys.put(KiteAxis.PROG, progKeys);
        keys.put(KiteAxis.SPON, sponKeys);
        keys.put(KiteAxis.GUID, guidKeys);
        prims.put(KiteAxis.PROG, primitivesOf(primitiveByKey, progKeys));
        prims.put(KiteAxis.SPON, primitivesOf(primitiveByKey, sponKeys));
        prims.put(KiteAxis.GUID, primitivesOf(primitiveByKey, guidKeys));

        KiteAxis[] all = KiteAxis.values();
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                Set<String> keyInter = new LinkedHashSet<>(keys.get(all[i]));
                keyInter.retainAll(keys.get(all[j]));
                if (!keyInter.isEmpty()) {
                    out.add(new Intersection(all[i], all[j], "键级", keyInter));
                }
                Set<String> primInter = new LinkedHashSet<>(prims.get(all[i]));
                primInter.retainAll(prims.get(all[j]));
                if (!primInter.isEmpty()) {
                    out.add(new Intersection(all[i], all[j], "原语级", primInter));
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static Set<String> primitivesOf(Map<String, String> primitiveByKey, Set<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        for (String k : keys) {
            String p = primitiveByKey.get(k);
            out.add(p == null ? k : p);
        }
        return out;
    }

    public static List<KiteAxisDefinition.SharedPrimitive> registeredShared() {
        return KiteAxisDefinition.sharedPrimitives();
    }

    public static List<Intersection> unregisteredIntersections(List<Intersection> intersections) {
        Set<String> registered = new LinkedHashSet<>();
        for (KiteAxisDefinition.SharedPrimitive sp : registeredShared()) {
            registered.add(sp.primitive());
        }
        List<Intersection> out = new ArrayList<>();
        for (Intersection i : intersections) {
            if ("原语级".equals(i.level()) && registered.containsAll(i.items())) {
                continue;
            }
            out.add(i);
        }
        return Collections.unmodifiableList(out);
    }

    public record MultiCollinearity(KiteAxis axis, int n, double rSquared, Verdict verdict, String note) {
    }

    public static List<MultiCollinearity> multiCollinearity(List<double[]> rows) {
        List<MultiCollinearity> out = new ArrayList<>();
        if (rows.size() < KiteConstants.KITE_ORTHO_MIN_N) {
            for (KiteAxis axis : KiteAxis.values()) {
                out.add(new MultiCollinearity(axis, rows.size(), Double.NaN, Verdict.UNVERIFIED,
                        "N=" + rows.size() + " < KITE_ORTHO_MIN_N（OR-3）"));
            }
            return Collections.unmodifiableList(out);
        }
        for (int j = 0; j < 3; j++) {
            int a = (j + 1) % 3;
            int b = (j + 2) % 3;
            double[] y = column(rows, j);
            double[] u = column(rows, a);
            double[] v = column(rows, b);
            Double r2 = multipleRSquared(y, u, v);
            KiteAxis axis = KiteAxis.values()[j];
            if (r2 == null) {
                out.add(new MultiCollinearity(axis, rows.size(), Double.NaN, Verdict.UNVERIFIED,
                        "自变量（另两轴）共线/常数 ⇒ 回归无定义，不得记 0"));
            } else {
                Verdict verdict = r2 <= KiteConstants.KITE_ORTHO_MAX ? Verdict.PASS : Verdict.FAIL;
                out.add(new MultiCollinearity(axis, rows.size(), r2, verdict,
                        "R²(" + axis + " | 另两轴)=" + KiteConstants.trim(r2)
                                + (verdict == Verdict.PASS ? " ≤ " : " > ") + "KITE_ORTHO_MAX"));
            }
        }
        return Collections.unmodifiableList(out);
    }

    static Double multipleRSquared(double[] y, double[] u, double[] v) {
        int n = y.length;
        double[][] xtx = new double[3][3];
        double[] xty = new double[3];
        for (int i = 0; i < n; i++) {
            double[] row = {1d, u[i], v[i]};
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    xtx[r][c] += row[r] * row[c];
                }
                xty[r] += row[r] * y[i];
            }
        }
        double[] beta = solve3(xtx, xty);
        if (beta == null) {
            return null;
        }
        double my = mean(y);
        double sse = 0d;
        double sst = 0d;
        for (int i = 0; i < n; i++) {
            double pred = beta[0] + beta[1] * u[i] + beta[2] * v[i];
            sse += (y[i] - pred) * (y[i] - pred);
            sst += (y[i] - my) * (y[i] - my);
        }
        if (sst == 0d) {
            return null;
        }
        return Math.min(1d, Math.max(0d, 1d - sse / sst));
    }

    private static double[] solve3(double[][] m, double[] rhs) {
        double[][] a = new double[3][4];
        for (int r = 0; r < 3; r++) {
            System.arraycopy(m[r], 0, a[r], 0, 3);
            a[r][3] = rhs[r];
        }
        for (int col = 0; col < 3; col++) {
            int pivot = col;
            for (int r = col + 1; r < 3; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-12d) {
                return null;
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            for (int r = 0; r < 3; r++) {
                if (r == col) {
                    continue;
                }
                double f = a[r][col] / a[col][col];
                for (int c = col; c < 4; c++) {
                    a[r][c] -= f * a[col][c];
                }
            }
        }
        double[] x = new double[3];
        for (int r = 0; r < 3; r++) {
            x[r] = a[r][3] / a[r][r];
        }
        return x;
    }

    public static List<String> dumpPairs(List<Pair> pairs) {
        List<String> out = new ArrayList<>();
        int i = 1;
        for (Pair p : pairs) {
            out.add("ρ²(" + p.a() + "," + p.b() + ") = "
                    + (Double.isNaN(p.rhoSquared()) ? "n/a" : KiteConstants.trim(p.rhoSquared()))
                    + "  N=" + p.n() + "  " + p.verdict() + "  [" + p.note() + "]");
            i++;
        }
        return out;
    }
}
