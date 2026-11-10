package com.pratham.optimizeserver.core;

import com.pratham.optimizeserver.analyzer.DiagnosticCoordinator;
import com.pratham.optimizeserver.compatibility.CompatibilityManager;
import com.pratham.optimizeserver.compatibility.CompatibilityReport;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.integrations.DependencyAutoInstaller;
import com.pratham.optimizeserver.integrations.chunky.ChunkyIntegration;
import com.pratham.optimizeserver.integrations.spark.SparkIntegration;
import com.pratham.optimizeserver.metrics.MetricRegistry;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.metrics.RollingWindow;
import com.pratham.optimizeserver.monitoring.ChunkMonitor;
import com.pratham.optimizeserver.monitoring.CpuMonitor;
import com.pratham.optimizeserver.monitoring.EntityMonitor;
import com.pratham.optimizeserver.monitoring.MemoryMonitor;
import com.pratham.optimizeserver.monitoring.NetworkMonitor;
import com.pratham.optimizeserver.monitoring.StorageMonitor;
import com.pratham.optimizeserver.monitoring.TaskMonitor;
import com.pratham.optimizeserver.monitoring.TickMonitor;
import com.pratham.optimizeserver.optimizer.OptimizationManager;
import com.pratham.optimizeserver.optimizer.OptimizationRegistry;
import com.pratham.optimizeserver.optimizer.OptimizationValidator;
import com.pratham.optimizeserver.optimizer.RollbackManager;
import com.pratham.optimizeserver.reporting.DiagnosticReport;
import com.pratham.optimizeserver.reporting.ReportGenerator;
import com.pratham.optimizeserver.safety.CompatibilityGuard;
import com.pratham.optimizeserver.safety.SafeModeController;
import com.pratham.optimizeserver.safety.SafetyValidator;
import com.pratham.optimizeserver.scheduler.BoundedWorkerPool;
import com.pratham.optimizeserver.scheduler.SchedulerAdapter;
import com.pratham.optimizeserver.scheduler.TaskHandle;
import com.pratham.optimizeserver.scheduler.UniversalScheduler;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Central orchestrator for OptimizeServer monitoring, diagnostics, non-invasive optimization,
 * optional integrations, and reporting.
 */
public final class PerformanceEngine implements EngineLifecycle {

    private final Plugin plugin;
    private final Server server;
    private final File dataFolder;
    private final Logger logger;
    private final ConfigManager configManager;

    private CompatibilityManager compatibilityManager;
    private SafeModeController safeModeController;
    private SafetyValidator safetyValidator;
    private CompatibilityGuard compatibilityGuard;
    private SchedulerAdapter scheduler;
    private MetricRegistry metricRegistry;

    private TickMonitor tickMonitor;
    private CpuMonitor cpuMonitor;
    private MemoryMonitor memoryMonitor;
    private NetworkMonitor networkMonitor;
    private ChunkMonitor chunkMonitor;
    private EntityMonitor entityMonitor;
    private TaskMonitor taskMonitor;
    private StorageMonitor storageMonitor;

    private DiagnosticCoordinator diagnosticCoordinator;
    private OptimizationManager optimizationManager;
    private SparkIntegration sparkIntegration;
    private ChunkyIntegration chunkyIntegration;
    private DependencyAutoInstaller dependencyAutoInstaller;
    private ReportGenerator reportGenerator;

    private TaskHandle tickSamplerHandle;
    private TaskHandle systemSamplerHandle;
    private TaskHandle asyncBackgroundHandle;
    private final AtomicLong tickCounter = new AtomicLong(0L);

    private volatile EngineState state = EngineState.UNINITIALIZED;

    public PerformanceEngine(Plugin plugin,
                             Server server,
                             File dataFolder,
                             Logger logger,
                             ConfigManager configManager) {
        this.plugin = plugin;
        this.server = server;
        this.dataFolder = dataFolder != null ? dataFolder : new File(".");
        this.logger = logger != null ? logger : Logger.getLogger("OptimizeServer");
        this.configManager = configManager;
    }

