# Changelog

All notable changes to **OptimizeServer** will be documented in this file.

## [1.7.0] - 2026-11-20

### Added
- **`/os ticks [minutes]`:** how server ticks are distributed by duration (<= 25 ms, 25-50, 50-100, 100-250, > 250 ms) over the last 1-15 minutes, with the share of ticks over 50 ms. Averages hide stutter; this shows it. Uses the existing tick ring, so it adds no per-tick cost.
- **`/os gc`:** per-collector collection counts, total and average pause time, the current GC pause and allocation rates, and a **memory-leak watch** that fits a trend through the heap left after each garbage collection. A floor that keeps climbing is reported with its growth in MB/hour and an estimate of when the heap would fill; it needs several GC cycles over at least 4 minutes and is an estimate, not proof.
- **`/os export [minutes]`:** writes recent metrics (TPS, MSPT, CPU, heap, GC, players, chunks, entities, block entities, tasks) to a CSV under `plugins/OptimizeServer/exports/` for charts and spreadsheets. Written off the server thread; the newest 20 exports are kept. Needs `optimizeserver.report`.

## [1.6.1] - 2026-11-20

### Added
- **Sponsor:** OptimizeServer is sponsored by [NGP Cloud](https://ngpcloud.in) (game server and Minecraft hosting). A plain-text sponsor line is shown once in the console at startup and in `/os version`; hide it with `general.show-sponsor: false`. The README has the sponsor banner, release notes carry a sponsor line, and `docs/publishing/sponsor-snippet.md` holds paste-ready Modrinth and SpigotMC blocks.

## [1.6.0] - 2026-11-18

### Added
- **`/os history [minutes]`:** sparkline charts of TPS, MSPT, heap usage, and online players for the last 1-15 minutes (default 5), with min / avg / max for each, built from the snapshots the plugin already keeps.
- **`/os plugins`:** ranks plugins by scheduled tasks (main-thread vs async) and running async workers, and warns when a plugin's main-thread task count keeps growing (a likely task leak). Uses the existing off-thread task sampling, so it adds no server cost.
- **Webhook alerts (opt-in):** `alerts.webhook` in `config.yml` posts critical/high findings and lag spikes to a Discord (or compatible) webhook. Disabled by default; requires an `https://` URL; rate-limited (`min-interval-seconds`, default 10); sent from async workers only. `/os webhook test` checks the setup.

## [1.5.0] - 2026-11-17

### Added
- **`/os tune apply`:** previews the gameplay-neutral config fixes found by `/os tune`, then writes them to `server.properties`, `bukkit.yml`, `spigot.yml`, and Paper configs after `/os tune apply confirm` (within 60 seconds). Every touched file is backed up to `plugins/OptimizeServer/backups/<timestamp>/` first, edits are line-based so comments and formatting are kept, only keys that already exist are changed, and files are replaced atomically. JVM flags, Java version, and server-jar advice stay manual. Settings take effect after a restart.
- **`/os tune restore`:** restores the config files from the most recent `/os tune apply` backup.
- New permission `optimizeserver.tune.apply` (default: op, included in `optimizeserver.admin`).
- `/os tune` now says how many of its suggestions `/os tune apply` can write.

### Changed
- `.claude/` (local Claude Code worktrees and settings) is now git-ignored.

## [1.4.0] - 2026-11-17

### Added
- **Lag spike recorder (`/os spikes [page|clear]`):** every tick slower than `monitoring.lag-spikes.threshold-ms` (default 100 ms) is captured with the loaded chunks, entities, players, heap usage, and garbage-collection time at that moment, plus a likely cause (GC pause, world generation, heap pressure). Keeps the last 50 spikes and logs a cooldown-limited console warning. Costs one comparison per normal tick.
- **Ticking block entities:** on Paper, `/os entities`, `/os worlds`, and entity diagnostics now show how many block entities tick every tick (hoppers, furnaces, spawners) separately from inert ones, via `World#getTickableTileEntityCount()`.
- **Per-world breakdown (`/os worlds`):** loaded chunks, entities, block entities, and players for each world, so you can see which world carries the load.
- **More `/os tune` checks:** suggests Java 21 for Minecraft 1.20+ servers on older Java (and Java 11 for Paper 1.16.5 on Java 8), flags `-Xmn` / fixed `NewSize` and `MaxGCPauseMillis` above 200 ms under G1, and now checks `sync-chunk-writes` on Spigot 1.16+ too.
- **Release workflow:** Modrinth publishing now needs only the `MODRINTH_TOKEN` secret; the project slug is resolved to its ID automatically (override with the `MODRINTH_PROJECT_ID` variable).
- Added container-safe heap reservation (`resolveSafeHeapTargetMb`) for `-XX:MaxRAMPercentage` / Pterodactyl / Docker hosts (reserving 20-25% off-heap headroom and omitting `-XX:+AlwaysPreTouch` inside container-managed heaps to prevent cgroup Exit 137 OOM kills) and `settings.netty-threads` 2-core auditing.
- **Console logger formatting:** Wrapped Bukkit/Paper `PluginLogger` in `OptimizeServer` to strip redundant `[OptimizeServer]` / normalize `[OptimizeServer Alert]` prefixes so console lines never print `[OptimizeServer] [OptimizeServer]`.

### Changed
- The automatic MSPT regression rollback now needs `optimization.regression-rollback-streak` consecutive severe samples (default `12`, minimum `3`) instead of a fixed 3, so brief spikes no longer roll back conditional modules.

## [1.3.0] - 2026-11-16

### Changed
- **Exact, cheaper tick measurement on Paper:** `TickMonitor` now reads each tick's duration from Paper's `ServerTickEndEvent` (`PaperTickEventHook`, resolved reflectively). This removes the per-tick scheduler task and the per-tick `getTickTimes()` array copy, and P95/P99 MSPT now reflect real individual ticks instead of a 20-tick average. Spigot and Folia keep the scheduler sampler, and if a fork ships the event class but never fires it, the plugin switches back after 3 seconds. Toggle: `monitoring.sampling.use-paper-tick-events`.
- **One write per tick:** a single `TickDurationRing` (exact integer prefix sums) replaces the five synchronized `RollingWindow`s. Window averages are O(log n), only the 1m window is sorted, and the sort runs outside the lock the tick thread writes under. Tick-history heap drops from ~640 KB to ~295 KB.
- **Less main-thread work per second:** `TaskMonitor` (BukkitScheduler task enumeration, O(n²) in queued tasks) now runs on the async worker. World/entity sampling moved from the per-tick task onto the 1-second system sampler. Async snapshot cycles that overlap on a saturated CPU are skipped instead of queued.
- **Allocation-free block-entity counts on Paper:** `EntityMonitor` uses `World#getTileEntityCount()` when available instead of copying `Chunk[]` arrays and creating a `BlockState` per block entity. Per-type spawn counters use an `AtomicIntegerArray` indexed by `EntityType` ordinal, so the entity-spawn listener does no hashing.
- **Shared snapshot maps:** `MetricSnapshot` shares the frozen entity-count maps published by monitors instead of copying both maps into each of the up to 900 retained snapshots.
- `/os tune` no longer recommends `prevent-moving-into-unloaded-chunks` (it rubber-bands players, which changes vanilla movement). Added zero-gameplay checks for `use-native-transport=false` on Linux and a missing `-XX:+AlwaysPreTouch` with a fixed heap.

### Fixed
- **CPU and allocation telemetry on Java 16+:** `CpuMonitor`, `MemoryMonitor`, and `CompatibilityManager` looked up methods on JDK-internal implementation classes, so on Java 16+ process CPU silently read 0% (on Windows) and allocation rate always read 0. This also disabled the CPU-based worker governor and alerts, and threw two exceptions every second. Lookups now go through the exported `com.sun.management` interfaces (new `ManagementBeans`); Java 21+ uses `getTotalThreadAllocatedBytes()`.
- Reflective Paper accessors (`getTPS`, `getAverageTickTime`, `getTickTimes`, `getTileEntityCount`) are dropped after their first failure instead of throwing on every tick or sample.
- `UniversalScheduler` no longer retains a handle for every one-shot task (a slow leak per command/alert); only repeating timers are tracked.
- The allocation rate no longer spikes right after `adaptive-telemetry-batcher` resumes sampling.

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

