package com.pratham.optimizeserver.metrics;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central registry managing the per-tick duration ring (serving the 5s, 30s, 1m, 5m, and 15m
 * windows from a single write per tick), bounded historical snapshots, and reusable primitive buffers.
 */
public final class MetricRegistry {

    public static final long WINDOW_5S_NANOS = 5L * 1_000_000_000L;
    public static final long WINDOW_30S_NANOS = 30L * 1_000_000_000L;
    public static final long WINDOW_1M_NANOS = 60L * 1_000_000_000L;
    public static final long WINDOW_5M_NANOS = 300L * 1_000_000_000L;
    public static final long WINDOW_15M_NANOS = 900L * 1_000_000_000L;

    /** 15 minutes at 20 TPS (18,000 ticks) plus headroom for post-lag catch-up ticks. */
    private static final int TICK_RING_CAPACITY = 18_432;

    private final TickDurationRing tickRing;
    private final MetricHistory history;
    private final ArrayBlockingQueue<double[]> bufferPool;
    private final AtomicBoolean bufferPoolEnabled = new AtomicBoolean(true);
    private final AtomicLong bufferReuses = new AtomicLong(0L);
    private final AtomicLong bufferAllocations = new AtomicLong(0L);

    public MetricRegistry(int maxSnapshots, int bufferPoolCapacity) {
        this.tickRing = new TickDurationRing(TICK_RING_CAPACITY);
        this.history = new MetricHistory(maxSnapshots);
        int safePoolCap = Math.max(8, bufferPoolCapacity);
        this.bufferPool = new ArrayBlockingQueue<double[]>(safePoolCap);
        for (int i = 0; i < safePoolCap / 2; i++) {
            this.bufferPool.offer(new double[256]);
        }
    }

    public void recordTickDurationMs(double mspt, long timestampNanos) {
        tickRing.record(mspt, timestampNanos);
    }

    public void setZeroAllocAggregationEnabled(boolean enabled) {
        tickRing.setReuseScratchBuffer(enabled);
    }

    public boolean isZeroAllocAggregationEnabled() {
        return tickRing.isReuseScratchBuffer();
    }

    public void setBufferPoolEnabled(boolean enabled) {
        this.bufferPoolEnabled.set(enabled);
    }

    public boolean isBufferPoolEnabled() {
        return bufferPoolEnabled.get();
    }

    public double[] borrowScratchBuffer(int minLength) {
        if (bufferPoolEnabled.get()) {
            double[] candidate = bufferPool.poll();
            if (candidate != null && candidate.length >= minLength) {
                bufferReuses.incrementAndGet();
                return candidate;
            }
        }
        bufferAllocations.incrementAndGet();
        return new double[Math.max(256, minLength)];
    }

    public void releaseScratchBuffer(double[] buffer) {
        if (buffer != null && bufferPoolEnabled.get()) {
            bufferPool.offer(buffer);
        }
    }

    public long getBufferReuses() {
        return bufferReuses.get();
    }

    public long getBufferAllocations() {
        return bufferAllocations.get();
    }

    public TickDurationRing getTickRing() {
        return tickRing;
    }

    public MetricHistory getHistory() {
        return history;
    }
}

