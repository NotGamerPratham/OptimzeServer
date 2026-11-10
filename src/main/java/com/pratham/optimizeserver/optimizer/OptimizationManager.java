package com.pratham.optimizeserver.optimizer;

import com.pratham.optimizeserver.analyzer.DiagnosticCoordinator;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.metrics.MetricRegistry;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.monitoring.ChunkMonitor;
import com.pratham.optimizeserver.monitoring.EntityMonitor;
import com.pratham.optimizeserver.safety.GameplayInvariant;
import com.pratham.optimizeserver.scheduler.BoundedWorkerPool;

import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Orchestrates registration, validation, activation, conditional tuning, and rollback
 * of all internal Automatic Safe and Conditional optimization modules.
 */
public final class OptimizationManager {

    private final OptimizationRegistry registry;
    private final OptimizationValidator validator;
    private final RollbackManager rollbackManager;
    private final ConfigManager configManager;
    private final MetricRegistry metricRegistry;
    private final ChunkMonitor chunkMonitor;
    private final EntityMonitor entityMonitor;
    private final DiagnosticCoordinator diagnosticCoordinator;
    private final BoundedWorkerPool workerPool;
    private final Logger logger;

    private final AtomicInteger adaptiveWorldSampleIntervalTicks = new AtomicInteger(100);
    private volatile boolean taskBatchingEnabled = true;

    public OptimizationManager(OptimizationRegistry registry,
                               OptimizationValidator validator,
                               RollbackManager rollbackManager,
                               ConfigManager configManager,
                               MetricRegistry metricRegistry,
                               ChunkMonitor chunkMonitor,
                               EntityMonitor entityMonitor,
                               DiagnosticCoordinator diagnosticCoordinator,
                               BoundedWorkerPool workerPool,
                               Logger logger) {
        this.registry = registry;
        this.validator = validator;
        this.rollbackManager = rollbackManager;
        this.configManager = configManager;
        this.metricRegistry = metricRegistry;
        this.chunkMonitor = chunkMonitor;
        this.entityMonitor = entityMonitor;
        this.diagnosticCoordinator = diagnosticCoordinator;
        this.workerPool = workerPool;
        this.logger = logger != null ? logger : Logger.getLogger("OptimizeServer");
        registerBuiltInModules();
    }

