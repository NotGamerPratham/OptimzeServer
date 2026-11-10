package com.pratham.optimizeserver.core;

/**
 * Lifecycle contract for the core performance engine.
 */
public interface EngineLifecycle {

    /**
     * Initializes subsystems, validates compatibility, and starts monitoring schedules.
     */
    void start();

    /**
     * Reloads configuration and re-evaluates modules and thresholds.
     */
    void reload();

    /**
     * Gracefully stops all scheduled tasks and worker executors.
     */
    void stop();

    /**
     * Returns the current engine operational state.
     */
    EngineState getState();
}
