package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.metrics.MetricHistory;
import com.pratham.optimizeserver.metrics.MetricSnapshot;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Captures individual server ticks that exceed a duration threshold, together with the world, player,
 * heap, and garbage-collection context at that moment, so admins can see what each lag spike coincided with.
 *
 * <p>The per-tick cost is one comparison. Context is only gathered when a tick actually exceeds the
 * threshold, and the history is a small bounded ring. Purely observational: nothing is changed in response.</p>
 */
public final class LagSpikeRecorder {

    /**
     * Callback fired (on the tick thread) after a spike is recorded. Implementations must stay cheap.
     */
    public interface SpikeListener {
        void onSpike(LagSpike spike);
    }

    /**
     * Immutable record of one slow tick.
     */
    public static final class LagSpike {
        private final long timestampMillis;
        private final double durationMs;
        private final long gcMsSinceLastSample;
        private final int loadedChunks;
        private final int totalEntities;
        private final int onlinePlayers;
        private final double heapUsagePercent;
        private final double chunkGenRatePerSec;

        public LagSpike(long timestampMillis,
                        double durationMs,
                        long gcMsSinceLastSample,
                        int loadedChunks,
                        int totalEntities,
                        int onlinePlayers,
                        double heapUsagePercent,
                        double chunkGenRatePerSec) {
            this.timestampMillis = timestampMillis;
            this.durationMs = durationMs;
            this.gcMsSinceLastSample = gcMsSinceLastSample;
            this.loadedChunks = loadedChunks;
            this.totalEntities = totalEntities;
            this.onlinePlayers = onlinePlayers;
            this.heapUsagePercent = heapUsagePercent;
            this.chunkGenRatePerSec = chunkGenRatePerSec;
        }

        public long getTimestampMillis() { return timestampMillis; }
        public double getDurationMs() { return durationMs; }
        /** Garbage-collection pause time accumulated since the last telemetry sample (at most ~1 s earlier). */
        public long getGcMsSinceLastSample() { return gcMsSinceLastSample; }
        public int getLoadedChunks() { return loadedChunks; }
        public int getTotalEntities() { return totalEntities; }
        public int getOnlinePlayers() { return onlinePlayers; }
        public double getHeapUsagePercent() { return heapUsagePercent; }
        public double getChunkGenRatePerSec() { return chunkGenRatePerSec; }

        /**
         * Best-effort attribution from the captured context.
         */
        public String getLikelyCause() {
            if (durationMs > 0.0 && gcMsSinceLastSample >= Math.max(20.0, durationMs * 0.5)) {
                return "Garbage collection pause";
            }
            if (chunkGenRatePerSec >= 8.0) {
                return "World generation (new chunks)";
            }
            if (heapUsagePercent >= 90.0) {
                return "Heap pressure";
            }
            return "Unattributed (run /os profile start to capture a Spark profile)";
        }
    }

    private final MetricHistory history;
    private final List<GarbageCollectorMXBean> gcBeans;

    private volatile boolean enabled = true;
    private volatile double thresholdMs = 100.0;
    private volatile SpikeListener listener;

    private final Object lock = new Object();
    private LagSpike[] ring;
    private int head = 0;
    private int size = 0;
    private long totalSpikes = 0L;
    private double worstMs = 0.0;

    public LagSpikeRecorder(MetricHistory history, int capacity) {
        this.history = history;
        this.ring = new LagSpike[Math.max(5, capacity)];
        List<GarbageCollectorMXBean> beans;
        try {
            beans = ManagementFactory.getGarbageCollectorMXBeans();
        } catch (Throwable t) {
            beans = Collections.emptyList();
        }
        this.gcBeans = beans;
    }

    /**
     * Applies settings; thresholds below 55 ms are raised to 55 ms so normal ticks never count as spikes.
     */
    public void configure(boolean enabled, double thresholdMs, int capacity) {
        this.enabled = enabled;
        this.thresholdMs = Math.max(55.0, thresholdMs);
        synchronized (lock) {
            int safeCap = Math.max(5, capacity);
            if (safeCap != ring.length) {
                List<LagSpike> keep = recentLocked(safeCap);
                ring = new LagSpike[safeCap];
                head = 0;
                size = 0;
                for (int i = keep.size() - 1; i >= 0; i--) {
                    appendLocked(keep.get(i));
                }
            }
        }
    }

    public void setListener(SpikeListener listener) {
        this.listener = listener;
    }

    /**
     * Called once per recorded tick. Returns the captured spike, or {@code null} for a normal tick.
     */
    public LagSpike onTick(double durationMs) {
        if (!enabled || durationMs < thresholdMs) {
            return null;
        }
        MetricSnapshot latest = history != null ? history.getLatest() : null;
        long gcSinceSample = 0L;
        if (latest != null) {
            gcSinceSample = Math.max(0L, readGcTotalTimeMs() - latest.getGcTotalTimeMs());
        }
        LagSpike spike = new LagSpike(
                System.currentTimeMillis(),
                durationMs,
                gcSinceSample,
                latest != null ? latest.getLoadedChunks() : 0,
                latest != null ? latest.getTotalEntities() : 0,
                latest != null ? latest.getOnlinePlayers() : 0,
                latest != null ? latest.getHeapUsagePercent() : 0.0,
                latest != null ? latest.getChunkGenRatePerSec() : 0.0
        );
        synchronized (lock) {
            appendLocked(spike);
            totalSpikes++;
            if (durationMs > worstMs) {
                worstMs = durationMs;
            }
        }
        SpikeListener l = this.listener;
        if (l != null) {
            try {
                l.onSpike(spike);
            } catch (Throwable ignored) {
            }
        }
        return spike;
    }

    private long readGcTotalTimeMs() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : gcBeans) {
            try {
                long t = bean.getCollectionTime();
                if (t > 0L) {
                    total += t;
                }
            } catch (Throwable ignored) {
            }
        }
        return total;
    }

    private void appendLocked(LagSpike spike) {
        ring[head] = spike;
        head = (head + 1) % ring.length;
        if (size < ring.length) {
            size++;
        }
    }

    private List<LagSpike> recentLocked(int max) {
        int count = Math.min(size, Math.max(0, max));
        List<LagSpike> out = new ArrayList<LagSpike>(count);
        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i + ring.length) % ring.length;
            out.add(ring[idx]);
        }
        return out;
    }

    /**
     * Most recent spikes first.
     */
    public List<LagSpike> getRecentSpikes(int max) {
        synchronized (lock) {
            return Collections.unmodifiableList(recentLocked(max));
        }
    }

    /**
     * Number of retained spikes captured at or after {@code sinceMillis}.
     */
    public int countSince(long sinceMillis) {
        synchronized (lock) {
            int n = 0;
            for (LagSpike s : recentLocked(size)) {
                if (s.getTimestampMillis() >= sinceMillis) {
                    n++;
                }
            }
            return n;
        }
    }

    public long getTotalSpikes() {
        synchronized (lock) {
            return totalSpikes;
        }
    }

    public double getWorstMs() {
        synchronized (lock) {
            return worstMs;
        }
    }

    public double getThresholdMs() {
        return thresholdMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void clear() {
        synchronized (lock) {
            ring = new LagSpike[ring.length];
            head = 0;
            size = 0;
            totalSpikes = 0L;
            worstMs = 0.0;
        }
    }
}
