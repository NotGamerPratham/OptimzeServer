package com.pratham.optimizeserver.integrations.chunky;

import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Optional integration bridge for the Chunky chunk pre-generation plugin (org.popcraft.chunky).
 * Uses the documented ChunkyAPI via Bukkit ServicesManager where available.
 * Never starts chunk generation automatically or modifies world borders/settings.
 */
public final class ChunkyIntegration {

    private final Server server;
    private final ConfigManager configManager;

    private boolean pluginAvailable = false;
    private boolean apiAvailable = false;
    private String chunkyVersion = "unavailable";
    private int apiVersion = -1;
    private Object chunkyApiInstance;

    private Method isRunningMethod;
    private Method startTaskMethod;
    private Method pauseTaskMethod;
    private Method continueTaskMethod;
    private Method cancelTaskMethod;

    public ChunkyIntegration(Server server, ConfigManager configManager) {
        this.server = server;
        this.configManager = configManager;
        detect();
    }

    public synchronized void detect() {
        this.pluginAvailable = false;
        this.apiAvailable = false;
        this.chunkyVersion = "unavailable";
        this.apiVersion = -1;
        this.chunkyApiInstance = null;

        if (server == null) {
            return;
        }

        try {
            PluginManager pm = server.getPluginManager();
            if (pm != null) {
                Plugin p = pm.getPlugin("Chunky");
                if (p != null) {
                    this.pluginAvailable = p.isEnabled();
                    if (p.getDescription() != null && p.getDescription().getVersion() != null) {
                        this.chunkyVersion = p.getDescription().getVersion();
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            ClassLoader loader = server.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("org.popcraft.chunky.api.ChunkyAPI", false, loader);
            ServicesManager sm = server.getServicesManager();
            if (sm != null) {
                Object api = sm.load(apiClass);
                if (api != null) {
                    this.chunkyApiInstance = api;
                    this.apiAvailable = true;
                    this.pluginAvailable = true;

                    Method versionMethod = apiClass.getMethod("version");
                    Object verVal = versionMethod.invoke(api);
                    if (verVal instanceof Number) {
                        this.apiVersion = ((Number) verVal).intValue();
                    }

                    this.isRunningMethod = apiClass.getMethod("isRunning", String.class);
                    this.startTaskMethod = apiClass.getMethod("startTask",
                            String.class, String.class, double.class, double.class, double.class, double.class, String.class);
                    this.pauseTaskMethod = apiClass.getMethod("pauseTask", String.class);
                    this.continueTaskMethod = apiClass.getMethod("continueTask", String.class);
                    this.cancelTaskMethod = apiClass.getMethod("cancelTask", String.class);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isAvailable() {
        return pluginAvailable;
    }

    public boolean isApiAvailable() {
        return apiAvailable && chunkyApiInstance != null;
    }

    public String getChunkyVersion() {
        return chunkyVersion;
    }

    public int getApiVersion() {
        return apiVersion;
    }

    public boolean isGenerationRunning(String worldName) {
        detect();
        if (!isApiAvailable() || isRunningMethod == null || worldName == null) {
            return false;
        }
        try {
            Object res = isRunningMethod.invoke(chunkyApiInstance, worldName);
            return Boolean.TRUE.equals(res);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Reports Chunky availability and per-world task generation status.
     */
    public List<String> getStatusLines(MetricSnapshot latestSnapshot) {
        detect();
        List<String> lines = new ArrayList<String>();
        lines.add("Chunky Plugin Available: " + (isAvailable() ? "Yes (v" + chunkyVersion + ")" : "No"));
        lines.add("ChunkyAPI Service Connected: " + (isApiAvailable() ? "Yes (API v" + apiVersion + ")" : "Unavailable"));

        if (latestSnapshot != null) {
            lines.add(String.format(Locale.ROOT,
                    "Live Chunk Telemetry: %d loaded | %.1f loads/s | %.1f unloads/s | %.1f new-gens/s",
                    latestSnapshot.getLoadedChunks(),
                    latestSnapshot.getChunkLoadRatePerSec(),
                    latestSnapshot.getChunkUnloadRatePerSec(),
                    latestSnapshot.getChunkGenRatePerSec()));
        }

        if (isApiAvailable() && server != null && server.getWorlds() != null) {
            for (World world : server.getWorlds()) {
                if (world != null) {
                    boolean running = isGenerationRunning(world.getName());
                    int loadedCount = 0;
                    try {
                        if (world.getLoadedChunks() != null) {
                            loadedCount = world.getLoadedChunks().length;
                        }
                    } catch (Throwable ignored) {
                    }
                    lines.add("World [" + world.getName() + "]: Pre-gen Task="
                            + (running ? "RUNNING" : "IDLE") + " | Loaded Chunks=" + loadedCount);
                }
            }
        }
        return lines;
    }

    /**
     * Provides safe pre-generation recommendations and resource requirement calculations.
     */
    public List<String> getRecommendations(MetricSnapshot snapshot) {
        detect();
        List<String> lines = new ArrayList<String>();
        int maxRecRadius = configManager != null
                ? configManager.getInt("integrations.chunky.max-recommended-radius-blocks", 5000)
                : 5000;
        double safeTpsFloor = configManager != null
                ? configManager.getDouble("integrations.chunky.safe-tps-floor-for-generation", 18.0)
                : 18.0;

        int recRadius = Math.min(maxRecRadius, 3000);
        long chunksInSquare = (long) Math.ceil((recRadius * 2.0) / 16.0) * (long) Math.ceil((recRadius * 2.0) / 16.0);
        long estimatedDiskMb = (chunksInSquare * 6L) / 1024L; // ~6 KB compressed per chunk average

        lines.add("Chunk Pre-Generation Engineering Recommendations:");
        lines.add("1. Recommended Initial Radius: " + recRadius + " blocks (~" + chunksInSquare
                + " chunks, estimated ~" + estimatedDiskMb + " MB disk storage per world).");
        lines.add("2. Resource Impact Warning: Terrain pre-generation is heavily CPU- and I/O-intensive. Run only during off-peak hours with minimal online players.");
        if (snapshot != null) {
            boolean tpsSafe = snapshot.getTps1m() >= safeTpsFloor && snapshot.getAverageMspt() < 42.0;
            lines.add(String.format(Locale.ROOT,
                    "3. Current Server Readiness: 1m TPS=%.2f, Avg MSPT=%.2f ms, Free Disk=%d MB -> %s",
                    snapshot.getTps1m(),
                    snapshot.getAverageMspt(),
                    snapshot.getFreeDiskSpaceMb(),
                    tpsSafe ? "READY FOR OFF-PEAK PRE-GENERATION" : "DEFER (Resolve active load before starting pre-gen)"));
        }
        lines.add("4. Gameplay Guarantee: OptimizeServer never starts pre-generation automatically and never modifies world borders or vanilla generation rules.");
        if (isAvailable()) {
            lines.add("To start manually when ready: /os chunks start <world> <radiusBlocks>");
        } else {
            lines.add("Note: Install Chunky to execute pre-generation tasks directly via '/os chunks start'.");
        }
        return lines;
    }

    /**
     * Starts an administrator-requested Chunky pre-generation task after verifying resource readiness.
     */
    public List<String> startGeneration(String worldName, double radiusBlocks, MetricSnapshot snapshot) {
        detect();
        List<String> lines = new ArrayList<String>();
        if (!isAvailable() || !isApiAvailable() || startTaskMethod == null) {
            lines.add("Chunky developer API is not available on this server. Install Chunky to use '/os chunks start'.");
            return lines;
        }
        String targetWorld = resolveWorldName(worldName);
        if (targetWorld == null) {
            lines.add("Specified world does not exist on this server.");
            return lines;
        }
        int maxRadius = configManager != null
                ? configManager.getInt("integrations.chunky.max-recommended-radius-blocks", 5000)
                : 5000;
        double safeRadius = Math.max(160.0, Math.min(maxRadius * 2.0, radiusBlocks));
        long chunksToGen = (long) Math.ceil((safeRadius * 2.0) / 16.0) * (long) Math.ceil((safeRadius * 2.0) / 16.0);
        long estDiskMb = (chunksToGen * 6L) / 1024L;

        lines.add(String.format(Locale.ROOT,
                "Resource Notice: Pre-generating '%s' (radius %.0f blocks, ~%d chunks) requires ~%d MB disk space and sustained multi-core CPU capacity.",
                targetWorld, safeRadius, chunksToGen, estDiskMb));

        if (snapshot != null && snapshot.getFreeDiskSpaceMb() > 0L && snapshot.getFreeDiskSpaceMb() < estDiskMb + 512L) {
            lines.add("Refusing to start pre-generation: free disk space (" + snapshot.getFreeDiskSpaceMb()
                    + " MB) is insufficient for estimated output (~" + estDiskMb + " MB + 512 MB safety margin).");
            return lines;
        }

        if (isGenerationRunning(targetWorld)) {
            lines.add("A Chunky generation task is already running in world '" + targetWorld + "'.");
            return lines;
        }

        try {
            Object started = startTaskMethod.invoke(
                    chunkyApiInstance, targetWorld, "square", 0.0, 0.0, safeRadius, safeRadius, "concentric");
            if (Boolean.TRUE.equals(started)) {
                lines.add("Started Chunky pre-generation in world '" + targetWorld + "' (square radius " + (int) safeRadius + " blocks).");
                lines.add("Use '/os chunks pause " + targetWorld + "' or '/os chunks cancel " + targetWorld + "' to control execution.");
            } else {
                lines.add("ChunkyAPI declined to start the task for world '" + targetWorld + "'. Check console logs.");
            }
        } catch (Throwable t) {
            lines.add("Failed to invoke ChunkyAPI startTask: " + t.getMessage());
        }
        return lines;
    }

    public List<String> pauseOrResumeGeneration(String worldName) {
        detect();
        List<String> lines = new ArrayList<String>();
        if (!isAvailable() || !isApiAvailable()) {
            lines.add("Chunky developer API is not available on this server.");
            return lines;
        }
        String targetWorld = resolveWorldName(worldName);
        if (targetWorld == null) {
            lines.add("Specified world does not exist on this server.");
            return lines;
        }
        try {
            if (isGenerationRunning(targetWorld)) {
                Object ok = pauseTaskMethod.invoke(chunkyApiInstance, targetWorld);
                lines.add(Boolean.TRUE.equals(ok)
                        ? "Paused Chunky generation task for world '" + targetWorld + "'."
                        : "Could not pause task for world '" + targetWorld + "'.");
            } else {
                Object ok = continueTaskMethod.invoke(chunkyApiInstance, targetWorld);
                lines.add(Boolean.TRUE.equals(ok)
                        ? "Resumed Chunky generation task for world '" + targetWorld + "'."
                        : "No paused Chunky task found to resume for world '" + targetWorld + "'.");
            }
        } catch (Throwable t) {
            lines.add("Failed to pause/resume Chunky task: " + t.getMessage());
        }
        return lines;
    }

    public List<String> cancelGeneration(String worldName) {
        detect();
        List<String> lines = new ArrayList<String>();
        if (!isAvailable() || !isApiAvailable() || cancelTaskMethod == null) {
            lines.add("Chunky developer API is not available on this server.");
            return lines;
        }
        String targetWorld = resolveWorldName(worldName);
        if (targetWorld == null) {
            lines.add("Specified world does not exist on this server.");
            return lines;
        }
        try {
            Object ok = cancelTaskMethod.invoke(chunkyApiInstance, targetWorld);
            lines.add(Boolean.TRUE.equals(ok)
                    ? "Cancelled Chunky generation task for world '" + targetWorld + "'."
                    : "No active Chunky task to cancel for world '" + targetWorld + "'.");
        } catch (Throwable t) {
            lines.add("Failed to cancel Chunky task: " + t.getMessage());
        }
        return lines;
    }

    private String resolveWorldName(String requested) {
        if (server == null) {
            return requested;
        }
        if (requested != null && !requested.trim().isEmpty()) {
            World w = server.getWorld(requested.trim());
            return w != null ? w.getName() : null;
        }
        List<World> worlds = server.getWorlds();
        if (worlds != null && !worlds.isEmpty() && worlds.get(0) != null) {
            return worlds.get(0).getName();
        }
        return null;
    }
}
