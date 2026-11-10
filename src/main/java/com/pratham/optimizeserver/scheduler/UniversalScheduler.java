package com.pratham.optimizeserver.scheduler;

import com.pratham.optimizeserver.compatibility.PlatformCapabilities;
import com.pratham.optimizeserver.compatibility.ServerPlatform;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Concrete universal scheduler supporting Bukkit/Spigot/Paper main-thread scheduling,
 * Folia regionalized scheduling, and a bounded asynchronous worker pool.
 */
public final class UniversalScheduler implements SchedulerAdapter {

    private final Plugin plugin;
    private final Server server;
    private final Logger logger;
    private final boolean foliaMode;
    private final BoundedWorkerPool workerPool;
    private final List<TaskHandle> trackedHandles = new CopyOnWriteArrayList<TaskHandle>();

    private Object globalRegionScheduler;
    private Method globalRunMethod;
    private Method globalRunDelayedMethod;
    private Method globalRunTimerMethod;

    private Object regionScheduler;
    private Method regionRunChunkMethod;
    private Method regionRunLocationMethod;

    private Method isGlobalTickThreadMethod;
    private Method isOwnedByCurrentRegionLocationMethod;
    private Method isOwnedByCurrentRegionEntityMethod;

    public UniversalScheduler(Plugin plugin,
                              Server server,
                              ServerPlatform platform,
                              PlatformCapabilities capabilities,
                              BoundedWorkerPool workerPool) {
        this.plugin = plugin;
        this.server = server;
        this.logger = plugin != null ? plugin.getLogger() : Logger.getLogger("OptimizeServer");
        this.workerPool = workerPool;
        boolean wantFolia = platform != null && platform.isRegionThreaded()
                && capabilities != null && capabilities.hasFoliaRegionSchedulers();
        this.foliaMode = wantFolia && initFoliaReflection();
    }

