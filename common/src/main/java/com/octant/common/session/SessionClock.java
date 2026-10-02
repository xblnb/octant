package com.octant.common.session;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.ContractException;
import com.octant.common.model.RawEventSchema;

public final class SessionClock {

    private final NanoClock clock;
    private final long anchorNanos;
    private long lastTRelMs;
    private long lastTick;
    private boolean forcedCloseByMaxTrel;
    private boolean forcedCloseBySplitGap;

    public SessionClock() {
        this(System::nanoTime);
    }

    public SessionClock(NanoClock clock) {
        this.clock = clock;
        this.anchorNanos = clock.nanoTime();
        this.lastTRelMs = 0L;
        this.lastTick = 0L;
    }

    public long anchorNanos() {
        return anchorNanos;
    }

    public long observe() {
        if (forcedCloseByMaxTrel || forcedCloseBySplitGap) {
            throw new ContractException("会话已被强制闭合（" + closeCause() + "），不得继续写入事件");
        }
        long raw = (clock.nanoTime() - anchorNanos) / 1_000_000L;
        long candidate = Math.max(raw, lastTRelMs);
        long tRelMs = Math.min(candidate, RawEventSchema.SESSION_MAX_TREL_MS);
        if (tRelMs - lastTRelMs >= RawEventSchema.SESSION_SPLIT_GAP_MS) {
            forcedCloseBySplitGap = true;
            throw new ContractException("相邻事件间隔达到 SESSION_SPLIT_GAP，必须切分新会话");
        }
        markObserved(tRelMs);
        if (tRelMs >= RawEventSchema.SESSION_MAX_TREL_MS) {
            forcedCloseByMaxTrel = true;
        }
        return tRelMs;
    }

    public void markObserved(long tRelMs) {
        if (tRelMs < lastTRelMs) {
            throw new ContractException(
                    "tRelMs 违反单调非递减（§2.2/§2.3.2）：新值 " + tRelMs + " < 上一个 " + lastTRelMs);
        }
        lastTRelMs = tRelMs;
        lastTick = CaptureEvent.tickOf(tRelMs);
    }

    public long lastTRelMs() {
        return lastTRelMs;
    }

    public long lastTick() {
        return lastTick;
    }

    public boolean forcedCloseByMaxTrel() {
        return forcedCloseByMaxTrel;
    }

    public boolean forcedCloseBySplitGap() {
        return forcedCloseBySplitGap;
    }

    public String closeCause() {
        if (forcedCloseByMaxTrel) {
            return "max_trel";
        }
        if (forcedCloseBySplitGap) {
            return "split_gap";
        }
        return "";
    }

    public static long tickOf(long tRelMs) {
        return CaptureEvent.tickOf(tRelMs);
    }

    @FunctionalInterface
    public interface NanoClock {
        long nanoTime();
    }
}
