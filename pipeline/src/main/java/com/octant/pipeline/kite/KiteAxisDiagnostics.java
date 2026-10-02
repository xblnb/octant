package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class KiteAxisDiagnostics {

    private KiteAxisDiagnostics() {
    }

    record Candidate(String id, String axisName, String keys, String formula, String domain,
                     String primitive, List<Double> values, Map<String, Double> byPoint) {
    }

    record Result(String report, String csv) {
        String reportText() {
            return report;
        }

        String csvText() {
            return csv;
        }
    }

    private static List<Double> progCumulative(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        int catalog = src.progressCatalog();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int done = src.cumulativeCompletedUnits(src.originMs() + (t + len) * 1000L);
                out.add(catalog == 0 ? Double.NaN : Math.min(1d, (double) done / catalog));
            }
        }
        return out;
    }

    private static List<Double> concentration(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                Map<String, Integer> counts = src.typeCountsIn(t, len);
                int total = 0;
                int max = 0;
                for (int v : counts.values()) {
                    total += v;
                    max = Math.max(max, v);
                }
                out.add(total == 0 ? Double.NaN : (double) max / total);
            }
        }
        return out;
    }

    private static List<Double> typeEntropy(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int k = src.typeCountsIn(t, len).size();
                out.add(k <= 1 ? 0d : Math.min(1d, Math.log(k) / Math.log(24d)));
            }
        }
        return out;
    }

    private static List<Double> itemEntropy(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int k = src.distinctItemsIn(t, len);
                out.add(k <= 1 ? 0d : Math.min(1d, Math.log(k) / Math.log(24d)));
            }
        }
        return out;
    }

    private static List<Double> placeConcentration(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                out.add(src.placeConcentrationIn(t, len));
            }
        }
        return out;
    }

    private static List<Double> actionKindEntropy(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int k = src.distinctActionKindsIn(t, len);
                out.add(k <= 1 ? 0d : Math.min(1d, Math.log(k) / Math.log(24d)));
            }
        }
        return out;
    }

    private static List<Double> placeDiversity(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int k = src.distinctPlacesIn(t, len);
                out.add(Math.min(1d, Math.log(1d + k) / Math.log(1d + 32d)));
            }
        }
        return out;
    }

    private static List<Double> placeDiversityLog2(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int k = src.distinctPlacesIn(t, len);
                out.add(Math.min(1d, Math.log(1d + k) / Math.log(1d + 10d)));
            }
        }
        return out;
    }

    private static List<Double> itemEntityDiversity(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        int catalog = src.itemCatalog();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int k = src.cumulativeDistinctItems(src.originMs() + (t + len) * 1000L);
                out.add(catalog <= 0 ? Double.NaN
                        : Math.min(1d, Math.log(1d + k) / Math.log(1d + catalog)));
            }
        }
        return out;
    }

    private static List<Double> guidDispersion(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                double conc = src.placeConcentrationIn(t, len);
                out.add(Double.isNaN(conc) ? Double.NaN : 1d - conc);
            }
        }
        return out;
    }

    private static List<Double> guidPlaceEntropy(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int k = src.distinctPlacesIn(t, len);
                out.add(k <= 1 ? 0d : Math.min(1d, Math.log(k) / Math.log(24d)));
            }
        }
        return out;
    }

    private static List<Double> progDelta(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        int catalog = src.progressCatalog();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int n = src.completedUnitsIn(t, len);
                out.add(catalog == 0 ? Double.NaN : Math.min(1d, (double) n / catalog));
            }
        }
        return out;
    }

    private static List<Double> progRate(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        int catalog = src.progressCatalog();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int n = src.completedUnitsIn(t, len);
                double activeMin = Math.max(1d, src.sliceOf(t, len).activeSeconds() / 60d);
                out.add(catalog == 0 ? Double.NaN : Math.min(1d, (n / activeMin) / catalog));
            }
        }
        return out;
    }

    private static List<Double> guidWindow(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        double sat = Math.sqrt(KiteConstants.KITE_STRUCT_SAT);
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                double v = src.distinctPlacesIn(t, len);
                double conc = src.placeConcentrationIn(t, len);
                if (Double.isNaN(conc)) {
                    out.add(Double.NaN);
                    continue;
                }
                double rv = Math.sqrt(v);
                out.add(Math.min(1d, 0.5d * (rv / (rv + sat)) + 0.5d * (1d - conc)));
            }
        }
        return out;
    }

    private static List<Double> sponWindowEntropy(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int k = src.distinctItemsIn(t, len);
                out.add(k <= 1 ? 0d : Math.min(1d, Math.log(k) / Math.log(24d)));
            }
        }
        return out;
    }

    private static List<Double> sponTypeEntropy(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int k = src.typeCountsIn(t, len).size();
                out.add(k <= 1 ? 0d : Math.min(1d, Math.log(k) / Math.log(24d)));
            }
        }
        return out;
    }

    private static List<Double> sponDispersion(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                Map<String, Integer> c = src.typeCountsIn(t, len);
                int total = 0;
                int max = 0;
                for (int v : c.values()) {
                    total += v;
                    max = Math.max(max, v);
                }
                out.add(total == 0 ? Double.NaN : 1d - (double) max / total);
            }
        }
        return out;
    }

    private static List<Double> structureDensity(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        double sat = Math.sqrt(KiteConstants.KITE_STRUCT_SAT);
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                double v = src.distinctPlacesIn(t, len);
                double conc = src.placeConcentrationIn(t, len);
                if (Double.isNaN(conc)) {
                    out.add(Double.NaN);
                    continue;
                }
                double rv = Math.sqrt(v);
                out.add(Math.min(1d, 0.5d * (rv / (rv + sat)) + 0.5d * (1d - conc)));
            }
        }
        return out;
    }

    private static List<Double> adherence(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                Double v = src.adherenceIn(t, len);
                out.add(v == null ? Double.NaN : v);
            }
        }
        return out;
    }

    static Result run(KiteObservationSource source, KiteSeries series) {
        List<long[]> spans = source.activeSpans();
        long delta = KiteConstants.KITE_DT_REF_S;

        List<Candidate> cands = new ArrayList<>();
        cands.add(new Candidate("C1 进度(累计)", "PROG", "octant.progress.completed_units",
                "done_cum / reachable_catalog（clamp[0,1]）", "[0,1]", "progress.advancement_catalog",
                progCumulative(source, spans, delta), null));
        cands.add(new Candidate("C6 动作实体多样性", "SPON", "octant.dispersion.entropy_normalized",
                "ln(1+k)/ln(1+C)，k = 截止该窗互异物品数，C = 本运行物品目录", "[0,1]", "dist.item_entity_diversity",
                itemEntityDiversity(source, spans, delta), null));
        cands.add(new Candidate("F0 进度(实现档)", "PROG", "octant.progress.completed_units",
                "累计完成互异推进单元数 / 本运行推进目录", "[0,1]", "progress.advancement_catalog",
                progImplementation(source, spans, delta), null));
        cands.add(new Candidate("F1 自发性(实现档)", "SPON", "octant.dispersion.entropy_normalized",
                "0.5·(ln(1+k)/ln(1+C)) + 0.3·(窗内场所数/目录) + 0.2·(窗内动作类型熵)", "[0,1]",
                "dist.item_entity_diversity", sponImplementation(source, spans, delta), null));
        cands.add(new Candidate("F2 引导性(实现档)", "GUID", "octant.env.dimension_variants",
                "0.5·√v/(√v+√sat) + 0.5·(1−窗内场所集中度)（adherence 可用时再 0.5/0.5 合成）", "[0,1]",
                "env.structure_occupancy", guidImplementation(source, spans, delta), null));
        cands.add(new Candidate("D1 进度(窗内增量)", "PROG", "octant.progress.completed_units",
                "该窗完成互异单元数 / 本运行推进目录", "[0,1]", "progress.advancement_done",
                progDelta(source, spans, delta), null));
        cands.add(new Candidate("D1' 进度(窗内速率)", "PROG", "octant.progress.completed_units",
                "(该窗完成互异单元数 / 该窗活跃分钟) / 目录", "[0,1]", "progress.advancement_done",
                progRate(source, spans, delta), null));
        cands.add(new Candidate("D2 自发性(窗内动作熵)", "SPON", "octant.dispersion.entropy_normalized",
                "ln(k)/ln(24)，k = 该窗互异 item_action.item 数", "[0,1]", "dist.action_class_entropy",
                sponWindowEntropy(source, spans, delta), null));
        cands.add(new Candidate("D2' 自发性(窗内类型熵)", "SPON", "octant.dispersion.entropy_normalized",
                "ln(k)/ln(24)，k = 该窗互异事件类型数", "[0,1]", "act.type_diversity",
                sponTypeEntropy(source, spans, delta), null));
        cands.add(new Candidate("D2'' 自发性(1−集中)", "SPON", "octant.pref.category_entropy",
                "1 − 该窗最大事件类型占比", "[0,1]", "act.type_dispersion",
                sponDispersion(source, spans, delta), null));
        cands.add(new Candidate("D4 引导性(集中度档)", "GUID", "octant.env.dimension_dominant_share",
                "1 − 该窗场所集中度（纯集中度档）", "[0,1]", "env.place_dispersion",
                guidDispersion(source, spans, delta), null));
        cands.add(new Candidate("D5 引导性(场所熵)", "GUID", "octant.env.dimension_variants",
                "ln(k)/ln(24)，k = 该窗互异场所数", "[0,1]", "env.place_diversity",
                guidPlaceEntropy(source, spans, delta), null));
        cands.add(new Candidate("D3 引导性(窗内)", "GUID", "octant.env.dimension_variants",
                "0.5·√(v)/(√(v)+√(KITE_STRUCT_SAT)) + 0.5·(1−该窗集中度)", "[0,1]", "env.structure_occupancy",
                guidWindow(source, spans, delta), null));
        cands.add(new Candidate("C5 GUID规格原式", "GUID", "octant.env.dimension_variants",
                "0.5·√(v)/(√(v)+√(KITE_STRUCT_SAT)) + 0.5·(1−集中度)", "[0,1]", "env.structure_occupancy",
                structureDensity(source, spans, delta), null));
        cands.add(new Candidate("C2 集中度", "SPON", "—（事件类型计数）",
                "max_t(share of event type t) ∈ (0,1]", "(0,1]", "act.type_share",
                concentration(source, spans, delta), null));
        cands.add(new Candidate("C2' 类型熵", "SPON", "—（事件类型计数）",
                "ln(k)/ln(24)，k = 互异事件类型数", "[0,1]", "act.type_diversity",
                typeEntropy(source, spans, delta), null));
        cands.add(new Candidate("C2'' 动作熵", "SPON", "octant.dispersion.entropy_normalized",
                "ln(k)/ln(24)，k = 互异 item_action.item 数", "[0,1]", "dist.action_class_entropy",
                itemEntropy(source, spans, delta), null));
        cands.add(new Candidate("C3 环境集中度", "GUID", "octant.env.dimension_dominant_share",
                "max_k(share of place k) ∈ (0,1]", "(0,1]", "env.place_concentration",
                placeConcentration(source, spans, delta), null));
        cands.add(new Candidate("C3' 贴合度", "GUID", "octant.pref.completion_rate_per_hour",
                "mean(stage/max_stage) ∈ [0,1]", "[0,1]", "quest.stage_level",
                adherence(source, spans, delta), null));
        cands.add(new Candidate("C2''' 动作类型熵", "SPON", "octant.pref.category_entropy",
                "ln(k)/ln(24)，k = 互异 item_action.action 数", "[0,1]", "dist.action_kind_mix",
                actionKindEntropy(source, spans, delta), null));
        cands.add(new Candidate("C4 环境多样性(sat32)", "GUID", "octant.env.dimension_variants",
                "ln(1+k)/ln(1+32)，k = 互异场所数", "[0,1]", "env.structure_occupancy",
                placeDiversity(source, spans, delta), null));
        cands.add(new Candidate("C4' 环境多样性(sat10)", "GUID", "octant.env.dimension_variants",
                "ln(1+k)/ln(1+10)，k = 互异场所数", "[0,1]", "env.structure_occupancy",
                placeDiversityLog2(source, spans, delta), null));

        List<Double> sessionSeq = new ArrayList<>();
        for (int si = 0; si < spans.size(); si++) {
            long[] sp = spans.get(si);
            for (long t = sp[0]; t < sp[1]; t += delta) {
                sessionSeq.add((double) si);
            }
        }
        Candidate sess = new Candidate("S 会话序号(混杂探针)", "—", "—（非指标）", "第 i 个占用窗所属会话序",
                "[0,11]", "session.index", sessionSeq, null);

        StringBuilder rep = new StringBuilder();
        StringBuilder csv = new StringBuilder();
        rep.append("══ 轴候选诊断（真实语料；逐候选：观测键 → 归一化式 → 值域 → ρ² 读数）══\n");
        rep.append("窗口：Δ₀ = ").append(delta).append(" s；占用窗 = ").append(sessionSeq.size())
                .append("；会话跨度 = ").append(spans.size()).append(" 段\n");
        rep.append("（占用窗口径 = 只取「有事件的 10 分钟窗」，会话之间直接拼接；与会话间空档无关）\n\n");
        csv.append("candidate,axis,keys,formula,domain,primitive,n,n_eff,neg_log10_rho2_with_session,rho2_with_session\n");

        for (Candidate c : cands) {
            rep.append("── ").append(c.id()).append("（轴 ").append(c.axisName()).append("）──\n");
            rep.append("   观测键      : ").append(c.keys()).append('\n');
            rep.append("   归一化式    : ").append(c.formula()).append('\n');
            rep.append("   值域        : ").append(c.domain()).append('\n');
            rep.append("   原语        : ").append(c.primitive()).append('\n');
            int n = 0;
            Set<Long> distinct = new LinkedHashSet<>();
            for (double v : c.values()) {
                if (!Double.isNaN(v)) {
                    n++;
                    distinct.add(Math.round(v * 1e9));
                }
            }
            Double r2s = KiteOrthogonality.rhoSquared(toArray(c.values(), false), toArray(sess.values(), false));
            rep.append("   N=").append(n).append("  互异取值=").append(distinct.size())
                    .append("  ρ²(与该轴 vs 会话序号)=").append(r2s == null ? "n/a（常数列）" : fmt(r2s)).append('\n');
            csv.append(q(c.id())).append(',').append(q(c.axisName())).append(',').append(q(c.keys())).append(',')
                    .append(q(c.formula())).append(',').append(q(c.domain())).append(',').append(q(c.primitive()))
                    .append(',').append(n).append(',').append(distinct.size()).append(',')
                    .append(r2s == null ? "" : fmt(r2s)).append(',')
                    .append(r2s == null ? "n/a" : fmt(r2s)).append('\n');
            rep.append('\n');
        }

        List<String[]> combos = List.of(
                new String[] {"现定义（被裁定要改的那套）", "C1 进度(累计)", "C2'' 动作熵", "C3' 贴合度"},
                new String[] {"甲-1（类型熵 + 环境集中度）", "C1 进度(累计)", "C2' 类型熵", "C3 环境集中度"},
                new String[] {"甲-2（集中度 + 环境集中度）", "C1 进度(累计)", "C2 集中度", "C3 环境集中度"},
                new String[] {"甲-3（动作熵 + 环境集中度）", "C1 进度(累计)", "C2'' 动作熵", "C3 环境集中度"});
        rep.append("════ 三轴组合的 ρ² 并列读数（判据阈值 KITE_ORTHO_MAX=")
                .append(fmt(KiteConstants.KITE_ORTHO_MAX)).append("，本类**不改**）════\n");
        csv.append("\ncombo,axis1,axis2,axis3,n,rho2_12,rho2_13,rho2_23,verdict,max_rho2\n");
        for (String[] combo : combos) {
            List<Double> a = valueOf(cands, combo[1]);
            List<Double> b = valueOf(cands, combo[2]);
            List<Double> c3 = valueOf(cands, combo[3]);
            List<double[]> rows = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                if (Double.isNaN(a.get(i)) || Double.isNaN(b.get(i)) || Double.isNaN(c3.get(i))) {
                    continue;
                }
                rows.add(new double[] {a.get(i), b.get(i), c3.get(i)});
            }
            List<KiteOrthogonality.Pair> pairs = KiteOrthogonality.pairwise(rows);
            boolean fail = false;
            boolean unv = false;
            for (KiteOrthogonality.Pair p : pairs) {
                fail |= p.verdict() == KiteOrthogonality.Verdict.FAIL;
                unv |= p.verdict() == KiteOrthogonality.Verdict.UNVERIFIED;
            }
            String verdict = fail ? "FAIL" : (unv ? "UNVERIFIED" : "PASS");
            rep.append("── ").append(combo[0]).append("   [").append(combo[1]).append(" / ").append(combo[2])
                    .append(" / ").append(combo[3]).append("]  N=").append(rows.size()).append("  ⇒ ")
                    .append(verdict).append('\n');
            for (KiteOrthogonality.Pair p : pairs) {
                rep.append("     ρ²(").append(p.a()).append(',').append(p.b()).append(") = ")
                        .append(Double.isNaN(p.rhoSquared()) ? "n/a（常数列，无定义）" : fmt(p.rhoSquared()))
                        .append("   ").append(p.verdict()).append('\n');
            }
            rep.append("     max ρ² = ")
                    .append(Double.isNaN(KiteOrthogonality.maxRhoSquared(pairs)) ? "n/a"
                            : fmt(KiteOrthogonality.maxRhoSquared(pairs)))
                    .append('\n');
            csv.append(q(combo[0])).append(',').append(q(combo[1])).append(',').append(q(combo[2])).append(',')
                    .append(q(combo[3])).append(',').append(rows.size()).append(',')
                    .append(val(pairs, 0)).append(',').append(val(pairs, 1)).append(',').append(val(pairs, 2))
                    .append(',').append(verdict).append(',')
                    .append(Double.isNaN(KiteOrthogonality.maxRhoSquared(pairs)) ? "n/a"
                            : fmt(KiteOrthogonality.maxRhoSquared(pairs)))
                    .append('\n');
        }
        List<String> progIds = new ArrayList<>();
        List<String> sponIds = new ArrayList<>();
        List<String> guidIds = new ArrayList<>();
        for (Candidate c : cands) {
            if (!c.id().startsWith("C1")) {
                if (c.axisName().equals("SPON")) {
                    sponIds.add(c.id());
                } else if (c.axisName().equals("GUID")) {
                    guidIds.add(c.id());
                }
            } else {
                progIds.add(c.id());
            }
        }
        rep.append("\n════ 全枚举三元组（PROG×SPON×GUID 各候选），按 max ρ² 升序 ════\n");
        csv.append("\ntriplet,prog,spon,guid,n,max_rho2,rho2_12,rho2_13,rho2_23,verdict\n");
        List<String> ranked = new ArrayList<>();
        for (String p : progIds) {
            for (String sp : sponIds) {
                for (String gd : guidIds) {
                    List<Double> a = valueOf(cands, p);
                    List<Double> b = valueOf(cands, sp);
                    List<Double> c3 = valueOf(cands, gd);
                    List<double[]> rows = new ArrayList<>();
                    for (int i = 0; i < a.size(); i++) {
                        if (Double.isNaN(a.get(i)) || Double.isNaN(b.get(i)) || Double.isNaN(c3.get(i))) {
                            continue;
                        }
                        rows.add(new double[] {a.get(i), b.get(i), c3.get(i)});
                    }
                    if (rows.size() < KiteConstants.KITE_ORTHO_MIN_N) {
                        continue;
                    }
                    List<KiteOrthogonality.Pair> pairs = KiteOrthogonality.pairwise(rows);
                    double max = KiteOrthogonality.maxRhoSquared(pairs);
                    boolean fail = false;
                    boolean unv = false;
                    for (KiteOrthogonality.Pair q : pairs) {
                        fail |= q.verdict() == KiteOrthogonality.Verdict.FAIL;
                        unv |= q.verdict() == KiteOrthogonality.Verdict.UNVERIFIED;
                    }
                    String verdict = fail ? "FAIL" : (unv ? "UNVERIFIED" : "PASS");
                    ranked.add(String.format(Locale.ROOT, "%s | %s | %s | N=%d | max=%s | %s | 12=%s 13=%s 23=%s",
                            p, sp, gd, rows.size(), Double.isNaN(max) ? "n/a" : fmt(max), verdict,
                            val(pairs, 0), val(pairs, 1), val(pairs, 2)));
                    csv.append(q(p + " | " + sp + " | " + gd)).append(',').append(q(p)).append(',').append(q(sp))
                            .append(',').append(q(gd)).append(',').append(rows.size()).append(',')
                            .append(Double.isNaN(max) ? "n/a" : fmt(max)).append(',')
                            .append(val(pairs, 0)).append(',').append(val(pairs, 1)).append(',').append(val(pairs, 2))
                            .append(',').append(verdict).append('\n');
                }
            }
        }
        ranked.sort((x, y) -> {
            double mx = maxOf(x);
            double my = maxOf(y);
            return Double.compare(mx, my);
        });
        int shown = 0;
        for (String line : ranked) {
            rep.append("  ").append(line).append('\n');
            shown++;
        }
        rep.append("  （共枚举 ").append(shown).append(" 个三元组；阈值 KITE_ORTHO_MAX=")
                .append(fmt(KiteConstants.KITE_ORTHO_MAX)).append(" 未改动）\n");

        SelectionReport sr = selectionReport(cands, source, spans, delta, ranked);
        rep.append('\n').append(sr.render());
        KiteAxisDiagnostics.LAST_SELECTION = sr;
        csv.append("\nholdout_fold,n_points,max_rho2,rho2_12,rho2_13,rho2_23,verdict\n");
        for (String s : sr.outOfSample()) {
            csv.append(q(s)).append('\n');
        }

        rep.append("\n════ T20 逐候选：混杂强度 × 分辨率 ════\n");
        csv.append("\nt20_candidate,axis,primitive,n_points,n_distinct,rho2_session,confound_ok,resolution_ok\n");
        for (Candidate c : cands) {
            if (c.id().startsWith("S ")) {
                continue;
            }
            int n = 0;
            Set<Long> distinct = new LinkedHashSet<>();
            for (double v : c.values()) {
                if (!Double.isNaN(v)) {
                    n++;
                    distinct.add(Math.round(v * 1e9));
                }
            }
            Double r2s = KiteOrthogonality.rhoSquared(toArray(c.values(), false), toArray(sess.values(), false));
            boolean confoundOk = r2s != null && r2s <= KiteConstants.KITE_CONFOUND_MAX;
            boolean resolutionOk = distinct.size() >= KiteConstants.KITE_ORTHO_MIN_N;
            rep.append(String.format(Locale.ROOT,
                    "  %-24s 轴=%-4s N=%3d 互异=%3d  ρ²(会话)=%-10s 混杂可接受=%-5s 分辨率足够=%-5s%n",
                    c.id(), c.axisName(), n, distinct.size(),
                    r2s == null ? "n/a(常数列)" : fmt(r2s), confoundOk, resolutionOk));
            csv.append(q(c.id())).append(',').append(q(c.axisName())).append(',').append(q(c.primitive()))
                    .append(',').append(n).append(',').append(distinct.size()).append(',')
                    .append(r2s == null ? "n/a" : fmt(r2s)).append(',').append(confoundOk).append(',')
                    .append(resolutionOk).append('\n');
        }

        return new Result(rep.toString(), csv.toString());
    }

    private static List<Double> sponImplementation(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        int itemCatalog = src.itemCatalog();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int k = src.distinctItemsIn(t, len);
                out.add(itemCatalog <= 0 ? 0d
                        : Math.min(1d, Math.log(1d + k) / Math.log(1d + itemCatalog)));
            }
        }
        return out;
    }

    private static List<Double> guidImplementation(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        double sat = Math.sqrt(KiteConstants.KITE_STRUCT_SAT);
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                double v = src.distinctPlacesIn(t, len);
                double conc = src.placeConcentrationIn(t, len);
                if (Double.isNaN(conc)) {
                    out.add(Double.NaN);
                    continue;
                }
                double rv = Math.sqrt(v);
                double density = 0.5d * (rv / (rv + sat)) + 0.5d * (1d - conc);
                Double adh = src.adherenceIn(t, len);
                out.add(Math.min(1d, adh == null ? density : 0.5d * density + 0.5d * adh));
            }
        }
        return out;
    }

    private static List<Double> progImplementation(KiteObservationSource src, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        int catalog = src.progressCatalog();
        for (long[] sp : spans) {
            for (long t = sp[0]; t < sp[1]; t += delta) {
                long len = Math.min(delta, sp[1] - t);
                int done = src.cumulativeCompletedUnits(src.originMs() + (t + len) * 1000L);
                out.add(catalog == 0 ? Double.NaN : Math.min(1d, (double) done / catalog));
            }
        }
        return out;
    }

    private static List<Double> subset(List<Double> values, List<Boolean> keep, List<long[]> spans, long delta) {
        List<Double> out = new ArrayList<>();
        int k = 0;
        for (int si = 0; si < spans.size(); si++) {
            long[] sp = spans.get(si);
            for (long t = sp[0]; t < sp[1]; t += delta) {
                if (keep.get(si)) {
                    out.add(values.get(k));
                }
                k++;
            }
        }
        return out;
    }

    private static String pairReading(List<double[]> rows, int i, int j) {
        double[] a = new double[rows.size()];
        double[] b = new double[rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            a[r] = rows.get(r)[i];
            b[r] = rows.get(r)[j];
        }
        Double r2 = KiteOrthogonality.rhoSquared(a, b);
        String[] names = {"PROG", "SPON", "GUID"};
        return names[i] + "~" + names[j] + "=" + (r2 == null ? "n/a" : fmt(r2));
    }

    private static SelectionReport selectionReport(List<Candidate> cands, KiteObservationSource source,
                                                   List<long[]> spans, long delta, List<String> ranked) {
        List<String> rejected = new ArrayList<>();
        for (String line : ranked) {
            if (line.contains("FAIL") || line.contains("UNVERIFIED")) {
                rejected.add(line.trim());
            }
            if (rejected.size() >= 4) {
                break;
            }
        }
        String selected = "F0 进度 = 累计完成互异推进单元数/本运行推进目录 ；"
                + "F1 自发性 = ln(1+k)/ln(1+C)（k = 该窗互异物品实体数；**后两项已由档位门排除**）；"
                + "F2 引导性 = 0.5·√v/(√v+√sat) + 0.5·(1−窗内场所集中度)（adherence 可用时再 0.5/0.5 合成）";
        List<String> holdout = new ArrayList<>();
        double[] selectedInSample = new double[1];
        List<Boolean> firstHalf = new ArrayList<>();
        List<Boolean> secondHalf = new ArrayList<>();
        List<Boolean> odd = new ArrayList<>();
        List<Boolean> even = new ArrayList<>();
        int half = spans.size() / 2;
        for (int si = 0; si < spans.size(); si++) {
            firstHalf.add(si < half);
            secondHalf.add(si >= half);
            odd.add(si % 2 == 0);
            even.add(si % 2 == 1);
        }
        holdout.addAll(holdoutFold("A 选前半（会话 1.." + half + "）→ 报后半（会话 " + (half + 1) + ".."
                + spans.size() + "）", cands, spans, delta, firstHalf, secondHalf, selectedInSample));
        holdout.addAll(holdoutFold("B 选后半 → 报前半", cands, spans, delta, secondHalf, firstHalf, selectedInSample));
        holdout.addAll(holdoutFold("C 选奇号会话 → 报偶号会话", cands, spans, delta, odd, even, selectedInSample));
        holdout.addAll(holdoutFold("D 选偶号会话 → 报奇号会话", cands, spans, delta, even, odd, selectedInSample));
        holdout.add("—— 以下为**实现档三元组自身**的样本外读数（不重跑选择，直接测在用的定义）——");
        holdout.addAll(implementationHoldout(source, spans, delta));
        return new SelectionReport(ranked.size(), selected, String.join(" ｜ ", rejected),
                selectedInSample[0], holdout,
                "留出按**会话**切分（会话之间本就有时序间隔）；每次折内**重跑选择程序**，"
                        + "再把选出的三元组用到**未见会话**上 ⇒ 样本外读数是真留出；"
                        + "样本内 max ρ² 与样本外并列给出，二者一起看才有意义；"
                        + "另附**实现档三元组 F0/F1/F2 自身**的逐折样本外读数（不重跑选择，直接测在用的定义）");
    }

    private static List<String> implementationHoldout(KiteObservationSource source, List<long[]> spans, long delta) {
        List<String> out = new ArrayList<>();
        int half = spans.size() / 2;
        List<Boolean> first = new ArrayList<>();
        List<Boolean> second = new ArrayList<>();
        List<Boolean> odd = new ArrayList<>();
        List<Boolean> even = new ArrayList<>();
        for (int si = 0; si < spans.size(); si++) {
            first.add(si < half);
            second.add(si >= half);
            odd.add(si % 2 == 0);
            even.add(si % 2 == 1);
        }
        List<Double> f0 = progImplementation(source, spans, delta);
        List<Double> f1 = sponImplementation(source, spans, delta);
        List<Double> f2 = guidImplementation(source, spans, delta);
        String[][] folds = {
                {"前半（会话 1.." + half + "）", "1"},
                {"后半（会话 " + (half + 1) + ".." + spans.size() + "）", "2"},
                {"奇号会话", "3"},
                {"偶号会话", "4"}
        };
        List<Boolean>[] keeps = new List[] {first, second, odd, even};
        for (int f = 0; f < folds.length; f++) {
            List<Double> a = subset(f0, keeps[f], spans, delta);
            List<Double> b = subset(f1, keeps[f], spans, delta);
            List<Double> c = subset(f2, keeps[f], spans, delta);
            List<double[]> rows = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                if (Double.isNaN(a.get(i)) || Double.isNaN(b.get(i)) || Double.isNaN(c.get(i))) {
                    continue;
                }
                rows.add(new double[] {a.get(i), b.get(i), c.get(i)});
            }
            if (rows.size() < KiteConstants.KITE_ORTHO_MIN_N) {
                out.add("实现档 F0/F1/F2 于 " + folds[f][0] + "：可用点 " + rows.size() + " < "
                        + KiteConstants.KITE_ORTHO_MIN_N + " ⇒ **UNVERIFIED**（不得记通过）");
                continue;
            }
            List<KiteOrthogonality.Pair> pairs = KiteOrthogonality.pairwise(rows);
            double max = KiteOrthogonality.maxRhoSquared(pairs);
            boolean fail = false;
            boolean unv = false;
            for (KiteOrthogonality.Pair q : pairs) {
                fail |= q.verdict() == KiteOrthogonality.Verdict.FAIL;
                unv |= q.verdict() == KiteOrthogonality.Verdict.UNVERIFIED;
            }
            out.add("实现档 F0/F1/F2 于 " + folds[f][0] + "：N=" + rows.size() + " max ρ²=" + fmt(max)
                    + " ⇒ " + (fail ? "**FAIL**" : (unv ? "**UNVERIFIED**" : "PASS")) + "（"
                    + pairReading(rows, 0, 1) + "、" + pairReading(rows, 0, 2) + "、"
                    + pairReading(rows, 1, 2) + "）");
        }
        return out;
    }

    private static List<String> holdoutFold(String name, List<Candidate> cands, List<long[]> spans, long delta,
                                            List<Boolean> trainKeep, List<Boolean> testKeep, double[] inSampleOut) {
        List<String> out = new ArrayList<>();
        String bestId = null;
        double bestMax = Double.MAX_VALUE;
        double bestInSample = Double.NaN;
        for (Candidate p : cands) {
            if (!p.id().startsWith("C1") && !p.id().startsWith("F0")) {
                continue;
            }
            for (Candidate sp : cands) {
                if (!(sp.axisName().equals("SPON"))) {
                    continue;
                }
                for (Candidate gd : cands) {
                    if (!(gd.axisName().equals("GUID"))) {
                        continue;
                    }
                    List<Double> a = subset(p.values(), trainKeep, spans, delta);
                    List<Double> b = subset(sp.values(), trainKeep, spans, delta);
                    List<Double> c = subset(gd.values(), trainKeep, spans, delta);
                    List<double[]> rows = new ArrayList<>();
                    for (int i = 0; i < a.size(); i++) {
                        if (Double.isNaN(a.get(i)) || Double.isNaN(b.get(i)) || Double.isNaN(c.get(i))) {
                            continue;
                        }
                        rows.add(new double[] {a.get(i), b.get(i), c.get(i)});
                    }
                    if (rows.size() < KiteConstants.KITE_ORTHO_MIN_N) {
                        continue;
                    }
                    double mx = KiteOrthogonality.maxRhoSquared(KiteOrthogonality.pairwise(rows));
                    if (!Double.isNaN(mx) && mx < bestMax) {
                        bestMax = mx;
                        bestId = p.id() + " | " + sp.id() + " | " + gd.id();
                        bestInSample = mx;
                    }
                }
            }
        }
        if (bestId == null) {
            out.add(name + "：样本量不足，无可选三元组（记 UNVERIFIED）");
            return out;
        }
        inSampleOut[0] = bestInSample;
        Candidate p = null;
        Candidate sp = null;
        Candidate gd = null;
        String[] parts = bestId.split(" \\| ");
        for (Candidate c : cands) {
            if (c.id().equals(parts[0])) {
                p = c;
            }
            if (c.id().equals(parts[1])) {
                sp = c;
            }
            if (c.id().equals(parts[2])) {
                gd = c;
            }
        }
        List<Double> a = subset(p.values(), testKeep, spans, delta);
        List<Double> b = subset(sp.values(), testKeep, spans, delta);
        List<Double> c = subset(gd.values(), testKeep, spans, delta);
        List<double[]> rows = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            if (Double.isNaN(a.get(i)) || Double.isNaN(b.get(i)) || Double.isNaN(c.get(i))) {
                continue;
            }
            rows.add(new double[] {a.get(i), b.get(i), c.get(i)});
        }
        if (rows.size() < KiteConstants.KITE_ORTHO_MIN_N) {
            out.add(name + "：所选 " + bestId + " 在测试集上可用点仅 " + rows.size() + " < "
                    + KiteConstants.KITE_ORTHO_MIN_N + " ⇒ **UNVERIFIED**（不得记通过）");
            return out;
        }
        List<KiteOrthogonality.Pair> pairs = KiteOrthogonality.pairwise(rows);
        double max = KiteOrthogonality.maxRhoSquared(pairs);
        boolean fail = false;
        for (KiteOrthogonality.Pair q : pairs) {
            fail |= q.verdict() == KiteOrthogonality.Verdict.FAIL;
        }
        out.add(name + "：训练集内 max ρ²=" + fmt(bestInSample) + "；**样本外 N=" + rows.size()
                + " max ρ²=" + fmt(max) + " ⇒ " + (fail ? "**FAIL**" : "PASS") + "（所选 " + bestId + "；"
                + pairReading(rows, 0, 1) + "、" + pairReading(rows, 0, 2) + "、" + pairReading(rows, 1, 2) + "）");
        return out;
    }

    static SelectionReport LAST_SELECTION;

    record SelectionReport(int candidateCount, String selected, String rejectedNear, double inSampleMax,
                           List<String> outOfSample, String note) {
        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("════ 选择程序与留出检验（硬条件）════\n");
            sb.append("  候选数与枚举范围  : ").append(candidateCount).append(" 个三元组（PROG×SPON×GUID 全枚举，同一语料同一 Δ₀）\n");
            sb.append("  选择依据          : 先要求三轴都可用（N 最大），再取 **max ρ² 最小** 者\n");
            sb.append("  选中（实现档）    : ").append(selected).append('\n');
            sb.append("  被拒者（含近失败）: ").append(rejectedNear).append('\n');
            sb.append("  样本内 max ρ²     : ").append(fmt(inSampleMax))
                    .append("（**样本内、经选择 ⇒ 乐观有偏**，不得单独作为证据）\n");
            for (String s : outOfSample) {
                sb.append("  样本外 ").append(s).append('\n');
            }
            sb.append("  口径              : ").append(note).append('\n');
            return sb.toString();
        }
    }

    private static double maxOf(String rankedLine) {
        int i = rankedLine.indexOf("max=");
        int j = rankedLine.indexOf(' ', i);
        String s = rankedLine.substring(i + 4, j);
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }

    private static String val(List<KiteOrthogonality.Pair> pairs, int i) {
        KiteOrthogonality.Pair p = pairs.get(i);
        return Double.isNaN(p.rhoSquared()) ? "n/a" : fmt(p.rhoSquared());
    }

    private static List<Double> valueOf(List<Candidate> cands, String id) {
        for (Candidate c : cands) {
            if (c.id().equals(id)) {
                return c.values();
            }
        }
        throw new IllegalArgumentException("未知候选：" + id);
    }

    private static double[] toArray(List<Double> v, boolean dropNaN) {
        int n = 0;
        for (double d : v) {
            if (!Double.isNaN(d)) {
                n++;
            }
        }
        double[] out = new double[n];
        int i = 0;
        for (double d : v) {
            if (!Double.isNaN(d)) {
                out[i++] = d;
            }
        }
        return out;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String q(String s) {
        return '"' + s.replace("\"", "'") + '"';
    }

    static Map<String, String> summary() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("candidates", "C1/C2/C2'/C2''/C3/C3'/S");
        return m;
    }
}
