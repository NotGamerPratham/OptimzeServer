package com.pratham.optimizeserver;

import com.pratham.optimizeserver.commands.CommandPermissions;
import com.pratham.optimizeserver.commands.OptimizeServerCommand;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.config.ConfigMigrator;
import com.pratham.optimizeserver.core.EngineState;
import com.pratham.optimizeserver.core.PerformanceEngine;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.metrics.RollingWindow;
import com.pratham.optimizeserver.optimizer.OptimizationModule;
import com.pratham.optimizeserver.reporting.DiagnosticReport;
import com.pratham.optimizeserver.reporting.ReportGenerator;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for PerformanceEngine lifecycle, ConfigManager migration/validation,
 * Hybrid Optimization Engine modules & rollbacks, Command permissions & subcommands, Reporting,
 * and controlled benchmark verification.
 */
public class OptimizationAndLifecycleTest {

    @Test
    public void testConfigMigrationAndValidation() {
        YamlConfiguration legacyV1 = new YamlConfiguration();
        legacyV1.set("config-version", 1);
        legacyV1.set("general.telemetry-enabled", true); // Should be sanitized to false
        legacyV1.set("monitoring.sampling.tick-sample-interval-ticks", 999); // Should be clamped to 20
        legacyV1.set("safety.enforce-gameplay-invariants", false); // Must be forced to true

        ConfigManager cm = new ConfigManager(null, legacyV1, Logger.getLogger("Test"));
        cm.processConfiguration(legacyV1);

        assertEquals(3, legacyV1.getInt("config-version"));
        assertFalse(cm.getBoolean("general.telemetry-enabled", false));
        assertEquals(20, cm.getInt("monitoring.sampling.tick-sample-interval-ticks", 1));
        assertTrue(cm.getBoolean("safety.enforce-gameplay-invariants", false));
        assertFalse(cm.getLastValidationMessages().isEmpty());

        // Verify config lookup cache hits
        cm.setCacheEnabled(true);
        cm.clearCache();
        long hitsBefore = cm.getCacheHits();
        for (int i = 0; i < 10; i++) {
            assertTrue(cm.getBoolean("safety.enforce-gameplay-invariants", false));
        }
        assertTrue(cm.getCacheHits() > hitsBefore);
    }

    @Test
    public void testFullEngineLifecycleOptimizationsCommandsAndReporting(@TempDir File tempDir) throws Exception {
        YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(
                        OptimizationAndLifecycleTest.class.getResourceAsStream("/config.yml"),
                        StandardCharsets.UTF_8
                )
        );
        ConfigManager configManager = new ConfigManager(null, defaultConfig, Logger.getLogger("Test"));
        configManager.processConfiguration(defaultConfig);

