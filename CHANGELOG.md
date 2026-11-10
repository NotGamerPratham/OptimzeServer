# Changelog

All notable changes to **OptimizeServer** will be documented in this file.

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
