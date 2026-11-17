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

    // Resolved lazily against the live World implementation (Paper's CraftWorld exposes these; Spigot does not)
    private final WorldIntAccessor tileCountAccessor = new WorldIntAccessor("getTileEntityCount");
    private final WorldIntAccessor tickableTileCountAccessor = new WorldIntAccessor("getTickableTileEntityCount");
    private final WorldIntAccessor playerCountAccessor = new WorldIntAccessor("getPlayerCount");

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
    private int incrementalWorldTileAccum = 0;
    private Map<String, Integer> incrementalTilesByWorld = new HashMap<String, Integer>();

    private volatile int totalEntities = 0;
    private volatile int tileEntityCount = 0;
    private volatile int tickableTileEntityCount = -1;
    private volatile Map<String, Integer> tilesByWorld = Collections.emptyMap();
    private volatile Map<String, Integer> tickableTilesByWorld = Collections.emptyMap();
    private volatile Map<String, Integer> playersByWorld = Collections.emptyMap();
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
            Map<String, Integer> players = new HashMap<String, Integer>();

            try {
                List<World> worlds = server.getWorlds();
                if (worlds != null) {
                    for (World world : worlds) {
                        if (world == null) {
                            continue;
                        }
                        players.put(world.getName(), Integer.valueOf(readPlayerCount(world)));
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
                this.playersByWorld = FrozenCounts.adopt(players);
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
        if (zeroAllocScanEnabled && readTileEntityCountsFast(worlds)) {
            // Paper sums its block-entity maps directly: no Chunk[] copy and no BlockState wrappers
            resetIncrementalScan();
            return;
        }
        // Without Paper's counters there is no way to tell ticking block entities apart cheaply
        this.tickableTileEntityCount = -1;
        this.tickableTilesByWorld = Collections.emptyMap();
        if (!zeroAllocScanEnabled) {
            int fullTiles = 0;
            Map<String, Integer> perWorld = new HashMap<String, Integer>();
            for (World world : worlds) {
                if (world == null) continue;
                int worldTiles = 0;
                Chunk[] loaded = world.getLoadedChunks();
                if (loaded != null) {
                    for (Chunk chunk : loaded) {
                        worldTiles += countChunkTileEntities(chunk);
                    }
                }
                perWorld.put(world.getName(), Integer.valueOf(worldTiles));
                fullTiles += worldTiles;
            }
            this.tileEntityCount = fullTiles;
            this.tilesByWorld = FrozenCounts.adopt(perWorld);
            resetIncrementalScan();
            return;
        }

        // Incremental time-budgeted traversal avoids blocking one tick across thousands of chunks
        long deadlineNanos = startNanos + tileScanBudgetNanos;
        if (scanWorldCursor >= worlds.size()) {
            resetIncrementalScan();
        }

        while (scanWorldCursor < worlds.size()) {
            World world = worlds.get(scanWorldCursor);
            if (world != null) {
                Chunk[] loaded = world.getLoadedChunks();
                if (loaded != null) {
                    while (scanChunkCursor < loaded.length) {
                        int n = countChunkTileEntities(loaded[scanChunkCursor]);
                        incrementalTileAccum += n;
                        incrementalWorldTileAccum += n;
                        scanChunkCursor++;
                        if ((scanChunkCursor & 7) == 0 && System.nanoTime() >= deadlineNanos) {
                            budgetYieldCount.incrementAndGet();
                            return;
                        }
                    }
                }
                incrementalTilesByWorld.put(world.getName(), Integer.valueOf(incrementalWorldTileAccum));
            }
            incrementalWorldTileAccum = 0;
            scanWorldCursor++;
            scanChunkCursor = 0;
        }

        this.tileEntityCount = incrementalTileAccum;
        this.tilesByWorld = FrozenCounts.adopt(incrementalTilesByWorld);
        this.incrementalTilesByWorld = new HashMap<String, Integer>();
        resetIncrementalScan();
    }

    private void resetIncrementalScan() {
        this.scanWorldCursor = 0;
        this.scanChunkCursor = 0;
        this.incrementalTileAccum = 0;
        this.incrementalWorldTileAccum = 0;
        this.incrementalTilesByWorld.clear();
    }

    /**
     * Reads {@code World#getTileEntityCount()} (and, when present, {@code getTickableTileEntityCount()})
     * for every world. Returns false when the live World implementation lacks them (Spigot/CraftBukkit),
     * so the caller falls back to chunk scanning.
     */
    private boolean readTileEntityCountsFast(List<World> worlds) {
        int total = 0;
        int tickableTotal = 0;
        boolean tickableAvailable = true;
        Map<String, Integer> perWorld = new HashMap<String, Integer>();
        Map<String, Integer> tickablePerWorld = new HashMap<String, Integer>();
        for (World world : worlds) {
            if (world == null) {
                continue;
            }
            int tiles = tileCountAccessor.read(world);
            if (tiles < 0) {
                return false;
            }
            total += tiles;
            perWorld.put(world.getName(), Integer.valueOf(tiles));
            if (tickableAvailable) {
                int tickable = tickableTileCountAccessor.read(world);
                if (tickable < 0) {
                    tickableAvailable = false;
                } else {
                    tickableTotal += tickable;
                    tickablePerWorld.put(world.getName(), Integer.valueOf(tickable));
                }
            }
        }
        fastTileCountReads.incrementAndGet();
        this.tileEntityCount = total;
        this.tilesByWorld = FrozenCounts.adopt(perWorld);
        this.tickableTileEntityCount = tickableAvailable ? tickableTotal : -1;
        this.tickableTilesByWorld = tickableAvailable ? FrozenCounts.adopt(tickablePerWorld) : Collections.<String, Integer>emptyMap();
        return true;
    }

    private int readPlayerCount(World world) {
        int fast = playerCountAccessor.read(world);
        if (fast >= 0) {
            return fast;
        }
        List<?> players = world.getPlayers();
        return players != null ? players.size() : 0;
    }

    /**
     * Lazily resolved, failure-latching reflective {@code int} accessor on the live World class.
     * Returns -1 when the method is absent or has failed once (it is never retried every sample).
     */
    private static final class WorldIntAccessor {
        private final String methodName;
        private volatile Class<?> resolvedFor;
        private volatile Method method;

        WorldIntAccessor(String methodName) {
            this.methodName = methodName;
        }

        int read(World world) {
            Class<?> cls = world.getClass();
            Method m;
            if (cls == resolvedFor) {
                m = method;
            } else {
                m = findOptionalMethod(cls, methodName);
                if (m != null && m.getReturnType() != int.class) {
                    m = null;
                }
                this.method = m;
                this.resolvedFor = cls;
            }
            if (m == null) {
                return -1;
            }
            try {
                Object raw = m.invoke(world);
                return raw instanceof Number ? Math.max(0, ((Number) raw).intValue()) : -1;
            } catch (Throwable ignored) {
                this.method = null;
                return -1;
            }
        }
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

    /**
     * Block entities that tick every server tick (hoppers, furnaces, spawners, ...), or -1 when the
     * server does not expose Paper's {@code World#getTickableTileEntityCount()}.
     */
    public int getTickableTileEntityCount() {
        return tickableTileEntityCount;
    }

    public Map<String, Integer> getTilesByWorld() {
        return tilesByWorld;
    }

    public Map<String, Integer> getTickableTilesByWorld() {
        return tickableTilesByWorld;
    }

    public Map<String, Integer> getPlayersByWorld() {
        return playersByWorld;
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

