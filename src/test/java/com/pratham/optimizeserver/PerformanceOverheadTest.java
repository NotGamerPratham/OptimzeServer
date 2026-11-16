package com.pratham.optimizeserver;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.pratham.optimizeserver.compatibility.CompatibilityManager;
import com.pratham.optimizeserver.metrics.FrozenCounts;
import com.pratham.optimizeserver.metrics.MetricRegistry;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.metrics.RollingWindow;
import com.pratham.optimizeserver.metrics.TickDurationRing;
import com.pratham.optimizeserver.monitoring.CpuMonitor;
import com.pratham.optimizeserver.monitoring.EntityMonitor;
import com.pratham.optimizeserver.monitoring.MemoryMonitor;
import com.pratham.optimizeserver.monitoring.PaperTickEventHook;
import com.pratham.optimizeserver.monitoring.TickMonitor;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the main-thread and heap overhead reductions: single-ring tick windows, Paper
 * ServerTickEndEvent sampling, allocation-free tile counts, shared snapshot maps, and
 * module-safe JVM management bean access.
 */
public class PerformanceOverheadTest {

    @Test
    public void testTickDurationRingWindowsMatchExpectedAverages() {
        TickDurationRing ring = new TickDurationRing(64);
        long base = 1_000_000_000_000L;
        for (int i = 0; i < 40; i++) {
            ring.record(i < 20 ? 10.0 : 30.0, base + i * 50_000_000L);
        }
        long now = base + 39 * 50_000_000L;
        // Last 1s (cutoff now-1s) covers ticks 20..39 (all 30ms) plus tick 19 at exactly the cutoff
        RollingWindow.WindowAverage last1s = ring.computeAverage(1_000_000_000L, now);
        assertEquals(21, last1s.getSampleCount());
        assertEquals((20 * 30.0 + 10.0) / 21.0, last1s.getAverage(), 1e-6);

        RollingWindow.WindowStats all = ring.computeStats(10_000_000_000L, now);
        assertEquals(40, all.getSampleCount());
        assertEquals(20.0, all.getAverage(), 1e-6);
        assertEquals(10.0, all.getMin(), 1e-9);
        assertEquals(30.0, all.getMax(), 1e-9);
        assertEquals(30.0, all.getP95(), 1e-9);

        // Wrap past capacity: only the newest 64 samples remain and averages stay exact
        for (int i = 40; i < 200; i++) {
            ring.record(5.0, base + i * 50_000_000L);
        }
        RollingWindow.WindowAverage wrapped = ring.computeAverage(Long.MAX_VALUE / 4, base + 199 * 50_000_000L);
        assertEquals(64, wrapped.getSampleCount());
        assertEquals(5.0, wrapped.getAverage(), 1e-9);

        // Out-of-order timestamps are clamped instead of corrupting the search
        ring.record(7.0, base);
        assertEquals(64, ring.computeAverage(Long.MAX_VALUE / 4, base + 199 * 50_000_000L).getSampleCount());

        assertEquals(RollingWindow.WindowAverage.EMPTY, new TickDurationRing(16).computeAverage(1L, 0L));
    }

