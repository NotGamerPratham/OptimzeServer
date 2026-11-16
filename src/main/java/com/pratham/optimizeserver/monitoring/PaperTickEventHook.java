package com.pratham.optimizeserver.monitoring;

import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Passive, read-only bridge to Paper's {@code ServerTickEndEvent}, which the server already fires at the
 * end of every tick with that tick's exact work duration. Listening to it replaces a per-tick scheduler
 * task plus a per-tick {@code getTickTimes()} array copy with a single primitive read.
 *
 * <p>Resolved reflectively so the universal JAR still loads on Spigot/CraftBukkit, where the event does
 * not exist. Never cancels or modifies anything.</p>
 */
public final class PaperTickEventHook implements Listener {

    public static final String TICK_END_EVENT_CLASS = "com.destroystokyo.paper.event.server.ServerTickEndEvent";

    private final Class<? extends Event> eventClass;
    private final MethodHandle tickDurationHandle;
    private final TickMonitor tickMonitor;
    private final Logger logger;
    private volatile boolean active = true;
    private volatile boolean failureLogged = false;

    private PaperTickEventHook(Class<? extends Event> eventClass,
                               MethodHandle tickDurationHandle,
                               TickMonitor tickMonitor,
                               Logger logger) {
        this.eventClass = eventClass;
        this.tickDurationHandle = tickDurationHandle;
        this.tickMonitor = tickMonitor;
        this.logger = logger != null ? logger : Logger.getLogger("OptimizeServer");
    }

    /**
     * Registers the hook when the running server exposes {@code ServerTickEndEvent}.
     *
     * @return the registered hook, or {@code null} when the event is unavailable and the caller
     * must fall back to scheduler-based tick sampling.
     */
    public static PaperTickEventHook tryRegister(Plugin plugin, Server server, TickMonitor tickMonitor, Logger logger) {
        if (plugin == null || server == null || tickMonitor == null) {
            return null;
        }
        PaperTickEventHook hook = create(resolveEventClass(server), tickMonitor, logger);
        if (hook == null) {
            return null;
        }
        try {
            PluginManager pm = server.getPluginManager();
            if (pm == null) {
                return null;
            }
            pm.registerEvent(hook.eventClass, hook, EventPriority.MONITOR, hook.executor(), plugin, false);
            return hook;
        } catch (Throwable t) {
            if (logger != null) {
                logger.log(Level.FINE, "[OptimizeServer] ServerTickEndEvent registration failed; using scheduler tick sampling.", t);
            }
            return null;
        }
    }

    /**
     * Builds a hook for the given event class without registering it (used by registration and tests).
     */
    static PaperTickEventHook create(Class<?> rawEventClass, TickMonitor tickMonitor, Logger logger) {
        if (rawEventClass == null || !Event.class.isAssignableFrom(rawEventClass)) {
            return null;
        }
        try {
            MethodHandle handle = MethodHandles.publicLookup()
                    .findVirtual(rawEventClass, "getTickDuration", MethodType.methodType(double.class))
                    .asType(MethodType.methodType(double.class, Event.class));
            return new PaperTickEventHook(rawEventClass.asSubclass(Event.class), handle, tickMonitor, logger);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> resolveEventClass(Server server) {
        ClassLoader[] loaders = {
                server.getClass().getClassLoader(),
                PaperTickEventHook.class.getClassLoader()
        };
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(TICK_END_EVENT_CLASS, false, loader);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    EventExecutor executor() {
        return new EventExecutor() {
            @Override
            public void execute(Listener listener, Event event) {
                handle(event);
            }
        };
    }

    /**
     * Records the tick duration carried by a {@code ServerTickEndEvent}.
     */
    public void handle(Event event) {
        if (!active || event == null || !eventClass.isInstance(event)) {
            return;
        }
        try {
            double durationMs = (double) tickDurationHandle.invokeExact(event);
            tickMonitor.recordTickEndEvent(durationMs, System.nanoTime());
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                logger.log(Level.WARNING, "[OptimizeServer] Could not read ServerTickEndEvent duration; tick telemetry may be incomplete.", t);
            }
        }
    }

    public boolean isActive() {
        return active;
    }

    public void unregister() {
        active = false;
        try {
            HandlerList.unregisterAll(this);
        } catch (Throwable ignored) {
        }
    }
}
