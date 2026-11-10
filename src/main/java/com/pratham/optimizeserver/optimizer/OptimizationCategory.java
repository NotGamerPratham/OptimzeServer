package com.pratham.optimizeserver.optimizer;

/**
 * Categories of optimization modules in the Hybrid Optimization Engine.
 */
public enum OptimizationCategory {
    AUTOMATIC_SAFE("Automatic Safe Internal Optimization"),
    CONDITIONAL("Conditional Validated Optimization"),
    MANUAL_INFRASTRUCTURE("Manual Infrastructure Recommendation");

    private final String displayName;

    OptimizationCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

