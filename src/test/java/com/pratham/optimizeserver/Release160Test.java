package com.pratham.optimizeserver;

import com.pratham.optimizeserver.analyzer.AlertManager;
import com.pratham.optimizeserver.analyzer.BottleneckCategory;
import com.pratham.optimizeserver.analyzer.DiagnosticFinding;
import com.pratham.optimizeserver.analyzer.Severity;
import com.pratham.optimizeserver.commands.OptimizeServerCommand;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.core.PerformanceEngine;
import com.pratham.optimizeserver.integrations.WebhookNotifier;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.reporting.Sparkline;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the 1.6.0 features: /os history sparklines, /os plugins, and opt-in webhook alerts.
 */
public class Release160Test {

    @Test
    public void testSparklineBucketsAndRenders() {
        List<Double> values = Arrays.asList(0.0, 0.0, 10.0, 10.0, 20.0, 20.0);
        double[] b = Sparkline.bucket(values, 3);
        assertArrayEquals(new double[]{0.0, 10.0, 20.0}, b, 1e-9);
        assertEquals("▁▅█", Sparkline.render(b, 0.0, 20.0));
        assertEquals(2, Sparkline.bucket(Arrays.asList(1.0, 2.0), 40).length, "never more columns than samples");
        assertEquals("█", Sparkline.render(new double[]{99.0}, 0.0, 20.0), "values above the ceiling are clamped");
        assertEquals(0, Sparkline.bucket(Collections.<Double>emptyList(), 10).length);
    }

    @Test
    public void testWebhookUrlPolicyAndJsonEscaping() throws Exception {
        assertTrue(WebhookNotifier.isAllowedUrl("https://discord.com/api/webhooks/1/abc"));
        assertTrue(WebhookNotifier.isAllowedUrl("http://127.0.0.1:8080/hook"));
        assertFalse(WebhookNotifier.isAllowedUrl("http://example.com/hook"), "plain http only for loopback");
        assertFalse(WebhookNotifier.isAllowedUrl("ftp://example.com/x"));
        assertFalse(WebhookNotifier.isAllowedUrl(""));
        assertFalse(WebhookNotifier.isAllowedUrl(null));

        Method json = WebhookNotifier.class.getDeclaredMethod("jsonString", String.class);
        json.setAccessible(true);
        assertEquals("\"a\\\"b\\\\c\\nd\"", json.invoke(null, "a\"b\\c\nd"));
    }

    private static final class CapturingHook implements HttpHandler {
        final List<String> bodies = new CopyOnWriteArrayList<String>();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            InputStream in = exchange.getRequestBody();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            bodies.add(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }
    }

