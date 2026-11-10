package com.pratham.optimizeserver.compatibility;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Inspects the server implementation, Minecraft version, JVM capabilities, and optional dependencies,
 * establishing safe fallbacks where modern or platform-specific APIs are unavailable.
 */
public final class CompatibilityManager {

    private final CompatibilityReport report;

    public CompatibilityManager(Server server, boolean forceSafeModeOnUnknown) {
        this.report = inspect(server, forceSafeModeOnUnknown);
    }

    public CompatibilityReport getReport() {
        return report;
    }

    public ServerPlatform getPlatform() {
        return report.getPlatform();
    }

    public MinecraftVersion getMinecraftVersion() {
        return report.getMinecraftVersion();
    }

    public JavaRuntimeInfo getJavaRuntime() {
        return report.getJavaRuntime();
    }

    public PlatformCapabilities getCapabilities() {
        return report.getCapabilities();
    }

    public static CompatibilityReport inspect(Server server, boolean forceSafeModeOnUnknown) {
        String serverName = server != null ? server.getName() : "Unknown";
        String serverVersion = server != null ? server.getVersion() : "";
        String bukkitVersion = server != null ? server.getBukkitVersion() : "";
        ClassLoader loader = server != null ? server.getClass().getClassLoader() : CompatibilityManager.class.getClassLoader();

        ServerPlatform platform = ServerPlatform.detect(serverName, serverVersion, loader);
        MinecraftVersion mcVersion = MinecraftVersion.parse(serverVersion, bukkitVersion);
        JavaRuntimeInfo javaInfo = JavaRuntimeInfo.detect();

        Class<?> serverClass = server != null ? server.getClass() : Server.class;
        boolean hasPaperTps = hasMethod(serverClass, "getTPS");
        boolean hasPaperMspt = hasMethod(serverClass, "getAverageTickTime");
        boolean hasPaperTickTimes = hasMethod(serverClass, "getTickTimes");
        boolean hasFoliaSchedulers = hasMethod(serverClass, "getGlobalRegionScheduler")
                && hasMethod(serverClass, "getRegionScheduler")
                && hasMethod(serverClass, "getAsyncScheduler");
        boolean hasEntityScheduler = hasMethod(Entity.class, "getScheduler");
        boolean hasPlayerGetPing = hasMethod(Player.class, "getPing");
        boolean hasSpigotPing = hasMethodByName("org.bukkit.entity.Player$Spigot", "getPing", loader);
        boolean hasSimDistance = hasMethod(World.class, "getSimulationDistance");
        boolean hasTileEntities = hasMethodByName("org.bukkit.Chunk", "getTileEntities", loader);

        boolean processCpuSupported = detectProcessCpuSupport();
        boolean threadAllocSupported = detectThreadAllocSupport();

        boolean sparkPresent = false;
        boolean sparkBundled = false;
        boolean chunkyPresent = false;

        if (server != null) {
            try {
                PluginManager pm = server.getPluginManager();
                if (pm != null) {
                    Plugin sparkPlugin = pm.getPlugin("spark");
                    sparkPresent = sparkPlugin != null || isClassAvailable("me.lucko.spark.api.SparkProvider", loader);
                    if (sparkPresent && platform.isPaperCompatible() && mcVersion.isAtLeast(1, 21, 0)) {
                        sparkBundled = true;
                    }
                    Plugin chunkyPlugin = pm.getPlugin("Chunky");
                    chunkyPresent = chunkyPlugin != null || isClassAvailable("org.popcraft.chunky.api.ChunkyAPI", loader);
                }
            } catch (Throwable ignored) {
            }
        }

        PlatformCapabilities capabilities = new PlatformCapabilities(
                hasPaperTps,
                hasPaperMspt,
                hasPaperTickTimes,
                hasFoliaSchedulers,
                hasEntityScheduler,
                hasPlayerGetPing,
                hasSpigotPing,
                hasSimDistance,
                hasTileEntities,
                processCpuSupported,
                threadAllocSupported,
                sparkPresent,
                sparkBundled,
                chunkyPresent
        );

        List<String> warnings = new ArrayList<String>();
        List<String> fallbacks = new ArrayList<String>();
        boolean safeModeRequired = false;

        if (!platform.isKnownPlatform()) {
            warnings.add("Unrecognized Bukkit-compatible implementation '" + serverName + "'.");
            if (forceSafeModeOnUnknown) {
                safeModeRequired = true;
                fallbacks.add("Operating in conservative safe mode due to unrecognized server implementation.");
            }
        }

        if (platform.isRegionThreaded() && !hasFoliaSchedulers) {
            warnings.add("Folia platform detected without expected region scheduler methods; enabling conservative safe mode.");
            safeModeRequired = true;
        }

        if (platform.isRegionThreaded()) {
            fallbacks.add("Folia regionalized multi-threading active: routing tasks through GlobalRegionScheduler, RegionScheduler, and AsyncScheduler.");
        }

        if (!hasPaperTps || !hasPaperMspt) {
            fallbacks.add("Native Paper TPS/MSPT APIs unavailable: using high-precision monotonic tick-interval measurement fallback.");
        }

        if (!hasPlayerGetPing && !hasSpigotPing) {
            fallbacks.add("Direct Player#getPing API unavailable on this server build: using reflective entity ping fallback where supported.");
        }

        if (!hasTileEntities) {
            fallbacks.add("Chunk#getTileEntities inspection unavailable: skipping block-entity enumeration.");
        }

        int minJava = mcVersion.getMinimumRequiredJavaRelease();
        if (javaInfo.getFeatureVersion() < minJava) {
            warnings.add("JVM feature version " + javaInfo.getFeatureVersion()
                    + " is older than the typical target Java " + minJava + " for Minecraft " + mcVersion.toShortString() + ".");
        }

        return new CompatibilityReport(
                platform,
                mcVersion,
                javaInfo,
                capabilities,
                safeModeRequired,
                warnings,
                fallbacks
        );
    }

    private static boolean hasMethod(Class<?> clazz, String methodName, Class<?>... params) {
        if (clazz == null) {
            return false;
        }
        try {
            Method m = clazz.getMethod(methodName, params);
            return m != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasMethodByName(String className, String methodName, ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            return hasMethod(clazz, methodName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isClassAvailable(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean detectProcessCpuSupport() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean == null) {
                return false;
            }
            Method m = osBean.getClass().getMethod("getProcessCpuLoad");
            m.setAccessible(true);
            Object val = m.invoke(osBean);
            return val instanceof Number;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean detectThreadAllocSupport() {
        try {
            ThreadMXBean tmBean = ManagementFactory.getThreadMXBean();
            if (tmBean == null) {
                return false;
            }
            Method m = tmBean.getClass().getMethod("isThreadAllocatedMemorySupported");
            m.setAccessible(true);
            Object val = m.invoke(tmBean);
            return Boolean.TRUE.equals(val);
        } catch (Throwable ignored) {
            return false;
        }
    }
}

