package com.octant.forge.v1_20_1;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import com.octant.capture.ModSelfCheck;

public final class ExportAutotestRunner {

    private ExportAutotestRunner() {
    }

    public static void main(String[] args) throws Exception {
        Path gameDir = Path.of(args.length > 0 ? args[0] : ".")
                .toAbsolutePath().normalize();
        System.out.println("[autotest] JVM     = " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("[autotest] gameDir = " + gameDir);
        System.out.println("[autotest] mod class 已加载 = "
                + OctantMod.class.getProtectionDomain().getCodeSource().getLocation());

        Map<String, String> files = OctantMod.runExportAutotest(
                gameDir, ModSelfCheck.syntheticEvents());

        System.out.println("[autotest] === 产物清单（键=文件名，值=sha256 前 16 位）===");
        Map<String, String> sorted = new TreeMap<>(files);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            System.out.println("[autotest]   " + e.getKey() + "  " + e.getValue());
        }
        Path exportRoot = com.octant.common.privacy.OctantPaths.dataDir(gameDir).resolve("exports");
        System.out.println("[autotest] 落盘目录存在 = " + Files.isDirectory(exportRoot)
                + "  (" + exportRoot + ")");
        if (Files.isDirectory(exportRoot)) {
            try (var walk = Files.walk(exportRoot)) {
                for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                    System.out.println(String.format("[autotest]   文件 %-34s %9d B  sha256_16=%s",
                            exportRoot.relativize(p), Files.size(p),
                            sha16(Files.readAllBytes(p))));
                }
            }
        }
        if (sorted.isEmpty() || sorted.containsKey("__not_run__")) {
            System.out.println("[autotest] 结果：未导出（" + sorted + "）");
            System.exit(3);
        }
        if (sorted.containsKey("__failed__") || sorted.containsKey("__exception__")) {
            System.out.println("[autotest] 结果：失败（" + sorted + "）");
            System.exit(1);
        }
        System.out.println("[autotest] 结果：成功，产出 " + sorted.size() + " 个文件");
        System.exit(0);
    }

    private static String sha16(byte[] b) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(b);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(String.format("%02x", d[i]));
        }
        return sb.toString();
    }

    static String marker() {
        return new String("ExportAutotestRunner".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
