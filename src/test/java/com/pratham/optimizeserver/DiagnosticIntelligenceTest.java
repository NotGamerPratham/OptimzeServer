package com.pratham.optimizeserver;

import com.pratham.optimizeserver.analyzer.AlertManager;
import com.pratham.optimizeserver.analyzer.AnomalyDetector;
import com.pratham.optimizeserver.analyzer.BottleneckAnalyzer;
import com.pratham.optimizeserver.analyzer.BottleneckCategory;
import com.pratham.optimizeserver.analyzer.DiagnosticCoordinator;
import com.pratham.optimizeserver.analyzer.DiagnosticFinding;
import com.pratham.optimizeserver.analyzer.EvidenceCollector;
import com.pratham.optimizeserver.analyzer.RecommendationEngine;
import com.pratham.optimizeserver.analyzer.ServerConfigAuditor;
import com.pratham.optimizeserver.analyzer.Severity;
import com.pratham.optimizeserver.analyzer.TrendAnalyzer;
import com.pratham.optimizeserver.compatibility.CompatibilityManager;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.metrics.MetricHistory;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.monitoring.EntityMonitor;
import com.pratham.optimizeserver.monitoring.TaskMonitor;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the Performance Intelligence Engine: BottleneckAnalyzer, AnomalyDetector,
 * TrendAnalyzer, RecommendationEngine, and DiagnosticCoordinator.
 */
public class DiagnosticIntelligenceTest {

    @Test
    public void testAllBottleneckClassifications() {
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(server, true);
        BottleneckAnalyzer analyzer = new BottleneckAnalyzer();

        Map<String, Integer> entityTypes = new HashMap<String, Integer>();
        entityTypes.put("HOPPER_MINECART", 4200);
        Map<String, Integer> entityWorlds = new HashMap<String, Integer>();
        entityWorlds.put("world", 6500);
        Map<String, Integer> pendingTasks = new HashMap<String, Integer>();
        pendingTasks.put("HeavyPlugin", 350);

        MetricSnapshot overloaded = MetricSnapshot.builder()
                .averageMspt(62.0)
                .p50Mspt(30.0)
                .p95Mspt(75.0)
                .p99Mspt(140.0)
                .tps1m(15.5)
                .processCpuLoadPercent(94.0)
                .systemCpuLoadPercent(96.0)
                .availableProcessors(4)
                .heapUsedBytes(7_600_000_000L)
                .heapMaxBytes(8_000_000_000L)
                .recentGcPauseRateMsPerSec(180.0)
                .gcTotalCount(45L)
                .gcTotalTimeMs(6200L)
                .loadedChunks(3800)
                .chunkLoadRatePerSec(55.0)
                .chunkGenRatePerSec(18.0)
                .totalEntities(6500)
                .tileEntityCount(5000)
                .entitiesByType(entityTypes)
                .entitiesByWorld(entityWorlds)
                .pendingServerTasks(400)
                .activeServerWorkers(40)
                .freeDiskSpaceMb(400L)
                .totalDiskSpaceMb(50_000L)
                .storageIoLatencyMs(42.0)
                .onlinePlayers(40)
                .averagePingMs(220.0)
                .maxPingMs(450.0)
                .packetProcessingOverheadMs(24.0)
                .build();

        EvidenceCollector.EvidenceBundle bundle = new EvidenceCollector.EvidenceBundle(
                overloaded,
                Collections.singletonList(overloaded),
                pendingTasks,
                Collections.<String, Integer>emptyMap(),
                cm.getReport()
        );

        List<DiagnosticFinding> findings = analyzer.analyze(bundle, Collections.<AnomalyDetector.AnomalySignal>emptyList(), null, null);
        Set<BottleneckCategory> detected = new HashSet<BottleneckCategory>();
        for (DiagnosticFinding f : findings) {
            detected.add(f.getSubsystem());
            assertNotNull(f.getId());
            assertFalse(f.getEvidence().isEmpty());
            assertTrue(f.getConfidence() > 0.5);
        }

        assertTrue(detected.contains(BottleneckCategory.CPU_BOUND));
        assertTrue(detected.contains(BottleneckCategory.MEMORY_PRESSURE));
        assertTrue(detected.contains(BottleneckCategory.GC_PRESSURE));
        assertTrue(detected.contains(BottleneckCategory.CHUNK_PROCESSING));
        assertTrue(detected.contains(BottleneckCategory.ENTITY_PROCESSING));
        assertTrue(detected.contains(BottleneckCategory.PLUGIN_TASK_OVERHEAD));
        assertTrue(detected.contains(BottleneckCategory.STORAGE_BOTTLENECK));
        assertTrue(detected.contains(BottleneckCategory.NETWORK_PROCESSING));
    }

