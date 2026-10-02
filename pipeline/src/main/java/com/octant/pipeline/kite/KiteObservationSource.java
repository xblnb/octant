package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class KiteObservationSource {

    private static final Map<String, List<String>> WHITELIST = whitelist();

    private final List<String> eventNames;
    private final long originMs;
    private final int progressCatalog;
    private final int placeCatalog;
    private final Map<String, Integer> maxStageByQuest;
    private final List<IndexedEvent> indexed;
    private final int sessionCount;
    private final List<Map<String, Object>> rawEvents;

    private record IndexedEvent(long tRelMs, String type, String eventId, String sessionId,
                                Map<String, Object> payload, boolean confirmed) {
    }

    private KiteObservationSource(long originMs, int progressCatalog, int placeCatalog,
                                  Map<String, Integer> maxStageByQuest, List<IndexedEvent> indexed,
                                  int sessionCount, List<Map<String, Object>> rawEvents) {
        this.originMs = originMs;
        this.progressCatalog = progressCatalog;
        this.placeCatalog = placeCatalog;
        this.maxStageByQuest = Collections.unmodifiableMap(maxStageByQuest);
        this.indexed = Collections.unmodifiableList(indexed);
        this.sessionCount = sessionCount;
        this.rawEvents = Collections.unmodifiableList(rawEvents);
        List<String> names = new ArrayList<>();
        for (IndexedEvent e : indexed) {
            names.add(e.type());
        }
        this.eventNames = Collections.unmodifiableList(names);
    }

    public List<Map<String, Object>> rawEvents() {
        return rawEvents;
    }

    public static KiteObservationSource of(List<Map<String, Object>> events, long origin) {
        List<IndexedEvent> tmp = new ArrayList<>();
        Map<String, Integer> maxStage = new TreeMap<>();
        Set<String> advantageIds = new LinkedHashSet<>();
        Set<String> placeKeys = new LinkedHashSet<>();
        Set<String> sessions = new LinkedHashSet<>();
        for (Map<String, Object> raw : events) {
            String type = str(raw.get("type"));
            Object t = raw.get("tRelMs");
            long tRel = t instanceof Number n ? n.longValue() : 0L;
            String eventId = str(raw.get("eventId"));
            boolean confirmed = !(raw.get("confirmed") instanceof Boolean b) || b;
            Map<String, Object> payload = payloadOf(raw.get("payload"));
            String sid = str(raw.get("sessionId"));
            tmp.add(new IndexedEvent(tRel, type, eventId, sid, payload, confirmed));
            if (sid != null) {
                sessions.add(sid);
            }
            if ("advancement_gained".equals(type)) {
                String id = str(payload.get("advancementId"));
                if (!id.isEmpty()) {
                    advantageIds.add(id);
                }
            }
            String place = placeKeyOf(type, payload);
            if (place != null) {
                placeKeys.add(place);
            }
            if ("quest_progressed".equals(type)) {
                String q = str(payload.get("questId"));
                Object st = payload.get("stage");
                if (!q.isEmpty() && st instanceof Number n) {
                    maxStage.merge(q, n.intValue(), Math::max);
                }
            }
        }
        tmp.sort(Comparator.comparingLong(IndexedEvent::tRelMs)
                .thenComparing(IndexedEvent::type)
                .thenComparing(IndexedEvent::eventId));
        return new KiteObservationSource(origin, advantageIds.size(), placeKeys.size(), maxStage, tmp,
                sessions.size(), new ArrayList<>(events));
    }

    public long originMs() {
        return originMs;
    }

    public int eventCount() {
        return indexed.size();
    }

    public int sessionCount() {
        return sessionCount;
    }

    public int progressCatalog() {
        return progressCatalog;
    }

    public int placeCatalog() {
        return placeCatalog;
    }

    public List<String> sortedEventNames() {
        return eventNames;
    }

    public String timebaseFingerprint() {
        StringBuilder sb = new StringBuilder();
        for (IndexedEvent e : indexed) {
            sb.append(e.tRelMs()).append('|').append(e.type()).append('|').append(e.eventId()).append('\n');
        }
        return sb.toString();
    }

    public List<long[]> activeSpans() {
        Map<String, long[]> bySession = new LinkedHashMap<>();
        for (IndexedEvent e : indexed) {
            String sid = e.sessionId();
            long[] r = bySession.get(sid);
            if (r == null) {
                bySession.put(sid, new long[] {e.tRelMs(), e.tRelMs() + 1L});
            } else {
                r[0] = Math.min(r[0], e.tRelMs());
                r[1] = Math.max(r[1], e.tRelMs() + 1L);
            }
        }
        List<long[]> spans = new ArrayList<>();
        for (long[] r : bySession.values()) {
            spans.add(new long[] {r[0] / 1000L, (long) Math.ceil(r[1] / 1000d)});
        }
        spans.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        for (int i = 1; i < spans.size(); i++) {
            if (spans.get(i)[0] < spans.get(i - 1)[1]) {
                throw new IllegalStateException("会话活跃跨度重叠："
                        + java.util.Arrays.toString(spans.get(i - 1)) + " 与 " + java.util.Arrays.toString(spans.get(i))
                        + " ⇒ 活跃时长口径不唯一，不得继续（应显式抑制而不是猜）");
            }
        }
        return Collections.unmodifiableList(spans);
    }

    public List<KiteObservation> observations(long startSec, long lenSec, boolean sourceIsSnapshotOnly) {
        long startMs = originMs + startSec * 1000L;
        long endMs = startMs + lenSec * 1000L;
        List<IndexedEvent> slice = slice(startMs, endMs);
        boolean empty = slice.isEmpty();

        String densityReason = sourceIsSnapshotOnly ? KiteConstants.RC_INPUT_SNAPSHOT_ONLY
                : KiteConstants.RC_INPUT_MISSING;

        List<KiteObservation> out = new ArrayList<>();

        if (sourceIsSnapshotOnly) {
            for (KiteAxis axis : KiteAxis.values()) {
                for (String key : KiteAxisDefinition.derivedKeysOf(axis)) {
                    if (SNAPSHOT_CATALOG_KEYS.contains(key)) {
                        continue;
                    }
                    out.add(KiteObservation.unavailable(axis, key, primitiveOf(key),
                            KiteConstants.RC_INPUT_SNAPSHOT_ONLY));
                }
            }
            out.add(KiteObservation.unavailable(KiteAxis.SPON, "octant.stall.active_event_density",
                    "stall.density_in_interval", KiteConstants.RC_INPUT_SNAPSHOT_ONLY));
            out.add(hasProgressCatalog()
                    ? KiteObservation.of(KiteAxis.PROG, "octant.progress.reachable_units",
                            "progress.advancement_catalog", progressCatalog)
                    : KiteObservation.unavailable(KiteAxis.PROG, "octant.progress.reachable_units",
                            "progress.advancement_catalog", KiteConstants.RC_INPUT_MISSING));
            out.add(hasPlaceCatalog()
                    ? KiteObservation.of(KiteAxis.GUID, "octant.env.dimension_variants",
                            "env.structure_occupancy", placeCatalog)
                    : KiteObservation.unavailable(KiteAxis.GUID, "octant.env.dimension_variants",
                            "env.structure_occupancy", KiteConstants.RC_INPUT_MISSING));
            return Collections.unmodifiableList(out);
        }
        out.add(sourceIsSnapshotOnly
                ? KiteObservation.unavailable(KiteAxis.SPON, "octant.stall.active_event_density",
                        "stall.density_in_interval", densityReason)
                : KiteObservation.of(KiteAxis.SPON, "octant.stall.active_event_density",
                        "stall.density_in_interval", intervalDensity(slice, lenSec)));

        out.add(sourceIsSnapshotOnly
                ? KiteObservation.unavailable(KiteAxis.PROG, "octant.progress.completed_units",
                        "progress.advancement_done", densityReason)
                : KiteObservation.of(KiteAxis.PROG, "octant.progress.completed_units",
                        "progress.advancement_done",
                        KiteAxisProfile.current() == KiteAxisProfile.REDEFINED_PROG_DELTA
                                ? completedUnitsIn(startSec, lenSec)
                                : cumulativeCompletedUnits(endMs)));
        out.add(progressCatalog == 0
                ? KiteObservation.unavailable(KiteAxis.PROG, "octant.progress.reachable_units",
                        "progress.advancement_catalog", KiteConstants.RC_INPUT_MISSING)
                : KiteObservation.of(KiteAxis.PROG, "octant.progress.reachable_units",
                        "progress.advancement_catalog", progressCatalog));

        out.add(sourceIsSnapshotOnly || empty
                ? KiteObservation.unavailable(KiteAxis.SPON, "octant.dispersion.entropy_normalized",
                        "dist.action_class_entropy", densityReason)
                : (KiteAxisProfile.current() == KiteAxisProfile.REDEFINED_2026
                        ? KiteObservation.ofEntityDiversity(KiteAxis.SPON,
                                "octant.dispersion.entropy_normalized", "dist.item_entity_diversity",
                                distinctItemClasses(slice), itemCatalog())
                        : KiteObservation.of(KiteAxis.SPON, "octant.dispersion.entropy_normalized",
                                "dist.action_class_entropy",
                                KiteAxisMath.normalizedEntropy(distinctItemClasses(slice),
                                        KiteAxisMath.SPON_ENTROPY_SATURATION))));
        boolean sponSubComponentsGated = KiteAxisProfile.current() == KiteAxisProfile.REDEFINED_2026
                || KiteAxisProfile.current() == KiteAxisProfile.REDEFINED_PROG_DELTA;
        out.add(sourceIsSnapshotOnly || sponSubComponentsGated
                ? KiteObservation.unavailable(KiteAxis.SPON, "octant.breadth.weighted_coverage",
                        "dist.place_breadth", sourceIsSnapshotOnly ? densityReason : KiteConstants.RC_INPUT_MISSING)
                : KiteObservation.of(KiteAxis.SPON, "octant.breadth.weighted_coverage",
                        "dist.place_breadth", placeCatalog == 0
                                ? 0d
                                : (double) distinctPlaces(slice) / (double) placeCatalog));
        out.add(sourceIsSnapshotOnly || empty || sponSubComponentsGated
                ? KiteObservation.unavailable(KiteAxis.SPON, "octant.pref.category_entropy",
                        "dist.action_class_mix", sourceIsSnapshotOnly ? densityReason : KiteConstants.RC_INPUT_MISSING)
                : KiteObservation.of(KiteAxis.SPON, "octant.pref.category_entropy",
                        "dist.action_class_mix",
                        KiteAxisMath.normalizedEntropy(distinctActionKinds(slice),
                                KiteAxisMath.SPON_MIX_SATURATION)));

        int variants = KiteAxisProfile.current() == KiteAxisProfile.REDEFINED_2026
                || KiteAxisProfile.current() == KiteAxisProfile.REDEFINED_PROG_DELTA
                        ? distinctPlaces(slice) : placeCatalog;
        out.add(placeCatalog == 0
                ? KiteObservation.unavailable(KiteAxis.GUID, "octant.env.dimension_variants",
                        "env.structure_occupancy", KiteConstants.RC_INPUT_MISSING)
                : KiteObservation.of(KiteAxis.GUID, "octant.env.dimension_variants",
                        "env.structure_occupancy", variants));
        double dominant = dominantShare(slice);
        out.add(sourceIsSnapshotOnly || empty
                ? KiteObservation.unavailable(KiteAxis.GUID, "octant.env.dimension_dominant_share",
                        "env.place_concentration", densityReason)
                : KiteObservation.of(KiteAxis.GUID, "octant.env.dimension_dominant_share",
                        "env.place_concentration", dominant));
        Double adherence = adherence(slice);
        out.add(adherence == null
                ? KiteObservation.unavailable(KiteAxis.GUID, "octant.pref.completion_rate_per_hour",
                        "quest.stage_level", KiteConstants.RC_INPUT_MISSING)
                : KiteObservation.of(KiteAxis.GUID, "octant.pref.completion_rate_per_hour",
                        "quest.stage_level", adherence));
        return Collections.unmodifiableList(out);
    }

    static String primitiveOf(String key) {
        String p = KiteAxisDefinition.primitiveByKey().get(key);
        return p == null ? key : p;
    }

    static final Set<String> SNAPSHOT_CATALOG_KEYS = Set.of(
            "octant.progress.reachable_units",
            "octant.env.dimension_variants");

    public static Set<String> snapshotCatalogKeys() {
        return SNAPSHOT_CATALOG_KEYS;
    }

    private boolean hasProgressCatalog() {
        return progressCatalog > 0;
    }

    private boolean hasPlaceCatalog() {
        return placeCatalog > 0;
    }

    public int itemCatalog() {
        Set<String> items = new LinkedHashSet<>();
        for (IndexedEvent e : indexed) {
            if ("item_action".equals(e.type())) {
                String item = str(e.payload().get("item"));
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
        }
        return items.size();
    }

    int cumulativeDistinctItems(long endMs) {
        Set<String> items = new LinkedHashSet<>();
        for (IndexedEvent e : indexed) {
            if (originMs + e.tRelMs() >= endMs) {
                break;
            }
            if ("item_action".equals(e.type())) {
                String item = str(e.payload().get("item"));
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
        }
        return items.size();
    }

    public int progressEventCount() {
        int n = 0;
        for (IndexedEvent e : indexed) {
            if ("advancement_gained".equals(e.type())) {
                n++;
            }
        }
        return n;
    }

    public int progressEventWindowCount() {
        Set<Long> windows = new LinkedHashSet<>();
        for (IndexedEvent e : indexed) {
            if ("advancement_gained".equals(e.type())) {
                windows.add(e.tRelMs() / (KiteConstants.KITE_DT_REF_S * 1000L));
            }
        }
        return windows.size();
    }

    int cumulativeCompletedUnits(long endMs) {
        Set<String> ids = new LinkedHashSet<>();
        for (IndexedEvent e : indexed) {
            if (originMs + e.tRelMs() >= endMs) {
                break;
            }
            if ("advancement_gained".equals(e.type())) {
                String id = str(e.payload().get("advancementId"));
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids.size();
    }

    double intervalDensity(List<IndexedEvent> slice, long lenSec) {
        int n = 0;
        for (IndexedEvent e : slice) {
            if (isWhitelistInput(e.type()) && e.confirmed()) {
                n++;
            }
        }
        long active = activeSeconds(slice);
        double denomMinutes = active > 0L ? active / 60d : lenSec / 60d;
        if (denomMinutes <= 0d) {
            return 0d;
        }
        return n / denomMinutes;
    }

    long activeSeconds(List<IndexedEvent> slice) {
        long first = Long.MIN_VALUE;
        long last = Long.MIN_VALUE;
        for (IndexedEvent e : slice) {
            if (!e.confirmed()) {
                continue;
            }
            if (first == Long.MIN_VALUE) {
                first = e.tRelMs();
            }
            last = e.tRelMs();
        }
        if (first == Long.MIN_VALUE) {
            return 0L;
        }
        return Math.max(0L, (last - first) / 1000L);
    }

    int completedUnitsIn(long startSec, long lenSec) {
        Set<String> ids = new LinkedHashSet<>();
        for (IndexedEvent e : sliceOfEvents(startSec, lenSec)) {
            if ("advancement_gained".equals(e.type())) {
                String id = str(e.payload().get("advancementId"));
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids.size();
    }

    int distinctActionKindsIn(long startSec, long lenSec) {
        Set<String> kinds = new LinkedHashSet<>();
        for (IndexedEvent e : sliceOfEvents(startSec, lenSec)) {
            if ("item_action".equals(e.type())) {
                String a = str(e.payload().get("action"));
                if (!a.isEmpty()) {
                    kinds.add(a);
                }
            }
        }
        return kinds.size();
    }

    int distinctPlacesIn(long startSec, long lenSec) {
        Set<String> keys = new LinkedHashSet<>();
        for (IndexedEvent e : sliceOfEvents(startSec, lenSec)) {
            String k = placeKeyOf(e.type(), e.payload());
            if (k != null) {
                keys.add(k);
            }
        }
        return keys.size();
    }

    Map<String, Integer> typeCountsIn(long startSec, long lenSec) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (IndexedEvent e : sliceOfEvents(startSec, lenSec)) {
            out.merge(e.type(), 1, Integer::sum);
        }
        return out;
    }

    int distinctItemsIn(long startSec, long lenSec) {
        Set<String> items = new LinkedHashSet<>();
        for (IndexedEvent e : sliceOfEvents(startSec, lenSec)) {
            if ("item_action".equals(e.type())) {
                String item = str(e.payload().get("item"));
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
        }
        return items.size();
    }

    double placeConcentrationIn(long startSec, long lenSec) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (IndexedEvent e : sliceOfEvents(startSec, lenSec)) {
            String k = placeKeyOf(e.type(), e.payload());
            if (k == null) {
                continue;
            }
            counts.merge(k, 1, Integer::sum);
            total++;
        }
        if (total == 0) {
            return Double.NaN;
        }
        int max = 0;
        for (int v : counts.values()) {
            max = Math.max(max, v);
        }
        return (double) max / total;
    }

    Double adherenceIn(long startSec, long lenSec) {
        return adherence(sliceOfEvents(startSec, lenSec));
    }

    private List<IndexedEvent> sliceOfEvents(long startSec, long lenSec) {
        long startMs = originMs + startSec * 1000L;
        return slice(startMs, startMs + lenSec * 1000L);
    }

    private int distinctItemClasses(List<IndexedEvent> slice) {
        Set<String> items = new LinkedHashSet<>();
        for (IndexedEvent e : slice) {
            if ("item_action".equals(e.type())) {
                String item = str(e.payload().get("item"));
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
        }
        return items.size();
    }

    private int distinctActionKinds(List<IndexedEvent> slice) {
        Set<String> kinds = new LinkedHashSet<>();
        for (IndexedEvent e : slice) {
            if ("item_action".equals(e.type())) {
                String action = str(e.payload().get("action"));
                if (!action.isEmpty()) {
                    kinds.add(action);
                }
            }
        }
        return kinds.size();
    }

    private int distinctPlaces(List<IndexedEvent> slice) {
        Set<String> keys = new LinkedHashSet<>();
        for (IndexedEvent e : slice) {
            String k = placeKeyOf(e.type(), e.payload());
            if (k != null) {
                keys.add(k);
            }
        }
        return keys.size();
    }

    private double dominantShare(List<IndexedEvent> slice) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (IndexedEvent e : slice) {
            String k = placeKeyOf(e.type(), e.payload());
            if (k == null) {
                continue;
            }
            counts.merge(k, 1, Integer::sum);
            total++;
        }
        if (total == 0) {
            return 1d;
        }
        int max = 0;
        for (int v : counts.values()) {
            max = Math.max(max, v);
        }
        return (double) max / (double) total;
    }

    private Double adherence(List<IndexedEvent> slice) {
        int progress = 0;
        int total = 0;
        for (IndexedEvent e : slice) {
            if (!isWhitelistInput(e.type()) || !e.confirmed()) {
                continue;
            }
            total++;
            if ("advancement_gained".equals(e.type()) || "quest_progressed".equals(e.type())
                    || "quest_completed".equals(e.type())) {
                progress++;
            }
        }
        if (total == 0) {
            return null;
        }
        return (double) progress / (double) total;
    }

    private List<IndexedEvent> slice(long startMs, long endMs) {
        List<IndexedEvent> out = new ArrayList<>();
        for (IndexedEvent e : indexed) {
            long abs = originMs + e.tRelMs();
            if (abs >= startMs && abs < endMs) {
                out.add(e);
            }
        }
        return out;
    }

    public SlicedInterval sliceOf(long startSec, long lenSec) {
        long startMs = originMs + startSec * 1000L;
        long endMs = startMs + lenSec * 1000L;
        int from = lowerBound(startMs);
        int to = lowerBound(endMs);
        int n = Math.max(0, to - from);
        int wl = 0;
        for (int i = from; i < to; i++) {
            IndexedEvent e = indexed.get(i);
            if (isWhitelistInput(e.type()) && e.confirmed()) {
                wl++;
            }
        }
        long active = to > from
                ? Math.max(0L, (indexed.get(to - 1).tRelMs() - indexed.get(from).tRelMs()) / 1000L)
                : 0L;
        return new SlicedInterval(n, wl, active);
    }

    private int lowerBound(long target) {
        int lo = 0;
        int hi = indexed.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (originMs + indexed.get(mid).tRelMs() < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    public static final class SlicedInterval {

        private final int eventCount;
        private final int whitelistInputCount;
        private final long activeSeconds;

        private SlicedInterval(int eventCount, int whitelistInputCount, long activeSeconds) {
            this.eventCount = eventCount;
            this.whitelistInputCount = whitelistInputCount;
            this.activeSeconds = activeSeconds;
        }

        public int eventCount() {
            return eventCount;
        }

        public long activeSeconds() {
            return activeSeconds;
        }

        public int whitelistInputCount() {
            return whitelistInputCount;
        }
    }

    static boolean isWhitelistInput(String type) {
        for (List<String> types : WHITELIST.values()) {
            if (types.contains(type)) {
                return true;
            }
        }
        return false;
    }

    static List<String> whitelistDump() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : WHITELIST.entrySet()) {
            out.add(e.getKey() + " → " + e.getValue());
        }
        return out;
    }

    static Set<String> whitelistTypes() {
        Set<String> out = new LinkedHashSet<>();
        for (List<String> types : WHITELIST.values()) {
            out.addAll(types);
        }
        return out;
    }

    private static Map<String, List<String>> whitelist() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("移动（位移 > 1 方块）", List.of("region_first_visit", "biome_visited", "structure_entered"));
        m.put("挖掘·放置方块", List.of("item_action"));
        m.put("攻击·被攻击", List.of("combat_started", "combat_ended"));
        m.put("使用物品", List.of("item_action"));
        m.put("容器交互", List.of("container_snapshot"));
        m.put("合成", List.of("recipe_attempted", "recipe_unlocked"));
        m.put("切换维度", List.of("dimension_entered"));
        m.put("进度获得", List.of("advancement_gained", "quest_progressed", "quest_completed"));
        m.put("机器交互（非机器自主产出）", List.of("machine_interacted"));
        return Collections.unmodifiableMap(m);
    }

    static String placeKeyOf(String type, Map<String, Object> payload) {
        switch (type) {
            case "biome_visited":
                return "D=" + str(payload.get("dimension")) + "|B=" + str(payload.get("biome"));
            case "dimension_entered":
                return "D=" + str(payload.get("dimension")) + "|B=";
            case "region_first_visit":
                return "D=" + str(payload.get("dimension")) + "|B=" + str(payload.get("biome"))
                        + "|R=" + str(payload.get("regionKey"));
            case "structure_entered":
                return "D=" + str(payload.get("dimension")) + "|R=" + str(payload.get("regionKey"))
                        + "|S=" + str(payload.get("structure"));
            default:
                return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
