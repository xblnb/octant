package com.octant.pipeline.kiteview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.octant.pipeline.kiteview.KiteShapeCriteria.Hit;
import com.octant.pipeline.kiteview.KiteViewData.Sample;
import com.octant.pipeline.kiteview.KiteViewData.Series;

public final class KiteScene {

    private KiteScene() {
    }

    public static final double FOV = 0.62d;
    public static final double DEFAULT_AZ = -0.62d;
    public static final double DEFAULT_EL = 0.42d;
    public static final double FIT_FILL = 1.10d;
    public static final double PERSPECTIVE_K = 3.5d;
    public static final int GRID_LINES = 12;

    public static int gridLinesFor(int pointCount) {
        if (pointCount < 40) {
            return 16;
        }
        return pointCount < 120 ? 12 : 8;
    }

    public static double gridStrokeFor(int pointCount) {
        if (pointCount < 40) {
            return 2.6d;
        }
        return pointCount < 120 ? 1.8d : 1.3d;
    }
    public static final double[] TICKS = {0.0d, 0.25d, 0.5d, 0.75d, 1.0d};
    public static final int STAGES = 3;

    public record Camera(double az, double el, double dist, double panX, double panY, double focal) {
        public static Camera defaultFor(double radius) {
            return new Camera(DEFAULT_AZ, DEFAULT_EL, Math.max(1e-3d, PERSPECTIVE_K * radius), 0.0d, 0.0d, 1.0d);
        }

        public double pixelScale(double radius, double size, double margin) {
            return FIT_FILL * (size - 2.0d * margin) / 2.0d / Math.max(1e-6d, radius);
        }

        public String describe() {
            return String.format(Locale.ROOT, "方位角=%.4f rad 仰角=%.4f rad 距离=%.4f 中心偏移=(%.1f, %.1f)",
                    Double.valueOf(az), Double.valueOf(el), Double.valueOf(dist),
                    Double.valueOf(panX), Double.valueOf(panY));
        }
    }

    static String constantsJson(Camera cam, double size, double margin, double[] center, double radius) {
        return String.format(Locale.ROOT,
                "{\"size\":%.4f,\"margin\":%.4f,\"fov\":%.6f,\"cx\":%.6f,\"cy\":%.6f,\"cz\":%.6f,"
                        + "\"radius\":%.6f,\"az\":%.6f,\"el\":%.6f,\"dist\":%.6f,\"focal\":%.6f}",
                Double.valueOf(size), Double.valueOf(margin), Double.valueOf(FOV),
                Double.valueOf(center[0]), Double.valueOf(center[1]), Double.valueOf(center[2]),
                Double.valueOf(radius), Double.valueOf(cam.az()), Double.valueOf(cam.el()),
                Double.valueOf(cam.dist()), Double.valueOf(cam.focal()));
    }

    public static double[] project(double[] p, Camera cam, double[] center, double size, double margin) {
        return project(p, cam, center, size, margin, 0.5d);
    }

    public static double[] project(double[] p, Camera cam, double[] center, double size, double margin, double radius) {
        double[] q = rotate(p, cam, center);
        double persp = 1.0d - Math.max(-0.9d, Math.min(0.9d, q[2] / Math.max(1e-6d, cam.dist())));
        double s = cam.pixelScale(radius, size, margin) * cam.focal() / Math.max(0.1d, persp);
        return new double[] {size / 2.0d + cam.panX() + s * q[0], size / 2.0d + cam.panY() - s * q[1]};
    }

    public static double[] rotate(double[] p, Camera cam, double[] center) {
        double x = p[0] - center[0];
        double y = p[1] - center[1];
        double z = p[2] - center[2];
        double ca = Math.cos(cam.az());
        double sa = Math.sin(cam.az());
        double x1 = ca * x + sa * z;
        double z1 = -sa * x + ca * z;
        double ce = Math.cos(cam.el());
        double se = Math.sin(cam.el());
        double y2 = ce * y - se * z1;
        double z2 = se * y + ce * z1;
        return new double[] {x1, y2, z2};
    }

