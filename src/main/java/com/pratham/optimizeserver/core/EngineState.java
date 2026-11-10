package com.pratham.optimizeserver.core;

/**
 * Operational lifecycle states of the OptimizeServer PerformanceEngine.
 */
public enum EngineState {
    UNINITIALIZED,
    STARTING,
    RUNNING,
    SAFE_MODE,
    STOPPING,
    STOPPED
}
