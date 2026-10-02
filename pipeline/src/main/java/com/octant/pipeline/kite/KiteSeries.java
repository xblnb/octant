package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class KiteSeries {

    public record KitePoint(int index, long startSec, long deltaSec, long activeSeconds,
                            double densityPerMin, int eventCount, KiteVector vector,
                            List<KiteObservation> observations) {

        public boolean complete() {
            return vector.complete();
        }
    }

    private final List<KitePoint> points;
    private final String kind;

    private KiteSeries(String kind, List<KitePoint> points) {
        this.kind = kind;
        this.points = Collections.unmodifiableList(points);
    }

    public static KiteSeries fixedBins(KiteObservationSource source, long deltaSec, int maxBins,
                                       boolean snapshotOnly) {
        List<long[]> spans = source.activeSpans();
        List<KitePoint> points = new ArrayList<>();
        int index = 0;
        for (long[] span : spans) {
            long start = span[0];
            long end = span[1];
            for (long t = start; t < end && points.size() < maxBins; t += deltaSec) {
                long len = Math.min(deltaSec, end - t);
                List<KiteObservation> obs = source.observations(t, len, snapshotOnly);
                KiteObservationSource.SlicedInterval slice = source.sliceOf(t, len);
                KiteSampleSize ss = KiteSampleSize.ofActiveSeconds(slice.activeSeconds(), index + 1);
                KiteVector vector = KiteAxisMath.vectorOf(obs, ss);
                points.add(new KitePoint(index, t, len, slice.activeSeconds(), densityOf(obs),
                        slice.eventCount(), vector, obs));
                index++;
            }
        }
        return new KiteSeries("fixed(" + deltaSec + "s, 活跃跨度 " + spans.size() + " 段)", points);
    }

    public static KiteSeries scheduled(KiteObservationSource source,
                                       List<KiteAdaptiveSampler.Sample> schedule, boolean snapshotOnly) {
        List<KitePoint> points = new ArrayList<>();
        for (KiteAdaptiveSampler.Sample s : schedule) {
            List<KiteObservation> obs = source.observations(s.startSec(), s.deltaSec(), snapshotOnly);
            KiteObservationSource.SlicedInterval slice = source.sliceOf(s.startSec(), s.deltaSec());
            KiteSampleSize ss = KiteSampleSize.ofActiveSeconds(slice.activeSeconds(), s.index() + 1);
            KiteVector vector = KiteAxisMath.vectorOf(obs, ss);
            points.add(new KitePoint(s.index(), s.startSec(), s.deltaSec(), slice.activeSeconds(),
                    s.densityPerMin(), slice.eventCount(), vector, obs));
        }
        return new KiteSeries("adaptive", points);
    }

    private static double densityOf(List<KiteObservation> obs) {
        for (KiteObservation o : obs) {
            if ("octant.stall.active_event_density".equals(o.key()) && o.available()) {
                return o.value();
            }
        }
        return Double.NaN;
    }

    public List<KitePoint> points() {
        return points;
    }

    public String kind() {
        return kind;
    }

    public int size() {
        return points.size();
    }

    public int usableCount() {
        int n = 0;
        for (KitePoint p : points) {
            if (p.complete()) {
                n++;
            }
        }
        return n;
    }

    public int usableCount(KiteAxis axis) {
        int n = 0;
        for (KitePoint p : points) {
            if (p.vector().axisValue(axis).available()) {
                n++;
            }
        }
        return n;
    }

    public List<double[]> usableVectors() {
        List<double[]> out = new ArrayList<>();
        for (KitePoint p : points) {
            if (p.complete()) {
                out.add(p.vector().requireValues());
            }
        }
        return Collections.unmodifiableList(out);
    }

    public String firstSuppression() {
        for (KitePoint p : points) {
            for (KiteAxis axis : KiteAxis.values()) {
                KiteAxisValue v = p.vector().axisValue(axis);
                if (!v.available()) {
                    return "点#" + p.index() + " " + axis + " = <" + v.reasonCode() + ">（" + v.observationDump() + "）";
                }
            }
        }
        return "（无抑制点）";
    }

    public List<String> dumpAxes() {
        List<String> out = new ArrayList<>();
        for (KitePoint p : points) {
            StringBuilder sb = new StringBuilder();
            sb.append("#").append(p.index()).append(" [").append(p.startSec()).append("s,+")
                    .append(p.deltaSec()).append("s) d=").append(KiteConstants.trim(p.densityPerMin()))
                    .append(" active=").append(p.activeSeconds()).append("s ");
            for (KiteAxis axis : KiteAxis.values()) {
                KiteAxisValue av = p.vector().axisValue(axis);
                sb.append(axis).append('=').append(av.available()
                        ? KiteConstants.trim(av.value())
                        : "<" + av.reasonCode() + ">").append(' ');
            }
            out.add(sb.toString().trim());
        }
        return out;
    }
}
