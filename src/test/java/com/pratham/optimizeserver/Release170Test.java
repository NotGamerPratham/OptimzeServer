package com.pratham.optimizeserver;

import com.pratham.optimizeserver.commands.OptimizeServerCommand;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.core.PerformanceEngine;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.metrics.TickDurationRing;
import com.pratham.optimizeserver.monitoring.LeakWatch;
import com.pratham.optimizeserver.reporting.MetricExporter;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the 1.7.0 features: /os ticks distribution, /os gc leak watch, and /os export CSV.
 */
public class Release170Test {

    private static final long MB = 1024L * 1024L;

    @Test
    public void testTickBucketCounts() {
        TickDurationRing ring = new TickDurationRing(64);
        long base = 5_000_000_000_000L;
        double[] durations = {10.0, 25.0, 30.0, 50.0, 60.0, 120.0, 300.0, 20.0};
        for (int i = 0; i < durations.length; i++) {
            ring.record(durations[i], base + i * 50_000_000L);
        }
        long now = base + durations.length * 50_000_000L;
        int[] c = ring.countBuckets(60_000_000_000L, now, new double[]{25.0, 50.0, 100.0, 250.0});
        // <=25: 10, 25, 20 | 25-50: 30, 50 | 50-100: 60 | 100-250: 120 | >250: 300
        assertArrayEquals(new int[]{3, 2, 1, 1, 1}, c);

        int[] narrow = ring.countBuckets(100_000_000L, now, new double[]{25.0, 50.0, 100.0, 250.0});
        int total = 0;
        for (int n : narrow) {
            total += n;
        }
        assertTrue(total < durations.length, "a short window counts only recent ticks");
        assertArrayEquals(new int[]{0, 0, 0, 0, 0}, new TickDurationRing(16).countBuckets(1L, 0L, new double[]{25.0, 50.0, 100.0, 250.0}));
    }

    private static MetricSnapshot postGc(long timestampMillis, long usedMb) {
        return MetricSnapshot.builder().timestampMillis(timestampMillis)
                .postGcHeapUsedBytes(usedMb * MB).postGcHeapMaxBytes(4096L * MB).build();
    }

    @Test
    public void testLeakWatchVerdicts() {
        long t0 = 1_700_000_000_000L;
        // Rises 100 MB every 2 minutes = 3000 MB/h
        List<MetricSnapshot> rising = new ArrayList<MetricSnapshot>();
        for (int i = 0; i < 8; i++) {
            rising.add(postGc(t0 + i * 120_000L, 1000 + i * 100L));
        }
        LeakWatch.Trend up = LeakWatch.analyze(rising);
        assertEquals(LeakWatch.Verdict.RISING, up.getVerdict());
        assertEquals(3000.0, up.getSlopeMbPerHour(), 1.0);
        assertEquals(1700.0, up.getLatestMb(), 1e-6);
        assertTrue(up.getHoursToFull() > 0.0 && up.getHoursToFull() < 1.0, "4096 MB limit is reached within the hour");

        // Flat floor with tiny jitter: stable
        List<MetricSnapshot> flat = new ArrayList<MetricSnapshot>();
        for (int i = 0; i < 8; i++) {
            flat.add(postGc(t0 + i * 120_000L, 1000 + (i % 2)));
        }
        assertEquals(LeakWatch.Verdict.STABLE, LeakWatch.analyze(flat).getVerdict());

        // Too few distinct readings, or too short a span
        assertEquals(LeakWatch.Verdict.INSUFFICIENT_DATA, LeakWatch.analyze(rising.subList(0, 3)).getVerdict());
        List<MetricSnapshot> burst = new ArrayList<MetricSnapshot>();
        for (int i = 0; i < 8; i++) {
            burst.add(postGc(t0 + i * 10_000L, 1000 + i * 100L));
        }
        assertEquals(LeakWatch.Verdict.INSUFFICIENT_DATA, LeakWatch.analyze(burst).getVerdict());
        assertEquals(LeakWatch.Verdict.INSUFFICIENT_DATA, LeakWatch.analyze(Collections.<MetricSnapshot>emptyList()).getVerdict());

        // Repeated identical readings between collections count once
        List<MetricSnapshot> repeated = new ArrayList<MetricSnapshot>();
        for (int i = 0; i < 30; i++) {
            repeated.add(postGc(t0 + i * 30_000L, 1000));
        }
        assertEquals(1, LeakWatch.analyze(repeated).getPoints());
        assertFalse(LeakWatch.collectors().isEmpty(), "JVM exposes at least one collector");
    }

