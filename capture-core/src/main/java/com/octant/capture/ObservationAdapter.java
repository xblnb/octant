package com.octant.capture;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.EventSource;
import com.octant.common.model.RawEventSchema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ObservationAdapter {

    public static final EventSource SOURCE = EventSource.FORGE;

    private static volatile String lastTranslationFailure;

    public static String lastTranslationFailure() {
        return lastTranslationFailure;
    }

    private ObservationAdapter() {
    }

    public sealed interface Observation permits SessionStart, SessionHeartbeat, SessionEnd,
            AdvancementGained, PlayerDeath, BiomeChanged, CombatStarted, CombatEnded,
            ItemAction, ContainerSnapshot {

        CaptureEventType type();
    }

    public record SessionStart(String sessionStartDate, String privacyClass, String gameVersion,
                               String loader, boolean cheatsEnabled) implements Observation {
        @Override
        public CaptureEventType type() {
            return CaptureEventType.SESSION_START;
        }
    }

    public record SessionHeartbeat(boolean active, long sinceLastInputMs, long tickProgress)
            implements Observation {
        @Override
        public CaptureEventType type() {
            return CaptureEventType.SESSION_HEARTBEAT;
        }
    }

    public record SessionEnd(long wallMs, long activeMs, long afkMs, String afkReason,
                             String closeCause, boolean openSession, int inputEvents,
                             int totalEvents) implements Observation {
        @Override
        public CaptureEventType type() {
            return CaptureEventType.SESSION_END;
        }
    }

    public record AdvancementGained(String advancementId, String parentId, boolean isRecipe,
                                    boolean grantedByCommand) implements Observation {
        @Override
        public CaptureEventType type() {
            return CaptureEventType.ADVANCEMENT_GAINED;
        }
    }

    public record PlayerDeath(String deathCause, String causeClass, String killerEntityType,
                              boolean intentional) implements Observation {
        @Override
        public CaptureEventType type() {
            return CaptureEventType.PLAYER_DEATH;
        }
    }

    public record BiomeChanged(String biome, String dimension, boolean firstVisit)
            implements Observation {
        @Override
        public CaptureEventType type() {
            return CaptureEventType.BIOME_VISITED;
        }
    }

    public record CombatStarted(String opponentKey, String entityType, double opponentThreat,
                                boolean farmPattern, boolean shared, long durMs)
            implements Observation {
        @Override
        public CaptureEventType type() {
            return CaptureEventType.COMBAT_STARTED;
        }
    }

    public record CombatEnded(String opponentKey, String outcome, double damageDealt,
                              double damageTaken, boolean lastHitByPlayer, String resolved,
                              String tactic, long durMs) implements Observation {
        @Override
        public CaptureEventType type() {
            return CaptureEventType.COMBAT_ENDED;
        }
    }

    public record ItemAction(String item, String action, int count, boolean creativeGiven)
            implements Observation {
        @Override
        public CaptureEventType type() {
            return CaptureEventType.ITEM_ACTION;
        }
    }

    public record ContainerSnapshot(String containerKey, java.util.List<java.util.Map<String, Object>> itemsDigest,
                                    boolean truncated) implements Observation {
        @Override
        public CaptureEventType type() {
            return CaptureEventType.CONTAINER_SNAPSHOT;
        }
    }

    public static CaptureEvent translate(Observation obs, String playerKey, String sessionId,
                                         int eventSeq, long tRelMs) {
        return translate(obs, playerKey, sessionId, eventSeq, tRelMs, null);
    }

    public static CaptureEvent translate(Observation obs, String playerKey, String sessionId,
                                         int eventSeq, long tRelMs, Long durMs) {
        if (obs == null) {
            return null;
        }
        try {
            Map<String, Object> payload = payloadOf(obs);
            return new CaptureEvent(
                    RawEventSchema.VERSION,
                    CaptureEvent.eventId(clampSeq(eventSeq)),
                    CaptureEvent.sessionId(parseSessionSeq(sessionId)),
                    playerKey,
                    obs.type(),
                    tRelMs,
                    CaptureEvent.tickOf(tRelMs),
                    obs.type().category(),
                    SOURCE,
                    true,
                    durMs,
                    payload);
        } catch (RuntimeException ex) {
            lastTranslationFailure = obs.getClass().getSimpleName() + " → "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            return null;
        }
    }

    private static Map<String, Object> payloadOf(Observation obs) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (obs instanceof SessionStart s) {
            p.put("privacyClass", s.privacyClass());
            p.put("gameVersion", s.gameVersion());
            p.put("loader", s.loader());
            p.put("sessionStartDate", s.sessionStartDate());
            p.put("cheatsEnabled", s.cheatsEnabled());
        } else if (obs instanceof SessionHeartbeat h) {
            p.put("activity", h.active() ? "active" : "idle");
            p.put("sinceLastInputMs", h.sinceLastInputMs());
            p.put("tickProgress", h.tickProgress());
        } else if (obs instanceof SessionEnd e) {
            p.put("wallMs", e.wallMs());
            p.put("activeMs", e.activeMs());
            p.put("afkMs", e.afkMs());
            p.put("afkReason", e.afkReason());
            p.put("closeCause", e.closeCause());
            p.put("openSession", e.openSession());
            p.put("inputEvents", e.inputEvents());
            p.put("totalEvents", e.totalEvents());
        } else if (obs instanceof AdvancementGained a) {
            p.put("advancementId", a.advancementId());
            p.put("parentId", a.parentId());
            p.put("isRecipe", a.isRecipe());
            p.put("grantedByCommand", a.grantedByCommand());
        } else if (obs instanceof PlayerDeath d) {
            p.put("deathCause", d.deathCause());
            p.put("causeClass", d.causeClass());
            p.put("killerEntityType", d.killerEntityType());
            p.put("intentional", d.intentional());
        } else if (obs instanceof BiomeChanged b) {
            p.put("biome", b.biome());
            p.put("dimension", b.dimension());
            p.put("firstVisit", b.firstVisit());
        } else if (obs instanceof CombatStarted c) {
            p.put("opponentKey", c.opponentKey());
            p.put("entityType", c.entityType());
            p.put("opponentThreat", c.opponentThreat());
            p.put("farmPattern", c.farmPattern());
            p.put("shared", c.shared());
            p.put("durMs", c.durMs());
        } else if (obs instanceof CombatEnded c) {
            p.put("opponentKey", c.opponentKey());
            p.put("outcome", c.outcome());
            p.put("damageDealt", c.damageDealt());
            p.put("damageTaken", c.damageTaken());
            p.put("lastHitByPlayer", c.lastHitByPlayer());
            p.put("resolved", c.resolved());
            p.put("tactic", c.tactic());
            p.put("durMs", c.durMs());
        } else if (obs instanceof ItemAction i) {
            p.put("item", i.item());
            p.put("action", i.action());
            p.put("count", i.count());
            p.put("creativeGiven", i.creativeGiven());
        } else if (obs instanceof ContainerSnapshot c) {
            p.put("containerKey", c.containerKey());
            p.put("itemsDigest", c.itemsDigest());
            p.put("truncated", c.truncated());
        } else {
            throw new IllegalArgumentException("未登记的观测类型：" + obs.getClass().getName());
        }
        return p;
    }

    public static Set<CaptureEventType> unwiredTypes() {
        return Set.of(
                CaptureEventType.SESSION_ENVIRONMENT,
                CaptureEventType.QUEST_COMPLETED,
                CaptureEventType.QUEST_PROGRESSED,
                CaptureEventType.DIMENSION_ENTERED,
                CaptureEventType.STRUCTURE_ENTERED,
                CaptureEventType.REGION_FIRST_VISIT,
                CaptureEventType.RECIPE_UNLOCKED,
                CaptureEventType.RECIPE_ATTEMPTED,
                CaptureEventType.MACHINE_OBSERVED,
                CaptureEventType.MACHINE_INTERACTED,
                CaptureEventType.AUTOMATION_CYCLE,
                CaptureEventType.STALL_SEGMENT,
                CaptureEventType.REPEATED_FAILURE,
                CaptureEventType.RESOURCE_BLOCKED);
    }

    public static Set<CaptureEventType> wiredTypes() {
        return Set.of(
                CaptureEventType.SESSION_START,
                CaptureEventType.SESSION_HEARTBEAT,
                CaptureEventType.SESSION_END,
                CaptureEventType.ADVANCEMENT_GAINED,
                CaptureEventType.PLAYER_DEATH,
                CaptureEventType.BIOME_VISITED,
                CaptureEventType.ITEM_ACTION,
                CaptureEventType.CONTAINER_SNAPSHOT,
                CaptureEventType.COMBAT_STARTED,
                CaptureEventType.COMBAT_ENDED);
    }

    private static int clampSeq(int seq) {
        if (seq < 1) {
            return 1;
        }
        return Math.min(seq, 999);
    }

    private static int parseSessionSeq(String sessionId) {
        if (sessionId == null || sessionId.length() < 2 || sessionId.charAt(0) != 's') {
            throw new IllegalArgumentException("会话 ID 形态非法：" + sessionId);
        }
        return Integer.parseInt(sessionId.substring(1));
    }
}
