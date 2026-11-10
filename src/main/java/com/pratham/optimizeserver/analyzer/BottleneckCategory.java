package com.pratham.optimizeserver.analyzer;

/**
 * Classification categories for server performance bottlenecks.
 */
public enum BottleneckCategory {
    CPU_BOUND("CPU-Bound Execution"),
    MEMORY_PRESSURE("Heap Memory Pressure"),
    GC_PRESSURE("Garbage Collection Pressure"),
    CHUNK_PROCESSING("Chunk Loading & Generation Bottleneck"),
    ENTITY_PROCESSING("Entity & Block-Entity Processing Bottleneck"),
    PLUGIN_TASK_OVERHEAD("Plugin Scheduled Task Overhead"),
    STORAGE_BOTTLENECK("Storage Capacity or I/O Latency Bottleneck"),
    NETWORK_PROCESSING("Network & Packet Processing Overhead"),
    UNKNOWN_OR_INSUFFICIENT_EVIDENCE("Unknown or Insufficient Evidence");

    private final String displayName;

    BottleneckCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

