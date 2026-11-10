package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.compatibility.PlatformCapabilities;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors player connection statistics, network RTT latency, throughput indicators,
 * and separates physical network round-trip latency from server tick-delay and packet-processing overhead.
 * Does not perform invasive Netty pipeline manipulation or claim physical ping reduction.
 */
public final class NetworkMonitor {

    private final Server server;
    private final boolean hasPlayerGetPing;
    private final boolean hasSpigotPing;
    private Method playerGetPingMethod;
    private Method spigotGetPingMethod;

    private final AtomicLong incomingEventCounter = new AtomicLong(0L);
    private final AtomicLong outgoingEventCounter = new AtomicLong(0L);
    private final AtomicLong cumulativePacketOverheadNanos = new AtomicLong(0L);

    private long lastSampleNanos = 0L;
    private long lastIncomingCount = 0L;
    private long lastOutgoingCount = 0L;
    private long lastOverheadNanos = 0L;

    private volatile int onlinePlayers = 0;
    private volatile double averagePingMs = 0.0;
    private volatile double maxPingMs = 0.0;
    private volatile double incomingEventsPerSec = 0.0;
    private volatile double outgoingEventsPerSec = 0.0;
    private volatile double packetProcessingOverheadMs = 0.0;

    public NetworkMonitor(Server server, PlatformCapabilities capabilities) {
        this.server = server;
        this.hasPlayerGetPing = capabilities != null && capabilities.hasPlayerGetPingApi();
        this.hasSpigotPing = capabilities != null && capabilities.hasSpigotPingApi();
        initReflection();
    }

    private void initReflection() {
        if (hasPlayerGetPing) {
            try {
                playerGetPingMethod = Player.class.getMethod("getPing");
            } catch (Throwable ignored) {
            }
        }
        if (hasSpigotPing) {
            try {
                Class<?> spigotClass = Class.forName("org.bukkit.entity.Player$Spigot");
                spigotGetPingMethod = spigotClass.getMethod("getPing");
            } catch (Throwable ignored) {
            }
        }
    }

    public void recordIncomingTrafficEvent(long processingDurationNanos) {
        incomingEventCounter.incrementAndGet();
        if (processingDurationNanos > 0L) {
            cumulativePacketOverheadNanos.addAndGet(processingDurationNanos);
        }
    }

    public void recordOutgoingTrafficEvent(long processingDurationNanos) {
        outgoingEventCounter.incrementAndGet();
        if (processingDurationNanos > 0L) {
            cumulativePacketOverheadNanos.addAndGet(processingDurationNanos);
        }
    }

    /**
     * Samples online player count and ping statistics on the main/global thread.
     */
    public void sampleMainThread(long nowNanos) {
        if (server != null) {
            try {
                Collection<? extends Player> players = server.getOnlinePlayers();
                int count = players != null ? players.size() : 0;
                this.onlinePlayers = count;
                if (count > 0 && players != null) {
                    double sumPing = 0.0;
                    double maxPing = 0.0;
                    int measured = 0;
                    for (Player p : players) {
                        if (p != null) {
                            int ping = readPlayerPing(p);
                            if (ping >= 0) {
                                sumPing += ping;
                                if (ping > maxPing) {
                                    maxPing = ping;
                                }
                                measured++;
                            }
                        }
                    }
                    this.averagePingMs = measured > 0 ? (sumPing / measured) : 0.0;
                    this.maxPingMs = maxPing;
                } else {
                    this.averagePingMs = 0.0;
                    this.maxPingMs = 0.0;
                }
            } catch (Throwable ignored) {
            }
        }

        long curIn = incomingEventCounter.get();
        long curOut = outgoingEventCounter.get();
        long curOverhead = cumulativePacketOverheadNanos.get();

        if (lastSampleNanos > 0L && nowNanos > lastSampleNanos) {
            double elapsedSec = (nowNanos - lastSampleNanos) / 1_000_000_000.0;
            if (elapsedSec > 0.05) {
                this.incomingEventsPerSec = Math.max(0L, curIn - lastIncomingCount) / elapsedSec;
                this.outgoingEventsPerSec = Math.max(0L, curOut - lastOutgoingCount) / elapsedSec;
                double deltaOverheadMs = Math.max(0L, curOverhead - lastOverheadNanos) / 1_000_000.0;
                this.packetProcessingOverheadMs = deltaOverheadMs / elapsedSec;
            }
        }

        this.lastSampleNanos = nowNanos;
        this.lastIncomingCount = curIn;
        this.lastOutgoingCount = curOut;
        this.lastOverheadNanos = curOverhead;
    }

    private int readPlayerPing(Player player) {
        if (playerGetPingMethod != null) {
            try {
                Object val = playerGetPingMethod.invoke(player);
                if (val instanceof Number) {
                    return Math.max(0, ((Number) val).intValue());
                }
            } catch (Throwable ignored) {
            }
        }
        if (spigotGetPingMethod != null) {
            try {
                Object spigot = player.spigot();
                if (spigot != null) {
                    Object val = spigotGetPingMethod.invoke(spigot);
                    if (val instanceof Number) {
                        return Math.max(0, ((Number) val).intValue());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // Reflective fallback for legacy CraftBukkit / Spigot 1.8.8 - 1.16.5
        try {
            Method getHandle = player.getClass().getMethod("getHandle");
            Object entityPlayer = getHandle.invoke(player);
            if (entityPlayer != null) {
                Field pingField = entityPlayer.getClass().getField("ping");
                Object val = pingField.get(entityPlayer);
                if (val instanceof Number) {
                    return Math.max(0, ((Number) val).intValue());
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * Estimates server-induced tick queuing delay in milliseconds given current average MSPT.
     */
    public double estimateTickInducedDelayMs(double averageMspt) {
        return Math.max(0.0, averageMspt - 50.0);
    }

    public int getOnlinePlayers() {
        return onlinePlayers;
    }

    public double getAveragePingMs() {
        return averagePingMs;
    }

    public double getMaxPingMs() {
        return maxPingMs;
    }

    public double getIncomingEventsPerSec() {
        return incomingEventsPerSec;
    }

    public double getOutgoingEventsPerSec() {
        return outgoingEventsPerSec;
    }

    public double getPacketProcessingOverheadMs() {
        return packetProcessingOverheadMs;
    }
}
