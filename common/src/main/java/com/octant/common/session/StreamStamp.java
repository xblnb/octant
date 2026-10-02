package com.octant.common.session;

public final class StreamStamp {

    private StreamStamp() {
    }

    public static long monotonic(long desired, long lastWritten) {
        if (lastWritten == Long.MIN_VALUE) {
            return desired;
        }
        return Math.max(desired, lastWritten);
    }

    public static boolean wasRaised(long desired, long lastWritten) {
        return lastWritten != Long.MIN_VALUE && desired < lastWritten;
    }
}
