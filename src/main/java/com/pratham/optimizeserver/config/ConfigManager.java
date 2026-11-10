package com.pratham.optimizeserver.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Manages loading, migration, validation, and optional bounded caching of OptimizeServer configuration.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final ConfigMigrator migrator = new ConfigMigrator();
    private final ConfigValidator validator = new ConfigValidator();
    private final Map<String, Object> lookupCache = new ConcurrentHashMap<String, Object>();
    private final AtomicBoolean cacheEnabled = new AtomicBoolean(true);
    private final AtomicLong cacheHits = new AtomicLong(0L);
    private final AtomicLong cacheMisses = new AtomicLong(0L);
    private volatile int maxCacheEntries = 512;
    private volatile FileConfiguration backingConfig;
    private volatile List<String> lastValidationMessages = Collections.emptyList();

    public ConfigManager(JavaPlugin plugin, FileConfiguration initialConfig, Logger logger) {
        this.plugin = plugin;
        this.backingConfig = initialConfig;
        this.logger = logger != null ? logger : (plugin != null ? plugin.getLogger() : Logger.getLogger("OptimizeServer"));
    }

    public synchronized void loadAndValidate() {
        if (plugin != null) {
            plugin.saveDefaultConfig();
            plugin.reloadConfig();
            this.backingConfig = plugin.getConfig();
        }
        processConfiguration(this.backingConfig);
    }

    public synchronized void processConfiguration(FileConfiguration config) {
        this.backingConfig = config;
        lookupCache.clear();
        List<String> messages = new ArrayList<String>();
        if (config != null) {
            List<String> migrationNotes = migrator.migrateIfNeeded(config);
            List<String> validationWarnings = validator.validateAndSanitize(config);
            messages.addAll(migrationNotes);
            messages.addAll(validationWarnings);

            this.maxCacheEntries = Math.max(32, config.getInt("optimization.modules.config-lookup-cache.max-entries", 512));
            this.cacheEnabled.set(config.getBoolean("optimization.modules.config-lookup-cache.enabled", true));

            if (!migrationNotes.isEmpty() && plugin != null) {
                try {
                    plugin.saveConfig();
                } catch (Throwable ignored) {
                }
            }
        }
        this.lastValidationMessages = Collections.unmodifiableList(messages);
        for (String msg : messages) {
            if (logger != null) {
                logger.info("[OptimizeServer] Config: " + msg);
            }
        }
    }

    public void setCacheEnabled(boolean enabled) {
        this.cacheEnabled.set(enabled);
        if (!enabled) {
            this.lookupCache.clear();
        }
    }

    public boolean isCacheEnabled() {
        return cacheEnabled.get();
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public long getCacheMisses() {
        return cacheMisses.get();
    }

    public int getCachedEntryCount() {
        return lookupCache.size();
    }

    public void clearCache() {
        lookupCache.clear();
    }

    public boolean getBoolean(String path, boolean def) {
        Object val = getCachedOrLoad(path, Boolean.valueOf(def));
        return val instanceof Boolean ? ((Boolean) val).booleanValue() : def;
    }

    public int getInt(String path, int def) {
        Object val = getCachedOrLoad(path, Integer.valueOf(def));
        return val instanceof Number ? ((Number) val).intValue() : def;
    }

    public long getLong(String path, long def) {
        Object val = getCachedOrLoad(path, Long.valueOf(def));
        return val instanceof Number ? ((Number) val).longValue() : def;
    }

    public double getDouble(String path, double def) {
        Object val = getCachedOrLoad(path, Double.valueOf(def));
        return val instanceof Number ? ((Number) val).doubleValue() : def;
    }

    public String getString(String path, String def) {
        Object val = getCachedOrLoad(path, def);
        return val != null ? String.valueOf(val) : def;
    }

    private Object getCachedOrLoad(String path, Object def) {
        FileConfiguration cfg = this.backingConfig;
        if (cfg == null || path == null) {
            return def;
        }
        if (cacheEnabled.get()) {
            Object cached = lookupCache.get(path);
            if (cached != null) {
                cacheHits.incrementAndGet();
                return cached;
            }
            cacheMisses.incrementAndGet();
            Object loaded = cfg.get(path, def);
            if (loaded != null && lookupCache.size() < maxCacheEntries) {
                lookupCache.put(path, loaded);
            }
            return loaded != null ? loaded : def;
        }
        cacheMisses.incrementAndGet();
        Object loaded = cfg.get(path, def);
        return loaded != null ? loaded : def;
    }

    public FileConfiguration getRawConfig() {
        return backingConfig;
    }

    public List<String> getLastValidationMessages() {
        return lastValidationMessages;
    }
}