        Server purpurServer = TestServerHarness.createMockServer(
                "Purpur", "git-Purpur-2150 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);

        PerformanceEngine engine = new PerformanceEngine(
                null,
                purpurServer,
                tempDir,
                Logger.getLogger("OptimizeServerTest"),
                configManager
        );

        engine.start();
        assertEquals(EngineState.RUNNING, engine.getState());
        assertNotNull(engine.getLatestSnapshot());

        // Verify all 13 optimization modules are registered and enabled
        assertEquals(13, engine.getOptimizationManager().getAllModules().size());
        assertEquals(13, engine.getOptimizationManager().getRegistry().getActiveModules().size());

        // Test adaptive monitoring sampler & telemetry batcher response to high MSPT
        MetricSnapshot highMspt = MetricSnapshot.builder()
                .averageMspt(48.0)
                .processCpuLoadPercent(90.0)
                .build();
        engine.getOptimizationManager().evaluateConditionalModules(highMspt);
        assertEquals(400, engine.getOptimizationManager().getEffectiveWorldSampleIntervalTicks());
        assertEquals(1, engine.getScheduler().getWorkerPool().getCorePoolSize());
        assertFalse(engine.getMemoryMonitor().isThreadAllocationSamplingEnabled());

        MetricSnapshot calmTick = MetricSnapshot.builder()
                .averageMspt(12.0)
                .processCpuLoadPercent(20.0)
                .build();
        engine.getOptimizationManager().evaluateConditionalModules(calmTick);
        assertTrue(engine.getMemoryMonitor().isThreadAllocationSamplingEnabled());

        // Verify low-memory-footprint-governor compacts under 1 GB heap pressure and restores on disable
        assertTrue(engine.getOptimizationManager().disableModule("low-memory-footprint-governor"));
        int baselineCap = engine.getMetricRegistry().getHistory().getCapacity();
        assertTrue(baselineCap > 90);
        assertTrue(engine.getOptimizationManager().enableModule("low-memory-footprint-governor"));
        MetricSnapshot oneGbPressured = MetricSnapshot.builder()
                .heapMaxBytes(1024L * 1024L * 1024L)
                .heapUsedBytes(900L * 1024L * 1024L)
                .build();
        engine.getOptimizationManager().evaluateConditionalModules(oneGbPressured);
        assertTrue(engine.getOptimizationManager().isLowMemoryCompacted());
        assertTrue(engine.getMetricRegistry().getHistory().getCapacity() <= 90);
        assertTrue(engine.getOptimizationManager().disableModule("low-memory-footprint-governor"));
        assertFalse(engine.getOptimizationManager().isLowMemoryCompacted());
        assertEquals(baselineCap, engine.getMetricRegistry().getHistory().getCapacity());
        assertTrue(engine.getOptimizationManager().enableModule("low-memory-footprint-governor"));

        // Verify zero-alloc-world-scanner and async-io-offloader toggles
        assertTrue(engine.getOptimizationManager().disableModule("zero-alloc-world-scanner"));
        assertFalse(engine.getEntityMonitor().isZeroAllocScanEnabled());
        assertTrue(engine.getOptimizationManager().enableModule("zero-alloc-world-scanner"));
        assertTrue(engine.getEntityMonitor().isZeroAllocScanEnabled());

        assertTrue(engine.getOptimizationManager().disableModule("async-io-offloader"));
        assertFalse(engine.getOptimizationManager().isAsyncIoOffloadEnabled());
        assertTrue(engine.getOptimizationManager().enableModule("async-io-offloader"));
        assertTrue(engine.getOptimizationManager().isAsyncIoOffloadEnabled());

        // Test module disable, enable, and rollback
        assertTrue(engine.getOptimizationManager().disableModule("config-lookup-cache"));
        assertFalse(engine.getConfigManager().isCacheEnabled());
        assertTrue(engine.getOptimizationManager().enableModule("config-lookup-cache"));
        assertTrue(engine.getConfigManager().isCacheEnabled());
        assertTrue(engine.getOptimizationManager().rollbackModule("config-lookup-cache", "Unit test rollback"));
        assertFalse(engine.getOptimizationManager().getRegistry().getModule("config-lookup-cache").isEnabled());
        assertTrue(engine.getOptimizationManager().enableModule("config-lookup-cache"));

        // Test command permission enforcement: unauthorized player rejected
        OptimizeServerCommand command = new OptimizeServerCommand(engine, "1.2.0");
        TestServerHarness.CapturingCommandSender unauthorized = new TestServerHarness.CapturingCommandSender(
                "RegularPlayer", false, Collections.<String>emptySet());
        command.onCommand(unauthorized.getSender(), null, "os", new String[]{"status"});
        assertTrue(unauthorized.getMessages().get(0).contains("Access denied"));
        assertTrue(command.onTabComplete(unauthorized.getSender(), null, "os", new String[]{""}).isEmpty());

        // Test authorized administrator executing every command family
        Set<String> adminPerms = new HashSet<String>();
        adminPerms.add(CommandPermissions.ADMIN);
        TestServerHarness.CapturingCommandSender admin = new TestServerHarness.CapturingCommandSender(
                "NotGamerPratham", true, adminPerms);

        String[][] commandMatrix = new String[][]{
                {"help", "1"},
                {"version"},
                {"status"},
                {"tps"},
                {"mspt"},
                {"cpu"},
                {"memory"},
                {"network"},
                {"entities"},
                {"chunks", "status"},
                {"chunks", "recommend"},
                {"diagnose", "full"},
                {"diagnose", "cpu"},
                {"diagnose", "memory"},
                {"diagnose", "chunks"},
                {"diagnose", "entities"},
                {"diagnose", "plugins"},
                {"profile", "status"},
                {"profile", "memory"},
                {"health"},
                {"optimize", "status"},
                {"optimize", "list", "1"},
                {"optimize", "disable", "diagnostic-result-cache"},
                {"optimize", "enable", "diagnostic-result-cache"},
                {"optimize", "rollback", "diagnostic-result-cache"},
                {"optimize", "enable", "diagnostic-result-cache"},
                {"tune"},
                {"capacity"},
                {"report", "generate"},
                {"report", "history"},
                {"reload"}
        };

        for (String[] cmdArgs : commandMatrix) {
            admin.clear();
            assertTrue(command.onCommand(admin.getSender(), null, "os", cmdArgs));
            assertFalse(admin.getMessages().isEmpty(), "Expected output for /os " + String.join(" ", cmdArgs));
        }

        // Verify tab completion
        List<String> rootTabs = command.onTabComplete(admin.getSender(), null, "os", new String[]{"d"});
        assertTrue(rootTabs.contains("diagnose"));
        List<String> optTabs = command.onTabComplete(admin.getSender(), null, "os", new String[]{"optimize", "enable", "config-"});
        assertTrue(optTabs.contains("config-lookup-cache"));

        // Verify report file exists on disk
        List<File> reportFiles = engine.getReportGenerator().listSavedReportFiles();
        assertFalse(reportFiles.isEmpty());
        assertTrue(reportFiles.get(0).exists());
        assertTrue(reportFiles.get(0).length() > 200L);

        // Verify Folia compatibility mode detection
        Server foliaServer = TestServerHarness.createMockServer(
                "Folia", "git-Folia-25 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        PerformanceEngine foliaEngine = new PerformanceEngine(
                null, foliaServer, tempDir, Logger.getLogger("FoliaTest"), configManager);
        foliaEngine.start();
        // Since mock Folia server proxy does not implement getGlobalRegionScheduler, CompatibilityManager
        // must detect the missing region scheduler and enter SAFE_MODE automatically!
        assertEquals(EngineState.SAFE_MODE, foliaEngine.getState());
        assertTrue(foliaEngine.getSafeModeController().isSafeModeActive());
        foliaEngine.stop();
        assertEquals(EngineState.STOPPED, foliaEngine.getState());

        // Stop main engine
        engine.stop();
        assertEquals(EngineState.STOPPED, engine.getState());
    }

