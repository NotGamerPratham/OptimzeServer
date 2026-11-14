package com.pratham.optimizeserver.analyzer;

import com.pratham.optimizeserver.compatibility.CompatibilityReport;
import com.pratham.optimizeserver.compatibility.JavaRuntimeInfo;
import com.pratham.optimizeserver.compatibility.ServerPlatform;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Inspects the server's on-disk configuration files, JVM startup flags, and memory/CPU capacity,
 * producing strictly non-gameplay-altering performance tuning recommendations and copy-paste presets.
 * Never mutates external server files automatically.
 */
public final class ServerConfigAuditor {

    public enum CapacityStatus {
        HEALTHY,
        TIGHT_HEADROOM,
        INSUFFICIENT_RESOURCES
    }

    public static final class TuningSuggestion {
        private final String file;
        private final String key;
        private final String currentValue;
        private final String recommendedValue;
        private final String reason;

        public TuningSuggestion(String file,
                                String key,
                                String currentValue,
                                String recommendedValue,
                                String reason) {
            this.file = file;
            this.key = key;
            this.currentValue = currentValue;
            this.recommendedValue = recommendedValue;
            this.reason = reason;
        }

        public String getFile() { return file; }
        public String getKey() { return key; }
        public String getCurrentValue() { return currentValue; }
        public String getRecommendedValue() { return recommendedValue; }
        public String getReason() { return reason; }
    }

    public static final class CapacityAssessment {
        private final long heapMaxMb;
        private final long heapUsedMb;
        private final long postGcOldGenUsedMb;
        private final long postGcOldGenMaxMb;
        private final long headroomMb;
        private final int onlinePlayers;
        private final int configuredMaxPlayers;
        private final int estimatedSafePlayers;
        private final int availableCores;
        private final double loadedChunksPerPlayer;
        private final CapacityStatus status;
        private final String summary;

        public CapacityAssessment(long heapMaxMb,
                                  long heapUsedMb,
                                  long postGcOldGenUsedMb,
                                  long postGcOldGenMaxMb,
                                  long headroomMb,
                                  int onlinePlayers,
                                  int configuredMaxPlayers,
                                  int estimatedSafePlayers,
                                  int availableCores,
                                  double loadedChunksPerPlayer,
                                  CapacityStatus status,
                                  String summary) {
            this.heapMaxMb = heapMaxMb;
            this.heapUsedMb = heapUsedMb;
            this.postGcOldGenUsedMb = postGcOldGenUsedMb;
            this.postGcOldGenMaxMb = postGcOldGenMaxMb;
            this.headroomMb = headroomMb;
            this.onlinePlayers = onlinePlayers;
            this.configuredMaxPlayers = configuredMaxPlayers;
            this.estimatedSafePlayers = estimatedSafePlayers;
            this.availableCores = availableCores;
            this.loadedChunksPerPlayer = loadedChunksPerPlayer;
            this.status = status;
            this.summary = summary;
        }

        public long getHeapMaxMb() { return heapMaxMb; }
        public long getHeapUsedMb() { return heapUsedMb; }
        public long getPostGcOldGenUsedMb() { return postGcOldGenUsedMb; }
        public long getPostGcOldGenMaxMb() { return postGcOldGenMaxMb; }
        public long getHeadroomMb() { return headroomMb; }
        public int getOnlinePlayers() { return onlinePlayers; }
        public int getConfiguredMaxPlayers() { return configuredMaxPlayers; }
        public int getEstimatedSafePlayers() { return estimatedSafePlayers; }
        public int getAvailableCores() { return availableCores; }
        public double getLoadedChunksPerPlayer() { return loadedChunksPerPlayer; }
        public CapacityStatus getStatus() { return status; }
        public String getSummary() { return summary; }
    }

    public static final class AuditReport {
        private final File serverRoot;
        private final List<TuningSuggestion> suggestions;
        private final String recommendedJvmCommand;
        private final CapacityAssessment capacity;

