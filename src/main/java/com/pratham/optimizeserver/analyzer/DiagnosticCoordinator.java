package com.pratham.optimizeserver.analyzer;

import com.pratham.optimizeserver.compatibility.CompatibilityReport;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.metrics.MetricHistory;
import com.pratham.optimizeserver.monitoring.TaskMonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates evidence collection, anomaly detection, trend analysis, bottleneck classification,
 * infrastructure recommendations, diagnostic deduplication, and short-TTL result caching.
 */
public final class DiagnosticCoordinator {

    public static final class DiagnosticEvaluation {
        private final long timestampMillis;
        private final EvidenceCollector.EvidenceBundle evidenceBundle;
        private final List<AnomalyDetector.AnomalySignal> anomalies;
        private final TrendAnalyzer.TrendSummary trends;
        private final List<DiagnosticFinding> findings;
        private final List<RecommendationEngine.InfrastructureRecommendation> recommendations;

        public DiagnosticEvaluation(long timestampMillis,
                                    EvidenceCollector.EvidenceBundle evidenceBundle,
                                    List<AnomalyDetector.AnomalySignal> anomalies,
                                    TrendAnalyzer.TrendSummary trends,
                                    List<DiagnosticFinding> findings,
                                    List<RecommendationEngine.InfrastructureRecommendation> recommendations) {
            this.timestampMillis = timestampMillis;
            this.evidenceBundle = evidenceBundle;
            this.anomalies = Collections.unmodifiableList(new ArrayList<AnomalyDetector.AnomalySignal>(anomalies));
            this.trends = trends;
            this.findings = Collections.unmodifiableList(new ArrayList<DiagnosticFinding>(findings));
            this.recommendations = Collections.unmodifiableList(new ArrayList<RecommendationEngine.InfrastructureRecommendation>(recommendations));
        }

        public long getTimestampMillis() {
            return timestampMillis;
        }

        public EvidenceCollector.EvidenceBundle getEvidenceBundle() {
            return evidenceBundle;
        }

        public List<AnomalyDetector.AnomalySignal> getAnomalies() {
            return anomalies;
        }

        public TrendAnalyzer.TrendSummary getTrends() {
            return trends;
        }

        public List<DiagnosticFinding> getFindings() {
            return findings;
        }

        public List<RecommendationEngine.InfrastructureRecommendation> getRecommendations() {
            return recommendations;
        }
    }

    private final EvidenceCollector evidenceCollector = new EvidenceCollector();
    private final AnomalyDetector anomalyDetector = new AnomalyDetector();
    private final TrendAnalyzer trendAnalyzer = new TrendAnalyzer();
    private final BottleneckAnalyzer bottleneckAnalyzer = new BottleneckAnalyzer();
    private final RecommendationEngine recommendationEngine = new RecommendationEngine();

    private final Map<String, Long> deduplicationTimestamps = new ConcurrentHashMap<String, Long>();
    private final List<DiagnosticFinding> findingsHistory = new CopyOnWriteArrayList<DiagnosticFinding>();

    private final AtomicBoolean deduplicationEnabled = new AtomicBoolean(true);
    private final AtomicBoolean resultCacheEnabled = new AtomicBoolean(true);
    private final AtomicLong deduplicatedCount = new AtomicLong(0L);
    private final AtomicLong cacheHits = new AtomicLong(0L);

    private volatile long dedupWindowMillis = 30_000L;
    private volatile long cacheTtlMillis = 3_000L;
    private volatile int maxFindingsHistory = 200;
    private volatile DiagnosticEvaluation cachedEvaluation;

    public DiagnosticEvaluation evaluate(MetricHistory history,
                                         TaskMonitor taskMonitor,
                                         CompatibilityReport compatibilityReport,
                                         ConfigManager config,
                                         boolean allowCached) {
        long now = System.currentTimeMillis();
        DiagnosticEvaluation currentCached = this.cachedEvaluation;
        if (allowCached && resultCacheEnabled.get() && currentCached != null
                && (now - currentCached.getTimestampMillis()) <= cacheTtlMillis) {
            cacheHits.incrementAndGet();
            return currentCached;
        }

        int trendSamples = config != null ? config.getInt("diagnostics.trend-window-samples", 30) : 30;
        double zScoreThreshold = config != null ? config.getDouble("diagnostics.anomaly-zscore-threshold", 2.5) : 2.5;
        double minConfidence = config != null ? config.getDouble("diagnostics.min-confidence-threshold", 0.55) : 0.55;
        this.maxFindingsHistory = config != null ? Math.max(20, config.getInt("diagnostics.max-findings-history", 200)) : 200;

        EvidenceCollector.EvidenceBundle bundle = evidenceCollector.collect(history, taskMonitor, compatibilityReport, trendSamples);
        List<AnomalyDetector.AnomalySignal> anomalies = anomalyDetector.detectAnomalies(
                bundle.getCurrent(), bundle.getRecentHistory(), zScoreThreshold);
        TrendAnalyzer.TrendSummary trends = trendAnalyzer.analyze(bundle.getRecentHistory());
        List<DiagnosticFinding> rawFindings = bottleneckAnalyzer.analyze(bundle, anomalies, trends, config);

        List<DiagnosticFinding> filtered = new ArrayList<DiagnosticFinding>();
        for (DiagnosticFinding finding : rawFindings) {
            if (finding.getConfidence() < minConfidence) {
                continue;
            }
            filtered.add(finding);
            recordInHistoryWithDedup(finding, now);
        }

        List<RecommendationEngine.InfrastructureRecommendation> recs =
                recommendationEngine.generateRecommendations(bundle, filtered);

        DiagnosticEvaluation eval = new DiagnosticEvaluation(now, bundle, anomalies, trends, filtered, recs);
        this.cachedEvaluation = eval;
        return eval;
    }

    private void recordInHistoryWithDedup(DiagnosticFinding finding, long nowMillis) {
        if (deduplicationEnabled.get()) {
            String sig = finding.getDeduplicationSignature();
            Long prevTime = deduplicationTimestamps.get(sig);
            if (prevTime != null && (nowMillis - prevTime.longValue()) < dedupWindowMillis) {
                deduplicatedCount.incrementAndGet();
                return;
            }
            deduplicationTimestamps.put(sig, Long.valueOf(nowMillis));
        }
        findingsHistory.add(finding);
        while (findingsHistory.size() > maxFindingsHistory) {
            findingsHistory.remove(0);
        }
    }

    public void setDeduplicationEnabled(boolean enabled, long windowSeconds) {
        this.deduplicationEnabled.set(enabled);
        this.dedupWindowMillis = Math.max(1L, windowSeconds) * 1000L;
        if (!enabled) {
            deduplicationTimestamps.clear();
        }
    }

    public boolean isDeduplicationEnabled() {
        return deduplicationEnabled.get();
    }

    public void setResultCacheEnabled(boolean enabled, long ttlMillis) {
        this.resultCacheEnabled.set(enabled);
        this.cacheTtlMillis = Math.max(100L, ttlMillis);
        if (!enabled) {
            this.cachedEvaluation = null;
        }
    }

    public boolean isResultCacheEnabled() {
        return resultCacheEnabled.get();
    }

    public void invalidateCache() {
        this.cachedEvaluation = null;
    }

    public long getDeduplicatedCount() {
        return deduplicatedCount.get();
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public List<DiagnosticFinding> getFindingsHistory() {
        return Collections.unmodifiableList(new ArrayList<DiagnosticFinding>(findingsHistory));
    }
}

