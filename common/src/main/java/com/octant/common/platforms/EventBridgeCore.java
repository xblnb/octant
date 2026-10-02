package com.octant.common.platforms;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.ContractException;

import java.util.concurrent.atomic.AtomicLong;

public final class EventBridgeCore {

    private final PlatformAdapter.EventSink sink;
    private final AtomicLong rejectedByConsent = new AtomicLong();
    private final AtomicLong translationFailures = new AtomicLong();
    private final AtomicLong submitted = new AtomicLong();

    public EventBridgeCore(PlatformAdapter.EventSink sink) {
        this.sink = java.util.Objects.requireNonNull(sink, "sink");
    }

    public boolean submit(CaptureEvent event) {
        if (event == null) {
            throw new ContractException(
                    "事件不得为空：翻译失败应由翻译器返回 null 并计数，而不是把 null 送到汇");
        }
        boolean accepted = sink.emit(event);
        if (accepted) {
            submitted.incrementAndGet();
        } else {
            rejectedByConsent.incrementAndGet();
        }
        return accepted;
    }

    public boolean translateAndSubmit(PlatformAdapter.EventTranslator translator, Object observation) {
        if (translator == null) {
            translationFailures.incrementAndGet();
            return false;
        }
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

    public long submitted() {
        return submitted.get();
    }

    public long rejectedByConsent() {
        return rejectedByConsent.get();
    }

    public long translationFailures() {
        return translationFailures.get();
    }
}
