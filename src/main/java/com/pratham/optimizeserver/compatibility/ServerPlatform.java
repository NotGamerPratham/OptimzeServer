package com.pratham.optimizeserver.compatibility;

import java.util.Locale;

/**
 * Identifies the underlying Minecraft server implementation and its execution model.
 */
public enum ServerPlatform {

    FOLIA("Folia", true, true, true),
    GALE("Gale", true, false, true),
    LEAVES("Leaves", true, false, true),
    PURPUR("Purpur", true, false, true),
    AIRPLANE("Airplane", true, false, true),
    PUFFERFISH("Pufferfish", true, false, true),
    PAPER("Paper", true, false, true),
    SPIGOT("Spigot", false, false, true),
    CRAFTBUKKIT("CraftBukkit", false, false, true),
    UNKNOWN_BUKKIT("Unknown Bukkit-Compatible", false, false, false);

    private final String displayName;
    private final boolean paperCompatible;
    private final boolean regionThreaded;
    private final boolean knownPlatform;

    ServerPlatform(String displayName, boolean paperCompatible, boolean regionThreaded, boolean knownPlatform) {
        this.displayName = displayName;
        this.paperCompatible = paperCompatible;
        this.regionThreaded = regionThreaded;
        this.knownPlatform = knownPlatform;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isPaperCompatible() {
        return paperCompatible;
    }

    public boolean isRegionThreaded() {
        return regionThreaded;
    }

    public boolean isKnownPlatform() {
        return knownPlatform;
    }

    /**
     * Detects the server platform from server name, version string, and runtime class availability.
     */
    public static ServerPlatform detect(String serverName, String serverVersion, ClassLoader classLoader) {
        String combined = ((serverName != null ? serverName : "") + " " + (serverVersion != null ? serverVersion : ""))
                .toLowerCase(Locale.ROOT);

        if (combined.contains("folia") || isClassPresent(classLoader, "io.papermc.paper.threadedregions.RegionizedServer")) {
            return FOLIA;
        }
        if (combined.contains("gale") || isClassPresent(classLoader, "org.galemc.gale.configuration.GaleGlobalConfiguration")) {
            return GALE;
        }
        if (combined.contains("leaves")
                || isClassPresent(classLoader, "org.leavesmc.leaves.LeavesConfig")
                || isClassPresent(classLoader, "top.leavesmc.leaves.LeavesConfig")) {
            return LEAVES;
        }
        if (combined.contains("purpur")
                || isClassPresent(classLoader, "org.purpurmc.purpur.PurpurConfig")
                || isClassPresent(classLoader, "net.pl3x.purpur.PurpurConfig")) {
            return PURPUR;
        }
        if (combined.contains("airplane") || isClassPresent(classLoader, "gg.airplane.AirplaneConfig")) {
            return AIRPLANE;
        }
        if (combined.contains("pufferfish") || isClassPresent(classLoader, "gg.pufferfish.pufferfish.PufferfishConfig")) {
            return PUFFERFISH;
        }
        if (combined.contains("paper")
                || isClassPresent(classLoader, "io.papermc.paper.configuration.GlobalConfiguration")
                || isClassPresent(classLoader, "com.destroystokyo.paper.PaperConfig")) {
            return PAPER;
        }
        if (combined.contains("spigot") || isClassPresent(classLoader, "org.spigotmc.SpigotConfig")) {
            return SPIGOT;
        }
        if (combined.contains("craftbukkit") || combined.contains("bukkit")) {
            return CRAFTBUKKIT;
        }
        return UNKNOWN_BUKKIT;
    }

    private static boolean isClassPresent(ClassLoader loader, String className) {
        if (loader == null) {
            return false;
        }
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
