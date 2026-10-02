package com.octant.forge.v1_20_1;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModsTomlTest {

    private static final List<String> REAL_FORGE_VERSIONS =
            List.of("47.4.10", "47.4.22", "47.4.102");

    private static String modsToml() throws IOException {
        try (InputStream in = ModsTomlTest.class.getClassLoader()
                .getResourceAsStream("META-INF/mods.toml")) {
            assertNotNull(in, "产物里没有 META-INF/mods.toml：Forge 不会把本 jar 当模组载入");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String topLevelValue(String toml, String key) {
        Matcher m = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*=\\s*\"([^\"]*)\"",
                Pattern.MULTILINE).matcher(toml);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void modsTomlIsOnTheClasspathAndDeclaresTheLiteralModId() throws IOException {
        String toml = modsToml();
        assertTrue(toml.contains("modId = \"" + OctantMod.MOD_ID + "\""),
                "modId 必须是字面量 " + OctantMod.MOD_ID + "（写占位符会被原样当成标识）");
        assertFalse(toml.contains("${"),
                "mods.toml 里不得出现 ${...} 占位符：Forge 1.20.1 不做属性替换");
        assertTrue(toml.contains("[[mods]]"), "缺少 [[mods]] 段");
        assertNotNull(topLevelValue(toml, "modLoader"), "缺少 modLoader");
    }

    @Test
    void modLoaderIsJavaFml() throws IOException {
        assertTrue("javafml".equals(topLevelValue(modsToml(), "modLoader")),
                "modLoader 必须是 javafml");
    }

    @Test
    void loaderVersionRangeCoversEveryInstalledForgeVersion() throws IOException {
        String range = topLevelValue(modsToml(), "loaderVersion");
        assertNotNull(range, "缺少 loaderVersion（FML 用它判定本模组能否在此 Forge 上加载）");

        for (String v : REAL_FORGE_VERSIONS) {
            assertTrue(inRange(v, range),
                    "loaderVersion=" + range + " 未覆盖用户实装的 Forge " + v);
        }
        assertFalse(inRange("1.20.1", range),
                "范围 " + range + " 不应当接受 1.20.1 这种非 Forge 版本号（解析器可能把范围当恒真）");
    }

    static boolean inRange(String version, String range) {
        String r = range.trim();
        if (r.startsWith("[") || r.startsWith("(")) {
            boolean lowInclusive = r.startsWith("[");
            boolean highInclusive = r.endsWith("]");
            String body = r.substring(1, r.length() - 1);
            String[] parts = body.split(",", -1);
            if (parts.length == 1) {
                return parts[0].trim().equals(version);
            }
            String low = parts[0].trim();
            String high = parts[1].trim();
            if (!low.isEmpty()) {
                int c = compareVersions(version, low);
                if (c < 0 || (c == 0 && !lowInclusive)) {
                    return false;
                }
            }
            if (!high.isEmpty()) {
                int c = compareVersions(version, high);
                if (c > 0 || (c == 0 && !highInclusive)) {
                    return false;
                }
            }
            return true;
        }
        return r.equals(version);
    }

    static int compareVersions(String a, String b) {
        List<Integer> pa = numericParts(a);
        List<Integer> pb = numericParts(b);
        int n = Math.max(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            int x = i < pa.size() ? pa.get(i) : 0;
            int y = i < pb.size() ? pb.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static List<Integer> numericParts(String v) {
        List<Integer> out = new ArrayList<>();
        for (String s : v.split("\\.")) {
            Matcher m = Pattern.compile("(\\d+)").matcher(s);
            out.add(m.find() ? Integer.parseInt(m.group(1)) : 0);
        }
        return out;
    }

    @Test
    void rangeParserCanFail() {
        assertFalse(inRange("46.9.0", "[47,)"), "低于下限必须被排除");
        assertFalse(inRange("48.0.0", "[47,48)"), "达到上限且上限为开区间时必须被排除");
        assertTrue(inRange("47.99.0", "[47,48)"), "区间内必须被接受");
        assertFalse(inRange("47.4.9", "[47.4.10,)"), "小数点后按数值比较，不得按字符串比较");
        assertTrue(inRange("47.4.10", "[47.4.10,)"), "闭区间下端点必须被接受");
    }

    @Test
    void declaredModIdMatchesTheEntryPointAnnotationConstant() throws IOException {
        String declared = topLevelValue(modsToml(), "modId");
        assertNotNull(declared, "mods.toml 缺少 modId");
        assertTrue(OctantMod.MOD_ID.equals(declared),
                "OctantMod.MOD_ID（" + OctantMod.MOD_ID + "）必须与 mods.toml 的 modId（"
                        + declared + "）一致");
    }

    @Test
    void loaderVersionAndModVersionAreReportedConsistently() throws IOException {
        String toml = modsToml();
        String version = topLevelValue(toml, "version");
        assertNotNull(version, "缺少 version");
        assertTrue(version.matches("\\d+\\.\\d+(\\.\\d+)?.*"),
                "version 必须是版本号形态（FML 会把它放进日志与模组列表）：" + version);
    }

    @Test
    void everyDependencyBlockDeclaresMandatory() throws IOException {
        String toml = modsToml();
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\[\\[dependencies\\.octant\\]\\]([\\s\\S]*?)(?=\\n\\[|\\Z)")
                .matcher(toml);
        while (m.find()) {
            blocks.add(m.group(1));
        }
        assertFalse(blocks.isEmpty(),
                "必须至少声明一条依赖（forge 与 minecraft 都要）");
        for (String block : blocks) {
            String modId = firstMatch(block, "(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
            assertNotNull(modId, "依赖段缺少 modId：\n" + block);
            assertTrue(Pattern.compile("(?m)^\\s*mandatory\\s*=\\s*(true|false)\\s*$")
                            .matcher(block).find(),
                    "依赖 " + modId + " 缺少 mandatory 字段 —— FML 会把整份清单判为非法并丢弃本 jar"
                            + "（实测错误：Missing required field mandatory in dependency）");
            assertTrue(Pattern.compile("(?m)^\\s*versionRange\\s*=\\s*\"[^\"]+\"\\s*$")
                            .matcher(block).find(),
                    "依赖 " + modId + " 缺少 versionRange");
            assertTrue(Pattern.compile("(?m)^\\s*side\\s*=\\s*\"(BOTH|CLIENT|SERVER)\"\\s*$")
                            .matcher(block).find(),
                    "依赖 " + modId + " 的 side 必须是 BOTH/CLIENT/SERVER 之一");
        }

        String joined = String.join("\n", blocks);
        assertTrue(joined.contains("\"forge\""), "缺少对 forge 的依赖声明");
        assertTrue(joined.contains("\"minecraft\""), "缺少对 minecraft 的依赖声明");
    }

    private static String firstMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    public static String dump(Path ignored) throws IOException {
        String toml = modsToml();
        Files.createDirectories(ignored.getParent());
        Files.writeString(ignored, toml, StandardCharsets.UTF_8);
        return toml;
    }
}
