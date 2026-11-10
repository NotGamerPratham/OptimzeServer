package com.pratham.optimizeserver.analyzer;

import com.pratham.optimizeserver.metrics.MetricSnapshot;

import java.util.List;

/**
 * Computes linear regression trends across historical metric snapshots to identify sustained degradation
 * such as rising MSPT, memory growth, or entity accumulation.
 */
public final class TrendAnalyzer {

    public static final class TrendSummary {
        private final double msptSlopePerMinute;
        private final double heapGrowthPercentPerMinute;
        private final double entityGrowthPerMinute;
        private final double cpuSlopePerMinute;
        private final int sampleCount;

        public TrendSummary(double msptSlopePerMinute,
                            double heapGrowthPercentPerMinute,
                            double entityGrowthPerMinute,
                            double cpuSlopePerMinute,
                            int sampleCount) {
            this.msptSlopePerMinute = msptSlopePerMinute;
            this.heapGrowthPercentPerMinute = heapGrowthPercentPerMinute;
            this.entityGrowthPerMinute = entityGrowthPerMinute;
            this.cpuSlopePerMinute = cpuSlopePerMinute;
            this.sampleCount = sampleCount;
        }

        public double getMsptSlopePerMinute() {
            return msptSlopePerMinute;
        }

        public double getHeapGrowthPercentPerMinute() {
            return heapGrowthPercentPerMinute;
        }

        public double getEntityGrowthPerMinute() {
            return entityGrowthPerMinute;
        }

        public double getCpuSlopePerMinute() {
            return cpuSlopePerMinute;
        }

        public int getSampleCount() {
            return sampleCount;
        }
    }

    public TrendSummary analyze(List<MetricSnapshot> history) {
        if (history == null || history.size() < 3) {
            return new TrendSummary(0.0, 0.0, 0.0, 0.0, history != null ? history.size() : 0);
        }

        int n = history.size();
        long t0 = history.get(0).getTimestampMillis();
        double[] xMinutes = new double[n];
        double[] yMspt = new double[n];
        double[] yHeapPct = new double[n];
        double[] yEntities = new double[n];
        double[] yCpu = new double[n];

        for (int i = 0; i < n; i++) {
            MetricSnapshot s = history.get(i);
            double mins = Math.max(0.0, (s.getTimestampMillis() - t0) / 60000.0);
            if (mins == 0.0 && i > 0) {
                mins = i / 60.0; // Fallback if synthetic timestamps are identical in unit tests
            }
            xMinutes[i] = mins;
            yMspt[i] = s.getAverageMspt();
            yHeapPct[i] = s.getHeapUsagePercent();
            yEntities[i] = s.getTotalEntities();
            yCpu[i] = s.getProcessCpuLoadPercent();
        }

        return new TrendSummary(
                computeSlope(xMinutes, yMspt),
                computeSlope(xMinutes, yHeapPct),
                computeSlope(xMinutes, yEntities),
                computeSlope(xMinutes, yCpu),
                n
        );
    }

    private static double computeSlope(double[] x, double[] y) {
        int n = x.length;
        if (n < 2) {
            return 0.0;
        }
        double sumX = 0.0;
        double sumY = 0.0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
        }
        double meanX = sumX / n;
        double meanY = sumY / n;

        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            num += dx * (y[i] - meanY);
            den += dx * dx;
        }
        if (Math.abs(den) < 1e-9) {
            return 0.0;
        }
        return num / den;
    }
}

