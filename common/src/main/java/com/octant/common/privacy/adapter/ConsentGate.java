package com.octant.common.privacy.adapter;

import com.octant.common.model.ContractException;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class ConsentGate {

    private final LongSupplier clock;
    private volatile ConsentState state;
    private final AtomicLong droppedByConsent = new AtomicLong();

    public ConsentGate() {
        this(ConsentState.DENIED, System::currentTimeMillis);
    }

    public ConsentGate(ConsentState initial, LongSupplier clock) {
        this.state = Objects.requireNonNull(initial, "initial");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean isCollecting() {
        return state.collectionEnabled();
    }

    public boolean allowsCategory(String categoryId) {
        return state.allowsCategory(categoryId);
    }

    public Set<String> grantedCategories() {
        return state.collectionCategories();
    }

    public ConsentState state() {
        return state;
    }

    public boolean assertCollecting() {
        return isCollecting();
    }

    public void recordDroppedByConsent() {
        droppedByConsent.incrementAndGet();
    }

    public long droppedByConsent() {
        return droppedByConsent.get();
    }

    public synchronized ConsentState grant(String privacyInstanceId, Set<String> categories,
                                           long maxBytes, int maxAgeDays) {
        ConsentState next = ConsentState.granted(privacyInstanceId, categories,
                utcDate(clock.getAsLong()), maxBytes, maxAgeDays);
        state = next;
        return next;
    }

    public synchronized ConsentState revoke() {
        state = state.revoked(utcDate(clock.getAsLong()));
        return state;
    }

    public synchronized void refresh(ConsentState next) {
        state = Objects.requireNonNull(next, "next");
    }

    public static String utcDate(long epochMillis) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(epochMillis);
        return java.time.LocalDate.ofInstant(instant, java.time.ZoneOffset.UTC).toString();
    }

    public static void requireCollectingOrThrow() {
        throw new ContractException("同意门关闭：禁止任何 L0 写入（dc §2.9）");
    }
}
