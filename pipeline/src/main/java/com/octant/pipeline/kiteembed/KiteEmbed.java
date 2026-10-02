package com.octant.pipeline.kiteembed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.octant.pipeline.kiteview.KiteShapeCriteria;
import com.octant.pipeline.kiteview.KiteViewData;
import com.octant.pipeline.kiteview.KiteViewHtml;
import com.octant.pipeline.kiteview.KiteViewUpstream;

public final class KiteEmbed {

    public static final String SCOPE_ID = "mi-kite";

    public static final String KITE_SECTION_ID = "sec-kite";

    private static final String LEAD =
            "<h3 id=\"kite-3d\">行为风筝：可以转、可以缩的立体相空间</h3>\n"
            + "<p class=\"kite-lead\">三个方向分别是<strong>进度</strong>（走得远不远）、"
            + "<strong>自发性</strong>（没人催也自己去做）、<strong>引导性</strong>（被任务牵着走）。"
            + "里面的曲线是每 10 分钟一次采样连起来的。<strong>蓝色是平顺的转向，红色是急促的转向</strong>——"
            + "所以颜色本身就是结论：整条偏蓝说明他一直在自己稳步推进，突然红一段说明他在硬碰或者乱撞。</p>\n"
            + "<p class=\"kite-lead\">图上每个阶段都有一块平面，落在哪块平面之间，就说明他卡在哪个阶段。"
            + "<strong>拖拽旋转、滚轮缩放、双击回到原位</strong>；点曲线上的点，下面会翻出那一刻的原始数据。</p>\n";

    private static final String MACHINE_NOTE =
            "<p class=\"kite-machine-note\" data-machine-layer=\"1\">"
            + "下面这一块是<strong>机器层判定原文</strong>（术语与代号见文末「术语速查」）。两点先说清楚："
            + "① 本报告第 1、2 节的结论与建议<strong>不取自三维形状</strong>，只取自统计量与证据；"
            + "② 这块的裁决句<strong>随上游产物读数变化，逐字引用、未作改写</strong>，"
            + "它的适用范围以它自己写明的为准（它是对量具前提的判定，不是对玩家行为的判定）。"
            + "</p>\n";

    public static final List<String> ANCHORS = List.of(
            "<section id=\"verdict\"",
            "<section id=\"fallback\"",
            "<pre data-machine hidden id=\"data\">",
            "<pre data-scene hidden id=\"scene-model\">",
            "document.body.setAttribute('data-trace-ok'");

    private static final List<String> AT_RULES_INHERIT_SCOPE =
            List.of("@media", "@supports", "@container", "@layer", "@scope");

    private static final List<String> AT_RULES_OPAQUE =
            List.of("@keyframes", "@-webkit-keyframes", "@-moz-keyframes", "@font-face",
                    "@page", "@property", "@counter-style", "@font-feature-values");

    private static final String NEXT_SECTION_ANCHOR = "<h2 id=\"sec-4\">";

    private KiteEmbed() {
    }