        public AuditReport(File serverRoot,
                           List<TuningSuggestion> suggestions,
                           String recommendedJvmCommand,
                           CapacityAssessment capacity) {
            this.serverRoot = serverRoot;
            this.suggestions = Collections.unmodifiableList(new ArrayList<TuningSuggestion>(suggestions));
            this.recommendedJvmCommand = recommendedJvmCommand;
            this.capacity = capacity;
        }

        public File getServerRoot() { return serverRoot; }
        public List<TuningSuggestion> getSuggestions() { return suggestions; }
        public String getRecommendedJvmCommand() { return recommendedJvmCommand; }
        public CapacityAssessment getCapacity() { return capacity; }
    }

    public AuditReport audit(File serverRoot,
                             Server server,
                             CompatibilityReport compat,
                             MetricSnapshot snapshot) {
        File resolvedRoot = serverRoot != null ? serverRoot : new File(".");
        List<TuningSuggestion> suggestions = new ArrayList<TuningSuggestion>();

        ServerPlatform platform = compat != null ? compat.getPlatform() : ServerPlatform.UNKNOWN_BUKKIT;
        JavaRuntimeInfo jvm = compat != null ? compat.getJavaRuntime() : JavaRuntimeInfo.detect();

        int maxPlayers = resolveMaxPlayers(server);
        CapacityAssessment capacity = evaluateCapacity(snapshot, compat, maxPlayers);

        auditPlatform(platform, suggestions);
        auditJvm(jvm, capacity.getHeapMaxMb(), suggestions);

        Properties serverProps = loadProperties(new File(resolvedRoot, "server.properties"));
        YamlConfiguration bukkitYml = loadYaml(new File(resolvedRoot, "bukkit.yml"));
        YamlConfiguration spigotYml = loadYaml(new File(resolvedRoot, "spigot.yml"));
        YamlConfiguration paperGlobal = loadYaml(new File(resolvedRoot, "config" + File.separator + "paper-global.yml"));
        YamlConfiguration paperWorldDefaults = loadYaml(new File(resolvedRoot, "config" + File.separator + "paper-world-defaults.yml"));
        YamlConfiguration legacyPaper = loadYaml(new File(resolvedRoot, "paper.yml"));

        boolean behindProxy = isProxyForwardingEnabled(spigotYml, paperGlobal, legacyPaper);

        auditServerProperties(serverProps, platform, behindProxy, compat, suggestions);
        auditBukkitYml(bukkitYml, suggestions);
        auditSpigotYml(spigotYml, capacity.getAvailableCores(), suggestions);
        auditPaperConfigs(paperGlobal, paperWorldDefaults, legacyPaper, capacity.getAvailableCores(), suggestions);

        String recommendedJvmCmd = buildRecommendedJvmCommand(capacity.getHeapMaxMb(), jvm);
        return new AuditReport(resolvedRoot, suggestions, recommendedJvmCmd, capacity);
    }