    private void registerBuiltInModules() {
        // 1. config-lookup-cache
        registry.register(new BaseInternalModule(
                "config-lookup-cache",
                "Caches configuration key lookups in a bounded concurrent map to eliminate repeated YAML path traversal.",
                OptimizationCategory.AUTOMATIC_SAFE,
                "Reduces configuration lookup overhead and object boxing on hot monitoring paths."
        ) {
            private boolean previousState = false;

            @Override
            protected boolean doEnable() {
                if (configManager != null) {
                    previousState = configManager.isCacheEnabled();
                    beforeMeasurement = "cacheEnabled=" + previousState + ", hits=" + configManager.getCacheHits();
                    configManager.setCacheEnabled(true);
                }
                return true;
            }

            @Override
            protected boolean doDisable() {
                if (configManager != null) {
                    configManager.setCacheEnabled(false);
                }
                return true;
            }

            @Override
            protected boolean doRollback() {
                if (configManager != null) {
                    configManager.setCacheEnabled(previousState);
                }
                return true;
            }

            @Override
            public String getAfterMeasurement() {
                if (configManager == null) return "N/A";
                return "cacheEnabled=" + configManager.isCacheEnabled()
                        + ", hits=" + configManager.getCacheHits()
                        + ", misses=" + configManager.getCacheMisses()
                        + ", entries=" + configManager.getCachedEntryCount();
            }
        });

        // 2. monitoring-buffer-pool
        registry.register(new BaseInternalModule(
                "monitoring-buffer-pool",
                "Reuses pre-allocated primitive double[] scratch buffers during rolling window percentile calculations.",
                OptimizationCategory.AUTOMATIC_SAFE,
                "Eliminates transient array allocations during P50/P95/P99 MSPT sorting."
        ) {
            private boolean prevZeroAlloc = false;
            private boolean prevPool = false;

            @Override
            protected boolean doEnable() {
                if (metricRegistry != null) {
                    prevZeroAlloc = metricRegistry.isZeroAllocAggregationEnabled();
                    prevPool = metricRegistry.isBufferPoolEnabled();
                    beforeMeasurement = "zeroAlloc=" + prevZeroAlloc + ", poolReuses=" + metricRegistry.getBufferReuses();
                    metricRegistry.setZeroAllocAggregationEnabled(true);
                    metricRegistry.setBufferPoolEnabled(true);
                }
                return true;
            }

            @Override
            protected boolean doDisable() {
                if (metricRegistry != null) {
                    metricRegistry.setZeroAllocAggregationEnabled(false);
                    metricRegistry.setBufferPoolEnabled(false);
                }
                return true;
            }

            @Override
            protected boolean doRollback() {
                if (metricRegistry != null) {
                    metricRegistry.setZeroAllocAggregationEnabled(prevZeroAlloc);
                    metricRegistry.setBufferPoolEnabled(prevPool);
                }
                return true;
            }

            @Override
            public String getAfterMeasurement() {
                if (metricRegistry == null) return "N/A";
                return "zeroAlloc=" + metricRegistry.isZeroAllocAggregationEnabled()
                        + ", bufferReuses=" + metricRegistry.getBufferReuses()
                        + ", allocations=" + metricRegistry.getBufferAllocations();
            }
        });

        // 3. bounded-metric-ring
        registry.register(new BaseInternalModule(
                "bounded-metric-ring",
                "Maintains fixed-capacity circular ring buffers for historical metric snapshots.",
                OptimizationCategory.AUTOMATIC_SAFE,
                "Guarantees O(1) bounded memory footprint for performance telemetry."
        ) {
            private int previousCapacity = 900;

            @Override
            protected boolean doEnable() {
                if (metricRegistry != null) {
                    previousCapacity = metricRegistry.getHistory().getCapacity();
                    int targetCap = configManager != null
                            ? configManager.getInt("monitoring.retention.max-snapshots", 900)
                            : 900;
                    beforeMeasurement = "ringCapacity=" + previousCapacity + ", size=" + metricRegistry.getHistory().getSize();
                    metricRegistry.getHistory().resizeCapacity(targetCap);
                }
                return true;
            }

            @Override
            protected boolean doDisable() {
                return true;
            }

            @Override
            protected boolean doRollback() {
                if (metricRegistry != null) {
                    metricRegistry.getHistory().resizeCapacity(previousCapacity);
                }
                return true;
            }

            @Override
            public String getAfterMeasurement() {
                if (metricRegistry == null) return "N/A";
                return "ringCapacity=" + metricRegistry.getHistory().getCapacity()
                        + ", occupied=" + metricRegistry.getHistory().getSize();
            }
        });

        // 4. redundant-query-coalescer
        registry.register(new BaseInternalModule(
                "redundant-query-coalescer",
                "Coalesces closely spaced world chunk and entity inspection queries within a 250ms window.",
                OptimizationCategory.AUTOMATIC_SAFE,
                "Prevents duplicate main-thread world enumeration during concurrent command or diagnostic bursts."
        ) {
            private long previousWindowMs = 0L;

            @Override
            protected boolean doEnable() {
                long targetMs = configManager != null
                        ? configManager.getLong("optimization.modules.redundant-query-coalescer.coalesce-window-ms", 250L)
                        : 250L;
                if (chunkMonitor != null) {
                    previousWindowMs = chunkMonitor.getCoalesceWindowMillis();
                    beforeMeasurement = "coalesceWindowMs=" + previousWindowMs + ", coalescedHits=" + chunkMonitor.getCoalescedQueryHits();
                    chunkMonitor.setCoalesceWindowMillis(targetMs);
                }
                if (entityMonitor != null) {
                    entityMonitor.setCoalesceWindowMillis(targetMs);
                }
                return true;
            }

            @Override
            protected boolean doDisable() {
                if (chunkMonitor != null) chunkMonitor.setCoalesceWindowMillis(0L);
                if (entityMonitor != null) entityMonitor.setCoalesceWindowMillis(0L);
                return true;
            }

            @Override
            protected boolean doRollback() {
                if (chunkMonitor != null) chunkMonitor.setCoalesceWindowMillis(previousWindowMs);
                if (entityMonitor != null) entityMonitor.setCoalesceWindowMillis(previousWindowMs);
                return true;
            }

            @Override
            public String getAfterMeasurement() {
                if (chunkMonitor == null) return "N/A";
                return "coalesceWindowMs=" + chunkMonitor.getCoalesceWindowMillis()
                        + ", coalescedHits=" + chunkMonitor.getCoalescedQueryHits();
            }
        });

        // 5. diagnostic-deduplicator
        registry.register(new BaseInternalModule(
                "diagnostic-deduplicator",
                "Deduplicates recurring diagnostic findings within a sliding window to prevent log and history churn.",
                OptimizationCategory.AUTOMATIC_SAFE,
                "Suppresses redundant duplicate findings while preserving distinct bottleneck transitions."
        ) {
            private boolean previousEnabled = false;

            @Override
            protected boolean doEnable() {
                if (diagnosticCoordinator != null) {
                    previousEnabled = diagnosticCoordinator.isDeduplicationEnabled();
                    long windowSec = configManager != null
                            ? configManager.getLong("optimization.modules.diagnostic-deduplicator.dedup-window-seconds", 30L)
                            : 30L;
                    beforeMeasurement = "dedupEnabled=" + previousEnabled + ", deduplicated=" + diagnosticCoordinator.getDeduplicatedCount();
                    diagnosticCoordinator.setDeduplicationEnabled(true, windowSec);
                }
                return true;
            }

            @Override
            protected boolean doDisable() {
                if (diagnosticCoordinator != null) {
                    diagnosticCoordinator.setDeduplicationEnabled(false, 30L);
                }
                return true;
            }

            @Override
            protected boolean doRollback() {
                if (diagnosticCoordinator != null) {
                    diagnosticCoordinator.setDeduplicationEnabled(previousEnabled, 30L);
                }
                return true;
            }

            @Override
            public String getAfterMeasurement() {
                if (diagnosticCoordinator == null) return "N/A";
                return "dedupEnabled=" + diagnosticCoordinator.isDeduplicationEnabled()
                        + ", deduplicatedFindings=" + diagnosticCoordinator.getDeduplicatedCount();
            }
        });

        // 6. task-batch-coordinator
        registry.register(new BaseInternalModule(
                "task-batch-coordinator",
                "Batches non-world background telemetry and storage checks into unified asynchronous execution cycles.",
                OptimizationCategory.AUTOMATIC_SAFE,
                "Reduces thread wakeup context switches across background monitoring tasks."
        ) {
            private boolean previousBatching = false;

            @Override
            protected boolean doEnable() {
                previousBatching = taskBatchingEnabled;
                beforeMeasurement = "taskBatching=" + previousBatching;
                taskBatchingEnabled = true;
                return true;
            }

            @Override
            protected boolean doDisable() {
                taskBatchingEnabled = false;
                return true;
            }

            @Override
            protected boolean doRollback() {
                taskBatchingEnabled = previousBatching;
                return true;
            }

            @Override
            public String getAfterMeasurement() {
                return "taskBatching=" + taskBatchingEnabled;
            }
        });

        // 7. adaptive-monitoring-sampler (CONDITIONAL)
        registry.register(new BaseInternalModule(
                "adaptive-monitoring-sampler",
                "Dynamically scales OptimizeServer's own world/entity inspection cadence when server MSPT is elevated.",
                OptimizationCategory.CONDITIONAL,
                "Reduces OptimizeServer's main-thread sampling frequency under heavy tick load without altering Minecraft tick rate."
        ) {
            private int baselineInterval = 100;

            @Override
            protected boolean doEnable() {
                baselineInterval = configManager != null
                        ? configManager.getInt("monitoring.sampling.world-sample-interval-ticks", 100)
                        : 100;
                beforeMeasurement = "worldSampleIntervalTicks=" + adaptiveWorldSampleIntervalTicks.get();
                adaptiveWorldSampleIntervalTicks.set(baselineInterval);
                return true;
            }

            @Override
            protected boolean doDisable() {
                adaptiveWorldSampleIntervalTicks.set(baselineInterval);
                return true;
            }

            @Override
            protected boolean doRollback() {
                adaptiveWorldSampleIntervalTicks.set(baselineInterval);
                return true;
            }

            @Override
            public String getAfterMeasurement() {
                return "adaptiveWorldSampleIntervalTicks=" + adaptiveWorldSampleIntervalTicks.get();
            }
        });

        // 8. worker-pool-governor (CONDITIONAL)
        registry.register(new BaseInternalModule(
                "worker-pool-governor",
                "Governs OptimizeServer's bounded async worker pool threads according to CPU saturation and queue depth.",
                OptimizationCategory.CONDITIONAL,
                "Prevents background diagnostic threads from competing with the server tick thread during CPU saturation."
        ) {
            private int prevCore = 2;
            private int prevMax = 4;

            @Override
            protected boolean doEnable() {
                if (workerPool != null) {
                    prevCore = workerPool.getCorePoolSize();
                    prevMax = workerPool.getMaximumPoolSize();
                    beforeMeasurement = "core=" + prevCore + ", max=" + prevMax;
                }
                return true;
            }

            @Override
            protected boolean doDisable() {
                if (workerPool != null) {
                    workerPool.adjustPoolLimits(prevCore, prevMax);
                }
                return true;
            }

            @Override
            protected boolean doRollback() {
                if (workerPool != null) {
                    workerPool.adjustPoolLimits(prevCore, prevMax);
                }
                return true;
            }

            @Override
            public String getAfterMeasurement() {
                if (workerPool == null) return "N/A";
                return "core=" + workerPool.getCorePoolSize()
                        + ", max=" + workerPool.getMaximumPoolSize()
                        + ", queueSize=" + workerPool.getQueueSize();
            }
        });

        // 9. diagnostic-result-cache (CONDITIONAL)
        registry.register(new BaseInternalModule(
                "diagnostic-result-cache",
                "Caches diagnostic evaluations for a short TTL (3s) to avoid redundant analysis across rapid command calls.",
                OptimizationCategory.CONDITIONAL,
                "Eliminates duplicate statistical regression and anomaly computation within the TTL window."
        ) {
            private boolean prevCache = false;

            @Override
            protected boolean doEnable() {
                if (diagnosticCoordinator != null) {
                    prevCache = diagnosticCoordinator.isResultCacheEnabled();
                    long ttl = configManager != null
                            ? configManager.getLong("optimization.modules.diagnostic-result-cache.ttl-millis", 3000L)
                            : 3000L;
                    beforeMeasurement = "resultCacheEnabled=" + prevCache + ", hits=" + diagnosticCoordinator.getCacheHits();
                    diagnosticCoordinator.setResultCacheEnabled(true, ttl);
                }
                return true;
            }

            @Override
            protected boolean doDisable() {
                if (diagnosticCoordinator != null) {
                    diagnosticCoordinator.setResultCacheEnabled(false, 3000L);
                }
                return true;
            }

            @Override
            protected boolean doRollback() {
                if (diagnosticCoordinator != null) {
                    diagnosticCoordinator.setResultCacheEnabled(prevCache, 3000L);
                }
                return true;
            }

            @Override
            public String getAfterMeasurement() {
                if (diagnosticCoordinator == null) return "N/A";
                return "resultCacheEnabled=" + diagnosticCoordinator.isResultCacheEnabled()
                        + ", cacheHits=" + diagnosticCoordinator.getCacheHits();
            }
        });
    }

