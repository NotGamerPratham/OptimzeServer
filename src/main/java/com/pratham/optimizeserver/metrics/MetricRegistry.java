package com.pratham.optimizeserver.metrics;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central registry managing rolling measurement windows (5s, 30s, 1m, 5m, 15m),
 * bounded historical snapshots, and reusable primitive buffers.
 */
public final class MetricRegistry {

    private final RollingWindow mspt5s;
    private final RollingWindow mspt30s;
    private final RollingWindow mspt1m;
    private final RollingWindow mspt5m;
    private final RollingWindow mspt15m;
    private final MetricHistory history;
    private final ArrayBlockingQueue<double[]> bufferPool;
    private final AtomicBoolean bufferPoolEnabled = new AtomicBoolean(true);
    private final AtomicLong bufferReuses = new AtomicLong(0L);
    private final AtomicLong bufferAllocations = new AtomicLong(0L);

    public MetricRegistry(int maxSnapshots, int bufferPoolCapacity) {
        this.mspt5s = new RollingWindow(5L, 200);
        this.mspt30s = new RollingWindow(30L, 800);
        this.mspt1m = new RollingWindow(60L, 1400);
        this.mspt5m = new RollingWindow(300L, 6200);
        this.mspt15m = new RollingWindow(900L, 18200);
        this.history = new MetricHistory(maxSnapshots);
        int safePoolCap = Math.max(8, bufferPoolCapacity);
        this.bufferPool = new ArrayBlockingQueue<double[]>(safePoolCap);
        for (int i = 0; i < safePoolCap / 2; i++) {
            this.bufferPool.offer(new double[256]);
        }
    }

    public void recordTickDurationMs(double mspt, long timestampNanos) {
        mspt5s.record(mspt, timestampNanos);
        mspt30s.record(mspt, timestampNanos);
        mspt1m.record(mspt, timestampNanos);
        mspt5m.record(mspt, timestampNanos);
        mspt15m.record(mspt, timestampNanos);
    }

    public void setZeroAllocAggregationEnabled(boolean enabled) {
        mspt5s.setReuseScratchBuffer(enabled);
        mspt30s.setReuseScratchBuffer(enabled);
        mspt1m.setReuseScratchBuffer(enabled);
        mspt5m.setReuseScratchBuffer(enabled);
        mspt15m.setReuseScratchBuffer(enabled);
    }

    public boolean isZeroAllocAggregationEnabled() {
        return mspt1m.isReuseScratchBuffer();
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

    public RollingWindow getMspt5s() {
        return mspt5s;
    }

    public RollingWindow getMspt30s() {
        return mspt30s;
    }

    public RollingWindow getMspt1m() {
        return mspt1m;
    }

    public RollingWindow getMspt5m() {
        return mspt5m;
    }

    public RollingWindow getMspt15m() {
        return mspt15m;
    }

    public MetricHistory getHistory() {
        return history;
    }
}
