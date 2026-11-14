package com.pratham.optimizeserver.commands;

import com.pratham.optimizeserver.analyzer.BottleneckCategory;
import com.pratham.optimizeserver.analyzer.DiagnosticCoordinator;
import com.pratham.optimizeserver.analyzer.DiagnosticFinding;
import com.pratham.optimizeserver.analyzer.RecommendationEngine;
import com.pratham.optimizeserver.analyzer.ServerConfigAuditor;
import com.pratham.optimizeserver.compatibility.CompatibilityReport;
import com.pratham.optimizeserver.core.PerformanceEngine;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.optimizer.OptimizationModule;
import com.pratham.optimizeserver.reporting.DiagnosticReport;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Primary administrative command executor and tab-completer for /optimizeserver (/os).
 */
public final class OptimizeServerCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_SUBCOMMANDS = Arrays.asList(
            "help", "version", "status", "tps", "mspt", "cpu", "memory", "network",
            "chunks", "entities", "diagnose", "profile", "health", "optimize", "tune", "capacity", "report", "reload"
    );

    private static final List<String> DIAGNOSE_SUBCOMMANDS = Arrays.asList(
            "full", "cpu", "memory", "chunks", "entities", "plugins"
    );

    private static final List<String> PROFILE_SUBCOMMANDS = Arrays.asList(
            "start", "stop", "status", "memory"
    );

    private static final List<String> OPTIMIZE_SUBCOMMANDS = Arrays.asList(
            "status", "list", "enable", "disable", "rollback"
    );

    private static final List<String> CHUNKS_SUBCOMMANDS = Arrays.asList(
            "status", "recommend", "start", "pause", "cancel"
    );

    private static final List<String> REPORT_SUBCOMMANDS = Arrays.asList(
            "generate", "history"
    );

    private final PerformanceEngine engine;
    private final String pluginVersion;

    public OptimizeServerCommand(PerformanceEngine engine, String pluginVersion) {
        this.engine = engine;
        this.pluginVersion = pluginVersion != null ? pluginVersion : "1.2.0";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender == null) {
            return true;
        }
        if (engine == null) {
            sender.sendMessage(ChatColor.RED + "[OptimizeServer] Engine is not initialized.");
            return true;
        }

        if (args == null || args.length == 0) {
            if (!requirePermission(sender, CommandPermissions.STATUS)) return true;
            handleStatus(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if ("help".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.STATUS)) return true;
            int page = args.length >= 2 ? parsePage(args[1], 1) : 1;
            handleHelp(sender, page);
            return true;
        }
        if ("version".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.STATUS)) return true;
            handleVersion(sender);
            return true;
        }
        if ("status".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.STATUS)) return true;
            handleStatus(sender);
            return true;
        }
        if ("tps".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.STATUS)) return true;
            handleTps(sender);
            return true;
        }
        if ("mspt".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.STATUS)) return true;
            handleMspt(sender);
            return true;
        }
        if ("cpu".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.STATUS)) return true;
            handleCpu(sender);
            return true;
        }
        if ("memory".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.STATUS)) return true;
            handleMemory(sender);
            return true;
        }
        if ("network".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.STATUS)) return true;
            handleNetwork(sender);
            return true;
        }
        if ("entities".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.STATUS)) return true;
            handleEntities(sender);
            return true;
        }
        if ("chunks".equals(sub)) {
            handleChunks(sender, args);
            return true;
        }
        if ("diagnose".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.DIAGNOSE)) return true;
            handleDiagnose(sender, args);
            return true;
        }
        if ("profile".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.PROFILE)) return true;
            handleProfile(sender, args);
            return true;
        }
        if ("health".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.PROFILE)
                    && !CommandPermissions.hasPermission(sender, CommandPermissions.STATUS)) {
                return true;
            }
            handleHealth(sender);
            return true;
        }
        if ("optimize".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.OPTIMIZE)) return true;
            handleOptimize(sender, args);
            return true;
        }
        if ("tune".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.DIAGNOSE)) return true;
            handleTune(sender, args);
            return true;
        }
        if ("capacity".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.STATUS)) return true;
            handleCapacity(sender);
            return true;
        }
        if ("report".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.REPORT)) return true;
            handleReport(sender, args);
            return true;
        }
        if ("reload".equals(sub)) {
            if (!requirePermission(sender, CommandPermissions.RELOAD)) return true;
            handleReload(sender);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "[OptimizeServer] Unknown subcommand '" + args[0]
                + "'. Type " + ChatColor.YELLOW + "/os help" + ChatColor.RED + " for available commands.");
        return true;
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (!CommandPermissions.hasPermission(sender, permission)) {
            sender.sendMessage(ChatColor.RED + "[OptimizeServer] Access denied. Required permission: " + permission);
            return false;
        }
        return true;
    }

    private void handleHelp(CommandSender sender, int page) {
        List<String> helpLines = Arrays.asList(
                ChatColor.YELLOW + "/os help [page]" + ChatColor.GRAY + " - Display paginated command reference",
                ChatColor.YELLOW + "/os version" + ChatColor.GRAY + " - Show plugin, platform, MC, and JVM compatibility info",
                ChatColor.YELLOW + "/os status" + ChatColor.GRAY + " - Show unified server performance summary",
                ChatColor.YELLOW + "/os tps" + ChatColor.GRAY + " - Display 5s, 30s, 1m, 5m, and 15m rolling TPS",
                ChatColor.YELLOW + "/os mspt" + ChatColor.GRAY + " - Display Average, P50, P95, P99, and StdDev MSPT",
                ChatColor.YELLOW + "/os cpu" + ChatColor.GRAY + " - Display process and system CPU utilization",
                ChatColor.YELLOW + "/os memory" + ChatColor.GRAY + " - Display heap, non-heap, and GC telemetry",
                ChatColor.YELLOW + "/os network" + ChatColor.GRAY + " - Display player RTT ping vs server tick/packet overhead",
                ChatColor.YELLOW + "/os chunks [status|recommend|start|pause|cancel]" + ChatColor.GRAY + " - Chunk metrics & Chunky control",
                ChatColor.YELLOW + "/os entities" + ChatColor.GRAY + " - Display entity and block-entity distributions",
                ChatColor.YELLOW + "/os diagnose [full|cpu|memory|chunks|entities|plugins]" + ChatColor.GRAY + " - Bottleneck analysis",
                ChatColor.YELLOW + "/os profile <start|stop|status|memory>" + ChatColor.GRAY + " - Spark profiler control",
                ChatColor.YELLOW + "/os health" + ChatColor.GRAY + " - Comprehensive server and Spark health overview",
                ChatColor.YELLOW + "/os optimize [status|list|enable|disable|rollback]" + ChatColor.GRAY + " - Hybrid optimization control",
                ChatColor.YELLOW + "/os tune [page]" + ChatColor.GRAY + " - Audit server configs & generate non-gameplay-altering presets",
                ChatColor.YELLOW + "/os capacity" + ChatColor.GRAY + " - Inspect RAM/OldGen headroom and safe player capacity",
                ChatColor.YELLOW + "/os report <generate|history>" + ChatColor.GRAY + " - Generate or list local diagnostic reports",
                ChatColor.YELLOW + "/os reload" + ChatColor.GRAY + " - Reload and re-validate config.yml"
        );
        CommandPagination.sendPaginated(sender, "OptimizeServer Command Help", helpLines, page, 8, "/os help");
    }

    private void handleVersion(CommandSender sender) {
        CompatibilityReport compat = engine.getCompatibilityManager().getReport();
        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "OptimizeServer v" + pluginVersion + ChatColor.DARK_AQUA + " ===");
        sender.sendMessage(ChatColor.GRAY + "Author: " + ChatColor.WHITE + "NotGamerPratham");
        sender.sendMessage(ChatColor.GRAY + "Engine State: " + ChatColor.GREEN + engine.getState().name());
        sender.sendMessage(ChatColor.GRAY + "Server Platform: " + ChatColor.WHITE + compat.getPlatform().getDisplayName()
                + " (Folia/RegionThreaded=" + compat.getPlatform().isRegionThreaded() + ")");
        sender.sendMessage(ChatColor.GRAY + "Minecraft Version: " + ChatColor.WHITE + compat.getMinecraftVersion().toString());
        sender.sendMessage(ChatColor.GRAY + "Java Runtime: " + ChatColor.WHITE + compat.getJavaRuntime().getVersionString()
                + " (" + compat.getJavaRuntime().getVmName() + ")");
        sender.sendMessage(ChatColor.GRAY + "Optional Integrations: " + ChatColor.WHITE
                + "Spark=" + (engine.getSparkIntegration().isAvailable() ? "Available" : "Absent")
                + ", Chunky=" + (engine.getChunkyIntegration().isAvailable() ? "Available" : "Absent"));
    }

    private void handleStatus(CommandSender sender) {
        MetricSnapshot s = engine.getLatestSnapshot();
        CompatibilityReport compat = engine.getCompatibilityManager().getReport();
        int activeMods = engine.getOptimizationManager().getRegistry().getActiveModules().size();
        int totalMods = engine.getOptimizationManager().getAllModules().size();

        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "OptimizeServer Status" + ChatColor.DARK_AQUA + " ===");
        sender.sendMessage(ChatColor.GRAY + "Platform: " + ChatColor.WHITE + compat.getPlatform().getDisplayName()
                + " MC " + compat.getMinecraftVersion().toShortString()
                + " | State: " + ChatColor.GREEN + engine.getState().name());
        sender.sendMessage(String.format(Locale.ROOT,
                ChatColor.GRAY + "TPS (5s/1m/15m): " + ChatColor.WHITE + "%.2f / %.2f / %.2f"
                        + ChatColor.GRAY + " | MSPT (Avg/P95/P99): " + ChatColor.WHITE + "%.2f / %.2f / %.2f ms",
                s.getTps5s(), s.getTps1m(), s.getTps15m(),
                s.getAverageMspt(), s.getP95Mspt(), s.getP99Mspt()));
        ServerConfigAuditor.CapacityAssessment cap = engine.evaluateCapacity();
        String oldGenSuffix = s.getPostGcHeapMaxBytes() > 0L
                ? String.format(Locale.ROOT, " | Post-GC OldGen: %.1f%%", s.getPostGcHeapUsagePercent())
                : "";
        sender.sendMessage(String.format(Locale.ROOT,
                ChatColor.GRAY + "CPU (Proc/Sys): " + ChatColor.WHITE + "%.1f%% / %.1f%% (%d cores)"
                        + ChatColor.GRAY + " | Heap: " + ChatColor.WHITE + "%d / %d MB (%.1f%%)%s",
                s.getProcessCpuLoadPercent(), s.getSystemCpuLoadPercent(), s.getAvailableProcessors(),
                s.getHeapUsedBytes() / (1024L * 1024L), s.getHeapMaxBytes() / (1024L * 1024L),
                s.getHeapUsagePercent(), oldGenSuffix));
        sender.sendMessage(String.format(Locale.ROOT,
                ChatColor.GRAY + "World: " + ChatColor.WHITE + "%d chunks, %d entities (%d tiles), %d players"
                        + ChatColor.GRAY + " | Optimizations: " + ChatColor.GREEN + "%d/%d active"
                        + ChatColor.GRAY + " | Capacity: " + ChatColor.WHITE + "%s",
                s.getLoadedChunks(), s.getTotalEntities(), s.getTileEntityCount(),
                s.getOnlinePlayers(), activeMods, totalMods, cap.getStatus().name()));
    }

    private void handleTps(CommandSender sender) {
        MetricSnapshot s = engine.getLatestSnapshot();
        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "Rolling Ticks Per Second (TPS)" + ChatColor.DARK_AQUA + " ===");
        sender.sendMessage(String.format(Locale.ROOT,
                ChatColor.GRAY + "5s : " + formatTps(s.getTps5s())
                        + ChatColor.GRAY + " | 30s: " + formatTps(s.getTps30s())
                        + ChatColor.GRAY + " | 1m : " + formatTps(s.getTps1m())
                        + ChatColor.GRAY + " | 5m : " + formatTps(s.getTps5m())
                        + ChatColor.GRAY + " | 15m: " + formatTps(s.getTps15m())));
    }

    private void handleMspt(CommandSender sender) {
        MetricSnapshot s = engine.getLatestSnapshot();
        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "Milliseconds Per Tick (MSPT)" + ChatColor.DARK_AQUA + " ===");
        sender.sendMessage(String.format(Locale.ROOT,
                ChatColor.GRAY + "Average: " + ChatColor.WHITE + "%.2f ms"
                        + ChatColor.GRAY + " | P50 (Median): " + ChatColor.WHITE + "%.2f ms"
                        + ChatColor.GRAY + " | P95: " + ChatColor.WHITE + "%.2f ms"
                        + ChatColor.GRAY + " | P99: " + ChatColor.WHITE + "%.2f ms"
                        + ChatColor.GRAY + " | StdDev: " + ChatColor.WHITE + "%.2f ms",
                s.getAverageMspt(), s.getP50Mspt(), s.getP95Mspt(), s.getP99Mspt(), s.getMsptStdDev()));
    }

    private void handleCpu(CommandSender sender) {
        MetricSnapshot s = engine.getLatestSnapshot();
        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "CPU Utilization Telemetry" + ChatColor.DARK_AQUA + " ===");
        sender.sendMessage(String.format(Locale.ROOT,
                ChatColor.GRAY + "JVM Process CPU: " + ChatColor.WHITE + "%.2f%%"
                        + ChatColor.GRAY + " | Host System CPU: " + ChatColor.WHITE + "%.2f%%"
                        + ChatColor.GRAY + " | Available Logical Cores: " + ChatColor.WHITE + "%d",
                s.getProcessCpuLoadPercent(), s.getSystemCpuLoadPercent(), s.getAvailableProcessors()));
    }

    private void handleMemory(CommandSender sender) {
        MetricSnapshot s = engine.getLatestSnapshot();
        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "JVM Memory & GC Telemetry" + ChatColor.DARK_AQUA + " ===");
        for (String line : engine.getSparkIntegration().getMemoryDiagnostics(s)) {
            sender.sendMessage(ChatColor.GRAY + "  " + line);
        }
    }

    private void handleNetwork(CommandSender sender) {
        MetricSnapshot s = engine.getLatestSnapshot();
        double tickDelay = engine.getNetworkMonitor().estimateTickInducedDelayMs(s.getAverageMspt());
        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "Network & Packet Processing Telemetry" + ChatColor.DARK_AQUA + " ===");
        sender.sendMessage(String.format(Locale.ROOT,
                ChatColor.GRAY + "Online Players: " + ChatColor.WHITE + "%d"
                        + ChatColor.GRAY + " | Avg Player RTT Ping: " + ChatColor.WHITE + "%.1f ms"
                        + ChatColor.GRAY + " | Max Player RTT Ping: " + ChatColor.WHITE + "%.1f ms",
                s.getOnlinePlayers(), s.getAveragePingMs(), s.getMaxPingMs()));
        sender.sendMessage(String.format(Locale.ROOT,
                ChatColor.GRAY + "Traffic Activity: " + ChatColor.WHITE + "%.1f in/s, %.1f out/s"
                        + ChatColor.GRAY + " | Packet Processing Overhead: " + ChatColor.WHITE + "%.2f ms/s"
                        + ChatColor.GRAY + " | Tick Overrun Delay: " + ChatColor.WHITE + "%.2f ms",
                s.getIncomingEventsPerSec(), s.getOutgoingEventsPerSec(),
                s.getPacketProcessingOverheadMs(), tickDelay));
        sender.sendMessage(ChatColor.GRAY + "Note: Physical network round-trip latency depends on client routing; plugins can only optimize server-side tick and packet processing.");
    }

    private void handleEntities(CommandSender sender) {
        MetricSnapshot s = engine.getLatestSnapshot();
        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "Entity & Block-Entity Telemetry" + ChatColor.DARK_AQUA + " ===");
        sender.sendMessage(ChatColor.GRAY + "Total Entities: " + ChatColor.WHITE + s.getTotalEntities()
                + ChatColor.GRAY + " | Tile/Block Entities: " + ChatColor.WHITE + s.getTileEntityCount());
        sender.sendMessage(ChatColor.GRAY + "Distribution by World: " + ChatColor.WHITE + s.getEntitiesByWorld());
        sender.sendMessage(ChatColor.GRAY + "Distribution by Type : " + ChatColor.WHITE + s.getEntitiesByType());
        sender.sendMessage(ChatColor.GRAY + "Gameplay Policy: Read-only monitoring; OptimizeServer never removes entities or disables mob AI.");
    }

    private void handleChunks(CommandSender sender, String[] args) {
        if (args.length == 1 || "status".equalsIgnoreCase(args[1])) {
            if (!CommandPermissions.hasPermission(sender, CommandPermissions.CHUNKS)
                    && !CommandPermissions.hasPermission(sender, CommandPermissions.STATUS)) {
                requirePermission(sender, CommandPermissions.CHUNKS);
                return;
            }
            MetricSnapshot s = engine.getLatestSnapshot();
            sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "Chunk & Pre-Generation Status" + ChatColor.DARK_AQUA + " ===");
            for (String line : engine.getChunkyIntegration().getStatusLines(s)) {
                sender.sendMessage(ChatColor.GRAY + "  " + line);
            }
            return;
        }

        if (!requirePermission(sender, CommandPermissions.CHUNKS)) {
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        MetricSnapshot s = engine.getLatestSnapshot();

        if ("recommend".equals(action)) {
            sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "Chunk Pre-Generation Recommendations" + ChatColor.DARK_AQUA + " ===");
            for (String line : engine.getChunkyIntegration().getRecommendations(s)) {
                sender.sendMessage(ChatColor.GRAY + "  " + line);
            }
            return;
        }
        if ("start".equals(action)) {
            String worldName = args.length >= 3 ? args[2] : null;
            double radius = 1500.0;
            if (args.length >= 4) {
                try {
                    radius = Double.parseDouble(args[3]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "[OptimizeServer] Invalid radius '" + args[3] + "'. Expected a number in blocks.");
                    return;
                }
            }
            for (String line : engine.getChunkyIntegration().startGeneration(worldName, radius, s)) {
                sender.sendMessage(ChatColor.YELLOW + "[Chunky] " + ChatColor.WHITE + line);
            }
            return;
        }
        if ("pause".equals(action)) {
            String worldName = args.length >= 3 ? args[2] : null;
            for (String line : engine.getChunkyIntegration().pauseOrResumeGeneration(worldName)) {
                sender.sendMessage(ChatColor.YELLOW + "[Chunky] " + ChatColor.WHITE + line);
            }
            return;
        }
        if ("cancel".equals(action)) {
            String worldName = args.length >= 3 ? args[2] : null;
            for (String line : engine.getChunkyIntegration().cancelGeneration(worldName)) {
                sender.sendMessage(ChatColor.YELLOW + "[Chunky] " + ChatColor.WHITE + line);
            }
            return;
        }

        sender.sendMessage(ChatColor.RED + "Usage: /os chunks <status|recommend|start [world] [radius]|pause [world]|cancel [world]>");
    }

    private void handleDiagnose(CommandSender sender, String[] args) {
        String scope = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "full";
        if (!DIAGNOSE_SUBCOMMANDS.contains(scope)) {
            sender.sendMessage(ChatColor.RED + "Invalid diagnostic scope '" + scope
                    + "'. Valid scopes: " + DIAGNOSE_SUBCOMMANDS);
            return;
        }

        DiagnosticCoordinator.DiagnosticEvaluation eval = engine.runDiagnostics(true);
        List<DiagnosticFinding> matched = new ArrayList<DiagnosticFinding>();
        for (DiagnosticFinding f : eval.getFindings()) {
            if (matchesDiagnoseScope(f.getSubsystem(), scope)) {
                matched.add(f);
            }
        }

        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA
                + "Performance Intelligence Diagnosis (" + scope.toUpperCase(Locale.ROOT) + ")" + ChatColor.DARK_AQUA + " ===");

        if ("plugins".equals(scope)) {
            Map<String, Integer> byPlugin = eval.getEvidenceBundle().getPendingTasksByPlugin();
            sender.sendMessage(ChatColor.GRAY + "Scheduled Pending Tasks by Plugin: " + ChatColor.WHITE + byPlugin);
            sender.sendMessage(ChatColor.GRAY + "Active Workers by Plugin: " + ChatColor.WHITE + eval.getEvidenceBundle().getActiveWorkersByPlugin());
            sender.sendMessage(ChatColor.GRAY + "Note: Exact per-plugin CPU attribution requires stack-trace profiling ('/os profile start').");
        }

        if (matched.isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + "  No active bottlenecks detected for scope '" + scope + "'.");
        } else {
            for (DiagnosticFinding f : matched) {
                sender.sendMessage(String.format(Locale.ROOT,
                        ChatColor.GOLD + "  [%s] %s " + ChatColor.GRAY + "(%s | Confidence %.0f%%)",
                        f.getSeverity().name(), f.getSubsystem().getDisplayName(), f.getId(), f.getConfidence() * 100.0));
                for (String ev : f.getEvidence()) {
                    sender.sendMessage(ChatColor.GRAY + "    - Evidence: " + ChatColor.WHITE + ev);
                }
                sender.sendMessage(ChatColor.GRAY + "    - Investigate: " + ChatColor.AQUA + f.getRecommendedInvestigation());
                sender.sendMessage(ChatColor.GRAY + "    - Resolution : " + ChatColor.GREEN + f.getSuggestedResolution());
            }
        }

        if ("full".equals(scope) && !eval.getRecommendations().isEmpty()) {
            sender.sendMessage(ChatColor.DARK_AQUA + "--- Infrastructure & Configuration Recommendations ---");
            for (RecommendationEngine.InfrastructureRecommendation rec : eval.getRecommendations()) {
                sender.sendMessage(ChatColor.YELLOW + "  [" + rec.getDomain() + "] " + ChatColor.WHITE + rec.getSummary());
                sender.sendMessage(ChatColor.GRAY + "    Action: " + rec.getActionItem());
            }
        }
    }

    private boolean matchesDiagnoseScope(BottleneckCategory cat, String scope) {
        if ("full".equals(scope)) return true;
        if ("cpu".equals(scope)) return cat == BottleneckCategory.CPU_BOUND;
        if ("memory".equals(scope)) return cat == BottleneckCategory.MEMORY_PRESSURE || cat == BottleneckCategory.GC_PRESSURE;
        if ("chunks".equals(scope)) return cat == BottleneckCategory.CHUNK_PROCESSING;
        if ("entities".equals(scope)) return cat == BottleneckCategory.ENTITY_PROCESSING;
        if ("plugins".equals(scope)) return cat == BottleneckCategory.PLUGIN_TASK_OVERHEAD;
        return true;
    }

    private void handleProfile(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /os profile <start|stop|status|memory>");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if ("start".equals(action)) {
            for (String line : engine.getSparkIntegration().startProfiling(sender)) {
                sender.sendMessage(ChatColor.AQUA + "[Profile] " + ChatColor.WHITE + line);
            }
            return;
        }
        if ("stop".equals(action)) {
            for (String line : engine.getSparkIntegration().stopProfiling(sender)) {
                sender.sendMessage(ChatColor.AQUA + "[Profile] " + ChatColor.WHITE + line);
            }
            return;
        }
        if ("status".equals(action)) {
            sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "Spark Profiler Status" + ChatColor.DARK_AQUA + " ===");
            for (String line : engine.getSparkIntegration().getStatusLines()) {
                sender.sendMessage(ChatColor.GRAY + "  " + line);
            }
            return;
        }
        if ("memory".equals(action)) {
            sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "Memory & GC Profiling Diagnostics" + ChatColor.DARK_AQUA + " ===");
            for (String line : engine.getSparkIntegration().getMemoryDiagnostics(engine.getLatestSnapshot())) {
                sender.sendMessage(ChatColor.GRAY + "  " + line);
            }
            return;
        }
        sender.sendMessage(ChatColor.RED + "Usage: /os profile <start|stop|status|memory>");
    }

    private void handleHealth(CommandSender sender) {
        handleStatus(sender);
        for (String line : engine.getSparkIntegration().getStatusLines()) {
            sender.sendMessage(ChatColor.GRAY + "  " + line);
        }
    }

    private void handleOptimize(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if ("status".equals(action)) {
            Collection<OptimizationModule> all = engine.getOptimizationManager().getAllModules();
            int active = engine.getOptimizationManager().getRegistry().getActiveModules().size();
            sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "Hybrid Optimization Engine Status" + ChatColor.DARK_AQUA + " ===");
            sender.sendMessage(ChatColor.GRAY + "Active Modules: " + ChatColor.GREEN + active + "/" + all.size()
                    + ChatColor.GRAY + " | Safe Mode: "
                    + (engine.getSafeModeController().isSafeModeActive() ? ChatColor.RED + "ACTIVE" : ChatColor.GREEN + "INACTIVE"));
            sender.sendMessage(ChatColor.GRAY + "Adaptive World Sample Interval: " + ChatColor.WHITE
                    + engine.getOptimizationManager().getEffectiveWorldSampleIntervalTicks() + " ticks");
            sender.sendMessage(ChatColor.GRAY + "Use " + ChatColor.YELLOW + "/os optimize list" + ChatColor.GRAY + " to inspect individual modules.");
            return;
        }
        if ("list".equals(action)) {
            int page = args.length >= 3 ? parsePage(args[2], 1) : 1;
            List<String> lines = new ArrayList<String>();
            for (OptimizationModule mod : engine.getOptimizationManager().getAllModules()) {
                lines.add((mod.isEnabled() ? ChatColor.GREEN + "[ON] " : ChatColor.RED + "[OFF] ")
                        + ChatColor.YELLOW + mod.getId() + ChatColor.GRAY + " (" + mod.getCategory().name() + ") - "
                        + ChatColor.WHITE + mod.getAfterMeasurement());
            }
            CommandPagination.sendPaginated(sender, "Optimization Modules", lines, page, 6, "/os optimize list");
            return;
        }
        if ("enable".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /os optimize enable <module-id>");
                return;
            }
            String id = args[2];
            boolean ok = engine.getOptimizationManager().enableModule(id);
            if (ok) {
                sender.sendMessage(ChatColor.GREEN + "[OptimizeServer] Enabled optimization module '" + id + "'.");
            } else {
                sender.sendMessage(ChatColor.RED + "[OptimizeServer] Failed to enable module '" + id
                        + "'. Check module ID and safety/compatibility constraints.");
            }
            return;
        }
        if ("disable".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /os optimize disable <module-id>");
                return;
            }
            String id = args[2];
            boolean ok = engine.getOptimizationManager().disableModule(id);
            if (ok) {
                sender.sendMessage(ChatColor.YELLOW + "[OptimizeServer] Disabled optimization module '" + id + "'.");
            } else {
                sender.sendMessage(ChatColor.RED + "[OptimizeServer] Unknown or failed module '" + id + "'.");
            }
            return;
        }
        if ("rollback".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /os optimize rollback <module-id|all>");
                return;
            }
            String id = args[2];
            if ("all".equalsIgnoreCase(id)) {
                int count = engine.getOptimizationManager().rollbackAll("Administrator command rollback");
                sender.sendMessage(ChatColor.YELLOW + "[OptimizeServer] Rolled back " + count + " active optimization modules.");
                return;
            }
            boolean ok = engine.getOptimizationManager().rollbackModule(id, "Administrator command rollback");
            if (ok) {
                sender.sendMessage(ChatColor.YELLOW + "[OptimizeServer] Rolled back module '" + id + "' to pre-activation state.");
            } else {
                sender.sendMessage(ChatColor.RED + "[OptimizeServer] Could not rollback module '" + id + "'.");
            }
            return;
        }

        sender.sendMessage(ChatColor.RED + "Usage: /os optimize <status|list|enable <id>|disable <id>|rollback <id>>");
    }

    private void handleTune(final CommandSender sender, String[] args) {
        final int page = args.length >= 2 ? parsePage(args[1], 1) : 1;
        if (engine.shouldOffloadAsyncIo()) {
            sender.sendMessage(ChatColor.GRAY + "[OptimizeServer] Auditing server configurations asynchronously...");
            engine.getScheduler().runAsync(new Runnable() {
                @Override
                public void run() {
                    final ServerConfigAuditor.AuditReport audit = engine.auditServerConfig();
                    File presetFile = null;
                    String presetError = null;
                    try {
                        presetFile = engine.writeTunePresets(audit);
                    } catch (IOException ex) {
                        presetError = ex.getMessage();
                    }
                    final File savedPreset = presetFile;
                    final String err = presetError;
                    engine.getScheduler().runGlobal(new Runnable() {
                        @Override
                        public void run() {
                            sendTuneOutput(sender, audit, savedPreset, err, page);
                        }
                    });
                }
            });
            return;
        }
        ServerConfigAuditor.AuditReport audit = engine.auditServerConfig();
        File presetFile = null;
        String presetError = null;
        try {
            presetFile = engine.writeTunePresets(audit);
        } catch (IOException ex) {
            presetError = ex.getMessage();
        }
        sendTuneOutput(sender, audit, presetFile, presetError, page);
    }

    private void sendTuneOutput(CommandSender sender,
                                ServerConfigAuditor.AuditReport audit,
                                File presetFile,
                                String presetError,
                                int page) {
        List<String> lines = new ArrayList<String>();
        for (ServerConfigAuditor.TuningSuggestion s : audit.getSuggestions()) {
            lines.add(ChatColor.YELLOW + "[" + s.getFile() + "] " + ChatColor.WHITE + s.getKey()
                    + ChatColor.GRAY + " (" + s.getCurrentValue() + " -> " + ChatColor.GREEN + s.getRecommendedValue() + ChatColor.GRAY + ")");
            lines.add(ChatColor.GRAY + "  Why: " + s.getReason());
        }
        if (lines.isEmpty()) {
            lines.add(ChatColor.GREEN + "All audited zero-gameplay-impact settings already look well tuned.");
        }
        CommandPagination.sendPaginated(sender, "Zero-Gameplay-Impact Config Audit", lines, page, 8, "/os tune");
        sender.sendMessage(ChatColor.GRAY + "Recommended JVM Flags: " + ChatColor.AQUA + audit.getRecommendedJvmCommand());
        if (presetFile != null) {
            sender.sendMessage(ChatColor.GRAY + "Saved safe preset sheet to: " + ChatColor.YELLOW + presetFile.getPath());
        } else if (presetError != null) {
            sender.sendMessage(ChatColor.RED + "Failed to write preset file: " + presetError);
        }
    }

    private void handleCapacity(CommandSender sender) {
        ServerConfigAuditor.CapacityAssessment cap = engine.evaluateCapacity();
        ChatColor statusColor = cap.getStatus() == ServerConfigAuditor.CapacityStatus.HEALTHY
                ? ChatColor.GREEN
                : (cap.getStatus() == ServerConfigAuditor.CapacityStatus.TIGHT_HEADROOM ? ChatColor.YELLOW : ChatColor.RED);
        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "Server Capacity & Headroom Assessment" + ChatColor.DARK_AQUA + " ===");
        sender.sendMessage(ChatColor.GRAY + "Capacity Status: " + statusColor + cap.getStatus().name());
        sender.sendMessage(ChatColor.GRAY + "Heap: " + ChatColor.WHITE + cap.getHeapUsedMb() + " MB used / " + cap.getHeapMaxMb() + " MB max"
                + ChatColor.GRAY + " | Post-GC OldGen: " + ChatColor.WHITE + cap.getPostGcOldGenUsedMb() + " MB / " + cap.getPostGcOldGenMaxMb() + " MB"
                + ChatColor.GRAY + " | Headroom: " + ChatColor.WHITE + cap.getHeadroomMb() + " MB");
        sender.sendMessage(ChatColor.GRAY + "Players: " + ChatColor.WHITE + cap.getOnlinePlayers() + " online / " + cap.getConfiguredMaxPlayers() + " max-players"
                + ChatColor.GRAY + " | Estimated Safe Player Capacity: " + statusColor + cap.getEstimatedSafePlayers());
        sender.sendMessage(String.format(Locale.ROOT,
                ChatColor.GRAY + "CPU Cores: " + ChatColor.WHITE + "%d" + ChatColor.GRAY + " | Loaded Chunks/Player: " + ChatColor.WHITE + "%.1f",
                cap.getAvailableCores(), cap.getLoadedChunksPerPlayer()));
        sender.sendMessage(ChatColor.GRAY + "Assessment: " + ChatColor.WHITE + cap.getSummary());
    }

    private void handleReport(final CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "generate";
        if ("generate".equals(action)) {
            if (engine.shouldOffloadAsyncIo()) {
                final DiagnosticReport queued = engine.generateReportAsyncAware(new Runnable() {
                    @Override
                    public void run() {
                        engine.getScheduler().runGlobal(new Runnable() {
                            @Override
                            public void run() {
                                sender.sendMessage(ChatColor.GREEN + "[OptimizeServer] Async diagnostic report write completed.");
                            }
                        });
                    }
                });
                sender.sendMessage(ChatColor.GREEN + "[OptimizeServer] Generated diagnostic report: "
                        + ChatColor.WHITE + queued.getReportId());
                sender.sendMessage(ChatColor.GRAY + "Saving asynchronously to: " + ChatColor.YELLOW + queued.getSavedFile().getPath());
            } else {
                DiagnosticReport report = engine.generateReport();
                sender.sendMessage(ChatColor.GREEN + "[OptimizeServer] Generated diagnostic report: "
                        + ChatColor.WHITE + report.getReportId());
                sender.sendMessage(ChatColor.GRAY + "Saved to: " + ChatColor.YELLOW + report.getSavedFile().getPath());
            }
            return;
        }
        if ("history".equals(action)) {
            int page = args.length >= 3 ? parsePage(args[2], 1) : 1;
            List<File> saved = engine.getReportGenerator().listSavedReportFiles();
            List<String> lines = new ArrayList<String>();
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
            for (File f : saved) {
                lines.add(ChatColor.YELLOW + f.getName() + ChatColor.GRAY + " ("
                        + (f.length() / 1024L) + " KB | " + fmt.format(new Date(f.lastModified())) + ")");
            }
            CommandPagination.sendPaginated(sender, "Saved Diagnostic Reports", lines, page, 8, "/os report history");
            return;
        }
        sender.sendMessage(ChatColor.RED + "Usage: /os report <generate|history>");
    }

    private void handleReload(CommandSender sender) {
        engine.reload();
        sender.sendMessage(ChatColor.GREEN + "[OptimizeServer] Configuration reloaded and validated successfully.");
    }

    private static String formatTps(double tps) {
        ChatColor color = tps >= 19.0 ? ChatColor.GREEN : (tps >= 16.0 ? ChatColor.YELLOW : ChatColor.RED);
        return color + String.format(Locale.ROOT, "%.2f", tps);
    }

    private static int parsePage(String raw, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (sender == null || !CommandPermissions.hasPermission(sender, CommandPermissions.STATUS)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filterPrefix(ROOT_SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("diagnose".equals(sub)) return filterPrefix(DIAGNOSE_SUBCOMMANDS, args[1]);
            if ("profile".equals(sub)) return filterPrefix(PROFILE_SUBCOMMANDS, args[1]);
            if ("optimize".equals(sub)) return filterPrefix(OPTIMIZE_SUBCOMMANDS, args[1]);
            if ("chunks".equals(sub)) return filterPrefix(CHUNKS_SUBCOMMANDS, args[1]);
            if ("report".equals(sub)) return filterPrefix(REPORT_SUBCOMMANDS, args[1]);
        }
        if (args.length == 3 && "optimize".equalsIgnoreCase(args[0])) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if ("enable".equals(action) || "disable".equals(action) || "rollback".equals(action)) {
                List<String> ids = new ArrayList<String>(engine.getOptimizationManager().getRegistry().getModuleIds());
                if ("rollback".equals(action)) {
                    ids.add("all");
                }
                return filterPrefix(ids, args[2]);
            }
        }
        return Collections.emptyList();
    }

    private static List<String> filterPrefix(Collection<String> candidates, String prefix) {
        String lower = prefix != null ? prefix.toLowerCase(Locale.ROOT) : "";
        List<String> matches = new ArrayList<String>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(c);
            }
        }
        return matches;
    }
}

