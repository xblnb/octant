package com.octant.pipeline.kiteview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.octant.pipeline.kiteview.KiteViewData.Sample;
import com.octant.pipeline.kiteview.KiteViewData.Series;

public final class KiteViewTsv {

    private KiteViewTsv() {
    }

    public static final String HEADER = "t_sec\tprog\tspon\tguid\tdensity_per_min";

    public static String write(Series s) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 夹具轨迹（合成数据；用于验证形态判据，不是玩家真实数据）\n");
        sb.append("# 由 com.octant.pipeline.kiteview 生成；列口径见下一行\n");
        sb.append(HEADER).append('\n');
        for (Sample p : s.samples()) {
            sb.append(p.tSec()).append('\t')
              .append(cell(p.prog())).append('\t')
              .append(cell(p.spon())).append('\t')
              .append(cell(p.guid())).append('\t')
              .append(cell(p.densityPerMin())).append('\n');
        }
        return sb.toString();
    }

    private static String cell(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.6f", Double.valueOf(v));
    }

    public static Series read(String text, String sourceFile, String label, boolean fixture) {
        List<Sample> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            int lineNo = i + 1;
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals(HEADER)) {
                continue;
            }
            String[] c = raw.split("\t", -1);
            if (c.length < 5) {
                continue;
            }
            if (c[0].contains(".") && Double.parseDouble(c[0].trim()) != Math.floor(Double.parseDouble(c[0].trim()))) {
                System.out.println("WARN: 第 " + lineNo + " 行 `t_sec` 非整数（" + c[0].trim()
                        + "）⇒ 已按整数秒取整；**这会让「断线」判据失真**，建议改用整数秒。");
            }
            out.add(new Sample(out.size(), parseLong(c[0]), parseDouble(c[1]), parseDouble(c[2]),
                    parseDouble(c[3]), parseDouble(c[4]), sourceFile, lineNo));
        }
        return new Series(label, fixture, out);
    }

    private static long parseLong(String s) {
        String v = s.trim();
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(v);
                System.out.println("WARN: `t_sec` 非整数字面量（" + v + "）⇒ 已按 " + (long) d
                        + " 秒取值；**旧实现会把它静默当成 0，只废掉「断线」一条**。");
                return (long) d;
            } catch (NumberFormatException e2) {
                System.out.println("WARN: `t_sec` 无法解析（" + v + "）⇒ 记 0 秒（该行的时间信息丢失）");
                return 0L;
            }
        }
    }

    private static double parseDouble(String s) {
        String t = s.trim();
        if (t.isEmpty() || "不可判定".equals(t)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
