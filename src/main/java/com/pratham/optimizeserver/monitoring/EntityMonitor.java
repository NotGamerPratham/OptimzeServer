package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.compatibility.PlatformCapabilities;
import com.pratham.optimizeserver.scheduler.SchedulerAdapter;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import com.pratham.optimizeserver.metrics.FrozenCounts;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors total entities, entity distribution by type and world, and block/tile entity counts.
 * Uses Paper's allocation-free world counters ({@code World#getEntityCount()},
 * {@code World#getTileEntityCount()}) where available, and otherwise snapshot-free chunk reads with
 * time-budgeted incremental chunk slicing, so scanning thousands of loaded chunks never spikes
 * main-thread tick time or heap churn.
 * Strictly read-only: never removes entities, alters spawn limits, or disables mob AI.
 */
public final class EntityMonitor {

    private static final long DEFAULT_TILE_SCAN_BUDGET_NANOS = 400_000L; // 0.40 ms cap per sample
    private static final EntityType[] ENTITY_TYPES = EntityType.values();

    private final Server server;
    private final SchedulerAdapter scheduler;
    private final boolean hasTileEntitiesApi;
    private final Method worldEntityCountMethod;
    private final Method chunkTileEntitiesNoSnapshotMethod;

    // Resolved lazily against the live World implementation (Paper's CraftWorld exposes it; Spigot does not)
    private volatile Class<?> tileCountWorldClass;
    private volatile Method worldTileEntityCountMethod;

    private final AtomicInteger eventTrackedTotalEntities = new AtomicInteger(0);
    // Indexed by EntityType ordinal: the spawn listener fires for every item, orb, and projectile, so no hashing
    private final AtomicIntegerArray eventTrackedByType = new AtomicIntegerArray(ENTITY_TYPES.length);
    private final AtomicLong budgetYieldCount = new AtomicLong(0L);
    private final AtomicLong zeroSnapshotChunkReads = new AtomicLong(0L);
    private final AtomicLong fastTileCountReads = new AtomicLong(0L);

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
            int ordinal = type.ordinal();
            if (ordinal < ENTITY_TYPES.length) {
                eventTrackedByType.incrementAndGet(ordinal);
            }
        }
    }

    public void recordEntityRemove(EntityType type) {
        int cur = eventTrackedTotalEntities.decrementAndGet();
        if (cur < 0) {
            eventTrackedTotalEntities.set(0);
        }
        if (type != null) {
            int ordinal = type.ordinal();
            if (ordinal < ENTITY_TYPES.length) {
                int v = eventTrackedByType.decrementAndGet(ordinal);
                if (v < 0) {
                    eventTrackedByType.set(ordinal, 0);
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

    public long getFastTileCountReads() {
        return fastTileCountReads.get();
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
                this.entitiesByWorld = FrozenCounts.adopt(byWorld);
                if (byType != null) {
                    this.entitiesByType = FrozenCounts.adopt(byType);
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
        if (zeroAllocScanEnabled) {
            int fastTotal = readTileEntityCountFast(worlds);
            if (fastTotal >= 0) {
                // Paper sums its block-entity maps directly: no Chunk[] copy and no BlockState wrappers
                this.tileEntityCount = fastTotal;
                this.scanWorldCursor = 0;
                this.scanChunkCursor = 0;
                this.incrementalTileAccum = 0;
                return;
            }
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

    /**
     * Sums {@code World#getTileEntityCount()} across worlds, or returns -1 when the live World
     * implementation does not expose it (Spigot/CraftBukkit) so the caller falls back to chunk scanning.
     */
    private int readTileEntityCountFast(List<World> worlds) {
        int total = 0;
        for (World world : worlds) {
            if (world == null) {
                continue;
            }
            Method method = resolveTileEntityCountMethod(world.getClass());
            if (method == null) {
                return -1;
            }
            try {
                Object raw = method.invoke(world);
                if (!(raw instanceof Number)) {
                    return -1;
                }
                total += Math.max(0, ((Number) raw).intValue());
            } catch (Throwable ignored) {
                // Never retry a broken accessor every sample; chunk scanning remains available
                this.worldTileEntityCountMethod = null;
                this.tileCountWorldClass = world.getClass();
                return -1;
            }
        }
        fastTileCountReads.incrementAndGet();
        return total;
    }

    private Method resolveTileEntityCountMethod(Class<?> worldClass) {
        if (worldClass == tileCountWorldClass) {
            return worldTileEntityCountMethod;
        }
        Method resolved = findOptionalMethod(worldClass, "getTileEntityCount");
        if (resolved != null && resolved.getReturnType() != int.class) {
            resolved = null;
        }
        this.worldTileEntityCountMethod = resolved;
        this.tileCountWorldClass = worldClass;
        return resolved;
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
        Map<String, Integer> snapshot = null;
        for (int i = 0; i < ENTITY_TYPES.length; i++) {
            int count = eventTrackedByType.get(i);
            if (count > 0) {
                if (snapshot == null) {
                    snapshot = new HashMap<String, Integer>();
                }
                snapshot.put(ENTITY_TYPES[i].name(), Integer.valueOf(count));
            }
        }
        return snapshot == null ? Collections.<String, Integer>emptyMap() : FrozenCounts.adopt(snapshot);
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

