package com.octant.pipeline.saveimport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LeakScanMain {

    private LeakScanMain() {
    }

    public static List<String> scanDirectory(Path dir, List<String> sensitive) throws IOException {
        StringBuilder all = new StringBuilder();
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isRegularFile(p)) {
                        all.append(Files.readString(p));
                    }
                }
            }
        }
        return SaveReport.scanForLeaks(all.toString(), sensitive);
    }

    public static void main(String[] args) {
        Path dir = null;
        List<String> expects = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--dir".equals(args[i]) && i + 1 < args.length) {
                dir = Path.of(args[++i]);
            } else if ("--expect-leak".equals(args[i]) && i + 1 < args.length) {
                expects.add(args[++i]);
            }
        }
        if (dir == null) {
            System.out.println("[leakscan] 缺少 --dir");
            System.exit(3);
        }
        try {
            List<String> hits = scanDirectory(dir, expects);
            System.out.println("[leakscan] 目录 = " + dir);
            System.out.println("[leakscan] 敏感串 = " + expects.size() + " 条");
            System.out.println("[leakscan] 命中 = " + hits.size() + " 项");
            for (String h : hits) {
                System.out.println("    ! " + h);
            }
            System.exit(hits.isEmpty() ? 0 : 2);
        } catch (IOException ex) {
            System.out.println("[leakscan] 失败：" + ex);
            System.exit(3);
        }
    }
}
