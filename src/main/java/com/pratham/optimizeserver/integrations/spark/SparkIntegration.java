package com.pratham.optimizeserver.integrations.spark;

import com.pratham.optimizeserver.compatibility.CompatibilityReport;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional integration bridge for the Spark profiler (me.lucko.spark).
 * Detects standalone and Paper-bundled Spark installations, queries the Spark API where available,
 * and coordinates administrator-controlled profiling sessions without auto-uploading to external services.
 */
public final class SparkIntegration {

    private final Server server;
    private final CompatibilityReport compatibilityReport;

    private boolean pluginAvailable = false;
    private boolean apiAvailable = false;
    private boolean bundledByPaper = false;
    private String sparkVersion = "unavailable";

    private volatile boolean sessionActive = false;
    private volatile long sessionStartedAtMillis = 0L;
    private volatile String startedBy = "none";
    private volatile String lastSessionSummary = "No profiling session recorded in this runtime.";

    public SparkIntegration(Server server, CompatibilityReport compatibilityReport) {
        this.server = server;
        this.compatibilityReport = compatibilityReport;
        detect();
    }

    public synchronized void detect() {
        this.pluginAvailable = false;
        this.apiAvailable = false;
        this.bundledByPaper = false;
        this.sparkVersion = "unavailable";

        if (server != null) {
            try {
                PluginManager pm = server.getPluginManager();
                if (pm != null) {
                    Plugin sparkPlugin = pm.getPlugin("spark");
                    if (sparkPlugin != null) {
                        this.pluginAvailable = sparkPlugin.isEnabled();
                        if (sparkPlugin.getDescription() != null && sparkPlugin.getDescription().getVersion() != null) {
                            this.sparkVersion = sparkPlugin.getDescription().getVersion();
                        } else {
                            this.sparkVersion = "detected";
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // Check SparkProvider API availability
        try {
            ClassLoader loader = server != null ? server.getClass().getClassLoader() : SparkIntegration.class.getClassLoader();
            Class<?> providerClass = Class.forName("me.lucko.spark.api.SparkProvider", false, loader);
            Method getMethod = providerClass.getMethod("get");
            Object sparkInstance = getMethod.invoke(null);
            if (sparkInstance != null) {
                this.apiAvailable = true;
                this.pluginAvailable = true;
                if ("unavailable".equals(this.sparkVersion)) {
                    this.sparkVersion = "api-provider";
                }
            }
        } catch (Throwable ignored) {
        }

        if (compatibilityReport != null && compatibilityReport.getCapabilities().isSparkBundledByServer()) {
            this.bundledByPaper = true;
            if (this.pluginAvailable && "unavailable".equals(this.sparkVersion)) {
                this.sparkVersion = "paper-bundled";
            }
        }
    }

    public boolean isAvailable() {
        return pluginAvailable || apiAvailable;
    }

    public boolean isApiAvailable() {
        return apiAvailable;
    }

    public boolean isBundledByPaper() {
        return bundledByPaper;
    }

    public String getSparkVersion() {
        return sparkVersion;
    }

    public boolean isSessionActive() {
        return sessionActive;
    }

    public long getSessionStartedAtMillis() {
        return sessionStartedAtMillis;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public String getLastSessionSummary() {
        return lastSessionSummary;
    }

    /**
     * Starts an administrator-controlled Spark CPU profiling session without auto-uploading.
     */
    public synchronized List<String> startProfiling(CommandSender initiator) {
        List<String> messages = new ArrayList<String>();
        detect();
        if (!isAvailable()) {
            messages.add("Spark profiler is not installed or enabled on this server.");
            messages.add("Install Spark (or run Paper 1.21+ where Spark is bundled) to enable method-level stack profiling.");
            return messages;
        }
        if (sessionActive) {
            long elapsedSec = Math.max(0L, (System.currentTimeMillis() - sessionStartedAtMillis) / 1000L);
            messages.add("A Spark profiling session is already active (started " + elapsedSec + "s ago by " + startedBy + ").");
            return messages;
        }

        boolean dispatched = false;
        if (server != null) {
            try {
                CommandSender target = initiator != null ? initiator : server.getConsoleSender();
                if (target != null) {
                    dispatched = server.dispatchCommand(target, "spark profiler start");
                }
            } catch (Throwable ignored) {
            }
        }

        this.sessionActive = true;
        this.sessionStartedAtMillis = System.currentTimeMillis();
        this.startedBy = initiator != null ? initiator.getName() : "Console";
        this.lastSessionSummary = "Active session started by " + this.startedBy + " (external auto-upload disabled).";

        messages.add("Started Spark CPU profiler session (Spark v" + sparkVersion
                + (bundledByPaper ? ", Paper-bundled" : "") + ").");
        if (!dispatched) {
            messages.add("Note: Tracked profiling state locally; run '/os profile stop' when ready to conclude the session.");
        } else {
            messages.add("Dispatched 'spark profiler start' (results remain controlled by the administrator; no automatic external upload is performed by OptimizeServer).");
        }
        return messages;
    }

    /**
     * Stops an active Spark profiling session.
     * Note: We do NOT automatically invoke 'spark profiler stop' if that would trigger an unrequested upload;
     * instead we provide safe local status and only dispatch when requested by the administrator.
     */
    public synchronized List<String> stopProfiling(CommandSender initiator) {
        List<String> messages = new ArrayList<String>();
        detect();
        if (!isAvailable()) {
            messages.add("Spark profiler is not installed or enabled on this server.");
            return messages;
        }
        if (!sessionActive) {
            messages.add("No active OptimizeServer-managed Spark profiling session is currently running.");
            messages.add("Last session summary: " + lastSessionSummary);
            return messages;
        }

        long durationSec = Math.max(1L, (System.currentTimeMillis() - sessionStartedAtMillis) / 1000L);
        this.sessionActive = false;
        this.lastSessionSummary = "Completed " + durationSec + "s session initiated by " + startedBy
                + " (stopped by " + (initiator != null ? initiator.getName() : "Console") + ").";

        messages.add("Stopped OptimizeServer profiling session tracking (" + durationSec + "s elapsed).");
        messages.add("Privacy & Safety Policy: OptimizeServer never automatically uploads profiler data to external services.");
        messages.add("To inspect the captured tree in-game without uploading, use '/spark profiler info' or explicitly run '/spark profiler stop' if you wish to generate a viewer link.");
        return messages;
    }

    /**
     * Returns current Spark profiler status and API metrics.
     */
    public List<String> getStatusLines() {
        detect();
        List<String> lines = new ArrayList<String>();
        lines.add("Spark Available: " + (isAvailable() ? "Yes (v" + sparkVersion + ")" : "No"));
        lines.add("Paper-Bundled Detection: " + (bundledByPaper ? "Yes (avoid duplicate standalone Spark JAR)" : "No"));
        lines.add("Spark Developer API: " + (apiAvailable ? "Connected" : "Unavailable"));
        lines.add("Managed Session Active: " + (sessionActive ? "Yes (started by " + startedBy + ")" : "No"));
        lines.add("Last Session Reference: " + lastSessionSummary);

        Map<String, String> apiStats = querySparkApiMetrics();
        if (!apiStats.isEmpty()) {
            for (Map.Entry<String, String> entry : apiStats.entrySet()) {
                lines.add("Spark API " + entry.getKey() + ": " + entry.getValue());
            }
        }
        return lines;
    }

    /**
     * Provides memory & GC diagnostic assistance combining Spark GC statistics and JVM memory metrics.
     */
    public List<String> getMemoryDiagnostics(MetricSnapshot snapshot) {
        detect();
        List<String> lines = new ArrayList<String>();
        lines.add("Spark Integration Status: " + (isAvailable() ? "Active (v" + sparkVersion + ")" : "Not Installed (using JVM ManagementFactory)"));
        if (snapshot != null) {
            long usedMb = snapshot.getHeapUsedBytes() / (1024L * 1024L);
            long commMb = snapshot.getHeapCommittedBytes() / (1024L * 1024L);
            long maxMb = snapshot.getHeapMaxBytes() / (1024L * 1024L);
            long nonHeapMb = snapshot.getNonHeapUsedBytes() / (1024L * 1024L);
            lines.add(String.format(Locale.ROOT, "Heap Usage: %d MB / %d MB (%.1f%%) | Committed: %d MB | Non-Heap: %d MB",
                    usedMb, maxMb, snapshot.getHeapUsagePercent(), commMb, nonHeapMb));
            lines.add(String.format(Locale.ROOT, "GC Totals: %d collections (%d ms total) | Recent Pause Rate: %.2f ms/s",
                    snapshot.getGcTotalCount(), snapshot.getGcTotalTimeMs(), snapshot.getRecentGcPauseRateMsPerSec()));
            if (snapshot.getRecentAllocatedBytesPerSec() > 0L) {
                lines.add(String.format(Locale.ROOT, "Thread Allocation Rate: %.2f MB/s",
                        snapshot.getRecentAllocatedBytesPerSec() / (1024.0 * 1024.0)));
            }
        }

        Map<String, String> sparkGc = querySparkGcInfo();
        for (Map.Entry<String, String> entry : sparkGc.entrySet()) {
            lines.add("Spark Collector [" + entry.getKey() + "]: " + entry.getValue());
        }

        if (isAvailable()) {
            lines.add("Memory Assistance: For class-level heap retention histograms without external upload, run '/spark heapsummary --save-to-file'.");
        }
        return lines;
    }

    /**
     * Queries Spark API TPS/MSPT/GC metrics reflectively if SparkProvider is registered.
     */
    public Map<String, String> querySparkApiMetrics() {
        if (!apiAvailable) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            ClassLoader loader = server != null ? server.getClass().getClassLoader() : SparkIntegration.class.getClassLoader();
            Class<?> providerClass = Class.forName("me.lucko.spark.api.SparkProvider", false, loader);
            Object spark = providerClass.getMethod("get").invoke(null);
            if (spark == null) {
                return out;
            }
            Class<?> sparkClass = spark.getClass();

            // Query TPS 10s / 1m if available
            Object tpsStat = sparkClass.getMethod("tps").invoke(spark);
            if (tpsStat != null) {
                Class<?> windowEnum = Class.forName("me.lucko.spark.api.statistic.StatisticWindow$TicksPerSecond", false, loader);
                Object sec10 = Enum.valueOf(windowEnum.asSubclass(Enum.class), "SECONDS_10");
                Method pollMethod = tpsStat.getClass().getMethod("poll", windowEnum);
                Object val = pollMethod.invoke(tpsStat, sec10);
                if (val instanceof Number) {
                    out.put("TPS (10s)", String.format(Locale.ROOT, "%.2f", ((Number) val).doubleValue()));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public Map<String, String> querySparkGcInfo() {
        if (!apiAvailable) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            ClassLoader loader = server != null ? server.getClass().getClassLoader() : SparkIntegration.class.getClassLoader();
            Class<?> providerClass = Class.forName("me.lucko.spark.api.SparkProvider", false, loader);
            Object spark = providerClass.getMethod("get").invoke(null);
            if (spark == null) {
                return out;
            }
            Object gcMapObj = spark.getClass().getMethod("gc").invoke(spark);
            if (gcMapObj instanceof Map<?, ?>) {
                Map<?, ?> gcMap = (Map<?, ?>) gcMapObj;
                for (Map.Entry<?, ?> entry : gcMap.entrySet()) {
                    Object key = entry.getKey();
                    Object gcInfo = entry.getValue();
                    if (key != null && gcInfo != null) {
                        long totalCols = ((Number) gcInfo.getClass().getMethod("totalCollections").invoke(gcInfo)).longValue();
                        long totalTime = ((Number) gcInfo.getClass().getMethod("totalTime").invoke(gcInfo)).longValue();
                        double avgTime = ((Number) gcInfo.getClass().getMethod("averageTime").invoke(gcInfo)).doubleValue();
                        out.put(String.valueOf(key), String.format(Locale.ROOT,
                                "collections=%d, totalTime=%dms, avgPause=%.2fms", totalCols, totalTime, avgTime));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
