package com.pratham.optimizeserver.analyzer;

import com.pratham.optimizeserver.commands.CommandPermissions;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.scheduler.SchedulerAdapter;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Emits cooldown-throttled console and optional administrator permission alerts when detected
 * performance bottlenecks meet or exceed the configured minimum severity threshold.
 * Operates asynchronously for evaluation and dispatches player messages safely on global/region threads.
 */
public final class AlertManager {

    private final Server server;
    private final SchedulerAdapter scheduler;
    private final ConfigManager configManager;
    private final Logger logger;
    private final Map<String, Long> lastAlertMillis = new ConcurrentHashMap<String, Long>();

    public AlertManager(Server server,
                        SchedulerAdapter scheduler,
                        ConfigManager configManager,
                        Logger logger) {
        this.server = server;
        this.scheduler = scheduler;
        this.configManager = configManager;
        this.logger = logger != null ? logger : Logger.getLogger("OptimizeServer");
    }

    /**
     * Evaluates findings against configured alert policy and emits throttled alerts.
     */
    public int processFindings(List<DiagnosticFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return 0;
        }
        boolean consoleEnabled = configManager == null || configManager.getBoolean("alerts.console-alerts-enabled", true);
        boolean adminBroadcast = configManager != null && configManager.getBoolean("alerts.admin-permission-broadcast", false);
        if (!consoleEnabled && !adminBroadcast) {
            return 0;
        }

        long cooldownMillis = Math.max(5L,
                configManager != null ? configManager.getLong("alerts.alert-cooldown-seconds", 60L) : 60L) * 1000L;
        Severity minSeverity = parseSeverity(
                configManager != null ? configManager.getString("alerts.min-severity", "HIGH") : "HIGH");

        long now = System.currentTimeMillis();
        int sent = 0;

        for (DiagnosticFinding finding : findings) {
            if (finding == null || !meetsSeverity(finding.getSeverity(), minSeverity)) {
                continue;
            }
            String key = finding.getSubsystem().name() + ":" + finding.getSeverity().name();
            Long prev = lastAlertMillis.get(key);
            if (prev != null && (now - prev.longValue()) < cooldownMillis) {
                continue;
            }
            lastAlertMillis.put(key, Long.valueOf(now));

            String summary = formatAlertSummary(finding);
            if (consoleEnabled && logger != null) {
                if (finding.getSeverity() == Severity.CRITICAL) {
                    logger.severe("[OptimizeServer Alert] " + summary);
                } else {
                    logger.warning("[OptimizeServer Alert] " + summary);
                }
            }
            if (adminBroadcast) {
                broadcastToAdmins(summary);
            }
            sent++;
        }
        return sent;
    }

    private void broadcastToAdmins(final String plainSummary) {
        if (server == null || scheduler == null) {
            return;
        }
        scheduler.runGlobal(new Runnable() {
            @Override
            public void run() {
                try {
                    Collection<? extends Player> online = server.getOnlinePlayers();
                    if (online == null || online.isEmpty()) {
                        return;
                    }
                    String formatted = ChatColor.RED + "[OptimizeServer Alert] " + ChatColor.WHITE + plainSummary;
                    for (Player player : online) {
                        if (player != null && (player.isOp() || player.hasPermission(CommandPermissions.ADMIN))) {
                            player.sendMessage(formatted);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String formatAlertSummary(DiagnosticFinding finding) {
        String firstEvidence = finding.getEvidence().isEmpty() ? finding.getRecommendedInvestigation() : finding.getEvidence().get(0);
        return "[" + finding.getSeverity().name() + "] "
                + finding.getSubsystem().getDisplayName()
                + " -> " + firstEvidence
                + " | Action: " + finding.getSuggestedResolution();
    }

    private static Severity parseSeverity(String raw) {
        if (raw != null) {
            String norm = raw.trim().toUpperCase(Locale.ROOT);
            for (Severity s : Severity.values()) {
                if (s.name().equals(norm)) {
                    return s;
                }
            }
        }
        return Severity.HIGH;
    }

    private static boolean meetsSeverity(Severity actual, Severity minimum) {
        if (actual == null) {
            return false;
        }
        return actual.ordinal() >= minimum.ordinal();
    }

    public void clearCooldowns() {
        lastAlertMillis.clear();
    }
}
