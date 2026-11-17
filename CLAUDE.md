# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**OptimizeServer** (`com.pratham:optimizeserver`, version `1.6.0`, authored by `NotGamerPratham`) is a Minecraft Java Edition server performance engineering, bottleneck diagnostics, and non-invasive execution optimization plugin (`api-version: '1.13'`, `folia-supported: true`, `load: POSTWORLD`).

Its core rule is **zero intentional gameplay modification**: every feature improves monitoring/diagnostic efficiency, server/JVM tuning guidance, or async offloading while preserving 100% of vanilla Minecraft semantics.

---

## Build & Verification Commands

Default builds cross-compile to **Java 8 bytecode** (`class version 52.0`) using Maven toolchain flags (`--release 8`, `-Xlint:all,-serial,-processing,-options`) so the single output artifact (`target/OptimizeServer.jar`) runs from Minecraft `1.8.8` through `1.21.x+` on Java `8` through `25+`.

```bash
# Default universal build (universal-java8 profile -> target/OptimizeServer.jar)
mvn clean test package

# Optional compatibility verification profiles (Spigot 1.20.4 / 1.21.1 + newer Chunky APIs)
mvn clean test -Pmodern-java17
mvn clean test -Pmodern-java21
```

**Important build rules:**
- Run profiles **sequentially**, never in parallel, because all profiles share `target/`.
- Always finish with `mvn clean test package` so `target/OptimizeServer.jar` is packaged with Java 8 bytecode (`major version: 52`).
- Keep all source code strictly Java 8 source/API compatible (`--release 8`) with zero javac warnings.
- `spigot-api`, `spark-api`, `chunky-common`, and `org.jetbrains:annotations` are `provided` (and optional where applicable) in `pom.xml` — never shade them into the plugin JAR.
- `src/main/resources/` uses Maven resource filtering (`version: '${project.version}'` in `src/main/resources/plugin.yml`).
- Tests use JUnit 5 (`src/test/java/com/pratham/optimizeserver/`) with `TestServerHarness` mock Bukkit proxies (`CompatibilityAndSafetyTest`, `DiagnosticIntelligenceTest`, `FoliaAndBenchmarkVerificationTest`, `MetricsAndMonitoringTest`, `OptimizationAndLifecycleTest`).

---

## Architecture Overview

Root package: `com.pratham.optimizeserver`