    @Test
    public void testControlledBenchmarkInternalOptimizations() {
        // 1. Benchmark RollingWindow percentile calculation with vs without reusable scratch buffer
        RollingWindow windowUnoptimized = new RollingWindow(60L, 512);
        windowUnoptimized.setReuseScratchBuffer(false);
        RollingWindow windowOptimized = new RollingWindow(60L, 512);
        windowOptimized.setReuseScratchBuffer(true);

        long now = System.nanoTime();
        for (int i = 0; i < 512; i++) {
            double v = 10.0 + (i % 45);
            windowUnoptimized.record(v, now - (512 - i) * 1_000_000L);
            windowOptimized.record(v, now - (512 - i) * 1_000_000L);
        }

        // Warmup
        for (int i = 0; i < 500; i++) {
            windowUnoptimized.computeStats(now);
            windowOptimized.computeStats(now);
        }

        long t0 = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            windowUnoptimized.computeStats(now);
        }
        long unoptimizedNanos = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            windowOptimized.computeStats(now);
        }
        long optimizedNanos = System.nanoTime() - t1;

        assertTrue(unoptimizedNanos > 0L && optimizedNanos > 0L);

        // 2. Benchmark ConfigManager lookup with vs without config-lookup-cache
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("monitoring.thresholds.mspt-warning", 40.0);
        cfg.set("monitoring.thresholds.tps-warning", 18.5);
        ConfigManager cm = new ConfigManager(null, cfg, Logger.getLogger("Bench"));

        cm.setCacheEnabled(false);
        for (int i = 0; i < 1000; i++) {
            cm.getDouble("monitoring.thresholds.mspt-warning", 40.0);
        }
        long c0 = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            cm.getDouble("monitoring.thresholds.mspt-warning", 40.0);
        }
        long uncachedConfigNanos = System.nanoTime() - c0;

        cm.setCacheEnabled(true);
        for (int i = 0; i < 1000; i++) {
            cm.getDouble("monitoring.thresholds.mspt-warning", 40.0);
        }
        long c1 = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            cm.getDouble("monitoring.thresholds.mspt-warning", 40.0);
        }
        long cachedConfigNanos = System.nanoTime() - c1;

        assertTrue(cachedConfigNanos > 0L && uncachedConfigNanos > 0L);
        assertEquals(21_000L - 1L, cm.getCacheHits());
    }

    @Test
    public void testAutoRollbackOnSustainedMsptRegression(@TempDir File tempDir) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(
                new InputStreamReader(
                        OptimizationAndLifecycleTest.class.getResourceAsStream("/config.yml"),
                        StandardCharsets.UTF_8
                )
        );
        cfg.set("integrations.auto-install.enabled", false);
        ConfigManager cm = new ConfigManager(null, cfg, Logger.getLogger("AutoRollbackTest"));
        cm.processConfiguration(cfg);

        Server paperServer = TestServerHarness.createMockServer(
                "Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        PerformanceEngine engine = new PerformanceEngine(
                null, paperServer, tempDir, Logger.getLogger("AutoRollbackTest"), cm);
        engine.start();

        assertTrue(engine.getOptimizationManager().isAutoRollbackOnRegressionEnabled());
        assertTrue(engine.getOptimizationManager().getRegistry().getModule("adaptive-telemetry-batcher").isEnabled());

        engine.getOptimizationManager().setBaselineMspt(18.0);
        MetricSnapshot spike = MetricSnapshot.builder().averageMspt(85.0).processCpuLoadPercent(50.0).build();
        MetricSnapshot recovery = MetricSnapshot.builder().averageMspt(18.0).processCpuLoadPercent(20.0).build();

        // Single spike increments streak to 1 without prematurely rolling back
        engine.getOptimizationManager().evaluateConditionalModules(spike);
        assertEquals(1, engine.getOptimizationManager().getRegressionStreak());
        assertTrue(engine.getOptimizationManager().getRegistry().getModule("adaptive-telemetry-batcher").isEnabled());

        // Healthy tick resets regression streak
        engine.getOptimizationManager().evaluateConditionalModules(recovery);
        assertEquals(0, engine.getOptimizationManager().getRegressionStreak());

        // Three consecutive severe MSPT regressions trigger automatic rollback of conditional modules
        int historyBefore = engine.getOptimizationManager().getRollbackManager().getHistory().size();
        engine.getOptimizationManager().evaluateConditionalModules(spike);
        engine.getOptimizationManager().evaluateConditionalModules(spike);
        engine.getOptimizationManager().evaluateConditionalModules(spike);

        assertEquals(0, engine.getOptimizationManager().getRegressionStreak());
        assertFalse(engine.getOptimizationManager().getRegistry().getModule("adaptive-telemetry-batcher").isEnabled());
        assertTrue(engine.getOptimizationManager().getRollbackManager().getHistory().size() > historyBefore);

        engine.stop();
    }

    @Test
    public void testConfigMigratorV2ToV3PreservesCustomSettingsAndNoOpsAtV3() {
        ConfigMigrator migrator = new ConfigMigrator();
        assertTrue(migrator.migrateIfNeeded(null).isEmpty());

        YamlConfiguration v2Config = new YamlConfiguration();
        v2Config.set("config-version", 2);
        v2Config.set("alerts.min-severity", "CRITICAL");
        v2Config.set("optimization.modules.low-memory-footprint-governor.compact-history-capacity", 64);

        List<String> notes = migrator.migrateIfNeeded(v2Config);
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("version 2 to version 3"));
        assertEquals(3, v2Config.getInt("config-version"));

        // Existing custom settings must be preserved across migration
        assertEquals("CRITICAL", v2Config.getString("alerts.min-severity"));
        assertEquals(64, v2Config.getInt("optimization.modules.low-memory-footprint-governor.compact-history-capacity"));

        // New v3 keys must be backfilled with defaults
        assertTrue(v2Config.getBoolean("optimization.modules.zero-alloc-world-scanner.enabled"));
        assertTrue(v2Config.getBoolean("optimization.modules.async-io-offloader.enabled"));
        assertTrue(v2Config.getBoolean("optimization.modules.adaptive-telemetry-batcher.enabled"));
        assertEquals(40, v2Config.getInt("optimization.modules.low-memory-footprint-governor.compact-findings-history"));
        assertEquals(60, v2Config.getInt("alerts.alert-cooldown-seconds"));

        // Running migrator again on v3 config must be a clean no-op
        assertTrue(migrator.migrateIfNeeded(v2Config).isEmpty());
    }

    @Test
    public void testReportGeneratorLogRotationAndBoundedArchives(@TempDir File tempDir) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("config-version", 3);
        cfg.set("general.log-rotation.enabled", true);
        cfg.set("general.log-rotation.max-file-size-kb", 1);
        cfg.set("general.log-rotation.max-archives", 2);

        ConfigManager cm = new ConfigManager(null, cfg, Logger.getLogger("LogRotationTest"));
        cm.processConfiguration(cfg);

        ReportGenerator generator = new ReportGenerator(tempDir, cm);
        String payload = new String(new char[140]).replace('\0', 'R');
        for (int i = 0; i < 35; i++) {
            generator.appendToRotatingActivityLog("Entry-" + i + "-" + payload);
        }

        File activeLog = new File(tempDir, "optimizeserver-activity.log");
        File archive1 = new File(tempDir, "optimizeserver-activity.1.log");
        File archive2 = new File(tempDir, "optimizeserver-activity.2.log");
        File archive3 = new File(tempDir, "optimizeserver-activity.3.log");

        assertTrue(activeLog.exists() && activeLog.length() > 0L);
        assertTrue(archive1.exists() && archive1.length() > 0L);
        assertTrue(archive2.exists() && archive2.length() > 0L);
        assertFalse(archive3.exists(), "Archives must be bounded to configured max-archives (2)");
    }

    @Test
    public void testLivePluginAsyncReportOffloadAndTaskBatchCoordinatorToggle(@TempDir File tempDir) throws Exception {
        final File pluginDir = new File(tempDir, "plugins/OptimizeServer");
        final Logger testLogger = Logger.getLogger("LivePluginAsyncTest");
        Plugin dummyPlugin = (Plugin) Proxy.newProxyInstance(
                OptimizationAndLifecycleTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String m = method.getName();
                        if ("getName".equals(m)) return "OptimizeServer";
                        if ("getLogger".equals(m)) return testLogger;
                        if ("isEnabled".equals(m)) return Boolean.TRUE;
                        if ("getDataFolder".equals(m)) return pluginDir;
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return Boolean.FALSE;
                        if (rt == int.class) return Integer.valueOf(0);
                        if (rt == long.class) return Long.valueOf(0L);
                        return null;
                    }
                }
        );

        // 1. Default config: task-batch-coordinator & async-io-offloader enabled
        YamlConfiguration cfg1 = YamlConfiguration.loadConfiguration(
                new InputStreamReader(
                        OptimizationAndLifecycleTest.class.getResourceAsStream("/config.yml"),
                        StandardCharsets.UTF_8
                )
        );
        cfg1.set("integrations.auto-install.enabled", false);
        ConfigManager cm1 = new ConfigManager(null, cfg1, testLogger);
        cm1.processConfiguration(cfg1);

        Server paperServer = TestServerHarness.createMockServer(
                "Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        PerformanceEngine engine1 = new PerformanceEngine(dummyPlugin, paperServer, pluginDir, testLogger, cm1);
        engine1.start();

        assertEquals(EngineState.RUNNING, engine1.getState());
        assertTrue(engine1.getOptimizationManager().isTaskBatchingEnabled());
        assertTrue(engine1.shouldOffloadAsyncIo());

        final CountDownLatch savedLatch = new CountDownLatch(1);
        final AtomicReference<String> callbackThread = new AtomicReference<String>();
        DiagnosticReport asyncReport = engine1.generateReportAsyncAware(new Runnable() {
            @Override
            public void run() {
                callbackThread.set(Thread.currentThread().getName());
                savedLatch.countDown();
            }
        });

        assertTrue(savedLatch.await(5, TimeUnit.SECONDS), "Async report save callback did not complete in time");
        assertNotNull(asyncReport);
        assertNotNull(callbackThread.get());
        assertTrue(callbackThread.get().startsWith("OptimizeServer-Worker-"),
                "Expected offloaded async worker thread, got: " + callbackThread.get());
        assertTrue(asyncReport.getSavedFile().exists());

        engine1.stop();
        assertEquals(EngineState.STOPPED, engine1.getState());

        // 2. Config with task-batch-coordinator & async-io-offloader disabled falls back cleanly
        YamlConfiguration cfg2 = YamlConfiguration.loadConfiguration(
                new InputStreamReader(
                        OptimizationAndLifecycleTest.class.getResourceAsStream("/config.yml"),
                        StandardCharsets.UTF_8
                )
        );
        cfg2.set("integrations.auto-install.enabled", false);
        cfg2.set("optimization.modules.task-batch-coordinator.enabled", false);
        cfg2.set("optimization.modules.async-io-offloader.enabled", false);
        ConfigManager cm2 = new ConfigManager(null, cfg2, testLogger);
        cm2.processConfiguration(cfg2);

        PerformanceEngine engine2 = new PerformanceEngine(dummyPlugin, paperServer, pluginDir, testLogger, cm2);
        engine2.start();
        assertEquals(EngineState.RUNNING, engine2.getState());
        assertFalse(engine2.getOptimizationManager().isTaskBatchingEnabled());
        assertFalse(engine2.shouldOffloadAsyncIo());
        engine2.stop();
        assertEquals(EngineState.STOPPED, engine2.getState());
    }
}

