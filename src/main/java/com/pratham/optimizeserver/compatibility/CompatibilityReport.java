package com.pratham.optimizeserver.compatibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Summarizes platform identification, capabilities, warnings, and fallback decisions.
 */
public final class CompatibilityReport {

    private final ServerPlatform platform;
    private final MinecraftVersion minecraftVersion;
    private final JavaRuntimeInfo javaRuntime;
    private final PlatformCapabilities capabilities;
    private final boolean conservativeSafeModeRequired;
    private final List<String> warnings;
    private final List<String> activeFallbacks;

    public CompatibilityReport(ServerPlatform platform,
                               MinecraftVersion minecraftVersion,
                               JavaRuntimeInfo javaRuntime,
                               PlatformCapabilities capabilities,
                               boolean conservativeSafeModeRequired,
                               List<String> warnings,
                               List<String> activeFallbacks) {
        this.platform = platform;
        this.minecraftVersion = minecraftVersion;
        this.javaRuntime = javaRuntime;
        this.capabilities = capabilities;
        this.conservativeSafeModeRequired = conservativeSafeModeRequired;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        this.activeFallbacks = Collections.unmodifiableList(new ArrayList<String>(activeFallbacks));
    }

    public ServerPlatform getPlatform() {
        return platform;
    }

    public MinecraftVersion getMinecraftVersion() {
        return minecraftVersion;
    }

    public JavaRuntimeInfo getJavaRuntime() {
        return javaRuntime;
    }

    public PlatformCapabilities getCapabilities() {
        return capabilities;
    }

    public boolean isConservativeSafeModeRequired() {
        return conservativeSafeModeRequired;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<String> getActiveFallbacks() {
        return activeFallbacks;
    }
}
