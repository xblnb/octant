package com.octant.capture;

import java.nio.file.Path;

public interface OctantHost {

    Path gameDir();

    Path worldDir();

    String modVersion();

    String gameVersion();

    String loader();

    java.util.List<String> modsList();

    String privacyClass();

    boolean cheatsEnabled();
}