    /**
     * Initializes configured modules after validating each through {@link OptimizationValidator}.
     */
    public void initializeConfiguredModules() {
        String mode = configManager != null
                ? configManager.getString("optimization.mode", "HYBRID_CONDITIONAL").toUpperCase(Locale.ROOT)
                : "HYBRID_CONDITIONAL";
        if ("MANUAL_ONLY".equals(mode) || "SAFE_MODE".equals(mode)) {
            logger.info("[OptimizeServer] Optimization mode is " + mode + "; skipping automatic module activation.");
            return;
        }

        for (OptimizationModule module : registry.getAllModules()) {
            if (module.getCategory() == OptimizationCategory.CONDITIONAL && "AUTOMATIC_SAFE".equals(mode)) {
                continue;
            }
            boolean cfgEnabled = configManager == null
                    || configManager.getBoolean("optimization.modules." + module.getId() + ".enabled", true);
            if (cfgEnabled) {
                enableModule(module.getId());
            }
        }
    }

    /**
     * Evaluates conditional modules periodically against the latest {@link MetricSnapshot}.
     */
    public void evaluateConditionalModules(MetricSnapshot latest) {
        if (latest == null) {
            return;
        }
        OptimizationModule adaptiveSampler = registry.getModule("adaptive-monitoring-sampler");
        if (adaptiveSampler != null && adaptiveSampler.isEnabled()) {
            int minTicks = configManager != null
                    ? configManager.getInt("optimization.modules.adaptive-monitoring-sampler.min-world-sample-ticks", 100)
                    : 100;
            int maxTicks = configManager != null
                    ? configManager.getInt("optimization.modules.adaptive-monitoring-sampler.max-world-sample-ticks", 400)
                    : 400;
            if (latest.getAverageMspt() >= 45.0) {
                adaptiveWorldSampleIntervalTicks.set(maxTicks);
            } else if (latest.getAverageMspt() >= 35.0) {
                adaptiveWorldSampleIntervalTicks.set(Math.min(maxTicks, minTicks * 2));
            } else {
                adaptiveWorldSampleIntervalTicks.set(minTicks);
            }
        }

        OptimizationModule poolGov = registry.getModule("worker-pool-governor");
        if (poolGov != null && poolGov.isEnabled() && workerPool != null) {
            int configuredCore = configManager != null ? configManager.getInt("workers.async-core-threads", 2) : 2;
            int configuredMax = configManager != null ? configManager.getInt("workers.async-max-threads", 4) : 4;
            if (latest.getProcessCpuLoadPercent() >= 85.0) {
                // Conserve CPU cores for the server tick thread when host CPU is saturated
                workerPool.adjustPoolLimits(1, Math.max(1, configuredCore));
            } else {
                workerPool.adjustPoolLimits(configuredCore, configuredMax);
            }
        }
    }

