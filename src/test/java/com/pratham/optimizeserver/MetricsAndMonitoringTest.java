package com.pratham.optimizeserver;

import com.pratham.optimizeserver.compatibility.CompatibilityManager;
import com.pratham.optimizeserver.listeners.ServerActivityListener;
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
import com.pratham.optimizeserver.scheduler.BoundedWorkerPool;
import com.pratham.optimizeserver.scheduler.TaskHandle;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

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
        RollingWindow.WindowAverage fastAvg = window.computeAverage(baseNanos);
        assertEquals(100, fastAvg.getSampleCount());
        assertEquals(50.5, fastAvg.getAverage(), 0.01);
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

    @Test
    public void testServerActivityListenerCountersAndDeactivationGate() throws Exception {
        final Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(server, true);

        ChunkMonitor chunkMonitor = new ChunkMonitor(server, null);
        chunkMonitor.setCoalesceWindowMillis(0L);
        EntityMonitor entityMonitor = new EntityMonitor(null, null, cm.getCapabilities());
        entityMonitor.setCoalesceWindowMillis(0L);
        NetworkMonitor networkMonitor = new NetworkMonitor(server, cm.getCapabilities());

        ServerActivityListener listener = new ServerActivityListener(chunkMonitor, entityMonitor, networkMonitor);
        assertTrue(listener.isActive());

        final World dummyWorld = (World) Proxy.newProxyInstance(
                MetricsAndMonitoringTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getName".equals(method.getName())) return "world";
                        if ("getLoadedChunks".equals(method.getName())) return new Chunk[0];
                        return null;
                    }
                }
        );
        Chunk dummyChunk = (Chunk) Proxy.newProxyInstance(
                MetricsAndMonitoringTest.class.getClassLoader(),
                new Class<?>[]{Chunk.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getWorld".equals(method.getName())) return dummyWorld;
                        return null;
                    }
                }
        );
        LivingEntity zombie = (LivingEntity) Proxy.newProxyInstance(
                MetricsAndMonitoringTest.class.getClassLoader(),
                new Class<?>[]{LivingEntity.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getType".equals(method.getName())) return EntityType.ZOMBIE;
                        return null;
                    }
                }
        );
        Player player = (Player) Proxy.newProxyInstance(
                MetricsAndMonitoringTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getName".equals(method.getName())) return "TestPlayer";
                        if ("getServer".equals(method.getName())) return server;
                        return null;
                    }
                }
        );

        long t0 = System.nanoTime();
        chunkMonitor.sampleMainThread(t0);
        entityMonitor.sampleMainThread(t0);
        networkMonitor.sampleMainThread(t0);

        listener.onChunkLoad(new ChunkLoadEvent(dummyChunk, true));
        listener.onChunkLoad(new ChunkLoadEvent(dummyChunk, false));
        listener.onChunkUnload(new ChunkUnloadEvent(dummyChunk));

        listener.onEntitySpawn(new EntitySpawnEvent(zombie));
        listener.onEntitySpawn(new EntitySpawnEvent(zombie));
        listener.onEntityDeath(createEntityDeathEvent(zombie));

        listener.onPlayerJoin(new PlayerJoinEvent(player, "joined"));
        listener.onPlayerCommand(new PlayerCommandPreprocessEvent(player, "/os status"));
        listener.onPlayerQuit(new PlayerQuitEvent(player, "quit"));

        long t1 = t0 + 1_000_000_000L;
        chunkMonitor.sampleMainThread(t1);
        entityMonitor.sampleMainThread(t1);
        networkMonitor.sampleMainThread(t1);

        assertEquals(2.0, chunkMonitor.getChunkLoadRatePerSec(), 0.05);
        assertEquals(1.0, chunkMonitor.getChunkGenRatePerSec(), 0.05);
        assertEquals(1.0, chunkMonitor.getChunkUnloadRatePerSec(), 0.05);
        assertEquals(1, entityMonitor.getTotalEntities());
        assertEquals(Integer.valueOf(1), entityMonitor.getEntitiesByType().get("ZOMBIE"));
        assertTrue((networkMonitor.getIncomingEventsPerSec() + networkMonitor.getOutgoingEventsPerSec()) >= 2.0);

        // Once deactivated (e.g. plugin disable), events must be ignored completely
        listener.setActive(false);
        assertFalse(listener.isActive());

        listener.onChunkLoad(new ChunkLoadEvent(dummyChunk, true));
        listener.onChunkUnload(new ChunkUnloadEvent(dummyChunk));
        listener.onEntitySpawn(new EntitySpawnEvent(zombie));
        listener.onPlayerJoin(new PlayerJoinEvent(player, "joined"));
        listener.onPlayerCommand(new PlayerCommandPreprocessEvent(player, "/os status"));
        listener.onPlayerQuit(new PlayerQuitEvent(player, "quit"));

        long t2 = t1 + 1_000_000_000L;
        chunkMonitor.sampleMainThread(t2);
        entityMonitor.sampleMainThread(t2);
        networkMonitor.sampleMainThread(t2);

        assertEquals(0.0, chunkMonitor.getChunkLoadRatePerSec(), 0.01);
        assertEquals(0.0, chunkMonitor.getChunkUnloadRatePerSec(), 0.01);
        assertEquals(0.0, networkMonitor.getIncomingEventsPerSec(), 0.01);
        assertEquals(0.0, networkMonitor.getOutgoingEventsPerSec(), 0.01);
        assertEquals(1, entityMonitor.getTotalEntities());
    }

    @Test
    public void testBoundedWorkerPoolExecutionFailureIsolationRejectionAndShutdown() throws Exception {
        BoundedWorkerPool pool = new BoundedWorkerPool(Logger.getLogger("PoolTest"), 1, 2, 16, 10L);
        assertEquals(1, pool.getCorePoolSize());
        assertEquals(2, pool.getMaximumPoolSize());
        assertEquals(16, pool.getQueueCapacity());

        // Adjust pool limits up and verify
        pool.adjustPoolLimits(2, 4);
        assertEquals(2, pool.getCorePoolSize());
        assertEquals(4, pool.getMaximumPoolSize());
        pool.adjustPoolLimits(1, 2);

        // 1. Failing task is isolated and increments failedTasks without killing workers
        final CountDownLatch recoveryLatch = new CountDownLatch(1);
        assertTrue(pool.submitAsync(new Runnable() {
            @Override
            public void run() {
                throw new IllegalStateException("Intentional failure isolation check");
            }
        }));
        assertTrue(pool.submitAsync(new Runnable() {
            @Override
            public void run() {
                recoveryLatch.countDown();
            }
        }));
        assertTrue(recoveryLatch.await(3, TimeUnit.SECONDS));

        // 2. Timer and delayed handles can be cancelled cleanly
        TaskHandle timerHandle = pool.scheduleAsyncTimer(new Runnable() {
            @Override
            public void run() {
            }
        }, 5000L, 5000L);
        assertFalse(timerHandle.isCancelled());
        timerHandle.cancel();
        assertTrue(timerHandle.isCancelled());

        final AtomicInteger completedDelayed = new AtomicInteger(0);
        final CountDownLatch delayedLatch = new CountDownLatch(1);
        TaskHandle delayedHandle = pool.scheduleAsyncLater(new Runnable() {
            @Override
            public void run() {
                completedDelayed.incrementAndGet();
                delayedLatch.countDown();
            }
        }, 10L);
        assertTrue(delayedLatch.await(3, TimeUnit.SECONDS));
        assertEquals(1, completedDelayed.get());
        assertFalse(delayedHandle.isCancelled());

        // 3. Saturate max=2 workers + queue=16 to exercise bounded rejection counter
        final CountDownLatch workersEntered = new CountDownLatch(2);
        final CountDownLatch releaseWorkers = new CountDownLatch(1);
        for (int i = 0; i < 24; i++) {
            pool.submitAsync(new Runnable() {
                @Override
                public void run() {
                    workersEntered.countDown();
                    try {
                        releaseWorkers.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        assertTrue(workersEntered.await(3, TimeUnit.SECONDS));
        releaseWorkers.countDown();

        pool.shutdownGracefully(3L);

        assertTrue(pool.getSubmittedTasks() >= 26L);
        assertTrue(pool.getFailedTasks() >= 1L);
        assertTrue(pool.getRejectedTasks() >= 1L);
        assertTrue(pool.getCompletedTasks() >= 1L);

        // Submissions after shutdown must return false / NoOpTaskHandle
        assertFalse(pool.submitAsync(new Runnable() {
            @Override
            public void run() {
            }
        }));
        TaskHandle postShutdownTimer = pool.scheduleAsyncTimer(new Runnable() {
            @Override
            public void run() {
            }
        }, 10L, 10L);
        assertTrue(postShutdownTimer.isCancelled());
    }

    private static EntityDeathEvent createEntityDeathEvent(LivingEntity entity) throws Exception {
        for (Constructor<?> ctor : EntityDeathEvent.class.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 2 && params[0].isAssignableFrom(LivingEntity.class) && java.util.List.class.isAssignableFrom(params[1])) {
                return (EntityDeathEvent) ctor.newInstance(entity, Collections.emptyList());
            }
            if (params.length == 3 && params[0].isAssignableFrom(LivingEntity.class) && java.util.List.class.isAssignableFrom(params[2])) {
                return (EntityDeathEvent) ctor.newInstance(entity, null, Collections.emptyList());
            }
        }
        throw new IllegalStateException("No compatible EntityDeathEvent constructor found");
    }
}

