package com.pratham.optimizeserver.metrics;

import java.util.Arrays;

/**
 * Thread-safe, bounded circular rolling window backed by primitive arrays.
 * Uses monotonic time (System.nanoTime()) for accurate duration filtering and percentile computation.
 */
public final class RollingWindow {

    private final long windowDurationNanos;
    private final int capacity;
    private final long[] timestampsNanos;
    private final double[] values;
    private final double[] scratchBuffer;
    private int head = 0;
    private int size = 0;
    private boolean reuseScratchBuffer = true;

    public RollingWindow(long windowSeconds, int capacity) {
        this.windowDurationNanos = Math.max(1L, windowSeconds) * 1_000_000_000L;
        this.capacity = Math.max(16, capacity);
        this.timestampsNanos = new long[this.capacity];
        this.values = new double[this.capacity];
        this.scratchBuffer = new double[this.capacity];
    }

    public synchronized void record(double value, long timestampNanos) {
        timestampsNanos[head] = timestampNanos;
        values[head] = value;
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
    }

    public synchronized void setReuseScratchBuffer(boolean reuseScratchBuffer) {
        this.reuseScratchBuffer = reuseScratchBuffer;
    }

    public synchronized boolean isReuseScratchBuffer() {
        return reuseScratchBuffer;
    }

    public synchronized WindowStats computeStats(long nowNanos) {
        if (size == 0) {
            return WindowStats.EMPTY;
        }
        long cutoff = nowNanos - windowDurationNanos;
        double[] buf = reuseScratchBuffer ? scratchBuffer : new double[size];
        int validCount = 0;
        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        int startIdx = (head - size + capacity) % capacity;
        for (int i = 0; i < size; i++) {
            int idx = (startIdx + i) % capacity;
            if (timestampsNanos[idx] >= cutoff) {
                double v = values[idx];
                buf[validCount++] = v;
                sum += v;
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }

        if (validCount == 0) {
            return WindowStats.EMPTY;
        }

        double avg = sum / validCount;
        double varianceSum = 0.0;
        for (int i = 0; i < validCount; i++) {
            double diff = buf[i] - avg;
            varianceSum += diff * diff;
        }
        double stdDev = Math.sqrt(varianceSum / validCount);

        Arrays.sort(buf, 0, validCount);
        double p50 = percentileFromSorted(buf, validCount, 0.50);
        double p95 = percentileFromSorted(buf, validCount, 0.95);
        double p99 = percentileFromSorted(buf, validCount, 0.99);

        return new WindowStats(validCount, avg, min, max, stdDev, p50, p95, p99);
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

    public synchronized void clear() {
        head = 0;
        size = 0;
    }

    public long getWindowDurationSeconds() {
        return windowDurationNanos / 1_000_000_000L;
    }

    public int getCapacity() {
        return capacity;
    }

    /**
     * Immutable statistical summary of a rolling window.
     */
    public static final class WindowStats {
        public static final WindowStats EMPTY = new WindowStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

        private final int sampleCount;
        private final double average;
        private final double min;
        private final double max;
        private final double stdDev;
        private final double p50;
        private final double p95;
        private final double p99;

        public WindowStats(int sampleCount,
                           double average,
                           double min,
                           double max,
                           double stdDev,
                           double p50,
                           double p95,
                           double p99) {
            this.sampleCount = sampleCount;
            this.average = average;
            this.min = min;
            this.max = max;
            this.stdDev = stdDev;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
        }

        public int getSampleCount() {
            return sampleCount;
        }

        public double getAverage() {
            return average;
        }

        public double getMin() {
            return min;
        }

        public double getMax() {
            return max;
        }

        public double getStdDev() {
            return stdDev;
        }

        public double getP50() {
            return p50;
        }

        public double getP95() {
            return p95;
        }

        public double getP99() {
            return p99;
        }
    }
}
