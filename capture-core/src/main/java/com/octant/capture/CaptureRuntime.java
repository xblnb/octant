package com.octant.capture;

import com.octant.common.model.CaptureEvent;
import com.octant.common.privacy.SaltProvider;
import com.octant.common.privacy.adapter.ConsentGate;
import com.octant.common.privacy.adapter.ConsentState;
import com.octant.common.session.RawEventStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CaptureRuntime {

    private static final Logger LOG = Logger.getLogger(CaptureRuntime.class.getName());

    private static final long HEARTBEAT_ACTIVE_MS = 30_000L;

    private static final long TICK_MS = 50L;

    private final OctantHost host;
    private final ConsentGate gate;
    private final PrivacyConsent consent;
    private final RawEventStore store;
    private final SaltProvider salt;

    private final AtomicLong written = new AtomicLong();
    private final AtomicLong translationFailures = new AtomicLong();
    private final AtomicLong inputEvents = new AtomicLong();
    private final AtomicLong droppedByConsent = new AtomicLong();
    private final AtomicLong writeRefused = new AtomicLong();
    private final AtomicLong writeFailed = new AtomicLong();
    private final AtomicLong stampRaised = new AtomicLong();
    private volatile String lastWriteFailure;

    private final AtomicLong stampShiftedIntervals = new AtomicLong();

    private final Map<UUID, PlayerSession> sessions = new HashMap<>();

    private int nextSessionSeq;

    private final java.util.Set<String> knownPlayerKeys = new java.util.LinkedHashSet<>();

    public CaptureRuntime(OctantHost host) {
        this.host = host;
        this.gate = new ConsentGate(ConsentState.denied(), System::currentTimeMillis);
        this.consent = new PrivacyConsent(host.gameDir(), host.worldDir(), gate);
        this.store = new RawEventStore(host.worldDir());
        this.salt = loadOrCreateSalt(com.octant.common.privacy.OctantPaths.dataDir(host.worldDir()).resolve("meta").resolve("salt.bin"));
        this.consent.reload();
        this.store.recover();
        this.nextSessionSeq = deriveNextSessionSeq(store.eventsRoot());
    }

    public PrivacyConsent consent() {
        return consent;
    }

    public RawEventStore store() {
        return store;
    }

    public long writtenCount() {
        return written.get();
    }

    public long translationFailureCount() {
        return translationFailures.get();
    }

    public long droppedByConsentCount() {
        return droppedByConsent.get();
    }

    public long inputEventCount() {
        return inputEvents.get();
    }

    public int activeSessions() {
        return sessions.size();
    }

    private PlayerSession activeSession() {
        if (sessions.isEmpty()) {
            return null;
        }
        return sessions.entrySet().stream()
                .min(java.util.Map.Entry.comparingByKey())
                .map(java.util.Map.Entry::getValue)
                .orElse(null);
    }

    public boolean collecting() {
        return gate.isCollecting();
    }

    public List<String> knownPlayerKeys() {
        return List.copyOf(knownPlayerKeys);
    }

    public Map<String, Object> statusMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("collecting", collecting());
        m.put("lastTranslationFailure", ObservationAdapter.lastTranslationFailure());
        m.put("translationFailures", translationFailures.get());
        m.put("stampShiftedIntervals", stampShiftedIntervals.get());
        m.put("stampRaised", stampRaised.get());
        m.put("writeRefused", writeRefused.get());
        m.put("writeFailed", writeFailed.get());
        m.put("lastWriteFailure", lastWriteFailure);
        m.put("droppedTotal", droppedByConsent.get() + writeRefused.get() + writeFailed.get()
                + translationFailures.get());
        PlayerSession cur = activeSession();
        m.put("currentSessionId", cur == null ? null : cur.sessionId);
        m.put("currentEventSeq", cur == null ? 0 : cur.nextEventSeq - 1);
        m.put("consentVersion", gate.state().consentVersion());
        m.put("categories", List.copyOf(gate.state().collectionCategories()));
        m.put("eventsWritten", written.get());
        m.put("droppedByConsent", droppedByConsent.get());
        m.put("inputEvents", inputEvents.get());
        m.put("activeSessions", sessions.size());
        m.put("wiredTypes", ObservationAdapter.wiredTypes().size());
        m.put("unwiredTypes", ObservationAdapter.unwiredTypes().size());
        return m;
    }

    public synchronized PlayerSession onPlayerJoin(UUID uuid) {
        PlayerSession existing = sessions.get(uuid);
        if (existing != null) {
            return existing;
        }
        String playerKey = playerKeyOf(uuid);
        knownPlayerKeys.add(playerKey);
        PlayerSession s = new PlayerSession(uuid, playerKey, CaptureEvent.sessionId(nextSessionSeq++));
        sessions.put(uuid, s);
        String date = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(System.currentTimeMillis()));
        emit(s, new ObservationAdapter.SessionStart(
                date, host.privacyClass(), host.gameVersion(), host.loader(), host.cheatsEnabled()));
        return s;
    }

    public synchronized void onPlayerLeave(UUID uuid) {
        PlayerSession s = sessions.remove(uuid);
        if (s == null) {
            return;
        }
        long activeMs = s.relMs();
        emitClosedAll(s, s.combat.closeAll(activeMs, "interrupted"));
        s.combat.reset();
        if (activeMs < 60_000L) {
            return;
        }
        emit(s, new ObservationAdapter.SessionEnd(
                activeMs, activeMs, 0L, "none", "logout", false,
                s.inputEvents, s.eventCount));
    }

    public synchronized void noteWorldChanged(UUID uuid) {
        PlayerSession s = sessions.get(uuid);
        if (s != null) {
            emitClosedAll(s, s.combat.closeAll(s.relMs(), "interrupted"));
            s.biomesSeen.clear();
        }
    }

    public synchronized void onServerTick(UUID uuid) {
        PlayerSession s = sessions.get(uuid);
        if (s == null) {
            return;
        }
        s.ticks++;
        long now = s.relMs();
        emitClosedAll(s, s.combat.due(now));
        if (now - s.lastHeartbeatAtMs >= HEARTBEAT_ACTIVE_MS) {
            s.lastHeartbeatAtMs = now;
            emit(s, new ObservationAdapter.SessionHeartbeat(
                    true, s.sinceLastInputMs(now), s.ticks));
        }
    }

    public synchronized void recordInput(UUID uuid) {
        PlayerSession s = sessions.get(uuid);
        if (s != null) {
            s.lastInputAtMs = s.relMs();
        }
        inputEvents.incrementAndGet();
    }

    public synchronized void onAdvancement(UUID uuid, String advancementId, String parentId,
                                           boolean isRecipe, boolean grantedByCommand) {
        PlayerSession s = sessions.get(uuid);
        if (s == null) {
            return;
        }
        emit(s, new ObservationAdapter.AdvancementGained(
                advancementId, parentId, isRecipe, grantedByCommand));
    }

    public synchronized void onPlayerDeath(UUID uuid, String deathCause, String causeClass,
                                           String killerEntityType, boolean intentional) {
        PlayerSession s = sessions.get(uuid);
        if (s == null) {
            return;
        }
        emit(s, new ObservationAdapter.PlayerDeath(
                deathCause, causeClass, killerEntityType, intentional));
        emitClosedAll(s, s.combat.closeAll(s.relMs(), "player_death"));
    }

    public synchronized void onBiome(UUID uuid, String biome, String dimension) {
        PlayerSession s = sessions.get(uuid);
        if (s == null) {
            return;
        }
        boolean first = s.biomesSeen.add(biome);
        if (!first) {
            return;
        }
        emit(s, new ObservationAdapter.BiomeChanged(biome, dimension, true));
    }

    public synchronized void onPlayerAttack(UUID playerUuid, String targetId, String entityType,
                                           double threatContribution, double damage, boolean ranged) {
        PlayerSession s = sessions.get(playerUuid);
        if (s == null) {
            return;
        }
        s.combat.onPlayerHit(targetId, entityType, threatContribution, damage, ranged, s.relMs());
    }

    public synchronized void onForeignAttack(String targetId, String contributorId,
                                             double threatContribution) {
        for (PlayerSession s : sessions.values()) {
            s.combat.onForeignHit(targetId, contributorId, threatContribution, s.relMs());
        }
    }

    public synchronized void onPlayerHurt(UUID playerUuid, String attackerId, double damage) {
        PlayerSession s = sessions.get(playerUuid);
        if (s == null) {
            return;
        }
        s.combat.onPlayerHurt(attackerId, damage, s.relMs());
    }

    public synchronized void onOpponentDeath(UUID playerUuid, String targetId) {
        PlayerSession s = sessions.get(playerUuid);
        if (s == null) {
            return;
        }
        emitClosed(s, s.combat.onOpponentDeath(targetId, s.relMs()));
    }

    public synchronized void onItemAction(UUID playerUuid, String itemId, String action,
                                         int count, boolean creativeGiven) {
        PlayerSession s = sessions.get(playerUuid);
        if (s == null) {
            return;
        }
        emit(s, new ObservationAdapter.ItemAction(itemId, action, count, creativeGiven));
    }

    public synchronized void onContainerSnapshot(UUID playerUuid, String containerKey,
                                                java.util.List<java.util.Map<String, Object>> itemsDigest,
                                                boolean truncated) {
        PlayerSession s = sessions.get(playerUuid);
        if (s == null) {
            return;
        }
        emit(s, new ObservationAdapter.ContainerSnapshot(containerKey, itemsDigest, truncated));
    }

    private void emitClosed(PlayerSession s, CombatEncounters.Closed c) {
        if (c == null) {
            return;
        }
        long startT = c.startMs();
        if (c.durMs() > startT) {
            startT = c.endMs();
            stampShiftedIntervals.incrementAndGet();
        }
        emitAt(s, new ObservationAdapter.CombatStarted(c.opponentKey(), c.entityType(),
                c.opponentThreat(), c.farmPattern(), c.shared(), c.durMs()), startT, c.durMs());
        emitAt(s, new ObservationAdapter.CombatEnded(c.opponentKey(), c.outcome(), c.damageDealt(),
                        c.damageTaken(), c.lastHitByPlayer(), c.resolved(), c.tactic(), c.durMs()),
                c.endMs(), c.durMs());
    }

    private void emitClosedAll(PlayerSession s, List<CombatEncounters.Closed> closed) {
        for (CombatEncounters.Closed c : closed) {
            emitClosed(s, c);
        }
    }

    private boolean emit(PlayerSession s, ObservationAdapter.Observation obs) {
        return emitAt(s, obs, s.relMs(), null);
    }

    private boolean emitAt(PlayerSession s, ObservationAdapter.Observation obs,
                           long tRelMs, Long durMs) {
        int seq = s.nextEventSeq;
        s.nextEventSeq = Math.min(999, s.nextEventSeq + 1);
        s.eventCount = Math.min(999, s.eventCount + 1);

        long wantT = tRelMs;
        long lastWritten = store.lastTRelMs();
        long useT = com.octant.common.session.StreamStamp.monotonic(wantT, lastWritten);
        if (com.octant.common.session.StreamStamp.wasRaised(wantT, lastWritten)) {
            stampRaised.incrementAndGet();
        }

        CaptureEvent event = ObservationAdapter.translate(obs, s.playerKey, s.sessionId, seq, useT, durMs);
        if (event == null) {
            translationFailures.incrementAndGet();
            return false;
        }
        if (!gate.assertCollecting()) {
            gate.recordDroppedByConsent();
            droppedByConsent.incrementAndGet();
            return false;
        }
        try {
            long bytes = store.append(event, seq);
            if (bytes > 0) {
                written.incrementAndGet();
            } else {
                writeRefused.incrementAndGet();
                lastWriteFailure = "store.append 返回 0（超硬上限 / 单条超长 / 写盘异常），"
                        + "事件 " + event.type().eventId() + "，seq=" + seq;
                LOG.warning(() -> "[octant] 事件写入被拒：" + lastWriteFailure);
            }
        } catch (RuntimeException ex) {
            writeFailed.incrementAndGet();
            lastWriteFailure = event.type().eventId() + " → "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            LOG.warning(() -> "[octant] 事件写入失败（已计数、未上抛）：" + lastWriteFailure);
            return false;
        }
        return true;
    }

    public String playerKeyOf(UUID uuid) {
        return salt.pseudonymizeUuid(uuid.toString());
    }

    public String saltSeedForRedaction() {
        String material = knownPlayerKeys.isEmpty()
                ? "no-player-yet" : String.join(",", knownPlayerKeys);
        return salt.pseudonym("export_salt", material);
    }

    private static SaltProvider loadOrCreateSalt(Path saltFile) {
        try {
            return SaltProvider.loadOrCreate(saltFile);
        } catch (IOException ex) {
            LOG.log(Level.WARNING,
                    "每存档假名盐不可读写，退化为内存态盐（跨会话关联本存档将失效）：" + saltFile, ex);
            return SaltProvider.generate();
        }
    }

    private static int deriveNextSessionSeq(Path eventsRoot) {
        int max = 0;
        if (!Files.isDirectory(eventsRoot)) {
            return 1;
        }
        try (var playerDirs = Files.list(eventsRoot)) {
            for (Path playerDir : (Iterable<Path>) playerDirs::iterator) {
                if (!Files.isDirectory(playerDir)) {
                    continue;
                }
                try (var files = Files.list(playerDir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        String n = f.getFileName().toString();
                        if (!n.endsWith(".jsonl")) {
                            continue;
                        }
                        String base = n.substring(0, n.length() - ".jsonl".length());
                        int dash = base.indexOf('-');
                        String idPart = dash < 0 ? base : base.substring(0, dash);
                        if (idPart.length() > 1 && idPart.charAt(0) == 's') {
                            try {
                                max = Math.max(max, Integer.parseInt(idPart.substring(1)));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        } catch (IOException ex) {
            LOG.log(Level.FINE, "会话序号推导失败，从 1 开始", ex);
        }
        return Math.min(9999, max + 1);
    }

    public static final class PlayerSession {
        private final UUID uuid;
        private final String playerKey;
        private final String sessionId;
        private long ticks;
        private long lastInputAtMs;
        private long lastHeartbeatAtMs;
        private int nextEventSeq = 1;
        private int eventCount;
        int inputEvents;
        private final java.util.Set<String> biomesSeen = new java.util.LinkedHashSet<>();

        private final com.octant.common.session.OpponentKeyAllocator opponentKeys =
                new com.octant.common.session.OpponentKeyAllocator();

        final CombatEncounters combat = new CombatEncounters(opponentKeys);

        PlayerSession(UUID uuid, String playerKey, String sessionId) {
            this.uuid = uuid;
            this.playerKey = playerKey;
            this.sessionId = sessionId;
        }

        public UUID uuid() {
            return uuid;
        }

        public String playerKey() {
            return playerKey;
        }

        public String sessionId() {
            return sessionId;
        }

        public long relMs() {
            return ticks * TICK_MS;
        }

        public long ticks() {
            return ticks;
        }

        public long sinceLastInputMs(long nowMs) {
            return Math.max(0L, nowMs - lastInputAtMs);
        }

        public int eventCount() {
            return eventCount;
        }
    }

    public synchronized Optional<PlayerSession> sessionOf(UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }
}
