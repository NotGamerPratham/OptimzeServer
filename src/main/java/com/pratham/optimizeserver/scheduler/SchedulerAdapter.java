package com.pratham.optimizeserver.scheduler;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * Abstraction for scheduling tasks safely across Spigot, Paper, Purpur, and Folia.
 */
public interface SchedulerAdapter {

    /**
     * Executes a task on the primary server thread (Bukkit/Paper) or GlobalRegionScheduler (Folia).
     */
    TaskHandle runGlobal(Runnable task);

    /**
     * Executes a delayed task on the primary server thread or GlobalRegionScheduler.
     */
    TaskHandle runGlobalLater(Runnable task, long delayTicks);

    /**
     * Executes a repeating task on the primary server thread or GlobalRegionScheduler.
     */
    TaskHandle runGlobalTimer(Runnable task, long initialDelayTicks, long periodTicks);

    /**
     * Executes a task on the region owning the given world chunk (Folia) or main thread (Bukkit/Paper).
     */
    TaskHandle runAtChunk(World world, int chunkX, int chunkZ, Runnable task);

    /**
     * Executes a task on the region owning the given location (Folia) or main thread (Bukkit/Paper).
     */
    TaskHandle runAtLocation(Location location, Runnable task);

    /**
     * Executes a task on the thread owning the given entity (Folia EntityScheduler) or main thread.
     */
    TaskHandle runOnEntity(Entity entity, Runnable task, Runnable retiredCallback);

    /**
     * Executes a non-world task asynchronously on the bounded worker pool.
     */
    boolean runAsync(Runnable task);

    /**
     * Schedules a repeating non-world task asynchronously on the bounded worker pool.
     */
    TaskHandle runAsyncTimer(Runnable task, long initialDelayMs, long periodMs);

    /**
     * Returns true if the current thread is allowed to access global server state.
     */
    boolean isGlobalThread();

    /**
     * Returns true if the current thread owns the region for the specified location.
     */
    boolean isOwnedByCurrentRegion(Location location);

    /**
     * Returns true if the current thread owns the specified entity.
     */
    boolean isOwnedByCurrentRegion(Entity entity);

    /**
     * Returns true if the underlying server uses Folia regionalized multithreading.
     */
    boolean isRegionThreaded();

    /**
     * Returns the bounded asynchronous worker pool managed by this scheduler.
     */
    BoundedWorkerPool getWorkerPool();

    /**
     * Cancels all active tasks and shuts down internal worker executors.
     */
    void shutdown(long timeoutSeconds);
}

