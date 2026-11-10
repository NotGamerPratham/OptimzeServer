package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.compatibility.PlatformCapabilities;
import com.pratham.optimizeserver.scheduler.SchedulerAdapter;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors total entities, entity distribution by type and world, and block/tile entity counts.
 * Strictly read-only: never removes entities, alters spawn limits, or disables mob AI.
 */
public final class EntityMonitor {

    private final Server server;
    private final SchedulerAdapter scheduler;
    private final boolean hasTileEntitiesApi;

    private final AtomicInteger eventTrackedTotalEntities = new AtomicInteger(0);
    private final Map<String, AtomicInteger> eventTrackedByType = new ConcurrentHashMap<String, AtomicInteger>();

    private long lastSampleNanos = 0L;
    private volatile long coalesceWindowNanos = 250_000_000L;
    private volatile int totalEntities = 0;
    private volatile int tileEntityCount = 0;
    private volatile Map<String, Integer> entitiesByType = Collections.emptyMap();
    private volatile Map<String, Integer> entitiesByWorld = Collections.emptyMap();

    public EntityMonitor(Server server, SchedulerAdapter scheduler, PlatformCapabilities capabilities) {
        this.server = server;
        this.scheduler = scheduler;
        this.hasTileEntitiesApi = capabilities != null && capabilities.hasTileEntitiesApi();
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
            int tiles = 0;
            Map<String, Integer> byType = new HashMap<String, Integer>();
            Map<String, Integer> byWorld = new HashMap<String, Integer>();

            try {
                List<World> worlds = server.getWorlds();
                if (worlds != null) {
                    for (World world : worlds) {
                        if (world == null) {
                            continue;
                        }
                        List<Entity> worldEntities = world.getEntities();
                        int worldCount = worldEntities != null ? worldEntities.size() : 0;
                        total += worldCount;
                        byWorld.put(world.getName(), Integer.valueOf(worldCount));

                        if (worldEntities != null) {
                            for (Entity e : worldEntities) {
                                if (e != null && e.getType() != null) {
                                    String typeName = e.getType().name();
                                    Integer prev = byType.get(typeName);
                                    byType.put(typeName, Integer.valueOf(prev != null ? prev.intValue() + 1 : 1));
                                }
                            }
                        }

                        if (hasTileEntitiesApi) {
                            Chunk[] loaded = world.getLoadedChunks();
                            if (loaded != null) {
                                for (Chunk chunk : loaded) {
                                    if (chunk != null) {
                                        BlockState[] states = chunk.getTileEntities();
                                        if (states != null) {
                                            tiles += states.length;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                this.totalEntities = total;
                this.tileEntityCount = tiles;
                this.entitiesByType = Collections.unmodifiableMap(byType);
                this.entitiesByWorld = Collections.unmodifiableMap(byWorld);
                eventTrackedTotalEntities.set(total);
            } catch (Throwable ignored) {
                fallbackToEventCounters();
            }
        } else {
            fallbackToEventCounters();
        }
    }

    private void fallbackToEventCounters() {
        this.totalEntities = Math.max(0, eventTrackedTotalEntities.get());
        Map<String, Integer> snapshot = new HashMap<String, Integer>();
        for (Map.Entry<String, AtomicInteger> entry : eventTrackedByType.entrySet()) {
            int count = entry.getValue().get();
            if (count > 0) {
                snapshot.put(entry.getKey(), Integer.valueOf(count));
            }
        }
        this.entitiesByType = Collections.unmodifiableMap(snapshot);
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