    private boolean initFoliaReflection() {
        if (server == null) {
            return false;
        }
        try {
            Class<?> serverClass = server.getClass();
            Method getGlobal = serverClass.getMethod("getGlobalRegionScheduler");
            this.globalRegionScheduler = getGlobal.invoke(server);
            Class<?> grsClass = this.globalRegionScheduler.getClass();
            this.globalRunMethod = grsClass.getMethod("run", Plugin.class, Consumer.class);
            this.globalRunDelayedMethod = grsClass.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
            this.globalRunTimerMethod = grsClass.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);

            Method getRegion = serverClass.getMethod("getRegionScheduler");
            this.regionScheduler = getRegion.invoke(server);
            Class<?> rsClass = this.regionScheduler.getClass();
            this.regionRunChunkMethod = rsClass.getMethod("run", Plugin.class, World.class, int.class, int.class, Consumer.class);
            this.regionRunLocationMethod = rsClass.getMethod("run", Plugin.class, Location.class, Consumer.class);

            try {
                this.isGlobalTickThreadMethod = serverClass.getMethod("isGlobalTickThread");
            } catch (NoSuchMethodException ignored) {
            }
            try {
                this.isOwnedByCurrentRegionLocationMethod = serverClass.getMethod("isOwnedByCurrentRegion", Location.class);
            } catch (NoSuchMethodException ignored) {
            }
            try {
                this.isOwnedByCurrentRegionEntityMethod = serverClass.getMethod("isOwnedByCurrentRegion", Entity.class);
            } catch (NoSuchMethodException ignored) {
            }
            return true;
        } catch (Throwable t) {
            logger.log(Level.WARNING, "[OptimizeServer] Failed to initialize Folia reflection bridge; falling back to conservative scheduling.", t);
            return false;
        }
    }

    @Override
    public TaskHandle runGlobal(final Runnable task) {
        if (task == null || server == null || plugin == null) {
            return new BoundedWorkerPool.NoOpTaskHandle();
        }
        if (foliaMode && globalRegionScheduler != null && globalRunMethod != null) {
            try {
                Object scheduledTask = globalRunMethod.invoke(globalRegionScheduler, plugin, toConsumer(task));
                TaskHandle handle = new ReflectiveTaskHandle(scheduledTask);
                trackedHandles.add(handle);
                return handle;
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[OptimizeServer] Error scheduling global Folia task", t);
                return new BoundedWorkerPool.NoOpTaskHandle();
            }
        }
        BukkitTask bukkitTask = server.getScheduler().runTask(plugin, wrapSafe(task));
        TaskHandle handle = new BukkitTaskHandle(bukkitTask);
        trackedHandles.add(handle);
        return handle;
    }

    @Override
    public TaskHandle runGlobalLater(final Runnable task, long delayTicks) {
        if (task == null || server == null || plugin == null) {
            return new BoundedWorkerPool.NoOpTaskHandle();
        }
        long safeDelay = Math.max(1L, delayTicks);
        if (foliaMode && globalRegionScheduler != null && globalRunDelayedMethod != null) {
            try {
                Object scheduledTask = globalRunDelayedMethod.invoke(globalRegionScheduler, plugin, toConsumer(task), safeDelay);
                TaskHandle handle = new ReflectiveTaskHandle(scheduledTask);
                trackedHandles.add(handle);
                return handle;
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[OptimizeServer] Error scheduling delayed Folia task", t);
                return new BoundedWorkerPool.NoOpTaskHandle();
            }
        }
        BukkitTask bukkitTask = server.getScheduler().runTaskLater(plugin, wrapSafe(task), safeDelay);
        TaskHandle handle = new BukkitTaskHandle(bukkitTask);
        trackedHandles.add(handle);
        return handle;
    }

    @Override
    public TaskHandle runGlobalTimer(final Runnable task, long initialDelayTicks, long periodTicks) {
        if (task == null || server == null || plugin == null) {
            return new BoundedWorkerPool.NoOpTaskHandle();
        }
        long safeInitial = Math.max(1L, initialDelayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        if (foliaMode && globalRegionScheduler != null && globalRunTimerMethod != null) {
            try {
                Object scheduledTask = globalRunTimerMethod.invoke(globalRegionScheduler, plugin, toConsumer(task), safeInitial, safePeriod);
                TaskHandle handle = new ReflectiveTaskHandle(scheduledTask);
                trackedHandles.add(handle);
                return handle;
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[OptimizeServer] Error scheduling repeating Folia task", t);
                return new BoundedWorkerPool.NoOpTaskHandle();
            }
        }
        BukkitTask bukkitTask = server.getScheduler().runTaskTimer(plugin, wrapSafe(task), safeInitial, safePeriod);
        TaskHandle handle = new BukkitTaskHandle(bukkitTask);
        trackedHandles.add(handle);
        return handle;
    }

    @Override
    public TaskHandle runAtChunk(World world, int chunkX, int chunkZ, Runnable task) {
        if (task == null || world == null || server == null || plugin == null) {
            return new BoundedWorkerPool.NoOpTaskHandle();
        }
        if (foliaMode && regionScheduler != null && regionRunChunkMethod != null) {
            try {
                Object scheduledTask = regionRunChunkMethod.invoke(regionScheduler, plugin, world, chunkX, chunkZ, toConsumer(task));
                return new ReflectiveTaskHandle(scheduledTask);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[OptimizeServer] Error scheduling Folia chunk region task", t);
                return new BoundedWorkerPool.NoOpTaskHandle();
            }
        }
        return runGlobal(task);
    }

    @Override
    public TaskHandle runAtLocation(Location location, Runnable task) {
        if (task == null || location == null || server == null || plugin == null) {
            return new BoundedWorkerPool.NoOpTaskHandle();
        }
        if (foliaMode && regionScheduler != null && regionRunLocationMethod != null) {
            try {
                Object scheduledTask = regionRunLocationMethod.invoke(regionScheduler, plugin, location, toConsumer(task));
                return new ReflectiveTaskHandle(scheduledTask);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[OptimizeServer] Error scheduling Folia location region task", t);
                return new BoundedWorkerPool.NoOpTaskHandle();
            }
        }
        return runGlobal(task);
    }

    @Override
    public TaskHandle runOnEntity(Entity entity, Runnable task, Runnable retiredCallback) {
        if (task == null || entity == null || server == null || plugin == null) {
            return new BoundedWorkerPool.NoOpTaskHandle();
        }
        if (foliaMode) {
            try {
                Method getScheduler = entity.getClass().getMethod("getScheduler");
                Object entityScheduler = getScheduler.invoke(entity);
                Method runMethod = entityScheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Runnable.class);
                Object scheduledTask = runMethod.invoke(entityScheduler, plugin, toConsumer(task), retiredCallback);
                if (scheduledTask == null) {
                    return new BoundedWorkerPool.NoOpTaskHandle();
                }
                return new ReflectiveTaskHandle(scheduledTask);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[OptimizeServer] Error scheduling Folia entity task", t);
                return new BoundedWorkerPool.NoOpTaskHandle();
            }
        }
        return runGlobal(task);
    }

    @Override
    public boolean runAsync(Runnable task) {
        return workerPool != null && workerPool.submitAsync(task);
    }

    @Override
    public TaskHandle runAsyncTimer(Runnable task, long initialDelayMs, long periodMs) {
        if (workerPool == null) {
            return new BoundedWorkerPool.NoOpTaskHandle();
        }
        TaskHandle handle = workerPool.scheduleAsyncTimer(task, initialDelayMs, periodMs);
        trackedHandles.add(handle);
        return handle;
    }

    @Override
    public boolean isGlobalThread() {
        if (server == null) {
            return false;
        }
        if (foliaMode && isGlobalTickThreadMethod != null) {
            try {
                Object res = isGlobalTickThreadMethod.invoke(server);
                return Boolean.TRUE.equals(res);
            } catch (Throwable ignored) {
            }
        }
        return server.isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(Location location) {
        if (server == null || location == null) {
            return false;
        }
        if (foliaMode && isOwnedByCurrentRegionLocationMethod != null) {
            try {
                Object res = isOwnedByCurrentRegionLocationMethod.invoke(server, location);
                return Boolean.TRUE.equals(res);
            } catch (Throwable ignored) {
                return false;
            }
        }
        return server.isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(Entity entity) {
        if (server == null || entity == null) {
            return false;
        }
        if (foliaMode && isOwnedByCurrentRegionEntityMethod != null) {
            try {
                Object res = isOwnedByCurrentRegionEntityMethod.invoke(server, entity);
                return Boolean.TRUE.equals(res);
            } catch (Throwable ignored) {
                return false;
            }
        }
        return server.isPrimaryThread();
    }

    @Override
    public boolean isRegionThreaded() {
        return foliaMode;
    }

    @Override
    public BoundedWorkerPool getWorkerPool() {
        return workerPool;
    }

    @Override
    public void shutdown(long timeoutSeconds) {
        for (TaskHandle handle : trackedHandles) {
            try {
                handle.cancel();
            } catch (Throwable ignored) {
            }
        }
        trackedHandles.clear();
        if (workerPool != null) {
            workerPool.shutdownGracefully(timeoutSeconds);
        }
    }

    private Runnable wrapSafe(final Runnable task) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "[OptimizeServer] Uncaught exception in scheduled server task", t);
                }
            }
        };
    }

    private Consumer<Object> toConsumer(final Runnable task) {
        return new Consumer<Object>() {
            @Override
            public void accept(Object ignored) {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "[OptimizeServer] Uncaught exception in Folia scheduled task", t);
                }
            }
        };
    }

    private static final class BukkitTaskHandle implements TaskHandle {
        private final BukkitTask task;
        private volatile boolean cancelled = false;

        BukkitTaskHandle(BukkitTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (task != null) {
                task.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled || (task != null && task.isCancelled());
        }
    }

    private static final class ReflectiveTaskHandle implements TaskHandle {
        private final Object scheduledTask;
        private volatile boolean cancelled = false;

        ReflectiveTaskHandle(Object scheduledTask) {
            this.scheduledTask = scheduledTask;
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (scheduledTask != null) {
                try {
                    Method cancelMethod = scheduledTask.getClass().getMethod("cancel");
                    cancelMethod.setAccessible(true);
                    cancelMethod.invoke(scheduledTask);
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public boolean isCancelled() {
            if (cancelled) {
                return true;
            }
            if (scheduledTask != null) {
                try {
                    Method isCancelledMethod = scheduledTask.getClass().getMethod("isCancelled");
                    isCancelledMethod.setAccessible(true);
                    Object res = isCancelledMethod.invoke(scheduledTask);
                    return Boolean.TRUE.equals(res);
                } catch (Throwable ignored) {
                }
            }
            return false;
        }
    }
}