    @Test
    public void testUnknownOrInsufficientEvidenceFallbackWhenOnlyMsptIsHigh() {
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(server, true);
        BottleneckAnalyzer analyzer = new BottleneckAnalyzer();

        MetricSnapshot mysteriousSpike = MetricSnapshot.builder()
                .averageMspt(58.0)
                .p50Mspt(55.0)
                .p95Mspt(65.0)
                .p99Mspt(70.0)
                .tps1m(16.8)
                .processCpuLoadPercent(15.0)
                .systemCpuLoadPercent(20.0)
                .heapUsedBytes(1_000_000_000L)
                .heapMaxBytes(8_000_000_000L)
                .recentGcPauseRateMsPerSec(0.0)
                .loadedChunks(200)
                .totalEntities(150)
                .pendingServerTasks(5)
                .freeDiskSpaceMb(50_000L)
                .totalDiskSpaceMb(100_000L)
                .storageIoLatencyMs(0.5)
                .build();

        EvidenceCollector.EvidenceBundle bundle = new EvidenceCollector.EvidenceBundle(
                mysteriousSpike,
                Collections.singletonList(mysteriousSpike),
                Collections.<String, Integer>emptyMap(),
                Collections.<String, Integer>emptyMap(),
                cm.getReport()
        );

        List<DiagnosticFinding> findings = analyzer.analyze(bundle, Collections.<AnomalyDetector.AnomalySignal>emptyList(), null, null);
        assertEquals(1, findings.size());
        assertEquals(BottleneckCategory.UNKNOWN_OR_INSUFFICIENT_EVIDENCE, findings.get(0).getSubsystem());
    }

    @Test
    public void testAnomalyDetectorAndTrendAnalyzerAndCoordinatorDeduplication() {
        List<MetricSnapshot> historyList = new ArrayList<MetricSnapshot>();
        long baseTime = System.currentTimeMillis() - 600_000L;
        for (int i = 0; i < 10; i++) {
            historyList.add(MetricSnapshot.builder()
                    .timestampMillis(baseTime + i * 60_000L)
                    .averageMspt(15.0 + i * 2.0) // +2.0 ms/min slope
                    .p50Mspt(15.0)
                    .p99Mspt(20.0)
                    .heapUsedBytes((1000L + i * 100L) * 1024L * 1024L)
                    .heapMaxBytes(4000L * 1024L * 1024L)
                    .totalEntities(500 + i * 50)
                    .processCpuLoadPercent(20.0 + i * 3.0)
                    .chunkGenRatePerSec(1.0)
                    .build());
        }

        TrendAnalyzer trendAnalyzer = new TrendAnalyzer();
        TrendAnalyzer.TrendSummary trends = trendAnalyzer.analyze(historyList);
        assertEquals(2.0, trends.getMsptSlopePerMinute(), 0.05);
        assertEquals(50.0, trends.getEntityGrowthPerMinute(), 0.5);
        assertEquals(3.0, trends.getCpuSlopePerMinute(), 0.05);

        MetricSnapshot spike = MetricSnapshot.builder()
                .averageMspt(65.0)
                .p50Mspt(20.0)
                .p99Mspt(85.0)
                .chunkGenRatePerSec(25.0)
                .processCpuLoadPercent(92.0)
                .build();

        AnomalyDetector detector = new AnomalyDetector();
        List<AnomalyDetector.AnomalySignal> anomalies = detector.detectAnomalies(spike, historyList, 2.0);
        assertTrue(anomalies.size() >= 2);

        MetricHistory metricHistory = new MetricHistory(50);
        for (MetricSnapshot s : historyList) {
            metricHistory.append(s);
        }
        metricHistory.append(spike);

        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(server, true);
        DiagnosticCoordinator coordinator = new DiagnosticCoordinator();

        DiagnosticCoordinator.DiagnosticEvaluation eval1 = coordinator.evaluate(metricHistory, null, cm.getReport(), null, false);
        assertFalse(eval1.getFindings().isEmpty());
        assertFalse(eval1.getRecommendations().isEmpty());

        // Second evaluation with caching allowed should hit cache
        DiagnosticCoordinator.DiagnosticEvaluation eval2 = coordinator.evaluate(metricHistory, null, cm.getReport(), null, true);
        assertEquals(1L, coordinator.getCacheHits());
        assertSame(eval1, eval2);

        // Force non-cached evaluation within dedup window -> should increment deduplicatedCount
        coordinator.evaluate(metricHistory, null, cm.getReport(), null, false);
        assertTrue(coordinator.getDeduplicatedCount() >= 1L);

        RecommendationEngine recEngine = new RecommendationEngine();
        assertFalse(recEngine.generateRecommendations(eval1.getEvidenceBundle(), eval1.getFindings()).isEmpty());
    }

