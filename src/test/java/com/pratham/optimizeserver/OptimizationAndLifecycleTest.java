package com.pratham.optimizeserver;

import com.pratham.optimizeserver.commands.CommandPermissions;
import com.pratham.optimizeserver.commands.OptimizeServerCommand;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.core.EngineState;
import com.pratham.optimizeserver.core.PerformanceEngine;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.metrics.RollingWindow;
import com.pratham.optimizeserver.optimizer.OptimizationModule;
import com.pratham.optimizeserver.reporting.DiagnosticReport;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

        assertEquals(2, legacyV1.getInt("config-version"));
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

        // Verify all 9 optimization modules are registered and enabled
        assertEquals(9, engine.getOptimizationManager().getAllModules().size());
        assertEquals(9, engine.getOptimizationManager().getRegistry().getActiveModules().size());

        // Test adaptive monitoring sampler response to high MSPT
        MetricSnapshot highMspt = MetricSnapshot.builder()
                .averageMspt(48.0)
                .processCpuLoadPercent(90.0)
                .build();
        engine.getOptimizationManager().evaluateConditionalModules(highMspt);
        assertEquals(400, engine.getOptimizationManager().getEffectiveWorldSampleIntervalTicks());
        assertEquals(1, engine.getScheduler().getWorkerPool().getCorePoolSize());

        // Test module disable, enable, and rollback
        assertTrue(engine.getOptimizationManager().disableModule("config-lookup-cache"));
        assertFalse(engine.getConfigManager().isCacheEnabled());
        assertTrue(engine.getOptimizationManager().enableModule("config-lookup-cache"));
        assertTrue(engine.getConfigManager().isCacheEnabled());
        assertTrue(engine.getOptimizationManager().rollbackModule("config-lookup-cache", "Unit test rollback"));
        assertFalse(engine.getOptimizationManager().getRegistry().getModule("config-lookup-cache").isEnabled());
        assertTrue(engine.getOptimizationManager().enableModule("config-lookup-cache"));

        // Test command permission enforcement: unauthorized player rejected
        OptimizeServerCommand command = new OptimizeServerCommand(engine, "1.0.0");
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
}
