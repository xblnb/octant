package com.octant.pipeline.report.html;

import com.octant.pipeline.json.Json;
import com.octant.pipeline.json.JsonReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ContentSections {

    private ContentSections() {
    }

    private static final String TXT = "#f5f5f7";
    private static final String MUT = "#a1a1a6";
    private static final String DIM = "#8b8b90";
    private static final String ACC = "#70c8e8";
    private static final String ACC2 = "#7ad3a0";
    private static final String WARN = "#e8a33d";
    private static final String PANEL = "background:rgba(255,255,255,.05);border-left:3px solid ";

    private static final int MIN_TASKS_PER_CHAPTER = 10;
    private static final int MAX_CHAPTER_ROWS = 14;
    private static final int MAX_UNTOUCHED_ROWS = 10;
    private static final int MAX_IDLE_ROWS = 6;
    private static final int MAX_KILLER_ENTRIES = 6;
    private static final int MAX_COMPLEX_ROWS = 10;
    private static final int MAX_JUMP_ROWS = 10;
    private static final int MAX_SHALLOW_ROWS = 10;
    private static final int MAX_KILL_ROWS = 8;
    private static final int MAX_SPAN_ROWS = 10;
    private static final int MAX_KILLER_TABLE_ROWS = 8;
    private static final int MAX_SESSION_ROWS = 12;
    private static final int MIN_REPLAY_SEGMENT_SECONDS = 2;

    private static volatile java.util.Map<String, String[]> TIPS;

    private static final int MAX_TIPS_PER_DOMAIN = 2;
    private static final int MAX_TIPS_TOTAL = 14;

    private static final class TipBudget {
        private final java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        private final java.util.Map<String, Integer> perDomain = new java.util.LinkedHashMap<>();

        boolean take(String label) {
            if (keys.size() >= MAX_TIPS_TOTAL || keys.contains(label)) {
                return false;
            }
            String domain = label.contains(" · ") ? label.substring(0, label.indexOf(" · ")) : label;
            int used = perDomain.getOrDefault(domain, 0);
            if (used >= MAX_TIPS_PER_DOMAIN) {
                return false;
            }
            keys.add(label);
            perDomain.put(domain, used + 1);
            return true;
        }
    }

    private static java.util.Map<String, String[]> tips() {
        java.util.Map<String, String[]> cached = TIPS;
        if (cached != null) {
            return cached;
        }
        java.util.Map<String, String[]> out = new java.util.LinkedHashMap<>();
        try (java.io.InputStream in = ContentSections.class.getResourceAsStream("/content/tips.tsv")) {
            if (in != null) {
                String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    if (line.isBlank() || line.startsWith("#") || line.startsWith("---")) {
                        continue;
                    }
                    String[] f = line.split("\t", -1);
                    if (f.length >= 4 && !f[0].isBlank()) {
                        out.put(f[0].trim(), new String[]{f[1].trim(), f[2].trim(), f[3].trim()});
                    }
                }
            }
        } catch (Exception ignore) {
        }
        TIPS = out;
        return out;
    }

    private static String tipLib(String key, TipBudget budget) {
        String[] t = tips().get(key);
        if (t == null || !budget.take(t[0])) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(tip(t[0], t[1]));
        if (!t[2].isBlank()) {
            sb.append(act(t[2]));
        }
        return sb.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String em(String s) {
        if (s == null || s.indexOf("**") < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        int i = 0;
        while (true) {
            int a = s.indexOf("**", i);
            if (a < 0) {
                out.append(s, i, s.length());
                return out.toString();
            }
            int b = s.indexOf("**", a + 2);
            if (b < 0) {
                out.append(s, i, s.length());
                return out.toString();
            }
            out.append(s, i, a).append("<b>").append(s, a + 2, b).append("</b>");
            i = b + 2;
        }
    }

    private static String h2(String id, String s) {
        return "<h2 id=\"" + id + "\" style=\"color:" + TXT
                + ";margin:2.2rem 0 .4rem;font-size:1.5rem\">" + esc(s) + "</h2>";
    }

    private static String kp(String label, String body) {
        return "<p data-critical-path style=\"color:" + TXT + ";margin:.35rem 0;line-height:1.75\">"
                + "<b style=\"color:" + ACC + "\">" + esc(label) + "</b>·" + body + "</p>";
    }

    private static String mountainChart(Map<String, Object> g) {
        if (g.isEmpty()) {
            return "";
        }
        List<Object> days = arr(g.get("days"));
        if (days.size() < 2) {
            return "";
        }
        int n = days.size();
        int peak = i(g.get("peakGained"));
        int cumMax = Math.max(1, i(g.get("totalDone")));
        double x0 = 54;
        double x1 = 702;
        double y0 = 22;
        double y1 = 196;
        double plotW = x1 - x0;
        double plotH = y1 - y0;
        double step = plotW / n;
        double barW = Math.max(2.0, step * 0.62);
        StringBuilder ridge = new StringBuilder();
        StringBuilder bars = new StringBuilder();
        StringBuilder line = new StringBuilder();
        StringBuilder pts = new StringBuilder();
        double highestY = y1;
        for (int k = 0; k < n; k++) {
            Map<String, Object> d = obj(days.get(k));
            int day = i(d.get("day"));
            double gained = i(d.get("gained"));
            double cum1 = i(d.get("cumulative"));
            double cx = x0 + step * (k + 0.5);
            double h = peak <= 0 ? 0 : (plotH * gained / peak);
            double top = y1 - h;
            highestY = Math.min(highestY, top);
            long bx = Math.round(cx - barW / 2);
            String tipText = "第 " + day + " 天：当日推进 " + (int) gained + " 条，累计 "
                    + Math.round(cum1) + " / " + i(g.get("totalDefinition")) + " 条（"
                    + n2(d(g.get("completionPct"))) + "%）";
            bars.append("<rect x=\"").append(bx).append("\" y=\"").append(Math.round(top))
                    .append("\" width=\"").append(Math.round(barW))
                    .append("\" height=\"").append(Math.round(Math.max(0.0, h)))
                    .append("\" data-tip=\"").append(esc(tipText))
                    .append("\" rx=\"1.5\" fill=\"").append(ACC)
                    .append("\" opacity=\"").append(gained > 0 ? "0.85" : "0").append("\"/>");
            ridge.append(ridge.length() == 0 ? "M" : "L")
                    .append(Math.round(cx)).append(',').append(Math.round(top));
            double cy = y1 - plotH * (cum1 / cumMax);
            line.append(line.length() == 0 ? "M" : "L")
                    .append(Math.round(cx)).append(',').append(Math.round(cy));
            pts.append("<circle cx=\"").append(Math.round(cx)).append("\" cy=\"")
                    .append(Math.round(cy)).append("\" r=\"3\" fill=\"").append(ACC2)
                    .append("\" opacity=\".95\"/>");
        }
        ridge.append('L').append(Math.round(x1 - step / 2)).append(',').append(Math.round(y1))
                .append('L').append(Math.round(x0 + step / 2)).append(',').append(Math.round(y1)).append('Z');
        int lastDay = i(obj(days.get(n - 1)).get("day"));
        int totalDone = i(g.get("totalDone"));
        int totalDef = i(g.get("totalDefinition"));
        int idleDays = i(g.get("idleDays"));
        int peakDay = i(g.get("peakDay"));
        String takeaway = "第 " + peakDay + " 天推进最多（" + peak + " 条），这段时间里有 "
                + idleDays + " 天完全没有推进；到第 " + lastDay + " 天累计完成 "
                + totalDone + " / " + totalDef + " 条。";
        StringBuilder svg = new StringBuilder();
        svg.append("<figure data-layout=\"full-width-figure\" id=\"G1\" style=\"position:relative\" ")
                .append("data-chart=\"mountain\" data-series-count=\"2\" data-x-axis=\"游戏日\" ")
                .append("data-source=\"content-insights:G_timeline\" data-high-low=\"annotated\" ")
                .append("data-takeaway=\"").append(esc(takeaway)).append("\">\n");
        svg.append("<svg viewBox=\"0 0 720 232\" width=\"100%\" role=\"img\" aria-label=\"")
                .append(esc(takeaway))
                .append("\" style=\"display:block;max-width:100%;height:auto\">\n");
        svg.append("<rect x=\"").append(x0).append("\" y=\"").append(y0).append("\" width=\"")
                .append(plotW).append("\" height=\"").append(plotH)
                .append("\" fill=\"rgba(255,255,255,.03)\"/>\n");
        StringBuilder groupPaths = new StringBuilder();
        StringBuilder groupCums = new StringBuilder();
        StringBuilder groupBtns = new StringBuilder();
        List<Object> groups = arr(g.get("groups"));
        for (Object go : groups) {
            Map<String, Object> grp = obj(go);
            List<Object> gains = arr(grp.get("gains"));
            StringBuilder p = new StringBuilder();
            for (int k = 0; k < gains.size() && k < n; k++) {
                double gv = i(gains.get(k));
                double gx = x0 + step * (k + 0.5);
                double gy = y1 - (peak <= 0 ? 0 : plotH * gv / peak);
                p.append(p.length() == 0 ? "M" : "L").append(Math.round(gx)).append(',').append(Math.round(gy));
            }
            String name = str(grp.get("modName")) + "（" + i(grp.get("total")) + " 条）";
            groupPaths.append("<path data-group=\"").append(esc(name))
                    .append("\" data-series=\"当日推进量\" d=\"").append(p)
                    .append("\" fill=\"").append(ACC).append("\" opacity=\"0.16\"")
                    .append(" style=\"display:none\"/>\n");
            List<Object> cumG = arr(grp.get("cumulative"));
            StringBuilder cp = new StringBuilder();
            for (int k = 0; k < cumG.size() && k < n; k++) {
                double cv = i(cumG.get(k));
                double gx = x0 + step * (k + 0.5);
                double gy = y1 - plotH * (cv / cumMax);
                cp.append(cp.length() == 0 ? "M" : "L").append(Math.round(gx)).append(',')
                        .append(Math.round(gy));
            }
            groupCums.append("<path data-group-cum=\"").append(esc(name)).append("\" data-series=\"累计完成条数\" d=\"")
                    .append(cp).append("\" fill=\"none\" stroke=\"").append(ACC2)
                    .append("\" stroke-width=\"2.2\" stroke-linejoin=\"round\"")
                    .append(" style=\"display:none\"/>\n");
            groupBtns.append("<button type=\"button\" class=\"mi-sw\" data-show=\"")
                    .append(esc(name)).append("\" style=\"background:rgba(255,255,255,.06);color:")
                    .append(TXT).append(";border:1px solid rgba(255,255,255,.18);border-radius:99px;"
                            + "padding:.22rem .6rem;font-size:.82rem;line-height:1.4;cursor:pointer;"
                            + "max-width:100%;overflow-wrap:anywhere\">")
                    .append(esc(name)).append("</button>\n");
        }
        svg.append("<path data-group=\"全部\" data-series=\"当日推进量\" d=\"").append(ridge)
                .append("\" fill=\"").append(ACC).append("\" opacity=\"0.16\"/>\n");
        svg.append(groupPaths);
        svg.append(groupCums);
        svg.append(bars).append('\n');
        svg.append(groupBtns.length() == 0 ? "" : "<div class=\"mi-swrow\" style=\"display:flex;"
                + "flex-wrap:wrap;gap:.35rem;margin:.5rem 0 0\">"
                + "<button type=\"button\" class=\"mi-sw\" data-show=\"全部\" style=\""
                + "background:rgba(255,255,255,.06);color:" + TXT + ";border:1px solid rgba(255,255,255,.18);"
                + "border-radius:99px;padding:.22rem .6rem;font-size:.82rem;line-height:1.4;cursor:pointer;"
                + "max-width:100%;overflow-wrap:anywhere\">全部推进量</button>"
                + groupBtns + "</div>\n");
        svg.append("<path data-group-cum=\"全部\" data-series=\"累计完成条数\" d=\"").append(line)
                .append("\" fill=\"none\" stroke=\"").append(ACC2)
                .append("\" stroke-width=\"2.2\" stroke-linejoin=\"round\"/>\n");
        double peakX = x0 + step * (peakDay + 0.5);
        svg.append("<line x1=\"").append(Math.round(peakX)).append("\" y1=\"")
                .append(Math.round(highestY - 6)).append("\" x2=\"").append(Math.round(peakX))
                .append("\" y2=\"").append(Math.round(y1)).append("\" stroke=\"").append(WARN)
                .append("\" stroke-width=\"1\" stroke-dasharray=\"3 3\" opacity=\".75\"/>");
        svg.append("<text x=\"").append(Math.round(peakX)).append("\" y=\"")
                .append(Math.round(Math.max(12.0, highestY - 8)))
                .append("\" fill=\"#e8a33d")
                .append("\" font-size=\"12\" text-anchor=\"middle\">第 ")
                .append(peakDay).append(" 天 · ").append(peak).append(" 条</text>\n");
        svg.append("<line x1=\"").append(Math.round(x0)).append("\" y1=\"").append(Math.round(y1))
                .append("\" x2=\"").append(Math.round(x1)).append("\" y2=\"").append(Math.round(y1))
                .append("\" stroke=\"").append(DIM).append("\" stroke-width=\"1\"/>");
        svg.append("<text x=\"").append(x0).append("\" y=\"").append(y1 + 16)
                .append("\" fill=\"").append(DIM).append("\" font-size=\"12\">第 0 天</text>\n");
        svg.append("<text x=\"").append(x1).append("\" y=\"").append(y1 + 16)
                .append("\" fill=\"").append(DIM)
                .append("\" font-size=\"12\" text-anchor=\"end\">第 ").append(lastDay)
                .append(" 天</text>\n");
        svg.append("</svg>\n");
        svg.append("<div class=\"mi-tip\" data-mountain-tip hidden style=\"position:absolute;top:.2rem;"
                + "transform:translateX(-50%);max-width:min(22rem,92%);"
                + "background:rgba(20,20,22,.92);border:1px solid rgba(255,255,255,.18);"
                + "border-radius:5px;padding:.3rem .55rem;color:" + TXT + ";font-size:.85rem;line-height:1.5;"
                + "pointer-events:none;white-space:normal;overflow-wrap:anywhere\"></div>\n");
        svg.append("<script>(function(){var f=document.getElementById('G1');if(!f)return;")
                .append("var box=f.querySelector('[data-mountain-tip]');")
                .append("function show(e){var t=e.target&&e.target.getAttribute&&e.target.getAttribute('data-tip');")
                .append("if(!t){box.hidden=true;return;}box.textContent=t;box.hidden=false;")
                .append("var r=f.getBoundingClientRect();var x=(e.clientX||0)-r.left;")
                .append("box.style.left=Math.max(0,Math.min(r.width-8,x))+'px';}")
                .append("f.addEventListener('pointermove',show);")
                .append("f.addEventListener('pointerdown',show);")
                .append("f.addEventListener('pointerleave',function(){box.hidden=true;});")
                .append("})();</script>\n");
        svg.append("<script>(function(){var f=document.getElementById('G1');if(!f)return;")
                .append("var ps=f.querySelectorAll('[data-group]');var bs=f.querySelectorAll('.mi-sw');")
                .append("function pick(name){for(var i=0;i<ps.length;i++){")
                .append("ps[i].style.display=(ps[i].getAttribute('data-group')===name)?'':'none';}")
                .append("var cs=f.querySelectorAll('[data-group-cum]');for(var q=0;q<cs.length;q++){cs[q].style.display=(cs[q].getAttribute('data-group-cum')===name)?'':'none';}")
                .append("for(var j=0;j<bs.length;j++){var on=bs[j].getAttribute('data-show')===name;")
                .append("bs[j].setAttribute('aria-pressed',on?'true':'false');")
                .append("bs[j].style.background=on?'rgba(112,200,232,.22)':'rgba(255,255,255,.06)';}}")
                .append("for(var j=0;j<bs.length;j++){bs[j].addEventListener('click',function(){")
                .append("pick(this.getAttribute('data-show'));});}")
                .append("pick('全部');})();</script>\n");
        svg.append(cap("横轴＝游戏日（第 0 天＝这个存档里最早的一次成就）。"
                + "<b style=\"color:" + ACC + "\">山脊</b>＝当天首次完成了多少条（最高 " + peak
                + " 条）；<b style=\"color:" + ACC2 + "\">爬升线</b>＝累计完成条数（按自身最大值 "
                + totalDone + " 归一；与山脊不是同一量纲，所以只比各自的形状）。"
                + "谷底为 0 的天数＝确实没有推进，不做插值。"));
        svg.append("</figure>\n");
        return svg.toString();
    }

    private static String nav() {
        return "<nav class=\"mi-nav\" id=\"top\" aria-label=\"章节跳转\">\n"
                + "<div class=\"mi-nav-row\"><a class=\"mi-nav-brand\" href=\"#top\">Octant（卦限）</a>"
                + "<a href=\"#c1\">你玩到了什么</a><a href=\"#c2\">卡在哪</a>"
                + "<a href=\"#c3\">你怎么打的</a><a href=\"#c4\">数值平衡</a></div>\n"
                + "<div class=\"mi-nav-row sub\"><a href=\"#c5\">隐私条款</a>"
                + "<a href=\"#c6\">机器附录</a></div>\n</nav>\n";
    }

    private static String h3(String s) {
        return "<h3 style=\"color:" + TXT + ";margin:1.6rem 0 .2rem;font-size:1.15rem\">" + esc(s) + "</h3>";
    }

    private static String replaySection(String replayJson, TipBudget usedTips) {
        if (replayJson == null || replayJson.isBlank()) {
            return "";
        }
        Map<String, Object> r;
        try {
            r = obj(JsonReader.parseObject(replayJson));
        } catch (Exception e) {
            return "";
        }
        if (r.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(1 << 13);
        List<Object> sessions = arr(r.get("sessions"));
        List<Object> segs = arr(r.get("segments"));
        String source = str(r.get("signalSource"));

        sb.append(h3("时间花在哪：会话回放与玩家上下文"));
        sb.append("<p style=\"color:").append(TXT).append(";margin:.5rem 0;line-height:1.8\">")
          .append("这一段把 ").append(i(r.get("sessionTotal"))).append(" 个会话的时间分档：")
          .append("探索、推进、战斗、整理与自动化，各占多久。</p>");
        sb.append("<p style=\"color:").append(TXT).append(";margin:.35rem 0;line-height:1.8\">")
          .append("它只看事件的<b>类型与时刻</b>，不看物品名、不看聊天，")
          .append("所以这一段不可能带出别处没脱敏的东西。</p>");
        String src = str(r.get("sourceLabel"));
        if (src != null && !src.isBlank() && !"null".equals(src)) {
            sb.append(cap("口径：这一段的事件来源是<b>" + esc(src) + "</b>"
                    + (i(r.get("eventCount")) > 0 ? "（" + i(r.get("eventCount")) + " 条事件）" : "")
                    + "；报告其余部分的指标与内容层可能来自另一条来源，各自的口径写在各自的位置上。"));
        }

        if (sessions.isEmpty()) {
            sb.append(cap("没有读到会话 ⇒ 这一段没有可展示的数据。"));
            return sb.toString();
        }
        List<List<String>> rows = new ArrayList<>();
        int idx = 0;
        List<String> lanes = null;
        for (Object o : sessions) {
            if (lanes == null) {
                lanes = new ArrayList<>();
                for (Object k : arr(obj(o).get("laneOrder"))) {
                    lanes.add(str(k));
                }
            }
            idx++;
            if (idx > MAX_SESSION_ROWS) {
                continue;
            }
            Map<String, Object> s = obj(o);
            Map<String, Object> lm = obj(s.get("lanesMs"));
            long dur = d0(s.get("durationMs"));
            List<String> row = new ArrayList<>();
            row.add("第 " + idx + " 个会话");
            row.add(n2(dur / 60_000.0) + " 分钟");
            String top1 = null;
            String top2 = null;
            long v1 = -1;
            long v2 = -1;
            for (String lane : (lanes == null ? List.<String>of() : lanes)) {
                long v = d0(lm.get(lane));
                if (v > v1) {
                    v2 = v1;
                    top2 = top1;
                    v1 = v;
                    top1 = lane;
                } else if (v > v2) {
                    v2 = v;
                    top2 = lane;
                }
            }
            for (String lane : (lanes == null ? List.<String>of() : lanes)) {
                long v = d0(lm.get(lane));
                String pct = dur <= 0 ? "0%" : Math.round(100.0 * v / dur) + "%";
                String cell = n2(v / 60_000.0) + " 分钟（" + pct + "）";
                if (lane.equals(top1) || lane.equals(top2)) {
                    cell = bar(dur <= 0 ? 0 : 100.0 * v / dur, cell,
                            lane.equals(top1) ? ACC : ACC2);
                }
                row.add(cell);
            }
            rows.add(row);
        }
        List<String> heads = new ArrayList<>();
        heads.add("会话");
        heads.add("时长");
        for (String lane : (lanes == null ? List.<String>of() : lanes)) {
            heads.add(lane);
        }
        sb.append(table(heads, rows));
        if (idx > rows.size()) {
            sb.append(cap("共 " + idx + " 个会话，下表列出最前面的 " + rows.size()
                    + " 个（按会话号排序，序号稳定）。"));
        }
        sb.append(cap("口径：一档的时间 = <b>最近一次「宣告了状态」的事件</b>起算，"
                + "保持到下一次宣告；交战区间内一律计入战斗。"));
        sb.append(cap("「无观测」是<b>没有任何事件说明你在做什么</b>的那部分时间——"
                + "它既不是「没玩」，也不是「在闲逛」，而是「我们没有数据」。"
                + "想知道这一段具体在干什么，得靠采集侧多接一类事件，而不是靠这里猜。"));
        sb.append(cap("另外：心跳、登录、登出这类事件<b>不宣告状态</b>——它们说明会话还活着，"
                + "不说明你在干什么。把它们算成「探索」会让这一栏虚高。"));
        sb.append(tipLib(d0(r.get("sessionTotal")) >= 5 ? "replay_lanes" : "replay_lanes_few", usedTips));

        sb.append("<p style=\"color:").append(TXT).append(";margin:1.1rem 0 .2rem;line-height:1.8\">")
          .append("<b>值得回看的 ").append(segs.size()).append(" 段</b>")
          .append("（从事件流里挑出来的少数几段，每段给一个主理由）：</p>");
        if (segs.isEmpty()) {
            sb.append(cap(esc(str(r.get("note")))));
        } else {
            java.util.Map<String, Integer> order = new java.util.LinkedHashMap<>();
            int k = 0;
            for (Object o : sessions) {
                k++;
                order.put(str(obj(o).get("sessionId")), k);
            }
            List<List<String>> sr = new ArrayList<>();
            for (Object o : segs) {
                Map<String, Object> g = obj(o);
                Integer n = order.get(str(g.get("sessionId")));
                sr.add(List.of("第 " + (n == null ? "?" : n) + " 个会话",
                        clock(d0(g.get("startMs"))) + " → " + clock(d0(g.get("endMs"))),
                        Math.round(d0(g.get("lengthMs")) / 1000.0) + " 秒",
                        esc(str(g.get("kind"))),
                        esc(joinText(arr(g.get("reasons"))))));
            }
            sb.append(table(List.of("会话", "时间窗", "时长", "类别", "为什么值得看"), sr));
            sb.append(cap("口径：顺序是 <b>类别强度 → 会话 → 时间</b>（强度：死亡 &gt; 战斗异常 &gt; "
                    + "反复失败 &gt; 停滞 &gt; 首次触达），同一份数据两次导出结果一致。"
                    + "低于 " + MIN_REPLAY_SEGMENT_SECONDS + " 秒的窗口不会被列出来——"
                    + "短到看不成的片段不叫片段。"));
        }
        if ("collector".equals(source)) {
            sb.append(cap("这些信号是<b>采集端在当时当地</b>判定并记下来的（停滞 / 反复失败），"
                    + "不是事后从心跳间隔反推的。"));
        } else if ("derived".equals(source)) {
            sb.append(cap("本流里没有采集端自报的停滞/失败信号，这些片段是"
                    + "<b>由交战时长与心跳间隔推出来的</b>；口径与判定线都写在上面。"));
        }
        if (!segs.isEmpty()) {
            sb.append(tipLib("replay_long_combat", usedTips));
        }
        sb.append("<pre data-machine hidden>").append(esc(replayJson)).append("</pre>\n");
        return sb.toString();
    }

    private static String clock(long ms) {
        long total = Math.max(0, ms) / 1000;
        long m = total / 60;
        long s = total % 60;
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    private static String joinText(List<Object> items) {
        StringBuilder sb = new StringBuilder();
        for (Object o : items) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(str(o));
        }
        return sb.toString();
    }

    private static long d0(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String n2(double v) {
        long hundredths = Math.round(v * 100.0);
        long frac = Math.abs(hundredths % 100);
        if (frac == 0) {
            return String.valueOf(hundredths / 100);
        }
        return (hundredths / 100) + (frac < 10 ? ".0" + frac : "." + frac);
    }

    private static String cap(String s) {
        return "<p style=\"color:" + DIM + ";font-size:.86rem;margin:.5rem 0 0;line-height:1.7\">"
                + em(s) + "</p>";
    }

    private static String why(String s) {
        return "<p style=\"margin:.8rem 0 0;padding:.7rem .9rem;" + PANEL + ACC
                + ";border-radius:0 6px 6px 0;color:" + MUT + ";font-size:.95rem;line-height:1.75\">"
                + em(s) + "</p>";
    }

    private static String tip(String field, String body) {
        return "<p style=\"margin:.7rem 0 0;padding:.6rem .85rem;background:rgba(122,211,160,.06);"
                + "border-left:3px solid " + ACC2 + ";border-radius:0 6px 6px 0;color:" + MUT
                + ";font-size:.92rem;line-height:1.75\"><b style=\"color:" + ACC2 + "\">小提示 · "
                + esc(field) + "</b>　" + em(body) + "</p>";
    }

    private static String act(String body) {
        return "<p style=\"margin:.35rem 0 0 .9rem;padding:.55rem .85rem;background:rgba(255,255,255,.04);"
                + "border-left:3px solid rgba(255,255,255,.18);border-radius:0 6px 6px 0;color:" + MUT
                + ";font-size:.9rem;line-height:1.75\"><b style=\"color:" + ACC
                + "\">如果你要改</b>　" + em(body) + "</p>";
    }

    private static String bar(double pct, String label, String color) {
        double w = Math.max(1.5, Math.min(100.0, pct));
        long fill = Math.round(w * 2.4);
        return "<span style=\"display:inline-flex;align-items:center;gap:.55rem;flex-wrap:wrap\">"
                + "<svg viewBox=\"0 0 240 12\" width=\"100%\" height=\"12\" role=\"img\" "
                + "aria-label=\"" + esc(label) + "\" style=\"flex:1 1 6rem;min-width:5rem;max-width:14rem\">"
                + "<rect x=\"0\" y=\"3\" width=\"240\" height=\"6\" rx=\"3\" fill=\"rgba(255,255,255,.10)\"/>"
                + "<rect x=\"0\" y=\"3\" width=\"" + fill + "\" height=\"6\" rx=\"3\" fill=\"" + color + "\"/>"
                + "</svg>"
                + "<span style=\"color:" + MUT + ";font-size:.88rem;white-space:nowrap\">"
                + esc(label) + "</span></span>";
    }

    private static String table(List<String> heads, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table style=\"width:100%;border-collapse:collapse;margin:.7rem 0 0;font-size:.95rem;"
                + "table-layout:fixed\"><thead><tr>");
        for (String h : heads) {
            sb.append("<th style=\"text-align:left;padding:.4rem .6rem;color:").append(MUT)
              .append(";font-weight:600;border-bottom:1px solid rgba(255,255,255,.10)\">").append(esc(h)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (List<String> r : rows) {
            sb.append("<tr>");
            for (String c : r) {
                sb.append("<td style=\"padding:.4rem .6rem;color:").append(TXT)
                  .append(";border-bottom:1px solid rgba(255,255,255,.10);vertical-align:top\">").append(c).append("</td>");
            }
            sb.append("</tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    private static Map<String, Object> obj(Object o) {
        return o instanceof Json.JsonObject jo ? jo.members() : Map.of();
    }

    private static List<Object> arr(Object o) {
        if (o instanceof Json.JsonArray ja) {
            List<Object> l = new ArrayList<>();
            l.addAll(ja.items());
            return l;
        }
        return List.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int i(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static double d(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    static String render(String contentJson) {
        return render(contentJson, -1, -1, null);
    }

    static String render(String contentJson, int suppressed, int zero) {
        return render(contentJson, suppressed, zero, null);
    }

    static String render(String contentJson, int suppressed, int zero, String replayJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        Json.JsonObject root;
        try {
            root = JsonReader.parseObject(contentJson);
        } catch (Exception e) {
            return null;
        }
        Map<String, Object> a = obj(root.get("A_untouched"));
        Map<String, Object> b = obj(root.get("B_stalls"));
        Map<String, Object> c = obj(root.get("C_combat"));
        Map<String, Object> d = obj(root.get("D_quests"));
        Map<String, Object> e = obj(root.get("E_waypoints"));
        Map<String, Object> f = obj(root.get("F_recipes"));
        if (a.isEmpty() && d.isEmpty() && e.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(1 << 16);
        TipBudget usedTips = new TipBudget();

        sb.append(nav());
        sb.append("<p class=\"intro-h\" id=\"sec-0\">这份报告在说什么</p>\n");
        sb.append("<p class=\"lead\">下面四节只写你自己的游玩记录：玩到了什么、卡在哪、怎么打的、"
                + "数值平衡。结尾是隐私条款，以及一个默认折叠的机器附录（判据与可核查字段，"
                + "不读也不影响理解）。</p>\n");
        int ut0 = i(a.get("untouchedCount"));
        sb.append(kp("你玩到了什么", "任务书 <b>" + i(d.get("chaptersTotal")) + " 章 / "
                + i(d.get("questsTotal")) + " 条</b>，你完成了 <b>" + i(d.get("questsDone"))
                + " 条</b>；<b>" + ut0 + " 个模组</b>一次都没碰过，<b>" + i(e.get("waypointCount"))
                + " 个地图标点</b>是你自己标的。"));
        int gaps = i(b.get("gapsTotal"));
        int idle = i(b.get("idleModsTotal"));
        int minGap = i(b.get("minGapHours"));
        sb.append(kp("卡在哪", gaps > 0
                ? "有 <b>" + gaps + " 处</b>推进之间停了 " + minGap + " 小时以上；另有 <b>" + idle
                        + " 个模组</b>只碰过一点就没再动。"
                : "没有出现 " + minGap + " 小时以上的停顿；另有 <b>" + idle
                        + " 个模组</b>只碰过一点就没再动。"));
        int deaths = i(c.get("deaths"));
        List<Object> pk0 = arr(c.get("playerKillers"));
        String topKiller = pk0.isEmpty() ? null
                : esc(str(obj(pk0.get(0)).get("name"))) + "（" + i(obj(pk0.get(0)).get("count")) + " 次）";
        sb.append(kp("你怎么打的", String.format(java.util.Locale.ROOT, "%.1f", d(c.get("playHours")))
                + " 小时里死了 <b>" + deaths + " 次</b>"
                + (topKiller == null ? "" : "，最多来自 <b>" + topKiller + "</b>")
                + "；杀过 <b>" + i(c.get("mobTypesKilled")) + " 种</b>怪物。"));
        List<Object> tc = arr(f.get("topComplex"));
        List<Object> jp = arr(f.get("jumps"));
        String heaviest = tc.isEmpty() ? null
                : esc(str(obj(tc.get(0)).get("item"))) + "（depth " + i(obj(tc.get(0)).get("depth")) + "）";
        String steepest = jp.isEmpty() ? null
                : "《" + esc(str(obj(jp.get(0)).get("chapter"))) + "》的 +" + i(obj(jp.get(0)).get("delta"));
        sb.append(kp("数值平衡", "<b>" + i(f.get("recipes")) + " 条</b>配方可解析（<b>"
                + i(f.get("unparsed")) + " 条</b>未解析 ⇒ 本节数值都是下界）"
                + (heaviest == null ? "" : "；最重的一件是 <b>" + heaviest + "</b>")
                + (steepest == null ? "" : "；任务书里最陡的台阶是 " + steepest) + "。"));
        if (suppressed >= 0) {
            sb.append("<p data-suppressed-summary=\"").append(suppressed).append("\" data-zero-summary=\"")
                    .append(zero).append("\" style=\"color:").append(MUT)
                    .append(";margin:.9rem 0;font-size:.92rem;line-height:1.75\">")
                    .append("另有 <b>").append(suppressed).append(" 项</b>指标因样本不足被抑制")
                    .append(zero > 0 ? "（其中 " + zero + " 项为零值）" : "")
                    .append("；逐项的状态、原因码与所需样本量列在末尾机器附录的 X4 表里。</p>\n");
        }

        sb.append(h2("c1", "1 · 你玩到了什么"));
        sb.append(mountainChart(obj(root.get("G_timeline"))));
        if (!d.isEmpty()) {
            boolean progressRead = Boolean.TRUE.equals(d.get("progressRead"));
            sb.append("<p style=\"color:").append(TXT).append("\">任务书：<b>")
              .append(i(d.get("chaptersTotal"))).append(" 个章节 · ").append(i(d.get("questsTotal")))
              .append(" 条任务</b>，")
              .append(progressRead
                      ? "已完成 <b>" + i(d.get("questsDone")) + " 条</b>（进度取自存档 "
                              + esc(str(d.get("progressFileDir"))) + "，"
                              + i(d.get("progressFileBytes")) + " 字节；候选文件 "
                              + i(d.get("progressFileCandidates")) + " 个）。"
                      : "<b>已完成条数未知</b>　<span style=\"color:" + WARN
                              + "\">进度未读到：存档里没找到 FTB 进度文件，或读它失败 ⇒ "
                              + "下面的完成数是「未知」，不是「零」。</span>")
              .append("</p>");
            List<List<String>> rows = new ArrayList<>();
            int eligible = 0;
            for (Object o : arr(d.get("chapters"))) {
                Map<String, Object> ch = obj(o);
                int tot = i(ch.get("tasksTotal"));
                int done = i(ch.get("tasksDone"));
                if (tot < MIN_TASKS_PER_CHAPTER) {
                    continue;
                }
                eligible++;
                if (rows.size() >= MAX_CHAPTER_ROWS) {
                    continue;
                }
                int barPct = (int) Math.round(100.0 * Math.min(done, tot) / tot);
                rows.add(List.of(esc(str(ch.get("title"))), progressRead
                        ? bar(Math.min(100.0, 100.0 * done / tot),
                                done + " / " + tot + " 项（" + barPct + "%）", ACC)
                        : "<span style=\"color:" + WARN + "\">" + tot + " 项 · 完成数未知</span>"));
            }
            if (!rows.isEmpty()) {
                sb.append(table(List.of("章节", "完成进度"), rows));
                sb.append(cap("章节名取自你的任务书定义文件；共 " + i(d.get("chaptersTotal"))
                        + " 个章节，其中任务数 ≥ " + MIN_TASKS_PER_CHAPTER + " 的有 " + eligible
                        + " 个，下表列出其中进度最高的 " + rows.size() + " 个。"));
            }
            int pctMin = 101;
            int pctMax = -1;
            for (Object o : arr(d.get("chapters"))) {
                Map<String, Object> ch = obj(o);
                int tot = i(ch.get("tasksTotal"));
                if (tot < MIN_TASKS_PER_CHAPTER) {
                    continue;
                }
                int pc = (int) Math.round(100.0 * Math.min(i(ch.get("tasksDone")), tot) / tot);
                pctMin = Math.min(pctMin, pc);
                pctMax = Math.max(pctMax, pc);
            }
            int chapterSpread = pctMax < 0 ? 0 : pctMax - pctMin;
            sb.append(tipLib(chapterSpread >= 40 ? "quest_chapters_uneven" : "quest_chapters_even",
                    usedTips));
            int advDone = i(root.get("advancementsDone"));
            int advTotal = i(root.get("advancementsTotal"));
            int qDone = i(d.get("questsDone"));
            int qTotal = i(d.get("questsTotal"));
            if (progressRead && advTotal > 0 && qTotal > 0) {
                double qPct = 100.0 * qDone / qTotal;
                double aPct = 100.0 * advDone / advTotal;
                if (qPct >= 3 * Math.max(aPct, 1.0)) {
                    sb.append(cap("两个完成口径并列，别混着比：任务书 <b>" + qDone + " / " + qTotal
                            + "（" + Math.round(qPct) + "%）</b>，原版成就 <b>" + advDone + " / "
                            + advTotal + "（" + Math.round(aPct) + "%）</b>。前者是整合包作者铺的路，"
                            + "后者是游戏自带的清单，两条链的内容与粒度都不同。"));
                    sb.append(tipLib("denominator", usedTips));
                }
            }
            if (i(d.get("chaptersTotal")) >= 10) {
                sb.append(tipLib("entry_width", usedTips));
            }
        }
        int ut = i(a.get("untouchedCount"));
        if (ut > 0) {
            sb.append(h3("一次都没碰过的模组：" + ut + " 个"));
            int maxTotal = 0;
            int noTitle = 0;
            for (Object o : arr(a.get("untouched"))) {
                Map<String, Object> m = obj(o);
                maxTotal = Math.max(maxTotal, i(obj(o).get("total")));
                if (arr(m.get("examples")).isEmpty()) {
                    noTitle++;
                }
            }
            List<List<String>> rows = new ArrayList<>();
            for (Object o : arr(a.get("untouched"))) {
                Map<String, Object> m = obj(o);
                int total = i(m.get("total"));
                List<Object> ex = arr(m.get("examples"));
                StringBuilder exs = new StringBuilder();
                for (Object x : ex) {
                    if (exs.length() > 0) {
                        exs.append("、");
                    }
                    exs.append(esc(str(x)));
                }
                rows.add(List.of("《" + esc(str(m.get("modName"))) + "》",
                        bar(maxTotal <= 0 ? 0 : 100.0 * total / maxTotal,
                                total + " 条", WARN),
                        exs.length() > 0 ? exs.toString()
                                : "<span style=\"color:" + DIM + "\">技术性隐藏</span>"));
                if (rows.size() >= MAX_UNTOUCHED_ROWS) {
                    break;
                }
            }
            if (!rows.isEmpty()) {
                sb.append(table(List.of("模组", "没碰的内容", "例（有名字的内容）"), rows));
                sb.append(cap("共 " + ut + " 个模组一次都没碰过，按「没碰的内容条数」降序，下表列出前 "
                        + rows.size() + " 个。标「技术性隐藏」的模组，其成就在游戏里也没有显示标题，"
                        + "因此没有可读的例子可列。"));
                sb.append(cap("条形长度 = 该模组没碰的内容条数 ÷ 本表最大值（" + maxTotal
                        + " 条）；它表示的是「这堆没碰的东西**有多大**」，不是「完成度」。"));
                sb.append(tipLib(ut >= 15 ? "untouched_mods_many" : "untouched_mods_few", usedTips));
                if (noTitle > 0) {
                    sb.append(tipLib("hidden_no_title", usedTips));
                }
            }
        }
        int sh = i(a.get("shallowCount"));
        int shMax = i(a.get("shallowMaxPercent"));
        if (sh > 0) {
            sb.append(h3("碰过、但只碰了一点的模组：" + sh + " 个"));
            int maxShTotal = 0;
            for (Object o : arr(a.get("shallow"))) {
                maxShTotal = Math.max(maxShTotal, i(obj(o).get("total")));
            }
            List<List<String>> rows = new ArrayList<>();
            for (Object o : arr(a.get("shallow"))) {
                Map<String, Object> m = obj(o);
                int total = i(m.get("total"));
                int done = i(m.get("done"));
                rows.add(List.of("《" + esc(str(m.get("modName"))) + "》",
                        done + " / " + total + " 条",
                        bar(maxShTotal <= 0 ? 0 : 100.0 * total / maxShTotal,
                                i(m.get("share")) + "%", ACC)));
                if (rows.size() >= MAX_SHALLOW_ROWS) {
                    break;
                }
            }
            if (!rows.isEmpty()) {
                sb.append(table(List.of("模组", "已完成", "内容总量 · 完成占比"), rows));
                sb.append(cap("口径：该模组的完成条数 ÷ 它的内容总条数 ≤ " + shMax
                        + "%，就算「只碰了一点」。本次共 " + sh + " 个模组落在这一档，按内容总量降序，"
                        + "下表列出前 " + rows.size() + " 个。第三个口径（碰得不少）没有单列成表——"
                        + "需要时用同一份内容层 JSON 复算即可。"));
                sb.append(cap("条形长度 = 该模组的内容总条数 ÷ 本表最大值（" + maxShTotal
                        + " 条）；" + "右侧文字里的百分比才是它的完成占比。两者分开看，"
                        + "才分得清「这个大但没怎么动」和「这个不大但快做完了」。"));
                sb.append(tipLib("shallow_touch", usedTips));
            }
        }
        if (!e.isEmpty()) {
            sb.append(h3("你在世界地图上自己标的点：" + i(e.get("waypointCount")) + " 个"));
            List<List<String>> rows = new ArrayList<>();
            for (Object o : arr(e.get("dimensions"))) {
                Map<String, Object> dim = obj(o);
                StringBuilder names = new StringBuilder();
                for (Object w : arr(dim.get("waypoints"))) {
                    if (names.length() > 0) {
                        names.append("、");
                    }
                    names.append(esc(str(obj(w).get("name"))));
                }
                rows.add(List.of(esc(str(dim.get("dimension"))), i(dim.get("count")) + " 个", names.toString()));
            }
            if (!rows.isEmpty()) {
                sb.append(table(List.of("维度", "数量", "你标了什么"), rows));
                sb.append(tipLib("waypoints_selfset", usedTips));
            }
        }

        sb.append(h2("c2", "2 · 卡在哪"));
        List<List<String>> rows = new ArrayList<>();
        for (Object o : arr(b.get("topGaps"))) {
            Map<String, Object> g = obj(o);
            rows.add(List.of(String.format(java.util.Locale.ROOT, "%.1f 天", d(g.get("gapDays"))),
                    "《" + esc(str(g.get("name"))) + "》", esc(str(g.get("modName"))),
                    "《" + esc(str(g.get("after"))) + "》"));
        }
        if (!rows.isEmpty()) {
            sb.append(h3("停得最久的几处推进"));
            sb.append(table(List.of("停了多久", "你终于推进的内容", "属于", "在此之前最后做的"), rows));
            sb.append(cap("口径：相邻两次「首次完成」之间的时间差；只列 ≥ " + i(b.get("minGapHours"))
                    + " 小时的停顿（本次共 " + i(b.get("gapsTotal")) + " 处，按停得最久降序，下表列出前 "
                    + rows.size() + " 个）。"));
        }
        rows = new ArrayList<>();
        for (Object o : arr(b.get("idleMods"))) {
            Map<String, Object> m = obj(o);
            rows.add(List.of("《" + esc(str(m.get("modName"))) + "》",
                    i(m.get("done")) + " / " + i(m.get("total")) + " 条",
                    String.format(java.util.Locale.ROOT, "%.1f 天前", d(m.get("daysSinceLast")))));
            if (rows.size() >= MAX_IDLE_ROWS) {
                break;
            }
        }
        if (!rows.isEmpty()) {
            sb.append(h3("只碰过一点就没再动的模组"));
            sb.append(table(List.of("模组", "已完成", "最后一次动它"), rows));
            sb.append(cap("共 " + i(b.get("idleModsTotal")) + " 个模组属于「碰过但远未做完」，"
                    + "按「最后一次动它」的时间降序，下表列出前 " + rows.size() + " 个。"));
        }
        if (idle >= 5) {
            sb.append(tipLib("idle_mods", usedTips));
        }

        sb.append(h2("c3", "3 · 你怎么打的"));
        if (!c.isEmpty()) {
            rows = new ArrayList<>();
            rows.add(List.of("死亡", "<b>" + i(c.get("deaths")) + " 次</b>"));
            StringBuilder killers = new StringBuilder();
            int killerKinds = 0;
            for (Object o : arr(c.get("playerKillers"))) {
                killerKinds++;
                if (killerKinds > MAX_KILLER_ENTRIES) {
                    continue;
                }
                Map<String, Object> k = obj(o);
                if (killers.length() > 0) {
                    killers.append("、");
                }
                killers.append(esc(str(k.get("name")))).append(" ").append(i(k.get("count"))).append(" 次");
            }
            if (killerKinds > MAX_KILLER_ENTRIES) {
                killers.append(" …（共 ").append(killerKinds).append(" 种，此处按次数降序列前 ")
                        .append(MAX_KILLER_ENTRIES).append(" 种）");
            }
            rows.add(List.of("谁杀了你", killers.toString()));
            rows.add(List.of("杀过多少种怪物", i(c.get("mobTypesKilled")) + " 种"));
            rows.add(List.of("游玩时长", String.format(java.util.Locale.ROOT, "%.1f 小时", d(c.get("playHours")))));
            rows.add(List.of("走过的路", String.format(java.util.Locale.ROOT, "%.1f 公里", d(c.get("walkKm")))));
            rows.add(List.of("广度", "合成过 " + i(c.get("craftedKinds")) + " 种物品 · 挖过 "
                    + i(c.get("minedKinds")) + " 种方块"));
            sb.append(table(List.of("项", "读数"), rows));
            List<Object> pk = arr(c.get("playerKillers"));
            int killerSum = 0;
            for (Object o : pk) {
                killerSum += i(obj(o).get("count"));
            }
            if (!pk.isEmpty() && killerSum > 0) {
                Map<String, Object> k0 = obj(pk.get(0));
                int k0c = i(k0.get("count"));
                sb.append(h3("死亡归因：谁杀了你"));
                List<List<String>> kr = new ArrayList<>();
                int shown = 0;
                for (Object o : pk) {
                    Map<String, Object> k = obj(o);
                    int cnt = i(k.get("count"));
                    shown++;
                    kr.add(List.of(esc(str(k.get("name"))), cnt + " 次",
                            n2(100.0 * cnt / killerSum) + "%",
                            bar(100.0 * cnt / killerSum, "", WARN)));
                    if (shown >= MAX_KILLER_TABLE_ROWS) {
                        break;
                    }
                }
                sb.append(table(List.of("凶手", "次数", "占「有凶手的死亡」", "分布"), kr));
                sb.append(cap("口径：占比 = 该凶手的击杀次数 ÷ 「有凶手的死亡」合计 " + killerSum
                        + " 次（按次数降序，下表列出前 " + kr.size() + " 个；本次共 "
                        + i(c.get("playerKillersTotal")) + " 种凶手记到过你的名字上）。"
                        + "两个来源各算各的：死亡总数读统计键 <code>minecraft:deaths</code>（"
                        + deaths + " 次），凶手次数读 <code>minecraft:killed_by</code>（合计 "
                        + killerSum + " 次）。差额来自没有凶手的死法：摔落、虚空、指令、玩家对玩家。"));
                int ciLo = i(c.get("topKillerShareCiLow"));
                int ciHi = i(c.get("topKillerShareCiHigh"));
                if (ciHi > 0) {
                    sb.append(cap("这 " + killerSum + " 次死亡样本下，" + Math.round(100.0 * k0c / killerSum)
                            + "% 的 95% 置信区间约 " + ciLo + "%–" + ciHi
                            + "%（Wilson 区间）。区间宽 = 样本少，不等于结论弱；它回答的是"
                            + "「再玩一阵，这个比例大致会落在哪」，不是「真实概率就该是这个数」。"));
                }
                sb.append(why("<b>" + Math.round(100.0 * k0c / killerSum) + "% 的死亡（" + k0c + " / "
                        + killerSum + "）来自同一个东西：" + esc(str(k0.get("name"))) + "。</b>"
                        + "是硬墙还是失手，取决于你想让它承担什么角色。"
                        + "（分母取「有凶手的死亡」合计 " + killerSum + " 次；"
                        + "若与死亡总数 " + deaths + " 次不一致，差额来自没有凶手的死法，"
                        + "例如摔落、虚空、指令或玩家对玩家。）"));
                if (deaths != killerSum) {
                    sb.append(cap("来源不同：死亡总数读的是统计键 <code>minecraft:deaths</code>（"
                            + deaths + " 次），凶手次数读的是 <code>minecraft:killed_by</code>（合计 "
                            + killerSum + " 次）。两者不必相等，报告不把它们凑成一致。"));
                }
                double kShare = 100.0 * k0c / killerSum;
                if (kShare >= 40) {
                    sb.append(tipLib("death_concentrated", usedTips));
                } else if (kShare < 25) {
                    sb.append(tipLib("death_spread", usedTips));
                }
                sb.append(tipLib(killerSum < 30 ? "small_sample" : "multiplicity", usedTips));
                if (d(c.get("playHours")) > 0 && deaths > 0) {
                    sb.append(cap("换个分母看同一件事：整段游玩 " + n2(d(c.get("playHours")))
                            + " 小时 / " + deaths + " 次死亡 ≈ 每 "
                            + n2(d(c.get("playHours")) / deaths) + " 小时死一次。"
                            + "这个「每小时死亡数」比「总死亡数」更能反映压力，"
                            + "因为长存档的死亡总数天然更大。"));
                    sb.append(tipLib("rate_per_hour", usedTips));
                }
            }
            List<Object> tk = arr(c.get("topKills"));
            if (!tk.isEmpty()) {
                sb.append(h3("你杀得最多的怪物"));
                int maxKill = 1;
                for (Object o : tk) {
                    maxKill = Math.max(maxKill, i(obj(o).get("count")));
                }
                List<List<String>> tr = new ArrayList<>();
                for (Object o : tk) {
                    Map<String, Object> k = obj(o);
                    int cnt = i(k.get("count"));
                    tr.add(List.of(esc(str(k.get("name"))), cnt + " 只",
                            bar(100.0 * cnt / maxKill, "", ACC2)));
                    if (tr.size() >= MAX_KILL_ROWS) {
                        break;
                    }
                }
                sb.append(table(List.of("怪物", "击杀数", "相对量"), tr));
                sb.append(cap("口径：读的是统计键 <code>minecraft:killed</code>（你亲手击杀的实体），"
                        + "本次共记录 <b>" + i(c.get("mobTypesKilled")) + " 种</b>被击杀的怪物，"
                        + "下表列出击杀数最多的前 " + tr.size() + " 种。条形长度 = 该种的击杀数 ÷ "
                        + "本表最大值（" + maxKill + " 只）。"));
                sb.append(cap("注意这不是「难度榜」：召唤物、刷怪塔、被同伴清掉的都会记在这里，"
                        + "所以它读起来更像「你的战斗镜头都花在哪了」，而不是「谁最强」。"));
                sb.append(tipLib("kills_long_tail", usedTips));
            }
        }
        sb.append(replaySection(replayJson, usedTips));

        if (!f.isEmpty()) {
            sb.append(h2("c4", "4 · 数值平衡"));
            sb.append("<p style=\"margin:.8rem 0;padding:.75rem .9rem;background:rgba(232,163,61,.08);"
                    + "border:1px solid rgba(232,163,61,.4);border-radius:6px;color:" + TXT
                    + ";font-size:.93rem;line-height:1.8\">复杂度由三个可分别解释的轴给出："
                    + "<b>depth</b>（依赖几层）· <b>kinds</b>（直接原料几种）· <b>mods</b>（递归凑料跨几个模组）；"
                    + "合成分 <code>" + esc(str(f.get("composite"))) + "</code>，"
                    + "所以三轴与合成分并列列出，便于你按自己的口径重新加权。</p>");
            sb.append(cap("配方解析：" + i(f.get("recipes")) + " 条成功 · 有产出 "
                    + i(f.get("itemsProduced")) + " 件 · 其中 " + i(f.get("unparsed"))
                    + " 条未解析 ⇒ <b>本节数值都是下界</b>。"));
            rows = new ArrayList<>();
            for (Object o : arr(f.get("topComplex"))) {
                Map<String, Object> m = obj(o);
                rows.add(List.of(esc(str(m.get("item"))), i(m.get("depth")) + " 层",
                        i(m.get("kinds")) + " 种", i(m.get("mods")) + " 个", String.valueOf(i(m.get("score")))));
                if (rows.size() >= MAX_COMPLEX_ROWS) {
                    break;
                }
            }
            if (!rows.isEmpty()) {
                sb.append(h3("最「重」的几件"));
                sb.append(table(List.of("物品", "depth", "kinds", "mods", "score"), rows));
                sb.append(cap("共有产出配方的物品里，按合成分降序；本次可排序 "
                        + i(f.get("topComplexTotal")) + " 件，下表列出前 " + rows.size() + " 件。"));
                int heaviestMods = 0;
                for (Object o : arr(f.get("topComplex"))) {
                    heaviestMods = Math.max(heaviestMods, i(obj(o).get("mods")));
                }
                sb.append(tipLib(heaviestMods >= 3 ? "power_creep" : "diminishing", usedTips));
            }
            rows = new ArrayList<>();
            for (Object o : arr(f.get("jumps"))) {
                Map<String, Object> j = obj(o);
                rows.add(List.of("+" + i(j.get("delta")), "《" + esc(str(j.get("chapter"))) + "》",
                        esc(str(j.get("from"))) + " → <b>" + esc(str(j.get("to"))) + "</b>",
                        String.valueOf(i(j.get("score")))));
                if (rows.size() >= MAX_JUMP_ROWS) {
                    break;
                }
            }
            if (!rows.isEmpty()) {
                sb.append(h3("难度跳度：任务书里最陡的台阶"));
                sb.append(table(List.of("跳幅", "章节", "从 → 到", "后者 score"), rows));
                sb.append(cap("按任务书<b>文件里的先后顺序</b>取相邻节点算落差，落差 ≥ "
                        + i(f.get("minJumpDelta")) + " 才记一处；本次共 "
                        + i(f.get("jumpsTotal")) + " 处，下表列出最陡的前 " + rows.size() + " 处。"
                        + "（只统计节点数 ≥ " + i(f.get("minChapterNodes")) + " 的章节。）"));
                sb.append(tipLib(i(b.get("gapsTotal")) >= 3 && rows.size() <= 2
                        ? "stall_long" : "stall_many", usedTips));
            }
            List<Object> spans = arr(f.get("chapterSpans"));
            if (!spans.isEmpty()) {
                sb.append(h3("章节内的复杂度跨度"));
                List<List<String>> sr = new ArrayList<>();
                int maxSpan = 1;
                for (Object o : spans) {
                    maxSpan = Math.max(maxSpan, i(obj(o).get("max")) - i(obj(o).get("min")));
                }
                for (Object o : spans) {
                    Map<String, Object> s = obj(o);
                    int lo = i(s.get("min"));
                    int hi = i(s.get("max"));
                    sr.add(List.of("《" + esc(str(s.get("chapter"))) + "》",
                            i(s.get("quests")) + " 个节点",
                            lo + " → " + hi,
                            i(s.get("max")) - i(s.get("min")) + "",
                            bar(100.0 * (hi - lo) / maxSpan, "", ACC)));
                    if (sr.size() >= MAX_SPAN_ROWS) {
                        break;
                    }
                }
                sb.append(table(List.of("章节", "章节内节点数", "最简 → 最难", "跨度", "相对跨度"), sr));
                sb.append(cap("口径：一章的跨度 = 该章配方节点的合成分最大值 − 最小值；"
                        + "节点按任务书文件里的先后顺序取。只统计节点数 ≥ "
                        + i(f.get("minChapterNodes")) + " 的章节，本次共 "
                        + i(f.get("chapterSpansTotal")) + " 章达标，按跨度降序，下表列出前 "
                        + sr.size() + " 章。条形长度 = 该章跨度 ÷ 本表最大值（" + maxSpan + "）。"));
                sb.append(cap("跨度大不等于设计坏了：一章本来就要把玩家从「刚认识」带到「用得上」，"
                        + "跨度是这条路的长度。要一起看的是跳度表——路上有没有哪一级突然抬高。"));
                sb.append(tipLib("chapter_span", usedTips));
            }
            sb.append(h3("复制度"));
            int dupN = i(f.get("duplicateOutputItems"));
            int prodN = Math.max(1, i(f.get("itemsProduced")));
            sb.append(cap(dupN + " 个物品有<b>多条</b>产出配方（占全部有产出物品的 "
                    + Math.round(100.0 * Math.min(dupN, prodN) / prodN)
                    + "%）：多条路通常是好事，但会让「配方难度」在它们身上失去意义。"
                    + "（计入复制度的物品共 " + dupN + " 件；明细列表本次采样 "
                    + i(f.get("duplicatesTotal")) + " 件，未在正文逐条列出。）"));
            if (dupN > 0) {
                sb.append(tipLib("recipe_repetition", usedTips));
            }
        }

        sb.append(h2("c5", "5 · 隐私条款"));
        sb.append("<ul style=\"color:").append(MUT).append(";padding-left:1.2rem\">"
                + "<li><b>只读本机数据</b>：存档、统计、任务书进度、你自己的地图标点、模组配方。</li>"
                + "<li><b>不出网</b>：全程本地生成，没有联网、没有上传。</li>"
                + "<li><b>可撤回</b>：报告是单个文件，删掉即彻底删除。</li>"
                + "<li><b>可复核</b>：机器可读的数据在导出目录里。</li></ul>");
        return sb.toString();
    }
}