    @Test
    public void testServerConfigAuditorAndCapacityAssessment(@TempDir File tempDir) throws Exception {
        File serverRoot = new File(tempDir, "server");
        File configDir = new File(serverRoot, "config");
        File dataDir = new File(serverRoot, "plugins/OptimizeServer");
        assertTrue(configDir.mkdirs());
        assertTrue(dataDir.mkdirs());

        writeUtf8(new File(serverRoot, "server.properties"),
                "sync-chunk-writes=true\nnetwork-compression-threshold=64\nregion-file-compression=deflate\n");
        writeUtf8(new File(serverRoot, "bukkit.yml"),
                "chunk-gc:\n  period-in-ticks: 600\nticks-per:\n  autosave: 1200\n");
        writeUtf8(new File(serverRoot, "spigot.yml"),
                "settings:\n  save-user-cache-on-stop-only: false\n  netty-threads: 8\n");
        writeUtf8(new File(configDir, "paper-world-defaults.yml"),
                "chunks:\n  max-auto-save-chunks-per-tick: 24\n  prevent-moving-into-unloaded-chunks: false\n");

        Server paperServer = TestServerHarness.createMockServer(
                "Paper", "git-Paper-130 (MC: 1.21.1)", "1.21.1-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(paperServer, true);

        MetricSnapshot oneGbSnapshot = MetricSnapshot.builder()
                .heapMaxBytes(1024L * 1024L * 1024L)
                .heapUsedBytes(760L * 1024L * 1024L)
                .postGcHeapMaxBytes(700L * 1024L * 1024L)
                .postGcHeapUsedBytes(520L * 1024L * 1024L)
                .onlinePlayers(45)
                .loadedChunks(1800)
                .availableProcessors(2)
                .build();

        ServerConfigAuditor auditor = new ServerConfigAuditor();
        ServerConfigAuditor.AuditReport audit = auditor.audit(serverRoot, paperServer, cm.getReport(), oneGbSnapshot);

        assertFalse(audit.getSuggestions().isEmpty());
        assertNotNull(audit.getRecommendedJvmCommand());
        assertTrue(audit.getRecommendedJvmCommand().contains("UseG1GC"));

        ServerConfigAuditor.CapacityAssessment overloadCap = auditor.evaluateCapacity(oneGbSnapshot, cm.getReport(), 50);
        assertEquals(ServerConfigAuditor.CapacityStatus.INSUFFICIENT_RESOURCES, overloadCap.getStatus());

        MetricSnapshot healthySnapshot = MetricSnapshot.builder()
                .heapMaxBytes(8192L * 1024L * 1024L)
                .heapUsedBytes(3000L * 1024L * 1024L)
                .postGcHeapMaxBytes(6000L * 1024L * 1024L)
                .postGcHeapUsedBytes(2000L * 1024L * 1024L)
                .onlinePlayers(10)
                .loadedChunks(400)
                .availableProcessors(4)
                .build();
        ServerConfigAuditor.CapacityAssessment healthyCap = auditor.evaluateCapacity(healthySnapshot, cm.getReport(), 20);
        assertEquals(ServerConfigAuditor.CapacityStatus.HEALTHY, healthyCap.getStatus());

        File presetFile = auditor.writePresets(dataDir, audit);
        assertNotNull(presetFile);
        assertTrue(presetFile.exists());
        assertTrue(new File(dataDir, "presets/recommended-jvm-flags.txt").exists());
    }

    @Test
    public void testAlertManagerSeverityFilteringAndCooldown() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("alerts.console-alerts-enabled", true);
        cfg.set("alerts.admin-permission-broadcast", false);
        cfg.set("alerts.alert-cooldown-seconds", 60);
        cfg.set("alerts.min-severity", "HIGH");
        ConfigManager cm = new ConfigManager(null, cfg, Logger.getLogger("AlertTest"));

        AlertManager manager = new AlertManager(null, null, cm, Logger.getLogger("AlertTest"));

        DiagnosticFinding mediumFinding = new DiagnosticFinding(
                "FINDING-MED",
                System.currentTimeMillis(),
                Severity.MEDIUM,
                BottleneckCategory.MEMORY_PRESSURE,
                Collections.<String, String>emptyMap(),
                Collections.singletonList("Heap 72%"),
                0.70,
                "Moderate heap pressure",
                "Monitor heap",
                false
        );
        DiagnosticFinding highFinding = new DiagnosticFinding(
                "FINDING-HIGH",
                System.currentTimeMillis(),
                Severity.HIGH,
                BottleneckCategory.MEMORY_PRESSURE,
                Collections.<String, String>emptyMap(),
                Collections.singletonList("Post-GC OldGen 88%"),
                0.88,
                "Severe OldGen retention",
                "Increase heap or find leak",
                false
        );

        assertEquals(0, manager.processFindings(Collections.singletonList(mediumFinding)));
        assertEquals(1, manager.processFindings(Collections.singletonList(highFinding)));
        // Repeated identical category+severity inside cooldown window is suppressed
        assertEquals(0, manager.processFindings(Collections.singletonList(highFinding)));

        manager.clearCooldowns();
        assertEquals(1, manager.processFindings(Collections.singletonList(highFinding)));
    }