    @Override
    public synchronized void start() {
        if (state == EngineState.RUNNING || state == EngineState.SAFE_MODE) {
            return;
        }
        this.state = EngineState.STARTING;

        boolean forceSafeOnUnknown = configManager == null
                || configManager.getBoolean("compatibility.force-safe-mode-on-unknown-platform", true);
        this.compatibilityManager = new CompatibilityManager(server, forceSafeOnUnknown);
        CompatibilityReport compatReport = compatibilityManager.getReport();

        String configuredMode = configManager != null
                ? configManager.getString("optimization.mode", "HYBRID_CONDITIONAL")
                : "HYBRID_CONDITIONAL";
        boolean initialSafeMode = compatReport.isConservativeSafeModeRequired()
                || "SAFE_MODE".equalsIgnoreCase(configuredMode);
        String safeReason = compatReport.isConservativeSafeModeRequired()
                ? "Platform compatibility policy"
                : ("SAFE_MODE".equalsIgnoreCase(configuredMode) ? "Configured optimization.mode=SAFE_MODE" : null);

        this.safeModeController = new SafeModeController(initialSafeMode, safeReason);
        this.safetyValidator = new SafetyValidator();
        this.compatibilityGuard = new CompatibilityGuard(compatReport);

        int coreThreads = configManager != null ? configManager.getInt("workers.async-core-threads", 2) : 2;
        int maxThreads = configManager != null ? configManager.getInt("workers.async-max-threads", 4) : 4;
        int queueCap = configManager != null ? configManager.getInt("workers.queue-capacity", 256) : 256;
        long keepAlive = configManager != null ? configManager.getLong("workers.keep-alive-seconds", 60L) : 60L;

        BoundedWorkerPool workerPool = new BoundedWorkerPool(logger, coreThreads, maxThreads, queueCap, keepAlive);
        this.scheduler = new UniversalScheduler(
                plugin,
                server,
                compatReport.getPlatform(),
                compatReport.getCapabilities(),
                workerPool
        );

        int maxSnapshots = configManager != null ? configManager.getInt("monitoring.retention.max-snapshots", 900) : 900;
        int bufferPoolCap = configManager != null
                ? configManager.getInt("optimization.modules.monitoring-buffer-pool.pool-capacity", 64)
                : 64;
        this.metricRegistry = new MetricRegistry(maxSnapshots, bufferPoolCap);

        this.tickMonitor = new TickMonitor(server, compatReport.getCapabilities(), metricRegistry);
        this.cpuMonitor = new CpuMonitor();
        this.memoryMonitor = new MemoryMonitor();
        this.networkMonitor = new NetworkMonitor(server, compatReport.getCapabilities());
        this.chunkMonitor = new ChunkMonitor(server, scheduler);
        this.entityMonitor = new EntityMonitor(server, scheduler, compatReport.getCapabilities());
        this.taskMonitor = new TaskMonitor(server, scheduler);
        this.storageMonitor = new StorageMonitor(dataFolder);

        this.diagnosticCoordinator = new DiagnosticCoordinator();

        OptimizationRegistry optRegistry = new OptimizationRegistry();
        OptimizationValidator optValidator = new OptimizationValidator(safetyValidator, compatibilityGuard, safeModeController);
        RollbackManager rollbackManager = new RollbackManager(logger);
        this.optimizationManager = new OptimizationManager(
                optRegistry,
                optValidator,
                rollbackManager,
                configManager,
                metricRegistry,
                chunkMonitor,
                entityMonitor,
                diagnosticCoordinator,
                workerPool,
                logger
        );
        this.optimizationManager.initializeConfiguredModules();

        this.sparkIntegration = new SparkIntegration(server, compatReport);
        this.chunkyIntegration = new ChunkyIntegration(server, configManager);

        File pluginsDir = dataFolder.getParentFile() != null ? dataFolder.getParentFile() : new File(dataFolder, "plugins");
        this.dependencyAutoInstaller = new DependencyAutoInstaller(
                server,
                pluginsDir,
                configManager,
                compatReport,
                scheduler,
                sparkIntegration,
                chunkyIntegration,
                logger
        );
        if (plugin != null) {
            this.dependencyAutoInstaller.checkAndInstallMissingAsync();
        }

        this.reportGenerator = new ReportGenerator(dataFolder, configManager);

        // Capture initial baseline snapshot immediately so commands and diagnostics always have valid data
        captureImmediateSnapshot();
        scheduleMonitoringTasks();

        this.state = safeModeController.isSafeModeActive() ? EngineState.SAFE_MODE : EngineState.RUNNING;

        if (configManager == null || configManager.getBoolean("compatibility.emit-compatibility-warnings", true)) {
            for (String warn : compatReport.getWarnings()) {
                logger.warning("[OptimizeServer] Compatibility Warning: " + warn);
            }
            for (String fb : compatReport.getActiveFallbacks()) {
                logger.info("[OptimizeServer] Compatibility Fallback: " + fb);
            }
        }
    }

