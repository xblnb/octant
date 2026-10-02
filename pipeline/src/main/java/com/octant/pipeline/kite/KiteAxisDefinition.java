package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KiteAxisDefinition {

    private KiteAxisDefinition() {
    }

    public record Observation(KiteAxis axis, String key, String primitive, String role, String base, String domain) {
    }

    public record AxisDef(KiteAxis axis, String orientation, String formula, String domain, String suppressRule) {
    }

    public record SharedPrimitive(String primitive, String axes, String basis) {
    }

    private static final List<Observation> OBSERVATIONS = buildObservations();
    private static final List<AxisDef> AXES = buildAxes();
    private static final List<SharedPrimitive> SHARED = buildShared();

    private static List<Observation> buildObservations() {
        List<Observation> list = new ArrayList<>();
        list.add(new Observation(KiteAxis.PROG, "octant.progress.completed_units",
                "progress.advancement_done",
                "分量（本区间内已完成的互异推进单元数）",
                "取自 `advancement_gained.advancementId` 按区间去重计数", "整数 ≥ 0"));
        list.add(new Observation(KiteAxis.PROG, "octant.progress.reachable_units",
                "progress.advancement_catalog",
                "分母 + 抑制条件（== 0 ⇒ 抑制，不得记 0）",
                "本分析运行内观测到的互异推进单元目录大小（同一运行内冻结）", "整数 ≥ 0"));
        list.add(new Observation(KiteAxis.PROG, "octant.progress.cover_weighted",
                "progress.cover_ratio",
                "归一化后的坐标分量 = clamp(该比值, 0, 1)",
                "completed_units / reachable_units（区间口径）", "[0,1]"));
        list.add(new Observation(KiteAxis.PROG, "octant.progress.item_spectrum_coverage",
                "progress.item_spectrum",
                "注册但本切片不参与派生（规格同族清单第 4 项，本切片无物品谱目录 ⇒ 不代其定义）",
                "（不参与派生；登记以保持键集与规格一致）", "[0,1]"));

        list.add(new Observation(KiteAxis.SPON, "octant.dispersion.entropy_normalized",
                "dist.action_class_entropy",
                "分量（权重 KITE_W_SPON_ENTROPY）",
                "区间内动作类分布归一化熵；类别 = `item_action.item` 的互异取值", "[0,1]"));
        list.add(new Observation(KiteAxis.SPON, "octant.breadth.weighted_coverage",
                "dist.place_breadth",
                "分量（权重 KITE_W_SPON_BREADTH）",
                "区间内到访互异场所数 / 本运行观测到的场所目录大小", "[0,1]"));
        list.add(new Observation(KiteAxis.SPON, "octant.pref.category_entropy",
                "dist.action_class_mix",
                "分量（权重 KITE_W_SPON_PREF）",
                "区间内动作类**混合度**（同一批动作事件、但按另一条归一化式与另一个基："
                        + "以 `item_action.action` 的互异取值数为分母，取不同原始量来源）", "[0,1]"));

        list.add(new Observation(KiteAxis.GUID, "octant.env.snapshot",
                "env.snapshot_revision",
                "注册但本切片不参与派生（快照修订号；本切片不把「快照存在」当结构密度）",
                "（不参与派生；登记以保持键集与规格一致）", "整数 ≥ 0"));
        list.add(new Observation(KiteAxis.GUID, "octant.env.dimension_variants",
                "env.structure_occupancy",
                "分量（structure_density 的饱和输入）",
                "本运行内观测到的互异（维度, 群系）组合数", "整数 ≥ 0"));
        list.add(new Observation(KiteAxis.GUID, "octant.env.dimension_dominant_share",
                "env.place_concentration",
                "分量（structure_density 的集中度输入）",
                "区间内出现最多的那个（维度, 群系）组合事件占比", "[0,1]"));
        list.add(new Observation(KiteAxis.GUID, "octant.env.installed_unused_flag",
                "env.unused_pack_signal",
                "注册但本切片不参与派生（须「模组已安装但无专属事件」信号；本切片无该信号 ⇒ 不代其定义）",
                "（不参与派生；登记以保持键集与规格一致）", "布尔"));
        list.add(new Observation(KiteAxis.GUID, "octant.pref.completion_rate_per_hour",
                "quest.stage_level",
                "分量（adherence = rate/(rate+K_ADH) 的分子）",
                "区间内主通道贴合度：任务阶段等级均值（取值 `quest_progressed.stage`，"
                        + "分母 = 该任务在本运行内观测到的最大阶段值）", "[0,1]"));
        return Collections.unmodifiableList(list);
    }

    private static List<AxisDef> buildAxes() {
        List<AxisDef> list = new ArrayList<>();
        list.add(new AxisDef(KiteAxis.PROG, "竖直（vertical）",
                "PROG = clamp(cover_weighted, 0, 1)；cover_weighted = completed_units / reachable_units",
                "[0,1]",
                "reachable_units == 0 ⇒ 整轴 suppressed（规格 §1 L43：不得记 0）；"
                        + "序列/窗口缺失 ⇒ suppressed（规格 §3.4 AD-7）"));
        list.add(new AxisDef(KiteAxis.SPON, "水平（horizontal）",
                "SPON = clamp(0.5·entropy_normalized + 0.3·weighted_coverage + 0.2·category_entropy, 0, 1)",
                "[0,1]",
                "某分量不可用 ⇒ 该项弃权并**重归一化**（规格 §1 L44）；"
                        + "三项全不可用 ⇒ 整轴 suppressed（且按 SG-7 = 显式抑制，不是省略）"));
        list.add(new AxisDef(KiteAxis.GUID, "深度（depth：垂直于竖直/水平所张平面）",
                "GUID = clamp(0.5·structure_density + 0.5·adherence, 0, 1)；"
                        + "structure_density = 0.5·√(dimension_variants)/(√(dimension_variants)+√(KITE_STRUCT_SAT))"
                        + " + 0.5·(1 − dimension_dominant_share)；"
                        + "adherence = completion_rate_per_hour / (completion_rate_per_hour + K_ADH)",
                "[0,1]",
                "两分量任一不可用 ⇒ 按 SG-7「弃权即抑制」处置（不得省略该项）；"
                        + "全不可用 ⇒ 整轴 suppressed"));
        return Collections.unmodifiableList(list);
    }

    private static List<SharedPrimitive> buildShared() {
        List<SharedPrimitive> list = new ArrayList<>();
        list.add(new SharedPrimitive("progress.advancement_catalog",
                "PROG + GUID",
                "PROG 用它当分母（reachable_units，取值层 = 比率的分母）；GUID 用它当结构目录上限"
                        + "（取值层 = 饱和计数的输入）。两者取值层不同且 GUID 不引用 "
                        + "octant.progress.* 家族的任何一个键 ⇒ 键级不相交；原语级按本行登记共用。"));
        return Collections.unmodifiableList(list);
    }

    public static List<Observation> observations() {
        return profileObservations();
    }

    public static List<AxisDef> axes() {
        return profileAxes();
    }

    public static List<Observation> profileObservations() {
        if (KiteAxisProfile.current() == KiteAxisProfile.LEGACY) {
            return OBSERVATIONS;
        }
        List<Observation> out = new ArrayList<>();
        for (Observation o : OBSERVATIONS) {
            if (o.axis() == KiteAxis.SPON && "octant.dispersion.entropy_normalized".equals(o.key())) {
                out.add(new Observation(o.axis(), o.key(), o.primitive(), o.role(),
                        "累计实体多样性：截止该窗的互异 `item_action.item` 数 k 与"
                                + "本运行物品目录 C 的 ln(1+k)/ln(1+C)", o.domain()));
                continue;
            }
            if (o.axis() == KiteAxis.SPON && "octant.breadth.weighted_coverage".equals(o.key())) {
                out.add(new Observation(o.axis(), o.key(), o.primitive(),
                        "注册但重定义档不参与派生（其原始量在会话起始窗恒为常数 ⇒ 见 KiteAxisProfile.REDEFINED_2026）",
                        o.base(), o.domain()));
                continue;
            }
            if (o.axis() == KiteAxis.SPON && "octant.pref.category_entropy".equals(o.key())) {
                out.add(new Observation(o.axis(), o.key(), o.primitive(),
                        "注册但重定义档不参与派生（同上）", o.base(), o.domain()));
                continue;
            }
            if (o.axis() == KiteAxis.GUID && "octant.pref.completion_rate_per_hour".equals(o.key())) {
                out.add(new Observation(o.axis(), o.key(), o.primitive(), o.role(),
                        "adherence（弃权即抑制；不作为 GUID 的必要条件）", o.domain()));
                continue;
            }
            out.add(o);
        }
        return Collections.unmodifiableList(out);
    }

    public static List<AxisDef> profileAxes() {
        if (KiteAxisProfile.current() == KiteAxisProfile.LEGACY) {
            return AXES;
        }
        List<AxisDef> out = new ArrayList<>();
        for (AxisDef d : AXES) {
            if (d.axis() == KiteAxis.SPON) {
                out.add(new AxisDef(d.axis(), d.orientation(),
                        "SPON = clamp(0.5·entropy_normalized + 0.3·weighted_coverage + 0.2·category_entropy, 0, 1)"
                                + "【重定义档：entropy_normalized 取累计实体多样性 ln(1+k)/ln(1+C)，"
                                + "weighted_coverage 与 category_entropy 两项弃权并重归一化】",
                        d.domain(),
                        "某分量不可用 ⇒ 该项弃权并**重归一化**（规格 §1 L44）；"
                                + "三项全不可用 ⇒ 整轴 suppressed（按 SG-7 = 显式抑制，不是省略）。"
                                + "重定义档下只有第一项参与 ⇒ 权重和为 0.5，按 §1 L44 重归一化后系数为 1.0"));
                continue;
            }
            if (d.axis() == KiteAxis.GUID) {
                out.add(new AxisDef(d.axis(), d.orientation(), d.formula(), d.domain(),
                        "structure_density 成立即可给出读数（adherence 不可用 ⇒ 按 SG-7 弃权即抑制，"
                                + "**不再作为整轴的必要条件**）；两者都不可用 ⇒ 整轴 suppressed"));
                continue;
            }
            out.add(d);
        }
        return Collections.unmodifiableList(out);
    }

    public static List<SharedPrimitive> sharedPrimitives() {
        return SHARED;
    }

    public static Set<String> keysOf(KiteAxis axis) {
        Set<String> out = new LinkedHashSet<>();
        for (Observation o : OBSERVATIONS) {
            if (o.axis() == axis) {
                out.add(o.key());
            }
        }
        return out;
    }

    public static Set<String> usedPrimitivesOf(KiteAxis axis) {
        Set<String> out = new LinkedHashSet<>();
        for (Observation o : OBSERVATIONS) {
            if (o.axis() == axis && !o.role().startsWith("注册但本切片不参与派生")) {
                out.add(o.primitive());
            }
        }
        return out;
    }

    public static Set<String> derivedKeysOf(KiteAxis axis) {
        Set<String> out = new LinkedHashSet<>();
        for (Observation o : OBSERVATIONS) {
            if (o.axis() == axis && !o.role().startsWith("注册但本切片不参与派生")) {
                out.add(o.key());
            }
        }
        return out;
    }

    public static Map<String, String> primitiveByKey() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Observation o : OBSERVATIONS) {
            out.put(o.key(), o.primitive());
        }
        return out;
    }

    public static Set<String> allKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (Observation o : OBSERVATIONS) {
            out.add(o.key());
        }
        return out;
    }

    static AssertionError assertSponWeightsReachable() {
        double[] used = KiteAxisMath.sponWeightsUsed();
        if (Math.abs(used[0] - KiteConstants.KITE_W_SPON_ENTROPY) > 1e-12d
                || Math.abs(used[1] - KiteConstants.KITE_W_SPON_BREADTH) > 1e-12d
                || Math.abs(used[2] - KiteConstants.KITE_W_SPON_PREF) > 1e-12d) {
            return new AssertionError("SPON 公式系数与登记权重不一致：公式用 ["
                    + KiteConstants.trim(used[0]) + ", " + KiteConstants.trim(used[1]) + ", "
                    + KiteConstants.trim(used[2]) + "]，登记表 ["
                    + KiteConstants.trim(KiteConstants.KITE_W_SPON_ENTROPY) + ", "
                    + KiteConstants.trim(KiteConstants.KITE_W_SPON_BREADTH) + ", "
                    + KiteConstants.trim(KiteConstants.KITE_W_SPON_PREF) + "]");
        }
        return null;
    }

    public static List<String> dumpAxes() {
        List<String> out = new ArrayList<>();
        for (AxisDef d : AXES) {
            out.add(d.axis() + " | " + d.orientation() + " | " + d.domain() + " | " + d.formula());
        }
        return out;
    }

    public static List<String> dumpKeys() {
        List<String> out = new ArrayList<>();
        for (KiteAxis axis : KiteAxis.values()) {
            List<String> items = new ArrayList<>();
            for (Observation o : OBSERVATIONS) {
                if (o.axis() == axis) {
                    items.add(o.key() + "(" + o.primitive() + ")");
                }
            }
            out.add(axis + " = " + items);
        }
        return out;
    }

    public static List<String> dumpPrimitiveCollision() {
        List<String> out = new ArrayList<>();
        KiteAxis[] all = KiteAxis.values();
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                Set<String> inter = new LinkedHashSet<>(usedPrimitivesOf(all[i]));
                inter.retainAll(usedPrimitivesOf(all[j]));
                out.add(all[i] + " ∩ " + all[j] + " = " + (inter.isEmpty() ? "{}" : inter.toString()));
            }
        }
        return out;
    }
}
