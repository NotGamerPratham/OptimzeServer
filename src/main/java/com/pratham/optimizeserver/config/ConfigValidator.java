package com.pratham.optimizeserver.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates configuration values and enforces safe operating boundaries.
 */
public final class ConfigValidator {

    private static final Set<String> VALID_MODES = new HashSet<String>(
            Arrays.asList("AUTOMATIC_SAFE", "HYBRID_CONDITIONAL", "MANUAL_ONLY", "SAFE_MODE")
    );

    public List<String> validateAndSanitize(FileConfiguration config) {
        List<String> warnings = new ArrayList<String>();
        if (config == null) {
            warnings.add("Configuration object is null.");
            return warnings;
        }

        // Telemetry must never be silently enabled unless explicitly boolean true
        if (config.getBoolean("general.telemetry-enabled", false)) {
            warnings.add("External telemetry flag was enabled in config; OptimizeServer does not transmit data to external endpoints.");
            config.set("general.telemetry-enabled", false);
        }

        // Validate sampling intervals
        int tickSample = config.getInt("monitoring.sampling.tick-sample-interval-ticks", 1);
        if (tickSample < 1 || tickSample > 20) {
            warnings.add("Invalid monitoring.sampling.tick-sample-interval-ticks (" + tickSample + "); clamping to [1, 20].");
            config.set("monitoring.sampling.tick-sample-interval-ticks", Math.max(1, Math.min(20, tickSample)));
        }

        int sysSample = config.getInt("monitoring.sampling.system-sample-interval-ticks", 20);
        if (sysSample < 5 || sysSample > 1200) {
            warnings.add("Invalid monitoring.sampling.system-sample-interval-ticks (" + sysSample + "); resetting to 20.");
            config.set("monitoring.sampling.system-sample-interval-ticks", 20);
        }

        int worldSample = config.getInt("monitoring.sampling.world-sample-interval-ticks", 100);
        if (worldSample < 20 || worldSample > 6000) {
            warnings.add("Invalid monitoring.sampling.world-sample-interval-ticks (" + worldSample + "); resetting to 100.");
            config.set("monitoring.sampling.world-sample-interval-ticks", 100);
        }

        int maxSnapshots = config.getInt("monitoring.retention.max-snapshots", 900);
        if (maxSnapshots < 60 || maxSnapshots > 10000) {
            warnings.add("Invalid monitoring.retention.max-snapshots (" + maxSnapshots + "); clamping to [60, 10000].");
            config.set("monitoring.retention.max-snapshots", Math.max(60, Math.min(10000, maxSnapshots)));
        }

        // Validate TPS/MSPT thresholds
        double tpsWarn = config.getDouble("monitoring.thresholds.tps-warning", 18.5);
        double tpsCrit = config.getDouble("monitoring.thresholds.tps-critical", 15.0);
        if (tpsWarn <= 0.0 || tpsWarn > 20.0 || tpsCrit <= 0.0 || tpsCrit >= tpsWarn) {
            warnings.add("Invalid TPS warning/critical thresholds; resetting to 18.5 / 15.0.");
            config.set("monitoring.thresholds.tps-warning", 18.5);
            config.set("monitoring.thresholds.tps-critical", 15.0);
        }

        double msptWarn = config.getDouble("monitoring.thresholds.mspt-warning", 40.0);
        double msptCrit = config.getDouble("monitoring.thresholds.mspt-critical", 50.0);
        if (msptWarn <= 1.0 || msptCrit <= msptWarn) {
            warnings.add("Invalid MSPT warning/critical thresholds; resetting to 40.0 / 50.0.");
            config.set("monitoring.thresholds.mspt-warning", 40.0);
            config.set("monitoring.thresholds.mspt-critical", 50.0);
        }

        // Validate optimization mode
        String mode = config.getString("optimization.mode", "HYBRID_CONDITIONAL").toUpperCase(Locale.ROOT);
        if (!VALID_MODES.contains(mode)) {
            warnings.add("Unrecognized optimization.mode '" + mode + "'; defaulting to HYBRID_CONDITIONAL.");
            config.set("optimization.mode", "HYBRID_CONDITIONAL");
        }

        // Validate worker limits
        int coreThreads = config.getInt("workers.async-core-threads", 2);
        int maxThreads = config.getInt("workers.async-max-threads", 4);
        int queueCap = config.getInt("workers.queue-capacity", 256);
        if (coreThreads < 1 || coreThreads > 16) {
            warnings.add("Invalid workers.async-core-threads (" + coreThreads + "); resetting to 2.");
            coreThreads = 2;
            config.set("workers.async-core-threads", coreThreads);
        }
        if (maxThreads < coreThreads || maxThreads > 32) {
            warnings.add("Invalid workers.async-max-threads (" + maxThreads + "); clamping to [" + coreThreads + ", 32].");
            config.set("workers.async-max-threads", Math.max(coreThreads, Math.min(32, maxThreads)));
        }
        if (queueCap < 16 || queueCap > 4096) {
            warnings.add("Invalid workers.queue-capacity (" + queueCap + "); resetting to 256.");
            config.set("workers.queue-capacity", 256);
        }

        // Enforce mandatory safety restrictions
        if (!config.getBoolean("safety.enforce-gameplay-invariants", true)) {
            warnings.add("safety.enforce-gameplay-invariants cannot be disabled; enforcing true.");
            config.set("safety.enforce-gameplay-invariants", true);
        }
        if (!config.getBoolean("monitoring.memory.never-force-system-gc", true)) {
            warnings.add("monitoring.memory.never-force-system-gc cannot be disabled; enforcing true.");
            config.set("monitoring.memory.never-force-system-gc", true);
        }
        if (!config.getBoolean("integrations.spark.never-auto-upload", true)) {
            warnings.add("integrations.spark.never-auto-upload cannot be disabled; enforcing true.");
            config.set("integrations.spark.never-auto-upload", true);
        }
        if (!config.getBoolean("integrations.chunky.never-auto-start-generation", true)) {
            warnings.add("integrations.chunky.never-auto-start-generation cannot be disabled; enforcing true.");
            config.set("integrations.chunky.never-auto-start-generation", true);
        }

        return warnings;
    }
}