    private void scheduleMonitoringTasks() {
        if (configManager != null && !configManager.getBoolean("monitoring.enabled", true)) {
            return;
        }
        final int tickInterval = configManager != null
                ? Math.max(1, configManager.getInt("monitoring.sampling.tick-sample-interval-ticks", 1))
                : 1;
        final int sysInterval = configManager != null
                ? Math.max(5, configManager.getInt("monitoring.sampling.system-sample-interval-ticks", 20))
                : 20;
        final long bgIntervalMs = configManager != null
                ? Math.max(1000L, configManager.getLong("monitoring.sampling.background-sample-interval-ms", 5000L))
                : 5000L;

        this.tickSamplerHandle = scheduler.runGlobalTimer(new Runnable() {
            @Override
            public void run() {
                tickMonitor.onServerTick(tickInterval);
                long currentTick = tickCounter.incrementAndGet() * tickInterval;
                int worldInterval = optimizationManager.getEffectiveWorldSampleIntervalTicks();
                if (currentTick % worldInterval == 0) {
                    long nowNanos = System.nanoTime();
                    chunkMonitor.sampleMainThread(nowNanos);
                    entityMonitor.sampleMainThread(nowNanos);
                }
            }
        }, 1L, tickInterval);

        this.systemSamplerHandle = scheduler.runGlobalTimer(new Runnable() {
            @Override
            public void run() {
                long nowNanos = System.nanoTime();
                tickMonitor.refreshAggregates(nowNanos);
                networkMonitor.sampleMainThread(nowNanos);
                taskMonitor.sample();

                // Assemble and publish immutable MetricSnapshot asynchronously
                scheduler.runAsync(new Runnable() {
                    @Override
                    public void run() {
                        long asyncNanos = System.nanoTime();
                        cpuMonitor.sample(asyncNanos);
                        memoryMonitor.sample(asyncNanos);
                        MetricSnapshot snap = buildSnapshot(asyncNanos);
                        metricRegistry.getHistory().append(snap);
                        optimizationManager.evaluateConditionalModules(snap);
                    }
                });
            }
        }, sysInterval, sysInterval);

        this.asyncBackgroundHandle = scheduler.runAsyncTimer(new Runnable() {
            @Override
            public void run() {
                storageMonitor.sampleAsync();
            }
        }, 500L, bgIntervalMs);
    }

