package com.octant.capture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModSelfCheckHtmlPathTest {

    private static OctantHost fakeHost(Path gameDir, Path worldDir) {
        return new OctantHost() {
            @Override
            public Path gameDir() {
                return gameDir;
            }

            @Override
            public Path worldDir() {
                return worldDir;
            }

            @Override
            public String modVersion() {
                return "0.1.0-test";
            }

            @Override
            public String gameVersion() {
                return "1.20.1";
            }

            @Override
            public String loader() {
                return "forge";
            }

            @Override
            public List<String> modsList() {
                return List.of("minecraft:1.20.1", "octant:0.1.0-test");
            }

            @Override
            public String privacyClass() {
                return "singleplayer";
            }

            @Override
            public boolean cheatsEnabled() {
                return false;
            }
        };
    }

    @Test
    @DisplayName("自检验的是产品路径（HtmlReport），且给出可失败的结构读数")
    void selfCheckRunsTheHtmlProductPath(@TempDir Path tmp) {
        Path gameDir = tmp.resolve("game");
        Path worldDir = gameDir.resolve("saves").resolve("w");

        ModSelfCheck.Result r = ModSelfCheck.run(fakeHost(gameDir, worldDir));

        for (String line : r.lines()) {
            System.out.println("[selftest] " + line);
        }

        assertTrue(r.ok(), "自检必须通过；失败原因见上面 [selftest] 开头的 FAIL 行");
        String joined = String.join("\n", r.lines());
        assertTrue(joined.contains("HtmlReport"), "自检必须报告 HtmlReport 路径的读数（而不是 PDF）");
        assertTrue(joined.contains("字形"), "自检必须报告字形数（证明字体资产真的被用上）");
        assertTrue(joined.contains("图有交代"), "自检必须对图给出交代（真图数或原因码计数）");
    }
}
