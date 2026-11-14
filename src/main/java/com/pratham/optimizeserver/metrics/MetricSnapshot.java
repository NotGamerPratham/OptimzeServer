package com.pratham.optimizeserver.metrics;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable point-in-time snapshot of all collected server and JVM performance metrics.
 */
public final class MetricSnapshot {

    private final long timestampMillis;
    private final long monotonicNanos;

    // Tick & TPS Metrics
    private final double tps5s;
    private final double tps30s;
    private final double tps1m;
    private final double tps5m;
    private final double tps15m;
    private final double averageMspt;
    private final double p50Mspt;
    private final double p95Mspt;
    private final double p99Mspt;
    private final double msptStdDev;

    // CPU Metrics
    private final double processCpuLoadPercent;
    private final double systemCpuLoadPercent;
    private final int availableProcessors;

    // Memory & GC Metrics
    private final long heapUsedBytes;
    private final long heapCommittedBytes;
    private final long heapMaxBytes;
    private final long postGcHeapUsedBytes;
    private final long postGcHeapMaxBytes;
    private final long nonHeapUsedBytes;
    private final long gcTotalCount;
    private final long gcTotalTimeMs;
    private final double recentGcPauseRateMsPerSec;
    private final long recentAllocatedBytesPerSec;

    // Network Metrics
    private final int onlinePlayers;
    private final double averagePingMs;
    private final double maxPingMs;
    private final double packetProcessingOverheadMs;
    private final double incomingEventsPerSec;
    private final double outgoingEventsPerSec;

    // Chunk Metrics
    private final int loadedChunks;
    private final double chunkLoadRatePerSec;
    private final double chunkUnloadRatePerSec;
    private final double chunkGenRatePerSec;

    // Entity Metrics
    private final int totalEntities;
    private final int tileEntityCount;
    private final Map<String, Integer> entitiesByType;
    private final Map<String, Integer> entitiesByWorld;

    // Task & Storage Metrics
    private final int pendingServerTasks;
    private final int activeServerWorkers;
    private final long freeDiskSpaceMb;
    private final long totalDiskSpaceMb;
    private final double storageIoLatencyMs;

