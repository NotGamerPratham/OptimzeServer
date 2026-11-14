# Changelog

All notable changes to **OptimizeServer** will be documented in this file.

## [1.2.0] - 2026-11-14

### Added
- **Zero-Gameplay-Impact Execution & Memory Modules** (expanded from 9 to 13 total modules):
  - `zero-alloc-world-scanner`: Uses reflective Paper `World#getEntityCount()` and `Chunk#getTileEntities(false)` plus a 0.40 ms incremental chunk scan cursor (`EntityMonitor`).
  - `low-memory-footprint-governor`: Compacts `MetricHistory` ring capacity and trims diagnostic caches when heap ceiling `<= 1536 MB` or heap/OldGen pressure `>= 78%`, restoring capacity automatically on pressure relief or disable/rollback.
  - `async-io-offloader`: Moves live report serialization, `/os tune` config audits/preset writes, and `/os reload` activity log appends off the main tick thread.
  - `adaptive-telemetry-batcher`: Pauses `ThreadMXBean` per-thread allocation queries during MSPT (`>= 40 ms`) or CPU (`>= 85%`) spikes.
- **Post-GC OldGen Retention Telemetry & Cooldown Alerts:**
  - `MemoryMonitor` and `MetricSnapshot` track OldGen/Tenured/ZHeap pool occupancy (`getPostGcHeapUsagePercent()`).
  - `AlertManager` enforces `alerts.*` severity filtering and per-subsystem cooldowns with Folia-safe global dispatch.
  - `TaskMonitor` separates sync Bukkit tasks (`BukkitTask#isSync()`) and flags monotonic sync task growth streaks (`>= 25` tasks).
- **Non-Gameplay Config Auditor & Capacity Estimator (`ServerConfigAuditor`):**
  - Added `/os tune [page]` (`optimizeserver.diagnose`) and `/os capacity` (`optimizeserver.status`) to inspect `server.properties`, `bukkit.yml`, `spigot.yml`, `paper-global.yml`, `paper-world-defaults.yml`, `paper.yml`, JVM flags, and RAM headroom, writing presets to `plugins/OptimizeServer/presets/`.
  - Enhanced `/os status` with Post-GC OldGen percentage and live Capacity Status (`HEALTHY`, `TIGHT_HEADROOM`, or `INSUFFICIENT_RESOURCES`).
- **Automated MSPT Regression Safeguard:**
  - Wired `optimization.auto-rollback-on-regression` into `OptimizationManager` so sustained severe MSPT regressions (`>= 75 ms` and `>= +25 ms` over baseline for 3 consecutive evaluations) automatically roll back conditional tuning and log an audit record in `RollbackManager`.
- **Config Schema Migration v3 (`ConfigMigrator`):**
  - Bumped `config-version` to `3` with automatic default population for the new modules and alert settings.

### Changed
- **Main-Thread Overhead Reduction:**
  - Added `RollingWindow.computeAverage(long)` (`O(N)`, zero sorting) for `5s`, `30s`, `5m`, and `15m` TPS windows and moved `TickMonitor.refreshAggregates(...)` onto the asynchronous snapshot worker, removing per-second sorting of up to 25,400 elements from the primary tick thread.
  - Updated `ChunkMonitor` to use O(1) `World#getChunkCount()` reflectively when available instead of cloning `Chunk[]` arrays on every world sample.
  - Cached legacy `getHandle()` / `ping` reflection lookups in `NetworkMonitor` for MC 1.8.8–1.16.4 servers.
  - Coalesced `StorageMonitor.sampleAsync()` into the unified async sampler cycle when `task-batch-coordinator` is enabled, eliminating an idle background timer.
  - Removed high-frequency `PlayerMoveEvent` and `PlayerInteractEvent` handlers from `ServerActivityListener` and deactivated listener event capture cleanly in `OptimizeServer.onDisable()`.
  - Updated `RecommendationEngine` to tailor JVM flags by heap tier (low-RAM `<= 1536 MB` G1GC vs Aikar G1GC vs `>= 14 GB` Java 21+ Generational ZGC) and passed dynamic plugin version to `DependencyAutoInstaller` User-Agent headers.

## [1.0.0] - 2026-11-10

### Added
- **Universal Platform & Version Compatibility Engine** (`com.pratham.optimizeserver.compatibility`):
  - Automatic detection for Spigot, Paper, Purpur, Pufferfish, Airplane, Folia, Leaves, Gale, CraftBukkit, and unknown Bukkit-compatible servers.
  - Historical (1.8.8+), current (1.20.x, 1.21.x), and future Minecraft Java Edition version parsing.
  - Java runtime version and GC flag inspection across Java 8, 11, 16, 17, 21, and 25+.
  - Conservative safe-mode fallback on unrecognized server implementations.
- **Folia-Aware Scheduler & Bounded Worker Pool** (`com.pratham.optimizeserver.scheduler`):
  - Unified `SchedulerAdapter` supporting Bukkit/Paper main-thread tasks, Folia `GlobalRegionScheduler`, `RegionScheduler`, `EntityScheduler`, and bounded async workers (`BoundedWorkerPool`).
- **Real-Time Performance Monitoring Engine** (`com.pratham.optimizeserver.monitoring`):
  - `TickMonitor`: Monotonic MSPT & TPS across 5s, 30s, 1m, 5m, and 15m rolling windows with P50, P95, P99, and standard deviation statistics.
  - `CpuMonitor`: Process and system CPU load tracking.
  - `MemoryMonitor`: Heap, non-heap, GC pause rate, and thread allocation rate telemetry (strictly zero forced `System.gc()` calls).
  - `NetworkMonitor`: Player RTT ping vs server tick-overrun and packet processing overhead separation.
  - `ChunkMonitor`: Loaded chunks, load/unload rates, and new chunk generation rate with query coalescing.
  - `EntityMonitor`: Read-only entity and block-entity distributions by type and world.
  - `TaskMonitor`: Pending tasks and active worker tracking by owning plugin.
  - `StorageMonitor`: Asynchronous disk capacity and metadata I/O latency monitoring.
- **Performance Intelligence & Diagnostic Engine** (`com.pratham.optimizeserver.analyzer`):
  - `BottleneckAnalyzer`, `AnomalyDetector`, `TrendAnalyzer`, `RecommendationEngine`, `EvidenceCollector`, and `DiagnosticCoordinator`.
  - Classification across CPU, Memory, GC, Chunks, Entities, Plugin Tasks, Storage, Network, and Unknown/Insufficient Evidence.
- **Hybrid Optimization Engine & Safety Architecture** (`com.pratham.optimizeserver.optimizer`, `com.pratham.optimizeserver.safety`):
  - 9 reversible internal Automatic Safe and Conditional optimization modules with before/after measurements and `RollbackManager`.
  - Strict `SafetyValidator` protecting all 16 vanilla gameplay invariants.
- **Optional Integrations** (`com.pratham.optimizeserver.integrations`):
  - Optional Spark profiler integration (`SparkIntegration`) with Paper-bundled detection and privacy-preserving manual session controls.
  - Optional Chunky integration (`ChunkyIntegration`) with resource readiness checks and manual pre-generation controls.
- **Administrative Command Suite & Local Reporting**:
  - Full `/optimizeserver` (`/os`) command tree, tab completion, pagination, permission nodes, and local rotating diagnostic reports.

