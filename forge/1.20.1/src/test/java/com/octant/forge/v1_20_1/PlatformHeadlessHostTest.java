package com.octant.forge.v1_20_1;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformHeadlessHostTest {

    @Test
    void startupSelfCheckHostWritesNothingIntoTheGameDirectory() {
        OctantMod.HeadlessModHost h = new OctantMod.HeadlessModHost();
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        assertTrue(h.worldDir().startsWith(tmp),
                "自检的 worldDir 必须落在系统临时目录：" + h.worldDir());
        assertFalse(h.worldDir().startsWith(h.gameDir()),
                "自检不得把目录写进游戏目录（只读、自愿、本地）：" + h.worldDir());
        assertFalse(h.cheatsEnabled(), "自检宿主不得声称开了作弊");
    }
}
