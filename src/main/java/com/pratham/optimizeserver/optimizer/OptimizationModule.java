package com.pratham.optimizeserver.optimizer;

import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.safety.GameplayInvariant;

/**
 * Contract for a validated, reversible internal optimization module.
 * All modules preserve vanilla Minecraft gameplay and only optimize internal execution efficiency.
 */
public interface OptimizationModule {

    /**
     * Unique module identifier (e.g., "config-lookup-cache").
     */
    String getId();

    /**
     * Human-readable description of the optimization.
     */
    String getDescription();

    /**
     * Optimization category (AUTOMATIC_SAFE or CONDITIONAL).
     */
    OptimizationCategory getCategory();

    /**
     * Safety operation profile inspected by {@link com.pratham.optimizeserver.safety.SafetyValidator}.
     */
    GameplayInvariant.OperationProfile getOperationProfile();

    /**
     * Minimum required Java feature version (default 8).
     */
    int getMinimumJavaVersion();

    /**
     * Whether this module is safe on Folia regionalized multithreading.
     */
    boolean isFoliaCompatible();

    /**
     * Evaluates whether current conditions justify activating or maintaining this module.
     */
    boolean isActivationConditionMet(MetricSnapshot latestSnapshot);

    /**
     * Description of the measurable expected result.
     */
    String getExpectedResult();

    /**
     * Captured baseline measurement before module activation.
     */
    String getBeforeMeasurement();

    /**
     * Live or post-activation measurement demonstrating the module's effect.
     */
    String getAfterMeasurement();

    /**
     * Activates the optimization and captures baseline state for rollback.
     *
     * @return true if activated successfully
     */
    boolean enable();

    /**
     * Disables the optimization cleanly.
     *
     * @return true if disabled successfully
     */
    boolean disable();

    /**
     * Reverts the optimization to its exact pre-activation state.
     *
     * @return true if rolled back successfully
     */
    boolean rollback();

    /**
     * Returns true if the module is currently active.
     */
    boolean isEnabled();

    /**
     * Returns the last error message if an activation or rollback failed, or null if healthy.
     */
    String getLastError();
}
