package com.octant.pipeline.kiteview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.octant.pipeline.kiteview.KiteShapeCriteria.Hit;
import com.octant.pipeline.kiteview.KiteScene.Camera;
import com.octant.pipeline.kiteview.KiteViewData.Sample;
import com.octant.pipeline.kiteview.KiteViewData.Series;

public final class KiteViewHtml {

    private KiteViewHtml() {
    }

    public static final String K_PROG = "axis.PROG.normalized";
    public static final String K_SPON = "axis.SPON.normalized";
    public static final String K_GUID = "axis.GUID.normalized";
    public static final String K_DENSITY = "v.density_per_min";

    public static final int FALLBACK_SNAPSHOTS = 3;

    public static final double SIZE = 720.0d;
    public static final double MARGIN = 46.0d;

    static final double EXT_MIN = -1.2d;
    static final double EXT_MAX = 1.6d;

    private static final String BG = "#12181d";
    private static final String AXIS_COLOR = "#9fb3c0";
    private static final String GRID_COLOR = "#5d7180";
    private static final String TEXT_COLOR = "#e8eef2";
    private static final String DIM_COLOR = "#b7c6d2";

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", Double.valueOf(v));
    }

    static String num(double v) {
        return Double.isNaN(v) ? "不可判定" : String.format(Locale.ROOT, "%.3f", Double.valueOf(v));
    }

    private static String valueSpan(long tSec, String key, double v) {
        return "<span data-t=\"" + tSec + "\" data-k=\"" + esc(key) + "\">" + num(v) + "</span>";
    }

    private static String sceneJson(Series s, List<Hit> hits, Camera cam, double[] center, double radius, double size) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"size\":").append(f(size)).append(",\"margin\":").append(f(MARGIN));
        sb.append(",\"fov\":").append(String.format(Locale.ROOT, "%.6f", Double.valueOf(KiteScene.FOV)));
        sb.append(",\"center\":[").append(f(center[0])).append(',').append(f(center[1])).append(',').append(f(center[2])).append(']');
        sb.append(",\"radius\":").append(f(radius));
        sb.append(",\"cam\":{\"az\":").append(f(cam.az())).append(",\"el\":").append(f(cam.el()))
          .append(",\"dist\":").append(f(cam.dist())).append(",\"focal\":").append(f(cam.focal())).append('}');
        sb.append(",\"default\":{\"az\":").append(f(KiteScene.DEFAULT_AZ)).append(",\"el\":").append(f(KiteScene.DEFAULT_EL))
          .append(",\"dist\":").append(f(cam.dist())).append('}');
        sb.append(",\"bg\":\"").append(BG).append("\",\"axisColor\":\"").append(AXIS_COLOR)
          .append("\",\"gridColor\":\"").append(GRID_COLOR).append("\",\"textColor\":\"").append(TEXT_COLOR).append('"');
        sb.append(",\"colorSmooth\":\"").append(KiteChannels.HEX_SMOOTH).append('"');
        sb.append(",\"colorUrgent\":\"").append(KiteChannels.HEX_URGENT).append('"');
        sb.append(",\"dashUrgent\":\"").append(KiteChannels.DASH_URGENT).append('"');
        sb.append(",\"stageOpacity\":[").append(KiteChannels.STAGE_FILL_OPACITY[0]).append(',')
          .append(KiteChannels.STAGE_FILL_OPACITY[1]).append(',').append(KiteChannels.STAGE_FILL_OPACITY[2]).append(']');

        sb.append(",\"axisDef\":[");
        String[][] axes = {{"PROG", "进度", "0,1,0"}, {"SPON", "自发性", "1,0,0"}, {"GUID", "引导性", "0,0,1"}};
        for (int i = 0; i < axes.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"axis\":\"").append(axes[i][0]).append("\",\"label\":\"").append(axes[i][1])
              .append("\",\"dir\":[").append(axes[i][2]).append("],\"ticks\":[");
            for (int k = 0; k < KiteScene.TICKS.length; k++) {
                if (k > 0) {
                    sb.append(',');
                }
                sb.append(String.format(Locale.ROOT, "%.4f", Double.valueOf(KiteScene.TICKS[k])));
            }
            sb.append("]}");
        }
        sb.append(']');

        sb.append(",\"planes\":[");
        for (int st = 0; st < KiteScene.STAGES; st++) {
            if (st > 0) {
                sb.append(',');
            }
            double p0 = st / (double) KiteScene.STAGES;
            double p1 = (st + 1) / (double) KiteScene.STAGES;
            sb.append("{\"id\":\"stage").append(st + 1).append("\",\"label\":\"").append(KiteChannels.STAGE_LABELS[st])
              .append("\",\"texture\":\"").append(KiteChannels.STAGE_TEXTURE[st])
              .append("\",\"opacity\":").append(KiteChannels.STAGE_FILL_OPACITY[st])
              .append(",\"corners\":[[").append(f(0.5d)).append(',').append(f(0.0d)).append(',').append(f(p0)).append("],")
              .append('[').append(f(-0.5d)).append(',').append(f(0.0d)).append(',').append(f(p0)).append("],")
              .append('[').append(f(-0.5d)).append(',').append(f(1.0d)).append(',').append(f(p0)).append("],")
              .append('[').append(f(0.5d)).append(',').append(f(1.0d)).append(',').append(f(p0)).append("]]")
              .append(",\"span\":[").append(f((p0 + p1) / 2.0d)).append(']')
              .append('}');
        }
        sb.append(']');
        sb.append(",\"gridLines\":").append(KiteScene.gridLinesFor(s.size()));

        double[] q = s.densityQuantiles();
        sb.append(",\"segments\":[");
        boolean firstSeg = true;
        for (int i = 0; i + 1 < s.size(); i++) {
            Sample a = s.samples().get(i);
            Sample b = s.samples().get(i + 1);
            if (Double.isNaN(a.prog()) || Double.isNaN(a.spon()) || Double.isNaN(a.guid())
                    || Double.isNaN(b.prog()) || Double.isNaN(b.spon()) || Double.isNaN(b.guid())) {
                continue;
            }
            double ut = Series.utStep(new double[] {a.prog() - b.prog(), a.spon() - b.spon(), a.guid() - b.guid()},
                    new double[] {0, 0, 0});
            String state = "undecidable";
            if (i > 0) {
                Sample p0 = s.samples().get(i - 1);
                if (!Double.isNaN(p0.prog()) && !Double.isNaN(p0.spon()) && !Double.isNaN(p0.guid())) {
                    double u = Series.utStep(new double[] {a.prog() - p0.prog(), a.spon() - p0.spon(), a.guid() - p0.guid()},
                            new double[] {b.prog() - a.prog(), b.spon() - a.spon(), b.guid() - a.guid()});
                    state = (u < KiteViewData.UT_BOUNDARY) ? "smooth" : "urgent";
                }
            }
            int bin = Series.widthBin(a.densityPerMin(), q[0], q[1]);
            if (!firstSeg) {
                sb.append(',');
            }
            firstSeg = false;
            sb.append("{\"i\":").append(i).append(",\"state\":\"").append(state).append("\",\"w\":").append(bin)
              .append(",\"a\":[").append(f(a.prog())).append(',').append(f(a.spon())).append(',').append(f(a.guid())).append("]")
              .append(",\"b\":[").append(f(b.prog())).append(',').append(f(b.spon())).append(',').append(f(b.guid())).append("]}");
        }
        sb.append(']');

        sb.append(",\"points\":[");
        boolean firstPt = true;
        for (Sample p : s.samples()) {
            if (Double.isNaN(p.prog()) || Double.isNaN(p.spon()) || Double.isNaN(p.guid())) {
                continue;
            }
            if (!firstPt) {
                sb.append(',');
            }
            firstPt = false;
            sb.append("{\"i\":").append(p.index()).append(",\"p\":[").append(f(p.prog())).append(',')
              .append(f(p.spon())).append(',').append(f(p.guid())).append("]}");
        }
        sb.append(']');

        sb.append(",\"marks\":[");
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            Sample p = s.samples().get(Math.min(h.toIndex(), s.size() - 1));
            if (Double.isNaN(p.prog()) || Double.isNaN(p.spon()) || Double.isNaN(p.guid())) {
                continue;
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"shape\":\"").append(esc(h.shape())).append("\",\"geom\":\"").append(esc(h.geometry()))
              .append("\",\"p\":[").append(f(p.prog())).append(',').append(f(p.spon())).append(',')
              .append(f(p.guid())).append("]}");
        }
        sb.append(']');

        sb.append(",\"hint\":\"拖拽旋转 · 滚轮缩放 · 右键拖动平移 · 双击复位\"");
        sb.append('}');
        return sb.toString();
    }

    private static String initialSvg(String sceneJson, Series s, List<Hit> hits, Camera cam, double[] center, double size,
                                     double radius, boolean fallback) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg id=\"").append(fallback ? "scene-static" : "scene").append("\" width=\"").append((int) size)
          .append("\" height=\"").append((int) size).append("\" role=\"img\" aria-label=\"三维轨迹：三轴 + 阶段平面 + 刻度\">\n");
        sb.append("<rect data-plot-area=\"1\" data-bg=\"").append(BG).append("\" x=\"0\" y=\"0\" width=\"")
          .append((int) size).append("\" height=\"").append((int) size).append("\" fill=\"").append(BG).append("\"/>\n");

        KiteScene.Placer placer = new KiteScene.Placer();
        placer.reserve(size - 150.0d, 56.0d, size, 56.0d + Math.max(1, hits.size()) * 16.0d + 16.0d);
        placer.reserve(0.0d, size - 30.0d, size, size);

        sb.append("<g id=\"planes\">\n");
        for (int st = 0; st < KiteScene.STAGES; st++) {
            double p0 = st / (double) KiteScene.STAGES;
            double[][] corners = {
                {EXT_MAX, EXT_MIN, p0}, {EXT_MIN, EXT_MIN, p0}, {EXT_MIN, EXT_MAX, p0}, {EXT_MAX, EXT_MAX, p0},
            };
            StringBuilder pts = new StringBuilder();
            for (double[] c : corners) {
                double[] sc = KiteScene.project(c, cam, center, size, MARGIN, radius);
                pts.append(f(sc[0])).append(',').append(f(sc[1])).append(' ');
            }
            sb.append("<polygon data-grid-plane=\"stage").append(st + 1).append("\" data-stage-texture=\"")
              .append(KiteChannels.STAGE_TEXTURE[st]).append("\" points=\"").append(pts.toString().trim())
              .append("\" fill=\"#8fa6b5\" fill-opacity=\"").append(KiteChannels.STAGE_FILL_OPACITY[st])
              .append("\" stroke=\"#7d94a4\" stroke-width=\"0.9\"/>\n");
            double gridStroke = KiteScene.gridStrokeFor(s.size());
            int gridN = KiteScene.gridLinesFor(s.size());
            for (int k = 0; k < gridN; k++) {
                double t = k / (double) (gridN - 1);
                double u0 = EXT_MIN + t * (EXT_MAX - EXT_MIN);
                double[][][] segs = {
                    {{EXT_MAX, u0, p0}, {EXT_MIN, u0, p0}},
                    {{u0, EXT_MIN, p0}, {u0, EXT_MAX, p0}},
                };
                String[] dirs = {"v", "u"};
                for (int d = 0; d < 2; d++) {
                    double[] a = KiteScene.project(segs[d][0], cam, center, size, MARGIN, radius);
                    double[] b = KiteScene.project(segs[d][1], cam, center, size, MARGIN, radius);
                    sb.append("<line data-grid=\"stage").append(st + 1).append("\" data-grid-dir=\"").append(dirs[d])
                      .append("\" x1=\"").append(f(a[0])).append("\" y1=\"").append(f(a[1]))
                      .append("\" x2=\"").append(f(b[0])).append("\" y2=\"").append(f(b[1]))
                      .append("\" stroke=\"").append(GRID_COLOR).append("\" stroke-width=\"")
                      .append(f(gridStroke)).append("\" data-grid-stroke=\"").append(f(gridStroke))
                      .append("\"/>\n");
                }
            }
            double[] lc = KiteScene.project(new double[] {0.5d, 0.5d, p0}, cam, center, size, MARGIN, radius);
            double[] lp = KiteScene.placeText(placer, lc[0], lc[1], KiteChannels.STAGE_LABELS[st], 12.0d);
            sb.append("<text data-stage-label=\"").append(KiteChannels.STAGE_LABELS[st]).append("\" x=\"").append(f(lp[0]))
              .append("\" y=\"").append(f(lp[1])).append("\" font-size=\"12\" fill=\"").append(DIM_COLOR)
              .append("\">").append(KiteChannels.STAGE_LABELS[st]).append("</text>\n");
        }
        sb.append("</g>\n");

        sb.append("<g id=\"axes\">\n");
        String[][] axes = {{"PROG", "进度", "1,0,0"}, {"SPON", "自发性", "0,1,0"}, {"GUID", "引导性", "0,0,1"}};
        for (String[] ax : axes) {
            double[] from = new double[] {0, 0, 0};
            double[] to = new double[] {0, 0, 0};
            if ("PROG".equals(ax[0])) {
                from = new double[] {0.5d, 0.0d, 0.0d};
                to = new double[] {0.5d, 1.0d, 0.0d};
            } else if ("SPON".equals(ax[0])) {
                from = new double[] {-0.5d, 0.0d, 0.0d};
                to = new double[] {0.5d, 0.0d, 0.0d};
            } else {
                from = new double[] {0.5d, 0.0d, 0.0d};
                to = new double[] {0.5d, 0.0d, 1.0d};
            }
            double[] a = KiteScene.project(from, cam, center, size, MARGIN, radius);
            double[] b = KiteScene.project(to, cam, center, size, MARGIN, radius);
            sb.append("<line data-axis=\"").append(ax[0]).append("\" x1=\"").append(f(a[0])).append("\" y1=\"").append(f(a[1]))
              .append("\" x2=\"").append(f(b[0])).append("\" y2=\"").append(f(b[1]))
              .append("\" stroke=\"").append(AXIS_COLOR).append("\" stroke-width=\"2.4\"/>\n");
            for (double tv : KiteScene.TICKS) {
                double[] wp = new double[3];
                if ("PROG".equals(ax[0])) {
                    wp = new double[] {0.5d, tv, 0.0d};
                } else if ("SPON".equals(ax[0])) {
                    wp = new double[] {tv, 0.0d, 0.0d};
                } else {
                    wp = new double[] {0.5d, 0.0d, tv};
                }
                double[] sc = KiteScene.project(wp, cam, center, size, MARGIN, radius);
                sb.append("<circle data-axis-tick=\"").append(ax[0]).append(':').append(f(tv))
                  .append("\" cx=\"").append(f(sc[0])).append("\" cy=\"").append(f(sc[1]))
                  .append("\" r=\"3.4\" fill=\"").append(AXIS_COLOR).append("\"/>\n");
                String tickText = String.format(Locale.ROOT, "%.2f", Double.valueOf(tv));
                double[] tp = KiteScene.placeText(placer, sc[0], sc[1], tickText, 10.0d);
                sb.append("<text data-tick=\"").append(ax[0]).append(':').append(tickText)
                  .append("\" x=\"").append(f(tp[0])).append("\" y=\"").append(f(tp[1]))
                  .append("\" font-size=\"10\" fill=\"").append(DIM_COLOR).append("\">").append(tickText).append("</text>\n");
            }
            double[] lp = KiteScene.placeText(placer, b[0], b[1], ax[1], 13.0d);
            sb.append("<text data-axis-label=\"").append(ax[1]).append("\" x=\"").append(f(lp[0]))
              .append("\" y=\"").append(f(lp[1])).append("\" font-size=\"13\" fill=\"").append(TEXT_COLOR)
              .append("\" font-weight=\"600\">").append(ax[1]).append("</text>\n");
        }
        sb.append("</g>\n");

        sb.append("<g id=\"series\">\n");
        double[] q = s.densityQuantiles();
        int drawn = 0;
        for (int i = 0; i + 1 < s.size(); i++) {
            Sample p0 = s.samples().get(i);
            Sample p1 = s.samples().get(i + 1);
            if (Double.isNaN(p0.prog()) || Double.isNaN(p0.spon()) || Double.isNaN(p0.guid())
                    || Double.isNaN(p1.prog()) || Double.isNaN(p1.spon()) || Double.isNaN(p1.guid())) {
                continue;
            }
            String state = "smooth";
            if (i > 0) {
                Sample pm = s.samples().get(i - 1);
                if (!Double.isNaN(pm.prog()) && !Double.isNaN(pm.spon()) && !Double.isNaN(pm.guid())) {
                    double u = Series.utStep(new double[] {p0.prog() - pm.prog(), p0.spon() - pm.spon(), p0.guid() - pm.guid()},
                            new double[] {p1.prog() - p0.prog(), p1.spon() - p0.spon(), p1.guid() - p0.guid()});
                    state = (u < KiteViewData.UT_BOUNDARY) ? "smooth" : "urgent";
                }
            }
            String color = "urgent".equals(state) ? KiteChannels.HEX_URGENT : KiteChannels.HEX_SMOOTH;
            String dash = "urgent".equals(state) ? KiteChannels.DASH_URGENT : KiteChannels.DASH_SMOOTH;
            int bin = Series.widthBin(p0.densityPerMin(), q[0], q[1]);
            double[] a = KiteScene.project(p0.vector(), cam, center, size, MARGIN, radius);
            double[] b = KiteScene.project(p1.vector(), cam, center, size, MARGIN, radius);
            sb.append("<line data-seg=\"").append(i).append("\" data-ut=\"").append(state)
              .append("\" x1=\"").append(f(a[0])).append("\" y1=\"").append(f(a[1]))
              .append("\" x2=\"").append(f(b[0])).append("\" y2=\"").append(f(b[1]))
              .append("\" stroke=\"").append(color).append("\" stroke-width=\"").append(bin)
              .append("\" stroke-dasharray=\"").append(dash).append("\"/>\n");
            drawn++;
        }
        for (Sample p : s.samples()) {
            if (Double.isNaN(p.prog()) || Double.isNaN(p.spon()) || Double.isNaN(p.guid())) {
                continue;
            }
            double[] sc = KiteScene.project(p.vector(), cam, center, size, MARGIN, radius);
            sb.append("<circle data-point=\"").append(p.index()).append("\" cx=\"").append(f(sc[0]))
              .append("\" cy=\"").append(f(sc[1])).append("\" r=\"3.2\" fill=\"").append(TEXT_COLOR).append("\"/>\n");
        }
        sb.append("</g>\n");
        sb.append("<!-- drawn_segments=").append(drawn).append(" -->\n");

        sb.append("<g id=\"marks\">\n");
        int markRow = 0;
        for (Hit h : hits) {
            Sample p = s.samples().get(Math.min(h.toIndex(), s.size() - 1));
            if (Double.isNaN(p.prog()) || Double.isNaN(p.spon()) || Double.isNaN(p.guid())) {
                continue;
            }
            double[] sc = KiteScene.project(p.vector(), cam, center, size, MARGIN, radius);
            String label = "形态：" + h.shape();
            double lx = size - 10.0d - KiteScene.textWidth(label, 11.0d);
            double ly = 64.0d + markRow * 16.0d;
            int guard = 0;
            while (placer.collides(lx, ly, label, 11.0d) && guard < 200) {
                markRow++;
                ly = 64.0d + markRow * 16.0d;
                guard++;
            }
            placer.take(lx, ly, label, 11.0d);
            markRow++;
            double[] lp = new double[] {lx, ly};
            sb.append("<g data-shape-marker=\"").append(esc(h.shape())).append("\">")
              .append("<circle cx=\"").append(f(sc[0])).append("\" cy=\"").append(f(sc[1]))
              .append("\" r=\"7\" fill=\"none\" stroke=\"").append(TEXT_COLOR).append("\" stroke-width=\"2.4\"/>")
              .append("<line data-leader=\"1\" x1=\"").append(f(sc[0])).append("\" y1=\"").append(f(sc[1]))
              .append("\" x2=\"").append(f(lp[0])).append("\" y2=\"").append(f(lp[1] + 3))
              .append("\" stroke=\"").append(DIM_COLOR).append("\" stroke-width=\"0.7\"/>")
              .append("<text data-label=\"").append(esc(h.shape())).append("\" x=\"").append(f(lp[0]))
              .append("\" y=\"").append(f(lp[1])).append("\" font-size=\"11\" fill=\"").append(TEXT_COLOR)
              .append("\">").append(esc(label)).append("</text></g>\n");
        }
        sb.append("</g>\n");

        String hint = "拖拽旋转 · 滚轮缩放 · 右键拖动平移 · 双击复位";
        double[] hpos = KiteScene.placeText(placer, MARGIN, size - 14.0d, hint, 12.0d);
        sb.append("<text data-hint=\"1\" x=\"").append(f(hpos[0])).append("\" y=\"").append(f(hpos[1]))
          .append("\" font-size=\"12\" fill=\"").append(DIM_COLOR).append("\">").append(hint).append("</text>\n");
        sb.append("</svg>\n");
        return sb.toString();
    }

    public static String verdictSection(KiteViewUpstream.Info up, boolean withCamera) {
        StringBuilder sb = new StringBuilder();
        String thrOutcome = "MISSING";
        String collOutcome = "MISSING";
        String powOutcome = "MISSING";
        for (KiteViewUpstream.Reading rr : up.readings()) {
            String id = (rr.id() == null) ? "" : rr.id().toUpperCase(Locale.ROOT);
            if ("OR-1".equals(id)) {
                thrOutcome = rr.outcome();
            } else if ("AX-P".equals(id)) {
                collOutcome = rr.outcome();
            } else if ("OR-PW".equals(id) || id.contains("POW")) {
                powOutcome = rr.outcome();
            }
        }
        boolean powImplemented = !"MISSING".equals(powOutcome);
        String powNote = powImplemented
                ? ("上游功效读数 = " + powOutcome + "（`OR-PW`：三轴各自 **互异取值数 ≥ 4 且 方差 > 0.001**；"
                   + "逐轴三个数字与方差见下方读数行）")
                : ("**功效档未判定：门槛已批准但未实现**（`KITE_POWER_MIN_LEVELS` / "
                   + "`KITE_POWER_MIN_N` / `KITE_POWER_MIN_PER_LEVEL` 在 `pipeline/src` 全树 0 命中）");
        boolean anyUnd = false;
        for (KiteViewUpstream.Reading rr : up.readings()) {
            if ("UNVERIFIED".equals(rr.outcome()) || "MISSING".equals(rr.outcome())) {
                anyUnd = true;
            }
        }
        boolean tiersAllPass = "PASS".equals(thrOutcome) && "PASS".equals(collOutcome)
                && powImplemented && "PASS".equals(powOutcome);
        StringBuilder cross = new StringBuilder();
        StringBuilder missing = new StringBuilder();
        StringBuilder unver = new StringBuilder();
        for (KiteViewUpstream.Reading rr : up.readings()) {
            String rid = (rr.id() == null) ? "" : rr.id().toUpperCase(Locale.ROOT);
            boolean isGate = "OR-1".equals(rid) || "OR-PW".equals(rid) || "AX-P".equals(rid);
            if (isGate && "FAIL".equals(rr.outcome())) {
                if (cross.length() > 0) {
                    cross.append("、");
                }
                cross.append(rr.id());
            } else if ("MISSING".equals(rr.outcome())) {
                if (missing.length() > 0) {
                    missing.append("、");
                }
                missing.append(rr.id());
            } else if ("UNVERIFIED".equals(rr.outcome())) {
                if (unver.length() > 0) {
                    unver.append("、");
                }
                unver.append(rr.id());
            }
        }
        boolean anyCross = cross.length() > 0;
        boolean anyMissing = missing.length() > 0;
        boolean anyUnver = unver.length() > 0;
        String eff = anyCross ? "FAIL" : ((anyMissing || anyUnver || !tiersAllPass) ? "不可判定" : "PASS");
        sb.append("<section id=\"verdict\" data-verdict=\"").append(esc(eff))
          .append("\" data-tier-threshold=\"").append(esc(thrOutcome))
          .append("\" data-tier-collinearity=\"").append(esc(collOutcome))
          .append("\" data-tier-power=\"").append(esc(powOutcome)).append("\">\n");
        sb.append("<h2>本页裁决</h2>\n");
        boolean anyUndecided = false;
        for (KiteViewUpstream.Reading rr : up.readings()) {
            if ("UNVERIFIED".equals(rr.outcome()) || "MISSING".equals(rr.outcome())) {
                anyUndecided = true;
            }
        }
        boolean pass = "PASS".equals(up.orthVerdict()) && !anyUndecided;
        StringBuilder undecided = new StringBuilder();
        for (KiteViewUpstream.Reading r : up.readings()) {
            if ("UNVERIFIED".equals(r.outcome()) || "MISSING".equals(r.outcome())) {
                if (undecided.length() > 0) {
                    undecided.append("、");
                }
                undecided.append(r.id());
            }
        }
        String tiers = "阈值门 OR-1 = " + thrOutcome + "；共线性门 AX-P = " + collOutcome
                + "（**任一轴不是另两轴的线性组合**，不是功效门）；功效门 = " + powOutcome;
        String sentence;
        if ("PASS".equals(eff)) {
            sentence = "本页裁决 = PASS（" + tiers + "）：三档全过 ⇒ 形状可据以判读（仍受被抑制轴影响）。";
        } else if ("FAIL".equals(eff)) {
            sentence = "本页裁决 = FAIL（" + tiers + "）：**真越线**——" + cross + " 实测不达标 ⇒ "
                    + "三轴正交区分度的前提**未成立**；"
                    + (anyMissing ? "另有读数**缺失**（" + missing + "）⇒ 那部分记**不可判定**；" : "")
                    + (anyUnver ? "另有读数**未判定**（" + unver + "）⇒ 那部分记**不可判定**；" : "")
                    + "**不据三维形状**给出结论，图形只作数据定位用。";
        } else {
            sentence = "本页裁决 = **不可判定**（" + tiers + "）："
                    + (anyMissing ? "读数**缺失**（" + missing + "）⇒ **不可判定**（缺哪条已点名）；" : "")
                    + (anyUnver ? "读数**未判定**（" + unver + "）⇒ **不可判定**；" : "")
                    + (!powImplemented ? powNote + "；" : "")
                    + "没有任何门实测越线，但前提也**未具备** ⇒ **不据三维形状**给出结论，图形只作数据定位用。";
        }
        sb.append("<p data-verdict-sentence=\"1\">").append(esc(sentence)).append("</p>\n");
        sb.append("<ul>\n");
        sb.append("<li data-orth-threshold>阈值 = 上游常量 KITE_ORTHO_MAX = ")
          .append(Double.isNaN(up.orthoMax()) ? "（未取到）"
                  : String.format(Locale.ROOT, "%.3f", Double.valueOf(up.orthoMax()))).append("</li>\n");
        for (KiteViewUpstream.Reading r : up.readings()) {
            sb.append("<li data-orth-reading=\"").append(esc(r.id())).append("\">").append(esc(r.id())).append(" = ")
              .append(esc(r.outcome())).append("：").append(esc(r.detail())).append("</li>\n");
        }
        sb.append("<li data-upstream-sha16=\"").append(esc(up.sha16()))
          .append("\" data-source-layer=\"").append(esc(up.path())).append("\">上游产物 sha16 = ")
          .append(esc(up.sha16().isEmpty() ? "（未取到 ⇒ 本页所有数字**角色=未验证器材**）" : up.sha16()))
          .append("；来源层 = ").append(esc(up.path())).append("</li>\n");
        sb.append("<li data-n=\"").append(up.pointCount()).append("\">N（样本量）= ")
          .append(up.pointCount()).append("（可用 ").append(up.usableCount()).append("）</li>\n");
        sb.append("<li data-power-tier=\"").append(esc(powOutcome)).append("\">功效档 = ")
          .append(esc(powOutcome)).append("：").append(esc(powNote)).append("</li>\n");
        sb.append("<li data-collinearity-tier=\"").append(esc(collOutcome))
          .append("\">共线性档（`AX-P`）= ").append(esc(collOutcome)).append("</li>\n");
        sb.append("<li data-threshold-tier=\"").append(esc(thrOutcome))
          .append("\">阈值档（`OR-1`）= ").append(esc(thrOutcome)).append("</li>\n");
        for (KiteViewUpstream.Reading r : up.readings()) {
            List<Double> vals = KiteViewUpstream.rho2Of(r.detail());
            for (int vi = 0; vi < vals.size(); vi++) {
                sb.append("<li data-rho2=\"1\" data-role=\"现值\" data-reading=\"")
                  .append(esc(r.id())).append("\" data-source-layer=\"").append(esc(up.path()))
                  .append("\" data-upstream-sha16=\"").append(esc(up.sha16())).append("\">")
                  .append("ρ²（第 ").append(vi + 1).append(" 对，读数 `").append(esc(r.id())).append("`）= ")
                  .append(String.format(Locale.ROOT, "%.16g", vals.get(vi)))
                  .append("（**角色 = 现值**；**来源层 = ").append(esc(up.path()))
                  .append("**；**上游指纹 = ").append(esc(up.sha16())).append("**）</li>\n");
            }
        }
        sb.append("<li data-rho2-history=\"1\">**历史记录口径**：本页只印**现值**（角色/来源层/上游指纹见上）。"
                + "若与他处记录的同一 ρ² 不一致（例如曾记 `ρ²(SPON,GUID) = 0.023998` 而非现值），"
                + "说明**数据或算法已变** ⇒ 引用前必须按「角色 + 来源层 + 上游 sha16」三者对齐（Z-9）。</li>\n");
        sb.append("<li data-source-anchor=\"").append(esc(up.path())).append("\">原始读数来源：")
          .append(esc(up.path())).append(up.sha16().isEmpty() ? "" : "（sha16 = " + esc(up.sha16()) + "）").append("</li>\n");
        if (withCamera) {
            sb.append("<li data-camera-static=\"1\">视角（静态）：默认斜视 —— 方位角 = ")
              .append(String.format(Locale.ROOT, "%.4f", Double.valueOf(KiteScene.DEFAULT_AZ)))
              .append(" rad，仰角 = ").append(String.format(Locale.ROOT, "%.4f", Double.valueOf(KiteScene.DEFAULT_EL)))
              .append(" rad；三张快照各自按自身数据取景（取景充实系数 ")
              .append(String.format(Locale.ROOT, "%.2f", Double.valueOf(KiteScene.FIT_FILL))).append("）。</li>\n");
        }
        sb.append("</ul>\n</section>\n");
        return sb.toString();
    }

    private static String observationTable(Series s) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"unit\":\"t_seconds\",\"points\":[");
        for (int i = 0; i < s.size(); i++) {
            Sample p = s.samples().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"i\":").append(p.index()).append(",\"t\":").append(p.tSec()).append(",\"obs\":[");
            appendObs(sb, K_PROG, p.prog(), p.sourceFile(), p.sourceLine(), true);
            appendObs(sb, K_SPON, p.spon(), p.sourceFile(), p.sourceLine(), false);
            appendObs(sb, K_GUID, p.guid(), p.sourceFile(), p.sourceLine(), false);
            appendObs(sb, K_DENSITY, p.densityPerMin(), p.sourceFile(), p.sourceLine(), false);
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendObs(StringBuilder sb, String key, double v, String file, int line, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append("{\"k\":\"").append(esc(key)).append("\",\"v\":");
        sb.append(Double.isNaN(v) ? "null" : String.format(Locale.ROOT, "%.10f", Double.valueOf(v)));
        sb.append(",\"src\":\"").append(esc(file)).append("\",\"line\":").append(line).append('}');
    }

    public static int drawableSegments(Series s) {
        int n = 0;
        for (int i = 0; i + 1 < s.size(); i++) {
            double[] a = s.samples().get(i).vector();
            double[] b = s.samples().get(i + 1).vector();
            if (!Double.isNaN(a[0]) && !Double.isNaN(a[1]) && !Double.isNaN(a[2])
                    && !Double.isNaN(b[0]) && !Double.isNaN(b[1]) && !Double.isNaN(b[2])) {
                n++;
            }
        }
        return n;
    }

    public static String render(Series s, List<Hit> hits, String dataSourceNote) {
        return render(s, hits, KiteViewUpstream.Info.unavailable("（未指定）", dataSourceNote), dataSourceNote);
    }

    public static String render(Series s, List<Hit> hits, KiteViewUpstream.Info up, String dataSourceNote) {
        double[] center = KiteScene.bboxCenter(s);
        double radius = KiteScene.bboxRadius(s, center);
        Camera cam = Camera.defaultFor(radius);
        String scene = sceneJson(s, hits, cam, center, radius, SIZE);
        String svg = initialSvg(scene, s, hits, cam, center, SIZE, radius, false);

        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n");
        h.append("<title>行为风筝：三轴轨迹与形态标记（可交互三维）</title>\n");
        h.append("<style>\n");
        h.append("body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;margin:0;padding:1.5rem;background:#0d1216;color:#e8eef2}\n");
        h.append("h1{font-size:1.35rem;margin:0 0 .4rem 0}h2{font-size:1.05rem;margin:1.4rem 0 .5rem 0}\n");
        h.append(".row{display:flex;gap:1.2rem;flex-wrap:wrap;align-items:flex-start}\n");
        h.append(".panel{background:#12181d;border-radius:6px;padding:.8rem 1rem;min-width:16rem}\n");
        h.append("table{border-collapse:collapse;font-size:.86rem}td,th{padding:.18rem .5rem;border-bottom:1px solid #263039;text-align:left}\n");
        h.append(".legend span{display:inline-block;margin-right:1rem}\n");
        h.append(".sw{display:inline-block;width:2.2rem;height:0;border-top-width:2px;border-top-style:solid;vertical-align:middle}\n");
        h.append("input[type=range]{width:100%}\n.marker{fill:none;stroke:#e8eef2;stroke-width:1.5}\n");
        h.append("button{background:#1c252c;color:#e8eef2;border:1px solid #31404b;border-radius:4px;padding:.25rem .6rem;cursor:pointer}\n");
        h.append("</style>\n</head>\n<body>\n");

        h.append("<h1>行为风筝：三轴轨迹与形态标记</h1>\n");
        h.append("<section id=\"meta\">\n<h2>图元与通道声明</h2>\n<ul data-channel-meta>\n");
        h.append("<li data-map=\"color\">").append(esc(KiteChannels.mappingDeclarations().get(0))).append("</li>\n");
        h.append("<li data-map=\"width\">").append(esc(KiteChannels.mappingDeclarations().get(1))).append("</li>\n");
        h.append("<li data-color-role=\"").append(KiteChannels.COLOR_ROLE).append("\">colorRole = ")
         .append(KiteChannels.COLOR_ROLE).append("（STATE.* 不占 S1–S4 槽位）</li>\n");
        h.append("<li data-valence-neutral=\"true\">valenceNeutral = true（红只表示运动学急促，不含价值判断）</li>\n");
        h.append("<li data-ruling-source=\"HIG-COL-20.2/20.3/20.5/20.6\">"
                + "通道分工的来源（三层）：① 子条 `HIG-COL-20.2`/`20.3`（色相承 `U_t`）、`20.5`（线型为运动学第二载体）、"
                + "`20.6`（三通道分工）；② 设计裁定；③ **已确认「A」**（阶段用平面 + 标签 + 透明度/纹理）。"
                + "推翻需用户再说一句。</li>\n");
        h.append("<li data-axes>轴朝向：进度 PROG = 竖直；自发性 SPON = 水平；引导性 GUID = 深度</li>\n");
        h.append("<li data-projection>透视投影 + 轨道相机：p' = R_x(−el)·R_y(az)·(p−center)；s = focal·K/(dist−p'.z)</li>\n");
        h.append("<li data-camera-default=\"").append(esc(cam.describe())).append("\">默认相机（导出时确定）：")
         .append(esc(cam.describe())).append("</li>\n");
        int drawable = drawableSegments(s);
        h.append("<li data-drawable-segments=\"").append(drawable).append("\">三轴同时可用且相邻的采样点对 = ")
         .append(drawable).append(" 段</li>\n");
        if (drawable == 0) {
            h.append("<li data-drawable-notice>上游数据下**没有任何可画段**（三轴同时可用的采样点要么只有 1 个、"
                    + "要么互不相邻）⇒ 轨迹线为空 ⇒ 本页只呈现三轴、网格与刻度</li>\n");
        }
        h.append("<li data-source=\"").append(s.fixture() ? "fixture" : (up.available() ? "upstream" : "unavailable"))
         .append("\">数据来源：").append(esc(dataSourceNote)).append("</li>\n");
        if (up.available()) {
            h.append("<li data-upstream=\"").append(esc(up.path())).append("\">上游产物：").append(esc(up.path()))
             .append("（sha16 = ").append(esc(up.sha16())).append("，").append(up.bytes()).append(" 字节；schema = ")
             .append(esc(up.schema())).append("）</li>\n");
            h.append("<li data-upstream-series>序列 kind = ").append(esc(up.seriesKind())).append("；样点 = ")
             .append(up.pointCount()).append("；三轴齐备的样点 = ").append(up.usableCount()).append("</li>\n");
            for (String ax : up.axisMeta()) {
                h.append("<li data-axis-def>").append(esc(ax)).append("</li>\n");
            }
            if (!up.firstSuppression().isEmpty()) {
                h.append("<li data-suppression>抑制说明（原文）：").append(esc(up.firstSuppression())).append("</li>\n");
            }
        } else {
            h.append("<li data-upstream-unavailable>上游产物不可用：").append(esc(up.reason())).append("</li>\n");
        }
        if (s.fixture()) {
            h.append("<li data-fixture-note>本页数据为**夹具**（合成轨迹，用于验证判据），不是玩家真实数据</li>\n");
        }
        h.append("</ul>\n<h2>三轴正交区分度</h2>\n<ul data-orth-gate=\"").append(esc(up.orthVerdict())).append("\">\n");
        h.append("<li data-orth-threshold>阈值 = 上游常量 KITE_ORTHO_MAX = ")
         .append(Double.isNaN(up.orthoMax()) ? "（未取到）" : num(up.orthoMax())).append("</li>\n");
        for (KiteViewUpstream.Reading r : up.readings()) {
            h.append("<li data-orth-reading=\"").append(esc(r.id())).append("\">").append(esc(r.id())).append(" = ")
             .append(esc(r.outcome())).append("：").append(esc(r.detail())).append("</li>\n");
        }
        h.append("</ul>\n</section>\n");
        h.append(verdictSection(up, false));

        h.append("<section id=\"legend\">\n<h2>图例（运动学两档）</h2>\n<p class=\"legend\">\n");
        h.append("<span data-legend-item=\"").append(KiteChannels.TOKEN_SMOOTH).append("\">")
         .append("<i class=\"sw\" style=\"border-top-color:").append(KiteChannels.HEX_SMOOTH)
         .append(";border-top-style:solid\"></i> ").append(KiteChannels.LEGEND_SMOOTH)
         .append("（U_t &lt; 1.0，实线）</span>\n");
        h.append("<span data-legend-item=\"").append(KiteChannels.TOKEN_URGENT).append("\">")
         .append("<i class=\"sw\" style=\"border-top-color:").append(KiteChannels.HEX_URGENT)
         .append(";border-top-style:dashed\"></i> ").append(KiteChannels.LEGEND_URGENT)
         .append("（U_t ≥ 1.0，虚线）</span>\n");
        h.append("<span data-legend-item=\"stage\">阶段：平面 + 标签 + 填充透明度/纹理（不用色相、不用线型、不用线宽）</span>\n");
        h.append("</p>\n</section>\n");

        h.append("<section id=\"view\">\n<h2>三维视图（可拖拽旋转 / 滚轮缩放 / 双击复位）</h2>\n");
        h.append("<div class=\"row\">\n");
        h.append(svg);
        h.append("<div class=\"panel\">\n");
        h.append("<div><button id=\"reset\" type=\"button\">复位视角</button> ")
         .append("<button id=\"viewA\" type=\"button\">默认斜视</button> ")
         .append("<button id=\"viewB\" type=\"button\">俯视（进度×自发性）</button></div>\n");
        h.append("<div style=\"margin-top:.6rem\"><label for=\"tl\">时间轴（拖动查看该时刻的位置与密度）</label>"
                + "<input id=\"tl\" type=\"range\" min=\"0\" max=\"").append(Math.max(0, s.size() - 1))
         .append("\" value=\"0\" step=\"1\"></div>\n");
        h.append("<div style=\"margin-top:.6rem\"><button id=\"tg\" type=\"button\">形态标记：显示</button></div>\n");
        h.append("<div id=\"readout\" style=\"margin-top:.6rem\">\n<table>\n<tr><th>量</th><th>值</th></tr>\n");
        Sample p0 = s.samples().isEmpty() ? null : s.samples().get(0);
        if (p0 != null) {
            h.append("<tr><td>时刻 t（秒）</td><td>").append(p0.tSec()).append("</td></tr>\n");
            h.append("<tr><td>进度 PROG</td><td>").append(valueSpan(p0.tSec(), K_PROG, p0.prog())).append("</td></tr>\n");
            h.append("<tr><td>自发性 SPON</td><td>").append(valueSpan(p0.tSec(), K_SPON, p0.spon())).append("</td></tr>\n");
            h.append("<tr><td>引导性 GUID</td><td>").append(valueSpan(p0.tSec(), K_GUID, p0.guid())).append("</td></tr>\n");
            h.append("<tr><td>密度 v（次/分）</td><td>").append(valueSpan(p0.tSec(), K_DENSITY, p0.densityPerMin())).append("</td></tr>\n");
        }
        h.append("</table>\n</div>\n");
        h.append("<div id=\"drill\" hidden>\n<h2>该时刻的原始数据</h2>\n<table id=\"drill-table\">\n");
        h.append("<tr><th>键</th><th>值</th><th>来源</th></tr>\n");
        if (p0 != null) {
            h.append("<tr><td>").append(esc(K_PROG)).append("</td><td>").append(num(p0.prog()))
             .append("</td><td>").append(esc(p0.sourceFile())).append(":").append(p0.sourceLine()).append("</td></tr>\n");
            h.append("<tr><td>").append(esc(K_SPON)).append("</td><td>").append(num(p0.spon()))
             .append("</td><td>").append(esc(p0.sourceFile())).append(":").append(p0.sourceLine()).append("</td></tr>\n");
            h.append("<tr><td>").append(esc(K_GUID)).append("</td><td>").append(num(p0.guid()))
             .append("</td><td>").append(esc(p0.sourceFile())).append(":").append(p0.sourceLine()).append("</td></tr>\n");
            h.append("<tr><td>").append(esc(K_DENSITY)).append("</td><td>").append(num(p0.densityPerMin()))
             .append("</td><td>").append(esc(p0.sourceFile())).append(":").append(p0.sourceLine()).append("</td></tr>\n");
        }
        h.append("</table>\n</div>\n");
        h.append("<div style=\"margin-top:.6rem\"><span data-camera-readout id=\"camout\">")
         .append(esc(cam.describe())).append("</span></div>\n");
        h.append("</div>\n</div>\n</section>\n");

        h.append("<section id=\"shapes\">\n<h2>形态标记（几何判据的命中清单）</h2>\n");
        h.append("<table data-shape-table>\n<tr><th>形态</th><th>窗口</th><th>步区间</th><th>几何量</th></tr>\n");
        if (hits.isEmpty()) {
            h.append("<tr><td colspan=\"4\" data-shape-hits=\"0\">本次未命中任何形态判据</td></tr>\n");
        }
        for (Hit hit : hits) {
            h.append("<tr data-shape=\"").append(esc(hit.shape())).append("\"><td>").append(esc(hit.label()))
             .append("</td><td>").append(esc(hit.window())).append("</td><td>步 ").append(hit.fromIndex())
             .append("–").append(hit.toIndex()).append("</td><td>").append(esc(hit.geometry())).append("</td></tr>\n");
        }
        StringBuilder tally = new StringBuilder("四类形态（本页的判定范围）：");
        for (String nm : KiteShapeCriteria.SHAPE_NAMES) {
            int c = 0;
            for (Hit hh : hits) {
                if (hh.shape().equals(nm)) {
                    c++;
                }
            }
            tally.append(" ").append(nm).append("=").append(c).append(" 次；");
        }
        tally.append("本页只把命中的形态画在图上，未命中的三类不出现在图上。");
        h.append("<p data-shape-tally=\"1\">").append(esc(tally.toString())).append("</p>\n");
        h.append("</table>\n</section>\n");

        h.append("<section id=\"fallback\">\n<noscript>\n<h2>无脚本回退（静态快照）</h2>\n");
        h.append("<p>以下为同一数据在 ").append(FALLBACK_SNAPSHOTS)
         .append(" 个时间点的静态三维快照（同一条渲染器产出，含三轴刻度数字、阶段网格与形态标记）；"
                 + "没有脚本时也能读到内容。</p>\n");
        for (int k = 1; k <= FALLBACK_SNAPSHOTS; k++) {
            h.append(fallbackSvg(s, hits, cam, center, radius, k));
        }
        h.append("</noscript>\n</section>\n");
        h.append("<pre data-machine hidden id=\"data\">").append(observationTable(s)).append("</pre>\n");
        h.append("<pre data-scene hidden id=\"scene-model\">").append(scene).append("</pre>\n");
        h.append(script(s, hits, drawable));
        h.append("</body>\n</html>\n");
        return h.toString();
    }

    private static String fallbackSvg(Series s, List<Hit> hits, Camera cam, double[] center, double radius, int k) {
        if (s.samples().isEmpty()) {
            return "<figure data-fallback-snapshot=\"na\"><p data-na=\"no-samples\">"
                    + "[不适用] 无样点 ⇒ 本静态快照无法判定（既非通过、非不通过）</p></figure>\n";
        }
        int upto = (int) Math.round((double) s.size() * k / (double) (FALLBACK_SNAPSHOTS + 1));
        upto = Math.max(1, Math.min(s.size(), upto));
        List<Sample> sub = new ArrayList<>(s.samples().subList(0, Math.max(0, Math.min(upto, s.samples().size()))));
        Series part = new Series(s.label(), s.fixture(), sub);
        List<Hit> subHits = new ArrayList<>();
        for (Hit h : hits) {
            if (h.toIndex() < upto) {
                subHits.add(h);
            }
        }
        double[] c2 = KiteScene.bboxCenter(part);
        double r2 = KiteScene.bboxRadius(part, c2);
        Camera cam2 = Camera.defaultFor(r2);
        int marks = 0;
        for (Sample q : part.samples()) {
            if (!Double.isNaN(q.prog()) && !Double.isNaN(q.spon()) && !Double.isNaN(q.guid())) {
                marks++;
            }
        }
        return "<figure data-fallback-snapshot=\"" + k + "\">\n" + "<figcaption>静态快照 " + k + "：取前 " + upto
                + " 个采样点，其中**三轴齐备、可落成标记的 " + marks + " 个**（其余因轴被抑制或与该采样不相邻 ⇒ 不落标记）"
                + "；本图按自身数据取景</figcaption>\n"
                + initialSvg("{}", part, subHits, cam2, c2, 420.0d, r2, true) + "</figure>\n";
    }

    private static String script(Series s, List<Hit> hits, int drawable) {
        StringBuilder sb = new StringBuilder();
        sb.append("<script>\n(function(){\n");
        sb.append("var M=JSON.parse(document.getElementById('scene-model').textContent);\n");
        sb.append("var DATA=JSON.parse(document.getElementById('data').textContent);\n");
        sb.append("var svg=document.getElementById('scene');\n");
        sb.append("var cam={az:M.cam.az,el:M.cam.el,dist:M.cam.dist,focal:M.cam.focal,panX:0,panY:0};\n");
        sb.append("var DEF={az:M.default.az,el:M.default.el,dist:M.default.dist};\n");
        sb.append("var SIZE=M.size,MG=M.margin,C=M.center;\n");
        sb.append("function project(p){var x=p[0]-C[0],y=p[1]-C[1],z=p[2]-C[2];\n");
        sb.append(" var ca=Math.cos(cam.az),sa=Math.sin(cam.az);var x1=ca*x+sa*z,z1=-sa*x+ca*z;\n");
        sb.append(" var ce=Math.cos(cam.el),se=Math.sin(cam.el);var y2=ce*y-se*z1,z2=se*y+ce*z1;\n");
        sb.append(" var d=Math.max(1e-6,cam.dist-z2);var s=cam.focal*(SIZE-2*MG)/2/d;\n");
        sb.append(" return [SIZE/2+cam.panX+s*x1,SIZE/2+cam.panY-s*y2];}\n");
        sb.append("function setLine(el,a,b){el.setAttribute('x1',a[0].toFixed(2));el.setAttribute('y1',a[1].toFixed(2));"
                + "el.setAttribute('x2',b[0].toFixed(2));el.setAttribute('y2',b[1].toFixed(2));}\n");
        sb.append("function axisPts(axis,off,t){var o=(off===undefined)?0:off;\n");
        sb.append(" if(axis==='PROG'){return [0.5+o,t,0];} if(axis==='SPON'){return [t,o,0];} return [0.5,o,t];}\n");
        sb.append("function draw(){\n");
        sb.append(" var planes=document.querySelectorAll('#planes polygon[data-grid-plane]');\n");
        sb.append(" for(var i=0;i<planes.length;i++){var st=i/3;var c4=[[1.6,-1.2,0],[-1.2,-1.2,0],[-1.2,1.6,0],[1.6,1.6,0]];\n");
        sb.append("  var pts=[];for(var k2=0;k2<4;k2++){var q=project([c4[k2][0],c4[k2][1],st]);pts.push(q[0].toFixed(2)+','+q[1].toFixed(2));}\n");
        sb.append("  planes[i].setAttribute('points',pts.join(' '));}\n");
        sb.append(" var gl=document.querySelectorAll('#planes line[data-grid]');\n");
        sb.append(" var glPerPlane=Math.round(gl.length/3);\\n");
        sb.append(" for(var g=0;g<gl.length;g++){var el=gl[g],plane=el.getAttribute('data-grid'),"
                + "st2=(parseInt(plane.replace('stage',''),10)-1)/3;\\n");
        sb.append("  var idx=Array.prototype.indexOf.call(gl,g)%glPerPlane,t=idx/(glPerPlane-1);\n");
        sb.append("  var u0=-1.2+t*2.8;\n  if(el.getAttribute('data-grid-dir')==='v'){setLine(el,project([1.6,u0,st2]),project([-1.2,u0,st2]));}\n");
        sb.append("  else{setLine(el,project([u0,-1.2,st2]),project([u0,1.6,st2]));}}\n");
        sb.append(" var sl=document.querySelectorAll('#planes text[data-stage-label]');\n");
        sb.append(" for(var q2=0;q2<sl.length;q2++){var sp=project([0.5,0.5,q2/3]);sl[q2].setAttribute('x',(sp[0]+8).toFixed(2));sl[q2].setAttribute('y',(sp[1]-8).toFixed(2));}\n");
        sb.append(" var ax=document.querySelectorAll('#axes line[data-axis]');\n");
        sb.append(" for(var a=0;a<ax.length;a++){var nm=ax[a].getAttribute('data-axis');"
                + "setLine(ax[a],project(axisPts(nm,-0.5,0)),project(axisPts(nm,0.5,1)));}\n");
        sb.append(" var tk=document.querySelectorAll('#axes circle[data-axis-tick]');\n");
        sb.append(" for(var t3=0;t3<tk.length;t3++){var sp2=(tk[t3].getAttribute('data-axis-tick')||'').split(':');\n");
        sb.append("  var tv=parseFloat(sp2[1]);var pp=project(axisPts(sp2[0],sp2[0]==='PROG'?0.5:0,tv));\n");
        sb.append("  tk[t3].setAttribute('cx',pp[0].toFixed(2));tk[t3].setAttribute('cy',pp[1].toFixed(2));}\n");
        sb.append(" var tt=document.querySelectorAll('#axes text[data-tick]');\n");
        sb.append(" for(var t4=0;t4<tt.length;t4++){var sp3=(tt[t4].getAttribute('data-tick')||'').split(':');\n");
        sb.append("  var tv2=parseFloat(sp3[1]);var pq=project(axisPts(sp3[0],sp3[0]==='PROG'?0.5:0,tv2));\n");
        sb.append("  tt[t4].setAttribute('x',(pq[0]+10).toFixed(2));tt[t4].setAttribute('y',(pq[1]-8-t4%2*10).toFixed(2));}\n");
        sb.append(" var al=document.querySelectorAll('#axes text[data-axis-label]');\n");
        sb.append(" var names=['PROG','SPON','GUID'];\n");
        sb.append(" for(var a2=0;a2<al.length;a2++){var pa=project(axisPts(names[a2],0.5,1));al[a2].setAttribute('x',(pa[0]+12).toFixed(2));al[a2].setAttribute('y',(pa[1]-10).toFixed(2));}\n");
        sb.append(" var sg=document.querySelectorAll('#series line[data-seg]');\n");
        sb.append(" for(var s2=0;s2<sg.length;s2++){var i2=parseInt(sg[s2].getAttribute('data-seg'),10);\n");
        sb.append("  var A=DATA.points[i2],B=DATA.points[i2+1];\n");
        sb.append("  if(!A||!B){continue;}var oa=A.obs,ob=B.obs;\n");
        sb.append("  if(oa[0].v===null||oa[1].v===null||oa[2].v===null||ob[0].v===null||ob[1].v===null||ob[2].v===null){continue;}\n");
        sb.append("  setLine(sg[s2],project([oa[0].v,oa[1].v,oa[2].v]),project([ob[0].v,ob[1].v,ob[2].v]));}\n");
        sb.append(" var cp=document.querySelectorAll('#series circle[data-point]');\n");
        sb.append(" for(var c2=0;c2<cp.length;c2++){var o=cp[c2].getAttribute('data-point');var P=DATA.points[o];\n");
        sb.append("  if(!P){continue;}var po=P.obs;if(po[0].v===null||po[1].v===null||po[2].v===null){cp[c2].setAttribute('r','0');continue;}\n");
        sb.append("  var qq=project([po[0].v,po[1].v,po[2].v]);cp[c2].setAttribute('cx',qq[0].toFixed(2));cp[c2].setAttribute('cy',qq[1].toFixed(2));}\n");
        sb.append(" var mk=document.querySelectorAll('#marks g[data-shape-marker]');\n");
        sb.append(" var boxes=[];\n");
        sb.append(" for(var m2=0;m2<mk.length;m2++){var tx=mk[m2].querySelector('text');var ci=mk[m2].querySelector('circle');\n");
        sb.append("  var lid=mk[m2].querySelector('line');\n");
        sb.append("  var target=null;for(var d2=0;d2<M.marks.length;d2++){if(M.marks[d2].shape===mk[m2].getAttribute('data-shape-marker')){target=M.marks[d2].p;break;}}\n");
        sb.append("  if(!target){continue;}var mp=project(target);\n");
        sb.append("  ci.setAttribute('cx',mp[0].toFixed(2));ci.setAttribute('cy',mp[1].toFixed(2));\n");
        sb.append("  var lx=SIZE-10-tx.textContent.length*11,ly=64+m2*16;var chosen=[lx,ly-9,lx+tx.textContent.length*11,ly+3];\n");
        sb.append("  for(var o2=0;o2<offs.length;o2++){var X=mp[0]+offs[o2][0],Y=mp[1]+offs[o2][1];var w=tx.textContent.length*11,b=[X,Y-9,X+w,Y+3];var bad=false;\n");
        sb.append("   for(var b2=0;b2<boxes.length;b2++){var Bx=boxes[b2];if(Math.min(b[2],Bx[2])-Math.max(b[0],Bx[0])>0&&Math.min(b[3],Bx[3])-Math.max(b[1],Bx[1])>0){bad=true;break;}}\n");
        sb.append("   if(!bad){chosen=b;break;}}\n");
        sb.append("  if(!chosen){var X2=mp[0]+12,Y2=mp[1]+14+boxes.length*14;chosen=[X2,Y2-9,X2+tx.textContent.length*11,Y2+3];}\n");
        sb.append("  boxes.push(chosen);tx.setAttribute('x',chosen[0].toFixed(2));tx.setAttribute('y',(chosen[3]-3).toFixed(2));\n");
        sb.append("  lid.setAttribute('x1',mp[0].toFixed(2));lid.setAttribute('y1',mp[1].toFixed(2));lid.setAttribute('x2',chosen[0].toFixed(2));lid.setAttribute('y2',chosen[3].toFixed(2));}\n");
        sb.append(" var out=document.getElementById('camout');\n");
        sb.append(" if(out){out.textContent='方位角='+cam.az.toFixed(3)+' rad 仰角='+cam.el.toFixed(3)+' rad 距离='+cam.dist.toFixed(3)+' 缩放='+(DEF.dist/cam.dist).toFixed(3)+'× 平移=('+cam.panX.toFixed(0)+','+cam.panY.toFixed(0)+')';}\n");
        sb.append("}\n");
        sb.append("var pending=false;\nfunction schedule(){if(pending){return;}pending=true;requestAnimationFrame(function(){pending=false;draw();});}\n");
        sb.append("var dragging=false,lastX=0,lastY=0,panning=false;\n");
        sb.append("svg.addEventListener('pointerdown',function(e){dragging=true;panning=(e.button===2||e.shiftKey);lastX=e.clientX;lastY=e.clientY;svg.setPointerCapture(e.pointerId);e.preventDefault();});\n");
        sb.append("svg.addEventListener('pointermove',function(e){if(!dragging){return;}var dx=e.clientX-lastX,dy=e.clientY-lastY;lastX=e.clientX;lastY=e.clientY;\n");
        sb.append(" if(panning){cam.panX+=dx;cam.panY+=dy;}else{cam.az+=dx*0.010;cam.el=Math.max(-1.45,Math.min(1.45,cam.el+dy*0.010));}schedule();});\n");
        sb.append("svg.addEventListener('pointerup',function(e){dragging=false;svg.releasePointerCapture&&svg.releasePointerCapture(e.pointerId);});\n");
        sb.append("svg.addEventListener('pointercancel',function(){dragging=false;});\n");
        sb.append("svg.addEventListener('wheel',function(e){e.preventDefault();var k=Math.exp(e.deltaY*0.0015);cam.dist=Math.max(M.radius*0.6,Math.min(M.radius*40,cam.dist*k));schedule();},{passive:false});\n");
        sb.append("svg.addEventListener('dblclick',function(){reset();});\nfunction reset(){cam.az=DEF.az;cam.el=DEF.el;cam.dist=DEF.dist;cam.panX=0;cam.panY=0;schedule();}\n");
        sb.append("var rb=document.getElementById('reset');if(rb){rb.addEventListener('click',reset);}\n");
        sb.append("var va=document.getElementById('viewA');if(va){va.addEventListener('click',function(){cam.az=DEF.az;cam.el=DEF.el;schedule();});}\n");
        sb.append("var vb=document.getElementById('viewB');if(vb){vb.addEventListener('click',function(){cam.az=0;cam.el=1.30;schedule();});}\n");
        sb.append("var cur=0,showMarks=true;\n");
        sb.append("var tl=document.getElementById('tl');if(tl){tl.addEventListener('input',function(e){cur=Number(e.target.value);readout();});}\n");
        sb.append("var KEYS=['t','").append(K_PROG).append("','").append(K_SPON).append("','").append(K_GUID).append("','").append(K_DENSITY).append("'];\n");
        sb.append("function readout(){var p=DATA.points[cur];if(!p){return;}var rows=[['时刻 t（秒）',p.t,true],['进度 PROG',p.obs[0].v,false],['自发性 SPON',p.obs[1].v,false],['引导性 GUID',p.obs[2].v,false],['密度 v（次/分）',p.obs[3].v,false]];\n");
        sb.append(" var tb=document.querySelector('#readout table');var html='<tr><th>量</th><th>值</th></tr>';\n");
        sb.append(" for(var i=0;i<rows.length;i++){var v=rows[i][1];var txt=(v===null)?'不可判定':(rows[i][2]?String(v):Number(v).toFixed(3));\n");
        sb.append("  var cell=rows[i][2]?txt:'<span data-t=\"'+p.t+'\" data-k=\"'+KEYS[i]+'\">'+txt+'</span>';html+='<tr><td>'+rows[i][0]+'</td><td>'+cell+'</td></tr>';}\n");
        sb.append(" tb.innerHTML=html;\n");
        sb.append(" var dt=document.getElementById('drill-table');var h2='<tr><th>键</th><th>值</th><th>来源</th></tr>';\n");
        sb.append(" for(var k3=0;k3<p.obs.length;k3++){var o3=p.obs[k3];h2+='<tr><td>'+o3.k+'</td><td>'+((o3.v===null)?'不可判定':Number(o3.v).toFixed(3))+'</td><td>'+o3.src+':'+o3.line+'</td></tr>';}\n");
        sb.append(" if(dt){dt.innerHTML=h2;}}\n");
        sb.append("svg.addEventListener('click',function(e){var r=svg.getBoundingClientRect();var mx=e.clientX-r.left,my=e.clientY-r.top;var best=-1,bd=1e9;\n");
        sb.append(" var cp2=document.querySelectorAll('#series circle[data-point]');\n");
        sb.append(" for(var i4=0;i4<cp2.length;i4++){var X4=parseFloat(cp2[i4].getAttribute('cx')),Y4=parseFloat(cp2[i4].getAttribute('cy'));\n");
        sb.append("  var dx4=X4-mx,dy4=Y4-my,d4=dx4*dx4+dy4*dy4;if(d4<bd){bd=d4;best=Number(cp2[i4].getAttribute('data-point'));}}\n");
        sb.append(" if(best>=0){cur=DATA.points.findIndex(function(z){return z.i===best;});if(tl){tl.value=String(cur);}var dr=document.getElementById('drill');if(dr){dr.hidden=false;}readout();}});\n");
        sb.append("var tg=document.getElementById('tg');if(tg){tg.addEventListener('click',function(){showMarks=!showMarks;var g=document.getElementById('marks');if(g){g.hidden=!showMarks;}this.textContent='形态标记：'+(showMarks?'显示':'隐藏');});}\n");
        sb.append("var tr=document.getElementById('traceok');\n");
        sb.append("function traceOk(){var sp=document.querySelectorAll('#readout span[data-t][data-k]');var ok=true,i,j,m;\n");
        sb.append(" for(i=0;i<sp.length;i++){var t8=Number(sp[i].getAttribute('data-t')),k8=sp[i].getAttribute('data-k');var found=false;\n");
        sb.append("  for(j=0;j<DATA.points.length;j++){if(DATA.points[j].t!==t8){continue;}var ob2=DATA.points[j].obs;\n");
        sb.append("   for(m=0;m<ob2.length;m++){if(ob2[m].k===k8&&Number(ob2[m].v).toFixed(3)===sp[i].textContent){found=true;}}}}\n");
        sb.append("  if(!found){ok=false;}}\n");
        sb.append(" document.body.setAttribute('data-trace-ok',ok?'1':'0');return ok;}\n");
        sb.append("draw();readout();traceOk();\n");
        sb.append("window.__kite={cam:cam,draw:draw,project:project,drawableSegments:").append(drawable).append("};\n");
        sb.append("})();\n</script>\n");
        return sb.toString();
    }

    public static List<String> dashValues(String html) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("stroke-dasharray=\"([^\"]*)\"").matcher(html);
        while (m.find()) {
            out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }
}
