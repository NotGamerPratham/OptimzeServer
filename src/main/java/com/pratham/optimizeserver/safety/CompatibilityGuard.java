package com.pratham.optimizeserver.safety;

import com.pratham.optimizeserver.compatibility.CompatibilityReport;
import com.pratham.optimizeserver.compatibility.MinecraftVersion;
import com.pratham.optimizeserver.compatibility.ServerPlatform;

import java.util.EnumSet;
import java.util.Set;

/**
 * Verifies that platform, Minecraft version, and Java runtime requirements are met before enabling a component.
 */
public final class CompatibilityGuard {

    private final CompatibilityReport compatibilityReport;

    public CompatibilityGuard(CompatibilityReport compatibilityReport) {
        this.compatibilityReport = compatibilityReport;
    }

    public boolean isSupported(Set<ServerPlatform> allowedPlatforms,
                               MinecraftVersion minMinecraftVersion,
                               int minJavaFeatureVersion,
                               boolean allowOnRegionThreaded) {
        if (compatibilityReport == null) {
            return false;
        }
        ServerPlatform activePlatform = compatibilityReport.getPlatform();
        if (allowedPlatforms != null && !allowedPlatforms.isEmpty() && !allowedPlatforms.contains(activePlatform)) {
            return false;
        }
        if (!allowOnRegionThreaded && activePlatform.isRegionThreaded()) {
            return false;
        }
        if (minMinecraftVersion != null && compatibilityReport.getMinecraftVersion().compareTo(minMinecraftVersion) < 0) {
            return false;
        }
        if (compatibilityReport.getJavaRuntime().getFeatureVersion() < minJavaFeatureVersion) {
            return false;
        }
        return true;
    }

    public static Set<ServerPlatform> allKnownAndCompatiblePlatforms() {
        return EnumSet.allOf(ServerPlatform.class);
    }

    public CompatibilityReport getCompatibilityReport() {
        return compatibilityReport;
    }
}

