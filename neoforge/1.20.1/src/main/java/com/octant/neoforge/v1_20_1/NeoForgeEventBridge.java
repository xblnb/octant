package com.octant.neoforge.v1_20_1;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.ContractException;
import com.octant.common.platforms.PlatformAdapter;

import java.util.concurrent.atomic.AtomicLong;

public final class NeoForgeEventBridge {

    private final PlatformAdapter.EventSink sink;
    private final AtomicLong rejectedByConsent = new AtomicLong();
    private final AtomicLong translationFailures = new AtomicLong();

    public NeoForgeEventBridge(PlatformAdapter.EventSink sink) {
        this.sink = java.util.Objects.requireNonNull(sink, "sink");
    }

    public boolean submit(CaptureEvent event) {
        if (event == null) {
            throw new ContractException("事件不得为空（翻译失败应由翻译器返回 null 并计数，而不是走到这里）");
        }
        boolean accepted = sink.emit(event);
        if (!accepted) {
            rejectedByConsent.incrementAndGet();
        }
        return accepted;
    }

    public boolean translateAndSubmit(PlatformAdapter.EventTranslator translator, Object observation) {
        CaptureEvent event;
        try {
            event = translator.translate(observation);
        } catch (RuntimeException ex) {
            translationFailures.incrementAndGet();
            return false;
        }
        if (event == null) {
            translationFailures.incrementAndGet();
            return false;
        }
        return submit(event);
    }

    public long rejectedByConsent() {
        return rejectedByConsent.get();
    }

    public long translationFailures() {
        return translationFailures.get();
    }
}
