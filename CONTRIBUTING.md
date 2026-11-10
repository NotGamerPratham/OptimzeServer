# Contributing to OptimizeServer

Thank you for your interest in contributing to **OptimizeServer** (authored by **NotGamerPratham**).

## Engineering Principles

1. **Zero Intentional Gameplay Modifications**:
   - No pull request may alter vanilla gameplay mechanics, entity behaviour, mob AI, player movement, combat, inventories, redstone, world generation, game rules, entity spawning, random tick rates, chunk loading semantics, or gameplay packets.
   - Any optimization module must pass `SafetyValidator` and provide a complete `rollback()` implementation.
2. **Thread Ownership & Folia Compatibility**:
   - Never access live Bukkit worlds, chunks, or entities from asynchronous worker threads.
   - Always route scheduling through `SchedulerAdapter` (`UniversalScheduler`).
3. **Bytecode & Runtime Compatibility**:
   - Default builds target Java 8 bytecode (`--release 8`) to ensure compatibility from legacy 1.8.8 servers through modern 1.21.x+ servers.
   - Platform-specific APIs (Paper, Folia, Spark, Chunky) must degrade gracefully when absent.
4. **Verification**:
   - Run `mvn clean test package` and verify all unit and integration tests pass before submitting changes.
