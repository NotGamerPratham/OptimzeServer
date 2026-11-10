package com.pratham.optimizeserver.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable diagnostic finding representing a detected performance bottleneck or anomaly.
 * Never treats correlation as proven causation without direct profiler or metric proof.
 */
public final class DiagnosticFinding {

    private final String id;
    private final long timestampMillis;
    private final Severity severity;
    private final BottleneckCategory subsystem;
    private final Map<String, String> supportingMetrics;
    private final List<String> evidence;
    private final double confidence;
    private final String recommendedInvestigation;
    private final String suggestedResolution;
    private final boolean automationEligible;

    public DiagnosticFinding(String id,
                             long timestampMillis,
                             Severity severity,
                             BottleneckCategory subsystem,
                             Map<String, String> supportingMetrics,
                             List<String> evidence,
                             double confidence,
                             String recommendedInvestigation,
                             String suggestedResolution,
                             boolean automationEligible) {
        this.id = id != null ? id : "FINDING-UNKNOWN";
        this.timestampMillis = timestampMillis;
        this.severity = severity != null ? severity : Severity.INFO;
        this.subsystem = subsystem != null ? subsystem : BottleneckCategory.UNKNOWN_OR_INSUFFICIENT_EVIDENCE;
        this.supportingMetrics = supportingMetrics != null
                ? Collections.unmodifiableMap(new LinkedHashMap<String, String>(supportingMetrics))
                : Collections.<String, String>emptyMap();
        this.evidence = evidence != null
                ? Collections.unmodifiableList(new ArrayList<String>(evidence))
                : Collections.<String>emptyList();
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.recommendedInvestigation = recommendedInvestigation != null ? recommendedInvestigation : "";
        this.suggestedResolution = suggestedResolution != null ? suggestedResolution : "";
        this.automationEligible = automationEligible;
    }

    public String getId() {
        return id;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public Severity getSeverity() {
        return severity;
    }

    public BottleneckCategory getSubsystem() {
        return subsystem;
    }

    public Map<String, String> getSupportingMetrics() {
        return supportingMetrics;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getRecommendedInvestigation() {
        return recommendedInvestigation;
    }

    public String getSuggestedResolution() {
        return suggestedResolution;
    }

    public boolean isAutomationEligible() {
        return automationEligible;
    }

    /**
     * Generates a stable deduplication key based on subsystem and severity.
     */
    public String getDeduplicationSignature() {
        return subsystem.name() + ":" + severity.name();
    }
}