    public boolean enableModule(String id) {
        OptimizationModule module = registry.getModule(id);
        if (module == null) {
            return false;
        }
        OptimizationValidator.ModuleValidationReport validation = validator.validateModule(module);
        if (!validation.isValid()) {
            logger.warning("[OptimizeServer] Cannot enable module '" + module.getId()
                    + "': validation failed -> " + validation.getReasons());
            return false;
        }
        return module.enable();
    }

    public boolean disableModule(String id) {
        OptimizationModule module = registry.getModule(id);
        if (module == null) {
            return false;
        }
        return module.disable();
    }

    public boolean rollbackModule(String id, String reason) {
        OptimizationModule module = registry.getModule(id);
        if (module == null) {
            return false;
        }
        return rollbackManager.rollbackModule(module, reason);
    }

    public int rollbackAll(String reason) {
        return rollbackManager.rollbackAll(registry.getAllModules(), reason);
    }

    public int getEffectiveWorldSampleIntervalTicks() {
        return Math.max(20, adaptiveWorldSampleIntervalTicks.get());
    }

    public boolean isTaskBatchingEnabled() {
        return taskBatchingEnabled;
    }

    public OptimizationRegistry getRegistry() {
        return registry;
    }

    public OptimizationValidator getValidator() {
        return validator;
    }

