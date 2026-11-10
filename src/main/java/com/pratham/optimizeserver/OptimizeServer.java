package com.pratham.optimizeserver;

import com.pratham.optimizeserver.commands.OptimizeServerCommand;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.core.PerformanceEngine;
import com.pratham.optimizeserver.listeners.ServerActivityListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Main plugin entrypoint for OptimizeServer.
 * Author: NotGamerPratham
 */
public final class OptimizeServer extends JavaPlugin {

    private ConfigManager configManager;
    private PerformanceEngine performanceEngine;

    @Override
    public void onEnable() {
        long startMillis = System.currentTimeMillis();
        try {
            saveDefaultConfig();
            this.configManager = new ConfigManager(this, getConfig(), getLogger());
            this.configManager.loadAndValidate();

            if (!configManager.getBoolean("general.enabled", true)) {
                getLogger().info("[OptimizeServer] Plugin is disabled in config.yml (general.enabled=false).");
                return;
            }

            this.performanceEngine = new PerformanceEngine(
                    this,
                    getServer(),
                    getDataFolder(),
                    getLogger(),
                    this.configManager
            );
            this.performanceEngine.start();

            PluginManager pm = getServer().getPluginManager();
            if (pm != null) {
                ServerActivityListener listener = new ServerActivityListener(
                        performanceEngine.getChunkMonitor(),
                        performanceEngine.getEntityMonitor(),
                        performanceEngine.getNetworkMonitor()
                );
                pm.registerEvents(listener, this);
            }

            PluginCommand cmd = getCommand("optimizeserver");
            if (cmd != null) {
                String version = getDescription() != null ? getDescription().getVersion() : "1.0.0";
                OptimizeServerCommand executor = new OptimizeServerCommand(performanceEngine, version);
                cmd.setExecutor(executor);
                cmd.setTabCompleter(executor);
            }

            long elapsed = System.currentTimeMillis() - startMillis;
            getLogger().info("[OptimizeServer] Enabled successfully in " + elapsed + " ms ("
                    + performanceEngine.getCompatibilityManager().getPlatform().getDisplayName()
                    + " MC " + performanceEngine.getCompatibilityManager().getMinecraftVersion().toShortString()
                    + ", State=" + performanceEngine.getState().name() + ").");
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[OptimizeServer] Critical error during plugin startup; entering safe shutdown.", t);
            onDisable();
        }
    }

    @Override
    public void onDisable() {
        try {
            if (performanceEngine != null) {
                performanceEngine.stop();
            }
            getLogger().info("[OptimizeServer] Disabled cleanly.");
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "[OptimizeServer] Exception while shutting down PerformanceEngine", t);
        }
    }

    public PerformanceEngine getPerformanceEngine() {
        return performanceEngine;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
}

