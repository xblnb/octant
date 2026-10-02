package com.octant.neoforge.v1_21_1;

import com.octant.capture.OctantHost;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OctantNeoForgeHost implements OctantHost {

    private static final String LOADER = "neoforge";
    private final String gameVersion;
    private final String modVersion;
    private Path worldDir;
    private String privacyClass = "unknown";
    private boolean cheatsEnabled;

    public OctantNeoForgeHost(String modVersion, String gameVersion) {
        this.modVersion = modVersion;
        this.gameVersion = gameVersion;
    }

    public void worldDir(Path dir) {
        this.worldDir = dir;
    }

    public void serverFacts(String privacy, boolean cheats) {
        this.privacyClass = privacy;
        this.cheatsEnabled = cheats;
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path worldDir() {
        if (worldDir != null) {
            return worldDir;
        }
        var srv = ServerLifecycleHooks.getCurrentServer();
        return srv == null ? null : srv.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
    }

    @Override
    public String modVersion() {
        return modVersion;
    }

    @Override
    public String gameVersion() {
        return gameVersion;
    }

    @Override
    public String loader() {
        return LOADER;
    }

    @Override
    public List<String> modsList() {
        List<String> out = new ArrayList<>();
        ModList.get().getMods().forEach(info ->
                out.add(info.getModId() + ":" + info.getVersion().toString()));
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    @Override
    public String privacyClass() {
        return privacyClass;
    }

    @Override
    public boolean cheatsEnabled() {
        return cheatsEnabled;
    }
}
