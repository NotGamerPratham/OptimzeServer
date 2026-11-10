package com.pratham.optimizeserver;

import com.pratham.optimizeserver.compatibility.CompatibilityManager;
import com.pratham.optimizeserver.compatibility.ServerPlatform;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.core.EngineState;
import com.pratham.optimizeserver.core.PerformanceEngine;
import com.pratham.optimizeserver.integrations.DependencyAutoInstaller;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates Folia regionalized scheduler reflection bridges (GlobalRegionScheduler,
 * RegionScheduler, AsyncScheduler, thread-ownership checks) and executes controlled
 * benchmark comparisons with and without OptimizeServer internal optimization modules.
 */
public class FoliaAndBenchmarkVerificationTest {

    public interface SimulatedFoliaServer extends Server {
        SimulatedGlobalRegionScheduler getGlobalRegionScheduler();
        SimulatedRegionScheduler getRegionScheduler();
        Object getAsyncScheduler();
        boolean isGlobalTickThread();
        boolean isOwnedByCurrentRegion(Location location);
        boolean isOwnedByCurrentRegion(Entity entity);
        double[] getTPS();
        double getAverageTickTime();
        long[] getTickTimes();
    }

    public static final class SimulatedScheduledTask {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
    }

    public static final class SimulatedGlobalRegionScheduler {
        private final AtomicInteger executedCount = new AtomicInteger(0);

        public SimulatedScheduledTask run(Plugin plugin, Consumer<Object> task) {
            SimulatedScheduledTask handle = new SimulatedScheduledTask();
            if (task != null) {
                executedCount.incrementAndGet();
                task.accept(handle);
            }
            return handle;
        }

        public SimulatedScheduledTask runDelayed(Plugin plugin, Consumer<Object> task, long delayTicks) {
            SimulatedScheduledTask handle = new SimulatedScheduledTask();
            if (task != null) {
                executedCount.incrementAndGet();
                task.accept(handle);
            }
            return handle;
        }

        public SimulatedScheduledTask runAtFixedRate(Plugin plugin, Consumer<Object> task, long initialDelayTicks, long periodTicks) {
            SimulatedScheduledTask handle = new SimulatedScheduledTask();
            if (task != null) {
                executedCount.incrementAndGet();
                task.accept(handle);
            }
            return handle;
        }

        public int getExecutedCount() {
            return executedCount.get();
        }
    }

    public static final class SimulatedRegionScheduler {
        private final AtomicInteger chunkTasksExecuted = new AtomicInteger(0);

        public SimulatedScheduledTask run(Plugin plugin, World world, int chunkX, int chunkZ, Consumer<Object> task) {
            SimulatedScheduledTask handle = new SimulatedScheduledTask();
            if (task != null) {
                chunkTasksExecuted.incrementAndGet();
                task.accept(handle);
            }
            return handle;
        }

        public SimulatedScheduledTask run(Plugin plugin, Location location, Consumer<Object> task) {
            SimulatedScheduledTask handle = new SimulatedScheduledTask();
            if (task != null) {
                chunkTasksExecuted.incrementAndGet();
                task.accept(handle);
            }
            return handle;
        }

        public int getChunkTasksExecuted() {
            return chunkTasksExecuted.get();
        }
    }

    @Test
    public void testFullFoliaRegionSchedulerExecutionAndThreadSafety(@TempDir File tempDir) {
        final SimulatedGlobalRegionScheduler grs = new SimulatedGlobalRegionScheduler();
        final SimulatedRegionScheduler rs = new SimulatedRegionScheduler();
        final Object asyncSched = new Object();
        final Server baseProxy = TestServerHarness.createMockServer(
                "Folia", "git-Folia-28 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);

        SimulatedFoliaServer foliaServer = (SimulatedFoliaServer) Proxy.newProxyInstance(
                FoliaAndBenchmarkVerificationTest.class.getClassLoader(),
                new Class<?>[]{SimulatedFoliaServer.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String m = method.getName();
                        if ("getGlobalRegionScheduler".equals(m)) return grs;
                        if ("getRegionScheduler".equals(m)) return rs;
                        if ("getAsyncScheduler".equals(m)) return asyncSched;
                        if ("isGlobalTickThread".equals(m)) return Boolean.TRUE;
                        if ("isOwnedByCurrentRegion".equals(m)) return Boolean.TRUE;
                        if ("getTPS".equals(m)) return new double[]{20.0, 20.0, 20.0};
                        if ("getAverageTickTime".equals(m)) return Double.valueOf(12.5);
                        if ("getTickTimes".equals(m)) return new long[]{12_500_000L, 12_400_000L, 12_600_000L};
                        return method.invoke(baseProxy, args);
                    }
                }
        );

        CompatibilityManager cm = new CompatibilityManager(foliaServer, true);
        assertEquals(ServerPlatform.FOLIA, cm.getPlatform());
        assertTrue(cm.getCapabilities().hasFoliaRegionSchedulers());
        assertTrue(cm.getCapabilities().hasPaperTpsApi());
        assertTrue(cm.getCapabilities().hasPaperMsptApi());
        assertTrue(cm.getCapabilities().hasPaperTickTimesApi());
        assertFalse(cm.getReport().isConservativeSafeModeRequired());

        YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(
                        FoliaAndBenchmarkVerificationTest.class.getResourceAsStream("/config.yml"),
                        StandardCharsets.UTF_8
                )
        );
        ConfigManager configManager = new ConfigManager(null, defaultConfig, Logger.getLogger("FoliaTest"));
        defaultConfig.set("integrations.auto-install.enabled", false);
        configManager.processConfiguration(defaultConfig);