    public RollbackManager getRollbackManager() {
        return rollbackManager;
    }

    public Collection<OptimizationModule> getAllModules() {
        return registry.getAllModules();
    }

    private abstract static class BaseInternalModule implements OptimizationModule {
        private final String id;
        private final String description;
        private final OptimizationCategory category;
        private final String expectedResult;
        private final GameplayInvariant.OperationProfile operationProfile;
        private volatile boolean enabled = false;
        protected volatile String beforeMeasurement = "Not yet activated";
        protected volatile String lastError = null;

        BaseInternalModule(String id,
                           String description,
                           OptimizationCategory category,
                           String expectedResult) {
            this.id = id;
            this.description = description;
            this.category = category;
            this.expectedResult = expectedResult;
            this.operationProfile = GameplayInvariant.OperationProfile.builder(id)
                    .modifiesGameplayMechanics(false)
                    .modifiesGameRules(false)
                    .modifiesWorldData(false)
                    .removesEntities(false)
                    .disablesMobAi(false)
                    .skipsServerTicks(false)
                    .modifiesSimulationOrViewDistance(false)
                    .altersGameplayPackets(false)
                    .accessesBukkitObjectsAsync(false)
                    .forcesExplicitGarbageCollection(false)
                    .reversible(true)
                    .build();
        }

        protected abstract boolean doEnable();
        protected abstract boolean doDisable();
        protected abstract boolean doRollback();

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public OptimizationCategory getCategory() {
            return category;
        }

        @Override
        public GameplayInvariant.OperationProfile getOperationProfile() {
            return operationProfile;
        }

        @Override
        public int getMinimumJavaVersion() {
            return 8;
        }

        @Override
        public boolean isFoliaCompatible() {
            return true;
        }

        @Override
        public boolean isActivationConditionMet(MetricSnapshot latestSnapshot) {
            return true;
        }

        @Override
        public String getExpectedResult() {
            return expectedResult;
        }

        @Override
        public String getBeforeMeasurement() {
            return beforeMeasurement;
        }

        @Override
        public synchronized boolean enable() {
            try {
                boolean ok = doEnable();
                this.enabled = ok;
                if (ok) {
                    this.lastError = null;
                }
                return ok;
            } catch (Throwable t) {
                this.lastError = t.getMessage();
                return false;
            }
        }

        @Override
        public synchronized boolean disable() {
            try {
                boolean ok = doDisable();
                if (ok) {
                    this.enabled = false;
                }
                return ok;
            } catch (Throwable t) {
                this.lastError = t.getMessage();
                return false;
            }
        }

        @Override
        public synchronized boolean rollback() {
            try {
                boolean ok = doRollback();
                this.enabled = false;
                return ok;
            } catch (Throwable t) {
                this.lastError = t.getMessage();
                return false;
            }
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public String getLastError() {
            return lastError;
        }
    }
}

