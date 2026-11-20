package com.pratham.optimizeserver;

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
import java.util.Collections;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The NGP Cloud sponsor line is shown by default in /os version and can be switched off with general.show-sponsor.
 */
public class SponsorTest {

    private static String versionOutput(File dir, Boolean showSponsor) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(
                SponsorTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        cfg.set("integrations.auto-install.enabled", false);
        if (showSponsor != null) {
            cfg.set("general.show-sponsor", showSponsor);
        }
        ConfigManager configManager = new ConfigManager(null, cfg, Logger.getLogger("SponsorTest"));
        configManager.processConfiguration(cfg);
        Server server = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        PerformanceEngine engine = new PerformanceEngine(null, server, dir, Logger.getLogger("SponsorEngine"), configManager);
        engine.start();
        try {
            OptimizeServerCommand command = new OptimizeServerCommand(engine, "1.6.1");
            TestServerHarness.CapturingCommandSender admin = new TestServerHarness.CapturingCommandSender(
                    "Admin", true, Collections.<String>emptySet());
            command.onCommand(admin.getSender(), null, "os", new String[]{"version"});
            return String.valueOf(admin.getMessages());
        } finally {
            engine.stop();
        }
    }

    @Test
    public void testSponsorShownByDefaultAndHiddenWhenDisabled(@TempDir File tempDir) {
        String shown = versionOutput(tempDir, null);
        assertTrue(shown.contains("NGP Cloud") && shown.contains("https://ngpcloud.in"), shown);

        String hidden = versionOutput(tempDir, Boolean.FALSE);
        assertFalse(hidden.contains("NGP Cloud"), hidden);
    }

    @Test
    public void testSponsorLineIsPlainText() {
        assertTrue(OptimizeServerCommand.SPONSOR_LINE.contains("NGP Cloud"));
        assertTrue(OptimizeServerCommand.SPONSOR_LINE.contains("https://ngpcloud.in"));
        assertFalse(OptimizeServerCommand.SPONSOR_LINE.contains("§"), "no color codes in console log lines");
    }
}
