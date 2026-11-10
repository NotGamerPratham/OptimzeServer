package com.pratham.optimizeserver.compatibility;

/**
 * Immutable snapshot of runtime API capabilities available on the active server and JVM.
 */
public final class PlatformCapabilities {

    private final boolean paperTpsApi;
    private final boolean paperMsptApi;
    private final boolean paperTickTimesApi;
    private final boolean foliaRegionSchedulers;
    private final boolean entitySchedulerApi;
    private final boolean playerGetPingApi;
    private final boolean spigotPingApi;
    private final boolean simulationDistanceApi;
    private final boolean tileEntitiesApi;
    private final boolean processCpuLoadSupported;
    private final boolean threadAllocationSupported;
    private final boolean sparkPresent;
    private final boolean sparkBundledByServer;
    private final boolean chunkyPresent;

    public PlatformCapabilities(boolean paperTpsApi,
                                boolean paperMsptApi,
                                boolean paperTickTimesApi,
                                boolean foliaRegionSchedulers,
                                boolean entitySchedulerApi,
                                boolean playerGetPingApi,
                                boolean spigotPingApi,
                                boolean simulationDistanceApi,
                                boolean tileEntitiesApi,
                                boolean processCpuLoadSupported,
                                boolean threadAllocationSupported,
                                boolean sparkPresent,
                                boolean sparkBundledByServer,
                                boolean chunkyPresent) {
        this.paperTpsApi = paperTpsApi;
        this.paperMsptApi = paperMsptApi;
        this.paperTickTimesApi = paperTickTimesApi;
        this.foliaRegionSchedulers = foliaRegionSchedulers;
        this.entitySchedulerApi = entitySchedulerApi;
        this.playerGetPingApi = playerGetPingApi;
        this.spigotPingApi = spigotPingApi;
        this.simulationDistanceApi = simulationDistanceApi;
        this.tileEntitiesApi = tileEntitiesApi;
        this.processCpuLoadSupported = processCpuLoadSupported;
        this.threadAllocationSupported = threadAllocationSupported;
        this.sparkPresent = sparkPresent;
        this.sparkBundledByServer = sparkBundledByServer;
        this.chunkyPresent = chunkyPresent;
    }

    public boolean hasPaperTpsApi() {
        return paperTpsApi;
    }

    public boolean hasPaperMsptApi() {
        return paperMsptApi;
    }

    public boolean hasPaperTickTimesApi() {
        return paperTickTimesApi;
    }

    public boolean hasFoliaRegionSchedulers() {
        return foliaRegionSchedulers;
    }

    public boolean hasEntitySchedulerApi() {
        return entitySchedulerApi;
    }

    public boolean hasPlayerGetPingApi() {
        return playerGetPingApi;
    }

    public boolean hasSpigotPingApi() {
        return spigotPingApi;
    }

    public boolean hasSimulationDistanceApi() {
        return simulationDistanceApi;
    }

    public boolean hasTileEntitiesApi() {
        return tileEntitiesApi;
    }

    public boolean isProcessCpuLoadSupported() {
        return processCpuLoadSupported;
    }

    public boolean isThreadAllocationSupported() {
        return threadAllocationSupported;
    }

    public boolean isSparkPresent() {
        return sparkPresent;
    }

    public boolean isSparkBundledByServer() {
        return sparkBundledByServer;
    }

    public boolean isChunkyPresent() {
        return chunkyPresent;
    }
}
