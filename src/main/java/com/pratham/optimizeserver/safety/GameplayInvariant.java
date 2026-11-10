package com.pratham.optimizeserver.safety;

/**
 * Enumerates the non-negotiable vanilla Minecraft gameplay invariants protected by OptimizeServer.
 */
public enum GameplayInvariant {

    VANILLA_MECHANICS("Vanilla gameplay mechanics"),
    ENTITY_BEHAVIOUR("Entity behaviour"),
    MOB_AI("Mob AI execution"),
    PLAYER_MOVEMENT("Player movement"),
    COMBAT_CALCULATIONS("Combat calculations"),
    INVENTORY_MECHANICS("Inventory mechanics"),
    BLOCK_INTERACTIONS("Block interactions"),
    REDSTONE_BEHAVIOUR("Redstone behaviour"),
    WORLD_GENERATION("World generation"),
    GAME_RULES("Game rules"),
    ENTITY_SPAWNING("Entity spawning"),
    RANDOM_TICK_BEHAVIOUR("Random tick behaviour"),
    CHUNK_LOADING_SEMANTICS("Chunk loading semantics"),
    PLAYER_INTERACTIONS("Player interactions"),
    GAME_PHYSICS("Game physics"),
    PACKET_SEMANTICS("Gameplay-related packet semantics");

    private final String description;

    GameplayInvariant(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Immutable specification of an operation's side effects for validation by {@link SafetyValidator}.
     */
    public static final class OperationProfile {
        private final String operationId;
        private final boolean modifiesGameplayMechanics;
        private final boolean modifiesGameRules;
        private final boolean modifiesWorldData;
        private final boolean removesEntities;
        private final boolean disablesMobAi;
        private final boolean skipsServerTicks;
        private final boolean modifiesSimulationOrViewDistance;
        private final boolean altersGameplayPackets;
        private final boolean accessesBukkitObjectsAsync;
        private final boolean forcesExplicitGarbageCollection;
        private final boolean reversible;

        private OperationProfile(Builder builder) {
            this.operationId = builder.operationId;
            this.modifiesGameplayMechanics = builder.modifiesGameplayMechanics;
            this.modifiesGameRules = builder.modifiesGameRules;
            this.modifiesWorldData = builder.modifiesWorldData;
            this.removesEntities = builder.removesEntities;
            this.disablesMobAi = builder.disablesMobAi;
            this.skipsServerTicks = builder.skipsServerTicks;
            this.modifiesSimulationOrViewDistance = builder.modifiesSimulationOrViewDistance;
            this.altersGameplayPackets = builder.altersGameplayPackets;
            this.accessesBukkitObjectsAsync = builder.accessesBukkitObjectsAsync;
            this.forcesExplicitGarbageCollection = builder.forcesExplicitGarbageCollection;
            this.reversible = builder.reversible;
        }

        public static Builder builder(String operationId) {
            return new Builder(operationId);
        }

        public String getOperationId() {
            return operationId;
        }

        public boolean isModifiesGameplayMechanics() {
            return modifiesGameplayMechanics;
        }

        public boolean isModifiesGameRules() {
            return modifiesGameRules;
        }

        public boolean isModifiesWorldData() {
            return modifiesWorldData;
        }

        public boolean isRemovesEntities() {
            return removesEntities;
        }

        public boolean isDisablesMobAi() {
            return disablesMobAi;
        }

        public boolean isSkipsServerTicks() {
            return skipsServerTicks;
        }

        public boolean isModifiesSimulationOrViewDistance() {
            return modifiesSimulationOrViewDistance;
        }

        public boolean isAltersGameplayPackets() {
            return altersGameplayPackets;
        }

        public boolean isAccessesBukkitObjectsAsync() {
            return accessesBukkitObjectsAsync;
        }

        public boolean isForcesExplicitGarbageCollection() {
            return forcesExplicitGarbageCollection;
        }

        public boolean isReversible() {
            return reversible;
        }

        public static final class Builder {
            private final String operationId;
            private boolean modifiesGameplayMechanics;
            private boolean modifiesGameRules;
            private boolean modifiesWorldData;
            private boolean removesEntities;
            private boolean disablesMobAi;
            private boolean skipsServerTicks;
            private boolean modifiesSimulationOrViewDistance;
            private boolean altersGameplayPackets;
            private boolean accessesBukkitObjectsAsync;
            private boolean forcesExplicitGarbageCollection;
            private boolean reversible = true;

            private Builder(String operationId) {
                this.operationId = operationId != null ? operationId : "unnamed-operation";
            }

            public Builder modifiesGameplayMechanics(boolean val) {
                this.modifiesGameplayMechanics = val;
                return this;
            }

            public Builder modifiesGameRules(boolean val) {
                this.modifiesGameRules = val;
                return this;
            }

            public Builder modifiesWorldData(boolean val) {
                this.modifiesWorldData = val;
                return this;
            }

            public Builder removesEntities(boolean val) {
                this.removesEntities = val;
                return this;
            }

            public Builder disablesMobAi(boolean val) {
                this.disablesMobAi = val;
                return this;
            }

            public Builder skipsServerTicks(boolean val) {
                this.skipsServerTicks = val;
                return this;
            }

            public Builder modifiesSimulationOrViewDistance(boolean val) {
                this.modifiesSimulationOrViewDistance = val;
                return this;
            }

            public Builder altersGameplayPackets(boolean val) {
                this.altersGameplayPackets = val;
                return this;
            }

            public Builder accessesBukkitObjectsAsync(boolean val) {
                this.accessesBukkitObjectsAsync = val;
                return this;
            }

            public Builder forcesExplicitGarbageCollection(boolean val) {
                this.forcesExplicitGarbageCollection = val;
                return this;
            }

            public Builder reversible(boolean val) {
                this.reversible = val;
                return this;
            }

            public OperationProfile build() {
                return new OperationProfile(this);
            }
        }
    }
}
