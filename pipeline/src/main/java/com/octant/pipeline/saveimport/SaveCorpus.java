package com.octant.pipeline.saveimport;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.EventSource;
import com.octant.common.model.RawEventSchema;
import com.octant.pipeline.adapter.CaptureEventAdapter;
import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;
import com.octant.pipeline.raw.UnitKind;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class SaveCorpus {

    private static final long SESSION_MAX_MS = CaptureEvent.SESSION_MAX_TREL_MS;
    private static final long DAY_MS = 86_400_000L;

    public static final String PLAYER_KEY = "0000000000000000";

    private SaveCorpus() {
    }

    public record Conversion(List<CaptureEvent> events,
                             CaptureEventAdapter.Conversion conv,
                             AnalysisReport report,
                             ContentCatalog catalog,
                             Map<String, Object> statsSummary,
                             List<String> suppressedMetrics,
                             Map<String, Object> timelineFacts,
                             List<String> notApplicable) {
    }

    public static Conversion of(Path saveDir, Path modsDir, Path kubejsDir, List<Path> extraJars)
            throws IOException {
        SaveReader.SaveSnapshot save = SaveReader.read(saveDir);
        AdvancementCatalog.Catalog denom = AdvancementCatalog.scan(modsDir, kubejsDir, extraJars);
        return of(save, denom);
    }

    public static Conversion of(SaveReader.SaveSnapshot save, AdvancementCatalog.Catalog denom) {
        int[] buckets = {0};
        long[] maxTrel = {0L};
        List<CaptureEvent> events = events(save, denom, buckets, maxTrel);
        CaptureEventAdapter.Conversion conv = CaptureEventAdapter.toPipelineTolerant(events);
        ContentCatalog catalog = catalog(save, denom);
        long declaredPlaySeconds = save.playTimeTicks() / 20L;
        AnalysisReport report = AnalysisReport.analyze(conv.events(), catalog, "forge", declaredPlaySeconds);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("playTimeTicks", save.playTimeTicks());
        stats.put("playHours", round2(save.playHours()));
        stats.put("leaveGameCount", counter(save, "minecraft:leave_game"));
        stats.put("mobKills", counter(save, "minecraft:mob_kills"));
        stats.put("deaths", counter(save, "minecraft:deaths"));
        stats.put("distinctMobTypesKilled", save.killed().size());
        stats.put("distinctBlocksMined", save.mined().size());
        stats.put("distinctItemsCrafted", save.crafted().size());
        stats.put("statsDataVersion", save.statsDataVersion());
        stats.put("levelDataVersion", save.levelDataVersion());
        stats.put("levelNamePresent", save.levelNamePresent());
        stats.put("note", "**累计量**：只作如实汇报，**不**摊成会话级/分钟级指标（本类不为其造事件）");

        List<String> notApplicable = List.of(
                "逐事件时间线（心跳/战斗/维度/群系/机器/容器）—— 存档无逐事件记录",
                "每次会话各自的边界与长度 —— 只有 leave_game 次数这个累计量",
                "事件密度 / 分钟级活跃度 —— 需要逐事件时间戳",
                "挂机与暂停的区分 —— 需要 tick 级推进量与暂停边界",
                "重复失败与卡点重试次数 —— 存档无失败记录",
                "资源瓶颈 / 机器与任务进度 —— 存档无容器与任务观测",
                "成就之外的自定义任务（kubejs 任务）完成事件 —— 存档没有该记录（只在其数据定义里）");

        Map<String, Object> tf = new LinkedHashMap<>();
        tf.put("sessionBuckets", buckets[0]);
        tf.put("sessionBucketMeaning", "有达成活动的**UTC 日**数（不是玩家实际开局的次数）");
        tf.put("maxTrelMs", maxTrel[0]);
        tf.put("sessionLimitMs", SESSION_MAX_MS);
        tf.put("compressed", false);
        tf.put("compressedNote", "未做任何压缩：tRelMs = 当日真实毫秒差；跨日通过**换 sessionId**表达");

        return new Conversion(events, conv, report, catalog, stats, notApplicable, tf, notApplicable);
    }

    public static List<CaptureEvent> events(SaveReader.SaveSnapshot save,
                                            AdvancementCatalog.Catalog denom) {
        return events(save, denom, new int[1], new long[1]);
    }

    private static List<CaptureEvent> events(SaveReader.SaveSnapshot save,
                                             AdvancementCatalog.Catalog denom,
                                             int[] bucketsOut, long[] maxTrelOut) {
        record Hit(long ms, String id) {
        }
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(save.doneTimestampsIso()).entrySet()) {
            long ms = parseIso(e.getValue());
            if (ms >= 0) {
                hits.add(new Hit(ms, e.getKey()));
            }
        }
        hits.sort((a, b) -> a.ms() != b.ms() ? Long.compare(a.ms(), b.ms()) : a.id().compareTo(b.id()));

        List<CaptureEvent> out = new ArrayList<>();
        long firstMs = hits.isEmpty() ? 0L : hits.get(0).ms();
        long curDay = Long.MIN_VALUE;
        int seqInSession = 0;
        int sessionNo = 0;
        String session = null;
        maxTrelOut[0] = 0L;

        if (!hits.isEmpty()) {
            sessionNo = 1;
            session = CaptureEvent.sessionId(sessionNo);
            curDay = dayOf(hits.get(0).ms());
            seqInSession = 1;
            out.add(newEvent(session, seqInSession++, CaptureEventType.SESSION_START, 0L, Map.of(
                    "gameVersion", "1.20.1", "loader", "forge", "privacyClass", "singleplayer",
                    "sessionStartDate", isoDay(hits.get(0).ms()),
                    "cheatsEnabled", Boolean.FALSE)));
        }
        for (Hit h : hits) {
            long day = dayOf(h.ms());
            if (day != curDay) {
                sessionNo++;
                session = CaptureEvent.sessionId(sessionNo);
                curDay = day;
                seqInSession = 1;
                out.add(newEvent(session, seqInSession++, CaptureEventType.SESSION_START, 0L, Map.of(
                        "gameVersion", "1.20.1", "loader", "forge", "privacyClass", "singleplayer",
                        "sessionStartDate", isoDay(h.ms()),
                        "cheatsEnabled", Boolean.FALSE)));
            }
            long tRel = h.ms() - day;
            maxTrelOut[0] = Math.max(maxTrelOut[0], tRel);
            if (tRel > SESSION_MAX_MS) {
                throw new IllegalStateException("tRelMs 越界（不应发生）：" + tRel);
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("advancementId", h.id());
            p.put("parentId", "");
            p.put("isRecipe", Boolean.FALSE);
            p.put("grantedByCommand", Boolean.FALSE);
            out.add(newEvent(session, seqInSession++, CaptureEventType.ADVANCEMENT_GAINED, tRel, p));
        }
        bucketsOut[0] = sessionNo;
        return out;
    }

    private static CaptureEvent newEvent(String session, int seq, CaptureEventType type,
                                          long tRelMs, Map<String, Object> payload) {
        return new CaptureEvent(RawEventSchema.VERSION, CaptureEvent.eventId(seq), session,
                PLAYER_KEY, type, tRelMs, CaptureEvent.tickOf(tRelMs), type.category(),
                EventSource.FORGE, true, null, payload);
    }

    public static ContentCatalog catalog(SaveReader.SaveSnapshot save,
                                         AdvancementCatalog.Catalog denom) {
        ContentCatalog c = new ContentCatalog();
        c.register(UnitKind.ADVANCEMENT, new ArrayList<>(new TreeSet<>(denom.definition())), false);
        c.register(UnitKind.ITEM, new ArrayList<>(new TreeSet<>(save.used().keySet())), false);
        c.register(UnitKind.RECIPE, new ArrayList<>(new TreeSet<>(save.crafted().keySet())), false);
        return c;
    }

    public static Map<String, Object> denominatorFacts(AdvancementCatalog.Catalog c) {
        Map<String, Object> m = new LinkedHashMap<>(c.caveats());
        m.put("total", c.total());
        m.put("namespaceCount", c.namespaceCount());
        return m;
    }

    static long parseIso(String iso) {
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException ex) {
            return -1L;
        }
    }

    private static long dayOf(long ms) {
        return Math.floorDiv(ms, DAY_MS) * DAY_MS;
    }

    private static String isoDay(long ms) {
        return java.time.Instant.ofEpochMilli(ms).toString().substring(0, 10);
    }

    private static long counter(SaveReader.SaveSnapshot s, String key) {
        Long v = s.custom().get(key);
        return v == null ? -1L : v;
    }

    private static double round2(double d) {
        return d < 0 ? -1.0 : Math.round(d * 100.0) / 100.0;
    }

    public static Map<String, Integer> histogram(List<CaptureEvent> events) {
        Map<String, Integer> h = new TreeMap<>();
        for (CaptureEvent e : events) {
            h.merge(e.type().eventId(), 1, Integer::sum);
        }
        return h;
    }

    public static Map<String, Integer> rawHistogram(CaptureEventAdapter.Conversion conv) {
        Map<String, Integer> h = new TreeMap<>();
        for (RawEvent e : conv.events()) {
            EventType t = e.type();
            h.merge(t == null ? "(null)" : t.name(), 1, Integer::sum);
        }
        return h;
    }
}