    /**
     * Evaluates physical heap and CPU headroom against current and configured maximum player count.
     */
    public CapacityAssessment evaluateCapacity(MetricSnapshot snapshot,
                                                 CompatibilityReport compat,
                                                 int configuredMaxPlayers) {
        long heapMaxBytes = snapshot != null && snapshot.getHeapMaxBytes() > 0L
                ? snapshot.getHeapMaxBytes()
                : Runtime.getRuntime().maxMemory();
        long heapUsedBytes = snapshot != null ? snapshot.getHeapUsedBytes() : 0L;
        long postGcUsedBytes = snapshot != null ? snapshot.getPostGcHeapUsedBytes() : 0L;
        long postGcMaxBytes = snapshot != null ? snapshot.getPostGcHeapMaxBytes() : 0L;
        int online = snapshot != null ? snapshot.getOnlinePlayers() : 0;
        int cores = snapshot != null && snapshot.getAvailableProcessors() > 0
                ? snapshot.getAvailableProcessors()
                : Runtime.getRuntime().availableProcessors();
        int loadedChunks = snapshot != null ? snapshot.getLoadedChunks() : 0;

        long heapMaxMb = Math.max(1L, heapMaxBytes / (1024L * 1024L));
        long heapUsedMb = heapUsedBytes / (1024L * 1024L);
        long postGcUsedMb = postGcUsedBytes / (1024L * 1024L);
        long postGcMaxMb = postGcMaxBytes / (1024L * 1024L);

        long liveBaselineUsed = postGcUsedMb > 0L ? postGcUsedMb : heapUsedMb;
        long headroomMb = Math.max(0L, heapMaxMb - liveBaselineUsed);

        boolean modernMc = compat != null && compat.getMinecraftVersion().isAtLeast(1, 18, 0);
        // Conservative empirical vanilla heap model: base server footprint + per-player chunks/entities/packets
        long baselineFootprintMb = modernMc ? 650L : 260L;
        long perPlayerMb = modernMc ? 45L : 22L;
        long effectiveUsableHeapMb = Math.max(0L, (long) (heapMaxMb * 0.85) - baselineFootprintMb);
        int estimatedSafePlayers = Math.max(0, (int) (effectiveUsableHeapMb / perPlayerMb));

        double chunksPerPlayer = online > 0 ? (loadedChunks / (double) online) : 0.0;

        CapacityStatus status;
        String summary;
        int targetMax = configuredMaxPlayers > 0 ? configuredMaxPlayers : Math.max(20, online);
        if (heapMaxMb <= 1536L && (targetMax >= 30 || online >= 20)) {
            status = CapacityStatus.INSUFFICIENT_RESOURCES;
            summary = "Heap ceiling (" + heapMaxMb + " MB) cannot sustain " + targetMax
                    + " concurrent players under vanilla gameplay without GC thrashing or OOM; safe vanilla estimate is ~"
                    + Math.max(1, estimatedSafePlayers) + " players on this heap.";
        } else if ((postGcMaxMb > 0L && (postGcUsedMb * 100.0 / postGcMaxMb) >= 82.0)
                || headroomMb < 256L
                || cores < 2) {
            status = CapacityStatus.TIGHT_HEADROOM;
            summary = "Memory/CPU headroom is tight (" + headroomMb + " MB free of " + heapMaxMb
                    + " MB heap across " + cores + " logical cores; estimated safe capacity ~"
                    + Math.max(1, estimatedSafePlayers) + " players).";
        } else {
            status = CapacityStatus.HEALTHY;
            summary = "Heap headroom (" + headroomMb + " MB free of " + heapMaxMb
                    + " MB) and CPU (" + cores + " cores) are within sustainable bounds for ~"
                    + Math.max(1, estimatedSafePlayers) + " concurrent players.";
        }

        return new CapacityAssessment(
                heapMaxMb,
                heapUsedMb,
                postGcUsedMb,
                postGcMaxMb,
                headroomMb,
                online,
                configuredMaxPlayers,
                estimatedSafePlayers,
                cores,
                chunksPerPlayer,
                status,
                summary
        );
    }