    @Test
    public void testTaskMonitorSyncSeparationAndLeakStreak() {
        final Plugin lagPlugin = createNamedPluginProxy("LagPlugin");
        final Plugin asyncPlugin = createNamedPluginProxy("AsyncPlugin");
        final List<BukkitTask> tasks = new ArrayList<BukkitTask>();
        for (int i = 0; i < 26; i++) {
            tasks.add(createTaskProxy(lagPlugin, true));
        }
        tasks.add(createTaskProxy(asyncPlugin, false));

        final BukkitScheduler schedulerProxy = (BukkitScheduler) Proxy.newProxyInstance(
                BukkitScheduler.class.getClassLoader(),
                new Class<?>[]{BukkitScheduler.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getPendingTasks".equals(method.getName())) {
                            return new ArrayList<BukkitTask>(tasks);
                        }
                        if ("getActiveWorkers".equals(method.getName())) {
                            return Collections.emptyList();
                        }
                        return null;
                    }
                }
        );

        Server serverProxy = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getScheduler".equals(name)) return schedulerProxy;
                        if ("getName".equals(name)) return "Paper";
                        if ("getVersion".equals(name)) return "git-Paper-496 (MC: 1.20.4)";
                        if ("getBukkitVersion".equals(name)) return "1.20.4-R0.1-SNAPSHOT";
                        return null;
                    }
                }
        );

        TaskMonitor monitor = new TaskMonitor(serverProxy, null);
        monitor.sample();
        assertEquals(27, monitor.getPendingServerTasks());
        assertEquals(26, monitor.getPendingSyncTasks());
        assertEquals(Integer.valueOf(26), monitor.getPendingSyncTasksByPlugin().get("LagPlugin"));

        // Grow LagPlugin sync tasks twice to establish consecutive growth streak
        tasks.add(createTaskProxy(lagPlugin, true));
        tasks.add(createTaskProxy(lagPlugin, true));
        monitor.sample();
        tasks.add(createTaskProxy(lagPlugin, true));
        tasks.add(createTaskProxy(lagPlugin, true));
        monitor.sample();

        assertEquals(30, monitor.getPendingSyncTasks());
        assertEquals("LagPlugin", monitor.getGrowingTaskPlugin());
        assertTrue(monitor.getGrowingTaskPluginStreak() >= 2);
    }

    @Test
    public void testEntityMonitorIncrementalBudgetedScan() {
        final Chunk[] chunks = new Chunk[16];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = createSlowChunkProxy(2, 50_000L);
        }
        final World worldProxy = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getName".equals(name)) return "world";
                        if ("getLoadedChunks".equals(name)) return chunks;
                        if ("getEntities".equals(name)) return Collections.<Entity>emptyList();
                        return null;
                    }
                }
        );
        Server serverProxy = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getWorlds".equals(method.getName())) {
                            return Collections.singletonList(worldProxy);
                        }
                        if ("getName".equals(name(method))) return "Paper";
                        if ("getVersion".equals(method.getName())) return "git-Paper-496 (MC: 1.20.4)";
                        if ("getBukkitVersion".equals(method.getName())) return "1.20.4-R0.1-SNAPSHOT";
                        return null;
                    }

                    private String name(Method m) {
                        return m.getName();
                    }
                }
        );

        CompatibilityManager cm = new CompatibilityManager(serverProxy, true);
        EntityMonitor monitor = new EntityMonitor(serverProxy, null, cm.getReport().getCapabilities());
        monitor.setCoalesceWindowMillis(0L);
        monitor.setZeroAllocScanEnabled(true);
        monitor.setTileScanBudgetNanos(100_000L); // 0.10 ms cap -> yields cleanly every 8 chunks

        for (int step = 0; step < 5; step++) {
            monitor.sampleMainThread(System.nanoTime());
        }

        assertTrue(monitor.getBudgetYieldCount() >= 1L);
        assertEquals(32, monitor.getTileEntityCount());
    }

    private static void writeUtf8(File target, String content) throws Exception {
        OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    private static Plugin createNamedPluginProxy(final String pluginName) {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getName".equals(method.getName())) {
                            return pluginName;
                        }
                        return null;
                    }
                }
        );
    }

    private static BukkitTask createTaskProxy(final Plugin owner, final boolean sync) {
        return (BukkitTask) Proxy.newProxyInstance(
                BukkitTask.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getOwner".equals(name)) return owner;
                        if ("isSync".equals(name)) return Boolean.valueOf(sync);
                        if ("getTaskId".equals(name)) return Integer.valueOf(1);
                        if ("isCancelled".equals(name)) return Boolean.FALSE;
                        return null;
                    }
                }
        );
    }

    private static Chunk createSlowChunkProxy(final int tileCount, final long busySpinNanos) {
        final BlockState[] states = new BlockState[tileCount];
        return (Chunk) Proxy.newProxyInstance(
                Chunk.class.getClassLoader(),
                new Class<?>[]{Chunk.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getTileEntities".equals(method.getName())) {
                            long end = System.nanoTime() + busySpinNanos;
                            while (System.nanoTime() < end) {
                                // Intentional micro spin to deterministically trigger time budget slicing
                            }
                            return Arrays.copyOf(states, states.length);
                        }
                        return null;
                    }
                }
        );
    }
}

