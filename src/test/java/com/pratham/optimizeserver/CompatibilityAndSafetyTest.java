package com.pratham.optimizeserver;

import com.pratham.optimizeserver.compatibility.CompatibilityManager;
import com.pratham.optimizeserver.compatibility.CompatibilityReport;
import com.pratham.optimizeserver.compatibility.JavaRuntimeInfo;
import com.pratham.optimizeserver.compatibility.MinecraftVersion;
import com.pratham.optimizeserver.compatibility.ServerPlatform;
import com.pratham.optimizeserver.safety.CompatibilityGuard;
import com.pratham.optimizeserver.safety.GameplayInvariant;
import com.pratham.optimizeserver.safety.SafeModeController;
import com.pratham.optimizeserver.safety.SafetyValidator;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests platform detection, Minecraft version parsing, Java runtime detection,
 * gameplay invariant protection, and safe mode policies.
 */
public class CompatibilityAndSafetyTest {

    @Test
    public void testServerPlatformDetectionAcrossAllSupportedPlatforms() {
        ClassLoader cl = CompatibilityAndSafetyTest.class.getClassLoader();
        assertEquals(ServerPlatform.SPIGOT, ServerPlatform.detect("CraftBukkit", "git-Spigot-1234 (MC: 1.16.5)", cl));
        assertEquals(ServerPlatform.PAPER, ServerPlatform.detect("Paper", "git-Paper-496 (MC: 1.20.4)", cl));
        assertEquals(ServerPlatform.PURPUR, ServerPlatform.detect("Purpur", "git-Purpur-2150 (MC: 1.20.4)", cl));
        assertEquals(ServerPlatform.PUFFERFISH, ServerPlatform.detect("Pufferfish", "git-Pufferfish-52 (MC: 1.20.1)", cl));
        assertEquals(ServerPlatform.AIRPLANE, ServerPlatform.detect("Airplane", "git-Airplane-110 (MC: 1.17.1)", cl));
        assertEquals(ServerPlatform.FOLIA, ServerPlatform.detect("Folia", "git-Folia-22 (MC: 1.20.4)", cl));
        assertEquals(ServerPlatform.LEAVES, ServerPlatform.detect("Leaves", "git-Leaves-88 (MC: 1.21.1)", cl));
        assertEquals(ServerPlatform.GALE, ServerPlatform.detect("Gale", "git-Gale-45 (MC: 1.20.4)", cl));
        assertEquals(ServerPlatform.CRAFTBUKKIT, ServerPlatform.detect("CraftBukkit", "git-Bukkit-999 (MC: 1.8.8)", cl));
        assertEquals(ServerPlatform.UNKNOWN_BUKKIT, ServerPlatform.detect("CustomMysteryServer", "1.0-CUSTOM", cl));

        assertTrue(ServerPlatform.FOLIA.isRegionThreaded());
        assertFalse(ServerPlatform.PAPER.isRegionThreaded());
        assertTrue(ServerPlatform.PURPUR.isPaperCompatible());
        assertFalse(ServerPlatform.SPIGOT.isPaperCompatible());
    }

    @Test
    public void testMinecraftVersionParsingAndJavaRequirements() {
        MinecraftVersion v188 = MinecraftVersion.parse("git-Spigot-db6de12 (MC: 1.8.8)", "1.8.8-R0.1-SNAPSHOT");
        assertEquals(1, v188.getMajor());
        assertEquals(8, v188.getMinor());
        assertEquals(8, v188.getPatch());
        assertTrue(v188.isLegacyPre113());
        assertEquals(8, v188.getMinimumRequiredJavaRelease());

        MinecraftVersion v1165 = MinecraftVersion.parse("git-Paper-794 (MC: 1.16.5)", "1.16.5-R0.1-SNAPSHOT");
        assertFalse(v1165.isLegacyPre113());
        assertEquals(8, v1165.getMinimumRequiredJavaRelease());

        MinecraftVersion v1171 = MinecraftVersion.parse("git-Paper-411 (MC: 1.17.1)", "1.17.1-R0.1-SNAPSHOT");
        assertEquals(16, v1171.getMinimumRequiredJavaRelease());

        MinecraftVersion v1204 = MinecraftVersion.parse("git-Purpur-2150 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT");
        assertEquals(17, v1204.getMinimumRequiredJavaRelease());

        MinecraftVersion v1206 = MinecraftVersion.parse("git-Paper-145 (MC: 1.20.6)", "1.20.6-R0.1-SNAPSHOT");
        assertEquals(21, v1206.getMinimumRequiredJavaRelease());

        MinecraftVersion v1214 = MinecraftVersion.parse("git-Paper-88 (MC: 1.21.4)", "1.21.4-R0.1-SNAPSHOT");
        assertEquals(21, v1214.getMinimumRequiredJavaRelease());
        assertTrue(v1214.compareTo(v1204) > 0);
        assertTrue(v1204.compareTo(v188) > 0);

        MinecraftVersion vFuture = MinecraftVersion.parse("git-Paper-1 (MC: 26.1.2)", "26.1.2-R0.1-SNAPSHOT");
        assertEquals(26, vFuture.getMajor());
        assertEquals(21, vFuture.getMinimumRequiredJavaRelease());
    }

