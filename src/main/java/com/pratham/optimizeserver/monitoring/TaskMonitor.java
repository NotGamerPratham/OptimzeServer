package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.scheduler.SchedulerAdapter;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitors scheduled server tasks and active worker threads to identify potential plugin-task bottlenecks.
 * Does not claim exact per-plugin CPU attribution without external profiler evidence (e.g., Spark).
 */
public final class TaskMonitor {

    private final Server server;
    private final SchedulerAdapter scheduler;

    private volatile int pendingServerTasks = 0;
    private volatile int activeServerWorkers = 0;
    private volatile Map<String, Integer> pendingTasksByPlugin = Collections.emptyMap();
    private volatile Map<String, Integer> activeWorkersByPlugin = Collections.emptyMap();

    public TaskMonitor(Server server, SchedulerAdapter scheduler) {
        this.server = server;
        this.scheduler = scheduler;
    }

    public void sample() {
        if (server == null || (scheduler != null && scheduler.isRegionThreaded())) {
            this.pendingServerTasks = scheduler != null && scheduler.getWorkerPool() != null
                    ? scheduler.getWorkerPool().getQueueSize()
                    : 0;
            this.activeServerWorkers = scheduler != null && scheduler.getWorkerPool() != null
                    ? scheduler.getWorkerPool().getActiveCount()
                    : 0;
            return;
        }

        try {
            BukkitScheduler bukkitScheduler = server.getScheduler();
            if (bukkitScheduler == null) {
                return;
            }

            List<BukkitTask> pending = bukkitScheduler.getPendingTasks();
            Map<String, Integer> byPluginPending = new HashMap<String, Integer>();
            int pendingCount = 0;
            if (pending != null) {
                pendingCount = pending.size();
                for (BukkitTask task : pending) {
                    if (task != null) {
                        Plugin owner = task.getOwner();
                        String name = owner != null ? owner.getName() : "Unknown";
                        Integer prev = byPluginPending.get(name);
                        byPluginPending.put(name, Integer.valueOf(prev != null ? prev.intValue() + 1 : 1));
                    }
                }
            }

            List<BukkitWorker> workers = bukkitScheduler.getActiveWorkers();
            Map<String, Integer> byPluginWorkers = new HashMap<String, Integer>();
            int workerCount = 0;
            if (workers != null) {
                workerCount = workers.size();
                for (BukkitWorker worker : workers) {
                    if (worker != null) {
                        Plugin owner = worker.getOwner();
                        String name = owner != null ? owner.getName() : "Unknown";
                        Integer prev = byPluginWorkers.get(name);
                        byPluginWorkers.put(name, Integer.valueOf(prev != null ? prev.intValue() + 1 : 1));
                    }
                }
            }

            this.pendingServerTasks = pendingCount;
            this.activeServerWorkers = workerCount;
            this.pendingTasksByPlugin = Collections.unmodifiableMap(byPluginPending);
            this.activeWorkersByPlugin = Collections.unmodifiableMap(byPluginWorkers);
        } catch (Throwable ignored) {
        }
    }

    public int getPendingServerTasks() {
        return pendingServerTasks;
    }

    public int getActiveServerWorkers() {
        return activeServerWorkers;
    }

    public Map<String, Integer> getPendingTasksByPlugin() {
        return pendingTasksByPlugin;
    }

    public Map<String, Integer> getActiveWorkersByPlugin() {
        return activeWorkersByPlugin;
    }
}

