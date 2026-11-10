package com.pratham.optimizeserver.analyzer;

import com.pratham.optimizeserver.metrics.MetricSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Statistical anomaly detector evaluating Z-scores, tail-to-median ratios, and burst spikes
 * across recent performance snapshots.
 */
public final class AnomalyDetector {

    public static final class AnomalySignal {
        private final String metricName;
        private final double zScore;
        private final double observedValue;
        private final double baselineMean;
        private final String description;

        public AnomalySignal(String metricName,
                             double zScore,
                             double observedValue,
                             double baselineMean,
                             String description) {
            this.metricName = metricName;
            this.zScore = zScore;
            this.observedValue = observedValue;
            this.baselineMean = baselineMean;
            this.description = description;
        }

        public String getMetricName() {
            return metricName;
        }

        public double getZScore() {
            return zScore;
        }

        public double getObservedValue() {
            return observedValue;
        }

        public double getBaselineMean() {
            return baselineMean;
        }

        public String getDescription() {
            return description;
        }
    }

    public List<AnomalySignal> detectAnomalies(MetricSnapshot current,
                                               List<MetricSnapshot> history,
                                               double zScoreThreshold) {
        if (current == null) {
            return Collections.emptyList();
        }
        List<AnomalySignal> signals = new ArrayList<AnomalySignal>();
        double safeThreshold = Math.max(1.5, zScoreThreshold);

        // 1. Tail-to-median MSPT spike (P99 vs P50) even when historical baseline is short
        if (current.getP50Mspt() > 1.0 && current.getP99Mspt() > 50.0) {
            double tailRatio = current.getP99Mspt() / current.getP50Mspt();
            if (tailRatio >= 2.2) {
                signals.add(new AnomalySignal(
                        "mspt.p99_tail_ratio",
                        tailRatio,
                        current.getP99Mspt(),
                        current.getP50Mspt(),
                        String.format(Locale.ROOT,
                                "Severe tick-time tail spike: P99 MSPT (%.2f ms) is %.1fx higher than median P50 MSPT (%.2f ms).",
                                current.getP99Mspt(), tailRatio, current.getP50Mspt())
                ));
            }
        }

        if (history == null || history.size() < 5) {
            return signals;
        }

        // 2. Average MSPT Z-Score against historical baseline
        int n = history.size();
        double sumMspt = 0.0;
        double sumChunkGen = 0.0;
        for (int i = 0; i < n; i++) {
            MetricSnapshot s = history.get(i);
            sumMspt += s.getAverageMspt();
            sumChunkGen += s.getChunkGenRatePerSec();
        }
        double meanMspt = sumMspt / n;
        double meanChunkGen = sumChunkGen / n;

        double varMspt = 0.0;
        double varChunkGen = 0.0;
        for (int i = 0; i < n; i++) {
            MetricSnapshot s = history.get(i);
            double dMspt = s.getAverageMspt() - meanMspt;
            varMspt += dMspt * dMspt;
            double dGen = s.getChunkGenRatePerSec() - meanChunkGen;
            varChunkGen += dGen * dGen;
        }
        double stdMspt = Math.max(0.5, Math.sqrt(varMspt / n));
        double stdChunkGen = Math.max(0.2, Math.sqrt(varChunkGen / n));

        double zMspt = (current.getAverageMspt() - meanMspt) / stdMspt;
        if (zMspt >= safeThreshold && current.getAverageMspt() > 25.0) {
            signals.add(new AnomalySignal(
                    "mspt.average",
                    zMspt,
                    current.getAverageMspt(),
                    meanMspt,
                    String.format(Locale.ROOT,
                            "Average MSPT (%.2f ms) deviates +%.2fσ above recent baseline (%.2f ms).",
                            current.getAverageMspt(), zMspt, meanMspt)
            ));
        }

        double zChunkGen = (current.getChunkGenRatePerSec() - meanChunkGen) / stdChunkGen;
        if (zChunkGen >= safeThreshold && current.getChunkGenRatePerSec() >= 5.0) {
            signals.add(new AnomalySignal(
                    "chunks.generation_rate",
                    zChunkGen,
                    current.getChunkGenRatePerSec(),
                    meanChunkGen,
                    String.format(Locale.ROOT,
                            "Chunk generation burst (%.1f chunks/s) deviates +%.2fσ above baseline (%.1f chunks/s).",
                            current.getChunkGenRatePerSec(), zChunkGen, meanChunkGen)
            ));
        }

        return signals;
    }
}
