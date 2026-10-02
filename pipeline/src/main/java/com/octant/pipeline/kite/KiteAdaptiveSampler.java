package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class KiteAdaptiveSampler {

    public static final String REASON_DEGENERATE_K = KiteConstants.RC_SAMPLE_INSUFFICIENT;
    public static final String PROPOSED_CODE_NOT_REGISTERED = "INPUT_KITE_INSUFFICIENT_POINTS";

    public static final class Sample {
        private final int index;
        private final long startSec;
        private final long deltaSec;
        private final double densityPerMin;
        private final int historyK;
        private final int sign;
        private final boolean degenerate;
        private final String reasonCode;
        private final double quantile25;
        private final double quantile75;

        private Sample(int index, long startSec, long deltaSec, double densityPerMin, int historyK, int sign,
                       boolean degenerate, String reasonCode, double quantile25, double quantile75) {
            this.index = index;
            this.startSec = startSec;
            this.deltaSec = deltaSec;
            this.densityPerMin = densityPerMin;
            this.historyK = historyK;
            this.sign = sign;
            this.degenerate = degenerate;
            this.reasonCode = reasonCode;
            this.quantile25 = quantile25;
            this.quantile75 = quantile75;
        }

        public int index() {
            return index;
        }

        public long startSec() {
            return startSec;
        }

        public long endSec() {
            return startSec + deltaSec;
        }

        public long deltaSec() {
            return deltaSec;
        }

        public double densityPerMin() {
            return densityPerMin;
        }

        public int historyK() {
            return historyK;
        }

        public int sign() {
            return sign;
        }

        public boolean degenerate() {
            return degenerate;
        }

        public String reasonCode() {
            return reasonCode;
        }

        public double quantile25() {
            return quantile25;
        }

        public double quantile75() {
            return quantile75;
        }
    }

    private KiteAdaptiveSampler() {
    }

    public static List<Sample> schedule(List<Double> densityPerBin) {
        List<Sample> out = new ArrayList<>();
        List<Double> history = new ArrayList<>();
        long delta = KiteConstants.KITE_DT_REF_S;
        long cursor = 0L;
        for (int i = 0; i < densityPerBin.size(); i++) {
            double d = densityPerBin.get(i);
            int k = history.size();
            boolean degenerate = k < KiteConstants.KITE_ADAPT_MIN_SEG;
            int sign = 0;
            String reason = null;
            double q25 = Double.NaN;
            double q75 = Double.NaN;
            if (degenerate) {
                delta = KiteConstants.KITE_DT_REF_S;
                reason = REASON_DEGENERATE_K;
            } else {
                q25 = quantile(sortedCopy(history), 0.25d);
                q75 = quantile(sortedCopy(history), 0.75d);
                if (d >= q75) {
                    sign = -1;
                } else if (d <= q25) {
                    sign = 1;
                }
                delta = clamp(delta + (long) sign * KiteConstants.KITE_DT_STEP_S);
            }
            out.add(new Sample(i, cursor, delta, d, k, sign, degenerate, reason, q25, q75));
            cursor += delta;
            history.add(d);
        }
        return Collections.unmodifiableList(out);
    }

    private static long clamp(long delta) {
        long v = Math.max(KiteConstants.KITE_DT_MIN_S, delta);
        return Math.min(KiteConstants.KITE_DT_MAX_S, v);
    }

    static List<Double> sortedCopy(List<Double> in) {
        List<Double> out = new ArrayList<>(in);
        java.util.Collections.sort(out);
        return out;
    }

    static double quantile(List<Double> sortedAsc, double p) {
        int n = sortedAsc.size();
        if (n == 0) {
            throw new IllegalStateException("分位数需要至少 1 个样本（调用方应先判 K ≥ KITE_ADAPT_MIN_SEG）");
        }
        int ordinal = (int) Math.ceil(p * n);
        int idx = Math.min(n, Math.max(1, ordinal)) - 1;
        return sortedAsc.get(idx);
    }

    public static boolean allWithinBounds(List<Sample> samples) {
        for (Sample s : samples) {
            if (s.deltaSec() < KiteConstants.KITE_DT_MIN_S || s.deltaSec() > KiteConstants.KITE_DT_MAX_S) {
                return false;
            }
        }
        return true;
    }

    public static List<Long> deltas(List<Sample> samples) {
        List<Long> out = new ArrayList<>();
        for (Sample s : samples) {
            out.add(s.deltaSec());
        }
        return Collections.unmodifiableList(out);
    }
}
