package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.compatibility.PlatformCapabilities;
import com.pratham.optimizeserver.metrics.MetricRegistry;
import com.pratham.optimizeserver.metrics.RollingWindow;
import com.pratham.optimizeserver.metrics.TickDurationRing;
import org.bukkit.Server;

import java.lang.reflect.Method;

/**
 * Monitors server tick durations (MSPT) and Ticks Per Second (TPS) across 5s, 30s, 1m, 5m, and 15m rolling windows.
 *
 * <p>Measurement sources, best first:</p>
 * <ol>
 *     <li>Paper's {@code ServerTickEndEvent} (see {@link PaperTickEventHook}): the server's own exact
 *     per-tick work duration, delivered once per tick with no scheduler task, reflection array copy, or allocation.</li>
 *     <li>A per-tick scheduler task reading Paper's {@code getAverageTickTime()} / {@code getTickTimes()}.</li>
 *     <li>A monotonic {@code System.nanoTime()} tick-interval measurement (Spigot/CraftBukkit).</li>
 * </ol>
 * All durations land in one {@link TickDurationRing}, so every window is served from a single write per tick.
 */
public final class TickMonitor {

    private final Server server;
    private final MetricRegistry metricRegistry;
    private final boolean hasPaperTps;
    private final boolean hasPaperMspt;
    private final boolean hasPaperTickTimes;

    private volatile Method getTpsMethod;
    private volatile Method getAverageTickTimeMethod;
    private volatile Method getTickTimesMethod;

    private long lastTickNanos = 0L;
    private volatile long tickEventCount = 0L;
    private volatile double latestTps5s = 20.0;
    private volatile double latestTps30s = 20.0;
    private volatile double latestTps1m = 20.0;
    private volatile double latestTps5m = 20.0;
    private volatile double latestTps15m = 20.0;
    private volatile RollingWindow.WindowStats latestMsptStats = RollingWindow.WindowStats.EMPTY;
    private volatile LagSpikeRecorder spikeRecorder;

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
     * Records the exact work duration of one completed server tick, as reported by Paper's
     * {@code ServerTickEndEvent}. Called on the server tick thread once per tick.
     */
    public void recordTickEndEvent(double tickDurationMs, long nowNanos) {
        metricRegistry.recordTickDurationMs(Math.max(0.0, tickDurationMs), nowNanos);
        tickEventCount++;
        lastTickNanos = nowNanos;
        LagSpikeRecorder recorder = this.spikeRecorder;
        if (recorder != null) {
            recorder.onTick(tickDurationMs);
        }
    }

    /**
     * Attaches the recorder that inspects every measured tick for lag spikes (may be {@code null}).
     */
    public void setSpikeRecorder(LagSpikeRecorder spikeRecorder) {
        this.spikeRecorder = spikeRecorder;
    }

    /**
     * Number of ticks delivered through {@link #recordTickEndEvent(double, long)}.
     */
    public long getTickEventCount() {
        return tickEventCount;
    }

    /**
     * Fallback path, invoked from a scheduler task on every server tick (or configured tick interval)
     * when no native tick-end event is available.
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
            // Native averages smooth spikes away; the wall-clock gap between ticks shows a stall directly
            LagSpikeRecorder recorder = this.spikeRecorder;
            if (recorder != null) {
                recorder.onTick(intervalMsPerTick);
            }
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
     * Averages are O(log n); only the 1m window is sorted for percentiles.
     */
    public void refreshAggregates(long nowNanos) {
        TickDurationRing ring = metricRegistry.getTickRing();
        RollingWindow.WindowAverage avg5s = ring.computeAverage(MetricRegistry.WINDOW_5S_NANOS, nowNanos);
        if (avg5s.getSampleCount() == 0 && (hasPaperTickTimes || hasPaperMspt)) {
            double seededMspt = readNativeTickDurationMs(-1.0);
            if (seededMspt > 0.0) {
                metricRegistry.recordTickDurationMs(seededMspt, nowNanos);
                avg5s = ring.computeAverage(MetricRegistry.WINDOW_5S_NANOS, nowNanos);
            }
        }
        RollingWindow.WindowAverage avg30s = ring.computeAverage(MetricRegistry.WINDOW_30S_NANOS, nowNanos);
        RollingWindow.WindowStats stats1m = ring.computeStats(MetricRegistry.WINDOW_1M_NANOS, nowNanos);
        RollingWindow.WindowAverage avg5m = ring.computeAverage(MetricRegistry.WINDOW_5M_NANOS, nowNanos);
        RollingWindow.WindowAverage avg15m = ring.computeAverage(MetricRegistry.WINDOW_15M_NANOS, nowNanos);

        this.latestMsptStats = stats1m;

        this.latestTps5s = msptToTps(avg5s.getSampleCount(), avg5s.getAverage());
        this.latestTps30s = msptToTps(avg30s.getSampleCount(), avg30s.getAverage());
        this.latestTps1m = msptToTps(stats1m.getSampleCount(), stats1m.getAverage());
        this.latestTps5m = msptToTps(avg5m.getSampleCount(), avg5m.getAverage());
        this.latestTps15m = msptToTps(avg15m.getSampleCount(), avg15m.getAverage());

        // Cross-check 1m, 5m, 15m with native Paper TPS array if available and rolling window is still warming up
        Method tpsMethod = this.getTpsMethod;
        if (tpsMethod != null && server != null) {
            try {
                Object raw = tpsMethod.invoke(server);
                if (raw instanceof double[]) {
                    double[] nativeTps = (double[]) raw;
                    if (nativeTps.length >= 3) {
                        if (stats1m.getSampleCount() < 100) {
                            this.latestTps1m = clampTps(nativeTps[0]);
                        }
                        if (avg5m.getSampleCount() < 300) {
                            this.latestTps5m = clampTps(nativeTps[1]);
                        }
                        if (avg15m.getSampleCount() < 600) {
                            this.latestTps15m = clampTps(nativeTps[2]);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Unsupported on this fork (e.g. some region-threaded builds): stop paying for the exception
                this.getTpsMethod = null;
            }
        }
    }

    private double readNativeTickDurationMs(double fallbackIntervalMs) {
        // getAverageTickTime() returns a primitive with no array copy, so it is preferred over getTickTimes()
        Method avgMethod = this.getAverageTickTimeMethod;
        if (avgMethod != null && server != null) {
            try {
                Object raw = avgMethod.invoke(server);
                if (raw instanceof Number) {
                    double nativeMspt = ((Number) raw).doubleValue();
                    if (nativeMspt > 0.0) {
                        return nativeMspt;
                    }
                }
            } catch (Throwable ignored) {
                this.getAverageTickTimeMethod = null;
            }
        }
        Method timesMethod = this.getTickTimesMethod;
        if (timesMethod != null && server != null) {
            try {
                Object raw = timesMethod.invoke(server);
                if (raw instanceof long[]) {
                    long[] tickTimes = (long[]) raw;
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
            } catch (Throwable ignored) {
                this.getTickTimesMethod = null;
            }
        }
        // Fallback: if interval is close to 50ms, preserve measured interval; subtracted sleep yields work estimate
        return Math.max(0.1, fallbackIntervalMs);
    }

    private static double msptToTps(int sampleCount, double avgMspt) {
        if (sampleCount == 0 || avgMspt <= 50.0) {
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
