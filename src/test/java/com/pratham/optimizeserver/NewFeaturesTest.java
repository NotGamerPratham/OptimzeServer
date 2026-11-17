package com.pratham.optimizeserver;

import com.pratham.optimizeserver.analyzer.ServerConfigAuditor;
import com.pratham.optimizeserver.commands.OptimizeServerCommand;
import com.pratham.optimizeserver.compatibility.CompatibilityManager;
import com.pratham.optimizeserver.compatibility.JavaRuntimeInfo;
import com.pratham.optimizeserver.compatibility.ServerPlatform;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.core.PerformanceEngine;
import com.pratham.optimizeserver.metrics.MetricHistory;
import com.pratham.optimizeserver.metrics.MetricRegistry;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.monitoring.EntityMonitor;
import com.pratham.optimizeserver.monitoring.LagSpikeRecorder;
import com.pratham.optimizeserver.monitoring.TickMonitor;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the 1.4.0 features: lag spike recorder, tickable block entities, per-world breakdown,
 * the new /os commands, and the additional /os tune checks.
 */
public class NewFeaturesTest {

    @Test
    public void testLagSpikeRecorderCapturesOnlySlowTicksWithContext() {
        MetricHistory history = new MetricHistory(16);
        history.append(MetricSnapshot.builder()
                .loadedChunks(1200).totalEntities(3400).onlinePlayers(12)
                .heapUsedBytes(950L).heapMaxBytes(1000L)
                .chunkGenRatePerSec(0.0)
                .build());
        LagSpikeRecorder recorder = new LagSpikeRecorder(history, 3);
        final AtomicInteger notified = new AtomicInteger(0);
        recorder.setListener(new LagSpikeRecorder.SpikeListener() {
            @Override
            public void onSpike(LagSpikeRecorder.LagSpike spike) {
                notified.incrementAndGet();
            }
        });
        recorder.configure(true, 100.0, 3);

        assertNull(recorder.onTick(48.0));
        assertNull(recorder.onTick(99.9));
        LagSpikeRecorder.LagSpike spike = recorder.onTick(240.0);
        assertNotNull(spike);
        assertEquals(1200, spike.getLoadedChunks());
        assertEquals(3400, spike.getTotalEntities());
        assertEquals(12, spike.getOnlinePlayers());
        assertEquals("Heap pressure", spike.getLikelyCause());
        assertEquals(1, notified.get());

        // History size is clamped to a minimum of 5
        for (int i = 0; i < 6; i++) {
            recorder.onTick(150.0 + i * 10.0);
        }
        List<LagSpikeRecorder.LagSpike> recent = recorder.getRecentSpikes(10);
        assertEquals(5, recent.size(), "ring keeps only the configured history size");
        assertEquals(200.0, recent.get(0).getDurationMs(), 1e-9, "newest first");
        assertEquals(7L, recorder.getTotalSpikes());
        assertEquals(240.0, recorder.getWorstMs(), 1e-9);
        assertEquals(5, recorder.countSince(System.currentTimeMillis() - 60_000L));

        // Thresholds below a normal 50 ms tick are clamped so healthy ticks are never reported
        recorder.configure(true, 10.0, 3);
        assertEquals(55.0, recorder.getThresholdMs(), 1e-9);
        assertNull(recorder.onTick(50.0));

        recorder.configure(false, 100.0, 3);
        assertNull(recorder.onTick(5000.0));

        assertEquals("Garbage collection pause",
                new LagSpikeRecorder.LagSpike(0L, 120.0, 90L, 0, 0, 0, 10.0, 0.0).getLikelyCause());
        assertEquals("World generation (new chunks)",
                new LagSpikeRecorder.LagSpike(0L, 120.0, 0L, 0, 0, 0, 10.0, 20.0).getLikelyCause());
    }

