package com.pratham.optimizeserver;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dynamic proxy test harness for simulating Spigot, Paper, Purpur, and Folia Server instances,
 * BukkitScheduler, and CommandSenders without requiring external mocking agents.
 */
public final class TestServerHarness {

    private TestServerHarness() {
    }

    public static Server createMockServer(final String name,
                                          final String version,
                                          final String bukkitVersion,
                                          final boolean primaryThread) {
        final BukkitScheduler scheduler = createMockBukkitScheduler();
        final PluginManager pluginManager = createMockPluginManager();
        final ServicesManager servicesManager = createMockServicesManager();

        return (Server) Proxy.newProxyInstance(
                TestServerHarness.class.getClassLoader(),
                new Class<?>[]{Server.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String m = method.getName();
                        if ("getName".equals(m)) return name;
                        if ("getVersion".equals(m)) return version;
                        if ("getBukkitVersion".equals(m)) return bukkitVersion;
                        if ("isPrimaryThread".equals(m)) return Boolean.valueOf(primaryThread);
                        if ("getScheduler".equals(m)) return scheduler;
                        if ("getPluginManager".equals(m)) return pluginManager;
                        if ("getServicesManager".equals(m)) return servicesManager;
                        if ("getOnlinePlayers".equals(m)) return Collections.<Player>emptyList();
                        if ("getWorlds".equals(m)) return Collections.<World>emptyList();
                        if ("dispatchCommand".equals(m)) return Boolean.TRUE;
                        return defaultReturnValue(method.getReturnType());
                    }
                }
        );
    }

    public static BukkitScheduler createMockBukkitScheduler() {
        return (BukkitScheduler) Proxy.newProxyInstance(
                TestServerHarness.class.getClassLoader(),
                new Class<?>[]{BukkitScheduler.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String m = method.getName();
                        if ("runTask".equals(m) || "runTaskLater".equals(m) || "runTaskTimer".equals(m)) {
                            return createMockBukkitTask();
                        }
                        if ("getPendingTasks".equals(m)) {
                            return Collections.<BukkitTask>emptyList();
                        }
                        if ("getActiveWorkers".equals(m)) {
                            return Collections.<BukkitWorker>emptyList();
                        }
                        return defaultReturnValue(method.getReturnType());
                    }
                }
        );
    }

    public static BukkitTask createMockBukkitTask() {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        return (BukkitTask) Proxy.newProxyInstance(
                TestServerHarness.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String m = method.getName();
                        if ("cancel".equals(m)) {
                            cancelled.set(true);
                            return null;
                        }
                        if ("isCancelled".equals(m)) {
                            return Boolean.valueOf(cancelled.get());
                        }
                        if ("getTaskId".equals(m)) {
                            return Integer.valueOf(1);
                        }
                        return defaultReturnValue(method.getReturnType());
                    }
                }
        );
    }

    public static PluginManager createMockPluginManager() {
        return (PluginManager) Proxy.newProxyInstance(
                TestServerHarness.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getPlugin".equals(method.getName())) {
                            return null;
                        }
                        if ("getPlugins".equals(method.getName())) {
                            return new Plugin[0];
                        }
                        return defaultReturnValue(method.getReturnType());
                    }
                }
        );
    }

    public static ServicesManager createMockServicesManager() {
        return (ServicesManager) Proxy.newProxyInstance(
                TestServerHarness.class.getClassLoader(),
                new Class<?>[]{ServicesManager.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("load".equals(method.getName())) {
                            return null;
                        }
                        return defaultReturnValue(method.getReturnType());
                    }
                }
        );
    }

    public static final class CapturingCommandSender {
        private final List<String> messages = new ArrayList<String>();
        private final CommandSender sender;

        public CapturingCommandSender(final String name, final boolean op, final Set<String> permissions) {
            this.sender = (CommandSender) Proxy.newProxyInstance(
                    TestServerHarness.class.getClassLoader(),
                    new Class<?>[]{CommandSender.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String m = method.getName();
                            if ("sendMessage".equals(m) && args != null && args.length > 0) {
                                if (args[0] instanceof String) {
                                    messages.add((String) args[0]);
                                } else if (args[0] instanceof String[]) {
                                    for (String s : (String[]) args[0]) {
                                        messages.add(s);
                                    }
                                }
                                return null;
                            }
                            if ("getName".equals(m)) return name;
                            if ("isOp".equals(m)) return Boolean.valueOf(op);
                            if ("hasPermission".equals(m) && args != null && args.length == 1 && args[0] instanceof String) {
                                return Boolean.valueOf(op || (permissions != null && permissions.contains(args[0])));
                            }
                            return defaultReturnValue(method.getReturnType());
                        }
                    }
            );
        }

        public CommandSender getSender() {
            return sender;
        }

        public List<String> getMessages() {
            return messages;
        }

        public void clear() {
            messages.clear();
        }
    }

    private static Object defaultReturnValue(Class<?> returnType) {
        if (returnType == void.class || returnType == Void.class) return null;
        if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
        if (returnType == int.class || returnType == Integer.class) return Integer.valueOf(0);
        if (returnType == long.class || returnType == Long.class) return Long.valueOf(0L);
        if (returnType == double.class || returnType == Double.class) return Double.valueOf(0.0);
        if (returnType == float.class || returnType == Float.class) return Float.valueOf(0.0f);
        return null;
    }
}