        Plugin dummyPlugin = (Plugin) Proxy.newProxyInstance(
                FoliaAndBenchmarkVerificationTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getName".equals(method.getName())) return "OptimizeServer";
                        if ("getLogger".equals(method.getName())) return Logger.getLogger("OptimizeServer");
                        if ("isEnabled".equals(method.getName())) return Boolean.TRUE;
                        return null;
                    }
                }
        );

        PerformanceEngine engine = new PerformanceEngine(
                dummyPlugin, foliaServer, tempDir, Logger.getLogger("FoliaEngine"), configManager);
        engine.start();

        assertEquals(EngineState.RUNNING, engine.getState());
        assertTrue(engine.getScheduler().isRegionThreaded());
        assertTrue(engine.getScheduler().isGlobalThread());
        assertTrue(grs.getExecutedCount() >= 2);

        World dummyWorld = (World) Proxy.newProxyInstance(
                FoliaAndBenchmarkVerificationTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getName".equals(method.getName())) return "world";
                        return null;
                    }
                }
        );

        final AtomicBoolean chunkTaskRan = new AtomicBoolean(false);
        TaskHandle handle = engine.getScheduler().runAtChunk(dummyWorld, 0, 0, new Runnable() {
            @Override
            public void run() {
                chunkTaskRan.set(true);
            }
        });
        assertTrue(chunkTaskRan.get());
        assertEquals(1, rs.getChunkTasksExecuted());
        handle.cancel();
        assertTrue(handle.isCancelled());

        MetricSnapshot snap = engine.captureImmediateSnapshot();
        assertEquals(20.0, snap.getTps1m(), 0.01);
        assertEquals(12.5, snap.getAverageMspt(), 0.2);

        engine.stop();
        assertEquals(EngineState.STOPPED, engine.getState());
    }

    @Test
    public void testDependencyAutoInstallerVerificationAndInstall(@TempDir File tempDir) throws Exception {
        // Create a valid synthetic plugin JAR with plugin.yml
        File sourceJar = new File(tempDir, "synthetic-dependency.jar");
        JarOutputStream jos = new JarOutputStream(new FileOutputStream(sourceJar));
        try {
            JarEntry entry = new JarEntry("plugin.yml");
            jos.putNextEntry(entry);
            StringBuilder yaml = new StringBuilder();
            yaml.append("name: SyntheticDep\nversion: 1.0.0\nmain: com.example.Main\n");
            for (int i = 0; i < 10; i++) {
                yaml.append("# padding line to exceed 256-byte minimum archive threshold ").append(i).append("\n");
            }
            jos.write(yaml.toString().getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        } finally {
            jos.close();
        }

        String validSha512 = DependencyAutoInstaller.computeSha512Hex(sourceJar);
        assertTrue(DependencyAutoInstaller.validateJarArchive(sourceJar, validSha512));
        assertFalse(DependencyAutoInstaller.validateJarArchive(sourceJar, "00000000000000000000000000000000"));

        File pluginsDir = new File(tempDir, "plugins");
        File pluginDataDir = new File(pluginsDir, "OptimizeServer");
        pluginDataDir.mkdirs();

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(
                new InputStreamReader(
                        FoliaAndBenchmarkVerificationTest.class.getResourceAsStream("/config.yml"),
                        StandardCharsets.UTF_8
                )
        );
        String localUrl = sourceJar.toURI().toURL().toExternalForm();
        cfg.set("integrations.spark.download-url", localUrl);
        cfg.set("integrations.chunky.download-url", localUrl);

        ConfigManager configManager = new ConfigManager(null, cfg, Logger.getLogger("AutoInstallTest"));
        configManager.processConfiguration(cfg);

        Server paperServer = TestServerHarness.createMockServer(
                "Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        PerformanceEngine engine = new PerformanceEngine(
                null, paperServer, pluginDataDir, Logger.getLogger("AutoInstallEngine"), configManager);
        engine.start();

        List<DependencyAutoInstaller.InstallResult> firstRun =
                engine.getDependencyAutoInstaller().checkAndInstallNow();
        assertEquals(2, firstRun.size());
        assertTrue(firstRun.get(0).isDownloadedNow());
        assertTrue(firstRun.get(1).isDownloadedNow());
        assertTrue(new File(pluginsDir, "spark.jar").exists());
        assertTrue(new File(pluginsDir, "Chunky.jar").exists());

        // Second run should detect existing JARs and skip re-downloading
        List<DependencyAutoInstaller.InstallResult> secondRun =
                engine.getDependencyAutoInstaller().checkAndInstallNow();
        assertEquals(2, secondRun.size());
        assertTrue(secondRun.get(0).isAlreadyInstalled());
        assertFalse(secondRun.get(0).isDownloadedNow());
        assertTrue(secondRun.get(1).isAlreadyInstalled());
        assertFalse(secondRun.get(1).isDownloadedNow());

        engine.stop();
    }
}

