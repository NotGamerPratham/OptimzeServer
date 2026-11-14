package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.compatibility.PlatformCapabilities;
import com.pratham.optimizeserver.scheduler.SchedulerAdapter;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors total entities, entity distribution by type and world, and block/tile entity counts.
 * Uses zero-snapshot Paper APIs where available and time-budgeted incremental chunk slicing
 * so scanning thousands of loaded chunks never spikes main-thread tick time or heap churn.
 * Strictly read-only: never removes entities, alters spawn limits, or disables mob AI.
 */
public final class EntityMonitor {

    private static final long DEFAULT_TILE_SCAN_BUDGET_NANOS = 400_000L; // 0.40 ms cap per sample

    private final Server server;
    private final SchedulerAdapter scheduler;
    private final boolean hasTileEntitiesApi;
    private final Method worldEntityCountMethod;
    private final Method chunkTileEntitiesNoSnapshotMethod;

    private final AtomicInteger eventTrackedTotalEntities = new AtomicInteger(0);
    private final Map<String, AtomicInteger> eventTrackedByType = new ConcurrentHashMap<String, AtomicInteger>();
    private final AtomicLong budgetYieldCount = new AtomicLong(0L);
    private final AtomicLong zeroSnapshotChunkReads = new AtomicLong(0L);

    private long lastSampleNanos = 0L;
    private volatile long coalesceWindowNanos = 250_000_000L;
    private volatile boolean zeroAllocScanEnabled = true;
    private volatile long tileScanBudgetNanos = DEFAULT_TILE_SCAN_BUDGET_NANOS;

    // Incremental chunk scan cursor across sample ticks
    private int scanWorldCursor = 0;
    private int scanChunkCursor = 0;
    private int incrementalTileAccum = 0;

    private volatile int totalEntities = 0;
    private volatile int tileEntityCount = 0;
    private volatile Map<String, Integer> entitiesByType = Collections.emptyMap();
    private volatile Map<String, Integer> entitiesByWorld = Collections.emptyMap();

    public EntityMonitor(Server server, SchedulerAdapter scheduler, PlatformCapabilities capabilities) {
        this.server = server;
        this.scheduler = scheduler;
        this.hasTileEntitiesApi = capabilities != null && capabilities.hasTileEntitiesApi();
        this.worldEntityCountMethod = findOptionalMethod(World.class, "getEntityCount");
        this.chunkTileEntitiesNoSnapshotMethod = findOptionalMethod(Chunk.class, "getTileEntities", boolean.class);
    }

    private static Method findOptionalMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            return clazz.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void recordEntitySpawn(EntityType type) {
        eventTrackedTotalEntities.incrementAndGet();
        if (type != null) {
            String key = type.name();
            AtomicInteger counter = eventTrackedByType.get(key);
            if (counter == null) {
                counter = new AtomicInteger(0);
                AtomicInteger existing = eventTrackedByType.putIfAbsent(key, counter);
                if (existing != null) {
                    counter = existing;
                }
            }
            counter.incrementAndGet();
        }
    }

    public void recordEntityRemove(EntityType type) {
        int cur = eventTrackedTotalEntities.decrementAndGet();
        if (cur < 0) {
            eventTrackedTotalEntities.set(0);
        }
        if (type != null) {
            AtomicInteger counter = eventTrackedByType.get(type.name());
            if (counter != null) {
                int v = counter.decrementAndGet();
                if (v < 0) {
                    counter.set(0);
                }
            }
        }
    }

    public void setCoalesceWindowMillis(long windowMillis) {
        this.coalesceWindowNanos = Math.max(0L, windowMillis) * 1_000_000L;
    }

    public void setZeroAllocScanEnabled(boolean enabled) {
        this.zeroAllocScanEnabled = enabled;
    }

    public boolean isZeroAllocScanEnabled() {
        return zeroAllocScanEnabled;
    }

    public void setTileScanBudgetNanos(long budgetNanos) {
        this.tileScanBudgetNanos = Math.max(50_000L, budgetNanos);
    }

    public long getTileScanBudgetNanos() {
        return tileScanBudgetNanos;
    }

    public long getBudgetYieldCount() {
        return budgetYieldCount.get();
    }

    public long getZeroSnapshotChunkReads() {
        return zeroSnapshotChunkReads.get();
    }

