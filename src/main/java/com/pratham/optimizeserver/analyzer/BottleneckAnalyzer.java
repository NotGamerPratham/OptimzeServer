package com.pratham.optimizeserver.analyzer;

import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.metrics.MetricSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-signal bottleneck classification engine.
 * Combines instantaneous metrics, rolling window percentiles, statistical anomalies, and linear trends
 * to identify root-cause candidates without conflating correlation with causation.
 */
public final class BottleneckAnalyzer {

    private final AtomicLong findingSequence = new AtomicLong(1000L);

    public List<DiagnosticFinding> analyze(EvidenceCollector.EvidenceBundle bundle,
                                           List<AnomalyDetector.AnomalySignal> anomalies,
                                           TrendAnalyzer.TrendSummary trends,
                                           ConfigManager config) {
        List<DiagnosticFinding> findings = new ArrayList<DiagnosticFinding>();
        if (bundle == null || bundle.getCurrent() == null) {
            return findings;
        }

        MetricSnapshot s = bundle.getCurrent();
        long now = s.getTimestampMillis();

        double msptWarn = config != null ? config.getDouble("monitoring.thresholds.mspt-warning", 40.0) : 40.0;
        double msptCrit = config != null ? config.getDouble("monitoring.thresholds.mspt-critical", 50.0) : 50.0;
        double cpuWarn = config != null ? config.getDouble("monitoring.cpu.process-usage-warning-percent", 75.0) : 75.0;
        double cpuCrit = config != null ? config.getDouble("monitoring.cpu.process-usage-critical-percent", 90.0) : 90.0;
        double heapWarn = config != null ? config.getDouble("monitoring.memory.heap-usage-warning-percent", 80.0) : 80.0;
        double heapCrit = config != null ? config.getDouble("monitoring.memory.heap-usage-critical-percent", 92.0) : 92.0;
        double gcWarn = config != null ? config.getDouble("monitoring.memory.gc-pause-warning-ms", 50.0) : 50.0;
        double gcCrit = config != null ? config.getDouble("monitoring.memory.gc-pause-critical-ms", 150.0) : 150.0;
        int chunkWarn = config != null ? config.getInt("monitoring.chunks.loaded-chunks-warning", 2500) : 2500;
        double chunkLoadWarn = config != null ? config.getDouble("monitoring.chunks.chunk-load-rate-warning-per-sec", 40.0) : 40.0;
        double chunkGenWarn = config != null ? config.getDouble("monitoring.chunks.chunk-gen-rate-warning-per-sec", 12.0) : 12.0;
        int entityWarn = config != null ? config.getInt("monitoring.entities.total-entities-warning", 3000) : 3000;
        int entityCrit = config != null ? config.getInt("monitoring.entities.total-entities-critical", 6000) : 6000;
        int tileWarn = config != null ? config.getInt("monitoring.entities.tile-entities-warning", 4000) : 4000;
        int taskWarn = config != null ? config.getInt("monitoring.tasks.pending-tasks-warning", 250) : 250;
        long diskWarnMb = config != null ? config.getLong("monitoring.storage.free-space-warning-mb", 2048L) : 2048L;
        double ioLatencyWarn = config != null ? config.getDouble("monitoring.storage.io-latency-warning-ms", 25.0) : 25.0;
        double pingWarn = config != null ? config.getDouble("monitoring.network.ping-warning-ms", 180.0) : 180.0;

        boolean tickElevated = s.getAverageMspt() >= msptWarn || s.getP95Mspt() >= 45.0 || s.getTps1m() < 18.5;

        // 1. CPU-Bound Condition
        if (s.getProcessCpuLoadPercent() >= cpuWarn || s.getSystemCpuLoadPercent() >= 85.0) {
            Severity sev = (s.getProcessCpuLoadPercent() >= cpuCrit || s.getAverageMspt() >= msptCrit)
                    ? Severity.CRITICAL : Severity.HIGH;
            Map<String, String> metrics = new LinkedHashMap<String, String>();
            metrics.put("processCpuPercent", formatDouble(s.getProcessCpuLoadPercent()));
            metrics.put("systemCpuPercent", formatDouble(s.getSystemCpuLoadPercent()));
            metrics.put("availableProcessors", String.valueOf(s.getAvailableProcessors()));
            metrics.put("averageMspt", formatDouble(s.getAverageMspt()));

            List<String> ev = new ArrayList<String>();
            ev.add("JVM process CPU load is " + formatDouble(s.getProcessCpuLoadPercent()) + "% across "
                    + s.getAvailableProcessors() + " logical cores (system load: " + formatDouble(s.getSystemCpuLoadPercent()) + "%).");
            if (tickElevated) {
                ev.add("Elevated tick duration (Avg MSPT: " + formatDouble(s.getAverageMspt()) + " ms, P95: "
                        + formatDouble(s.getP95Mspt()) + " ms) correlates with high CPU saturation.");
            }
            if (trends != null && trends.getCpuSlopePerMinute() > 2.0) {
                ev.add("CPU utilization shows an upward trend of +" + formatDouble(trends.getCpuSlopePerMinute()) + "%/min.");
            }
            double conf = tickElevated ? 0.88 : 0.72;
            findings.add(new DiagnosticFinding(
                    nextId("CPU"),
                    now,
                    sev,
                    BottleneckCategory.CPU_BOUND,
                    metrics,
                    ev,
                    conf,
                    "Execute '/os profile start' during peak load to capture a call-tree flamegraph and isolate hot methods on the server thread.",
                    "Ensure the host CPU has high single-core clock speed, avoid CPU noisy-neighbor contention, and enable internal adaptive monitoring sampling.",
                    true
            ));
        }

        // 2. Memory Pressure (evaluate post-GC OldGen occupancy first when available to avoid YoungGen sawtooth noise)
        double heapPct = s.getHeapUsagePercent();
        double postGcPct = s.getPostGcHeapUsagePercent();
        double effectiveHeapPct = s.getPostGcHeapMaxBytes() > 0L ? postGcPct : heapPct;
        if (effectiveHeapPct >= heapWarn || heapPct >= heapCrit) {
            Severity sev = (effectiveHeapPct >= heapCrit || heapPct >= heapCrit) ? Severity.CRITICAL : Severity.HIGH;
            Map<String, String> metrics = new LinkedHashMap<String, String>();
            metrics.put("heapUsagePercent", formatDouble(heapPct));
            metrics.put("postGcOldGenUsagePercent", formatDouble(postGcPct));
            metrics.put("heapUsedMb", String.valueOf(s.getHeapUsedBytes() / (1024L * 1024L)));
            metrics.put("heapMaxMb", String.valueOf(s.getHeapMaxBytes() / (1024L * 1024L)));

            List<String> ev = new ArrayList<String>();
            ev.add("Heap utilization is at " + formatDouble(heapPct) + "% ("
                    + (s.getHeapUsedBytes() / (1024L * 1024L)) + " MB / "
                    + (s.getHeapMaxBytes() / (1024L * 1024L)) + " MB).");
            if (s.getPostGcHeapMaxBytes() > 0L) {
                ev.add("Post-GC Old-Generation retention is " + formatDouble(postGcPct) + "% ("
                        + (s.getPostGcHeapUsedBytes() / (1024L * 1024L)) + " MB / "
                        + (s.getPostGcHeapMaxBytes() / (1024L * 1024L)) + " MB).");
            }
            if (trends != null && trends.getHeapGrowthPercentPerMinute() > 1.5) {
                ev.add("Sustained heap growth of +" + formatDouble(trends.getHeapGrowthPercentPerMinute())
                        + "%/min detected across recent snapshots.");
            }
            double conf = effectiveHeapPct >= heapCrit ? 0.92 : 0.80;
            findings.add(new DiagnosticFinding(
                    nextId("MEM"),
                    now,
                    sev,
                    BottleneckCategory.MEMORY_PRESSURE,
                    metrics,
                    ev,
                    conf,
                    "Run '/os profile memory' and '/os capacity' to inspect heap retainers and live player memory headroom.",
                    "Verify -Xms matches -Xmx, run '/os tune' to apply non-gameplay chunk-GC settings, and allocate adequate heap headroom if host RAM allows.",
                    true
            ));
        }

        // 3. Garbage Collection Pressure
        if (s.getRecentGcPauseRateMsPerSec() >= gcWarn) {
            Severity sev = s.getRecentGcPauseRateMsPerSec() >= gcCrit ? Severity.CRITICAL : Severity.HIGH;
            Map<String, String> metrics = new LinkedHashMap<String, String>();
            metrics.put("gcPauseRateMsPerSec", formatDouble(s.getRecentGcPauseRateMsPerSec()));
            metrics.put("gcTotalCount", String.valueOf(s.getGcTotalCount()));
            metrics.put("gcTotalTimeMs", String.valueOf(s.getGcTotalTimeMs()));
            metrics.put("p99Mspt", formatDouble(s.getP99Mspt()));

            List<String> ev = new ArrayList<String>();
            ev.add("Garbage collector pause overhead is " + formatDouble(s.getRecentGcPauseRateMsPerSec())
                    + " ms/sec (cumulative GC time: " + s.getGcTotalTimeMs() + " ms across " + s.getGcTotalCount() + " collections).");
            if (s.getP99Mspt() > s.getP50Mspt() * 1.8) {
                ev.add("Tail tick latency (P99 MSPT: " + formatDouble(s.getP99Mspt())
                        + " ms vs P50: " + formatDouble(s.getP50Mspt()) + " ms) aligns with stop-the-world GC pauses.");
            }
            double conf = s.getP99Mspt() > 55.0 ? 0.90 : 0.78;
            findings.add(new DiagnosticFinding(
                    nextId("GC"),
                    now,
                    sev,
                    BottleneckCategory.GC_PRESSURE,
                    metrics,
                    ev,
                    conf,
                    "Inspect GC collector flags via '/os memory' and check allocation rates during tick spikes.",
                    "Adopt Aikar's G1GC tuning flags (or Generational ZGC on Java 21+ with large heaps) and ensure +DisableExplicitGC is set.",
                    true
            ));
        }

        // 4. Chunk-Processing Bottlenecks
        if (s.getLoadedChunks() >= chunkWarn
                || s.getChunkLoadRatePerSec() >= chunkLoadWarn
                || s.getChunkGenRatePerSec() >= chunkGenWarn) {
            Severity sev = (s.getChunkGenRatePerSec() >= chunkGenWarn && tickElevated) ? Severity.CRITICAL
                    : (tickElevated ? Severity.HIGH : Severity.MEDIUM);
            Map<String, String> metrics = new LinkedHashMap<String, String>();
            metrics.put("loadedChunks", String.valueOf(s.getLoadedChunks()));
            metrics.put("chunkLoadRatePerSec", formatDouble(s.getChunkLoadRatePerSec()));
            metrics.put("chunkGenRatePerSec", formatDouble(s.getChunkGenRatePerSec()));
            metrics.put("averageMspt", formatDouble(s.getAverageMspt()));

            List<String> ev = new ArrayList<String>();
            if (s.getChunkGenRatePerSec() >= chunkGenWarn) {
                ev.add("Active terrain generation rate is " + formatDouble(s.getChunkGenRatePerSec()) + " new chunks/sec.");
            }
            if (s.getChunkLoadRatePerSec() >= chunkLoadWarn) {
                ev.add("High chunk loading velocity: " + formatDouble(s.getChunkLoadRatePerSec()) + " chunks/sec.");
            }
            if (s.getLoadedChunks() >= chunkWarn) {
                ev.add("Total loaded chunks across worlds is elevated (" + s.getLoadedChunks() + " chunks).");
            }
            double conf = (s.getChunkGenRatePerSec() >= chunkGenWarn && tickElevated) ? 0.91 : (tickElevated ? 0.82 : 0.68);
            findings.add(new DiagnosticFinding(
                    nextId("CHK"),
                    now,
                    sev,
                    BottleneckCategory.CHUNK_PROCESSING,
                    metrics,
                    ev,
                    conf,
                    "Use '/os chunks status' and '/os chunks recommend' to inspect world chunk distribution and active exploration.",
                    "Schedule off-peak world pre-generation via Chunky ('/os chunks recommend') so players load existing region files rather than generating terrain live.",
                    false
            ));
        }

        // 5. Entity-Processing Bottlenecks
        if (s.getTotalEntities() >= entityWarn || s.getTileEntityCount() >= tileWarn) {
            Severity sev = (s.getTotalEntities() >= entityCrit && tickElevated) ? Severity.CRITICAL
                    : (tickElevated ? Severity.HIGH : Severity.MEDIUM);
            Map<String, String> metrics = new LinkedHashMap<String, String>();
            metrics.put("totalEntities", String.valueOf(s.getTotalEntities()));
            metrics.put("tileEntityCount", String.valueOf(s.getTileEntityCount()));
            metrics.put("averageMspt", formatDouble(s.getAverageMspt()));

            List<String> ev = new ArrayList<String>();
            ev.add("Total tracked entities: " + s.getTotalEntities() + " (block/tile entities: " + s.getTileEntityCount() + ").");
            String topType = findDominantKey(s.getEntitiesByType());
            if (topType != null) {
                ev.add("Dominant entity type: " + topType + " (" + s.getEntitiesByType().get(topType) + " instances).");
            }
            String topWorld = findDominantKey(s.getEntitiesByWorld());
            if (topWorld != null) {
                ev.add("Highest concentration in world '" + topWorld + "' (" + s.getEntitiesByWorld().get(topWorld) + " entities).");
            }
            if (tickElevated) {
                ev.add("Elevated MSPT (" + formatDouble(s.getAverageMspt()) + " ms) correlates with high entity/block-entity density.");
            }
            double conf = tickElevated ? 0.84 : 0.66;
            findings.add(new DiagnosticFinding(
                    nextId("ENT"),
                    now,
                    sev,
                    BottleneckCategory.ENTITY_PROCESSING,
                    metrics,
                    ev,
                    conf,
                    "Run '/os entities' and '/os diagnose entities' to inspect per-world and per-type concentration, then profile with '/os profile start'.",
                    "Inspect dense farms or hopper/tile-entity clusters in '" + (topWorld != null ? topWorld : "active worlds")
                            + "'. OptimizeServer preserves vanilla entity mechanics and will not delete entities or disable AI.",
                    false
            ));
        }

        // 6. Plugin-Task Overhead
        boolean growingSyncLeak = bundle.getGrowingTaskPlugin() != null && bundle.getGrowingTaskPluginStreak() >= 3;
        if (s.getPendingServerTasks() >= taskWarn || s.getActiveServerWorkers() >= 32 || growingSyncLeak) {
            Severity sev = (tickElevated || growingSyncLeak) ? Severity.HIGH : Severity.MEDIUM;
            Map<String, String> metrics = new LinkedHashMap<String, String>();
            metrics.put("pendingServerTasks", String.valueOf(s.getPendingServerTasks()));
            metrics.put("activeServerWorkers", String.valueOf(s.getActiveServerWorkers()));

            List<String> ev = new ArrayList<String>();
            ev.add("Scheduler has " + s.getPendingServerTasks() + " pending tasks and "
                    + s.getActiveServerWorkers() + " active worker threads.");
            String topSyncPlugin = findDominantKey(bundle.getPendingSyncTasksByPlugin());
            if (topSyncPlugin != null) {
                ev.add("Highest synchronous main-thread task count belongs to plugin '" + topSyncPlugin + "' ("
                        + bundle.getPendingSyncTasksByPlugin().get(topSyncPlugin) + " sync tasks).");
            } else {
                String topPlugin = findDominantKey(bundle.getPendingTasksByPlugin());
                if (topPlugin != null) {
                    ev.add("Highest scheduled task count belongs to plugin '" + topPlugin + "' ("
                            + bundle.getPendingTasksByPlugin().get(topPlugin)
                            + " tasks). Note: task count indicates scheduling volume, not proven CPU share.");
                }
            }
            if (growingSyncLeak) {
                ev.add("Monotonic growth in synchronous scheduled tasks detected for plugin '"
                        + bundle.getGrowingTaskPlugin() + "' across " + bundle.getGrowingTaskPluginStreak()
                        + " consecutive samples (possible uncancelled task leak).");
            }
            double conf = tickElevated ? 0.76 : 0.62;
            findings.add(new DiagnosticFinding(
                    nextId("TSK"),
                    now,
                    sev,
                    BottleneckCategory.PLUGIN_TASK_OVERHEAD,
                    metrics,
                    ev,
                    conf,
                    "Run '/os diagnose plugins' and start a Spark profiler session ('/os profile start') for exact per-plugin CPU time attribution.",
                    "Review repeating 1-tick synchronous tasks in high-volume plugins and consolidate internal task coordination.",
                    true
            ));
        }

        // 7. Storage Bottlenecks
        if ((s.getTotalDiskSpaceMb() > 0L && s.getFreeDiskSpaceMb() < diskWarnMb)
                || s.getStorageIoLatencyMs() >= ioLatencyWarn) {
            Severity sev = (s.getTotalDiskSpaceMb() > 0L && s.getFreeDiskSpaceMb() < 512L) ? Severity.CRITICAL : Severity.HIGH;
            Map<String, String> metrics = new LinkedHashMap<String, String>();
            metrics.put("freeDiskSpaceMb", String.valueOf(s.getFreeDiskSpaceMb()));
            metrics.put("storageIoLatencyMs", formatDouble(s.getStorageIoLatencyMs()));

            List<String> ev = new ArrayList<String>();
            if (s.getTotalDiskSpaceMb() > 0L && s.getFreeDiskSpaceMb() < diskWarnMb) {
                ev.add("Low available storage capacity: " + s.getFreeDiskSpaceMb() + " MB free of " + s.getTotalDiskSpaceMb() + " MB.");
            }
            if (s.getStorageIoLatencyMs() >= ioLatencyWarn) {
                ev.add("Asynchronous filesystem metadata probe latency is elevated (" + formatDouble(s.getStorageIoLatencyMs()) + " ms).");
            }
            findings.add(new DiagnosticFinding(
                    nextId("DSK"),
                    now,
                    sev,
                    BottleneckCategory.STORAGE_BOTTLENECK,
                    metrics,
                    ev,
                    0.85,
                    "Verify underlying NVMe/SSD I/O utilization and check region file save durations.",
                    "Host world region files on local NVMe storage, ensure adequate free disk headroom, and keep log/report rotation enabled.",
                    false
            ));
        }

        // 8. Network-Processing Overhead
        double tickDelayMs = Math.max(0.0, s.getAverageMspt() - 50.0);
        if (s.getAveragePingMs() >= pingWarn || s.getPacketProcessingOverheadMs() >= 10.0) {
            Severity sev = s.getPacketProcessingOverheadMs() >= 20.0 ? Severity.HIGH : Severity.MEDIUM;
            Map<String, String> metrics = new LinkedHashMap<String, String>();
            metrics.put("onlinePlayers", String.valueOf(s.getOnlinePlayers()));
            metrics.put("averagePingMs", formatDouble(s.getAveragePingMs()));
            metrics.put("maxPingMs", formatDouble(s.getMaxPingMs()));
            metrics.put("packetOverheadMs", formatDouble(s.getPacketProcessingOverheadMs()));
            metrics.put("tickInducedDelayMs", formatDouble(tickDelayMs));

            List<String> ev = new ArrayList<String>();
            ev.add("Average player RTT ping is " + formatDouble(s.getAveragePingMs()) + " ms (max: " + formatDouble(s.getMaxPingMs()) + " ms).");
            ev.add("Measured server packet/event processing overhead is " + formatDouble(s.getPacketProcessingOverheadMs())
                    + " ms/sec, with estimated tick-overrun queuing delay of " + formatDouble(tickDelayMs) + " ms.");
            ev.add("Distinction: Physical network round-trip latency is governed by client-to-host routing and cannot be lowered by a plugin; only server-side tick/packet delay is actionable on-host.");
            findings.add(new DiagnosticFinding(
                    nextId("NET"),
                    now,
                    sev,
                    BottleneckCategory.NETWORK_PROCESSING,
                    metrics,
                    ev,
                    0.79,
                    "Run '/os network' to compare physical RTT latency against server tick-induced delay.",
                    "If tick-induced delay is >0 ms, resolve server MSPT bottlenecks; if RTT ping is high for all players while MSPT <50 ms, inspect host network routing or proxy placement.",
                    false
            ));
        }

        // 9. Unknown or Insufficient Evidence Fallback
        if (tickElevated && findings.isEmpty()) {
            Map<String, String> metrics = new LinkedHashMap<String, String>();
            metrics.put("averageMspt", formatDouble(s.getAverageMspt()));
            metrics.put("p95Mspt", formatDouble(s.getP95Mspt()));
            metrics.put("p99Mspt", formatDouble(s.getP99Mspt()));
            metrics.put("tps1m", formatDouble(s.getTps1m()));

            List<String> ev = new ArrayList<String>();
            ev.add("Tick duration is elevated (Avg MSPT: " + formatDouble(s.getAverageMspt()) + " ms, 1m TPS: "
                    + formatDouble(s.getTps1m()) + "), but CPU, heap, GC, chunk, entity, task, and storage indicators are all within normal thresholds.");
            if (anomalies != null) {
                for (AnomalyDetector.AnomalySignal sig : anomalies) {
                    ev.add("Anomaly signal: " + sig.getDescription());
                }
            }
            ev.add("Causal attribution requires stack-trace sampling rather than metric correlation alone.");

            findings.add(new DiagnosticFinding(
                    nextId("UNK"),
                    now,
                    s.getAverageMspt() >= msptCrit ? Severity.HIGH : Severity.MEDIUM,
                    BottleneckCategory.UNKNOWN_OR_INSUFFICIENT_EVIDENCE,
                    metrics,
                    ev,
                    0.60,
                    "Run '/os profile start' for 60 seconds during the slowdown to capture method-level stack frames.",
                    "Avoid speculative configuration changes until profiler stack traces identify the blocking call path (e.g., synchronous network/database I/O on the main thread).",
                    false
            ));
        }

        return findings;
    }

    private String nextId(String prefix) {
        return "OS-" + prefix + "-" + findingSequence.incrementAndGet();
    }

    private static String findDominantKey(Map<String, Integer> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        String bestKey = null;
        int bestVal = -1;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (entry.getValue() != null && entry.getValue().intValue() > bestVal) {
                bestVal = entry.getValue().intValue();
                bestKey = entry.getKey();
            }
        }
        return bestKey;
    }

    private static String formatDouble(double val) {
        return String.format(Locale.ROOT, "%.2f", val);
    }
}

