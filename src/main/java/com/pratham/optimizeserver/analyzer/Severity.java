package com.pratham.optimizeserver.analyzer;

/**
 * Severity levels for diagnostic findings and bottleneck classifications.
 */
public enum Severity {
    INFO(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    public boolean isAtLeast(Severity other) {
        return other != null && this.rank >= other.rank;
    }
}