    /**
     * Writes human-readable tuning presets asynchronously-safe into plugins/OptimizeServer/presets/.
     */
    public File writePresets(File dataFolder, AuditReport report) throws IOException {
        if (dataFolder == null || report == null) {
            return null;
        }
        File presetsDir = new File(dataFolder, "presets");
        if (!presetsDir.exists()) {
            presetsDir.mkdirs();
        }

        File tuningFile = new File(presetsDir, "server-tuning-report.txt");
        List<String> lines = new ArrayList<String>();
        lines.add("================================================================================");
        lines.add(" OptimizeServer Non-Gameplay Server & JVM Tuning Report");
        lines.add(" Every suggestion below preserves 100% vanilla gameplay mechanics.");
        lines.add("================================================================================");
        lines.add("");
        lines.add("[CAPACITY ASSESSMENT]");
        CapacityAssessment cap = report.getCapacity();
        lines.add("  Status         : " + cap.getStatus().name());
        lines.add("  Summary        : " + cap.getSummary());
        lines.add("  Heap Usage     : " + cap.getHeapUsedMb() + " MB used / " + cap.getHeapMaxMb()
                + " MB max (headroom ~" + cap.getHeadroomMb() + " MB)");
        if (cap.getPostGcOldGenMaxMb() > 0L) {
            lines.add("  Post-GC OldGen : " + cap.getPostGcOldGenUsedMb() + " MB / " + cap.getPostGcOldGenMaxMb() + " MB");
        }
        lines.add("  Players        : " + cap.getOnlinePlayers() + " online / " + cap.getConfiguredMaxPlayers()
                + " max (est. safe: ~" + cap.getEstimatedSafePlayers() + ")");
        lines.add("");
        lines.add("[RECOMMENDED JVM STARTUP COMMAND]");
        lines.add("  " + report.getRecommendedJvmCommand());
        lines.add("");
        lines.add("[CONFIGURATION AUDIT FINDINGS (" + report.getSuggestions().size() + ")]");
        if (report.getSuggestions().isEmpty()) {
            lines.add("  All audited zero-gameplay settings look clean.");
        } else {
            for (TuningSuggestion s : report.getSuggestions()) {
                lines.add("  * [" + s.getFile() + "] " + s.getKey());
                lines.add("      Current     : " + s.getCurrentValue());
                lines.add("      Recommended : " + s.getRecommendedValue());
                lines.add("      Reason      : " + s.getReason());
            }
        }
        writeLines(tuningFile, lines);

        File jvmFile = new File(presetsDir, "recommended-jvm-flags.txt");
        List<String> jvmLines = new ArrayList<String>();
        jvmLines.add("# Copy into your start.sh / start.bat (adjust -Xms / -Xmx to leave host RAM for OS & off-heap)");
        jvmLines.add(report.getRecommendedJvmCommand());
        writeLines(jvmFile, jvmLines);

        return tuningFile;
    }

    private void auditPlatform(ServerPlatform platform, List<TuningSuggestion> out) {
        if (platform == ServerPlatform.SPIGOT || platform == ServerPlatform.CRAFTBUKKIT) {
            out.add(new TuningSuggestion(
                    "server-jar",
                    "Server implementation",
                    platform.getDisplayName(),
                    "Paper (or Purpur)",
                    "Async chunk I/O, faster lighting engine, and far lower heap allocations with identical Bukkit/Spigot plugins and vanilla gameplay."
            ));
        }
    }

    private void auditJvm(JavaRuntimeInfo jvm, long heapMaxMb, List<TuningSuggestion> out) {
        if (jvm == null) {
            return;
        }
        if (!jvm.hasMatchingXmsXmx()) {
            out.add(new TuningSuggestion(
                    "startup-flags",
                    "-Xms / -Xmx",
                    "Mismatched or unset",
                    "-Xms" + heapMaxMb + "M -Xmx" + heapMaxMb + "M",
                    "Prevents runtime heap resizing pauses and OS page faults."
            ));
        }
        if (!jvm.hasExplicitDisableExplicitGC()) {
            out.add(new TuningSuggestion(
                    "startup-flags",
                    "-XX:+DisableExplicitGC",
                    "Missing",
                    "-XX:+DisableExplicitGC",
                    "Stops plugins or libraries from triggering stop-the-world Full GC via System.gc()."
            ));
        }
        if (!jvm.isUsingG1GC() && !jvm.isUsingZGC()) {
            out.add(new TuningSuggestion(
                    "startup-flags",
                    "Garbage Collector",
                    String.valueOf(jvm.getCollectorNames()),
                    heapMaxMb >= 14336L && jvm.getFeatureVersion() >= 21 ? "-XX:+UseZGC -XX:+ZGenerational" : "-XX:+UseG1GC",
                    "Reduces long stop-the-world tick freezes under entity and chunk allocation churn."
            ));
        }
    }

