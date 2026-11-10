package com.pratham.optimizeserver.optimizer;

import com.pratham.optimizeserver.compatibility.MinecraftVersion;
import com.pratham.optimizeserver.safety.CompatibilityGuard;
import com.pratham.optimizeserver.safety.GameplayInvariant;
import com.pratham.optimizeserver.safety.SafeModeController;
import com.pratham.optimizeserver.safety.SafetyValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validates that an {@link OptimizationModule} satisfies all gameplay invariants,
 * platform compatibility rules, and safe-mode policies before activation.
 */
public final class OptimizationValidator {

    public static final class ModuleValidationReport {
        private final boolean valid;
        private final List<String> reasons;

        public ModuleValidationReport(boolean valid, List<String> reasons) {
            this.valid = valid;
            this.reasons = Collections.unmodifiableList(new ArrayList<String>(reasons));
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getReasons() {
            return reasons;
        }
    }

    private final SafetyValidator safetyValidator;
    private final CompatibilityGuard compatibilityGuard;
    private final SafeModeController safeModeController;

    public OptimizationValidator(SafetyValidator safetyValidator,
                                 CompatibilityGuard compatibilityGuard,
                                 SafeModeController safeModeController) {
        this.safetyValidator = safetyValidator;
        this.compatibilityGuard = compatibilityGuard;
        this.safeModeController = safeModeController;
    }

    public ModuleValidationReport validateModule(OptimizationModule module) {
        List<String> reasons = new ArrayList<String>();
        if (module == null) {
            reasons.add("OptimizationModule is null.");
            return new ModuleValidationReport(false, reasons);
        }

        if (safeModeController != null && safeModeController.isSafeModeActive()
                && module.getCategory() == OptimizationCategory.CONDITIONAL) {
            reasons.add("Conditional optimization '" + module.getId()
                    + "' is blocked while conservative safe mode is active.");
        }

        GameplayInvariant.OperationProfile profile = module.getOperationProfile();
        if (safetyValidator != null) {
            SafetyValidator.ValidationResult safetyRes = safetyValidator.validate(profile);
            if (!safetyRes.isSafe()) {
                reasons.addAll(safetyRes.getViolations());
            }
        }

        if (compatibilityGuard != null) {
            boolean compat = compatibilityGuard.isSupported(
                    CompatibilityGuard.allKnownAndCompatiblePlatforms(),
                    new MinecraftVersion(1, 8, 0, "1.8.0"),
                    module.getMinimumJavaVersion(),
                    module.isFoliaCompatible()
            );
            if (!compat) {
                reasons.add("Platform or JVM compatibility requirement not met for module '" + module.getId() + "'.");
            }
        }

        return new ModuleValidationReport(reasons.isEmpty(), reasons);
    }
}

