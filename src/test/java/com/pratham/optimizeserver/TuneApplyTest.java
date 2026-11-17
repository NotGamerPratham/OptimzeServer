package com.pratham.optimizeserver;

import com.pratham.optimizeserver.analyzer.ServerConfigApplier;
import com.pratham.optimizeserver.analyzer.ServerConfigAuditor;
import com.pratham.optimizeserver.commands.OptimizeServerCommand;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.core.PerformanceEngine;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies /os tune apply writes only gameplay-neutral config values, preserves comments,
 * backs files up first, requires confirmation, and can be undone with /os tune restore.
 */
public class TuneApplyTest {

    private static final String PROPERTIES = "# Minecraft server properties\nmotd=My Server\nsync-chunk-writes=true\nnetwork-compression-threshold=64\nview-distance=10\n";
    private static final String BUKKIT = "# bukkit settings\nchunk-gc:\n  period-in-ticks: 600 # sweep period\nticks-per:\n  autosave: 1200\n  monster-spawns: 1\n";
    private static final String SPIGOT = "settings:\n  save-user-cache-on-stop-only: false\n  netty-threads: 4\nworld-settings:\n  default:\n    view-distance: default\n";
    private static final String PAPER_WORLD = "chunks:\n  max-auto-save-chunks-per-tick: 24\n  prevent-moving-into-unloaded-chunks: false\n";

    private static File serverRoot(File tempDir) throws Exception {
        File root = new File(tempDir, "server");
        assertTrue(new File(root, "config").mkdirs());
        assertTrue(new File(root, "plugins/OptimizeServer").mkdirs());
        write(new File(root, "server.properties"), PROPERTIES);
        write(new File(root, "bukkit.yml"), BUKKIT);
        write(new File(root, "spigot.yml"), SPIGOT);
        write(new File(root, "config/paper-world-defaults.yml"), PAPER_WORLD);
        return root;
    }

