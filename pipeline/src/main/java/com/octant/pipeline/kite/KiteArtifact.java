package com.octant.pipeline.kite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class KiteArtifact {

    private KiteArtifact() {
    }

    static String num(double v) {
        if (Double.isNaN(v)) {
            return "null";
        }
        if (!Double.isFinite(v)) {
            return "\"" + (v > 0 ? "Infinity" : "-Infinity") + "\"";
        }
        return String.format(Locale.ROOT, "%.6f", v);
    }

    static String build(KiteObservationSource source, KiteSeries series, KiteSeries snapshotSeries,
                        KiteLabelRegistry registry, KiteSelfCheck.Runner runner, String fixtureSha,
                        List<String> asserted, String instrumentVersion, long origin) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"octant.kite.selfcheck/1\",\n");
        sb.append("  \"instrument\": \"").append(instrumentVersion).append("\",\n");
        sb.append("  \"specFrozenFingerprint\": \"").append(KiteSpecRef.normalized()).append("\",\n");
        sb.append("  \"fixtureSha256\": \"").append(fixtureSha).append("\",\n");
        sb.append("  \"timebaseOriginMs\": ").append(origin).append(",\n");

        sb.append("  \"constants\": [\n");
        List<KiteConstants.KiteConstant> cs = KiteConstants.registry();
        for (int i = 0; i < cs.size(); i++) {
            KiteConstants.KiteConstant c = cs.get(i);
            sb.append("    {\"name\": \"").append(c.name()).append("\", \"value\": ").append(num(c.value()))
                    .append(", \"unit\": \"").append(c.unit()).append("\", \"specRef\": \"").append(c.specRef())
                    .append("\", \"relation\": \"").append(c.relation()).append("\"}")
                    .append(i + 1 < cs.size() ? "," : "").append('\n');
        }
        sb.append("  ],\n");
        sb.append("  \"constantAssertions\": [");
        for (int i = 0; i < asserted.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    \"").append(asserted.get(i)).append('"');
        }
        sb.append(asserted.isEmpty() ? "],\n" : "\n  ],\n");

        sb.append("  \"axes\": [\n");
        List<KiteAxisDefinition.AxisDef> axes = KiteAxisDefinition.axes();
        for (int i = 0; i < axes.size(); i++) {
            KiteAxisDefinition.AxisDef a = axes.get(i);
            sb.append("    {\"axis\": \"").append(a.axis()).append("\", \"orientation\": \"").append(a.orientation())
                    .append("\", \"domain\": \"").append(a.domain()).append("\", \"formula\": \"")
                    .append(a.formula()).append("\", \"suppressRule\": \"").append(a.suppressRule()).append("\"}")
                    .append(i + 1 < axes.size() ? "," : "").append('\n');
        }
        sb.append("  ],\n");

        sb.append("  \"observations\": [\n");
        List<KiteAxisDefinition.Observation> obs = KiteAxisDefinition.observations();
        for (int i = 0; i < obs.size(); i++) {
            KiteAxisDefinition.Observation o = obs.get(i);
            sb.append("    {\"axis\": \"").append(o.axis()).append("\", \"key\": \"").append(o.key())
                    .append("\", \"primitive\": \"").append(o.primitive()).append("\", \"role\": \"")
                    .append(o.role()).append("\", \"base\": \"").append(o.base()).append("\", \"domain\": \"")
                    .append(o.domain()).append("\"}").append(i + 1 < obs.size() ? "," : "").append('\n');
        }
        sb.append("  ],\n");

        sb.append("  \"sharedPrimitives\": [\n");
        List<KiteAxisDefinition.SharedPrimitive> sp = KiteAxisDefinition.sharedPrimitives();
        for (int i = 0; i < sp.size(); i++) {
            sb.append("    {\"primitive\": \"").append(sp.get(i).primitive()).append("\", \"axes\": \"")
                    .append(sp.get(i).axes()).append("\", \"basis\": \"").append(sp.get(i).basis()).append("\"}")
                    .append(i + 1 < sp.size() ? "," : "").append('\n');
        }
        sb.append("  ],\n");

        sb.append(seriesJson("series", series));
        sb.append(",\n");
        sb.append(seriesJson("snapshotSeries", snapshotSeries));
        sb.append(",\n");

        sb.append("  \"labelRegistry\": {\n");
        sb.append("    \"mirror\": \"").append(registry.mirror()).append("\",\n");
        sb.append("    \"note\": \"").append(escape(registry.mirrorNote())).append("\",\n");
        sb.append("    \"authoritative\": \"docs/design/metrics-semantics.md §20\",\n");
        sb.append("    \"behaviorEstablished\": ").append(registry.behaviorEstablishedCount()).append(",\n");
        sb.append("    \"behaviorTotal\": ").append(KiteLabelRegistry.SPEC_BEHAVIOR_LABELS.size()).append(",\n");
        sb.append("    \"labels\": [\n");
        List<KiteLabelRegistry.Label> labels = new ArrayList<>(registry.labels().values());
        labels.sort((a, b) -> (a.family() + ":" + a.id()).compareTo(b.family() + ":" + b.id()));
        for (int i = 0; i < labels.size(); i++) {
            KiteLabelRegistry.Label l = labels.get(i);
            sb.append("      {\"ref\": \"").append(l.family()).append(':').append(l.id())
                    .append("\", \"judgmentEstablished\": ").append(l.judgmentFormulaEstablished()).append("}")
                    .append(i + 1 < labels.size() ? "," : "").append('\n');
        }
        sb.append("    ]\n  },\n");

        sb.append("  \"results\": [\n");
        List<KiteSelfCheck.Result> results = runner.results();
        for (int i = 0; i < results.size(); i++) {
            KiteSelfCheck.Result res = results.get(i);
            sb.append("    {\"id\": \"").append(res.id()).append("\", \"outcome\": \"").append(res.outcome())
                    .append("\", \"detail\": \"").append(escape(res.detail())).append("\"}")
                    .append(i + 1 < results.size() ? "," : "").append('\n');
        }
        sb.append("  ],\n");
        sb.append("  \"summary\": {\"pass\": ").append(runner.count(KiteSelfCheck.Outcome.PASS))
                .append(", \"fail\": ").append(runner.count(KiteSelfCheck.Outcome.FAIL))
                .append(", \"unverified\": ").append(runner.count(KiteSelfCheck.Outcome.UNVERIFIED)).append("},\n");
        sb.append("  \"originEvents\": ").append(source.eventCount()).append(",\n");
        sb.append("  \"originSessions\": ").append(source.sessionCount()).append('\n');
        sb.append("}\n");
        return sb.toString();
    }

    private static String seriesJson(String name, KiteSeries series) {
        StringBuilder sb = new StringBuilder();
        sb.append("  \"").append(name).append("\": {\n");
        sb.append("    \"kind\": \"").append(series.kind()).append("\",\n");
        sb.append("    \"points\": [\n");
        List<KiteSeries.KitePoint> pts = series.points();
        for (int i = 0; i < pts.size(); i++) {
            KiteSeries.KitePoint p = pts.get(i);
            sb.append("      {\"i\": ").append(p.index()).append(", \"startSec\": ").append(p.startSec())
                    .append(", \"deltaSec\": ").append(p.deltaSec()).append(", \"activeSec\": ")
                    .append(p.activeSeconds()).append(", \"density\": ").append(num(p.densityPerMin()))
                    .append(", \"axes\": {");
            for (int j = 0; j < KiteAxis.values().length; j++) {
                KiteAxis axis = KiteAxis.values()[j];
                KiteAxisValue av = p.vector().axisValue(axis);
                sb.append(j == 0 ? "" : ", ").append('"').append(axis).append("\": ");
                if (av.available()) {
                    sb.append(num(av.value()));
                } else {
                    sb.append("{\"status\": \"suppressed\", \"reasonCode\": \"").append(av.reasonCode())
                            .append("\", \"sampleSize\": ").append(av.sampleSize().toJson()).append('}');
                }
            }
            sb.append("}}").append(i + 1 < pts.size() ? "," : "").append('\n');
        }
        sb.append("    ],\n");
        sb.append("    \"usableCount\": ").append(series.usableCount()).append(",\n");
        sb.append("    \"usableByAxis\": {");
        for (int j = 0; j < KiteAxis.values().length; j++) {
            KiteAxis axis = KiteAxis.values()[j];
            sb.append(j == 0 ? "" : ", ").append('"').append(axis).append("\": ").append(series.usableCount(axis));
        }
        sb.append("},\n");
        sb.append("    \"firstSuppression\": \"").append(escape(series.firstSuppression())).append("\"\n");
        sb.append("  }");
        return sb.toString();
    }

    static String axisManifest(KiteAxisProfile profile) {
        return axisManifest(profile, null);
    }

    static String axisManifest(KiteAxisProfile profile, KiteObservationSource source) {
        int progressEventCount = source == null ? -1 : source.progressEventCount();
        int progressEventPoints = source == null ? -1 : source.progressEventWindowCount();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"octant.kite.axis-definitions/1\",\n");
        sb.append("  \"profile\": \"").append(profile.wireName()).append("\",\n");
        sb.append("  \"profileNote\": \"").append(escape(profile.note())).append("\",\n");
        sb.append("  \"frozenRelations\": {\n");
        sb.append("    \"thresholdsUnchanged\": true,\n");
        sb.append("    \"note\": \"KITE_ORTHO_MAX / OR-1 判定式 / 各项阈值均未改动；本文件只记录观测键与归一化式（规格 §11.1：实现细节由实现给出并冻结）\",\n");
        sb.append("    \"twoThresholdsSameValueDifferentRole\": {\n");
        sb.append("      \"KITE_ORTHO_MAX\": {\"value\": ").append(num(KiteConstants.KITE_ORTHO_MAX))
                .append(", \"role\": \"轴间 ρ² 的通过判据（超限 ⇒ FAIL）\", \"specRef\": \"spec §2.3 OR-1\"},\n");
        sb.append("      \"KITE_CONFOUND_MAX\": {\"value\": ").append(num(KiteConstants.KITE_CONFOUND_MAX))
                .append(", \"role\": \"轴 × 会话序号的登记门限（超限 ⇒ UNVERIFIED 并点名该轴；不判 FAIL）\", \"specRef\": \"impl-defined (OR-CF2)\"},\n");
        sb.append("      \"whySeparate\": \"值 ≠ 角色：共用常量会让改其中一个时悄悄影响另一个；两条断言各自钉死\",\n");
        sb.append("      \"sameValueIsDeliberate\": true\n");
        sb.append("    }\n");
        sb.append("  },\n");
        sb.append("  \"zeroVarianceRule\": {\n");
        sb.append("    \"statement\": \"计算任一相关系数（ρ² / R²）时，若任一侧的中心化方差为 0（含『数学恒定但末位舍入非零』那类）⇒ 该系数无定义\",\n");
        sb.append("    \"verdict\": \"UNVERIFIED（不适用）\",\n");
        sb.append("    \"forbidden\": [\"记 0\", \"记 1\", \"据此判通过\"],\n");
        sb.append("    \"mustAlsoReport\": \"哪一列零方差\",\n");
        sb.append("    \"degeneracyTest\": \"相对容差 Var <= (1e-9 * scale)^2；不得用『恰好等于 0』——实测该写法会漏掉『名义常数 + 末位舍入』那一类\",\n");
        sb.append("    \"sourceCriteria\": [\"OR-2\", \"OR-3\"],\n");
        sb.append("    \"negativeControl\": \"NC-2b（数学恒定 + 末位抖动 1e-17）⇒ 含该列的轴对必须全部 UNVERIFIED、无一个 PASS\"\n");
        sb.append("  },\n");
        sb.append("  \"clauseStatus\": [\n");
        sb.append("    {\"id\": \"OR-6\", \"status\": \"无活荷载（条款保留、不删）\",\n");
        sb.append("     \"reason\": \"重定义档下 SPON 只由第一项（entropy_normalized）构成，weighted_coverage / category_entropy 已不参与该轴派生 ⇒ 该分量的『处置』分支没有可作用的荷载\",\n");
        sb.append("     \"reportedValues\": \"三项仍逐条回显（见 kite-selfcheck 的 OR-6 行）\"},\n");
        sb.append("    {\"id\": \"HOLDOUT\", \"status\": \"不适用：该语料不支持留出检验\",\n");
        sb.append("     \"usablePoints\": ").append(progressEventPoints).append(",\n");
        sb.append("     \"usablePointsUnit\": \"含推进完成事件的 10 分钟窗数（= 留出检验真正能用的点数）\",\n");
        sb.append("     \"reason\": \"全语料 advancement_gained 仅 ").append(progressEventCount)
                .append(" 条、落在 ").append(progressEventPoints)
                .append(" 个窗里 ⇒ 按会话留出后训练/测试集各自不足 KITE_ORTHO_MIN_N，硬做等于凑数\",\n");
        sb.append("     \"whatWeDoInstead\": \"只报样本内读数 + 选择程序声明；不得把样本内读数当结论\"},\n");
        sb.append("    {\"id\": \"DATA-GAP\", \"status\": \"登记：现有任何数据源都不足以支撑正交性主张\",\n");
        sb.append("     \"realSave\": \"用户真实存档只有 1 份 advancements + 1 份 stats，无逐事件时间线（总量有、事件流没有）⇒ 同样给不出 advancement 的时间分布\",\n");
        sb.append("     \"implication\": \"风筝数据侧的正解依赖**模组在游戏内采集的真实事件流**（这正是 mc-insight 的用途）；在此之前，页面不得让读者以为\\\"我们没做\\\"，而要说明\\\"为什么现在画不出\\\"\"},\n");
        sb.append("    {\"id\": \"AXIS-PROFILE\", \"status\": \"(a) 采纳为当前档；(b) 已被否决\",\n");
        sb.append("     \"a\": \"PROG 累计（9 级分辨率）；OR-1 在本语料上记 UNVERIFIED（混杂 0.948 > 0.25），不得判 PASS\",\n");
        sb.append("     \"b\": \"PROG 窗内增量 —— 否决理由：二值轴不是轴，且连带把 SPON 打成常数列，三维相空间退化成一条线\"},\n");
        sb.append("    {\"id\": \"SPON-CHOICE\", \"status\": \"保留现选（累计实体多样性）；D2'' 登记为被拒备选\",\n");
        sb.append("     \"why\": \"现选与 GUID 键级不相交；D2'' 与 GUID 同属『事件类型分布』族、原语级更近，且其轴间 ρ²=0.254181 超线（差 0.0042 也不放行）\"},\n");
        sb.append("    {\"id\": \"T21-3\", \"status\": \"受限口径（登记不扩面）\",\n");
        sb.append("     \"now\": \"三个**运行级目录规模**（progressCatalog / placeCatalog / itemCatalog）被用作分母与饱和输入，但**没有任何登记位置**（§17.0 规则 3 要求分母可指、禁止自造）\",\n");
        sb.append("     \"whyNotNow\": \"它不是读数错误：三者在同一运行内冻结且被一致使用，读数可复算；缺的是**登记位置**（dc 侧口径表）而不是实现\",\n");
        sb.append("     \"recompute\": \"pipeline/src/main/java/com/octant/pipeline/kite/KiteObservationSource.java:40 附近（三个目录的规模由本运行观测得到）；派生处 KiteAxisMath.java:156 起（PROG 分母）、GUID 饱和输入\",\n");
        sb.append("     \"prerequisite\": \"data-architect 在 dc 侧登记『本运行观测到的互异元素目录、冻结于运行起点』与空值语义；或 requirements-owner 在本规范 §1 补同义登记\"},\n");
        sb.append("    {\"id\": \"T21-4\", \"status\": \"受限口径（登记不扩面）\",\n");
        sb.append("     \"now\": \"四处键名与已登记语义**不同层/不同量**（同名不同义）：dispersion.entropy_normalized（登记 = M5a 九轴熵 / 实现 = 物品实体累计多样性）、env.dimension_variants 与 env.dimension_dominant_share（登记 = 包侧快照 W_ALL / 实现 = 玩家侧场所目录与占比）、pref.completion_rate_per_hour（登记 = M6e 条每小时 / 实现 = [0,1] 阶段贴合度）\",\n");
        sb.append("     \"whyNotNow\": \"改键名/语义会同时牵动数据契约与规范 §1，且必须给两套口径并存一个兼容期；现在动会把 `OR-1 = UNVERIFIED` 这个结论淹掉\",\n");
        sb.append("     \"safetyRule\": \"同一键的两套口径**不得并列成两条证据**（防止同一事实被当两次证明）\",\n");
        sb.append("     \"recompute\": \"docs/design/metrics-semantics.md:3121（§24.2 键回引表）；实现侧四键见 KiteAxisDefinition.profileObservations() 的 role/base 两栏\",\n");
        sb.append("     \"prerequisite\": \"data-architect 在 dc 侧登记受限口径，或另立中间特征（**不得自造名**）\"},\n");
        sb.append("    {\"id\": \"T21-5\", \"status\": \"受限口径（登记不扩面）\",\n");
        sb.append("     \"now\": \"octant.progress.cover_weighted 被实现当作 completed_units / reachable_units 的别名，而该式在本文件是 **M1a** 的定义（cover_weighted 属 M1b = Σ cover(u)/|reachable(u)|）⇒ 键名与式不符\",\n");
        sb.append("     \"whyNotNow\": \"改这个键名等于改 PROG 的语义与产物键集（跨规范与 dc 两侧），属口径变更而非缺陷修复；改名不加信息\",\n");
        sb.append("     \"recompute\": \"docs/design/metrics-semantics.md:1807 与 §24.2；实现侧 KiteAxisMath.java:156 起\",\n");
        sb.append("     \"prerequisite\": \"改名为 M1A_PROGRESS_COMPLETION 家族，或明面登记『cover_weighted 在风筝运行内取 M1a 比率口径』\"},\n");
        sb.append("    {\"id\": \"T21-7\", \"status\": \"受限口径（登记不扩面）\",\n");
        sb.append("     \"now\": \"实现侧登记的共用原语 progress.advancement_catalog（标注 PROG + GUID）**与取值源不符**（GUID 的饱和输入来自 placeCatalog），两轴原语标签集无交集（progress.advancement_catalog vs env.structure_occupancy）⇒ 该登记**既非必要、也不可机检**\",\n");
        sb.append("     \"whyNotNow\": \"『来源不相交』的通过目前只是**标签层面**的读数；A 案（删登记判不相交）与 B 案（统一标签并登记）**改变的是判定口径本身**，须由规范侧裁定，实现侧不得自选\",\n");
        sb.append("     \"recompute\": \"pipeline/src/main/java/com/octant/pipeline/kite/KiteAxisDefinition.java:158 附近（buildShared）；判定器读数见自检 OR-4 行\",\n");
        sb.append("     \"prerequisite\": \"domain-expert 按 §24.3 裁 A 案或 B 案（**不得留一条『登记了但判不出来』的条目**）\"},\n");
        sb.append("    {\"id\": \"T21-9\", \"status\": \"受限口径（登记不扩面）\",\n");
        sb.append("     \"now\": \"规范 §16.3 变更流程写『在 §17 追加条目』，而 v2.6.0…v2.6.2 三个版本的修订记录实际都落在 **§18.x**（§18.1–§18.13）⇒ 内部指针与实际位置不一致\",\n");
        sb.append("     \"whyNotNow\": \"它是**流程条文**、不是数据读数，改它不影响任何判据；本次已在 §18.13 第 5 行留痕、未删改 §16.3 原文\",\n");
        sb.append("     \"recompute\": \"docs/design/metrics-semantics.md:1807（§16.3 原句）；对照 §18.1–§18.13 的实际落点\",\n");
        sb.append("     \"prerequisite\": \"该文件所有者下次修订时把该句改为『在 §18 变更记录追加条目（并在 §17.0 留指钉行）』\"},\n");
        sb.append("    {\"id\": \"T21-3/4/5/7/9\", \"status\": \"本组为**受限口径**登记（不扩面整改）\",\n");
        sb.append("     \"scope\": \"五项各自条目见上；共同前置 = 规范侧（domain-expert / requirements-owner）与数据契约侧（data-architect）先落登记\",\n");
        sb.append("     \"whyThisChoice\": \"五项都不是『读数错了』而是『口径受限』；现在扩面会把 OR-1 已改判 UNVERIFIED 这个最重要的诚实结论埋掉\"}\n");
        sb.append("  ],\n");
        sb.append("  \"axes\": [\n");
        List<KiteAxisDefinition.AxisDef> axes = KiteAxisDefinition.profileAxes();
        for (int i = 0; i < axes.size(); i++) {
            KiteAxisDefinition.AxisDef a = axes.get(i);
            sb.append("    {\"axis\": \"").append(a.axis()).append("\", \"orientation\": \"")
                    .append(escape(a.orientation())).append("\", \"domain\": \"").append(escape(a.domain()))
                    .append("\", \"formula\": \"").append(escape(a.formula())).append("\", \"suppressRule\": \"")
                    .append(escape(a.suppressRule())).append("\", \"observationKeys\": [");
            int j = 0;
            for (String k : KiteAxisDefinition.derivedKeysOf(a.axis())) {
                sb.append(j++ == 0 ? "" : ", ").append('"').append(k).append('"');
            }
            sb.append("]}").append(i + 1 < axes.size() ? "," : "").append('\n');
        }
        sb.append("  ],\n");
        sb.append("  \"observations\": [\n");
        List<KiteAxisDefinition.Observation> obs = KiteAxisDefinition.profileObservations();
        for (int i = 0; i < obs.size(); i++) {
            KiteAxisDefinition.Observation o = obs.get(i);
            sb.append("    {\"axis\": \"").append(o.axis()).append("\", \"key\": \"").append(o.key())
                    .append("\", \"primitive\": \"").append(o.primitive()).append("\", \"role\": \"")
                    .append(escape(o.role())).append("\", \"base\": \"").append(escape(o.base()))
                    .append("\", \"domain\": \"").append(escape(o.domain())).append("\"}")
                    .append(i + 1 < obs.size() ? "," : "").append('\n');
        }
        sb.append("  ],\n");
        sb.append("  \"sharedPrimitives\": [\n");
        List<KiteAxisDefinition.SharedPrimitive> sp = KiteAxisDefinition.sharedPrimitives();
        for (int i = 0; i < sp.size(); i++) {
            sb.append("    {\"primitive\": \"").append(sp.get(i).primitive()).append("\", \"axes\": \"")
                    .append(escape(sp.get(i).axes())).append("\", \"basis\": \"").append(escape(sp.get(i).basis()))
                    .append("\"}").append(i + 1 < sp.size() ? "," : "").append('\n');
        }
        sb.append("  ],\n");
        sb.append("  \"constants\": [\n");
        List<KiteConstants.KiteConstant> cs = KiteConstants.registry();
        for (int i = 0; i < cs.size(); i++) {
            KiteConstants.KiteConstant c = cs.get(i);
            sb.append("    {\"name\": \"").append(c.name()).append("\", \"value\": ").append(num(c.value()))
                    .append(", \"unit\": \"").append(escape(c.unit())).append("\", \"specRef\": \"")
                    .append(escape(c.specRef())).append("\"}").append(i + 1 < cs.size() ? "," : "").append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