    @Test
    public void testJavaRuntimeFeatureVersionParsing() {
        assertEquals(8, JavaRuntimeInfo.parseFeatureVersion("1.8.0_451"));
        assertEquals(11, JavaRuntimeInfo.parseFeatureVersion("11.0.22"));
        assertEquals(17, JavaRuntimeInfo.parseFeatureVersion("17.0.10+7"));
        assertEquals(21, JavaRuntimeInfo.parseFeatureVersion("21.0.8"));
        assertEquals(25, JavaRuntimeInfo.parseFeatureVersion("25.0.4-ea"));

        JavaRuntimeInfo liveInfo = JavaRuntimeInfo.detect();
        assertTrue(liveInfo.getFeatureVersion() >= 8);
        assertTrue(liveInfo.getAvailableProcessors() >= 1);
    }

    @Test
    public void testUnknownPlatformTriggersConservativeSafeMode() {
        Server unknownServer = TestServerHarness.createMockServer("CustomFork", "1.20.4-custom", "1.20.4-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(unknownServer, true);
        CompatibilityReport report = cm.getReport();
        assertEquals(ServerPlatform.UNKNOWN_BUKKIT, report.getPlatform());
        assertTrue(report.isConservativeSafeModeRequired());
        assertFalse(report.getWarnings().isEmpty());
    }

    @Test
    public void testSafetyValidatorRejectsAllGameplayModifyingOperations() {
        SafetyValidator validator = new SafetyValidator();

        GameplayInvariant.OperationProfile safeOp = GameplayInvariant.OperationProfile.builder("safe-op")
                .reversible(true)
                .build();
        assertTrue(validator.validate(safeOp).isSafe());

        assertFalse(validator.validate(GameplayInvariant.OperationProfile.builder("kill-entities")
                .removesEntities(true).build()).isSafe());
        assertFalse(validator.validate(GameplayInvariant.OperationProfile.builder("disable-ai")
                .disablesMobAi(true).build()).isSafe());
        assertFalse(validator.validate(GameplayInvariant.OperationProfile.builder("skip-ticks")
                .skipsServerTicks(true).build()).isSafe());
        assertFalse(validator.validate(GameplayInvariant.OperationProfile.builder("change-view-dist")
                .modifiesSimulationOrViewDistance(true).build()).isSafe());
        assertFalse(validator.validate(GameplayInvariant.OperationProfile.builder("change-gamerules")
                .modifiesGameRules(true).build()).isSafe());
        assertFalse(validator.validate(GameplayInvariant.OperationProfile.builder("mutate-world")
                .modifiesWorldData(true).build()).isSafe());
        assertFalse(validator.validate(GameplayInvariant.OperationProfile.builder("drop-packets")
                .altersGameplayPackets(true).build()).isSafe());
        assertFalse(validator.validate(GameplayInvariant.OperationProfile.builder("async-world-access")
                .accessesBukkitObjectsAsync(true).build()).isSafe());
        assertFalse(validator.validate(GameplayInvariant.OperationProfile.builder("force-gc")
                .forcesExplicitGarbageCollection(true).build()).isSafe());
        assertFalse(validator.validate(GameplayInvariant.OperationProfile.builder("irreversible")
                .reversible(false).build()).isSafe());
    }

    @Test
    public void testCompatibilityGuardAndSafeModeController() {
        Server paperServer = TestServerHarness.createMockServer("Paper", "git-Paper-496 (MC: 1.20.4)", "1.20.4-R0.1-SNAPSHOT", true);
        CompatibilityManager cm = new CompatibilityManager(paperServer, true);
        CompatibilityGuard guard = new CompatibilityGuard(cm.getReport());

        assertTrue(guard.isSupported(
                EnumSet.of(ServerPlatform.PAPER, ServerPlatform.PURPUR),
                new MinecraftVersion(1, 16, 0, "1.16.0"),
                8,
                true
        ));
        assertFalse(guard.isSupported(
                EnumSet.of(ServerPlatform.FOLIA),
                new MinecraftVersion(1, 16, 0, "1.16.0"),
                8,
                true
        ));

        SafeModeController smc = new SafeModeController(false, null);
        assertFalse(smc.isSafeModeActive());
        smc.enterSafeMode("Unit test emergency trigger");
        assertTrue(smc.isSafeModeActive());
        assertEquals(1, smc.getActivationReasons().size());
        smc.exitSafeMode();
        assertFalse(smc.isSafeModeActive());
    }
}
