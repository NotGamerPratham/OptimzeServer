package com.pratham.optimizeserver;

import com.pratham.optimizeserver.commands.OptimizeServerCommand;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.core.PerformanceEngine;
import com.pratham.optimizeserver.listeners.ServerActivityListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Main plugin entrypoint for OptimizeServer.
 * Author: NotGamerPratham
 */
public final class OptimizeServer extends JavaPlugin {

    private Logger pluginLogger;
    private ConfigManager configManager;
    private PerformanceEngine performanceEngine;
    private ServerActivityListener activityListener;

    @Override
    public void onEnable() {
        long startMillis = System.currentTimeMillis();
        Logger log = getCleanLogger();
        try {
            saveDefaultConfig();
            this.configManager = new ConfigManager(this, getConfig(), log);
            this.configManager.loadAndValidate();

            if (!configManager.getBoolean("general.enabled", true)) {
                log.info("Plugin is disabled in config.yml (general.enabled=false).");
                return;
            }

            this.performanceEngine = new PerformanceEngine(
                    this,
                    getServer(),
                    getDataFolder(),
                    log,
                    this.configManager
            );
            this.performanceEngine.start();

            PluginManager pm = getServer().getPluginManager();
            if (pm != null) {
                this.activityListener = new ServerActivityListener(
                        performanceEngine.getChunkMonitor(),
                        performanceEngine.getEntityMonitor(),
                        performanceEngine.getNetworkMonitor()
                );
                pm.registerEvents(this.activityListener, this);
            }

            PluginCommand cmd = getCommand("optimizeserver");
            if (cmd != null) {
                String version = getDescription() != null ? getDescription().getVersion() : "1.3.0";
                OptimizeServerCommand executor = new OptimizeServerCommand(performanceEngine, version);
                cmd.setExecutor(executor);
                cmd.setTabCompleter(executor);
            }

            long elapsed = System.currentTimeMillis() - startMillis;
            log.info("Enabled successfully in " + elapsed + " ms ("
                    + performanceEngine.getCompatibilityManager().getPlatform().getDisplayName()
                    + " MC " + performanceEngine.getCompatibilityManager().getMinecraftVersion().toShortString()
                    + ", State=" + performanceEngine.getState().name() + ").");
        } catch (Throwable t) {
            log.log(Level.SEVERE, "Critical error during plugin startup; entering safe shutdown.", t);
            onDisable();
        }
    }

    @Override
    public void onDisable() {
        Logger log = getCleanLogger();
        try {
            if (activityListener != null) {
                activityListener.setActive(false);
            }
            if (performanceEngine != null) {
                performanceEngine.stop();
            }
            log.info("Disabled cleanly.");
        } catch (Throwable t) {
            log.log(Level.WARNING, "Exception while shutting down PerformanceEngine", t);
        }
    }

    private Logger getCleanLogger() {
        if (this.pluginLogger == null) {
            this.pluginLogger = createCleanPluginLogger(getLogger());
        }
        return this.pluginLogger;
    }

    /**
     * Wraps Bukkit/Paper's PluginLogger (which already prepends "[OptimizeServer] ")
     * so internal prefixes like "[OptimizeServer] " or "[OptimizeServer Alert] " are not duplicated.
     */
    static Logger createCleanPluginLogger(final Logger delegate) {
        if (delegate == null) {
            return Logger.getLogger("OptimizeServer");
        }
        return new Logger(delegate.getName(), null) {
            @Override
            public boolean isLoggable(Level level) {
                return delegate.isLoggable(level);
            }

            @Override
            public Level getLevel() {
                return delegate.getLevel();
            }

            @Override
            public void log(LogRecord record) {
                if (record == null || !delegate.isLoggable(record.getLevel())) {
                    return;
                }
                String msg = record.getMessage();
                if (msg != null) {
                    if (msg.startsWith("[OptimizeServer] ")) {
                        record.setMessage(msg.substring("[OptimizeServer] ".length()));
                    } else if (msg.startsWith("[OptimizeServer Alert] ")) {
                        record.setMessage("[Alert] " + msg.substring("[OptimizeServer Alert] ".length()));
                    }
                }
                delegate.log(record);
            }
        };
    }

    public PerformanceEngine getPerformanceEngine() {
        return performanceEngine;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
}

