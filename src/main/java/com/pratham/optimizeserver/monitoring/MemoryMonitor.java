package com.pratham.optimizeserver.monitoring;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Monitors JVM heap, non-heap usage, garbage collection counts, GC pause trends, and memory allocation rate.
 * Never performs forced garbage collection (System.gc()).
 */
public final class MemoryMonitor {

    private final MemoryMXBean memoryBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final ThreadMXBean threadBean;
    private Method getThreadAllocatedBytesMethod;

    private long lastSampleNanos = 0L;
    private long lastGcTimeMs = 0L;
    private long lastAllocatedBytes = 0L;

    private volatile long heapUsedBytes = 0L;
    private volatile long heapCommittedBytes = 0L;
    private volatile long heapMaxBytes = 0L;
    private volatile long nonHeapUsedBytes = 0L;
    private volatile long gcTotalCount = 0L;
    private volatile long gcTotalTimeMs = 0L;
    private volatile double recentGcPauseRateMsPerSec = 0.0;
    private volatile long recentAllocatedBytesPerSec = 0L;

    public MemoryMonitor() {
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.threadBean = ManagementFactory.getThreadMXBean();
        initThreadAllocationReflection();
    }

    private void initThreadAllocationReflection() {
        if (threadBean == null) {
            return;
        }
        try {
            Method isSupported = threadBean.getClass().getMethod("isThreadAllocatedMemorySupported");
            isSupported.setAccessible(true);
            if (Boolean.TRUE.equals(isSupported.invoke(threadBean))) {
                Method setEnabled = threadBean.getClass().getMethod("setThreadAllocatedMemoryEnabled", boolean.class);
                setEnabled.setAccessible(true);
                setEnabled.invoke(threadBean, true);
                getThreadAllocatedBytesMethod = threadBean.getClass().getMethod("getThreadAllocatedBytes", long[].class);
                getThreadAllocatedBytesMethod.setAccessible(true);
            }
        } catch (Throwable ignored) {
        }
    }

    public void sample(long nowNanos) {
        if (memoryBean != null) {
            try {
                MemoryUsage heap = memoryBean.getHeapMemoryUsage();
                if (heap != null) {
                    this.heapUsedBytes = Math.max(0L, heap.getUsed());
                    this.heapCommittedBytes = Math.max(0L, heap.getCommitted());
                    long max = heap.getMax();
                    this.heapMaxBytes = max > 0L ? max : Runtime.getRuntime().maxMemory();
                }
                MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
                if (nonHeap != null) {
                    this.nonHeapUsedBytes = Math.max(0L, nonHeap.getUsed());
                }
            } catch (Throwable ignored) {
            }
        }

        long totalCount = 0L;
        long totalTime = 0L;
        if (gcBeans != null) {
            for (GarbageCollectorMXBean bean : gcBeans) {
                if (bean != null) {
                    long c = bean.getCollectionCount();
                    long t = bean.getCollectionTime();
                    if (c > 0L) totalCount += c;
                    if (t > 0L) totalTime += t;
                }
            }
        }
        this.gcTotalCount = totalCount;
        this.gcTotalTimeMs = totalTime;

        long currentAllocated = readTotalAllocatedBytes();

        if (lastSampleNanos > 0L && nowNanos > lastSampleNanos) {
            double elapsedSeconds = (nowNanos - lastSampleNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0.05) {
                long deltaGcMs = Math.max(0L, totalTime - lastGcTimeMs);
                this.recentGcPauseRateMsPerSec = deltaGcMs / elapsedSeconds;

                if (currentAllocated >= lastAllocatedBytes && lastAllocatedBytes > 0L) {
                    this.recentAllocatedBytesPerSec = (long) ((currentAllocated - lastAllocatedBytes) / elapsedSeconds);
                }
            }
        }

        this.lastSampleNanos = nowNanos;
        this.lastGcTimeMs = totalTime;
        this.lastAllocatedBytes = currentAllocated;
    }

    private long readTotalAllocatedBytes() {
        if (getThreadAllocatedBytesMethod == null || threadBean == null) {
            return 0L;
        }
        try {
            long[] ids = threadBean.getAllThreadIds();
            Object raw = getThreadAllocatedBytesMethod.invoke(threadBean, (Object) ids);
            if (raw instanceof long[]) {
                long[] bytes = (long[]) raw;
                long sum = 0L;
                for (long b : bytes) {
                    if (b > 0L) {
                        sum += b;
                    }
                }
                return sum;
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    public long getHeapUsedBytes() {
        return heapUsedBytes;
    }

    public long getHeapCommittedBytes() {
        return heapCommittedBytes;
    }

    public long getHeapMaxBytes() {
        return heapMaxBytes;
    }

    public long getNonHeapUsedBytes() {
        return nonHeapUsedBytes;
    }

    public long getGcTotalCount() {
        return gcTotalCount;
    }

    public long getGcTotalTimeMs() {
        return gcTotalTimeMs;
    }

    public double getRecentGcPauseRateMsPerSec() {
        return recentGcPauseRateMsPerSec;
    }

    public long getRecentAllocatedBytesPerSec() {
        return recentAllocatedBytesPerSec;
    }
}
