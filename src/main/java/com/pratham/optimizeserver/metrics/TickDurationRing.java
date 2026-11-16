package com.pratham.optimizeserver.metrics;

import java.util.Arrays;

/**
 * Single bounded ring of per-tick durations that serves every rolling MSPT/TPS window
 * (5s, 30s, 1m, 5m, 15m) from one write per tick.
 *
 * <p>Each slot stores the record timestamp and an exclusive prefix sum of all durations
 * (integer nanoseconds, so sums are exact). Window averages are answered in O(log n) via a
 * binary search on the monotonic timestamps plus one prefix-sum subtraction. Percentile stats
 * copy only the window's slice under the lock and sort it outside, so the writer (the server
 * tick thread) never waits behind a sort.</p>
 */
public final class TickDurationRing {

    private final int capacity;
    private final long[] timestampsNanos;
    private final long[] prefixBeforeNanos;
    private final Object statsLock = new Object();

    private long writeCount = 0L;
    private long totalNanos = 0L;
    private long lastTimestampNanos = Long.MIN_VALUE;

    private boolean reuseScratch = true;
    private long[] prefixScratch = new long[0];
    private double[] valueScratch = new double[0];

    public TickDurationRing(int capacity) {
        this.capacity = Math.max(16, capacity);
        this.timestampsNanos = new long[this.capacity];
        this.prefixBeforeNanos = new long[this.capacity];
    }

    /**
     * Records one tick duration. Timestamps are clamped to be non-decreasing so window
     * boundaries stay searchable even if a seeding sample races with the tick thread.
     */
    public synchronized void record(double durationMs, long timestampNanos) {
        long durationNanos = Math.max(0L, Math.round(Math.max(0.0, durationMs) * 1_000_000.0));
        long ts = timestampNanos < lastTimestampNanos ? lastTimestampNanos : timestampNanos;
        int idx = (int) (writeCount % capacity);
        timestampsNanos[idx] = ts;
        prefixBeforeNanos[idx] = totalNanos;
        totalNanos += durationNanos;
        lastTimestampNanos = ts;
        writeCount++;
    }

    public synchronized void clear() {
        writeCount = 0L;
        totalNanos = 0L;
        lastTimestampNanos = Long.MIN_VALUE;
    }

    public synchronized long getWriteCount() {
        return writeCount;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setReuseScratchBuffer(boolean reuse) {
        synchronized (statsLock) {
            this.reuseScratch = reuse;
            if (!reuse) {
                prefixScratch = new long[0];
                valueScratch = new double[0];
            }
        }
    }

    public boolean isReuseScratchBuffer() {
        synchronized (statsLock) {
            return reuseScratch;
        }
    }

    /**
     * Mean duration of the ticks recorded within {@code windowNanos} of {@code nowNanos}, in O(log n).
     */
    public RollingWindow.WindowAverage computeAverage(long windowNanos, long nowNanos) {
        long count;
        long sumNanos;
        synchronized (this) {
            long first = firstSeqWithin(nowNanos - windowNanos);
            if (first < 0L) {
                return RollingWindow.WindowAverage.EMPTY;
            }
            count = writeCount - first;
            sumNanos = totalNanos - prefixBeforeNanos[(int) (first % capacity)];
        }
        return new RollingWindow.WindowAverage((int) count, (sumNanos / (double) count) / 1_000_000.0);
    }

    /**
     * Full statistics (mean, min, max, std-dev, P50/P95/P99) for the ticks within the window.
     */
    public RollingWindow.WindowStats computeStats(long windowNanos, long nowNanos) {
        synchronized (statsLock) {
            int n;
            long[] prefix;
            synchronized (this) {
                long first = firstSeqWithin(nowNanos - windowNanos);
                if (first < 0L) {
                    return RollingWindow.WindowStats.EMPTY;
                }
                n = (int) (writeCount - first);
                prefix = prefixBuffer(n + 1);
                int start = (int) (first % capacity);
                int firstPart = Math.min(n, capacity - start);
                System.arraycopy(prefixBeforeNanos, start, prefix, 0, firstPart);
                if (firstPart < n) {
                    System.arraycopy(prefixBeforeNanos, 0, prefix, firstPart, n - firstPart);
                }
                prefix[n] = totalNanos;
            }

            double[] values = valueBuffer(n);
            double sum = 0.0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                double v = (prefix[i + 1] - prefix[i]) / 1_000_000.0;
                values[i] = v;
                sum += v;
                if (v < min) min = v;
                if (v > max) max = v;
            }
            double avg = sum / n;
            double varianceSum = 0.0;
            for (int i = 0; i < n; i++) {
                double diff = values[i] - avg;
                varianceSum += diff * diff;
            }
            Arrays.sort(values, 0, n);
            return new RollingWindow.WindowStats(
                    n,
                    avg,
                    min,
                    max,
                    Math.sqrt(varianceSum / n),
                    percentileFromSorted(values, n, 0.50),
                    percentileFromSorted(values, n, 0.95),
                    percentileFromSorted(values, n, 0.99)
            );
        }
    }

    /**
     * Returns the sequence number of the oldest retained sample with timestamp >= cutoff, or -1.
     * Caller must hold the instance lock.
     */
    private long firstSeqWithin(long cutoffNanos) {
        if (writeCount == 0L) {
            return -1L;
        }
        long lo = Math.max(0L, writeCount - capacity);
        long hi = writeCount - 1L;
        if (timestampsNanos[(int) (hi % capacity)] < cutoffNanos) {
            return -1L;
        }
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            if (timestampsNanos[(int) (mid % capacity)] >= cutoffNanos) {
                hi = mid;
            } else {
                lo = mid + 1L;
            }
        }
        return lo;
    }

    private long[] prefixBuffer(int length) {
        if (!reuseScratch) {
            return new long[length];
        }
        if (prefixScratch.length < length) {
            prefixScratch = new long[Math.max(length, prefixScratch.length * 2)];
        }
        return prefixScratch;
    }

    private double[] valueBuffer(int length) {
        if (!reuseScratch) {
            return new double[length];
        }
        if (valueScratch.length < length) {
            valueScratch = new double[Math.max(length, valueScratch.length * 2)];
        }
        return valueScratch;
    }

    private static double percentileFromSorted(double[] sorted, int length, double quantile) {
        if (length <= 0) {
            return 0.0;
        }
        if (length == 1) {
            return sorted[0];
        }
        double pos = quantile * (length - 1);
        int lower = (int) Math.floor(pos);
        int upper = (int) Math.ceil(pos);
        if (lower == upper) {
            return sorted[lower];
        }
        double weight = pos - lower;
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight;
    }
}
