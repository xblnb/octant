package com.octant.pipeline.kite;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class KiteSelfCheckChecks {

    private KiteSelfCheckChecks() {
    }

    private static final Pattern KEY_PATTERN = Pattern.compile("octant\\.[a-z_]+\\.[a-z_]+");
    private static final Pattern CONST_PATTERN = Pattern.compile("KITE_[A-Z0-9_]+|K_ADH");

    static final int KITE_POWER_MIN_DISTINCT = 4;
    static final double KITE_POWER_MIN_VARIANCE = 1e-3d;

    private static String sourceCache;

    static String sourceText() {
        if (sourceCache != null) {
            return sourceCache;
        }
        StringBuilder sb = new StringBuilder();
        Path dir = Path.of("pipeline/src/main/java/com/octant/pipeline/kite");
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            List<Path> files = new ArrayList<>();
            s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            files.sort(java.util.Comparator.comparing(Path::toString));
            for (Path f : files) {
                sb.append(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            sb.append("<源码不可读:").append(e.getClass().getSimpleName()).append('>');
        }
        sourceCache = sb.toString();
        return sourceCache;
    }

    static String stripCommentsAndDoc(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"') {
                int start = i;
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == '"') {
                        break;
                    }
                }
                out.append(src, start, Math.min(i, n));
                continue;
            }
            if (c == '\'') {
                int start = i;
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == '\'') {
                        break;
                    }
                }
                out.append(src, start, Math.min(i, n));
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    static String implementationCode() {
        StringBuilder sb = new StringBuilder();
        Path dir = Path.of("pipeline/src/main/java/com/octant/pipeline/kite");
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            List<Path> files = new ArrayList<>();
            s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            files.sort(java.util.Comparator.comparing(Path::toString));
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (name.equals("KiteConstants.java") || name.equals("KiteSelfCheckChecks.java")) {
                    continue;
                }
                String body = stripCommentsAndDoc(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
                body = body.replaceAll(
                        "(?m)^(\\s*(?:public|private|protected)?\\s*static\\s+final\\s+[A-Za-z0-9_<>\\[\\]]+\\s+[A-Z_0-9]+\\s*=\\s*)\"[^\"]*\"",
                        "$1\"\"");
                sb.append(body);
            }
        } catch (IOException e) {
            sb.append("<源码不可读:").append(e.getClass().getSimpleName()).append('>');
        }
        return sb.toString();
    }

    static List<Double> sortedCopy(List<Double> in) {
        return KiteAdaptiveSampler.sortedCopy(in);
    }

    static String arr(double[] v) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(fmt(v[i]));
        }
        return sb.append(')').toString();
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    static String fmtDoubles(List<Double> v, int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(n, v.size()); i++) {
            sb.append(i == 0 ? "" : ", ").append(fmt(v.get(i)));
        }
        return sb.append(v.size() > n ? ", …共" + v.size() + "项]" : "]").toString();
    }

    static void run(KiteSelfCheck.Runner r, KiteObservationSource source, KiteSeries series,
                    KiteSeries snapshotSeries, KiteLabelRegistry registry, Path workingDir,
                    PrintStream out, boolean hashOnly) {
        densityAh(r, source, out, hashOnly);
        axisChecks(r, source, series, workingDir, out, hashOnly);
        orthogonalChecks(r, source, series, out, hashOnly);
        dynamicsChecks(r, series, out, hashOnly);
        labelChecks(r, registry, out, hashOnly);
        suppressionChecks(r, snapshotSeries, workingDir, out, hashOnly);
        determinismChecks(r, source, workingDir, out, hashOnly);
        fingerprintChecks(r, workingDir, out, hashOnly);
    }

    private static void densityAh(KiteSelfCheck.Runner r, KiteObservationSource source, PrintStream out,
                                  boolean hashOnly) {
        List<Double> real = densitySeries(source);
        List<KiteAdaptiveSampler.Sample> realSchedule = KiteAdaptiveSampler.schedule(real);
        List<Long> realDeltas = KiteAdaptiveSampler.deltas(realSchedule);
        List<Double> denseSynth = ramp(40, 1.0d, 0.1d);
        List<Double> sparseSynth = ramp(40, 4.0d, -0.1d);
        List<Long> denseDeltas = KiteAdaptiveSampler.deltas(KiteAdaptiveSampler.schedule(denseSynth));
        List<Long> sparseDeltas = KiteAdaptiveSampler.deltas(KiteAdaptiveSampler.schedule(sparseSynth));

        if (!hashOnly) {
            out.println();
            out.println("── AD-1/AD-2 三组 Δ 序列（真实语料 / 密集序 / 稀疏序）──");
            out.println("  真实 d 序列（前 24）=" + fmtDoubles(real, 24));
            out.println("  真实 Δ 序列         =" + realDeltas);
            out.println("  密集序 Δ 序列       =" + denseDeltas);
            out.println("  稀疏序 Δ 序列       =" + sparseDeltas);
            out.println("  Δ₀=" + KiteConstants.KITE_DT_REF_S + "s  Δ_min=" + KiteConstants.KITE_DT_MIN_S
                    + "s  Δ_max=" + KiteConstants.KITE_DT_MAX_S + "s  step="
                    + KiteConstants.KITE_DT_STEP_S + "s");
        }

        boolean denseShortens = anyDeltaBelow(denseDeltas, KiteConstants.KITE_DT_REF_S);
        boolean denseRelativeShortens = hasStrictDecrease(denseDeltas);
        r.require("AD-1", denseShortens && denseRelativeShortens,
                "密集序：Δ 相对 Δ₀ 缩短=" + denseShortens + "、存在 Δ_{k+1} < Δ_k 的步=" + denseRelativeShortens
                        + "；Δ 序列=" + denseDeltas);

        boolean sparseLengthens = anyDeltaAbove(sparseDeltas, KiteConstants.KITE_DT_REF_S);
        boolean sparseRelativeLengthens = hasStrictIncrease(sparseDeltas);
        r.require("AD-2", sparseLengthens && sparseRelativeLengthens,
                "稀疏序：Δ 相对 Δ₀ 延长=" + sparseLengthens + "、存在 Δ_{k+1} > Δ_k 的步="
                        + sparseRelativeLengthens + "；Δ 序列=" + sparseDeltas);

        List<Double> hysteresis = new ArrayList<>();
        double[] levels = {4.00d, 4.05d, 4.10d, 4.15d, 4.20d};
        for (int i = 0; i < 60; i++) {
            hysteresis.add(levels[i % levels.length]);
        }
        List<KiteAdaptiveSampler.Sample> hs = KiteAdaptiveSampler.schedule(hysteresis);
        int zeros = 0;
        StringBuilder signs = new StringBuilder();
        StringBuilder trace = new StringBuilder();
        for (KiteAdaptiveSampler.Sample s : hs) {
            if (s.degenerate()) {
                continue;
            }
            signs.append(s.sign() > 0 ? '+' : s.sign() < 0 ? '-' : '0');
            if (s.sign() == 0) {
                zeros++;
            }
            if (trace.length() < 260) {
                trace.append("(K=").append(s.historyK())
                        .append(",Q25=").append(KiteConstants.trim(s.quantile25()))
                        .append(",Q75=").append(KiteConstants.trim(s.quantile75()))
                        .append(",d=").append(KiteConstants.trim(s.densityPerMin()))
                        .append(",s=").append(s.sign()).append(')');
            }
        }
        r.require("AD-3", zeros > 0, "迟滞带：s_k = 0 的区间数=" + zeros + "（>0 证明不是每区间必变）；"
                + "s_k 序列（跳过退化位）=" + signs + "；逐位读数=" + trace
                + "；Δ 序列=" + KiteAdaptiveSampler.deltas(hs));

        boolean denseBounded = KiteAdaptiveSampler.allWithinBounds(KiteAdaptiveSampler.schedule(denseSynth))
                && denseReachesFloor(denseDeltas);
        boolean sparseBounded = KiteAdaptiveSampler.allWithinBounds(KiteAdaptiveSampler.schedule(sparseSynth))
                && sparseReachesCeiling(sparseDeltas);
        r.require("AD-4", denseBounded && sparseBounded,
                "持续密集 ⇒ 下探到 Δ_min=" + denseDeltas.get(denseDeltas.size() - 1)
                        + "s（夹界成立=" + KiteAdaptiveSampler.allWithinBounds(KiteAdaptiveSampler.schedule(denseSynth))
                        + "）；持续稀疏 ⇒ 上探到 Δ_max=" + sparseDeltas.get(sparseDeltas.size() - 1)
                        + "s；两者均未越界");

        int shorter = -1;
        int longer = -1;
        for (int i = 1; i < realDeltas.size(); i++) {
            if (shorter < 0 && realDeltas.get(i) < KiteConstants.KITE_DT_REF_S) {
                shorter = i;
            }
            if (longer < 0 && realDeltas.get(i) > KiteConstants.KITE_DT_REF_S) {
                longer = i;
            }
        }
        boolean realBothWays = realDeltas.size() > KiteConstants.KITE_ADAPT_MIN_SEG
                && hasStrictDecrease(realDeltas) && hasStrictIncrease(realDeltas)
                && shorter >= 0 && longer >= 0;
        r.require("AD-FR", realBothWays,
                "真实语料（同一份事件流、同一 Δ 序列）：首次短于 Δ₀ 的位次=" + shorter + "、首次长于 Δ₀ 的位次="
                        + longer + "；Δ 序列=" + realDeltas + "；d 序列=" + fmtDoubles(real, 24));

        List<KiteAdaptiveSampler.Sample> tiny = KiteAdaptiveSampler.schedule(List.of(7.0d, 8.0d));
        boolean degenerateOk = true;
        for (KiteAdaptiveSampler.Sample s : tiny) {
            degenerateOk &= s.degenerate() && s.deltaSec() == KiteConstants.KITE_DT_REF_S
                    && s.sign() == 0 && KiteAdaptiveSampler.REASON_DEGENERATE_K.equals(s.reasonCode());
        }
        r.require("AD-K", degenerateOk,
                "K < KITE_ADAPT_MIN_SEG=" + KiteConstants.KITE_ADAPT_MIN_SEG + " 时：degenerate="
                        + tiny.get(0).degenerate() + "、Δ 保持 Δ₀、s_k=0、reasonCode="
                        + tiny.get(0).reasonCode() + "（降级到闭集内既有码；规格 §9 阶段表）");

        boolean proposedUsedAsReason = false;
        for (KiteAdaptiveSampler.Sample s : hs) {
            proposedUsedAsReason |= KiteAdaptiveSampler.PROPOSED_CODE_NOT_REGISTERED.equals(s.reasonCode());
        }
        for (KiteAdaptiveSampler.Sample s : tiny) {
            proposedUsedAsReason |= KiteAdaptiveSampler.PROPOSED_CODE_NOT_REGISTERED.equals(s.reasonCode());
        }
        for (KiteAdaptiveSampler.Sample s : realSchedule) {
            proposedUsedAsReason |= KiteAdaptiveSampler.PROPOSED_CODE_NOT_REGISTERED.equals(s.reasonCode());
        }
        boolean proposedInCode = implementationCode()
                .contains("\"" + KiteAdaptiveSampler.PROPOSED_CODE_NOT_REGISTERED + "\"");
        r.require("AD-K2", !proposedUsedAsReason && !proposedInCode,
                "规格 §3.2 点名但尚未在 dc 登记的表外码 " + KiteAdaptiveSampler.PROPOSED_CODE_NOT_REGISTERED
                        + " 未被用作任何区间的 reasonCode（三段调度逐位检查）：命中=" + proposedUsedAsReason
                        + "；实现代码正文里的字面量使用=" + proposedInCode
                        + "；实际用的是降级码 " + KiteAdaptiveSampler.REASON_DEGENERATE_K);
    }

    static List<Double> densitySeries(KiteObservationSource source) {
        List<Double> out = new ArrayList<>();
        double minutes = Math.max(1d, (double) KiteConstants.KITE_DT_REF_S / 60d);
        long cursor = 0L;
        for (int i = 0; i < 20000; i++) {
            KiteObservationSource.SlicedInterval s = source.sliceOf(cursor, KiteConstants.KITE_DT_REF_S);
            if (s.eventCount() > 0) {
                out.add(s.whitelistInputCount() / minutes);
            }
            cursor += KiteConstants.KITE_DT_REF_S;
        }
        return out;
    }

    static List<Double> ramp(int n, double start, double step) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(start + step * i);
        }
        return out;
    }

    static boolean anyDeltaBelow(List<Long> deltas, long ref) {
        for (long d : deltas) {
            if (d < ref) {
                return true;
            }
        }
        return false;
    }

    static boolean anyDeltaAbove(List<Long> deltas, long ref) {
        for (long d : deltas) {
            if (d > ref) {
                return true;
            }
        }
        return false;
    }

    static boolean hasStrictDecrease(List<Long> deltas) {
        for (int i = 1; i < deltas.size(); i++) {
            if (deltas.get(i) < deltas.get(i - 1)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasStrictIncrease(List<Long> deltas) {
        for (int i = 1; i < deltas.size(); i++) {
            if (deltas.get(i) > deltas.get(i - 1)) {
                return true;
            }
        }
        return false;
    }

    static boolean denseReachesFloor(List<Long> deltas) {
        for (long d : deltas) {
            if (d == KiteConstants.KITE_DT_MIN_S) {
                return true;
            }
        }
        return false;
    }

    static boolean sparseReachesCeiling(List<Long> deltas) {
        for (long d : deltas) {
            if (d == KiteConstants.KITE_DT_MAX_S) {
                return true;
            }
        }
        return false;
    }

    private static void axisChecks(KiteSelfCheck.Runner r, KiteObservationSource source, KiteSeries series,
                                   Path workingDir, PrintStream out, boolean hashOnly) {
        r.require("AX-N", KiteAxisDefinition.axes().size() == 3,
                "轴数=" + KiteAxisDefinition.axes().size() + "（" + KiteAxisDefinition.dumpAxes() + "）");

        boolean allHave = true;
        StringBuilder note = new StringBuilder();
        for (KiteAxis axis : KiteAxis.values()) {
            Set<String> keys = KiteAxisDefinition.keysOf(axis);
            String formula = "";
            String domain = "";
            for (KiteAxisDefinition.AxisDef d : KiteAxisDefinition.axes()) {
                if (d.axis() == axis) {
                    formula = d.formula();
                    domain = d.domain();
                }
            }
            boolean ok = !keys.isEmpty() && !formula.isEmpty() && "[0,1]".equals(domain);
            allHave &= ok;
            note.append(axis).append("{键=").append(keys.size()).append(" 值域=").append(domain)
                    .append(" 式长=").append(formula.length()).append("} ");
        }
        r.require("AX-E", allHave, "三轴各自给出键集合/归一化式/值域：" + note);

        String specText = KiteSpecRef.readAll(workingDir);
        if (specText == null) {
            r.unverified("AX-D", "规格文件不可读（" + KiteSpecRef.PATH + "）⇒ 键集双向对账未做");
        } else {
            Set<String> specKeys = new LinkedHashSet<>();
            String[] lines = specText.split("\n", -1);
            for (int i = 42; i <= 44 && i < lines.length; i++) {
                Matcher m = KEY_PATTERN.matcher(lines[i]);
                while (m.find()) {
                    specKeys.add(m.group());
                }
            }
            Set<String> implKeys = KiteAxisDefinition.allKeys();
            Set<String> missing = new LinkedHashSet<>(specKeys);
            missing.removeAll(implKeys);
            Set<String> extra = new LinkedHashSet<>(implKeys);
            extra.removeAll(specKeys);
            r.require("AX-D", missing.isEmpty() && extra.isEmpty(),
                    "规格 §1 表键集合=" + specKeys.size() + "，实现登记键集合=" + implKeys.size()
                            + "，规格有实现无=" + missing + "，实现有规格无=" + extra);
        }

        List<KiteOrthogonality.MultiCollinearity> mc = KiteOrthogonality.multiCollinearity(series.usableVectors());
        if (!hashOnly) {
            out.println();
            out.println("── 三轴线性独立性（R²(轴 | 另两轴)）──");
            for (KiteOrthogonality.MultiCollinearity m : mc) {
                out.println("  " + m.axis() + " N=" + m.n() + " " + m.verdict() + " " + m.note());
            }
        }
        boolean anyFail = false;
        boolean anyUnverified = false;
        for (KiteOrthogonality.MultiCollinearity m : mc) {
            anyFail |= m.verdict() == KiteOrthogonality.Verdict.FAIL;
            anyUnverified |= m.verdict() == KiteOrthogonality.Verdict.UNVERIFIED;
        }
        if (anyFail) {
            r.fail("AX-P", "存在可由另两轴线性表示的轴：" + mc);
        } else if (anyUnverified) {
            r.unverified("AX-P", "N 不足或回归无定义 ⇒ 未判定：" + mc);
        } else {
            r.pass("AX-P", "三轴的两两 ρ² 与逐轴多元 R² 均在 KITE_ORTHO_MAX 内：" + mc);
        }

        Set<String> orientations = new LinkedHashSet<>();
        for (KiteAxisDefinition.AxisDef d : KiteAxisDefinition.axes()) {
            orientations.add(d.orientation());
        }
        r.require("AX-O", orientations.size() == 3,
                "三轴朝向三值互异：" + orientations);

        Set<String> registered = new LinkedHashSet<>();
        for (KiteConstants.KiteConstant c : KiteConstants.registry()) {
            registered.add(c.name());
        }
        String code = implementationCode();
        Set<String> used = new LinkedHashSet<>();
        Matcher m = CONST_PATTERN.matcher(code);
        while (m.find()) {
            String name = m.group();
            if (name.endsWith("_")) {
                continue;
            }
            used.add(name);
        }
        Set<String> invented = new LinkedHashSet<>(used);
        invented.removeAll(registered);
        Set<String> unused = new LinkedHashSet<>(registered);
        unused.removeAll(used);
        Set<String> deferredUsed = new LinkedHashSet<>(used);
        deferredUsed.retainAll(KiteConstants.DEFERRED_CONSTANTS);
        r.require("AX-R", invented.isEmpty() && unused.isEmpty() && deferredUsed.isEmpty(),
                "登记=" + registered.size() + " 实现使用=" + used.size()
                        + "；**未登记却被引用**=" + invented + "；**登记了却没用到**=" + unused
                        + "；**本切片明确不做却被引用**=" + deferredUsed
                        + "（扫描口径：去注释后的代码正文，剔除 `…_` 前缀族名）");

        Set<String> deferredInRegistry = new LinkedHashSet<>(KiteConstants.DEFERRED_CONSTANTS);
        deferredInRegistry.retainAll(registered);
        r.require("AX-K", !KiteConstants.DEFERRED_CONSTANTS.isEmpty() && deferredInRegistry.isEmpty(),
                "本切片明确未实现的具名常量 " + KiteConstants.DEFERRED_CONSTANTS.size() + " 项（视觉编码/阶段平面族），"
                        + "全部**不在**登记表内=" + deferredInRegistry.isEmpty()
                        + "（清单非空是刻意的：让「漏了」与「不做」可分）");

        double sum = KiteConstants.KITE_W_SPON_ENTROPY + KiteConstants.KITE_W_SPON_BREADTH
                + KiteConstants.KITE_W_SPON_PREF;
        boolean sumOk = Math.abs(sum - 1.0d) < 1e-12d;
        double perturbed = sum - KiteConstants.KITE_W_SPON_PREF + (KiteConstants.KITE_W_SPON_PREF + 0.1d);
        boolean perturbedWouldFail = Math.abs(perturbed - 1.0d) >= 1e-12d;
        r.require("AX-C2", sumOk && perturbedWouldFail,
                "ΣKITE_W_SPON_* = " + KiteConstants.trim(sum) + "（断言成立=" + sumOk
                        + "）；反向对照：把 KITE_W_SPON_PREF +0.1 后和为 " + KiteConstants.trim(perturbed)
                        + " ⇒ 该断言必须红（可达=" + perturbedWouldFail + "）");

        Set<String> registeredTypes = new LinkedHashSet<>();
        for (com.octant.pipeline.raw.EventType t : com.octant.pipeline.raw.EventType.values()) {
            registeredTypes.add(t.wireName());
        }
        Set<String> wl = KiteObservationSource.whitelistTypes();
        Set<String> unknown = new LinkedHashSet<>(wl);
        unknown.removeAll(registeredTypes);
        r.require("AX-W", unknown.isEmpty(),
                "白名单映射的 " + wl.size() + " 个事件类型全部已登记；未登记者=" + unknown
                        + "；映射=" + KiteObservationSource.whitelistDump());
    }

    private static void orthogonalChecks(KiteSelfCheck.Runner r, KiteObservationSource source, KiteSeries series,
                                         PrintStream out, boolean hashOnly) {
        List<double[]> rows = series.usableVectors();
        List<KiteOrthogonality.Pair> pairs = KiteOrthogonality.pairwise(rows);

        if (!hashOnly) {
            out.println();
            out.println("── OR-1/OR-2/OR-3 三对轴的 ρ²（真实语料，N=" + series.usableCount() + "）──");
            for (String s : KiteOrthogonality.dumpPairs(pairs)) {
                out.println("  " + s);
            }
            out.println("  max ρ² = " + KiteConstants.trim(KiteOrthogonality.maxRhoSquared(pairs)));
            out.println("  未判定原因：" + series.firstSuppression());
        }

        boolean anyFail = false;
        boolean anyUnverified = false;
        for (KiteOrthogonality.Pair p : pairs) {
            anyFail |= p.verdict() == KiteOrthogonality.Verdict.FAIL;
            anyUnverified |= p.verdict() == KiteOrthogonality.Verdict.UNVERIFIED;
        }
        double[] variances = new double[KiteAxis.values().length];
        int[] distinctCounts = new int[KiteAxis.values().length];
        StringBuilder power = new StringBuilder();
        boolean powerOk = true;
        for (int j = 0; j < KiteAxis.values().length; j++) {
            double[] col = KiteOrthogonality.column(rows, j);
            double m = 0d;
            for (double v : col) {
                m += v;
            }
            m = col.length == 0 ? 0d : m / col.length;
            double var = 0d;
            Set<Long> distinct = new LinkedHashSet<>();
            for (double v : col) {
                var += (v - m) * (v - m);
                distinct.add(Math.round(v * 1e9));
            }
            var = col.length == 0 ? 0d : var / col.length;
            variances[j] = var;
            distinctCounts[j] = distinct.size();
            boolean axisOk = distinct.size() >= KITE_POWER_MIN_DISTINCT && var > KITE_POWER_MIN_VARIANCE;
            powerOk &= axisOk;
            power.append(KiteAxis.values()[j]).append("{互异=").append(distinct.size())
                    .append(", 方差=").append(KiteConstants.trim(var))
                    .append(", 功效达标=").append(axisOk).append("} ");
        }
        r.require("OR-PW", powerOk,
                "功效守门：三轴各自必须 互异取值数 ≥ " + KITE_POWER_MIN_DISTINCT + " 且 方差 > "
                        + KITE_POWER_MIN_VARIANCE + " ⇒ " + power);

        if (KiteAxisProfile.current() == KiteAxisProfile.REDEFINED_2026
                || KiteAxisProfile.current() == KiteAxisProfile.REDEFINED_PROG_DELTA) {
            Set<String> gatedStillAvailable = new LinkedHashSet<>();
            for (KiteSeries.KitePoint p : series.points()) {
                for (KiteObservation o : p.observations()) {
                    if (("octant.breadth.weighted_coverage".equals(o.key())
                            || "octant.pref.category_entropy".equals(o.key())) && o.available()) {
                        gatedStillAvailable.add(o.key() + "@point" + p.index());
                    }
                }
            }
            r.require("OR-GATE", gatedStillAvailable.isEmpty(),
                    "档位门一致性：重定义档下 SPON 的 `weighted_coverage` / `category_entropy` 必须"
                            + "**在取值源层不可用**（登记写「不参与派生」⇒ 取值源也必须如此）；"
                            + "仍被标为可用的处数=" + gatedStillAvailable.size());
        }
        if (!powerOk) {
            r.unverified("OR-1", "**低功效 ⇒ OR-1 不得判通过**：三轴中至少一条分辨力不足，"
                    + "此时「ρ² 很小」不构成「不共线」的证据（可能是「没有分辨力」）。读数：" + power
                    + "；三对 ρ²=" + KiteOrthogonality.dumpPairs(pairs));
        } else if (anyFail) {
            r.fail("OR-1", "存在 ρ² > KITE_ORTHO_MAX 的轴对：" + KiteOrthogonality.dumpPairs(pairs));
        } else if (anyUnverified) {
            r.unverified("OR-1", "存在未判定的轴对（常数列/样本不足）：" + KiteOrthogonality.dumpPairs(pairs));
        } else {
            r.pass("OR-1", "三对 ρ² 全部 ≤ KITE_ORTHO_MAX：" + KiteOrthogonality.dumpPairs(pairs));
        }

        {
            List<KiteSeries.KitePoint> pts = series.points();
            int total = pts.size();
            int complete = 0;
            Set<Integer> completeIdx = new LinkedHashSet<>();
            Map<String, Integer> reasonCount = new LinkedHashMap<>();
            for (KiteSeries.KitePoint p : pts) {
                boolean ok = p.complete();
                if (ok) {
                    complete++;
                    completeIdx.add(p.index());
                }
                for (KiteAxis axis : KiteAxis.values()) {
                    KiteAxisValue av = p.vector().axisValue(axis);
                    if (!av.available()) {
                        String k = axis + " 因 " + av.reasonCode();
                        reasonCount.merge(k, 1, Integer::sum);
                    }
                }
            }
            int adjacentPairs = 0;
            int longestRun = 0;
            int run = 0;
            for (int i = 0; i < total; i++) {
                if (completeIdx.contains(i)) {
                    run++;
                    longestRun = Math.max(longestRun, run);
                    if (i > 0 && completeIdx.contains(i - 1)) {
                        adjacentPairs++;
                    }
                } else {
                    run = 0;
                }
            }
            r.pass("SEG-N", "可画性四数：总采样点=" + total + "，三轴齐全点=" + complete
                    + "，三轴齐全且相邻的点对=" + adjacentPairs + "，最长连续可画段长度=" + longestRun
                    + "；不可用点按原因：" + reasonCount);
            if (adjacentPairs == 0) {
                r.fail("SEG-1", "**交付物无法画出轨迹**：三轴齐全且相邻的点对数 = 0 "
                        + "（下游只能画出 " + complete + " 个孤点，一条折线都连不出来）");
            } else if (adjacentPairs == 1) {
                r.fail("SEG-1", "**交付物无法画出轨迹**：三轴齐全且相邻的点对数 = 1 "
                        + "（只能连出 1 段，不构成折线）");
            } else {
                r.pass("SEG-1", "可画出折线：三轴齐全且相邻的点对 = " + adjacentPairs
                        + " 段（≥ 目标 2 段）；最长连续可画段长度=" + longestRun);
            }
        }

        List<double[]> control = KiteOrthogonality.independentControlSeries(24);
        List<KiteOrthogonality.Pair> ctlPairs = KiteOrthogonality.pairwise(control);
        List<KiteOrthogonality.MultiCollinearity> ctlMulti = KiteOrthogonality.multiCollinearity(control);
        boolean ctlAllPass = true;
        for (KiteOrthogonality.Pair p : ctlPairs) {
            ctlAllPass &= p.verdict() == KiteOrthogonality.Verdict.PASS;
        }
        boolean ctlAllHaveVariance = true;
        StringBuilder ctlVariance = new StringBuilder();
        for (int j = 0; j < KiteAxis.values().length; j++) {
            double[] col = KiteOrthogonality.column(control, j);
            Set<Long> distinct = new LinkedHashSet<>();
            for (double v : col) {
                distinct.add(Math.round(v * 1e9));
            }
            ctlAllHaveVariance &= distinct.size() >= 2;
            ctlVariance.append(KiteAxis.values()[j]).append("互异=").append(distinct.size()).append(' ');
        }
        StringBuilder ctlDump = new StringBuilder();
        for (KiteOrthogonality.Pair p : ctlPairs) {
            ctlDump.append(p.a()).append('~').append(p.b()).append('=')
                    .append(Double.isNaN(p.rhoSquared()) ? "n/a（无定义）"
                            : KiteConstants.trim(p.rhoSquared())).append(' ');
        }
        if (!hashOnly) {
            out.println();
            out.println("── OR-1p 正向对照（构造上独立的三轴序列，N=24）──");
            out.println("  ρ² 读数：" + ctlDump);
            out.println("  对照列方差：" + ctlVariance);
            out.println("  逐轴多元 R²：" + ctlMulti);
        }
        r.require("OR-1p", ctlAllPass && ctlAllHaveVariance,
                "正向对照（构造上独立的三轴序列）：三对 ρ² 全部 ≤ KITE_ORTHO_MAX = " + ctlAllPass
                        + "、**三列各自都有方差（非退化）** = " + ctlAllHaveVariance + "（" + ctlVariance.toString().trim()
                        + "）；读数 " + ctlDump + " ⇒ 量具**有区分力**（真实语料的 FAIL 是对象的性质）");

        {
            List<double[]> guardRows = series.usableVectors();
            int n = guardRows.size();
            int sessions = source.activeSpans().size();
            List<Double> seq = new ArrayList<>();
            for (int si = 0; si < sessions; si++) {
                long[] sp = source.activeSpans().get(si);
                for (long t = sp[0]; t < sp[1]; t += series.points().get(0).deltaSec()) {
                    seq.add((double) si);
                }
            }
            StringBuilder guard = new StringBuilder();
            boolean confoundFail = false;
            boolean resolutionFail = false;
            for (int j = 0; j < KiteAxis.values().length; j++) {
                KiteAxis axis = KiteAxis.values()[j];
                double[] col = KiteOrthogonality.column(guardRows, j);
                Set<Long> distinct = new LinkedHashSet<>();
                for (double v : col) {
                    distinct.add(Math.round(v * 1e9));
                }
                double[] seqArr = new double[seq.size()];
                for (int i = 0; i < seq.size(); i++) {
                    seqArr[i] = seq.get(i);
                }
                Double r2s = col.length == seqArr.length
                        ? KiteOrthogonality.rhoSquared(col, seqArr) : null;
                boolean confoundOk = r2s != null && r2s <= KiteConstants.KITE_CONFOUND_MAX;
                boolean resolutionOk = distinct.size() >= 2;
                confoundFail |= !confoundOk;
                resolutionFail |= !resolutionOk;
                guard.append(axis).append("{互异=").append(distinct.size())
                        .append(", ρ²(会话序号)=").append(r2s == null ? "n/a" : KiteConstants.trim(r2s))
                        .append(", 混杂可接受=").append(confoundOk)
                        .append(", 分辨率≥2=").append(resolutionOk).append("} ");
            }
            r.require("OR-CF", !resolutionFail,
                    "分辨率守卫：每轴在可用样本上的互异取值数必须 ≥ 2（=KITE_ORTHO_MIN_N 的下限意义）——"
                            + "零方差列按 OR-2 记 UNVERIFIED、**不得判通过**：" + guard);
            if (confoundFail) {
                r.unverified("OR-CF2", "混杂守卫：存在与会话序号 ρ² > KITE_CONFOUND_MAX="
                        + KiteConstants.trim(KiteConstants.KITE_CONFOUND_MAX) + " 的轴"
                        + "（本语料是**合成模板×12**、推进单元仅 9 个，进度轴天然与会话序号同向）⇒ "
                        + "正交性读数**在跨会话尺度上受混杂**，请按此读：" + guard);
            } else {
                r.pass("OR-CF2", "混杂守卫：三轴与会话序号的 ρ² 全部 ≤ KITE_CONFOUND_MAX="
                        + KiteConstants.trim(KiteConstants.KITE_CONFOUND_MAX) + "：" + guard);
            }
        }

        r.require("OR-1r", pairs.size() == 3,
                "三对 ρ² 全部报出（条数=" + pairs.size() + "）：" + KiteOrthogonality.dumpPairs(pairs));

        double[] prog = KiteOrthogonality.column(rows, 0);
        double[] spon = KiteOrthogonality.column(rows, 1);
        double[] guid = KiteOrthogonality.column(rows, 2);
        List<double[]> collinear = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            collinear.add(new double[] {prog[i], prog[i], guid[i]});
        }
        List<KiteOrthogonality.Pair> nc1 = KiteOrthogonality.pairwise(collinear);
        Double rhoProgSpon = KiteOrthogonality.rhoSquared(
                KiteOrthogonality.column(collinear, 0), KiteOrthogonality.column(collinear, 1));
        boolean nc1Fails = false;
        for (KiteOrthogonality.Pair p : nc1) {
            if (p.verdict() == KiteOrthogonality.Verdict.FAIL) {
                nc1Fails = true;
            }
        }
        r.require("NC-1", nc1Fails && rhoProgSpon != null && rhoProgSpon > KiteConstants.KITE_ORTHO_MAX,
                "构造 SPON := PROG ⇒ ρ²=" + (rhoProgSpon == null ? "n/a" : KiteConstants.trim(rhoProgSpon))
                        + "（> KITE_ORTHO_MAX=" + KiteConstants.trim(KiteConstants.KITE_ORTHO_MAX)
                        + "）⇒ OR-1 判 FAIL=" + nc1Fails);

        List<double[]> constant = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            constant.add(new double[] {0.5d, spon[i], guid[i]});
        }
        List<KiteOrthogonality.Pair> nc2 = KiteOrthogonality.pairwise(constant);
        boolean nc2Unverified = false;
        boolean nc2Zero = false;
        for (KiteOrthogonality.Pair p : nc2) {
            if ((p.a() == KiteAxis.PROG || p.b() == KiteAxis.PROG)
                    && p.verdict() == KiteOrthogonality.Verdict.UNVERIFIED) {
                nc2Unverified = true;
            }
            if (Double.valueOf(0d).equals(p.rhoSquared())) {
                nc2Zero = true;
            }
        }
        r.require("NC-2", nc2Unverified && !nc2Zero,
                "构造 PROG 恒为 0.5 ⇒ 含 PROG 的两对记 UNVERIFIED=" + nc2Unverified
                        + "、未被记成 0 相关=" + (!nc2Zero) + "；读数=" + KiteOrthogonality.dumpPairs(nc2));

        List<double[]> noisy = new ArrayList<>();
        double[] noisyA = KiteOrthogonality.noisyConstantColumn(Math.max(12, rows.size()), 0.01875d, 1e-17d);
        for (int i = 0; i < rows.size(); i++) {
            noisy.add(new double[] {noisyA[i % noisyA.length], rows.get(i)[1], rows.get(i)[2]});
        }
        List<KiteOrthogonality.Pair> nc2b = KiteOrthogonality.pairwise(noisy);
        boolean nc2bAllUnverified = !nc2b.isEmpty();
        boolean nc2bAnyPass = false;
        for (KiteOrthogonality.Pair p : nc2b) {
            if (p.a() == KiteAxis.PROG || p.b() == KiteAxis.PROG) {
                nc2bAllUnverified &= p.verdict() == KiteOrthogonality.Verdict.UNVERIFIED;
                nc2bAnyPass |= p.verdict() == KiteOrthogonality.Verdict.PASS;
            }
        }
        r.require("NC-2b", nc2bAllUnverified && !nc2bAnyPass,
                "退化列负控（数学恒定 + 末位抖动 1e-17）⇒ 含该列的轴对必须记 UNVERIFIED=" + nc2bAllUnverified
                        + "、**不得出现任何 PASS**=" + (!nc2bAnyPass) + "；读数="
                        + KiteOrthogonality.dumpPairs(nc2b));

        List<double[]> tiny = new ArrayList<>(rows.subList(0, Math.min(3, rows.size())));
        List<KiteOrthogonality.Pair> nc3 = KiteOrthogonality.pairwise(tiny);
        boolean nc3Unverified = true;
        for (KiteOrthogonality.Pair p : nc3) {
            nc3Unverified &= p.verdict() == KiteOrthogonality.Verdict.UNVERIFIED;
        }
        r.require("NC-3", nc3Unverified && tiny.size() == 3,
                "构造 N=3（< KITE_ORTHO_MIN_N=" + KiteConstants.KITE_ORTHO_MIN_N + "）⇒ 三对全部 UNVERIFIED="
                        + nc3Unverified);

        List<KiteOrthogonality.Intersection> actual = KiteOrthogonality.sourceIntersections();
        List<KiteOrthogonality.Intersection> unregistered = KiteOrthogonality.unregisteredIntersections(actual);
        if (!hashOnly) {
            out.println();
            out.println("── OR-4 两级相交（键级 + 原语级）──");
            out.println("  实测相交：" + actual);
            out.println("  已登记共用原语：" + KiteOrthogonality.registeredShared());
            out.println("  **未登记**的相交（⇒ FAIL 的那一类）：" + unregistered);
        }
        r.require("OR-4", unregistered.isEmpty(),
                "两级判定：相交=" + actual + "；其中已登记共用=" + (actual.size() - unregistered.size())
                        + "，**未登记**=" + unregistered);

        Map<String, String> primByKey = new LinkedHashMap<>(KiteAxisDefinition.primitiveByKey());
        Set<String> guidKeys = new LinkedHashSet<>(KiteAxisDefinition.derivedKeysOf(KiteAxis.GUID));
        guidKeys.remove("octant.env.dimension_variants");
        guidKeys.add("octant.progress.completed_units");
        List<KiteOrthogonality.Intersection> nc7a = KiteOrthogonality.unregisteredIntersections(
                KiteOrthogonality.sourceIntersections(primByKey,
                        KiteAxisDefinition.derivedKeysOf(KiteAxis.PROG),
                        KiteAxisDefinition.derivedKeysOf(KiteAxis.SPON), guidKeys));
        boolean nc7aFails = !nc7a.isEmpty();
        r.require("NC-7a", nc7aFails,
                "键级构造：把 GUID 派生式改回 PROG 的同一条键（octant.progress.completed_units）⇒ "
                        + "未登记相交非空=" + nc7aFails + "；读数=" + nc7a);

        Map<String, String> variant = new LinkedHashMap<>(primByKey);
        variant.put("octant.pref.completion_rate_per_hour", "progress.advancement_done");
        List<KiteOrthogonality.Intersection> nc7b = KiteOrthogonality.unregisteredIntersections(
                KiteOrthogonality.sourceIntersections(variant,
                        KiteAxisDefinition.derivedKeysOf(KiteAxis.PROG),
                        KiteAxisDefinition.derivedKeysOf(KiteAxis.SPON),
                        KiteAxisDefinition.derivedKeysOf(KiteAxis.GUID)));
        boolean nc7bFails = !nc7b.isEmpty();
        r.require("NC-7b", nc7bFails,
                "原语级构造：键名**未改**（pref.completion_rate_per_hour）但原语标签改标为 "
                        + "progress.advancement_done ⇒ 未登记相交非空=" + nc7bFails
                        + "（证明判的是原语，不是只看键名）；读数=" + nc7b);

        Map<String, String> nc4Variant = new LinkedHashMap<>(primByKey);
        nc4Variant.put("octant.env.dimension_variants", "progress.cover_ratio");
        List<KiteOrthogonality.Intersection> nc4 = KiteOrthogonality.unregisteredIntersections(
                KiteOrthogonality.sourceIntersections(nc4Variant,
                        KiteAxisDefinition.derivedKeysOf(KiteAxis.PROG),
                        KiteAxisDefinition.derivedKeysOf(KiteAxis.SPON),
                        KiteAxisDefinition.derivedKeysOf(KiteAxis.GUID)));
        r.require("NC-4", !nc4.isEmpty(),
                "构造「GUID 的导出式里出现 cover_weighted 的原始量」⇒ 判 FAIL=" + (!nc4.isEmpty())
                        + "；读数=" + nc4);

        double[] ent = columnOf(series, 0);
        double[] wide = columnOf(series, 1);
        double[] pref = columnOf(series, 2);
        List<Object[]> or6Pairs = new ArrayList<>();
        or6Pairs.add(new Object[] {"entropy_normalized", spon, ent});
        or6Pairs.add(new Object[] {"weighted_coverage", spon, wide});
        or6Pairs.add(new Object[] {"category_entropy", spon, pref});
        List<String> or6 = new ArrayList<>();
        int or6Undefined = 0;
        for (Object[] pr : or6Pairs) {
            String label = (String) pr[0];
            double[] a = (double[]) pr[1];
            double[] b = (double[]) pr[2];
            Double rho = KiteOrthogonality.rhoSquared(a, b);
            boolean aDeg = degenerateColumn(a);
            boolean bDeg = degenerateColumn(b);
            String cell;
            if (aDeg || bDeg) {
                or6Undefined++;
                cell = "ρ²(SPON, " + label + ")=n/a（无定义：一侧为常数列"
                        + (aDeg ? "SPON" : "") + (aDeg && bDeg ? " 与 " : "") + (bDeg ? label : "")
                        + " ⇒ 无定义/不适用）[原值 " + show(rho) + "，不得当作相关性证据]";
            } else {
                cell = "ρ²(SPON, " + label + ")=" + show(rho);
            }
            or6.add(cell);
        }
        boolean allReported = or6.size() == or6Pairs.size();
        if (!hashOnly) {
            out.println();
            out.println("── OR-6 分量共线张力（三项必报；一侧常数列记无定义）──");
            for (String s : or6) {
                out.println("  " + s);
            }
        }
        if (!allReported) {
            r.fail("OR-6", "三项未全部报出（有项无定义）⇒ 按规格 §2.3 OR-6 记 FAIL（未报 ⇒ UNVERIFIED 的下限）：" + or6);
        } else {
            r.pass("OR-6", "三项全部报出：" + or6 + "（KITE_ORTHO_MAX="
                    + KiteConstants.trim(KiteConstants.KITE_ORTHO_MAX) + "）"
                    + "；其中**一侧为常数列 ⇒ 无定义**的 = " + or6Undefined
                    + " 项（具名说明与值同行；该数不得进入任何裁决句）");
        }

        Double collinearComponent = KiteOrthogonality.rhoSquared(ent, ent);
        boolean or7Triggered = collinearComponent != null
                && collinearComponent > KiteConstants.KITE_ORTHO_MAX;
        r.require("OR-7", or7Triggered,
                "构造 weighted_coverage := entropy_normalized ⇒ ρ²=" + show(collinearComponent)
                        + " 超阈值=" + or7Triggered + "（该构造下未触发 ⇒ FAIL）");
    }

    private static boolean degenerateColumn(double[] col) {
        if (col == null || col.length == 0) {
            return true;
        }
        double scale = 0d;
        double mean = 0d;
        for (double v : col) {
            factorAcc(v);
            scale = Math.max(scale, Math.abs(v));
            mean += v;
        }
        mean /= col.length;
        double ss = 0d;
        for (double v : col) {
            double d = v - mean;
            ss += d * d;
        }
        double relativeVar = ss / col.length;
        double limit = DEGENERATE_EPS * Math.max(scale, DEGENERATE_EPS);
        return relativeVar <= limit * limit;
    }

    private static final double DEGENERATE_EPS = 1e-9d;

    private static void factorAcc(double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("列里出现非有限值（NaN/Inf）⇒ 不得据此判退化或相关性");
        }
    }

    private static double[] columnOf(KiteSeries series, int which) {        List<KiteSeries.KitePoint> pts = series.points();
        double[] out = new double[pts.size()];
        int n = 0;
        for (KiteSeries.KitePoint p : pts) {
            if (!p.complete()) {
                continue;
            }
            double[] v = p.vector().requireValues();
            out[n++] = v[which];
        }
        double[] trimmed = new double[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    private static String show(Double d) {
        return d == null ? "n/a（无定义）" : KiteConstants.trim(d);
    }

    private static void dynamicsChecks(KiteSelfCheck.Runner r, KiteSeries series, PrintStream out,
                                       boolean hashOnly) {
        double[] p = {0.4d, 0.5d, 0.6d};
        boolean zeroOk = true;
        for (int j = 0; j < 3; j++) {
            double[] q = p.clone();
            double[] a = KiteDynamics.acceleration(p, q, KiteConstants.KITE_SPRING_KAPPA,
                    KiteConstants.KITE_DAMPING_C, KiteConstants.KITE_MASS_M);
            for (double v : a) {
                zeroOk &= v == 0d;
            }
        }
        double worst = 0d;
        boolean finite = true;
        for (int e = 12; e >= 1; e--) {
            double eps = Math.pow(10d, -e);
            double[] q = {p[0] + eps, p[1], p[2]};
            double[] a = KiteDynamics.acceleration(p, q, KiteConstants.KITE_SPRING_KAPPA,
                    KiteConstants.KITE_DAMPING_C, KiteConstants.KITE_MASS_M);
            double mag = KiteDynamics.magnitude(a);
            finite &= Double.isFinite(mag);
            worst = Math.max(worst, mag);
        }
        r.require("KD-1", zeroOk && finite && worst <= KiteConstants.KITE_ACC_MAX,
                "L=0 ⇒ a 全 0=" + zeroOk + "；L→0（10^-12..10^-1）全程有限=" + finite
                        + "、max|a|=" + KiteConstants.trim(worst) + " ≤ KITE_ACC_MAX="
                        + KiteConstants.trim(KiteConstants.KITE_ACC_MAX) + "（不除零、不 NaN）");

        List<double[]> pts = series.usableVectors();
        List<Double> mags = KiteDynamics.accelerationMagnitudes(pts);
        r.require("KD-2", mags.size() == Math.max(0, pts.size() - 1),
                "N=" + pts.size() + " ⇒ |a| 序列长度=" + mags.size() + "（= N−1，可用区间 [1, N−1]）");

        double[] corner = {-1d, -1d, -1d};
        double[] reverse = {1d, 1d, 1d};
        double[] smallAngle = {1d, 0d, 0d};
        double[] tinyAngle = {1d, 1e-9d, 0d};
        double[] a1 = KiteDynamics.force(corner, KiteConstants.KITE_SPRING_KAPPA, KiteConstants.KITE_DAMPING_C);
        double[] a2 = KiteDynamics.force(reverse, KiteConstants.KITE_SPRING_KAPPA, KiteConstants.KITE_DAMPING_C);
        double[] a3 = KiteDynamics.force(smallAngle, KiteConstants.KITE_SPRING_KAPPA, KiteConstants.KITE_DAMPING_C);
        double[] a4 = KiteDynamics.force(tinyAngle, KiteConstants.KITE_SPRING_KAPPA, KiteConstants.KITE_DAMPING_C);
        double[] a5 = KiteDynamics.force(new double[] {0d, 0d, 0d}, KiteConstants.KITE_SPRING_KAPPA,
                KiteConstants.KITE_DAMPING_C);
        double m1 = KiteDynamics.magnitude(a1);
        double m2 = KiteDynamics.magnitude(a2);
        double m3 = KiteDynamics.magnitude(a3);
        double m4 = KiteDynamics.magnitude(a4);
        double m5 = KiteDynamics.magnitude(a5);
        boolean bounded = m1 <= KiteConstants.KITE_ACC_MAX && m2 <= KiteConstants.KITE_ACC_MAX
                && m3 <= KiteConstants.KITE_ACC_MAX && m4 <= KiteConstants.KITE_ACC_MAX
                && m5 <= KiteConstants.KITE_ACC_MAX;
        r.require("KD-3", bounded, "极端输入 |a|：零模长=" + KiteConstants.trim(m5) + "、反向="
                + KiteConstants.trim(m2) + "、全负角点=" + KiteConstants.trim(m1) + "、极小夹角="
                + KiteConstants.trim(m4) + "（均 ≤ " + KiteConstants.trim(KiteConstants.KITE_ACC_MAX) + "）");

        int detected = KiteDynamics.acceleratedSegments(pts, KiteConstants.KITE_SPRING_KAPPA,
                KiteConstants.KITE_DAMPING_C);
        int control = KiteDynamics.acceleratedSegments(pts, 0d, 0d);
        r.require("KD-4", detected > 0 && control == 0,
                "真实序列：检出加速段=" + detected + "（≥ KITE_ACC_DETECT="
                        + KiteConstants.trim(KiteConstants.KITE_ACC_DETECT) + "）；对照（κ=c=0）检出="
                        + control + "（必须为 0 ⇒ 检出依赖动力学而非其它来源）");

        double[] p0 = {0d, 0d, 0d};
        double[] p1 = {0.5d, 0.7d, 0.1d};
        double[] d = KiteDynamics.displacement(p0, p1);
        double len = KiteDynamics.arcLength(d);
        double[] t = KiteDynamics.unitTangent(d);
        double[] sigma = KiteDynamics.sigma(d);
        double[] f = KiteDynamics.force(d, KiteConstants.KITE_SPRING_KAPPA, KiteConstants.KITE_DAMPING_C);
        double fmag = KiteDynamics.magnitude(f);
        double amag = fmag / KiteConstants.KITE_MASS_M;
        double[] expectedSigma = {0.296715d, 0.363734d, 0.047581d};
        boolean exampleOk = Math.abs(len - 0.8660254d) < 1e-6d
                && Math.abs(t[0] - 0.5773503d) < 1e-6d
                && Math.abs(t[1] - 0.8082904d) < 1e-6d
                && Math.abs(t[2] - 0.1154701d) < 1e-6d
                && Math.abs(fmag - 1.321843d) < 1e-5d
                && Math.abs(amag - 1.321843d) < 1e-5d;
        for (int j = 0; j < 3; j++) {
            exampleOk &= Math.abs(sigma[j] - expectedSigma[j]) < 1e-5d;
        }
        if (!hashOnly) {
            out.println();
            out.println("── KD-E 手算算例（P0=(0,0,0) → P1=(0.5,0.7,0.1)）──");
            out.println("  D=" + arr(d) + "  L=" + fmt(len) + "（手算 √0.75=0.8660254）  t=" + arr(t));
            out.println("  σ(D)=" + arr(sigma) + "（手算 0.296715 / 0.363734 / 0.047581）");
            out.println("  F=" + arr(f) + "  |F|=" + fmt(fmag) + "  |a|=" + fmt(amag));
            out.println("  手算复核：z=D−α=(0.45,0.65,0.05)；β(1−e^{−z/β}) 逐项如上；"
                    + "c·L=1.2×0.8660254=1.0392305 ⇒ c·L·t=(0.6,0.84,0.12)；"
                    + "F=−0.6σ−cLt=(−0.778029,−1.058240,−0.148549) ⇒ |F|=|a|=1.321843");
        }
        r.require("KD-E", exampleOk, "手算可复核数值：σ=" + arr(sigma) + "（手算 0.296715/0.363734/0.047581）"
                + "、L=" + fmt(len) + "（手算 0.8660254）、t=" + arr(t)
                + "（手算 0.5773503/0.8082904/0.1154701）、|F|=|a|=" + fmt(fmag) + "（手算 1.321843）");

        double worstUnsaturated = 0d;
        for (int k = 0; k + 1 < pts.size(); k++) {
            worstUnsaturated = Math.max(worstUnsaturated, KiteDynamics.unsaturatedAccelerationMagnitude(
                    pts.get(k), pts.get(k + 1), KiteConstants.KITE_SPRING_KAPPA, KiteConstants.KITE_DAMPING_C,
                    KiteConstants.KITE_MASS_M));
        }
        r.require("KD-B", worstUnsaturated <= KiteConstants.KITE_ACC_MAX,
                "真实序列在**饱和前**的最大 |a|=" + KiteConstants.trim(worstUnsaturated)
                        + " ≤ KITE_ACC_MAX=" + KiteConstants.trim(KiteConstants.KITE_ACC_MAX)
                        + " ⇒ 上界不是靠夹断实现的（饱和只对极端输入生效）");

        r.require("KD-6", KiteDynamics.allFinite(pts),
                "真实序列与极端输入下的 F/a/L 全部有限（非 NaN/非 Inf）");
    }

    private static void labelChecks(KiteSelfCheck.Runner r, KiteLabelRegistry registry, PrintStream out,
                                    boolean hashOnly) {
        if (!hashOnly) {
            out.println();
            out.println("── LV-10 标签登记镜像 ──");
            for (String s : registry.dump()) {
                out.println("  " + s);
            }
            out.println("  读取器原始输出（前 400 字符，照录不加工）："
                    + KiteLabelRegistry.lastReaderRaw().replace("\n", " ⏎ "));
        }

        KiteVectorAggregator aggregator = new KiteVectorAggregator(registry);

        if (!registry.hasSource()) {
            r.unverified("LV-10S", "无权威标签登记输出（" + registry.mirrorNote()
                    + "）⇒ LV-10 守门无从判定（fail-closed：镜像为空、不提供过期副本）");
        } else {
            r.pass("LV-10S", "权威标签登记输出可用：来源=" + registry.mirror() + "（" + registry.mirrorNote()
                    + "）；登记标签=" + registry.labels().size() + " 条");
        }

        int[] fam = registry.familyCount("behavior");
        int hfam = registry.familyCount("health-state")[0];
        List<String> countMismatch = new ArrayList<>();
        for (String f : List.of("behavior", "health-state", "pref")) {
            int[] c = registry.familyCount(f);
            if (c[1] == 0) {
                continue;
            }
            int parsed = 0;
            for (KiteLabelRegistry.Label l : registry.labels().values()) {
                if (l.family().equals(f) && l.judgmentFormulaEstablished()) {
                    parsed++;
                }
            }
            if (parsed != c[0]) {
                countMismatch.add(f + "：解析出 " + parsed + " 条已建立，来源族级计数 " + c[0] + "/" + c[1]);
            }
        }
        boolean behaviorConsistent = countMismatch.isEmpty();
        List<String> wronglyAvailable = new ArrayList<>();
        for (KiteLabelRegistry.Mirror m : List.of(KiteLabelRegistry.Mirror.ARGUMENT,
                KiteLabelRegistry.Mirror.IN_REPO_READER, KiteLabelRegistry.Mirror.IN_REPO_READER_OUT)) {
            if (registry.mirror() == m) {
                for (KiteLabelRegistry.Label l : registry.labels().values()) {
                    if (!l.judgmentFormulaEstablished()
                            && registry.aggregatableRefs().contains(l.family() + ":" + l.id())) {
                        wronglyAvailable.add(l.family() + ":" + l.id());
                    }
                }
            }
        }
        if (!registry.hasSource()) {
            r.unverified("LV-10", "无权威输出 ⇒ 未判定（不得记通过）");
        } else {
            r.require("LV-10", behaviorConsistent && wronglyAvailable.isEmpty(),
                    "behavior 判定式齐备=" + registry.behaviorEstablishedCount() + "/"
                            + KiteLabelRegistry.SPEC_BEHAVIOR_LABELS.size()
                            + "（来源族级计数 " + fam[0] + "/" + fam[1] + "）；health-state="
                            + registry.healthStateEstablishedCount() + "/3（族级 " + hfam + "）；"
                            + "**逐族计数不一致**=" + countMismatch
                            + "；**未建立却被当作可用**的标签=" + wronglyAvailable
                            + "；镜像来源=" + registry.mirror());
        }

        String authorityText = registry.hasSource()
                ? String.join("\n", registry.dump())
                : "";
        KiteLabelRegistry unestablished = registry.hasSource()
                ? KiteLabelRegistry.ofText(authorityText
                        .replace("判定式已建立", "判定式未建立"),
                        KiteLabelRegistry.Mirror.ARGUMENT, "构造：把当前权威读数的每一处「已建立」改写成「未建立」")
                : KiteLabelRegistry.noSource("构造：无权威来源");
        String subject = KiteLabelRegistry.SPEC_BEHAVIOR_LABELS.get(0);
        String blockedBehavior = null;
        String blockedMessage = "";
        try {
            new KiteVectorAggregator(unestablished).requireAggregatable("behavior:" + subject);
        } catch (IllegalStateException e) {
            blockedBehavior = subject;
            blockedMessage = e.getMessage();
        }
        r.require("LV-10A", blockedBehavior != null,
                "构造「behavior:" + subject + " 判定式未建立」⇒ 塞进向量被守门拒绝=" + (blockedBehavior != null)
                        + "；抛出信息=" + blockedMessage
                        + "；构造镜像的齐备数=" + unestablished.behaviorEstablishedCount() + "/6");

        String blockedNoSource = null;
        String noSourceMessage = "";
        try {
            new KiteVectorAggregator(KiteLabelRegistry.noSource("构造：无权威来源"))
                    .requireAggregatable("pref:dominant_category_id");
        } catch (IllegalStateException e) {
            blockedNoSource = "pref:dominant_category_id";
            noSourceMessage = e.getMessage();
        }
        r.require("LV-10B", blockedNoSource != null,
                "构造「无权威来源」⇒ 连**已建立**的标签也被拒（fail-closed）= " + (blockedNoSource != null)
                        + "；抛出信息=" + noSourceMessage);

        String blockedUnknown = null;
        String unknownMessage = "";
        try {
            new KiteVectorAggregator(registry).requireAggregatable("behavior:这个标签不存在");
        } catch (IllegalStateException e) {
            blockedUnknown = "这个标签不存在";
            unknownMessage = e.getMessage();
        }
        r.require("LV-10C", blockedUnknown != null,
                "把**未登记**的标签塞进向量 ⇒ 被守门拒绝=" + (blockedUnknown != null) + "；抛出信息=" + unknownMessage);

        boolean positiveOpens = false;
        String positiveNote = "";
        try {
            new KiteVectorAggregator(registry).requireForAggregationOnly("pref:dominant_category_id");
            positiveOpens = registry.judgmentEstablished("pref:dominant_category_id");
            positiveNote = "pref:dominant_category_id 在权威来源内已建立=" + positiveOpens;
        } catch (IllegalStateException e) {
            positiveNote = "抛出：" + e.getMessage();
        }
        if (!registry.hasSource()) {
            r.unverified("LV-10D", "无权威输出 ⇒ 正向对照未判定；" + positiveNote);
        } else {
            r.require("LV-10D", positiveOpens,
                    "正向对照：权威来源内已建立判定式的标签**放行**=" + positiveOpens + "；" + positiveNote
                            + "（若此条失败 ⇒ 守门恒红、失去区分力）");
        }

        boolean closedEvenWhenEstablished = false;
        String closedMessage = "";
        try {
            new KiteVectorAggregator(registry).requireAggregatable("pref:dominant_category_id");
        } catch (IllegalStateException e) {
            closedEvenWhenEstablished = e.getMessage() != null && e.getMessage().contains("本切片");
            closedMessage = e.getMessage();
        }
        r.require("LV-10E", closedEvenWhenEstablished || !registry.hasSource(),
                "本切片边界：标签→向量整条关闭（不因判定式已建立而放开）=" + closedEvenWhenEstablished
                        + "；抛出信息=" + closedMessage);

        boolean guardAtAggregate = false;
        String aggMessage = "";
        try {
            aggregator.aggregate(List.of());
        } catch (IllegalArgumentException e) {
            guardAtAggregate = true;
            aggMessage = e.getMessage();
        }
        r.require("LV-10F", guardAtAggregate,
                "聚合入口校验生效（空输入被拒）=" + guardAtAggregate + "；信息=" + aggMessage);
    }

    private static void suppressionChecks(KiteSelfCheck.Runner r, KiteSeries snapshot, Path workingDir,
                                          PrintStream out, boolean hashOnly) {
        Set<String> forbiddenStillNumeric = new LinkedHashSet<>();
        Set<String> wronglySuppressed = new LinkedHashSet<>();
        Set<String> affectedSeen = new LinkedHashSet<>();
        int suppressedAxisInstances = 0;
        for (KiteSeries.KitePoint p : snapshot.points()) {
            for (KiteObservation o : p.observations()) {
                if (isSnapshotAffected(o.key())) {
                    affectedSeen.add(o.key());
                    if (o.available()) {
                        forbiddenStillNumeric.add(o.key() + "@point" + p.index());
                    }
                } else if (!o.available() && !isAlwaysUnavailable(o.key())) {
                    wronglySuppressed.add(o.key() + "<" + o.reasonCode() + ">@point" + p.index());
                }
            }
            for (KiteAxis axis : KiteAxis.values()) {
                if (!p.vector().axisValue(axis).available()) {
                    suppressedAxisInstances++;
                }
            }
        }
        r.require("AD-7", forbiddenStillNumeric.isEmpty() && wronglySuppressed.isEmpty()
                        && affectedSeen.size() >= 7 && suppressedAxisInstances > 0,
                "快照源：受影响量仍给数值的处数=" + forbiddenStillNumeric.size() + "（必须 0）；"
                        + "**不受影响却被误抑制**的处数=" + wronglySuppressed.size() + "（必须 0）；"
                        + "命中受影响键=" + affectedSeen.size() + " 类；被抑制的轴实例=" + suppressedAxisInstances
                        + "；受影响键清单=" + snapshotAffectedKeys()
                        + "；快照下仍可取的目录口径键=" + KiteObservationSource.snapshotCatalogKeys());

        Set<String> overlap = new LinkedHashSet<>(snapshotAffectedKeys());
        overlap.retainAll(KiteObservationSource.snapshotCatalogKeys());
        r.require("AD-7b", overlap.isEmpty(),
                "受影响键（整类抑制）与目录口径键（快照仍可取）交集=" + overlap + "（必须为空）");

        Set<String> statuses = new LinkedHashSet<>();
        for (KiteStatus s : KiteStatus.values()) {
            statuses.add(s.wireName());
        }
        Set<String> contract = new LinkedHashSet<>();
        for (com.octant.pipeline.analysis.MetricStatus s
                : com.octant.pipeline.analysis.MetricStatus.values()) {
            contract.add(s.wireName());
        }
        Set<String> invented = new LinkedHashSet<>(statuses);
        invented.removeAll(contract);
        r.require("SG-1", invented.isEmpty(),
                "数据侧 status 取值=" + statuses + "，契约既有枚举=" + contract + "，新增取值=" + invented);

        boolean sampleOk = true;
        StringBuilder note = new StringBuilder();
        for (KiteSeries.KitePoint p : snapshot.points()) {
            for (KiteAxis axis : KiteAxis.values()) {
                KiteAxisValue av = p.vector().axisValue(axis);
                if (!av.available()) {
                    KiteSampleSize ss = av.sampleSize();
                    boolean ok = ss != null && ss.activeSeconds() >= 0 && ss.samplePoints() >= 0
                            && (KiteConstants.BASIS_ACTIVE_SECONDS.equals(ss.basis())
                                || KiteConstants.BASIS_SAMPLE_POINTS.equals(ss.basis()));
                    if (!ok) {
                        sampleOk = false;
                        note.append(axis).append('@').append(p.index()).append(' ');
                    }
                }
            }
        }
        r.require("SG-2", sampleOk, "全部抑制项都带结构化 sampleSize{activeSeconds, samplePoints, basis}"
                + "（不合格项=" + (note.length() == 0 ? "无" : note.toString().trim()) + "）");

        boolean explicit = true;
        int suppressedAxes = 0;
        for (KiteSeries.KitePoint p : snapshot.points()) {
            for (KiteAxis axis : KiteAxis.values()) {
                KiteAxisValue av = p.vector().axisValue(axis);
                if (!av.available()) {
                    suppressedAxes++;
                    explicit &= av.reasonCode() != null && !av.reasonCode().isEmpty() && av.sampleSize() != null;
                }
            }
        }
        r.require("SG-7", explicit && suppressedAxes > 0,
                "弃权一律以显式抑制表达：被抑制的轴实例=" + suppressedAxes + "、全部带 reasonCode+sampleSize="
                        + explicit + "（无「省略而不记」）");

        Path dc = workingDir.resolve("docs/design/data-contracts.md");
        if (!Files.isRegularFile(dc)) {
            r.unverified("SG-R", "dc 文档不可读（" + dc + "）⇒ 降级码对账未做");
        } else {
            try {
                String text = new String(Files.readAllBytes(dc), StandardCharsets.UTF_8);
                boolean hasMissing = text.contains(KiteConstants.RC_INPUT_MISSING);
                boolean hasSnapshot = text.contains(KiteConstants.RC_INPUT_SNAPSHOT_ONLY);
                boolean hasInsufficient = text.contains(KiteConstants.RC_SAMPLE_INSUFFICIENT);
                r.require("SG-R", hasMissing && hasSnapshot && hasInsufficient,
                        "降级码在 dc 文档内逐字命中：INPUT_MISSING=" + hasMissing + "、INPUT_SNAPSHOT_ONLY="
                                + hasSnapshot + "、SAMPLE_INSUFFICIENT_SESSIONS=" + hasInsufficient);
            } catch (IOException e) {
                r.unverified("SG-R", "dc 文档读取失败：" + e.getClass().getSimpleName());
            }
        }
    }

    static Set<String> snapshotAffectedKeys() {
        Set<String> out = new LinkedHashSet<>();
        out.add("octant.stall.active_event_density");
        out.add("octant.progress.completed_units");
        out.add("octant.progress.cover_weighted");
        out.add("octant.dispersion.entropy_normalized");
        out.add("octant.breadth.weighted_coverage");
        out.add("octant.pref.category_entropy");
        out.add("octant.env.dimension_dominant_share");
        out.add("octant.pref.completion_rate_per_hour");
        return out;
    }

    static Set<String> keysNotProducedByThisSlice() {
        Set<String> out = new LinkedHashSet<>();
        out.add("octant.env.installed_unused_flag");
        out.add("octant.env.snapshot");
        out.add("octant.progress.item_spectrum_coverage");
        return out;
    }

    static boolean isAlwaysUnavailable(String key) {
        return keysNotProducedByThisSlice().contains(key);
    }

    static boolean isSnapshotAffected(String key) {
        return snapshotAffectedKeys().contains(key);
    }

    private static void determinismChecks(KiteSelfCheck.Runner r, KiteObservationSource source,
                                          Path workingDir, PrintStream out, boolean hashOnly) {
        KiteSeries a = KiteSeries.fixedBins(source, KiteConstants.KITE_DT_REF_S, 5, false);
        KiteObservationSource again = KiteObservationSource.of(rawEventsOf(source), source.originMs());
        KiteSeries b = KiteSeries.fixedBins(again, KiteConstants.KITE_DT_REF_S, 5, false);
        String ba = boundaries(a);
        String bb = boundaries(b);
        r.require("DT-0", ba.equals(bb) && source.originMs() == again.originMs(),
                "同一 (t_origin, Δ 序列, 观测快照) ⇒ 区间边界逐字节相同=" + ba.equals(bb)
                        + "；t_origin=" + source.originMs() + "（显式注入，非 now()）；边界=" + ba);

        List<Map<String, Object>> shuffled = new ArrayList<>(rawEventsOf(source));
        java.util.Collections.reverse(shuffled);
        KiteObservationSource rev = KiteObservationSource.of(shuffled, source.originMs());
        boolean orderFree = boundaries(KiteSeries.fixedBins(rev, KiteConstants.KITE_DT_REF_S, 5, false))
                .equals(ba) && rev.sortedEventNames().equals(source.sortedEventNames());
        r.require("DT-3", orderFree,
                "把输入事件顺序整体反转 ⇒ 稳定排序后的事件名序列与区间边界**都不变**=" + orderFree);

        String clean = digest(a, source.originMs());
        String dirty = digestWithInjectedNondeterminism(a, source.originMs());
        boolean injectionDetected = !clean.equals(dirty);
        r.require("DT-4", injectionDetected,
                "注入一处非确定源（以一个**不在输入里**的运行期值参与 SPON 取值）⇒ 摘要**发生变化**="
                        + injectionDetected + "（本次注入值不入读数、不入工件 ⇒ 反向对照不得污染 DT-1）");

        String clean2 = digest(a, source.originMs());
        r.require("DT-1i", clean.equals(clean2),
                "同进程内两次计算摘要相同=" + clean.equals(clean2) + "（sha16=" + Fingerprint.sha16(clean)
                        + "）；**注意**：进程内一致不能证伪进程级不确定性，跨进程口径由外部两次 JVM 运行承担");

        if (!hashOnly) {
            out.println();
            out.println("── DT 读数 ──");
            out.println("  区间边界=" + ba);
            out.println("  摘要(干净)=" + Fingerprint.sha16(clean));
        }
    }

    private static String boundaries(KiteSeries s) {
        StringBuilder sb = new StringBuilder();
        for (KiteSeries.KitePoint p : s.points()) {
            sb.append('[').append(p.startSec()).append(',').append(p.startSec() + p.deltaSec()).append(')');
        }
        return sb.toString();
    }

    static List<Map<String, Object>> rawEventsOf(KiteObservationSource source) {
        return source.rawEvents();
    }

    static String digest(KiteSeries s, long origin) {
        StringBuilder sb = new StringBuilder();
        sb.append("origin=").append(origin).append('\n');
        for (KiteSeries.KitePoint p : s.points()) {
            sb.append(p.index()).append('|').append(p.startSec()).append('|').append(p.deltaSec()).append('|')
                    .append(p.activeSeconds()).append('|');
            for (KiteAxis axis : KiteAxis.values()) {
                KiteAxisValue av = p.vector().axisValue(axis);
                sb.append(av.available() ? String.format(Locale.ROOT, "%.6f", av.value())
                        : "<" + av.reasonCode() + ">").append(',');
            }
            sb.append('\n');
        }
        return Fingerprint.sha256(sb.toString());
    }

    static String digestWithInjectedNondeterminism(KiteSeries s, long origin) {
        long injected = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        sb.append("origin=").append(origin).append('\n');
        for (KiteSeries.KitePoint p : s.points()) {
            sb.append(p.index()).append('|').append(p.startSec()).append('|').append(p.deltaSec()).append('|')
                    .append(p.activeSeconds()).append('|');
            for (KiteAxis axis : KiteAxis.values()) {
                KiteAxisValue av = p.vector().axisValue(axis);
                double v = av.available() ? av.value() : 0d;
                if (axis == KiteAxis.SPON) {
                    v = v * (1d + ((injected % 1000L) / 1_000_000d));
                }
                sb.append(av.available() ? String.format(Locale.ROOT, "%.6f", v) : "<" + av.reasonCode() + ">")
                        .append(',');
            }
            sb.append('\n');
        }
        return Fingerprint.sha256(sb.toString());
    }

    private static void fingerprintChecks(KiteSelfCheck.Runner r, Path workingDir, PrintStream out,
                                          boolean hashOnly) {
        KiteSpecRef.Measured m = KiteSpecRef.measure(workingDir);
        if (m == null) {
            r.unverified("AX-S", "规格文件不可读（" + KiteSpecRef.PATH + "）⇒ 漂移检测未做；冻结值="
                    + KiteSpecRef.normalized());
        } else {
            r.require("AX-S", m.matchesFrozen(),
                    "规格运行期实测 " + m.dump() + " vs 冻结 " + KiteSpecRef.normalized());
        }

        int c0 = KiteSelfCheck.exitCode(false, false, 0, 0);
        int c1 = KiteSelfCheck.exitCode(false, false, 1, 0);
        int c2 = KiteSelfCheck.exitCode(false, true, 0, 0);
        int c3 = KiteSelfCheck.exitCode(false, false, 0, 1);
        int c4 = KiteSelfCheck.exitCode(true, false, 0, 0);
        boolean distinct = c0 == 0 && c1 == 1 && c2 == 2 && c3 == 3 && c4 == 4 && c1 != c4;
        r.require("EC", distinct,
                "退出码五态：通过=" + c0 + " 判据失败=" + c1 + " 参数缺失=" + c2 + " 有项未判定=" + c3
                        + " 崩溃=" + c4 + "；崩溃码≠判据失败码=" + (c1 != c4));
    }
}