    @Test
    public void testWebhookDeliversAndRateLimits() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CapturingHook hook = new CapturingHook();
        http.createContext("/hook", hook);
        http.start();
        try {
            String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/hook";
            WebhookNotifier notifier = new WebhookNotifier(Logger.getLogger("WebhookTest"));
            assertTrue(notifier.send(url, "first \"alert\"", 60_000L, 3000));
            assertFalse(notifier.send(url, "second", 60_000L, 3000), "rate limited within the interval");
            assertEquals(1, hook.bodies.size());
            assertEquals("{\"content\":\"first \\\"alert\\\"\"}", hook.bodies.get(0));
            assertEquals(1L, notifier.getSentCount());

            assertFalse(notifier.send("http://127.0.0.1:" + http.getAddress().getPort() + "/missing", "x", 0L, 3000));
            assertEquals(1L, notifier.getFailedCount());
        } finally {
            http.stop(0);
        }
    }

    private static PerformanceEngine engine(File dir, String webhookUrl) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Release160Test.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        cfg.set("integrations.auto-install.enabled", false);
        if (webhookUrl != null) {
            cfg.set("alerts.webhook.enabled", true);
            cfg.set("alerts.webhook.url", webhookUrl);
        }
        ConfigManager configManager = new ConfigManager(null, cfg, Logger.getLogger("Release160Test"));
        configManager.processConfiguration(cfg);
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        PerformanceEngine engine = new PerformanceEngine(null, server, dir, Logger.getLogger("Release160Engine"), configManager);
        engine.start();
        return engine;
    }

    @Test
    public void testWebhookDisabledByDefaultAndEngineTestSend(@TempDir File tempDir) throws Exception {
        PerformanceEngine disabled = engine(tempDir, null);
        try {
            assertNotNull(disabled.sendWebhookTest(), "default config never sends anything");
        } finally {
            disabled.stop();
        }

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CapturingHook hook = new CapturingHook();
        http.createContext("/hook", hook);
        http.start();
        PerformanceEngine engine = engine(tempDir, "http://127.0.0.1:" + http.getAddress().getPort() + "/hook");
        try {
            assertNull(engine.sendWebhookTest());
            assertEquals(1, hook.bodies.size());
            assertTrue(hook.bodies.get(0).contains("webhook test"));

            OptimizeServerCommand command = new OptimizeServerCommand(engine, "1.6.0");
            TestServerHarness.CapturingCommandSender admin = new TestServerHarness.CapturingCommandSender(
                    "Admin", true, Collections.<String>emptySet());
            command.onCommand(admin.getSender(), null, "os", new String[]{"webhook", "test"});
            assertTrue(String.valueOf(admin.getMessages()).contains("Webhook test delivered"), String.valueOf(admin.getMessages()));
        } finally {
            engine.stop();
            http.stop(0);
        }
    }

    @Test
    public void testAlertManagerForwardsFilteredAlertsToSink() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("alerts.min-severity", "HIGH");
        ConfigManager configManager = new ConfigManager(null, cfg, Logger.getLogger("SinkTest"));
        configManager.processConfiguration(cfg);
        AlertManager alerts = new AlertManager(null, null, configManager, Logger.getLogger("SinkTest"));
        final List<String> forwarded = new ArrayList<String>();
        alerts.setExternalSink(new AlertManager.AlertSink() {
            @Override
            public void onAlert(Severity severity, String summary) {
                forwarded.add(severity.name() + ":" + summary);
            }
        });
        List<DiagnosticFinding> findings = Arrays.asList(
                finding(Severity.CRITICAL, BottleneckCategory.MEMORY_PRESSURE),
                finding(Severity.LOW, BottleneckCategory.CPU_BOUND));
        alerts.processFindings(findings);
        assertEquals(1, forwarded.size(), "below-threshold findings are not forwarded");
        assertTrue(forwarded.get(0).startsWith("CRITICAL:"));
        alerts.processFindings(findings);
        assertEquals(1, forwarded.size(), "cooldown applies to the sink too");
    }

    private static DiagnosticFinding finding(Severity severity, BottleneckCategory category) {
        return new DiagnosticFinding("TEST-" + category.name(), System.currentTimeMillis(), severity, category,
                Collections.<String, String>emptyMap(), Collections.singletonList("test evidence"), 0.9,
                "investigate", "resolve", false);
    }

    @Test
    public void testHistoryAndPluginsCommands(@TempDir File tempDir) {
        PerformanceEngine engine = engine(tempDir, null);
        try {
            for (int i = 0; i < 30; i++) {
                engine.getMetricRegistry().getHistory().append(MetricSnapshot.builder()
                        .tps5s(20.0 - (i % 5)).averageMspt(20.0 + i).heapUsedBytes(500L).heapMaxBytes(1000L)
                        .onlinePlayers(i % 4).build());
            }
            OptimizeServerCommand command = new OptimizeServerCommand(engine, "1.6.0");
            TestServerHarness.CapturingCommandSender admin = new TestServerHarness.CapturingCommandSender(
                    "Admin", true, Collections.<String>emptySet());
            command.onCommand(admin.getSender(), null, "os", new String[]{"history", "1"});
            String out = String.valueOf(admin.getMessages());
            assertTrue(out.contains("TPS") && out.contains("MSPT") && out.contains("Heap") && out.contains("Players"), out);
            assertTrue(out.contains("max 49.0 ms"), out);

            admin.clear();
            command.onCommand(admin.getSender(), null, "os", new String[]{"plugins"});
            assertTrue(String.valueOf(admin.getMessages()).contains("Plugin Scheduler Load"));

            List<String> completions = command.onTabComplete(admin.getSender(), null, "os", new String[]{"h"});
            assertTrue(completions.contains("history") && completions.contains("help"));
        } finally {
            engine.stop();
        }
    }
}