    private void auditServerProperties(Properties props,
                                       ServerPlatform platform,
                                       boolean behindProxy,
                                       CompatibilityReport compat,
                                       List<TuningSuggestion> out) {
        if (props == null) {
            return;
        }
        String syncWrites = props.getProperty("sync-chunk-writes");
        if (platform.isPaperCompatible() && "true".equalsIgnoreCase(syncWrites)) {
            out.add(new TuningSuggestion(
                    "server.properties",
                    "sync-chunk-writes",
                    "true",
                    "false",
                    "Prevents blocking synchronous chunk disk-flush stalls on Linux/Paper without altering world data or gameplay."
            ));
        }

        String netCompRaw = props.getProperty("network-compression-threshold");
        if (netCompRaw != null) {
            try {
                int thresh = Integer.parseInt(netCompRaw.trim());
                if (behindProxy && thresh != -1) {
                    out.add(new TuningSuggestion(
                            "server.properties",
                            "network-compression-threshold",
                            String.valueOf(thresh),
                            "-1 (when behind Velocity/BungeeCord proxy)",
                            "Offloads packet zlib compression from this backend JVM onto your reverse proxy, saving significant CPU per player."
                    ));
                } else if (!behindProxy && thresh >= 0 && thresh < 256) {
                    out.add(new TuningSuggestion(
                            "server.properties",
                            "network-compression-threshold",
                            String.valueOf(thresh),
                            "256",
                            "Compressing tiny packets wastes CPU for 50 players with negligible bandwidth gain."
                    ));
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (platform.isPaperCompatible() && compat != null && compat.getMinecraftVersion().isAtLeast(1, 20, 5)) {
            String regionComp = props.getProperty("region-file-compression");
            if (regionComp != null && "deflate".equalsIgnoreCase(regionComp.trim())) {
                out.add(new TuningSuggestion(
                        "server.properties",
                        "region-file-compression",
                        "deflate",
                        "lz4",
                        "LZ4 region compression cuts CPU overhead during chunk loading and saving with zero gameplay change."
                ));
            }
        }
    }

    private void auditBukkitYml(YamlConfiguration bukkit, List<TuningSuggestion> out) {
        if (bukkit == null) {
            return;
        }
        int chunkGcPeriod = bukkit.getInt("chunk-gc.period-in-ticks", 600);
        if (chunkGcPeriod > 400) {
            out.add(new TuningSuggestion(
                    "bukkit.yml",
                    "chunk-gc.period-in-ticks",
                    String.valueOf(chunkGcPeriod),
                    "300",
                    "Sweeps unreferenced/leaked chunk objects from heap twice as promptly cleanly without unloading active chunks."
            ));
        }
        int autosaveTicks = bukkit.getInt("ticks-per.autosave", 6000);
        if (autosaveTicks > 0 && autosaveTicks < 3000) {
            out.add(new TuningSuggestion(
                    "bukkit.yml",
                    "ticks-per.autosave",
                    String.valueOf(autosaveTicks),
                    "6000",
                    "Overly frequent world saves (< 2.5 min) trigger unnecessary disk I/O and MSPT spikes."
            ));
        }
    }

    private void auditSpigotYml(YamlConfiguration spigot, int cores, List<TuningSuggestion> out) {
        if (spigot == null) {
            return;
        }
        if (spigot.contains("settings.save-user-cache-on-stop-only")
                && !spigot.getBoolean("settings.save-user-cache-on-stop-only", false)) {
            out.add(new TuningSuggestion(
                    "spigot.yml",
                    "settings.save-user-cache-on-stop-only",
                    "false",
                    "true",
                    "Avoids periodic synchronous usercache.json disk writes during live gameplay."
            ));
        }
        int nettyThreads = spigot.getInt("settings.netty-threads", 4);
        if (cores <= 2 && nettyThreads > 4) {
            out.add(new TuningSuggestion(
                    "spigot.yml",
                    "settings.netty-threads",
                    String.valueOf(nettyThreads),
                    "2",
                    "Prevents Netty I/O threads from oversubscribing a " + cores + "-core host CPU."
            ));
        }
    }

    private void auditPaperConfigs(YamlConfiguration paperGlobal,
                                   YamlConfiguration paperWorldDefaults,
                                   YamlConfiguration legacyPaper,
                                   int cores,
                                   List<TuningSuggestion> out) {
        if (paperWorldDefaults != null) {
            int maxSave = paperWorldDefaults.getInt("chunks.max-auto-save-chunks-per-tick", 24);
            if (maxSave > 10) {
                out.add(new TuningSuggestion(
                        "config/paper-world-defaults.yml",
                        "chunks.max-auto-save-chunks-per-tick",
                        String.valueOf(maxSave),
                        "6",
                        "Spreads incremental chunk saving smoothly across ticks, cutting P95/P99 MSPT spikes without altering gameplay."
                ));
            }
            if (!paperWorldDefaults.getBoolean("chunks.prevent-moving-into-unloaded-chunks", false)) {
                out.add(new TuningSuggestion(
                        "config/paper-world-defaults.yml",
                        "chunks.prevent-moving-into-unloaded-chunks",
                        "false",
                        "true",
                        "Stops fast-moving players or elytras from stalling the main tick thread waiting on synchronous chunk reads."
                ));
            }
        } else if (legacyPaper != null) {
            int maxSave = legacyPaper.getInt("world-settings.default.max-auto-save-chunks-per-tick", 24);
            if (maxSave > 10) {
                out.add(new TuningSuggestion(
                        "paper.yml",
                        "world-settings.default.max-auto-save-chunks-per-tick",
                        String.valueOf(maxSave),
                        "6",
                        "Spreads incremental chunk saving smoothly across ticks without altering gameplay."
                ));
            }
            if (!legacyPaper.getBoolean("world-settings.default.prevent-moving-into-unloaded-chunks", false)) {
                out.add(new TuningSuggestion(
                        "paper.yml",
                        "world-settings.default.prevent-moving-into-unloaded-chunks",
                        "false",
                        "true",
                        "Prevents main-thread stalls when players sprint/elytra into unloaded chunks."
                ));
            }
        }

        if (paperGlobal != null && cores <= 2) {
            int workerThreads = paperGlobal.getInt("chunk-system.worker-threads", -1);
            if (workerThreads < 0 || workerThreads > 2) {
                out.add(new TuningSuggestion(
                        "config/paper-global.yml",
                        "chunk-system.worker-threads",
                        String.valueOf(workerThreads),
                        "1",
                        "Caps async chunk worker threads on a " + cores + "-core CPU so workers don't starve the primary tick thread."
                ));
            }
        }
    }

    public static String buildRecommendedJvmCommand(long heapMaxMb, JavaRuntimeInfo jvm) {
        long clampedHeapMb = Math.max(512L, heapMaxMb);
        int javaVer = jvm != null ? jvm.getFeatureVersion() : 17;

        if (clampedHeapMb >= 14336L && javaVer >= 21) {
            return "java -Xms" + clampedHeapMb + "M -Xmx" + clampedHeapMb + "M "
                    + "-XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -jar server.jar --nogui";
        }
        if (clampedHeapMb <= 1536L) {
            // Tuned specifically for small heaps: avoids Aikar's 30% NewSizePercent starving OldGen under <1.5GB
            return "java -Xms" + clampedHeapMb + "M -Xmx" + clampedHeapMb + "M "
                    + "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=100 "
                    + "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch "
                    + "-XX:G1HeapRegionSize=4M -XX:G1ReservePercent=15 -XX:InitiatingHeapOccupancyPercent=25 "
                    + "-jar server.jar --nogui";
        }
        return "java -Xms" + clampedHeapMb + "M -Xmx" + clampedHeapMb + "M "
                + "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=130 "
                + "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch "
                + "-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M "
                + "-XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15 -jar server.jar --nogui";
    }

    private static boolean isProxyForwardingEnabled(YamlConfiguration spigot,
                                                    YamlConfiguration paperGlobal,
                                                    YamlConfiguration legacyPaper) {
        if (spigot != null && spigot.getBoolean("settings.bungeecord", false)) {
            return true;
        }
        if (paperGlobal != null && paperGlobal.getBoolean("proxies.velocity.enabled", false)) {
            return true;
        }
        return legacyPaper != null && legacyPaper.getBoolean("settings.velocity-support.enabled", false);
    }

    private static int resolveMaxPlayers(Server server) {
        if (server == null) {
            return 20;
        }
        try {
            return Math.max(0, server.getMaxPlayers());
        } catch (Throwable ignored) {
            return 20;
        }
    }

    private static Properties loadProperties(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        Properties props = new Properties();
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            props.load(new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8)));
            return props;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static YamlConfiguration loadYaml(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeLines(File file, List<String> lines) throws IOException {
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8));
        try {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        } finally {
            writer.close();
        }
    }
}