    @Test
    public void testCsvExportAndRetention(@TempDir File tempDir) throws Exception {
        List<MetricSnapshot> rows = new ArrayList<MetricSnapshot>();
        rows.add(MetricSnapshot.builder().timestampMillis(1_700_000_000_000L).tps5s(19.5).averageMspt(33.25)
                .heapUsedBytes(512L * MB).heapMaxBytes(1024L * MB).onlinePlayers(7).loadedChunks(900).totalEntities(1200).build());
        String csv = MetricExporter.toCsv(rows);
        String[] lines = csv.split("\n");
        assertEquals(MetricExporter.HEADER, lines[0]);
        assertEquals(2, lines.length);
        assertTrue(lines[1].startsWith("2023-11-14T22:13:20Z,19.50,33.25,"), lines[1]);
        assertTrue(lines[1].contains(",512,1024,"), lines[1]);
        assertEquals(MetricExporter.HEADER.split(",").length, lines[1].split(",").length, "row matches header width");

        File dir = new File(tempDir, "exports");
        File written = MetricExporter.write(dir, rows);
        assertTrue(written.isFile());
        assertEquals(csv, new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8));

        for (int i = 0; i < 25; i++) {
            Files.write(new File(dir, String.format("metrics-20200101-0000%02d.csv", i)).toPath(), "x".getBytes(StandardCharsets.UTF_8));
        }
        MetricExporter.write(dir, rows);
        File[] csvFiles = dir.listFiles();
        assertNotNull(csvFiles);
        assertEquals(20, csvFiles.length, "oldest exports are pruned to the retention limit");
    }

    @Test
    public void testNewCommands(@TempDir File tempDir) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Release170Test.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        cfg.set("integrations.auto-install.enabled", false);
        ConfigManager configManager = new ConfigManager(null, cfg, Logger.getLogger("Release170Test"));
        configManager.processConfiguration(cfg);
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        PerformanceEngine engine = new PerformanceEngine(null, server, tempDir, Logger.getLogger("Release170Engine"), configManager);
        engine.start();
        try {
            long now = System.nanoTime();
            engine.getMetricRegistry().getTickRing().record(20.0, now - 3_000_000_000L);
            engine.getMetricRegistry().getTickRing().record(80.0, now - 2_000_000_000L);
            engine.getMetricRegistry().getTickRing().record(40.0, now - 1_000_000_000L);

            OptimizeServerCommand command = new OptimizeServerCommand(engine, "1.7.0");
            TestServerHarness.CapturingCommandSender admin = new TestServerHarness.CapturingCommandSender(
                    "Admin", true, Collections.<String>emptySet());

            command.onCommand(admin.getSender(), null, "os", new String[]{"ticks"});
            String ticks = String.valueOf(admin.getMessages());
            assertTrue(ticks.contains("Tick-Time Distribution") && ticks.contains("Ticks over 50 ms"), ticks);

            admin.clear();
            command.onCommand(admin.getSender(), null, "os", new String[]{"gc"});
            String gc = String.valueOf(admin.getMessages());
            assertTrue(gc.contains("Garbage Collection") && gc.contains("Memory leak watch"), gc);

            admin.clear();
            command.onCommand(admin.getSender(), null, "os", new String[]{"export", "5"});
            assertTrue(String.valueOf(admin.getMessages()).contains("Saved"), String.valueOf(admin.getMessages()));
            File[] exported = new File(tempDir, "exports").listFiles();
            assertNotNull(exported);
            assertEquals(1, exported.length);

            TestServerHarness.CapturingCommandSender viewer = new TestServerHarness.CapturingCommandSender(
                    "Viewer", false, Collections.singleton("optimizeserver.status"));
            viewer.clear();
            command.onCommand(viewer.getSender(), null, "os", new String[]{"export"});
            assertTrue(String.valueOf(viewer.getMessages()).contains("optimizeserver.report"), "export needs the report permission");

            List<String> completions = command.onTabComplete(admin.getSender(), null, "os", new String[]{"t"});
            assertTrue(completions.contains("ticks") && completions.contains("tune") && completions.contains("tps"));
        } finally {
            engine.stop();
        }
    }
}