    @Test
    public void testPaperTickEventHookRecordsExactTickDurations() {
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(server, true);
        MetricRegistry registry = new MetricRegistry(100, 16);
        TickMonitor tickMonitor = new TickMonitor(server, cm.getCapabilities(), registry);

        final AtomicReference<Class<?>> registeredEvent = new AtomicReference<Class<?>>();
        final AtomicReference<EventExecutor> registeredExecutor = new AtomicReference<EventExecutor>();
        final AtomicReference<EventPriority> registeredPriority = new AtomicReference<EventPriority>();
        final PluginManager pm = (PluginManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PluginManager.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("registerEvent".equals(method.getName()) && args != null && args.length >= 5) {
                            registeredEvent.set((Class<?>) args[0]);
                            registeredPriority.set((EventPriority) args[2]);
                            registeredExecutor.set((EventExecutor) args[3]);
                        }
                        return null;
                    }
                });
        final Server baseServer = server;
        Server paperServer = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Server.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("getPluginManager".equals(method.getName())) return pm;
                        return method.invoke(baseServer, args);
                    }
                });
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Plugin.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("isEnabled".equals(method.getName())) return Boolean.TRUE;
                        if ("getName".equals(method.getName())) return "OptimizeServer";
                        return null;
                    }
                });

        PaperTickEventHook hook = PaperTickEventHook.tryRegister(plugin, paperServer, tickMonitor, null);
        assertNotNull(hook);
        assertEquals(ServerTickEndEvent.class, registeredEvent.get());
        assertEquals(EventPriority.MONITOR, registeredPriority.get());

        long start = System.nanoTime();
        for (int i = 0; i < 30; i++) {
            try {
                registeredExecutor.get().execute(hook, new ServerTickEndEvent(i % 2 == 0 ? 10.0 : 70.0));
            } catch (Exception e) {
                fail(e);
            }
        }
        assertEquals(30L, tickMonitor.getTickEventCount());
        tickMonitor.refreshAggregates(System.nanoTime());
        RollingWindow.WindowStats stats = tickMonitor.getLatestMsptStats();
        assertEquals(30, stats.getSampleCount());
        assertEquals(40.0, stats.getAverage(), 1e-6);
        assertEquals(70.0, stats.getMax(), 1e-9);
        assertTrue(System.nanoTime() >= start);

        // Unrelated events and post-unregister events are ignored
        hook.handle(new Event() {
            @Override
            public org.bukkit.event.HandlerList getHandlers() {
                return null;
            }
        });
        hook.unregister();
        hook.handle(new ServerTickEndEvent(999.0));
        assertEquals(30L, tickMonitor.getTickEventCount());
    }

    public interface PaperLikeWorld extends World {
        int getEntityCount();
        int getTileEntityCount();
    }

    @Test
    public void testEntityMonitorUsesWorldTileEntityCountWithoutChunkScan() {
        final AtomicInteger loadedChunkCalls = new AtomicInteger(0);
        final World world = (World) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PaperLikeWorld.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String n = method.getName();
                        if ("getName".equals(n)) return "world";
                        if ("getEntityCount".equals(n)) return Integer.valueOf(42);
                        if ("getTileEntityCount".equals(n)) return Integer.valueOf(1234);
                        if ("getLoadedChunks".equals(n)) {
                            loadedChunkCalls.incrementAndGet();
                            return new Chunk[0];
                        }
                        return null;
                    }
                });
        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Server.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String n = method.getName();
                        if ("getWorlds".equals(n)) return Collections.singletonList(world);
                        if ("getName".equals(n)) return "Paper";
                        if ("getVersion".equals(n)) return "git-Paper-496 (MC: 1.20.4)";
                        if ("getBukkitVersion".equals(n)) return "1.20.4-R0.1-SNAPSHOT";
                        return null;
                    }
                });
        CompatibilityManager cm = new CompatibilityManager(server, true);
        EntityMonitor monitor = new EntityMonitor(server, null, cm.getCapabilities());
        monitor.setCoalesceWindowMillis(0L);
        monitor.sampleMainThread(System.nanoTime());

        assertEquals(1234, monitor.getTileEntityCount());
        assertEquals(1L, monitor.getFastTileCountReads());
        assertEquals(0, loadedChunkCalls.get());
    }

    @Test
    public void testSnapshotsShareFrozenMapsAndCopyMutableOnes() {
        Map<String, Integer> fresh = new HashMap<String, Integer>();
        fresh.put("ZOMBIE", Integer.valueOf(3));
        Map<String, Integer> frozen = FrozenCounts.adopt(fresh);

        MetricSnapshot a = MetricSnapshot.builder().entitiesByType(frozen).build();
        MetricSnapshot b = MetricSnapshot.builder().entitiesByType(frozen).build();
        assertSame(a.getEntitiesByType(), b.getEntitiesByType());
        assertEquals(Integer.valueOf(3), a.getEntitiesByType().get("ZOMBIE"));
        assertThrows(UnsupportedOperationException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                a.getEntitiesByType().put("CREEPER", Integer.valueOf(1));
            }
        });

        Map<String, Integer> mutable = new HashMap<String, Integer>();
        mutable.put("PIG", Integer.valueOf(1));
        MetricSnapshot c = MetricSnapshot.builder().entitiesByWorld(mutable).build();
        mutable.put("PIG", Integer.valueOf(99));
        assertEquals(Integer.valueOf(1), c.getEntitiesByWorld().get("PIG"));
        assertTrue(FrozenCounts.isFrozen(c.getEntitiesByWorld()));
    }

    @Test
    public void testJvmManagementTelemetryWorksOnModernJava() throws Exception {
        boolean hotspotOsBean;
        try {
            hotspotOsBean = Class.forName("com.sun.management.OperatingSystemMXBean")
                    .isInstance(ManagementFactory.getOperatingSystemMXBean());
        } catch (ClassNotFoundException e) {
            hotspotOsBean = false;
        }
        CpuMonitor cpu = new CpuMonitor();
        cpu.sample(System.nanoTime());
        if (hotspotOsBean) {
            assertTrue(cpu.isNativeProcessCpuLoadAvailable(), "process CPU load must resolve via the exported interface");
        }

        MemoryMonitor memory = new MemoryMonitor();
        if (memory.isAllocationTelemetryAvailable()) {
            memory.sample(System.nanoTime());
            long checksum = 0L;
            for (int i = 0; i < 64; i++) {
                byte[] chunk = new byte[256 * 1024];
                chunk[i] = (byte) i;
                checksum += chunk[i];
            }
            Thread.sleep(80L);
            memory.sample(System.nanoTime());
            assertTrue(memory.getRecentAllocatedBytesPerSec() > 0L, "allocation rate should be measured (checksum " + checksum + ")");
        }
    }
}