    @Test
    public void testTickMonitorFeedsSpikeRecorderFromTickEvents() {
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(server, true);
        MetricRegistry registry = new MetricRegistry(100, 16);
        TickMonitor tickMonitor = new TickMonitor(server, cm.getCapabilities(), registry);
        LagSpikeRecorder recorder = new LagSpikeRecorder(registry.getHistory(), 10);
        tickMonitor.setSpikeRecorder(recorder);

        long now = System.nanoTime();
        tickMonitor.recordTickEndEvent(30.0, now);
        tickMonitor.recordTickEndEvent(180.0, now + 50_000_000L);
        tickMonitor.recordTickEndEvent(35.0, now + 230_000_000L);
        assertEquals(1L, recorder.getTotalSpikes());
        assertEquals(180.0, recorder.getRecentSpikes(1).get(0).getDurationMs(), 1e-9);
    }

    public interface PaperCountsWorld extends World {
        int getEntityCount();
        int getTileEntityCount();
        int getTickableTileEntityCount();
        int getPlayerCount();
    }

    private static World paperWorld(final String name, final int tiles, final int tickable, final int players) {
        return (World) Proxy.newProxyInstance(
                NewFeaturesTest.class.getClassLoader(), new Class<?>[]{PaperCountsWorld.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String n = method.getName();
                        if ("getName".equals(n)) return name;
                        if ("getTileEntityCount".equals(n)) return Integer.valueOf(tiles);
                        if ("getTickableTileEntityCount".equals(n)) return Integer.valueOf(tickable);
                        if ("getPlayerCount".equals(n)) return Integer.valueOf(players);
                        if ("getEntities".equals(n)) return Collections.emptyList();
                        if ("getPlayers".equals(n)) return Collections.emptyList();
                        return null;
                    }
                });
    }

    @Test
    public void testEntityMonitorReportsTickableAndPerWorldCounts() {
        final List<World> worlds = Arrays.asList(
                paperWorld("world", 900, 300, 7),
                paperWorld("world_nether", 100, 20, 2));
        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Server.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String n = method.getName();
                        if ("getWorlds".equals(n)) return worlds;
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

        assertEquals(1000, monitor.getTileEntityCount());
        assertEquals(320, monitor.getTickableTileEntityCount());
        assertEquals(Integer.valueOf(900), monitor.getTilesByWorld().get("world"));
        assertEquals(Integer.valueOf(20), monitor.getTickableTilesByWorld().get("world_nether"));
        assertEquals(Integer.valueOf(7), monitor.getPlayersByWorld().get("world"));
        assertEquals(Integer.valueOf(2), monitor.getPlayersByWorld().get("world_nether"));
    }

    @Test
    public void testEntityMonitorTickableUnavailableOnSpigotWorlds() {
        final World spigotWorld = (World) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{World.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String n = method.getName();
                        if ("getName".equals(n)) return "world";
                        if ("getLoadedChunks".equals(n)) return new org.bukkit.Chunk[0];
                        if ("getEntities".equals(n)) return Collections.emptyList();
                        if ("getPlayers".equals(n)) return Collections.emptyList();
                        return null;
                    }
                });
        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Server.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String n = method.getName();
                        if ("getWorlds".equals(n)) return Collections.singletonList(spigotWorld);
                        if ("getName".equals(n)) return "CraftBukkit";
                        if ("getVersion".equals(n)) return "git-Spigot-1234 (MC: 1.16.5)";
                        if ("getBukkitVersion".equals(n)) return "1.16.5-R0.1-SNAPSHOT";
                        return null;
                    }
                });
        CompatibilityManager cm = new CompatibilityManager(server, true);
        EntityMonitor monitor = new EntityMonitor(server, null, cm.getCapabilities());
        monitor.setCoalesceWindowMillis(0L);
        monitor.sampleMainThread(System.nanoTime());
        assertEquals(-1, monitor.getTickableTileEntityCount());
        assertEquals(Integer.valueOf(0), monitor.getPlayersByWorld().get("world"));
    }

    @Test
    public void testSpikesAndWorldsCommands(@TempDir File tempDir) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(
                NewFeaturesTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        cfg.set("integrations.auto-install.enabled", false);
        ConfigManager configManager = new ConfigManager(null, cfg, Logger.getLogger("NewFeaturesTest"));
        configManager.processConfiguration(cfg);
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        PerformanceEngine engine = new PerformanceEngine(null, server, tempDir, Logger.getLogger("NewFeaturesEngine"), configManager);
        engine.start();
        try {
            OptimizeServerCommand command = new OptimizeServerCommand(engine, "1.4.0");
            TestServerHarness.CapturingCommandSender admin = new TestServerHarness.CapturingCommandSender(
                    "Admin", true, Collections.<String>emptySet());

            command.onCommand(admin.getSender(), null, "os", new String[]{"spikes"});
            assertTrue(joined(admin).contains("No ticks over the threshold"));

            engine.getTickMonitor().recordTickEndEvent(250.0, System.nanoTime());
            admin.clear();
            command.onCommand(admin.getSender(), null, "os", new String[]{"spikes"});
            assertTrue(joined(admin).contains("250 ms"), joined(admin));

            admin.clear();
            command.onCommand(admin.getSender(), null, "os", new String[]{"spikes", "clear"});
            assertEquals(0L, engine.getLagSpikeRecorder().getTotalSpikes());

            admin.clear();
            command.onCommand(admin.getSender(), null, "os", new String[]{"worlds"});
            assertTrue(joined(admin).contains("Per-World Load Breakdown"));

            List<String> completions = command.onTabComplete(admin.getSender(), null, "os", new String[]{"sp"});
            assertTrue(completions.contains("spikes"));
        } finally {
            engine.stop();
        }
    }

    private static String joined(TestServerHarness.CapturingCommandSender sender) {
        StringBuilder sb = new StringBuilder();
        for (String m : sender.getMessages()) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    @Test
    public void testAdditionalTuneChecks() {
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(server, true);
        ServerConfigAuditor auditor = new ServerConfigAuditor();

        JavaRuntimeInfo java17 = new JavaRuntimeInfo("17.0.9", 17, "HotSpot", "Oracle", 4,
                Arrays.asList("-Xms4G", "-Xmx4G", "-XX:+UseG1GC", "-Xmn1G", "-XX:MaxGCPauseMillis=500"),
                Collections.singletonList("G1 Young Generation"));
        List<ServerConfigAuditor.TuningSuggestion> out = new ArrayList<ServerConfigAuditor.TuningSuggestion>();
        auditor.auditJavaVersion(java17, cm.getReport(), ServerPlatform.PAPER, out);
        auditor.auditG1Hygiene(java17, out);
        List<String> keys = new ArrayList<String>();
        for (ServerConfigAuditor.TuningSuggestion s : out) {
            keys.add(s.getKey());
        }
        assertTrue(keys.contains("Java version"), keys.toString());
        assertTrue(keys.contains("-Xmn"), keys.toString());
        assertTrue(keys.contains("-XX:MaxGCPauseMillis"), keys.toString());

        JavaRuntimeInfo java21Clean = new JavaRuntimeInfo("21.0.5", 21, "HotSpot", "Oracle", 4,
                Arrays.asList("-Xms4G", "-Xmx4G", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=130"),
                Collections.singletonList("G1 Young Generation"));
        List<ServerConfigAuditor.TuningSuggestion> clean = new ArrayList<ServerConfigAuditor.TuningSuggestion>();
        auditor.auditJavaVersion(java21Clean, cm.getReport(), ServerPlatform.PAPER, clean);
        auditor.auditG1Hygiene(java21Clean, clean);
        assertTrue(clean.isEmpty(), "a clean Java 21 G1 setup needs no extra suggestions");
    }
}
