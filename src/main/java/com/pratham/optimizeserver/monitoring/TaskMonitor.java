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
 * Monitors scheduled server tasks (separating synchronous main-thread tasks from async tasks)
 * and active worker threads to identify potential plugin-task bottlenecks and task-leak growth.
 * Does not claim exact per-plugin CPU attribution without external profiler evidence (e.g., Spark).
 */
public final class TaskMonitor {

    private final Server server;
    private final SchedulerAdapter scheduler;

    private volatile int pendingServerTasks = 0;
    private volatile int pendingSyncTasks = 0;
    private volatile int activeServerWorkers = 0;
    private volatile Map<String, Integer> pendingTasksByPlugin = Collections.emptyMap();
    private volatile Map<String, Integer> pendingSyncTasksByPlugin = Collections.emptyMap();
    private volatile Map<String, Integer> activeWorkersByPlugin = Collections.emptyMap();
    private volatile String growingTaskPlugin = null;
    private volatile int growingTaskPluginStreak = 0;

    public TaskMonitor(Server server, SchedulerAdapter scheduler) {
        this.server = server;
        this.scheduler = scheduler;
    }

    public void sample() {
        if (server == null || (scheduler != null && scheduler.isRegionThreaded())) {
            this.pendingServerTasks = scheduler != null && scheduler.getWorkerPool() != null
                    ? scheduler.getWorkerPool().getQueueSize()
                    : 0;
            this.pendingSyncTasks = 0;
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
            Map<String, Integer> byPluginPending = pending != null && !pending.isEmpty()
                    ? new HashMap<String, Integer>()
                    : Collections.<String, Integer>emptyMap();
            Map<String, Integer> byPluginSync = pending != null && !pending.isEmpty()
                    ? new HashMap<String, Integer>()
                    : Collections.<String, Integer>emptyMap();
            int pendingCount = 0;
            int syncCount = 0;
            if (pending != null && !pending.isEmpty()) {
                pendingCount = pending.size();
                for (BukkitTask task : pending) {
                    if (task != null) {
                        Plugin owner = task.getOwner();
                        String name = owner != null ? owner.getName() : "Unknown";
                        incrementMap(byPluginPending, name);
                        if (safeIsSync(task)) {
                            syncCount++;
                            incrementMap(byPluginSync, name);
                        }
                    }
                }
            }

            List<BukkitWorker> workers = bukkitScheduler.getActiveWorkers();
            Map<String, Integer> byPluginWorkers = workers != null && !workers.isEmpty()
                    ? new HashMap<String, Integer>()
                    : Collections.<String, Integer>emptyMap();
            int workerCount = 0;
            if (workers != null && !workers.isEmpty()) {
                workerCount = workers.size();
                for (BukkitWorker worker : workers) {
                    if (worker != null) {
                        Plugin owner = worker.getOwner();
                        String name = owner != null ? owner.getName() : "Unknown";
                        incrementMap(byPluginWorkers, name);
                    }
                }
            }

            detectGrowingTaskPlugin(byPluginSync);

            this.pendingServerTasks = pendingCount;
            this.pendingSyncTasks = syncCount;
            this.activeServerWorkers = workerCount;
            this.pendingTasksByPlugin = byPluginPending.isEmpty()
                    ? Collections.<String, Integer>emptyMap()
                    : Collections.unmodifiableMap(byPluginPending);
            this.pendingSyncTasksByPlugin = byPluginSync.isEmpty()
                    ? Collections.<String, Integer>emptyMap()
                    : Collections.unmodifiableMap(byPluginSync);
            this.activeWorkersByPlugin = byPluginWorkers.isEmpty()
                    ? Collections.<String, Integer>emptyMap()
                    : Collections.unmodifiableMap(byPluginWorkers);
        } catch (Throwable ignored) {
        }
    }

    private void detectGrowingTaskPlugin(Map<String, Integer> currentSync) {
        String top = null;
        int topVal = 0;
        for (Map.Entry<String, Integer> e : currentSync.entrySet()) {
            int val = e.getValue() != null ? e.getValue().intValue() : 0;
            if (val > topVal) {
                topVal = val;
                top = e.getKey();
            }
        }
        if (top == null || topVal < 25) {
            this.growingTaskPlugin = null;
            this.growingTaskPluginStreak = 0;
            return;
        }
        Integer prev = pendingSyncTasksByPlugin.get(top);
        int prevVal = prev != null ? prev.intValue() : 0;
        if (top.equals(this.growingTaskPlugin) && topVal > prevVal) {
            this.growingTaskPluginStreak++;
        } else if (topVal > prevVal) {
            this.growingTaskPlugin = top;
            this.growingTaskPluginStreak = 1;
        } else {
            this.growingTaskPluginStreak = Math.max(0, this.growingTaskPluginStreak - 1);
            if (this.growingTaskPluginStreak == 0) {
                this.growingTaskPlugin = null;
            }
        }
    }

    private static void incrementMap(Map<String, Integer> map, String key) {
        Integer prev = map.get(key);
        map.put(key, Integer.valueOf(prev != null ? prev.intValue() + 1 : 1));
    }

    private static boolean safeIsSync(BukkitTask task) {
        try {
            return task.isSync();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public int getPendingServerTasks() {
        return pendingServerTasks;
    }

    public int getPendingSyncTasks() {
        return pendingSyncTasks;
    }

    public int getActiveServerWorkers() {
        return activeServerWorkers;
    }

    public Map<String, Integer> getPendingTasksByPlugin() {
        return pendingTasksByPlugin;
    }

    public Map<String, Integer> getPendingSyncTasksByPlugin() {
        return pendingSyncTasksByPlugin;
    }

    public Map<String, Integer> getActiveWorkersByPlugin() {
        return activeWorkersByPlugin;
    }

    public String getGrowingTaskPlugin() {
        return growingTaskPlugin;
    }

    public int getGrowingTaskPluginStreak() {
        return growingTaskPluginStreak;
    }
}

