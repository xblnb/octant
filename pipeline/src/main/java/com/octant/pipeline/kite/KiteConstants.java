package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KiteConstants {

    public static final double KITE_W_SPON_ENTROPY = 0.5d;
    public static final double KITE_W_SPON_BREADTH = 0.3d;
    public static final double KITE_W_SPON_PREF = 0.2d;

    public static final double K_ADH = 1.0d;
    public static final double KITE_STRUCT_SAT = 1.0d;

    public static final double KITE_ORTHO_MAX = 0.25d;

    public static final double KITE_CONFOUND_MAX = 0.25d;
    public static final int KITE_ORTHO_MIN_N = 8;
    public static final double KITE_ACC_MAX = 12.0d;
    public static final double KITE_ACC_DETECT = 0.05d;
    public static final int KITE_ADAPT_MIN_SEG = 4;

    public static final int KITE_DT_REF_S = 600;
    public static final int KITE_DT_MIN_S = 150;
    public static final int KITE_DT_MAX_S = 900;
    public static final int KITE_DT_STEP_S = 150;

    public static final double KITE_SPRING_KAPPA = 0.6d;
    public static final double KITE_DAMPING_C = 1.2d;
    public static final double KITE_MASS_M = 1.0d;
    public static final double KITE_SOFT_ALPHA = 0.05d;
    public static final double KITE_SOFT_BETA = 0.5d;

    public static final double KITE_ZERO_LENGTH_EPS = 1e-12d;

    public static final String RC_INPUT_MISSING = "INPUT_MISSING";
    public static final String RC_INPUT_SNAPSHOT_ONLY = "INPUT_SNAPSHOT_ONLY";
    public static final String RC_SAMPLE_INSUFFICIENT = "SAMPLE_INSUFFICIENT_SESSIONS";

    public static final String BASIS_ACTIVE_SECONDS = "active_seconds";
    public static final String BASIS_SAMPLE_POINTS = "sample_points";

    private KiteConstants() {
    }

    public record KiteConstant(String name, double value, String unit, String specRef, String relation,
                               boolean inS11List) {
    }

    public static final List<String> DEFERRED_CONSTANTS = List.of(
            "KITE_STATE_SMOOTH_WIN",
            "KITE_KINEMATIC_REF_MIN",
            "KITE_STATE_EPS",
            "KITE_STATE_MIN_SEG_S",
            "KITE_STATE_MIN_SEG",
            "KITE_STATE_TURN_DEG",
            "KITE_STATE_R_MIN",
            "KITE_STATE_R_MAX",
            "KITE_STATE_STALL_S",
            "KITE_STATE_S_MIN",
            "KITE_STATE_S_MAX",
            "KITE_STATE_D_MIN",
            "KITE_MARK_N_MAX",
            "KITE_MARK_DENSITY_MAX",
            "KITE_PHASE_MIN_SEG",
            "KITE_PHASE_MIN_SPAN",
            "KITE_PHASE_MAX_M");

    public static final int SPEC_S1_1_CONSTANT_COUNT = 13;

    private static final List<KiteConstant> REGISTRY = buildRegistry();

    private static List<KiteConstant> buildRegistry() {
        List<KiteConstant> list = new ArrayList<>();
        list.add(new KiteConstant("KITE_W_SPON_ENTROPY", KITE_W_SPON_ENTROPY, "无量纲",
                "spec §1.1 L50/L59", "0 ≤ v ≤ 1", true));
        list.add(new KiteConstant("KITE_W_SPON_BREADTH", KITE_W_SPON_BREADTH, "无量纲",
                "spec §1.1 L50/L59", "0 ≤ v ≤ 1", true));
        list.add(new KiteConstant("KITE_W_SPON_PREF", KITE_W_SPON_PREF, "无量纲",
                "spec §1.1 L50/L59", "0 ≤ v ≤ 1", true));
        list.add(new KiteConstant("K_ADH", K_ADH, "条/小时",
                "spec §1.1 L51/L60", "> 0", true));
        list.add(new KiteConstant("KITE_STRUCT_SAT", KITE_STRUCT_SAT, "个",
                "spec §1.1 L52/L61", "> 0", true));
        list.add(new KiteConstant("KITE_ORTHO_MAX", KITE_ORTHO_MAX, "无量纲",
                "spec §1.1 L53 / §1.1 L63 + §2.3 OR-1 L89", "0 < v < 1", true));
        list.add(new KiteConstant("KITE_ORTHO_MIN_N", KITE_ORTHO_MIN_N, "个",
                "spec §1.1 L53 + §2.3 OR-3 L93", "≥ 8", true));
        list.add(new KiteConstant("KITE_CONFOUND_MAX", KITE_CONFOUND_MAX, "无量纲",
                "spec §2.3 + §24.7 P-3（新增）", "0 < v < 1 且 v == 0.25（登记值）", false));
        list.add(new KiteConstant("KITE_ACC_MAX", KITE_ACC_MAX, "无量纲",
                "spec §1.1 L53 / §1.1 L62 + §5.2 KD-3 L236", "> 0", true));
        list.add(new KiteConstant("KITE_ACC_DETECT", KITE_ACC_DETECT, "无量纲",
                "spec §1.1 L53 / §1.1 L63 + §5.2 KD-4 L237", "0 < v ≤ KITE_ACC_MAX", true));
        list.add(new KiteConstant("KITE_ADAPT_MIN_SEG", KITE_ADAPT_MIN_SEG, "个",
                "spec §1.1 L53 + §3.2 L138/L141", "≥ 2", true));
        list.add(new KiteConstant("KITE_DT_MIN_S", KITE_DT_MIN_S, "秒",
                "spec §1.1 L53 + §3.1 L126", "≥ 150 且 = KITE_DT_REF_S/4", true));
        list.add(new KiteConstant("KITE_DT_MAX_S", KITE_DT_MAX_S, "秒",
                "spec §1.1 L53 + §3.1 L127", "≤ 1800 且 ≥ KITE_DT_REF_S", true));
        list.add(new KiteConstant("KITE_DT_STEP_S", KITE_DT_STEP_S, "秒",
                "spec §1.1 L53 + §3.1 L128", "≥ 60 且整除 Δ₀", true));
        list.add(new KiteConstant("KITE_DT_REF_S", KITE_DT_REF_S, "秒",
                "spec §3.1 L124 + §0.1", "= 600（基线 10 分钟，用户原话）", false));

        list.add(new KiteConstant("KITE_SPRING_KAPPA", KITE_SPRING_KAPPA, "无量纲",
                "spec §5.1 L224", "> 0", false));
        list.add(new KiteConstant("KITE_DAMPING_C", KITE_DAMPING_C, "无量纲",
                "spec §5.1 L224", "> 0", false));
        list.add(new KiteConstant("KITE_MASS_M", KITE_MASS_M, "无量纲",
                "spec §5.1 L225", "> 0", false));
        list.add(new KiteConstant("KITE_SOFT_ALPHA", KITE_SOFT_ALPHA, "无量纲",
                "spec §5.1 L224", "≥ 0 且 ≤ β", false));
        list.add(new KiteConstant("KITE_SOFT_BETA", KITE_SOFT_BETA, "无量纲",
                "spec §5.1 L224", "> 0 且 ≥ α", false));
        list.add(new KiteConstant("KITE_ZERO_LENGTH_EPS", KITE_ZERO_LENGTH_EPS, "无量纲（[0,1]³ 内弧长）",
                "spec §5.1 L222 + §5.2 KD-1 L235", "> 0 且极小", false));
        return Collections.unmodifiableList(list);
    }

    public static int countDeclaredS11() {
        int n = 0;
        for (KiteConstant c : REGISTRY) {
            if (c.inS11List()) {
                n++;
            }
        }
        return n;
    }

    public static int countBeyondS11() {
        return REGISTRY.size() - countDeclaredS11();
    }

    public static List<KiteConstant> registry() {
        return REGISTRY;
    }

    public static Double valueOf(String name) {
        for (KiteConstant c : REGISTRY) {
            if (c.name().equals(name)) {
                return c.value();
            }
        }
        return null;
    }

    public static List<String> verifyConstants() {
        List<String> asserted = new ArrayList<>();
        double sum = KITE_W_SPON_ENTROPY + KITE_W_SPON_BREADTH + KITE_W_SPON_PREF;
        if (Math.abs(sum - 1.0d) > 1e-12d) {
            throw new AssertionError("ΣKITE_W_SPON_* == 1.0 被破坏：实际 " + sum);
        }
        asserted.add("ΣKITE_W_SPON_* == 1.0");

        if (!(K_ADH > 0d)) {
            throw new AssertionError("K_ADH 必须 > 0，实际 " + K_ADH);
        }
        if (!(KITE_STRUCT_SAT > 0d)) {
            throw new AssertionError("KITE_STRUCT_SAT 必须 > 0，实际 " + KITE_STRUCT_SAT);
        }
        asserted.add("K_ADH > 0 && KITE_STRUCT_SAT > 0");

        if (!(KITE_ACC_DETECT > 0d && KITE_ACC_DETECT <= KITE_ACC_MAX)) {
            throw new AssertionError("0 < KITE_ACC_DETECT ≤ KITE_ACC_MAX 被破坏：detect="
                    + KITE_ACC_DETECT + ", max=" + KITE_ACC_MAX);
        }
        asserted.add("0 < KITE_ACC_DETECT ≤ KITE_ACC_MAX");

        if (KITE_DT_REF_S != 600) {
            throw new AssertionError("基线间隔 Δ₀ 必须 = 600 s（用户原话 10 分钟），实际 " + KITE_DT_REF_S);
        }
        if (KITE_DT_MIN_S < 150) {
            throw new AssertionError("KITE_DT_MIN_S 必须 ≥ 150 s（规格 §3.1 L126），实际 " + KITE_DT_MIN_S);
        }
        if (KITE_DT_MAX_S > 1800) {
            throw new AssertionError("KITE_DT_MAX_S 必须 ≤ 1800 s（规格 §3.1 L127），实际 " + KITE_DT_MAX_S);
        }
        if (KITE_DT_MIN_S > KITE_DT_REF_S || KITE_DT_REF_S > KITE_DT_MAX_S) {
            throw new AssertionError("必须 KITE_DT_MIN_S ≤ Δ₀ ≤ KITE_DT_MAX_S，实际 "
                    + KITE_DT_MIN_S + " / " + KITE_DT_REF_S + " / " + KITE_DT_MAX_S);
        }
        if (KITE_DT_STEP_S < 60) {
            throw new AssertionError("KITE_DT_STEP_S 必须 ≥ 60 s（规格 §3.1 L128），实际 " + KITE_DT_STEP_S);
        }
        if (KITE_DT_REF_S % KITE_DT_STEP_S != 0) {
            throw new AssertionError("Δ₀ 必须能被步长整除，否则两向对照不可复算："
                    + KITE_DT_REF_S + " % " + KITE_DT_STEP_S);
        }
        asserted.add("KITE_DT_MIN_S ≤ Δ₀(=600) ≤ KITE_DT_MAX_S && KITE_DT_STEP_S ≥ 60 && Δ₀ % STEP == 0");

        if (!(KITE_ORTHO_MAX > 0d && KITE_ORTHO_MAX < 1d)) {
            throw new AssertionError("KITE_ORTHO_MAX 必须 ∈ (0,1)，实际 " + KITE_ORTHO_MAX);
        }
        if (KITE_ORTHO_MAX != 0.25d) {
            throw new AssertionError("KITE_ORTHO_MAX 登记值为 0.25（规格 §2.3 推荐量级），实际 "
                    + KITE_ORTHO_MAX + " —— 改阈值必须先改本断言并留痕");
        }
        if (!(KITE_CONFOUND_MAX > 0d && KITE_CONFOUND_MAX < 1d)) {
            throw new AssertionError("KITE_CONFOUND_MAX 必须 ∈ (0,1)，实际 " + KITE_CONFOUND_MAX);
        }
        if (KITE_CONFOUND_MAX != 0.25d) {
            throw new AssertionError("KITE_CONFOUND_MAX 登记值为 0.25（与 KITE_ORTHO_MAX 同值但角色不同："
                    + "通过判据 vs 登记门限），实际 " + KITE_CONFOUND_MAX + " —— 改门限必须先改本断言并留痕");
        }
        if (KITE_ORTHO_MIN_N < 8) {
            throw new AssertionError("KITE_ORTHO_MIN_N 必须 ≥ 8（规格 §2.3 建议），实际 " + KITE_ORTHO_MIN_N);
        }
        if (KITE_ADAPT_MIN_SEG < 2) {
            throw new AssertionError("KITE_ADAPT_MIN_SEG 必须 ≥ 2（否则分位数无定义），实际 " + KITE_ADAPT_MIN_SEG);
        }
        if (KITE_ADAPT_MIN_SEG > KITE_ORTHO_MIN_N) {
            throw new AssertionError("KITE_ADAPT_MIN_SEG 必须 ≤ KITE_ORTHO_MIN_N，否则退化区间永远先于曲线成立："
                    + KITE_ADAPT_MIN_SEG + " > " + KITE_ORTHO_MIN_N);
        }
        asserted.add("KITE_ORTHO_MAX ∈ (0,1) && KITE_ORTHO_MAX == 0.25(登记值) && "
                + "KITE_CONFOUND_MAX ∈ (0,1) && KITE_CONFOUND_MAX == 0.25(登记值，角色=登记门限) && "
                + "KITE_ORTHO_MIN_N ≥ 8 && 2 ≤ KITE_ADAPT_MIN_SEG ≤ KITE_ORTHO_MIN_N");

        if (!(KITE_SPRING_KAPPA > 0d && KITE_DAMPING_C > 0d && KITE_MASS_M > 0d)) {
            throw new AssertionError("κ/c/m 必须 > 0：κ=" + KITE_SPRING_KAPPA + ", c=" + KITE_DAMPING_C
                    + ", m=" + KITE_MASS_M);
        }
        if (!(KITE_SOFT_BETA >= KITE_SOFT_ALPHA && KITE_SOFT_ALPHA >= 0d)) {
            throw new AssertionError("必须 0 ≤ α ≤ β：α=" + KITE_SOFT_ALPHA + ", β=" + KITE_SOFT_BETA);
        }
        asserted.add("κ>0 && c>0 && m>0 && 0 ≤ α ≤ β");

        double worst = KiteDynamics.worstCaseAccelerationMagnitude();
        if (!(worst <= KITE_ACC_MAX)) {
            throw new AssertionError("KITE_ACC_MAX 冻不住最坏角点的 |a|：worst=" + worst
                    + " > KITE_ACC_MAX=" + KITE_ACC_MAX + "（KD-3 将不可判定）");
        }
        asserted.add("worstCase(|a|) ≤ KITE_ACC_MAX");

        Map<String, Integer> seen = new LinkedHashMap<>();
        for (KiteConstant c : REGISTRY) {
            if (seen.put(c.name(), 1) != null) {
                throw new AssertionError("常量登记表出现重名：" + c.name());
            }
            if (Double.isNaN(c.value()) || Double.isInfinite(c.value())) {
                throw new AssertionError("常量必须有限：" + c.name() + " = " + c.value());
            }
        }
        if (countDeclaredS11() != SPEC_S1_1_CONSTANT_COUNT) {
            throw new AssertionError("规格 §1.1 声明 " + SPEC_S1_1_CONSTANT_COUNT
                    + " 个具名常量，登记表里 `inS11List=true` 的行却有 " + countDeclaredS11() + " 行");
        }
        asserted.add("§1.1 清单行数 == " + SPEC_S1_1_CONSTANT_COUNT + "（共登记 " + REGISTRY.size()
                + " 行；另 " + countBeyondS11() + " 行由 §3.1/§5.1 要求冻结）");

        AssertionError drift = KiteAxisDefinition.assertSponWeightsReachable();
        if (drift != null) {
            throw drift;
        }
        asserted.add("SPON 公式内的三项权重 == 登记表三项权重");

        double[] guid = KiteAxisMath.guidWeightsUsed();
        for (double w : guid) {
            if (Math.abs(w - 0.5d) > 1e-12d) {
                throw new AssertionError("GUID 公式系数必须逐字等于规格 §1 L45 原文的 0.5，实际 " + w);
            }
        }
        asserted.add("GUID 公式的四个系数 == 0.5（规格 §1 L45 原文）");

        if (Math.abs(KITE_ORTHO_MAX - 0.25d) > 1e-12d) {
            throw new AssertionError("KITE_ORTHO_MAX 必须保持 0.25（规格 §2.3 OR-1）；实际 " + KITE_ORTHO_MAX
                    + " ⇒ 改阈值属于「改尺子」，必须红");
        }
        if (KITE_ORTHO_MIN_N != 8) {
            throw new AssertionError("KITE_ORTHO_MIN_N 必须保持 8；实际 " + KITE_ORTHO_MIN_N);
        }
        asserted.add("KITE_ORTHO_MAX == 0.25 && KITE_ORTHO_MIN_N == 8（阈值钉死；改动即红）");

        if (Math.abs(KITE_CONFOUND_MAX - 0.25d) > 1e-12d) {
            throw new AssertionError("KITE_CONFOUND_MAX 必须保持 0.25（`OR-CF2` 的登记门限）；实际 "
                    + KITE_CONFOUND_MAX);
        }
        asserted.add("KITE_CONFOUND_MAX == 0.25（与 KITE_ORTHO_MAX 数值相同、角色不同："
                + "轴间通过判据 vs 轴×会话序号的登记门限）");

        return Collections.unmodifiableList(asserted);
    }

    public static List<String> dump() {
        List<String> out = new ArrayList<>();
        for (KiteConstant c : REGISTRY) {
            out.add(c.name() + "=" + trim(c.value()) + " " + c.unit() + "  [" + c.specRef() + "; " + c.relation() + "]");
        }
        return out;
    }

    static String trim(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