- **`OptimizeServer`** — `JavaPlugin` lifecycle entrypoint. Wraps Bukkit/Paper's `PluginLogger` via `createCleanPluginLogger(...)` so internal `[OptimizeServer]` / `[OptimizeServer Alert]` prefixes are not duplicated in server console logs.
- **`core/`** (`PerformanceEngine`, `EngineLifecycle`, `EngineState`) — Coordinates engine lifecycle, bounded worker pool, scheduler, metric collection, diagnostics, optimization modules, config auditing, and integrations.
- **`compatibility/`** (`CompatibilityManager`, `ServerPlatform`, `MinecraftVersion`, `JavaRuntimeInfo`, `PlatformCapabilities`, `CompatibilityReport`) — Detects Spigot, Paper, Purpur, Pufferfish, Airplane, Gale, Leaves, Folia, CraftBukkit, and unknown Bukkit forks; inspects JVM version, GC collectors, and container RAM flags (`hasContainerRamFlags()`, `getMaxRamPercentage()`, `detectContainerOrSystemMemoryMb()`).
- **`safety/`** (`GameplayInvariant`, `SafetyValidator`, `CompatibilityGuard`, `SafeModeController`) — Enforces the 16 non-negotiable `GameplayInvariant` constraints and activates conservative safe mode on unrecognized forks or unsafe Folia states.
- **`scheduler/`** (`SchedulerAdapter`, `UniversalScheduler`, `BoundedWorkerPool`, `TaskHandle`) — Routes tasks across Bukkit main thread, Folia (`GlobalRegionScheduler`, `RegionScheduler`, `EntityScheduler` via reflection), and the bounded async worker pool.
- **`metrics/`** (`MetricRegistry`, `MetricSnapshot`, `MetricHistory`, `RollingWindow`) — `O(1)` ring buffers, scratch-buffer percentile calculations (`P50`, `P95`, `P99`, `StdDev`), and `O(N)` window averages computed off the tick thread.
- **`monitoring/`** (`TickMonitor`, `CpuMonitor`, `MemoryMonitor`, `NetworkMonitor`, `ChunkMonitor`, `EntityMonitor`, `TaskMonitor`, `StorageMonitor`) & **`listeners/`** (`ServerActivityListener`) — Read-only telemetry. Uses reflection for Paper `O(1)` entity/chunk counters, incremental chunk scan budgets, Post-GC OldGen monitoring, and event-driven fallbacks on Folia.
- **`analyzer/`** (`DiagnosticCoordinator`, `BottleneckAnalyzer`, `AnomalyDetector`, `TrendAnalyzer`, `EvidenceCollector`, `RecommendationEngine`, `ServerConfigAuditor`, `AlertManager`) — Evidence-backed bottleneck classification, cooldown-throttled alerts, capacity estimation (`/os capacity`), and zero-gameplay config/JVM auditing (`/os tune`, writing presets to `plugins/OptimizeServer/presets/`).
- **`optimizer/`** (`OptimizationManager`, `OptimizationRegistry`, `OptimizationModule`, `OptimizationValidator`, `RollbackManager`, `OptimizationCategory`) — Manages 13 internal efficiency modules (`9 AUTOMATIC_SAFE` + `4 CONDITIONAL`), validating each through `SafetyValidator` before activation and triggering automatic MSPT regression rollback (configured via `optimization.regression-rollback-streak`, default `12`, minimum clamped to `3`).
- **`commands/`** (`OptimizeServerCommand`, `CommandPermissions`, `CommandPagination`) — Implements `/optimizeserver` (`/os`), tab completion, pagination, and `optimizeserver.*` permission checks.
- **`config/`** (`ConfigManager`, `ConfigValidator`, `ConfigMigrator`) — Handles `config-version: 3` migration, bounded lookup caching, and forced invariant sanitization (`safety.enforce-gameplay-invariants` is always locked `true`; `general.telemetry-enabled` is sanitized `false`).
- **`integrations/`** (`DependencyAutoInstaller`, `spark/SparkIntegration`, `chunky/ChunkyIntegration`) — Asynchronously installs missing Spark/Chunky JARs with SHA-512 & archive verification (when enabled) and exposes manual `/os profile` and `/os chunks` controls without auto-uploading profiles or auto-starting chunk generation.
- **`reporting/`** (`ReportGenerator`, `DiagnosticReport`) — Generates and rotates local text diagnostic reports under `plugins/OptimizeServer/reports/`.

---

## Critical Domain & Safety Constraints

1. **16 Vanilla Gameplay Invariants (`GameplayInvariant`):**
   - Never skip server ticks, spoof TPS/MSPT metrics, remove entities, alter entity spawning or mob AI, modify view/simulation distance, mutate `GameRule`s or world data, alter redstone/physics/random ticks/combat/inventory/block/player interactions/packet semantics, or invoke `System.gc()`.
   - Every optimization module must be purely internal/non-gameplay, define an accurate `OperationProfile`, pass `OptimizationValidator`, and implement a complete `doRollback()`.
2. **Thread Ownership & Reflection Boundaries:**
   - Never touch live Bukkit `World`, `Chunk`, or `Entity` state from async threads. Always schedule world/entity work through `SchedulerAdapter` (`UniversalScheduler`).
   - Because compilation targets Spigot `1.16.5` (`--release 8`), access Paper/Folia/modern-MC/JVM-OS methods reflectively (e.g., Folia schedulers, `World#getChunkCount()`, `World#getEntityCount()`, `Chunk#getTileEntities(false)`, cgroup `getTotalMemorySize()`) with clean fallbacks.
3. **Container / Pterodactyl JVM Audit Safety (`ServerConfigAuditor` & `RecommendationEngine`):**
   - On Docker/Pterodactyl hosts (especially `-XX:MaxRAMPercentage`), always compute heap targets through `ServerConfigAuditor.resolveSafeHeapTargetMb(heapMaxMb, jvm)` so 20–25% of container RAM remains free for Metaspace, CodeHeap, G1 native structures, and Netty direct buffers, and omit `-XX:+AlwaysPreTouch` inside container-managed heaps to prevent cgroup `Exit 137` OOM kills.
4. **Release & CI Workflow Alignment:**
   - `.github/workflows/maven-publish.yml` builds with JDK 21 (`mvn -B clean test package`) and publishes to GitHub Packages on `main`.
   - `.github/workflows/release.yml` triggers on `v*` tags matching `<version>` in `pom.xml`, verifies tests, hashes `OptimizeServer.jar`, extracts notes from the matching `## [x.y.z]` section in `CHANGELOG.md`, and publishes GitHub Releases + optional Modrinth uploads. Keep `<version>` in `pom.xml` synchronized with `CHANGELOG.md` before tagging releases.
