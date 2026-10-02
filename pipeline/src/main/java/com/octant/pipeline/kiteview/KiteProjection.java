package com.octant.pipeline.kiteview;

import java.util.List;

public final class KiteProjection {

    private KiteProjection() {
    }

    public static final double[][] MATRIX_ISO = {
        {0.0d, 1.0d, 0.45d},
        {1.0d, 0.0d, 0.35d},
    };

    public static final double[][] MATRIX_TOP = {
        {0.0d, 1.0d, 0.0d},
        {1.0d, 0.0d, 0.0d},
    };

    public static final List<String> VIEW_NAMES = List.of("默认斜视", "俯视（进度×自发性）");

    public static int viewCount() {
        return VIEW_NAMES.size();
    }

    public static double[][] matrix(int view) {
        return (view == 0) ? MATRIX_ISO : MATRIX_TOP;
    }

    public static double[] project(double[] progSponGuid, int view) {
        double[][] m = matrix(view);
        double x = m[0][0] * progSponGuid[0] + m[0][1] * progSponGuid[1] + m[0][2] * progSponGuid[2];
        double y = m[1][0] * progSponGuid[0] + m[1][1] * progSponGuid[1] + m[1][2] * progSponGuid[2];
        return new double[] {x - 0.5d, y - 0.5d};
    }

    public static double[] toCanvas(double[] progSponGuid, int view, double size, double margin) {
        double[] n = project(progSponGuid, view);
        double half = (size - 2.0d * margin) / 2.0d;
        double cx = size / 2.0d + n[0] * half;
        double cy = size / 2.0d - n[1] * half;
        return new double[] {cx, cy};
    }

    public static List<double[]> axisTicks(int view, double size, double margin) {
        java.util.ArrayList<double[]> ticks = new java.util.ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            for (int k = 0; k <= 4; k++) {
                double v = k / 4.0d;
                double[] p = new double[] {0.0d, 0.0d, 0.0d};
                p[axis] = v;
                ticks.add(toCanvas(p, view, size, margin));
            }
        }
        return ticks;
    }
}