    private MetricSnapshot(Builder b) {
        this.timestampMillis = b.timestampMillis;
        this.monotonicNanos = b.monotonicNanos;
        this.tps5s = b.tps5s;
        this.tps30s = b.tps30s;
        this.tps1m = b.tps1m;
        this.tps5m = b.tps5m;
        this.tps15m = b.tps15m;
        this.averageMspt = b.averageMspt;
        this.p50Mspt = b.p50Mspt;
        this.p95Mspt = b.p95Mspt;
        this.p99Mspt = b.p99Mspt;
        this.msptStdDev = b.msptStdDev;
        this.processCpuLoadPercent = b.processCpuLoadPercent;
        this.systemCpuLoadPercent = b.systemCpuLoadPercent;
        this.availableProcessors = b.availableProcessors;
        this.heapUsedBytes = b.heapUsedBytes;
        this.heapCommittedBytes = b.heapCommittedBytes;
        this.heapMaxBytes = b.heapMaxBytes;
        this.postGcHeapUsedBytes = b.postGcHeapUsedBytes;
        this.postGcHeapMaxBytes = b.postGcHeapMaxBytes;
        this.nonHeapUsedBytes = b.nonHeapUsedBytes;
        this.gcTotalCount = b.gcTotalCount;
        this.gcTotalTimeMs = b.gcTotalTimeMs;
        this.recentGcPauseRateMsPerSec = b.recentGcPauseRateMsPerSec;
        this.recentAllocatedBytesPerSec = b.recentAllocatedBytesPerSec;
        this.onlinePlayers = b.onlinePlayers;
        this.averagePingMs = b.averagePingMs;
        this.maxPingMs = b.maxPingMs;
        this.packetProcessingOverheadMs = b.packetProcessingOverheadMs;
        this.incomingEventsPerSec = b.incomingEventsPerSec;
        this.outgoingEventsPerSec = b.outgoingEventsPerSec;
        this.loadedChunks = b.loadedChunks;
        this.chunkLoadRatePerSec = b.chunkLoadRatePerSec;
        this.chunkUnloadRatePerSec = b.chunkUnloadRatePerSec;
        this.chunkGenRatePerSec = b.chunkGenRatePerSec;
        this.totalEntities = b.totalEntities;
        this.tileEntityCount = b.tileEntityCount;
        this.entitiesByType = b.entitiesByType != null
                ? Collections.unmodifiableMap(new HashMap<String, Integer>(b.entitiesByType))
                : Collections.<String, Integer>emptyMap();
        this.entitiesByWorld = b.entitiesByWorld != null
                ? Collections.unmodifiableMap(new HashMap<String, Integer>(b.entitiesByWorld))
                : Collections.<String, Integer>emptyMap();
        this.pendingServerTasks = b.pendingServerTasks;
        this.activeServerWorkers = b.activeServerWorkers;
        this.freeDiskSpaceMb = b.freeDiskSpaceMb;
        this.totalDiskSpaceMb = b.totalDiskSpaceMb;
        this.storageIoLatencyMs = b.storageIoLatencyMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public long getTimestampMillis() { return timestampMillis; }
    public long getMonotonicNanos() { return monotonicNanos; }
    public double getTps5s() { return tps5s; }
    public double getTps30s() { return tps30s; }
    public double getTps1m() { return tps1m; }
    public double getTps5m() { return tps5m; }
    public double getTps15m() { return tps15m; }
    public double getAverageMspt() { return averageMspt; }
    public double getP50Mspt() { return p50Mspt; }
    public double getP95Mspt() { return p95Mspt; }
    public double getP99Mspt() { return p99Mspt; }
    public double getMsptStdDev() { return msptStdDev; }
    public double getProcessCpuLoadPercent() { return processCpuLoadPercent; }
    public double getSystemCpuLoadPercent() { return systemCpuLoadPercent; }
    public int getAvailableProcessors() { return availableProcessors; }
    public long getHeapUsedBytes() { return heapUsedBytes; }
    public long getHeapCommittedBytes() { return heapCommittedBytes; }
    public long getHeapMaxBytes() { return heapMaxBytes; }
    public long getPostGcHeapUsedBytes() { return postGcHeapUsedBytes; }
    public long getPostGcHeapMaxBytes() { return postGcHeapMaxBytes; }
    public double getPostGcHeapUsagePercent() {
        if (postGcHeapMaxBytes > 0L) {
            return (postGcHeapUsedBytes * 100.0) / postGcHeapMaxBytes;
        }
        return getHeapUsagePercent();
    }
    public long getNonHeapUsedBytes() { return nonHeapUsedBytes; }
    public double getHeapUsagePercent() {
        return heapMaxBytes > 0 ? (heapUsedBytes * 100.0) / heapMaxBytes : 0.0;
    }
    public long getGcTotalCount() { return gcTotalCount; }
    public long getGcTotalTimeMs() { return gcTotalTimeMs; }
    public double getRecentGcPauseRateMsPerSec() { return recentGcPauseRateMsPerSec; }
    public long getRecentAllocatedBytesPerSec() { return recentAllocatedBytesPerSec; }
    public int getOnlinePlayers() { return onlinePlayers; }
    public double getAveragePingMs() { return averagePingMs; }
    public double getMaxPingMs() { return maxPingMs; }
    public double getPacketProcessingOverheadMs() { return packetProcessingOverheadMs; }
    public double getIncomingEventsPerSec() { return incomingEventsPerSec; }
    public double getOutgoingEventsPerSec() { return outgoingEventsPerSec; }
    public int getLoadedChunks() { return loadedChunks; }
    public double getChunkLoadRatePerSec() { return chunkLoadRatePerSec; }
    public double getChunkUnloadRatePerSec() { return chunkUnloadRatePerSec; }
    public double getChunkGenRatePerSec() { return chunkGenRatePerSec; }
    public int getTotalEntities() { return totalEntities; }
    public int getTileEntityCount() { return tileEntityCount; }
    public Map<String, Integer> getEntitiesByType() { return entitiesByType; }
    public Map<String, Integer> getEntitiesByWorld() { return entitiesByWorld; }
    public int getPendingServerTasks() { return pendingServerTasks; }
    public int getActiveServerWorkers() { return activeServerWorkers; }
    public long getFreeDiskSpaceMb() { return freeDiskSpaceMb; }
    public long getTotalDiskSpaceMb() { return totalDiskSpaceMb; }
    public double getStorageIoLatencyMs() { return storageIoLatencyMs; }

    public static final class Builder {
        private long timestampMillis = System.currentTimeMillis();
        private long monotonicNanos = System.nanoTime();
        private double tps5s = 20.0;
        private double tps30s = 20.0;
        private double tps1m = 20.0;
        private double tps5m = 20.0;
        private double tps15m = 20.0;
        private double averageMspt = 0.0;
        private double p50Mspt = 0.0;
        private double p95Mspt = 0.0;
        private double p99Mspt = 0.0;
        private double msptStdDev = 0.0;
        private double processCpuLoadPercent = 0.0;
        private double systemCpuLoadPercent = 0.0;
        private int availableProcessors = Runtime.getRuntime().availableProcessors();
        private long heapUsedBytes = 0L;
        private long heapCommittedBytes = 0L;
        private long heapMaxBytes = Runtime.getRuntime().maxMemory();
        private long postGcHeapUsedBytes = 0L;
        private long postGcHeapMaxBytes = 0L;
        private long nonHeapUsedBytes = 0L;
        private long gcTotalCount = 0L;
        private long gcTotalTimeMs = 0L;
        private double recentGcPauseRateMsPerSec = 0.0;
        private long recentAllocatedBytesPerSec = 0L;
        private int onlinePlayers = 0;
        private double averagePingMs = 0.0;
        private double maxPingMs = 0.0;
        private double packetProcessingOverheadMs = 0.0;
        private double incomingEventsPerSec = 0.0;
        private double outgoingEventsPerSec = 0.0;
        private int loadedChunks = 0;
        private double chunkLoadRatePerSec = 0.0;
        private double chunkUnloadRatePerSec = 0.0;
        private double chunkGenRatePerSec = 0.0;
        private int totalEntities = 0;
        private int tileEntityCount = 0;
        private Map<String, Integer> entitiesByType;
        private Map<String, Integer> entitiesByWorld;
        private int pendingServerTasks = 0;
        private int activeServerWorkers = 0;
        private long freeDiskSpaceMb = 0L;
        private long totalDiskSpaceMb = 0L;
        private double storageIoLatencyMs = 0.0;

        public Builder timestampMillis(long val) { this.timestampMillis = val; return this; }
        public Builder monotonicNanos(long val) { this.monotonicNanos = val; return this; }
        public Builder tps5s(double val) { this.tps5s = val; return this; }
        public Builder tps30s(double val) { this.tps30s = val; return this; }
        public Builder tps1m(double val) { this.tps1m = val; return this; }
        public Builder tps5m(double val) { this.tps5m = val; return this; }
        public Builder tps15m(double val) { this.tps15m = val; return this; }
        public Builder averageMspt(double val) { this.averageMspt = val; return this; }
        public Builder p50Mspt(double val) { this.p50Mspt = val; return this; }
        public Builder p95Mspt(double val) { this.p95Mspt = val; return this; }
        public Builder p99Mspt(double val) { this.p99Mspt = val; return this; }
        public Builder msptStdDev(double val) { this.msptStdDev = val; return this; }
        public Builder processCpuLoadPercent(double val) { this.processCpuLoadPercent = val; return this; }
        public Builder systemCpuLoadPercent(double val) { this.systemCpuLoadPercent = val; return this; }
        public Builder availableProcessors(int val) { this.availableProcessors = val; return this; }
        public Builder heapUsedBytes(long val) { this.heapUsedBytes = val; return this; }
        public Builder heapCommittedBytes(long val) { this.heapCommittedBytes = val; return this; }
        public Builder heapMaxBytes(long val) { this.heapMaxBytes = val; return this; }
        public Builder postGcHeapUsedBytes(long val) { this.postGcHeapUsedBytes = val; return this; }
        public Builder postGcHeapMaxBytes(long val) { this.postGcHeapMaxBytes = val; return this; }
        public Builder nonHeapUsedBytes(long val) { this.nonHeapUsedBytes = val; return this; }
        public Builder gcTotalCount(long val) { this.gcTotalCount = val; return this; }
        public Builder gcTotalTimeMs(long val) { this.gcTotalTimeMs = val; return this; }
        public Builder recentGcPauseRateMsPerSec(double val) { this.recentGcPauseRateMsPerSec = val; return this; }
        public Builder recentAllocatedBytesPerSec(long val) { this.recentAllocatedBytesPerSec = val; return this; }
        public Builder onlinePlayers(int val) { this.onlinePlayers = val; return this; }
        public Builder averagePingMs(double val) { this.averagePingMs = val; return this; }
        public Builder maxPingMs(double val) { this.maxPingMs = val; return this; }
        public Builder packetProcessingOverheadMs(double val) { this.packetProcessingOverheadMs = val; return this; }
        public Builder incomingEventsPerSec(double val) { this.incomingEventsPerSec = val; return this; }
        public Builder outgoingEventsPerSec(double val) { this.outgoingEventsPerSec = val; return this; }
        public Builder loadedChunks(int val) { this.loadedChunks = val; return this; }
        public Builder chunkLoadRatePerSec(double val) { this.chunkLoadRatePerSec = val; return this; }
        public Builder chunkUnloadRatePerSec(double val) { this.chunkUnloadRatePerSec = val; return this; }
        public Builder chunkGenRatePerSec(double val) { this.chunkGenRatePerSec = val; return this; }
        public Builder totalEntities(int val) { this.totalEntities = val; return this; }
        public Builder tileEntityCount(int val) { this.tileEntityCount = val; return this; }
        public Builder entitiesByType(Map<String, Integer> val) { this.entitiesByType = val; return this; }
        public Builder entitiesByWorld(Map<String, Integer> val) { this.entitiesByWorld = val; return this; }
        public Builder pendingServerTasks(int val) { this.pendingServerTasks = val; return this; }
        public Builder activeServerWorkers(int val) { this.activeServerWorkers = val; return this; }
        public Builder freeDiskSpaceMb(long val) { this.freeDiskSpaceMb = val; return this; }
        public Builder totalDiskSpaceMb(long val) { this.totalDiskSpaceMb = val; return this; }
        public Builder storageIoLatencyMs(double val) { this.storageIoLatencyMs = val; return this; }

        public MetricSnapshot build() {
            return new MetricSnapshot(this);
        }
    }
}