    /**
     * Samples entity and block-entity distributions on the main/global thread.
     */
    public void sampleMainThread(long nowNanos) {
        if (lastSampleNanos > 0L && coalesceWindowNanos > 0L && (nowNanos - lastSampleNanos) < coalesceWindowNanos) {
            return;
        }
        this.lastSampleNanos = nowNanos;

        if (server != null && (scheduler == null || !scheduler.isRegionThreaded())) {
            int total = 0;
            Map<String, Integer> byType = zeroAllocScanEnabled && worldEntityCountMethod != null
                    ? null
                    : new HashMap<String, Integer>();
            Map<String, Integer> byWorld = new HashMap<String, Integer>();

            try {
                List<World> worlds = server.getWorlds();
                if (worlds != null) {
                    for (World world : worlds) {
                        if (world == null) {
                            continue;
                        }
                        if (zeroAllocScanEnabled && worldEntityCountMethod != null) {
                            int worldCount = readWorldEntityCountFast(world);
                            if (worldCount >= 0) {
                                total += worldCount;
                                byWorld.put(world.getName(), Integer.valueOf(worldCount));
                                continue;
                            }
                        }

                        List<Entity> worldEntities = world.getEntities();
                        int worldCount = worldEntities != null ? worldEntities.size() : 0;
                        total += worldCount;
                        byWorld.put(world.getName(), Integer.valueOf(worldCount));

                        if (worldEntities != null) {
                            if (byType == null) {
                                byType = new HashMap<String, Integer>();
                            }
                            for (Entity e : worldEntities) {
                                if (e != null && e.getType() != null) {
                                    String typeName = e.getType().name();
                                    Integer prev = byType.get(typeName);
                                    byType.put(typeName, Integer.valueOf(prev != null ? prev.intValue() + 1 : 1));
                                }
                            }
                        }
                    }

                    if (hasTileEntitiesApi) {
                        sampleTileEntities(worlds, nowNanos);
                    }
                }
                this.totalEntities = total;
                this.entitiesByWorld = byWorld.isEmpty()
                        ? Collections.<String, Integer>emptyMap()
                        : Collections.unmodifiableMap(byWorld);
                if (byType != null) {
                    this.entitiesByType = byType.isEmpty()
                            ? Collections.<String, Integer>emptyMap()
                            : Collections.unmodifiableMap(byType);
                } else {
                    this.entitiesByType = buildTypeSnapshotFromEvents();
                }
                eventTrackedTotalEntities.set(total);
            } catch (Throwable ignored) {
                fallbackToEventCounters();
            }
        } else {
            fallbackToEventCounters();
        }
    }

    private int readWorldEntityCountFast(World world) {
        try {
            Object raw = worldEntityCountMethod.invoke(world);
            if (raw instanceof Number) {
                return Math.max(0, ((Number) raw).intValue());
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private void sampleTileEntities(List<World> worlds, long startNanos) {
        if (worlds == null || worlds.isEmpty()) {
            this.tileEntityCount = 0;
            return;
        }
        if (!zeroAllocScanEnabled) {
            int fullTiles = 0;
            for (World world : worlds) {
                if (world == null) continue;
                Chunk[] loaded = world.getLoadedChunks();
                if (loaded != null) {
                    for (Chunk chunk : loaded) {
                        fullTiles += countChunkTileEntities(chunk);
                    }
                }
            }
            this.tileEntityCount = fullTiles;
            this.scanWorldCursor = 0;
            this.scanChunkCursor = 0;
            this.incrementalTileAccum = 0;
            return;
        }

        // Incremental time-budgeted traversal avoids blocking one tick across thousands of chunks
        long deadlineNanos = startNanos + tileScanBudgetNanos;
        if (scanWorldCursor >= worlds.size()) {
            scanWorldCursor = 0;
            scanChunkCursor = 0;
            incrementalTileAccum = 0;
        }

        while (scanWorldCursor < worlds.size()) {
            World world = worlds.get(scanWorldCursor);
            if (world != null) {
                Chunk[] loaded = world.getLoadedChunks();
                if (loaded != null) {
                    while (scanChunkCursor < loaded.length) {
                        incrementalTileAccum += countChunkTileEntities(loaded[scanChunkCursor]);
                        scanChunkCursor++;
                        if ((scanChunkCursor & 7) == 0 && System.nanoTime() >= deadlineNanos) {
                            budgetYieldCount.incrementAndGet();
                            return;
                        }
                    }
                }
            }
            scanWorldCursor++;
            scanChunkCursor = 0;
        }

        this.tileEntityCount = incrementalTileAccum;
        this.scanWorldCursor = 0;
        this.scanChunkCursor = 0;
        this.incrementalTileAccum = 0;
    }

    private int countChunkTileEntities(Chunk chunk) {
        if (chunk == null) {
            return 0;
        }
        if (zeroAllocScanEnabled && chunkTileEntitiesNoSnapshotMethod != null) {
            try {
                Object raw = chunkTileEntitiesNoSnapshotMethod.invoke(chunk, Boolean.FALSE);
                if (raw instanceof BlockState[]) {
                    zeroSnapshotChunkReads.incrementAndGet();
                    return ((BlockState[]) raw).length;
                }
            } catch (Throwable ignored) {
            }
        }
        BlockState[] states = chunk.getTileEntities();
        return states != null ? states.length : 0;
    }

    private Map<String, Integer> buildTypeSnapshotFromEvents() {
        if (eventTrackedByType.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> snapshot = new HashMap<String, Integer>();
        for (Map.Entry<String, AtomicInteger> entry : eventTrackedByType.entrySet()) {
            int count = entry.getValue().get();
            if (count > 0) {
                snapshot.put(entry.getKey(), Integer.valueOf(count));
            }
        }
        return snapshot.isEmpty() ? Collections.<String, Integer>emptyMap() : Collections.unmodifiableMap(snapshot);
    }

    private void fallbackToEventCounters() {
        this.totalEntities = Math.max(0, eventTrackedTotalEntities.get());
        this.entitiesByType = buildTypeSnapshotFromEvents();
    }

    public int getTotalEntities() {
        return totalEntities;
    }

    public int getTileEntityCount() {
        return tileEntityCount;
    }

    public Map<String, Integer> getEntitiesByType() {
        return entitiesByType;
    }

    public Map<String, Integer> getEntitiesByWorld() {
        return entitiesByWorld;
    }
}

