package com.pratham.optimizeserver.monitoring;

import java.io.File;

/**
 * Collects storage capacity and non-intrusive metadata I/O latency indicators asynchronously.
 * Never performs blocking disk operations on the primary server thread.
 */
public final class StorageMonitor {

    private final File storageDirectory;
    private volatile long freeDiskSpaceMb = 0L;
    private volatile long totalDiskSpaceMb = 0L;
    private volatile double storageIoLatencyMs = 0.0;

    public StorageMonitor(File storageDirectory) {
        this.storageDirectory = storageDirectory != null ? storageDirectory : new File(".");
    }

    /**
     * Must be invoked from an asynchronous worker thread (never the server main thread).
     */
    public void sampleAsync() {
        try {
            long startNanos = System.nanoTime();
            long usableBytes = storageDirectory.getUsableSpace();
            long totalBytes = storageDirectory.getTotalSpace();
            long elapsedNanos = Math.max(0L, System.nanoTime() - startNanos);

            this.freeDiskSpaceMb = usableBytes / (1024L * 1024L);
            this.totalDiskSpaceMb = totalBytes / (1024L * 1024L);
            this.storageIoLatencyMs = elapsedNanos / 1_000_000.0;
        } catch (Throwable ignored) {
        }
    }

    public long getFreeDiskSpaceMb() {
        return freeDiskSpaceMb;
    }

    public long getTotalDiskSpaceMb() {
        return totalDiskSpaceMb;
    }

    public double getStorageIoLatencyMs() {
        return storageIoLatencyMs;
    }
}