    /**
     * Captures an immediate synchronous + non-blocking snapshot (used on startup, reload, and tests).
     */
    public MetricSnapshot captureImmediateSnapshot() {
        long nowNanos = System.nanoTime();
        tickMonitor.refreshAggregates(nowNanos);
        cpuMonitor.sample(nowNanos);
        memoryMonitor.sample(nowNanos);
        networkMonitor.sampleMainThread(nowNanos);
        chunkMonitor.sampleMainThread(nowNanos);
        entityMonitor.sampleMainThread(nowNanos);
        taskMonitor.sample();
        storageMonitor.sampleAsync();
        MetricSnapshot snap = buildSnapshot(nowNanos);
        metricRegistry.getHistory().append(snap);
        optimizationManager.evaluateConditionalModules(snap);
        return snap;
    }

    private MetricSnapshot buildSnapshot(long nowNanos) {
        RollingWindow.WindowStats msptStats = tickMonitor.getLatestMsptStats();
        return MetricSnapshot.builder()
                .timestampMillis(System.currentTimeMillis())
                .monotonicNanos(nowNanos)
                .tps5s(tickMonitor.getTps5s())
                .tps30s(tickMonitor.getTps30s())
                .tps1m(tickMonitor.getTps1m())
                .tps5m(tickMonitor.getTps5m())
                .tps15m(tickMonitor.getTps15m())
                .averageMspt(msptStats.getAverage())
                .p50Mspt(msptStats.getP50())
                .p95Mspt(msptStats.getP95())
                .p99Mspt(msptStats.getP99())
                .msptStdDev(msptStats.getStdDev())
                .processCpuLoadPercent(cpuMonitor.getLatestProcessCpuPercent())
                .systemCpuLoadPercent(cpuMonitor.getLatestSystemCpuPercent())
                .availableProcessors(cpuMonitor.getAvailableProcessors())
                .heapUsedBytes(memoryMonitor.getHeapUsedBytes())
                .heapCommittedBytes(memoryMonitor.getHeapCommittedBytes())
                .heapMaxBytes(memoryMonitor.getHeapMaxBytes())
                .nonHeapUsedBytes(memoryMonitor.getNonHeapUsedBytes())
                .gcTotalCount(memoryMonitor.getGcTotalCount())
                .gcTotalTimeMs(memoryMonitor.getGcTotalTimeMs())
                .recentGcPauseRateMsPerSec(memoryMonitor.getRecentGcPauseRateMsPerSec())
                .recentAllocatedBytesPerSec(memoryMonitor.getRecentAllocatedBytesPerSec())
                .onlinePlayers(networkMonitor.getOnlinePlayers())
                .averagePingMs(networkMonitor.getAveragePingMs())
                .maxPingMs(networkMonitor.getMaxPingMs())
                .packetProcessingOverheadMs(networkMonitor.getPacketProcessingOverheadMs())
                .incomingEventsPerSec(networkMonitor.getIncomingEventsPerSec())
                .outgoingEventsPerSec(networkMonitor.getOutgoingEventsPerSec())
                .loadedChunks(chunkMonitor.getLoadedChunks())
                .chunkLoadRatePerSec(chunkMonitor.getChunkLoadRatePerSec())
                .chunkUnloadRatePerSec(chunkMonitor.getChunkUnloadRatePerSec())
                .chunkGenRatePerSec(chunkMonitor.getChunkGenRatePerSec())
                .totalEntities(entityMonitor.getTotalEntities())
                .tileEntityCount(entityMonitor.getTileEntityCount())
                .entitiesByType(entityMonitor.getEntitiesByType())
                .entitiesByWorld(entityMonitor.getEntitiesByWorld())
                .pendingServerTasks(taskMonitor.getPendingServerTasks())
                .activeServerWorkers(taskMonitor.getActiveServerWorkers())
                .freeDiskSpaceMb(storageMonitor.getFreeDiskSpaceMb())
                .totalDiskSpaceMb(storageMonitor.getTotalDiskSpaceMb())
                .storageIoLatencyMs(storageMonitor.getStorageIoLatencyMs())
                .build();
    }

