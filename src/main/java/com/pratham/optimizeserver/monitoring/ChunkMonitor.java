package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.scheduler.SchedulerAdapter;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors loaded chunks, chunk load/unload frequency, and new chunk generation rate.
 * Uses Paper O(1) World#getChunkCount() when available to avoid copying Chunk[] arrays on the main thread.
 * Does not interfere with legitimate chunk loading or unloading.
 */
public final class ChunkMonitor {

    private final Server server;
    private final SchedulerAdapter scheduler;
    private final Method worldChunkCountMethod;

    private final AtomicLong chunkLoadCounter = new AtomicLong(0L);
    private final AtomicLong chunkUnloadCounter = new AtomicLong(0L);
    private final AtomicLong chunkGenCounter = new AtomicLong(0L);
    private final AtomicInteger eventTrackedLoadedChunks = new AtomicInteger(0);
    private final AtomicLong coalescedQueryHits = new AtomicLong(0L);
    private final AtomicLong fastChunkCountHits = new AtomicLong(0L);

    private long lastSampleNanos = 0L;
    private long lastLoadCount = 0L;
    private long lastUnloadCount = 0L;
    private long lastGenCount = 0L;
    private volatile long coalesceWindowNanos = 250_000_000L; // 250ms

    private volatile int loadedChunks = 0;
    private volatile double chunkLoadRatePerSec = 0.0;
    private volatile double chunkUnloadRatePerSec = 0.0;
    private volatile double chunkGenRatePerSec = 0.0;
    private volatile Map<String, Integer> chunksByWorld = Collections.emptyMap();

    public ChunkMonitor(Server server, SchedulerAdapter scheduler) {
        this.server = server;
        this.scheduler = scheduler;
        this.worldChunkCountMethod = findOptionalChunkCountMethod();
    }

    private static Method findOptionalChunkCountMethod() {
        try {
            return World.class.getMethod("getChunkCount");
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void recordChunkLoad(boolean newlyGenerated) {
        chunkLoadCounter.incrementAndGet();
        eventTrackedLoadedChunks.incrementAndGet();
        if (newlyGenerated) {
            chunkGenCounter.incrementAndGet();
        }
    }

    public void recordChunkUnload() {
        chunkUnloadCounter.incrementAndGet();
        int cur = eventTrackedLoadedChunks.decrementAndGet();
        if (cur < 0) {
            eventTrackedLoadedChunks.set(0);
        }
    }

    public void setCoalesceWindowMillis(long windowMillis) {
        this.coalesceWindowNanos = Math.max(0L, windowMillis) * 1_000_000L;
    }

    public long getCoalesceWindowMillis() {
        return coalesceWindowNanos / 1_000_000L;
    }

    public long getCoalescedQueryHits() {
        return coalescedQueryHits.get();
    }

    /**
     * Samples loaded chunk counts and rate deltas on the main/global thread.
     */
    public void sampleMainThread(long nowNanos) {
        if (lastSampleNanos > 0L && coalesceWindowNanos > 0L && (nowNanos - lastSampleNanos) < coalesceWindowNanos) {
            coalescedQueryHits.incrementAndGet();
            return;
        }

        int totalLoaded = 0;
        Map<String, Integer> perWorld = new HashMap<String, Integer>();

        if (server != null && (scheduler == null || !scheduler.isRegionThreaded())) {
            try {
                List<World> worlds = server.getWorlds();
                if (worlds != null) {
                    for (World world : worlds) {
                        if (world != null) {
                            int c = readWorldChunkCount(world);
                            totalLoaded += c;
                            perWorld.put(world.getName(), Integer.valueOf(c));
                        }
                    }
                }
                eventTrackedLoadedChunks.set(totalLoaded);
            } catch (Throwable ignored) {
                totalLoaded = Math.max(0, eventTrackedLoadedChunks.get());
            }
        } else {
            totalLoaded = Math.max(0, eventTrackedLoadedChunks.get());
        }

        this.loadedChunks = totalLoaded;
        this.chunksByWorld = Collections.unmodifiableMap(perWorld);

        long curLoads = chunkLoadCounter.get();
        long curUnloads = chunkUnloadCounter.get();
        long curGens = chunkGenCounter.get();

        if (lastSampleNanos > 0L && nowNanos > lastSampleNanos) {
            double elapsedSec = (nowNanos - lastSampleNanos) / 1_000_000_000.0;
            if (elapsedSec > 0.05) {
                this.chunkLoadRatePerSec = Math.max(0L, curLoads - lastLoadCount) / elapsedSec;
                this.chunkUnloadRatePerSec = Math.max(0L, curUnloads - lastUnloadCount) / elapsedSec;
                this.chunkGenRatePerSec = Math.max(0L, curGens - lastGenCount) / elapsedSec;
            }
        }

        this.lastSampleNanos = nowNanos;
        this.lastLoadCount = curLoads;
        this.lastUnloadCount = curUnloads;
        this.lastGenCount = curGens;
    }

    private int readWorldChunkCount(World world) {
        if (worldChunkCountMethod != null) {
            try {
                Object raw = worldChunkCountMethod.invoke(world);
                if (raw instanceof Number) {
                    fastChunkCountHits.incrementAndGet();
                    return Math.max(0, ((Number) raw).intValue());
                }
            } catch (Throwable ignored) {
            }
        }
        Chunk[] chunks = world.getLoadedChunks();
        return chunks != null ? chunks.length : 0;
    }

    public long getFastChunkCountHits() {
        return fastChunkCountHits.get();
    }

    public int getLoadedChunks() {
        return loadedChunks;
    }

    public double getChunkLoadRatePerSec() {
        return chunkLoadRatePerSec;
    }

    public double getChunkUnloadRatePerSec() {
        return chunkUnloadRatePerSec;
    }

    public double getChunkGenRatePerSec() {
        return chunkGenRatePerSec;
    }

    public Map<String, Integer> getChunksByWorld() {
        return chunksByWorld;
    }
}