    private static void write(File f, String content) throws Exception {
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static PerformanceEngine engine(File dataFolder) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(
                TuneApplyTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        cfg.set("integrations.auto-install.enabled", false);
        ConfigManager configManager = new ConfigManager(null, cfg, Logger.getLogger("TuneApplyTest"));
        configManager.processConfiguration(cfg);
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-130 (MC: 1.21.1)", "1.21.1-R0.1-SNAPSHOT", true);
        PerformanceEngine engine = new PerformanceEngine(null, server, dataFolder, Logger.getLogger("TuneApplyEngine"), configManager);
        engine.start();
        return engine;
    }

    @Test
    public void testApplyWritesValuesPreservesCommentsAndRestores(@TempDir File tempDir) throws Exception {
        File root = serverRoot(tempDir);
        PerformanceEngine engine = engine(new File(root, "plugins/OptimizeServer"));
        try {
            ServerConfigAuditor.AuditReport audit = engine.auditServerConfig();
            List<ServerConfigAuditor.TuningSuggestion> applicable = ServerConfigApplier.applicable(audit);
            assertFalse(applicable.isEmpty());
            for (ServerConfigAuditor.TuningSuggestion s : applicable) {
                assertFalse(s.getFile().startsWith("startup"), "JVM flags are never auto-applied");
            }

            ServerConfigApplier.ApplyResult result = engine.applyTuneSuggestions(audit);
            assertNotNull(result.getBackupDir());

            String props = read(new File(root, "server.properties"));
            assertTrue(props.contains("sync-chunk-writes=false"));
            assertTrue(props.contains("network-compression-threshold=256"));
            assertTrue(props.contains("# Minecraft server properties"), "comments preserved");
            assertTrue(props.contains("view-distance=10"), "gameplay settings untouched");

            String bukkit = read(new File(root, "bukkit.yml"));
            assertTrue(bukkit.contains("  period-in-ticks: 300 # sweep period"), bukkit);
            assertTrue(bukkit.contains("  autosave: 6000"));
            assertTrue(bukkit.contains("  monster-spawns: 1"), "gameplay settings untouched");

            String spigot = read(new File(root, "spigot.yml"));
            assertTrue(spigot.contains("  save-user-cache-on-stop-only: true"));

            String paper = read(new File(root, "config/paper-world-defaults.yml"));
            assertTrue(paper.contains("  max-auto-save-chunks-per-tick: 6"));
            assertTrue(paper.contains("  prevent-moving-into-unloaded-chunks: false"), "movement setting never changed");

            assertTrue(new File(result.getBackupDir(), "server.properties").isFile());
            assertEquals(PROPERTIES, read(new File(result.getBackupDir(), "server.properties")));

            List<String> restored = engine.restoreLatestTuneBackup();
            assertTrue(restored.contains("server.properties"));
            assertEquals(PROPERTIES, read(new File(root, "server.properties")));
            assertEquals(BUKKIT, read(new File(root, "bukkit.yml")));
            assertEquals(PAPER_WORLD, read(new File(root, "config/paper-world-defaults.yml")));
        } finally {
            engine.stop();
        }
    }

    @Test
    public void testApplyRequiresConfirmationAndPermission(@TempDir File tempDir) throws Exception {
        File root = serverRoot(tempDir);
        PerformanceEngine engine = engine(new File(root, "plugins/OptimizeServer"));
        try {
            OptimizeServerCommand command = new OptimizeServerCommand(engine, "1.5.0");

            TestServerHarness.CapturingCommandSender viewer = new TestServerHarness.CapturingCommandSender(
                    "Viewer", false, Collections.singleton("optimizeserver.diagnose"));
            command.onCommand(viewer.getSender(), null, "os", new String[]{"tune", "apply"});
            assertTrue(String.valueOf(viewer.getMessages()).contains("optimizeserver.tune.apply"));

            TestServerHarness.CapturingCommandSender admin = new TestServerHarness.CapturingCommandSender(
                    "Admin", true, Collections.<String>emptySet());
            command.onCommand(admin.getSender(), null, "os", new String[]{"tune", "apply", "confirm"});
            assertTrue(String.valueOf(admin.getMessages()).contains("No pending change set"));
            assertTrue(read(new File(root, "server.properties")).contains("sync-chunk-writes=true"), "nothing written without a preview");

            admin.clear();
            command.onCommand(admin.getSender(), null, "os", new String[]{"tune", "apply"});
            assertTrue(String.valueOf(admin.getMessages()).contains("/os tune apply confirm"));
            assertTrue(read(new File(root, "server.properties")).contains("sync-chunk-writes=true"), "preview writes nothing");

            admin.clear();
            command.onCommand(admin.getSender(), null, "os", new String[]{"tune", "apply", "confirm"});
            assertTrue(String.valueOf(admin.getMessages()).contains("Tuning Applied"));
            assertTrue(read(new File(root, "server.properties")).contains("sync-chunk-writes=false"));

            admin.clear();
            command.onCommand(admin.getSender(), null, "os", new String[]{"tune", "restore"});
            assertTrue(String.valueOf(admin.getMessages()).contains("Restored"));
            assertTrue(read(new File(root, "server.properties")).contains("sync-chunk-writes=true"));
        } finally {
            engine.stop();
        }
    }

    @Test
    public void testYamlEditorOnlyChangesExistingScalars() {
        List<String> lines = new java.util.ArrayList<String>(java.util.Arrays.asList(
                "chunks:", "  # comment", "  nested:", "    value: 5", "other: 1"));
        assertTrue(setYaml(lines, "chunks.nested.value", "9"));
        assertEquals("    value: 9", lines.get(3));
        assertFalse(setYaml(lines, "chunks.nested", "1"), "sections are never overwritten");
        assertFalse(setYaml(lines, "chunks.missing", "1"), "absent keys are never invented");
        assertEquals("other: 1", lines.get(4));
    }

    private static boolean setYaml(List<String> lines, String key, String value) {
        try {
            java.lang.reflect.Method m = ServerConfigApplier.class.getDeclaredMethod("setYamlValue", List.class, String.class, String.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(null, lines, key, value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
