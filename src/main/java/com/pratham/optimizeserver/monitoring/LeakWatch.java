package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.metrics.MetricSnapshot;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only garbage-collection insight: per-collector totals and a memory-leak trend estimate.
 *
 * <p>The leak estimate uses the heap that is still live after garbage collection (the JVM's "collection usage"),
 * which only changes when a collection finishes. A leak shows up as that floor creeping upward cycle after cycle,
 * while ordinary allocation churn does not move it. It is a trend estimate, never proof.</p>
 */
public final class LeakWatch {

    /** Minimum distinct post-GC readings before a trend is reported. */
    static final int MIN_POINTS = 4;
    /** Minimum time span covered by those readings. */
    static final long MIN_SPAN_MILLIS = 4L * 60_000L;
    /** Growth (MB per hour) below which the floor is considered stable. */
    static final double STABLE_MB_PER_HOUR = 64.0;

    public enum Verdict {
        INSUFFICIENT_DATA,
        STABLE,
        RISING
    }

    public static final class CollectorStat {
        private final String name;
        private final long count;
        private final long timeMs;

        public CollectorStat(String name, long count, long timeMs) {
            this.name = name;
            this.count = count;
            this.timeMs = timeMs;
        }

        public String getName() { return name; }
        public long getCount() { return count; }
        public long getTimeMs() { return timeMs; }
        public double getAverageMs() { return count > 0L ? timeMs / (double) count : 0.0; }
    }

    public static final class Trend {
        private final Verdict verdict;
        private final int points;
        private final long spanMillis;
        private final double slopeMbPerHour;
        private final double latestMb;
        private final double maxMb;

        Trend(Verdict verdict, int points, long spanMillis, double slopeMbPerHour, double latestMb, double maxMb) {
            this.verdict = verdict;
            this.points = points;
            this.spanMillis = spanMillis;
            this.slopeMbPerHour = slopeMbPerHour;
            this.latestMb = latestMb;
            this.maxMb = maxMb;
        }

        public Verdict getVerdict() { return verdict; }
        public int getPoints() { return points; }
        public long getSpanMillis() { return spanMillis; }
        public double getSlopeMbPerHour() { return slopeMbPerHour; }
        public double getLatestMb() { return latestMb; }
        public double getMaxMb() { return maxMb; }

        /**
         * Hours until the post-GC floor would reach the heap limit at the current growth, or -1 when not rising.
         */
        public double getHoursToFull() {
            if (verdict != Verdict.RISING || slopeMbPerHour <= 0.0 || maxMb <= latestMb) {
                return -1.0;
            }
            return (maxMb - latestMb) / slopeMbPerHour;
        }
    }

    private LeakWatch() {
    }

    public static List<CollectorStat> collectors() {
        List<CollectorStat> out = new ArrayList<CollectorStat>();
        try {
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                long count = bean.getCollectionCount();
                long time = bean.getCollectionTime();
                out.add(new CollectorStat(bean.getName(), Math.max(0L, count), Math.max(0L, time)));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Fits a line through the distinct post-GC readings of {@code history} (oldest first).
     */
    public static Trend analyze(List<MetricSnapshot> history) {
        List<double[]> points = new ArrayList<double[]>(); // {timeMillis, usedMb}
        double maxMb = 0.0;
        double lastValue = -1.0;
        if (history != null) {
            for (MetricSnapshot s : history) {
                if (s == null || s.getPostGcHeapUsedBytes() <= 0L) {
                    continue;
                }
                double mb = s.getPostGcHeapUsedBytes() / (1024.0 * 1024.0);
                maxMb = Math.max(maxMb, s.getPostGcHeapMaxBytes() / (1024.0 * 1024.0));
                if (mb != lastValue) {
                    points.add(new double[]{s.getTimestampMillis(), mb});
                    lastValue = mb;
                }
            }
        }
        if (points.size() < MIN_POINTS) {
            return new Trend(Verdict.INSUFFICIENT_DATA, points.size(), 0L, 0.0, lastValue > 0 ? lastValue : 0.0, maxMb);
        }
        long span = (long) (points.get(points.size() - 1)[0] - points.get(0)[0]);
        if (span < MIN_SPAN_MILLIS) {
            return new Trend(Verdict.INSUFFICIENT_DATA, points.size(), span, 0.0, lastValue, maxMb);
        }
        double slopePerMs = leastSquaresSlope(points);
        double mbPerHour = slopePerMs * 3_600_000.0;
        Verdict verdict = mbPerHour >= STABLE_MB_PER_HOUR ? Verdict.RISING : Verdict.STABLE;
        return new Trend(verdict, points.size(), span, mbPerHour, lastValue, maxMb);
    }

    private static double leastSquaresSlope(List<double[]> points) {
        int n = points.size();
        double t0 = points.get(0)[0];
        double sumX = 0.0;
        double sumY = 0.0;
        for (double[] p : points) {
            sumX += p[0] - t0;
            sumY += p[1];
        }
        double meanX = sumX / n;
        double meanY = sumY / n;
        double num = 0.0;
        double den = 0.0;
        for (double[] p : points) {
            double dx = (p[0] - t0) - meanX;
            num += dx * (p[1] - meanY);
            den += dx * dx;
        }
        return den == 0.0 ? 0.0 : num / den;
    }
}
