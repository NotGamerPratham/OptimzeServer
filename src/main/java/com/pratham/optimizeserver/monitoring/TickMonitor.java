package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.compatibility.PlatformCapabilities;
import com.pratham.optimizeserver.metrics.MetricRegistry;
import com.pratham.optimizeserver.metrics.RollingWindow;
import org.bukkit.Server;

import java.lang.reflect.Method;

/**
 * Monitors server tick durations (MSPT) and Ticks Per Second (TPS) across 5s, 30s, 1m, 5m, and 15m rolling windows.
 * Uses monotonic time (System.nanoTime()) and integrates with Paper's native tick time arrays when available.
 */
public final class TickMonitor {

    private static final long IDEAL_TICK_NANOS = 50_000_000L; // 50ms

    private final Server server;
    private final MetricRegistry metricRegistry;
    private final boolean hasPaperTps;
    private final boolean hasPaperMspt;
    private final boolean hasPaperTickTimes;

    private Method getTpsMethod;
    private Method getAverageTickTimeMethod;
    private Method getTickTimesMethod;

    private long lastTickNanos = 0L;
    private volatile double latestTps5s = 20.0;
    private volatile double latestTps30s = 20.0;
    private volatile double latestTps1m = 20.0;
    private volatile double latestTps5m = 20.0;
    private volatile double latestTps15m = 20.0;
    private volatile RollingWindow.WindowStats latestMsptStats = RollingWindow.WindowStats.EMPTY;

    public TickMonitor(Server server, PlatformCapabilities capabilities, MetricRegistry metricRegistry) {
        this.server = server;
        this.metricRegistry = metricRegistry;
        this.hasPaperTps = capabilities != null && capabilities.hasPaperTpsApi();
        this.hasPaperMspt = capabilities != null && capabilities.hasPaperMsptApi();
        this.hasPaperTickTimes = capabilities != null && capabilities.hasPaperTickTimesApi();
        initReflection();
    }

    private void initReflection() {
        if (server == null) {
            return;
        }
        Class<?> sc = server.getClass();
        if (hasPaperTps) {
            try {
                getTpsMethod = sc.getMethod("getTPS");
            } catch (Throwable ignored) {
            }
        }
        if (hasPaperMspt) {
            try {
                getAverageTickTimeMethod = sc.getMethod("getAverageTickTime");
            } catch (Throwable ignored) {
            }
        }
        if (hasPaperTickTimes) {
            try {
                getTickTimesMethod = sc.getMethod("getTickTimes");
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Invoked on every server tick (or configured tick interval) to record monotonic tick duration.
     */
    public void onServerTick(int intervalTicks) {
        long nowNanos = System.nanoTime();
        if (lastTickNanos > 0L) {
            long elapsedNanos = Math.max(0L, nowNanos - lastTickNanos);
            int safeInterval = Math.max(1, intervalTicks);
            double intervalMsPerTick = (elapsedNanos / 1_000_000.0) / safeInterval;

            // If Paper provides actual work MSPT via getAverageTickTime or getTickTimes, prefer actual work duration
            double measuredMspt = readNativeTickDurationMs(intervalMsPerTick);
            metricRegistry.recordTickDurationMs(measuredMspt, nowNanos);
        }
        lastTickNanos = nowNanos;
    }

    /**
     * Records an explicit tick duration in milliseconds (useful for deterministic testing and profiling hooks).
     */
    public void recordExplicitTickMs(double mspt, long timestampNanos) {
        metricRegistry.recordTickDurationMs(Math.max(0.0, mspt), timestampNanos);
        refreshAggregates(timestampNanos);
    }

    /**
     * Recomputes rolling window statistics for 5s, 30s, 1m, 5m, and 15m windows.
     */
    public void refreshAggregates(long nowNanos) {
        RollingWindow.WindowStats stats5s = metricRegistry.getMspt5s().computeStats(nowNanos);
        RollingWindow.WindowStats stats30s = metricRegistry.getMspt30s().computeStats(nowNanos);
        RollingWindow.WindowStats stats1m = metricRegistry.getMspt1m().computeStats(nowNanos);
        RollingWindow.WindowStats stats5m = metricRegistry.getMspt5m().computeStats(nowNanos);
        RollingWindow.WindowStats stats15m = metricRegistry.getMspt15m().computeStats(nowNanos);

        this.latestMsptStats = stats1m.getSampleCount() > 0 ? stats1m : stats5s;

        this.latestTps5s = msptToTps(stats5s);
        this.latestTps30s = msptToTps(stats30s);
        this.latestTps1m = msptToTps(stats1m);
        this.latestTps5m = msptToTps(stats5m);
        this.latestTps15m = msptToTps(stats15m);

        // Cross-check 1m, 5m, 15m with native Paper TPS array if available and rolling window is still warming up
        if (getTpsMethod != null && server != null) {
            try {
                Object raw = getTpsMethod.invoke(server);
                if (raw instanceof double[]) {
                    double[] nativeTps = (double[]) raw;
                    if (nativeTps.length >= 3) {
                        if (stats1m.getSampleCount() < 100) {
                            this.latestTps1m = clampTps(nativeTps[0]);
                        }
                        if (stats5m.getSampleCount() < 300) {
                            this.latestTps5m = clampTps(nativeTps[1]);
                        }
                        if (stats15m.getSampleCount() < 600) {
                            this.latestTps15m = clampTps(nativeTps[2]);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private double readNativeTickDurationMs(double fallbackIntervalMs) {
        if (getTickTimesMethod != null && server != null) {
            try {
                Object raw = getTickTimesMethod.invoke(server);
                if (raw instanceof long[]) {
                    long[] tickTimes = (long[]) raw;
                    if (tickTimes.length > 0) {
                        long sum = 0L;
                        int count = 0;
                        for (int i = 0; i < Math.min(20, tickTimes.length); i++) {
                            if (tickTimes[i] > 0L) {
                                sum += tickTimes[i];
                                count++;
                            }
                        }
                        if (count > 0) {
                            return (sum / (double) count) / 1_000_000.0;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (getAverageTickTimeMethod != null && server != null) {
            try {
                Object raw = getAverageTickTimeMethod.invoke(server);
                if (raw instanceof Number) {
                    double nativeMspt = ((Number) raw).doubleValue();
                    if (nativeMspt > 0.0) {
                        return nativeMspt;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // Fallback: if interval is close to 50ms, preserve measured interval; subtracted sleep yields work estimate
        return Math.max(0.1, fallbackIntervalMs);
    }

    private static double msptToTps(RollingWindow.WindowStats stats) {
        if (stats == null || stats.getSampleCount() == 0) {
            return 20.0;
        }
        double avgMspt = stats.getAverage();
        if (avgMspt <= 50.0) {
            return 20.0;
        }
        return clampTps(1000.0 / avgMspt);
    }

    private static double clampTps(double tps) {
        if (Double.isNaN(tps) || Double.isInfinite(tps)) {
            return 20.0;
        }
        return Math.max(0.0, Math.min(20.0, tps));
    }

    public double getTps5s() {
        return latestTps5s;
    }

    public double getTps30s() {
        return latestTps30s;
    }

    public double getTps1m() {
        return latestTps1m;
    }

    public double getTps5m() {
        return latestTps5m;
    }

    public double getTps15m() {
        return latestTps15m;
    }

    public RollingWindow.WindowStats getLatestMsptStats() {
        return latestMsptStats;
    }
}

