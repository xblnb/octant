package com.octant.pipeline.report.html;

import com.octant.pipeline.analysis.ContextReplay;
import com.octant.pipeline.export.AnalysisJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReplayPreview {

    private ReplayPreview() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法：ReplayPreview <events.anonymized.jsonl>"
                    + " [content-insights.json] [--out <out.html>]");
            return;
        }
        Path events = Path.of(args[0]);
        String contentJson = null;
        Path out = null;
        for (int i = 1; i < args.length; i++) {
            if ("--out".equals(args[i]) && i + 1 < args.length) {
                out = Path.of(args[++i]);
            } else if (contentJson == null) {
                contentJson = Files.readString(Path.of(args[i]), StandardCharsets.UTF_8);
            }
        }

        String replayJson = com.octant.pipeline.json.JsonWriter.pretty(
                AnalysisJson.replay(ContextReplay.replay(ContextReplay.readJsonl(events))));
        String base = contentJson == null ? "{\"A_untouched\":{\"untouchedCount\":0}}" : contentJson;
        String html = ContentSections.render(base, -1, -1, replayJson);
        if (html == null) {
            System.out.println("FAIL 渲染返回 null（内容层解析失败？）");
            System.exit(2);
        }
        String page = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<title>Octant 离线预览</title>"
                + "<style>body{background:#111;color:#f5f5f7;font-family:sans-serif;"
                + "max-width:1100px;margin:2rem auto;padding:0 1rem;line-height:1.7}"
                + "a{color:#70c8e8}</style></head><body>\n" + html + "\n</body></html>\n";
        if (out != null) {
            Files.writeString(out, page, StandardCharsets.UTF_8);
            System.out.println("已写出 " + out.toAbsolutePath() + "（" + page.length() + " 字符）");
        } else {
            System.out.println(page);
        }
        System.out.println("── " + ContextReplay.describe(
                ContextReplay.replay(ContextReplay.readJsonl(events))) + " ──");
    }
}
