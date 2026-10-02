package com.octant.capture;

import com.octant.common.session.OpponentKeyAllocator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CombatEncounters {

    static final long TIMEOUT_MS = 15_000L;

    static final long FARM_WINDOW_MS = 60_000L;
    static final int FARM_MIN_ENCOUNTERS = 3;

    record Closed(
            String opponentKey, String entityType, double opponentThreat,
            boolean farmPattern, boolean shared,
            long startMs, long endMs, long durMs,
            String outcome, String resolved, String tactic,
            boolean lastHitByPlayer,
            double damageDealt, double damageTaken) {
    }

    private static final class Open {
        final String opponentKey;
        final String entityType;
        final long startMs;
        final Map<String, Double> contributors = new LinkedHashMap<>();
        double damageDealt;
        double damageTaken;
        boolean lastHitByPlayer;
        boolean ranged;
        long lastHitMs;

        Open(String opponentKey, String entityType, long startMs) {
            this.opponentKey = opponentKey;
            this.entityType = entityType;
            this.startMs = startMs;
            this.lastHitMs = startMs;
        }

        double threat() {
            double sum = 0.0d;
            for (double v : contributors.values()) {
                sum += v;
            }
            return sum;
        }

        boolean isShared() {
            for (String k : contributors.keySet()) {
                if (k.startsWith("f:")) {
                    return true;
                }
            }
            return false;
        }
    }

    private final OpponentKeyAllocator allocator;
    private final Map<String, Open> open = new LinkedHashMap<>();
    private final Map<String, String> keyOfEntity = new LinkedHashMap<>();
    private final Map<String, List<Long>> recentByType = new LinkedHashMap<>();

    CombatEncounters(OpponentKeyAllocator allocator) {
        this.allocator = java.util.Objects.requireNonNull(allocator, "allocator");
    }

    boolean hasOpen() {
        return !open.isEmpty();
    }

    int openCount() {
        return open.size();
    }

    void onPlayerHit(String entityIdKey, String entityType, double threatContribution,
                     double damage, boolean ranged, long nowMs) {
        Open o = open.get(entityIdKey);
        if (o == null) {
            String key = keyOfEntity.computeIfAbsent(entityIdKey, k -> allocator.allocate(entityType));
            o = new Open(key, entityType, nowMs);
            open.put(entityIdKey, o);
            recentByType.computeIfAbsent(entityType, k -> new ArrayList<>()).add(nowMs);
        }
        o.contributors.putIfAbsent(entityIdKey, Math.max(0.0d, threatContribution));
        o.damageDealt += Math.max(0.0d, damage);
        o.lastHitByPlayer = true;
        o.ranged = ranged;
        o.lastHitMs = nowMs;
    }

    void onForeignHit(String entityIdKey, String contributorKey, double threatContribution, long nowMs) {
        Open o = open.get(entityIdKey);
        if (o == null) {
            return;
        }
        o.contributors.putIfAbsent("f:" + contributorKey, Math.max(0.0d, threatContribution));
        o.lastHitMs = nowMs;
    }

    void onPlayerHurt(String entityIdKey, double damage, long nowMs) {
        Open o = open.get(entityIdKey);
        if (o != null) {
            o.damageTaken += Math.max(0.0d, damage);
            o.lastHitMs = nowMs;
        }
    }

    Closed onOpponentDeath(String entityIdKey, long nowMs) {
        Open o = open.remove(entityIdKey);
        return o == null ? null : close(o, nowMs, "kill");
    }

    List<Closed> due(long nowMs) {
        List<Closed> out = new ArrayList<>();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Open> en : open.entrySet()) {
            if (nowMs - en.getValue().lastHitMs >= TIMEOUT_MS) {
                expired.add(en.getKey());
            }
        }
        for (String k : expired) {
            Open o = open.remove(k);
            if (o != null) {
                out.add(close(o, nowMs, "timeout"));
            }
        }
        return out;
    }

    List<Closed> closeAll(long nowMs, String outcome) {
        List<Closed> out = new ArrayList<>();
        for (String k : new ArrayList<>(open.keySet())) {
            Open o = open.remove(k);
            if (o != null) {
                out.add(close(o, nowMs, outcome));
            }
        }
        return out;
    }

    void reset() {
        open.clear();
        keyOfEntity.clear();
        recentByType.clear();
    }

    private Closed close(Open o, long nowMs, String outcome) {
        long dur = Math.max(1L, nowMs - o.startMs);
        String resolved;
        if ("kill".equals(outcome)) {
            resolved = "resolved";
        } else if ("player_death".equals(outcome)) {
            resolved = "unresolved";
        } else {
            resolved = o.damageDealt > 0.0d ? "partial" : "unresolved";
        }
        String tactic = "";
        if (o.damageDealt > 0.0d) {
            tactic = o.ranged ? "ranged" : "melee";
        }
        return new Closed(o.opponentKey, o.entityType, o.threat(), isFarm(o, nowMs), o.isShared(),
                o.startMs, nowMs, dur, outcome, resolved, tactic, o.lastHitByPlayer,
                o.damageDealt, o.damageTaken);
    }

    private boolean isFarm(Open o, long nowMs) {
        List<Long> times = recentByType.get(o.entityType);
        if (times == null) {
            return false;
        }
        int n = 0;
        for (long t : times) {
            if (nowMs - t <= FARM_WINDOW_MS) {
                n++;
            }
        }
        return n >= FARM_MIN_ENCOUNTERS;
    }
}
