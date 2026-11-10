package com.pratham.optimizeserver.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validates operations against OptimizeServer's strict gameplay-preservation and thread-safety rules.
 */
public final class SafetyValidator {

    public static final class ValidationResult {
        private final boolean safe;
        private final List<String> violations;

        public ValidationResult(boolean safe, List<String> violations) {
            this.safe = safe;
            this.violations = Collections.unmodifiableList(new ArrayList<String>(violations));
        }

        public boolean isSafe() {
            return safe;
        }

        public List<String> getViolations() {
            return violations;
        }
    }

    public ValidationResult validate(GameplayInvariant.OperationProfile profile) {
        List<String> violations = new ArrayList<String>();
        if (profile == null) {
            violations.add("OperationProfile cannot be null.");
            return new ValidationResult(false, violations);
        }

        if (profile.isModifiesGameplayMechanics()) {
            violations.add("Violates invariant [" + GameplayInvariant.VANILLA_MECHANICS.name()
                    + "]: operation '" + profile.getOperationId() + "' modifies vanilla gameplay mechanics.");
        }
        if (profile.isModifiesGameRules()) {
            violations.add("Violates invariant [" + GameplayInvariant.GAME_RULES.name()
                    + "]: operation '" + profile.getOperationId() + "' modifies server game rules.");
        }
        if (profile.isModifiesWorldData()) {
            violations.add("Violates invariant [" + GameplayInvariant.WORLD_GENERATION.name()
                    + "]: operation '" + profile.getOperationId() + "' mutates world data.");
        }
        if (profile.isRemovesEntities()) {
            violations.add("Violates invariant [" + GameplayInvariant.ENTITY_BEHAVIOUR.name()
                    + "]: operation '" + profile.getOperationId() + "' removes entities to improve performance.");
        }
        if (profile.isDisablesMobAi()) {
            violations.add("Violates invariant [" + GameplayInvariant.MOB_AI.name()
                    + "]: operation '" + profile.getOperationId() + "' disables or throttles mob AI.");
        }
        if (profile.isSkipsServerTicks()) {
            violations.add("Violates invariant [" + GameplayInvariant.RANDOM_TICK_BEHAVIOUR.name()
                    + "]: operation '" + profile.getOperationId() + "' skips server ticks or manipulates TPS.");
        }
        if (profile.isModifiesSimulationOrViewDistance()) {
            violations.add("Violates invariant [" + GameplayInvariant.CHUNK_LOADING_SEMANTICS.name()
                    + "]: operation '" + profile.getOperationId() + "' alters simulation or view distance.");
        }
        if (profile.isAltersGameplayPackets()) {
            violations.add("Violates invariant [" + GameplayInvariant.PACKET_SEMANTICS.name()
                    + "]: operation '" + profile.getOperationId() + "' alters gameplay-related packets.");
        }
        if (profile.isAccessesBukkitObjectsAsync()) {
            violations.add("Thread-safety violation: operation '" + profile.getOperationId()
                    + "' accesses live Bukkit world/chunk/entity objects asynchronously.");
        }
        if (profile.isForcesExplicitGarbageCollection()) {
            violations.add("JVM stability violation: operation '" + profile.getOperationId()
                    + "' triggers forced System.gc() pauses.");
        }
        if (!profile.isReversible()) {
            violations.add("Rollback requirement violation: operation '" + profile.getOperationId()
                    + "' does not provide a reversible rollback path.");
        }

        return new ValidationResult(violations.isEmpty(), violations);
    }
}

