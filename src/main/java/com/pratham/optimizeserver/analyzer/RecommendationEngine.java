package com.pratham.optimizeserver.analyzer;

import com.pratham.optimizeserver.compatibility.CompatibilityReport;
import com.pratham.optimizeserver.compatibility.JavaRuntimeInfo;
import com.pratham.optimizeserver.metrics.MetricSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Generates evidence-backed manual infrastructure and configuration recommendations
 * across JVM flags, heap sizing, CPU resources, storage, network, and server configuration.
 * Never applies external or infrastructure changes automatically.
 */
public final class RecommendationEngine {

    public static final class InfrastructureRecommendation {
        private final String domain;
        private final String summary;
        private final String rationale;
        private final String actionItem;

        public InfrastructureRecommendation(String domain, String summary, String rationale, String actionItem) {
            this.domain = domain;
            this.summary = summary;
            this.rationale = rationale;
            this.actionItem = actionItem;
        }

        public String getDomain() {
            return domain;
        }

        public String getSummary() {
            return summary;
        }

        public String getRationale() {
            return rationale;
        }

        public String getActionItem() {
            return actionItem;
        }
    }

    public List<InfrastructureRecommendation> generateRecommendations(EvidenceCollector.EvidenceBundle bundle,
                                                                      List<DiagnosticFinding> findings) {
        List<InfrastructureRecommendation> list = new ArrayList<InfrastructureRecommendation>();
        if (bundle == null) {
            return list;
        }

        MetricSnapshot s = bundle.getCurrent();
        CompatibilityReport compat = bundle.getCompatibilityReport();
        JavaRuntimeInfo jvm = compat != null ? compat.getJavaRuntime() : null;

        // 1. JVM Arguments
        if (jvm != null) {
            if (!jvm.hasExplicitDisableExplicitGC()) {
                list.add(new InfrastructureRecommendation(
                        "JVM Arguments",
                        "Add -XX:+DisableExplicitGC to JVM startup flags",
                        "Prevents third-party plugins or RMI timers from invoking System.gc() and causing stop-the-world tick spikes.",
                        "Append '-XX:+DisableExplicitGC -XX:+AlwaysPreTouch' to your server startup script."
                ));
            }
            if (!jvm.isUsingG1GC() && !jvm.isUsingZGC()) {
                list.add(new InfrastructureRecommendation(
                        "JVM Arguments",
                        "Configure a low-pause garbage collector (G1GC or Generational ZGC)",
                        "Active collectors " + jvm.getCollectorNames() + " may introduce longer stop-the-world pauses under chunk/entity load.",
                        "Use Aikar's G1GC flags (-XX:+UseG1GC -XX:MaxGCPauseMillis=130 -XX:G1NewSizePercent=30) or Generational ZGC on Java 21+ with >=16GB RAM."
                ));
            }
        }

        // 2. Heap Configuration
        if (jvm != null && !jvm.hasMatchingXmsXmx()) {
            list.add(new InfrastructureRecommendation(
                    "Heap Configuration",
                    "Match initial heap (-Xms) and maximum heap (-Xmx)",
                    "Dynamic heap resizing during live gameplay causes OS page faults and GC pauses.",
                    "Set -Xms equal to -Xmx in your startup flags and leave 1.5GB-2GB of host RAM for off-heap Metaspace/Netty buffers."
            ));
        }
        if (s != null && s.getHeapUsagePercent() > 82.0) {
            long maxMb = s.getHeapMaxBytes() / (1024L * 1024L);
            list.add(new InfrastructureRecommendation(
                    "Heap Configuration",
                    "Evaluate heap headroom (currently at " + String.format(Locale.ROOT, "%.1f%%", s.getHeapUsagePercent()) + " of " + maxMb + " MB)",
                    "High sustained heap occupancy increases GC frequency and old-generation promotion pressure.",
                    "Inspect heap retainers via '/os profile memory' or increase -Xms/-Xmx if physical host RAM permits."
            ));
        }

        // 3. CPU Resources
        if (s != null && (s.getAvailableProcessors() < 4 || s.getProcessCpuLoadPercent() > 75.0)) {
            list.add(new InfrastructureRecommendation(
                    "CPU Resources",
                    "Prioritize high single-thread IPC and dedicated physical cores",
                    "Detected " + s.getAvailableProcessors() + " logical processors with "
                            + String.format(Locale.ROOT, "%.1f%%", s.getProcessCpuLoadPercent()) + " process CPU load.",
                    "Provision at least 4 dedicated high-frequency cores for the main tick thread, async chunk workers, Netty I/O, and GC threads."
            ));
        }

        // 4. Storage Infrastructure
        if (s != null && (s.getStorageIoLatencyMs() > 15.0 || (s.getTotalDiskSpaceMb() > 0 && s.getFreeDiskSpaceMb() < 4096))) {
            list.add(new InfrastructureRecommendation(
                    "Storage Infrastructure",
                    "Ensure low-latency NVMe storage and sufficient free disk capacity",
                    "Measured filesystem probe latency: " + String.format(Locale.ROOT, "%.2f ms", s.getStorageIoLatencyMs())
                            + ", free space: " + s.getFreeDiskSpaceMb() + " MB.",
                    "Store world region files on local NVMe SSDs and maintain at least 15% free disk capacity to avoid filesystem fragmentation."
            ));
        }

        // 5. Network Infrastructure
        if (s != null && (s.getAveragePingMs() > 150.0 || s.getPacketProcessingOverheadMs() > 8.0)) {
            list.add(new InfrastructureRecommendation(
                    "Network Infrastructure",
                    "Separate physical routing latency from server-side packet compression overhead",
                    "Average player RTT is " + String.format(Locale.ROOT, "%.1f ms", s.getAveragePingMs())
                            + " and packet processing overhead is " + String.format(Locale.ROOT, "%.2f ms/s", s.getPacketProcessingOverheadMs()) + ".",
                    "If running behind a reverse proxy (Velocity/BungeeCord), offload packet compression to the proxy and set network-compression-threshold=-1 on backend servers."
            ));
        }

        // 6. Server Configuration
        if (s != null && (s.getChunkGenRatePerSec() > 5.0 || s.getLoadedChunks() > 2500)) {
            list.add(new InfrastructureRecommendation(
                    "Server Configuration",
                    "Pre-generate active world regions during off-peak hours",
                    "Active loaded chunks: " + s.getLoadedChunks() + " (generation rate: "
                            + String.format(Locale.ROOT, "%.1f chunks/s", s.getChunkGenRatePerSec()) + ").",
                    "Use '/os chunks recommend' to review safe Chunky pre-generation parameters without altering vanilla view or simulation distances."
            ));
        }

        // Ensure at least one baseline recommendation per category is available on request
        if (list.isEmpty()) {
            list.add(new InfrastructureRecommendation(
                    "Server Configuration",
                    "Server operating within healthy performance margins",
                    "All monitored TPS, MSPT, CPU, memory, GC, chunk, entity, and storage indicators are currently nominal.",
                    "Maintain periodic diagnostic snapshots via '/os report generate' before major plugin or version updates."
            ));
        }

        return Collections.unmodifiableList(list);
    }
}

