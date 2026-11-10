package com.pratham.optimizeserver;

import com.pratham.optimizeserver.analyzer.AnomalyDetector;
import com.pratham.optimizeserver.analyzer.BottleneckAnalyzer;
import com.pratham.optimizeserver.analyzer.BottleneckCategory;
import com.pratham.optimizeserver.analyzer.DiagnosticCoordinator;
import com.pratham.optimizeserver.analyzer.DiagnosticFinding;
import com.pratham.optimizeserver.analyzer.EvidenceCollector;
import com.pratham.optimizeserver.analyzer.RecommendationEngine;
import com.pratham.optimizeserver.analyzer.TrendAnalyzer;
import com.pratham.optimizeserver.compatibility.CompatibilityManager;
import com.pratham.optimizeserver.metrics.MetricHistory;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
}