    @Override
    public synchronized void reload() {
        if (configManager != null) {
            configManager.loadAndValidate();
        }
        if (diagnosticCoordinator != null) {
            diagnosticCoordinator.invalidateCache();
        }
        if (optimizationManager != null) {
            optimizationManager.initializeConfiguredModules();
        }
        if (sparkIntegration != null) {
            sparkIntegration.detect();
        }
        if (chunkyIntegration != null) {
            chunkyIntegration.detect();
        }
        if (reportGenerator != null) {
            reportGenerator.appendToRotatingActivityLog("Reloaded OptimizeServer configuration and re-validated modules.");
        }
    }

    @Override
    public synchronized void stop() {
        if (state == EngineState.STOPPED || state == EngineState.STOPPING) {
            return;
        }
        this.state = EngineState.STOPPING;
        if (tickSamplerHandle != null) tickSamplerHandle.cancel();
        if (systemSamplerHandle != null) systemSamplerHandle.cancel();
        if (asyncBackgroundHandle != null) asyncBackgroundHandle.cancel();

        long shutdownTimeout = configManager != null
                ? configManager.getLong("workers.shutdown-timeout-seconds", 10L)
                : 10L;
        if (scheduler != null) {
            scheduler.shutdown(shutdownTimeout);
        }
        this.state = EngineState.STOPPED;
    }

    @Override
    public EngineState getState() {
        return state;
    }

    public MetricSnapshot getLatestSnapshot() {
        MetricSnapshot latest = metricRegistry != null ? metricRegistry.getHistory().getLatest() : null;
        return latest != null ? latest : captureImmediateSnapshot();
    }

    public DiagnosticCoordinator.DiagnosticEvaluation runDiagnostics(boolean allowCached) {
        return diagnosticCoordinator.evaluate(
                metricRegistry.getHistory(),
                taskMonitor,
                compatibilityManager.getReport(),
                configManager,
                allowCached
        );
    }

    public DiagnosticReport generateReport() {
        MetricSnapshot snap = getLatestSnapshot();
        DiagnosticCoordinator.DiagnosticEvaluation eval = runDiagnostics(false);
        return reportGenerator.generateAndSave(
                compatibilityManager.getReport(),
                snap,
                eval,
                optimizationManager.getAllModules(),
                safeModeController
        );
    }

    public ConfigManager getConfigManager() { return configManager; }
    public CompatibilityManager getCompatibilityManager() { return compatibilityManager; }
    public SafeModeController getSafeModeController() { return safeModeController; }
    public SafetyValidator getSafetyValidator() { return safetyValidator; }
    public CompatibilityGuard getCompatibilityGuard() { return compatibilityGuard; }
    public SchedulerAdapter getScheduler() { return scheduler; }
    public MetricRegistry getMetricRegistry() { return metricRegistry; }
    public TickMonitor getTickMonitor() { return tickMonitor; }
    public CpuMonitor getCpuMonitor() { return cpuMonitor; }
    public MemoryMonitor getMemoryMonitor() { return memoryMonitor; }
    public NetworkMonitor getNetworkMonitor() { return networkMonitor; }
    public ChunkMonitor getChunkMonitor() { return chunkMonitor; }
    public EntityMonitor getEntityMonitor() { return entityMonitor; }
    public TaskMonitor getTaskMonitor() { return taskMonitor; }
    public StorageMonitor getStorageMonitor() { return storageMonitor; }
    public DiagnosticCoordinator getDiagnosticCoordinator() { return diagnosticCoordinator; }
    public OptimizationManager getOptimizationManager() { return optimizationManager; }
    public SparkIntegration getSparkIntegration() { return sparkIntegration; }
    public ChunkyIntegration getChunkyIntegration() { return chunkyIntegration; }
    public DependencyAutoInstaller getDependencyAutoInstaller() { return dependencyAutoInstaller; }
    public ReportGenerator getReportGenerator() { return reportGenerator; }
}