    public static double[] bboxCenter(Series s) {
        double minX = 1, maxX = 0, minY = 1, maxY = 0, minZ = 1, maxZ = 0;
        boolean any = false;
        for (Sample p : s.samples()) {
            if (Double.isNaN(p.prog()) || Double.isNaN(p.spon()) || Double.isNaN(p.guid())) {
                continue;
            }
            any = true;
            minX = Math.min(minX, p.prog());
            maxX = Math.max(maxX, p.prog());
            minY = Math.min(minY, p.spon());
            maxY = Math.max(maxY, p.spon());
            minZ = Math.min(minZ, p.guid());
            maxZ = Math.max(maxZ, p.guid());
        }
        if (!any) {
            return new double[] {0.5d, 0.5d, 0.5d};
        }
        return new double[] {(minX + maxX) / 2.0d, (minY + maxY) / 2.0d, (minZ + maxZ) / 2.0d};
    }

    public static double bboxRadius(Series s, double[] center) {
        double r2 = 0.0d;
        boolean any = false;
        for (Sample p : s.samples()) {
            if (Double.isNaN(p.prog()) || Double.isNaN(p.spon()) || Double.isNaN(p.guid())) {
                continue;
            }
            any = true;
            double dx = p.prog() - center[0];
            double dy = p.spon() - center[1];
            double dz = p.guid() - center[2];
            r2 = Math.max(r2, dx * dx + dy * dy + dz * dz);
        }
        double r = any ? Math.sqrt(r2) : 0.0d;
        return Math.max(r, 0.02d);
    }

    static final class Placer {
        private final List<double[]> taken = new ArrayList<>();
        private static final double[][] OFFSETS = {
            {10, -8}, {10, 12}, {-10, -8}, {-10, 12},
            {10, -24}, {-10, -24}, {16, 2}, {-16, 2},
            {10, 26}, {-10, 26}, {26, -4}, {-26, -4},
            {14, -40}, {-14, -40}, {14, 42}, {-14, 42},
            {44, -10}, {44, 10}, {-44, -10}, {-44, 10},
            {14, -58}, {-14, -58}, {60, 22}, {-60, 22},
            {70, -30}, {70, 30}, {-70, -30}, {-70, 30},
            {18, -76}, {-18, -76}, {18, 76}, {-18, 76},
        };

        boolean collides(double x, double y, String text, double fs) {
            double w = textWidth(text, fs);
            double[] box = {x, y - fs * 0.8d, x + w, y + fs * 0.25d};
            for (double[] b : taken) {
                if (intersect(box, b) > 0.0d) {
                    return true;
                }
            }
            return false;
        }

        void take(double x, double y, String text, double fs) {
            double w = textWidth(text, fs);
            taken.add(new double[] {x, y - fs * 0.8d, x + w, y + fs * 0.25d});
        }

        void reserve(double x0, double y0, double x1, double y1) {
            taken.add(new double[] {x0, y0, x1, y1});
        }
    }

    static double textWidth(String s, double fs) {
        double w = 0.0d;
        for (int i = 0; i < s.length(); i++) {
            w += (s.charAt(i) > 0x2E80) ? fs : fs * 0.6d;
        }
        return w;
    }

    static double intersect(double[] a, double[] b) {
        double dx = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        double dy = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        return (dx > 0 && dy > 0) ? dx * dy : 0.0d;
    }

    static double[] placeText(Placer placer, double ax, double ay, String text, double fs) {
        for (double[] off : Placer.OFFSETS) {
            double x = ax + off[0];
            double y = ay + off[1];
            if (!placer.collides(x, y, text, fs)) {
                placer.take(x, y, text, fs);
                return new double[] {x, y};
            }
        }
        double x = ax + Placer.OFFSETS[0][0];
        double y = ay + Placer.OFFSETS[0][1];
        placer.take(x, y, text, fs);
        return new double[] {x, y};
    }
}
