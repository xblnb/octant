package com.octant.fabric.v1_21_1;

import com.octant.capture.OctantHost;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OctantFabricHost implements OctantHost {

    private static final String MC_VERSION = "1.21.1";
    private static final String LOADER = "fabric";

    private final Path gameDir;
    private final String modVersion;
    private volatile Path worldDir;
    private volatile String privacyClass = "unknown";
    private volatile boolean cheatsEnabled;

    public OctantFabricHost() {
        this.gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        this.modVersion = FabricLoader.getInstance().getModContainer("octant")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public void worldDir(Path dir) {
        this.worldDir = dir == null ? null : dir.toAbsolutePath().normalize();
    }

    public void serverFacts(String privacy, boolean cheats) {
        this.privacyClass = privacy;
        this.cheatsEnabled = cheats;
    }

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
        return modVersion;
    }

    @Override
    public String gameVersion() {
        return MC_VERSION;
    }

    @Override
    public String loader() {
        return LOADER;
    }

    @Override
    public List<String> modsList() {
        List<String> out = new ArrayList<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            out.add(c.getMetadata().getId() + ":" + c.getMetadata().getVersion().getFriendlyString());
        }
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