    public static final class Crash extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Crash(String message) {
            super(message);
        }
    }

    public record Result(String html, Map<String, Object> extraction, List<Map<String, String>> checks,
                         boolean allPassed) {
    }

    public static String embed(String reportHtml, String kiteHtml) {
        return embedDetailed(reportHtml, kiteHtml).html();
    }

    public static Result embedDetailed(String reportHtml, String kiteHtml) {
        String report = reportHtml == null ? "" : reportHtml;
        String kite = kiteHtml == null ? "" : kiteHtml;

        for (String anchor : ANCHORS) {
            int c = count(kite, anchor);
            if (c != 1) {
                die("风筝页里锚点 " + quote(anchor) + " 出现 " + c + " 次（要求恰好 1 次）");
            }
        }
        if (count(report, "id=\"" + KITE_SECTION_ID + "\"") > 0) {
            die("报告里已经有 id=" + quote(KITE_SECTION_ID) + "，拒绝二次嵌入");
        }
        if (count(report, "id=\"" + SCOPE_ID + "\"") > 0) {
            die("报告里已经有 id=" + quote(SCOPE_ID) + "，拒绝二次嵌入");
        }

        String frag = between(kite, "<section id=\"verdict\"", "<section id=\"fallback\"", "可视片段区间");
        if (!frag.contains("<svg id=\"scene\"")) {
            die("片段里没有交互三维视图 <svg id=\"scene\">");
        }
        if (frag.contains("id=\"scene-static\"")) {
            die("片段里混进了静态回退图（应留在 fallback 区间之外）");
        }
        int h2InFrag = count(frag, "<h2");
        if (h2InFrag == 0) {
            die("片段里一个 <h2> 都没有，说明区间取错了");
        }
        frag = frag.replace("<h2", "<h3").replace("</h2>", "</h3>");
        if (count(frag, "</h2>") > 0 || count(frag, "<h2") > 0) {
            die("h2 降级不干净");
        }
        int nOpen = count(frag, "<section");
        int nClose = count(frag, "</section>");
        if (nClose != nOpen + 1) {
            die("片段里 </section> 比 <section> 多 " + (nClose - nOpen) + " 个（预期恰好多 1 个，即 #meta 的游离闭合标签）");
        }
        int firstClose = frag.indexOf("</section>") + "</section>".length();
        String tail = frag.substring(firstClose);
        int leadWs = tail.length() - lstrip(tail).length();
        if (!tail.substring(leadWs).startsWith("</section>")) {
            die("多出来的 </section> 不紧跟在裁决块闭合标签之后，无法确认它的归属");
        }
        frag = frag.substring(0, firstClose + leadWs) + tail.substring(leadWs + "</section>".length());
        if (count(frag, "<section") != count(frag, "</section>")) {
            die("摘掉游离闭合标签后片段仍不配平");
        }
        frag = rstrip(frag);
        int droppedNoscript = 0;
        if (frag.endsWith("<noscript>")) {
            frag = rstrip(frag.substring(0, frag.lastIndexOf("<noscript>")));
            droppedNoscript = 1;
        }
        if (!frag.endsWith("</section>")) {
            die("片段末尾不是 </section>，结构可疑：" + quote(tailOf(frag, 60)));
        }
        if (count(frag, "<section") != count(frag, "</section>")) {
            die("收尾之后片段仍不配平");
        }

        String payloadData = extractPayload(kite, "<pre data-machine hidden id=\"data\">", "逐点观测");
        String payloadModel = extractPayload(kite, "<pre data-scene hidden id=\"scene-model\">", "几何模型");
        for (Map.Entry<String, String> e : Map.of("data", payloadData, "scene-model", payloadModel).entrySet()) {
            String head = e.getValue().split(">", 2)[0];
            if (!head.contains("hidden")) {
                die("载荷 " + e.getKey() + " 丢了 hidden 属性（会直接显示成大段 JSON）");
            }
        }

        int styleOpen = kite.indexOf("<style>");
        int styleClose = kite.indexOf("</style>", Math.max(styleOpen, 0));
        if (styleOpen < 0 || styleClose < 0) {
            die("风筝页里找不到唯一的 <style> 块");
        }
        String styleBody = kite.substring(styleOpen + "<style>".length(), styleClose);
        Scoped scoped = scopeCss(styleBody, "#" + SCOPE_ID);
        String css = scoped.css();
        if (css.startsWith("<style") || css.contains("</style>")) {
            die("作用域化后的 CSS 里混进了 style 标签，说明抽取区间错了");
        }
        if (scoped.scopedRules() == 0) {
            die("一条选择器都没被作用域化，说明 CSS 抽取失败");
        }
        if (count(css, "#" + SCOPE_ID + " h2") == 0) {
            die("作用域化后找不到 #" + SCOPE_ID + " h2 规则，无法把 h2 换成 h3");
        }
        css = css.replace("#" + SCOPE_ID + " h2", "#" + SCOPE_ID + " h3");
        if (count(css, "#" + SCOPE_ID + " h2") > 0) {
            die("h2 规则替换不干净");
        }
        css = css + (
                "\n/* 与报告配色对齐：同特异性在后，覆盖风筝页自己的深色面板色 */\n"
                + "#" + SCOPE_ID + "{background:var(--mi-card,#1c1c1e);color:var(--mi-ink,#f5f5f7);"
                + "border:1px solid var(--mi-line,rgba(255,255,255,.16));"
                + "border-radius:var(--mi-radius,18px);padding:1rem 1.1rem}\n"
                + "#" + SCOPE_ID + " figure{margin:.6rem 0}\n"
                + "#" + SCOPE_ID + " .kite-lead{max-width:var(--mi-measure,68ch)}\n"
                + "#" + SCOPE_ID + " .kite-machine-note{color:var(--mi-ink-3,#a1a1a6);font-size:.95rem;"
                + "max-width:var(--mi-measure,68ch)}\n");

        int s0 = kite.indexOf("<script>");
        int s1 = kite.indexOf("</script>", Math.max(s0, 0));
        if (s0 < 0 || s1 < 0) {
            die("风筝页里找不到唯一的 <script> 块");
        }
        String script = kite.substring(s0 + "<script>".length(), s1);
        if (script.contains("<script") || script.contains("</script>")) {
            die("脚本抽取区间错了：抽取结果里还带着 script 标签");
        }
        if (script.contains("src=")) {
            die("脚本里出现 src=，不是纯内联");
        }
        int nBody = count(script, "document.body.setAttribute('data-trace-ok'");
        if (nBody != 1) {
            die("预期脚本里恰好 1 处 document.body.setAttribute('data-trace-ok'…，实际 " + nBody + " 处");
        }
        script = script.replace("document.body.setAttribute('data-trace-ok'",
                "document.getElementById('" + SCOPE_ID + "').setAttribute('data-trace-ok'");
        if (count(script, "document.body.setAttribute") > 0) {
            die("仍残留对宿主 body 的写入");
        }

        String block =
                "\n<section class=\"mi-kite\" id=\"" + KITE_SECTION_ID + "\" data-kite=\"1\">\n"
                + LEAD
                + MACHINE_NOTE
                + "<div id=\"" + SCOPE_ID + "\">\n"
                + frag
                + payloadData + "\n"
                + payloadModel + "\n"
                + "</div>\n"
                + "</section>\n"
                + "<script>" + script + "</script>\n";
        int nFragSections = count(frag, "<section");
        if (count(block, "<section") != nFragSections + 1) {
            die("组装区块的 section 数与预期不符");
        }

        String anchor = NEXT_SECTION_ANCHOR;
        if (count(report, anchor) != 1) {
            die("报告里 " + quote(anchor) + " 出现 " + count(report, anchor) + " 次（要求恰好 1 次）");
        }
        if (count(report, "<h2 id=\"sec-3\">") != 1) {
            die("报告里 <h2 id=\"sec-3\"> 不唯一，无法确认插入位置在图表区段内");
        }
        int ins = report.indexOf(anchor);
        if (report.indexOf("<h2 id=\"sec-3\">") > ins) {
            die("第 3 节标题在第 4 节之后，分节顺序异常");
        }
        String merged = report.substring(0, ins) + block + report.substring(ins);

        int k = merged.lastIndexOf("</style>");
        if (k < 0) {
            die("报告里没有 </style>");
        }
        merged = merged.substring(0, k)
                + "\n/* ── 行为风筝内嵌（作用域 #" + SCOPE_ID + "） ── */\n" + css
                + merged.substring(k);

        List<Map<String, String>> checks = new ArrayList<>();
        List<Boolean> all = new ArrayList<>();
        all.add(chk(checks, "报告仍恰 6 个 <h2>", count(merged, "<h2") == 6,
                "h2=" + count(merged, "<h2")));
        all.add(chk(checks, "h3 增量 = 片段 h2 数 + 1",
                count(merged, "<h3") == count(report, "<h3") + h2InFrag + 1,
                "h3 " + count(report, "<h3") + " → " + count(merged, "<h3")
                        + "（片段 h2=" + h2InFrag + "，新增导语标题 1）"));
        all.add(chk(checks, "<section> 与 </section> 配平",
                count(merged, "<section") == count(merged, "</section>"),
                "open=" + count(merged, "<section") + " close=" + count(merged, "</section>")));
        all.add(chk(checks, "脚本恰 1 块且全部内联",
                count(merged, "<script") == count(report, "<script") + 1
                        && count(merged, "<script src=") == 0,
                "<script> " + count(report, "<script") + " → " + count(merged, "<script")
                        + "，src= 形式 " + count(merged, "<script src=")));
        all.add(chk(checks, "零远程引用",
                count(merged, "src=\"http") == 0 && count(merged, "href=\"http") == 0
                        && count(merged, "@import") == 0 && count(merged, "url(http") == 0
                        && count(merged, "//cdn") == 0,
                "src=\"http=" + count(merged, "src=\"http") + " href=\"http=" + count(merged, "href=\"http")
                        + " @import=" + count(merged, "@import") + " url(http=" + count(merged, "url(http")
                        + " //cdn=" + count(merged, "//cdn")));
        boolean noNet = true;
        for (String t : List.of("fetch(", "XMLHttpRequest", "WebSocket", "EventSource",
                "sendBeacon", "eval(", "new Function", "document.write", "document.body.setAttribute")) {
            if (count(merged, t) != 0) {
                noNet = false;
            }
        }
        all.add(chk(checks, "零网络/危险 API", noNet,
                "fetch/XHR/WS/SSE/beacon/eval/Function/write/body-write 全 0"));
        all.add(chk(checks, "载荷仍为 hidden，未泄漏成大段 JSON",
                count(merged, "<pre data-machine hidden id=\"data\">") == 1
                        && count(merged, "<pre data-scene hidden id=\"scene-model\">") == 1,
                "两段载荷各 1 处且带 hidden"));
        all.add(chk(checks, "插入点在第 3 节标题之后、第 4 节标题之前",
                merged.indexOf("<h2 id=\"sec-3\">") < merged.indexOf("id=\"" + KITE_SECTION_ID + "\"")
                        && merged.indexOf("id=\"" + KITE_SECTION_ID + "\"") < merged.indexOf("<h2 id=\"sec-4\">"),
                "sec-3 < kite@" + merged.indexOf("id=\"" + KITE_SECTION_ID + "\"") + " < sec-4"));
        boolean ids = true;
        for (String id : List.of("scene", "scene-model", "data", "axes", "planes", "series", "marks",
                "readout", "tl", "reset", "viewA", "viewB", "camout", "drill", "drill-table", "tg")) {
            if (count(merged, "id=\"" + id + "\"") < 1) {
                ids = false;
            }
        }
        all.add(chk(checks, "交互元素齐备（缺一个脚本就空转）", ids, "16 个必需 id 全部存在"));

        boolean ok = true;
        for (Boolean b : all) {
            ok &= b.booleanValue();
        }

        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("fragment_chars", frag.length());
        extraction.put("fragment_h2_demoted", h2InFrag);
        extraction.put("fragment_sections", nFragSections);
        extraction.put("payload_data_chars", payloadData.length());
        extraction.put("payload_model_chars", payloadModel.length());
        extraction.put("style_rules_scoped", scoped.scopedRules());
        extraction.put("style_rule_preludes", scoped.rulePreludes());
        extraction.put("script_chars", script.length());
        extraction.put("body_write_rebound", nBody);
        extraction.put("dropped_orphan_section_close", 1);
        extraction.put("dropped_trailing_noscript", droppedNoscript);
        return new Result(merged, extraction, checks, ok);
    }

    private static String between(String text, String startMarker, String endMarker, String what) {
        int i = text.indexOf(startMarker);
        if (i < 0) {
            die("在风筝页里找不到区间起点 " + quote(startMarker) + "（" + what + "）");
        }
        int j = text.indexOf(endMarker, i + startMarker.length());
        if (j < 0) {
            die("在风筝页里找不到区间终点 " + quote(endMarker) + "（" + what + "）");
        }
        return text.substring(i, j);
    }

    private static String extractPayload(String kite, String marker, String what) {
        int i = kite.indexOf(marker);
        if (i < 0) {
            die("缺少隐藏载荷 " + quote(marker) + "（" + what + "）");
        }
        int j = kite.indexOf("</pre>", i);
        if (j < 0) {
            die("隐藏载荷 " + quote(marker) + " 没有闭合 </pre>（" + what + "）");
        }
        return kite.substring(i, j + "</pre>".length());
    }

    private record Scoped(String css, int rulePreludes, int scopedRules) {
    }

    static Scoped scopeCss(String cssIn, String scope) {
        String css = stripComments(cssIn);
        StringBuilder out = new StringBuilder();
        StringBuilder buf = new StringBuilder();
        List<Boolean> frames = new ArrayList<>();
        frames.add(Boolean.TRUE);
        int rulePreludes = 0;
        int scopedRules = 0;
        int n = css.length();
        int i = 0;
        while (i < n) {
            char ch = css.charAt(i);
            if (ch == '{') {
                String prelude = buf.toString().trim();
                buf.setLength(0);
                boolean scopedFrame = frames.get(frames.size() - 1).booleanValue();
                if (prelude.startsWith("@")) {
                    String head = prelude.isEmpty() ? "" : prelude.split("\\s+")[0].toLowerCase(Locale.ROOT);
                    out.append(prelude);
                    if (startsWithAny(head, AT_RULES_OPAQUE)) {
                        frames.add(Boolean.FALSE);
                    } else if (startsWithAny(head, AT_RULES_INHERIT_SCOPE)) {
                        frames.add(Boolean.TRUE);
                    } else {
                        frames.add(Boolean.FALSE);
                    }
                } else {
                    if (scopedFrame) {
                        List<String> parts = new ArrayList<>();
                        for (String p : splitTopCommas(prelude)) {
                            parts.add(scopeSelector(p, scope));
                        }
                        out.append(String.join(",", parts));
                        scopedRules++;
                        rulePreludes++;
                    } else {
                        out.append(prelude);
                    }
                    frames.add(Boolean.valueOf(scopedFrame));
                }
                out.append('{');
                i++;
                continue;
            }
            if (ch == '}') {
                out.append(buf);
                buf.setLength(0);
                out.append('}');
                if (frames.size() == 1) {
                    die("CSS 大括号不配对：多出一个 '}'");
                }
                frames.remove(frames.size() - 1);
                i++;
                continue;
            }
            buf.append(ch);
            i++;
        }
        if (!buf.toString().trim().isEmpty()) {
            die("CSS 尾部有游离内容：" + quote(headOf(buf.toString(), 60)));
        }
        if (frames.size() != 1) {
            die("CSS 大括号不配对：少了 " + (frames.size() - 1) + " 个 '}'");
        }
        String text = out.toString();
        if (count(text, "{") != count(text, "}")) {
            die("作用域化之后大括号不再配对");
        }
        return new Scoped(text, rulePreludes, scopedRules);
    }

    private static String stripComments(String css) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = css.length();
        while (i < n) {
            if (css.charAt(i) == '/' && i + 1 < n && css.charAt(i + 1) == '*') {
                int j = css.indexOf("*/", i + 2);
                if (j < 0) {
                    break;
                }
                i = j + 2;
                continue;
            }
            sb.append(css.charAt(i));
            i++;
        }
        return sb.toString();
    }

    static List<String> splitTopCommas(String sel) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int paren = 0;
        char quote = 0;
        for (int i = 0; i < sel.length(); i++) {
            char ch = sel.charAt(i);
            if (quote != 0) {
                buf.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                buf.append(ch);
                continue;
            }
            if (ch == '(') {
                paren++;
            } else if (ch == ')') {
                paren--;
            }
            if (ch == ',' && paren == 0) {
                out.add(buf.toString());
                buf.setLength(0);
            } else {
                buf.append(ch);
            }
        }
        out.add(buf.toString());
        return out;
    }

    static String scopeSelector(String selIn, String scope) {
        String sel = selIn.trim();
        if (sel.isEmpty()) {
            return sel;
        }
        String low = sel.toLowerCase(Locale.ROOT);
        for (String root : List.of("html,body", "html, body", "body,html", ":root", "body", "html")) {
            if (low.equals(root)) {
                return scope;
            }
            if (low.startsWith(root + " ") || low.startsWith(root + ">") || low.startsWith(root + ":")) {
                return scope + " " + sel.substring(root.length()).trim();
            }
        }
        if (low.startsWith(":root>")) {
            String rest = sel.substring(":root".length()).trim();
            int g = 0;
            while (g < rest.length() && rest.charAt(g) == '>') {
                g++;
            }
            return scope + " " + rest.substring(g).trim();
        }
        return scope + " " + sel;
    }

    public static KitePage renderKitePage(Path upstreamArtifact) {
        KiteViewUpstream.Info up = KiteViewUpstream.load(upstreamArtifact);
        if (!up.available()) {
            die("上游产物不可用，拒绝生成风筝页（不得用夹具/示意数据冒充真实曲线）：" + up.reason());
        }
        String note = "上游产物（" + up.path() + "，sha16 " + up.sha16()
                + "，N = " + up.pointCount() + "，三轴齐备 = " + up.usableCount()
                + "）逐点驱动；被抑制的轴记 `不可判定`（不记 0）。"
                + "上游仍在收口 ⇒ 收口后须重跑本页并更新指纹。";
        KiteViewData.Series series = up.series();
        List<KiteShapeCriteria.Hit> hits = KiteShapeCriteria.detectAll(series, up.deltaMaxSec());
        String html = KiteViewHtml.render(series, hits, up, note);
        return KitePage.of(true, "", up.path(), up.sha16(), up.bytes(),
                up.pointCount(), up.usableCount(), up.orthVerdict(), hits.size(), note, html);
    }

    public static Path defaultUpstreamArtifact() {
        return Paths.get(KiteViewUpstream.DEFAULT_ARTIFACT);
    }

    public static KitePage unavailablePage(Path upstreamArtifact) {
        KiteViewUpstream.Info up = KiteViewUpstream.load(upstreamArtifact);
        String path = up.path();
        String reason = up.available()
                ? "上游可用：本方法只用于上游缺失的情形"
                : up.reason();
        return KitePage.of(false, reason, path, "", 0L, 0, 0, "unavailable", 0,
                "**不适用**：" + reason, "");
    }

    public record KitePage(boolean available, String unavailableReason, String upstreamPath, String upstreamSha16,
                           long upstreamBytes, int upstreamPoints, int upstreamUsable, String upstreamVerdict,
                           int shapeHits, String dataSourceNote, String html,
                           Result embedded, Map<String, String> mergedFiles, String extractionJson,
                           String checksJson) {

        static KitePage of(boolean available, String unavailableReason, String upstreamPath, String upstreamSha16,
                           long upstreamBytes, int upstreamPoints, int upstreamUsable, String upstreamVerdict,
                           int shapeHits, String dataSourceNote, String html) {
            return new KitePage(available, unavailableReason, upstreamPath, upstreamSha16, upstreamBytes,
                    upstreamPoints, upstreamUsable, upstreamVerdict, shapeHits, dataSourceNote, html,
                    null, new LinkedHashMap<>(), "", "");
        }

        public boolean allPassed() {
            return embedded != null && embedded.allPassed();
        }
    }

    public static KitePage buildAndEmbed(String reportHtml, Path upstream) {
        return embedOnPage(reportHtml, renderKitePage(upstream));
    }

    public static KitePage buildAndEmbedLenient(String reportHtml, Path upstream) {
        KitePage page = renderKitePageOrUnavailable(upstream);
        return page.html().isEmpty() ? page : embedOnPage(reportHtml, page);
    }

    public static KitePage renderKitePageOrUnavailable(Path upstream) {
        KiteViewUpstream.Info up = KiteViewUpstream.load(upstream);
        return up.available() ? renderKitePage(upstream) : unavailablePage(upstream);
    }

    public static KitePage embedOnPage(String reportHtml, KitePage page) {
        Result r = embedDetailed(reportHtml, page.html());
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("report.html.kite", page.html());
        merged.put("kite-extraction.json", toJson(r.extraction()));
        merged.put("kite-checks.json", checksToJson(r.checks()));
        return new KitePage(page.available(), page.unavailableReason(), page.upstreamPath(),
                page.upstreamSha16(), page.upstreamBytes(), page.upstreamPoints(), page.upstreamUsable(),
                page.upstreamVerdict(), page.shapeHits(), page.dataSourceNote(), page.html(),
                r, merged, merged.get("kite-extraction.json"), merged.get("kite-checks.json"));
    }

    static String toJson(Map<String, Object> extraction) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, Object> e : extraction.entrySet()) {
            sb.append(first ? "  " : ",\n  ");
            sb.append('"').append(jsonEsc(e.getKey())).append("\": ");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(jsonEsc(String.valueOf(v))).append('"');
            }
            first = false;
        }
        return sb.append("\n}\n").toString();
    }

    static String checksToJson(List<Map<String, String>> checks) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < checks.size(); i++) {
            Map<String, String> c = checks.get(i);
            sb.append(i == 0 ? "  " : ",\n  ")
              .append("{\"criterion\": \"").append(jsonEsc(c.get("criterion")))
              .append("\", \"status\": \"").append(jsonEsc(c.get("status")))
              .append("\", \"evidence\": \"").append(jsonEsc(c.get("evidence"))).append("\"}");
        }
        return sb.append("\n]\n").toString();
    }

    private static final int OK = 0;
    private static final int FAIL = 1;
    private static final int ARGS = 2;
    private static final int CRASH = 4;

    public static void main(String[] args) throws IOException {
        String report = null;
        String kite = null;
        String out = null;
        String readings = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--help".equals(a)) {
                System.out.println("用法：--report <报告 HTML> --kite <风筝页 HTML> --out <输出 HTML> [--readings <读数 JSON>]");
                System.exit(OK);
            }
            if (!a.startsWith("--")) {
                System.out.println("ARGS-UNKNOWN: " + a);
                System.exit(ARGS);
            }
            if (i + 1 >= args.length) {
                System.out.println("ARGS-MISSING: " + a + " 缺少取值");
                System.exit(ARGS);
            }
            String v = args[++i];
            if ("--report".equals(a)) {
                report = v;
            } else if ("--kite".equals(a)) {
                kite = v;
            } else if ("--out".equals(a)) {
                out = v;
            } else if ("--readings".equals(a)) {
                readings = v;
            } else {
                System.out.println("ARGS-UNKNOWN: " + a);
                System.exit(ARGS);
            }
        }
        if (report == null || kite == null || out == null) {
            System.out.println("ARGS-MISSING: --report / --kite / --out 三者必填");
            System.exit(ARGS);
        }
        for (String p : List.of(report, kite)) {
            if (!Files.isRegularFile(Paths.get(p))) {
                System.out.println("TARGET-MISSING: " + p.replace('\\', '/'));
                System.exit(ARGS);
            }
        }
        try {
            byte[] reportRaw = Files.readAllBytes(Paths.get(report));
            byte[] kiteRaw = Files.readAllBytes(Paths.get(kite));
            Result r = embedDetailed(new String(reportRaw, StandardCharsets.UTF_8),
                    new String(kiteRaw, StandardCharsets.UTF_8));
            byte[] outRaw = r.html().getBytes(StandardCharsets.UTF_8);
            Files.write(Paths.get(out), outRaw);

            Map<String, Object> rd = new LinkedHashMap<>();
            rd.put("report", fingerprint(report, reportRaw, new String(reportRaw, StandardCharsets.UTF_8).length()));
            rd.put("kite", fingerprint(kite, kiteRaw, new String(kiteRaw, StandardCharsets.UTF_8).length()));
            rd.put("out", fingerprint(out, outRaw, r.html().length()));
            rd.put("extraction", r.extraction());
            rd.put("checks", r.checks());
            rd.put("all_passed", r.allPassed() ? Boolean.TRUE : Boolean.FALSE);
            if (readings != null) {
                writeReadings(Paths.get(readings), rd);
            }

            for (Map.Entry<String, Object> e : r.extraction().entrySet()) {
                System.out.println(String.format(Locale.ROOT, "  %-24s %s", e.getKey(), String.valueOf(e.getValue())));
            }
            for (Map<String, String> c : r.checks()) {
                System.out.println("[" + ("passed".equals(c.get("status")) ? "PASS" : "FAIL") + "] "
                        + c.get("criterion") + " — " + c.get("evidence"));
            }
            System.out.println("before: " + reportRaw.length + " B / " + sha16(reportRaw));
            System.out.println("after : " + outRaw.length + " B / " + sha16(outRaw));
            System.exit(r.allPassed() ? OK : FAIL);
        } catch (Crash c) {
            System.err.println("CRASH: " + c.getMessage());
            System.exit(CRASH);
        }
    }

    private static Map<String, Object> fingerprint(String path, byte[] raw, int chars) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path.replace('\\', '/'));
        m.put("bytes", (long) raw.length);
        m.put("chars", chars);
        m.put("sha16", sha16(raw));
        return m;
    }

    private static void writeReadings(Path p, Map<String, Object> rd) throws IOException {
        StringBuilder sb = new StringBuilder("{\n");
        for (String key : List.of("report", "kite", "out")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> o = (Map<String, Object>) rd.get(key);
            sb.append("  \"").append(key).append("\": {\"path\": \"").append(o.get("path"))
              .append("\", \"bytes\": ").append(o.get("bytes"))
              .append(", \"chars\": ").append(o.get("chars"))
              .append(", \"sha16\": \"").append(o.get("sha16")).append("\"},\n");
        }
        sb.append("  \"extraction\": {");
        @SuppressWarnings("unchecked")
        Map<String, Object> ex = (Map<String, Object>) rd.get("extraction");
        boolean first = true;
        for (Map.Entry<String, Object> e : ex.entrySet()) {
            sb.append(first ? "" : ", ").append("\"").append(e.getKey()).append("\": ").append(e.getValue());
            first = false;
        }
        sb.append("},\n  \"checks\": [");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> checks = (List<Map<String, String>>) rd.get("checks");
        for (int i = 0; i < checks.size(); i++) {
            Map<String, String> c = checks.get(i);
            sb.append(i == 0 ? "\n" : ",\n")
              .append("    {\"criterion\": \"").append(jsonEsc(c.get("criterion")))
              .append("\", \"status\": \"").append(c.get("status"))
              .append("\", \"evidence\": \"").append(jsonEsc(c.get("evidence"))).append("\"}");
        }
        sb.append("\n  ],\n  \"all_passed\": ").append(Boolean.TRUE.equals(rd.get("all_passed"))).append("\n}\n");
        Files.write(p, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static void die(String msg) {
        throw new Crash(msg);
    }

    private static boolean chk(List<Map<String, String>> checks, String name, boolean ok, String detail) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("criterion", name);
        m.put("status", ok ? "passed" : "failed");
        m.put("evidence", detail);
        checks.add(m);
        return ok;
    }

    static int count(String hay, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int n = 0;
        int i = hay.indexOf(needle);
        while (i >= 0) {
            n++;
            i = hay.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static String lstrip(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    private static String rstrip(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) {
            i--;
        }
        return s.substring(0, i);
    }

    private static String headOf(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String tailOf(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private static String quote(String s) {
        return "'" + s + "'";
    }

    private static boolean startsWithAny(String s, List<String> prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    static String sha16(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.ROOT, "%02X", Byte.valueOf(d[i])));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
