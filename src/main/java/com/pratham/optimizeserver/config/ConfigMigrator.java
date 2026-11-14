package com.pratham.optimizeserver.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handles forward migration of older OptimizeServer configuration schemas to the current version.
 */
public final class ConfigMigrator {

    public static final int CURRENT_CONFIG_VERSION = 3;

    public List<String> migrateIfNeeded(FileConfiguration config) {
        List<String> notes = new ArrayList<String>();
        if (config == null) {
            return notes;
        }
        int existingVersion = config.getInt("config-version", 1);
        if (existingVersion >= CURRENT_CONFIG_VERSION) {
            return notes;
        }

        // Migrate v1 -> v2
        if (existingVersion < 2) {
            setDefaultIfMissing(config, "general.enabled", true);
            setDefaultIfMissing(config, "general.debug-logging", false);
            setDefaultIfMissing(config, "general.console-prefix", "[OptimizeServer]");
            setDefaultIfMissing(config, "general.telemetry-enabled", false);
            setDefaultIfMissing(config, "general.log-rotation.enabled", true);
            setDefaultIfMissing(config, "general.log-rotation.max-file-size-kb", 2048);
            setDefaultIfMissing(config, "general.log-rotation.max-archives", 10);

            setDefaultIfMissing(config, "compatibility.auto-detect-platform", true);
            setDefaultIfMissing(config, "compatibility.force-safe-mode-on-unknown-platform", true);
            setDefaultIfMissing(config, "compatibility.folia-region-safe-mode", true);
            setDefaultIfMissing(config, "compatibility.emit-compatibility-warnings", true);
            setDefaultIfMissing(config, "compatibility.strict-api-verification", true);

            setDefaultIfMissing(config, "monitoring.retention.rolling-windows-seconds", Arrays.asList(5, 30, 60, 300, 900));
            setDefaultIfMissing(config, "safety.enforce-gameplay-invariants", true);
            setDefaultIfMissing(config, "integrations.auto-install.enabled", true);
            setDefaultIfMissing(config, "integrations.auto-install.load-immediately-after-download", true);
            setDefaultIfMissing(config, "integrations.auto-install.verify-sha512-checksum", true);
            setDefaultIfMissing(config, "integrations.spark.auto-install", true);
            setDefaultIfMissing(config, "integrations.spark.never-auto-upload", true);
            setDefaultIfMissing(config, "integrations.chunky.auto-install", true);
            setDefaultIfMissing(config, "integrations.chunky.never-auto-start-generation", true);
        }

        // Migrate v2 -> v3
        if (existingVersion < 3) {
            setDefaultIfMissing(config, "optimization.modules.zero-alloc-world-scanner.enabled", true);
            setDefaultIfMissing(config, "optimization.modules.low-memory-footprint-governor.enabled", true);
            setDefaultIfMissing(config, "optimization.modules.low-memory-footprint-governor.compact-history-capacity", 90);
            setDefaultIfMissing(config, "optimization.modules.low-memory-footprint-governor.compact-findings-history", 40);
            setDefaultIfMissing(config, "optimization.modules.async-io-offloader.enabled", true);
            setDefaultIfMissing(config, "optimization.modules.adaptive-telemetry-batcher.enabled", true);
            setDefaultIfMissing(config, "alerts.console-alerts-enabled", true);
            setDefaultIfMissing(config, "alerts.admin-permission-broadcast", false);
            setDefaultIfMissing(config, "alerts.alert-cooldown-seconds", 60);
            setDefaultIfMissing(config, "alerts.min-severity", "HIGH");
        }

        config.set("config-version", CURRENT_CONFIG_VERSION);
        notes.add("Migrated configuration schema from version " + existingVersion + " to version " + CURRENT_CONFIG_VERSION + ".");

        return notes;
    }

    private void setDefaultIfMissing(FileConfiguration config, String path, Object value) {
        if (!config.contains(path)) {
            config.set(path, value);
        }
    }
}

