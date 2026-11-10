package com.pratham.optimizeserver;

import com.pratham.optimizeserver.compatibility.CompatibilityManager;
import com.pratham.optimizeserver.metrics.MetricHistory;
import com.pratham.optimizeserver.metrics.MetricRegistry;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.metrics.RollingWindow;
import com.pratham.optimizeserver.monitoring.ChunkMonitor;
import com.pratham.optimizeserver.monitoring.CpuMonitor;
import com.pratham.optimizeserver.monitoring.EntityMonitor;
import com.pratham.optimizeserver.monitoring.MemoryMonitor;
import com.pratham.optimizeserver.monitoring.NetworkMonitor;
import com.pratham.optimizeserver.monitoring.StorageMonitor;
import com.pratham.optimizeserver.monitoring.TickMonitor;
import org.bukkit.Server;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests rolling windows, percentile accuracy, bounded history rings, and all monitoring subsystems.
 */
public class MetricsAndMonitoringTest {

    @Test
    public void testRollingWindowPercentilesAndExpiration() {
        RollingWindow window = new RollingWindow(10L, 100); // 10-second window
        long baseNanos = 100_000_000_000L;

        // Record an old sample outside the 10s window
        window.record(999.0, baseNanos - 20_000_000_000L);

        // Record 100 samples from 1.0 to 100.0 inside the window
        for (int i = 1; i <= 100; i++) {
            window.record(i, baseNanos - (100 - i) * 10_000_000L);
        }

        RollingWindow.WindowStats stats = window.computeStats(baseNanos);
        assertEquals(100, stats.getSampleCount());
        assertEquals(50.5, stats.getAverage(), 0.01);
        assertEquals(1.0, stats.getMin(), 0.01);
        assertEquals(100.0, stats.getMax(), 0.01);
        assertEquals(50.5, stats.getP50(), 0.5);
        assertEquals(95.05, stats.getP95(), 0.5);
        assertEquals(99.01, stats.getP99(), 0.5);
        assertTrue(stats.getStdDev() > 28.0);
    }

    @Test
    public void testMetricHistoryBoundedRingAndResize() {
        MetricHistory history = new MetricHistory(20);
        for (int i = 1; i <= 35; i++) {
            history.append(MetricSnapshot.builder().averageMspt(i).build());
        }
        assertEquals(20, history.getSize());
        assertEquals(35.0, history.getLatest().getAverageMspt(), 0.001);
        assertEquals(16.0, history.getRecent(20).get(0).getAverageMspt(), 0.001);

        history.resizeCapacity(16);
        assertEquals(16, history.getCapacity());
        assertEquals(16, history.getSize());
        assertEquals(35.0, history.getLatest().getAverageMspt(), 0.001);
    }

    @Test
    public void testTickMonitorTpsAndMsptCalculations() {
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(server, true);
        MetricRegistry registry = new MetricRegistry(100, 16);
        TickMonitor tickMonitor = new TickMonitor(server, cm.getCapabilities(), registry);

        long now = System.nanoTime();
        // Record 40 normal 25ms ticks -> TPS should be 20.0
        for (int i = 0; i < 40; i++) {
            tickMonitor.recordExplicitTickMs(25.0, now + i * 50_000_000L);
        }
        assertEquals(20.0, tickMonitor.getTps5s(), 0.01);
        assertEquals(25.0, tickMonitor.getLatestMsptStats().getAverage(), 0.01);

        // Record heavy 100ms ticks -> 5s TPS should drop to 10.0
        long later = now + 20_000_000_000L;
        for (int i = 0; i < 20; i++) {
            tickMonitor.recordExplicitTickMs(100.0, later + i * 100_000_000L);
        }
        assertEquals(10.0, tickMonitor.getTps5s(), 0.01);
    }

    @Test
    public void testCpuMemoryNetworkChunkEntityAndStorageMonitors(@TempDir File tempDir) {
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(server, true);

        CpuMonitor cpuMonitor = new CpuMonitor();
        cpuMonitor.sample(System.nanoTime());
        assertTrue(cpuMonitor.getAvailableProcessors() >= 1);
        assertTrue(cpuMonitor.getLatestProcessCpuPercent() >= 0.0);

        MemoryMonitor memoryMonitor = new MemoryMonitor();
        memoryMonitor.sample(System.nanoTime());
        assertTrue(memoryMonitor.getHeapUsedBytes() > 0L);
        assertTrue(memoryMonitor.getHeapMaxBytes() >= memoryMonitor.getHeapUsedBytes());

        NetworkMonitor networkMonitor = new NetworkMonitor(server, cm.getCapabilities());
        long t0 = System.nanoTime();
        networkMonitor.sampleMainThread(t0);
        for (int i = 0; i < 50; i++) {
            networkMonitor.recordIncomingTrafficEvent(100_000L);
            networkMonitor.recordOutgoingTrafficEvent(100_000L);
        }
        networkMonitor.sampleMainThread(t0 + 1_000_000_000L);
        assertEquals(50.0, networkMonitor.getIncomingEventsPerSec(), 1.0);
        assertEquals(15.0, networkMonitor.estimateTickInducedDelayMs(65.0), 0.01);

        ChunkMonitor chunkMonitor = new ChunkMonitor(server, null);
        chunkMonitor.setCoalesceWindowMillis(200L);
        chunkMonitor.recordChunkLoad(true);
        chunkMonitor.recordChunkLoad(false);
        chunkMonitor.sampleMainThread(t0);
        // Second call within 50ms should be coalesced
        chunkMonitor.sampleMainThread(t0 + 50_000_000L);
        assertEquals(1L, chunkMonitor.getCoalescedQueryHits());

        EntityMonitor entityMonitor = new EntityMonitor(null, null, cm.getCapabilities());
        entityMonitor.recordEntitySpawn(EntityType.ZOMBIE);
        entityMonitor.recordEntitySpawn(EntityType.ZOMBIE);
        entityMonitor.recordEntitySpawn(EntityType.VILLAGER);
        entityMonitor.recordEntityRemove(EntityType.ZOMBIE);
        entityMonitor.sampleMainThread(t0 + 500_000_000L);
        assertEquals(2, entityMonitor.getTotalEntities());
        assertEquals(Integer.valueOf(1), entityMonitor.getEntitiesByType().get("ZOMBIE"));

        StorageMonitor storageMonitor = new StorageMonitor(tempDir);
        storageMonitor.sampleAsync();
        assertTrue(storageMonitor.getTotalDiskSpaceMb() > 0L);
        assertTrue(storageMonitor.getFreeDiskSpaceMb() > 0L);
    }
}
