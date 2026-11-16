package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.compatibility.ManagementBeans;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Monitors JVM heap, post-GC old-generation occupancy, non-heap usage, garbage collection counts,
 * GC pause trends, and memory allocation rate.
 * Never performs forced garbage collection (System.gc()).
 */
public final class MemoryMonitor {

    private final MemoryMXBean memoryBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final List<MemoryPoolMXBean> oldGenPools;
    private final ThreadMXBean threadBean;
    private volatile Method getThreadAllocatedBytesMethod;
    // Java 21+: one cumulative JVM-wide counter (includes exited threads) instead of an id array + per-thread array
    private volatile Method getTotalThreadAllocatedBytesMethod;

    private long lastSampleNanos = 0L;
    private long lastGcTimeMs = 0L;
    private long lastAllocatedBytes = 0L;
    private boolean allocBaselineFresh = false;

    private volatile boolean threadAllocationSamplingEnabled = true;
    private volatile long heapUsedBytes = 0L;
    private volatile long heapCommittedBytes = 0L;
    private volatile long heapMaxBytes = 0L;
    private volatile long postGcHeapUsedBytes = 0L;
    private volatile long postGcHeapMaxBytes = 0L;
    private volatile long nonHeapUsedBytes = 0L;
    private volatile long gcTotalCount = 0L;
    private volatile long gcTotalTimeMs = 0L;
    private volatile double recentGcPauseRateMsPerSec = 0.0;
    private volatile long recentAllocatedBytesPerSec = 0L;

    public MemoryMonitor() {
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.oldGenPools = findOldGenPools();
        this.threadBean = ManagementFactory.getThreadMXBean();
        initThreadAllocationReflection();
    }

    private static List<MemoryPoolMXBean> findOldGenPools() {
        List<MemoryPoolMXBean> matched = new ArrayList<MemoryPoolMXBean>();
        try {
            List<MemoryPoolMXBean> all = ManagementFactory.getMemoryPoolMXBeans();
            if (all != null) {
                for (MemoryPoolMXBean pool : all) {
                    if (pool == null || pool.getType() != MemoryType.HEAP) {
                        continue;
                    }
                    String name = pool.getName() != null ? pool.getName().toLowerCase(Locale.ROOT) : "";
                    if (name.contains("old") || name.contains("tenured")
                            || name.contains("zheap") || name.contains("shenandoah")) {
                        matched.add(pool);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return matched;
    }

    private void initThreadAllocationReflection() {
        if (threadBean == null) {
            return;
        }
        // Resolved through the exported com.sun.management.ThreadMXBean interface; the previous lookup on the
        // non-exported implementation class failed on Java 16+, so allocation telemetry always read zero.
        Class<?> type = ManagementBeans.accessibleType(threadBean, ManagementBeans.THREAD_BEAN_INTERFACE);
        try {
            Method isSupported = ManagementBeans.findUsable(threadBean, type, "isThreadAllocatedMemorySupported");
            if (isSupported == null || !Boolean.TRUE.equals(isSupported.invoke(threadBean))) {
                return;
            }
            Method isEnabled = ManagementBeans.findUsable(threadBean, type, "isThreadAllocatedMemoryEnabled");
            if (isEnabled != null && !Boolean.TRUE.equals(isEnabled.invoke(threadBean))) {
                Method setEnabled = ManagementBeans.findUsable(threadBean, type, "setThreadAllocatedMemoryEnabled", boolean.class);
                if (setEnabled != null) {
                    setEnabled.invoke(threadBean, Boolean.TRUE);
                }
            }
            this.getTotalThreadAllocatedBytesMethod = ManagementBeans.findUsable(threadBean, type, "getTotalThreadAllocatedBytes");
            this.getThreadAllocatedBytesMethod = ManagementBeans.findUsable(threadBean, type, "getThreadAllocatedBytes", long[].class);
        } catch (Throwable ignored) {
        }
    }

    /**
     * True when allocation-rate telemetry is backed by a working JVM counter.
     */
    public boolean isAllocationTelemetryAvailable() {
        return getTotalThreadAllocatedBytesMethod != null || getThreadAllocatedBytesMethod != null;
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

        samplePostGcPools();

        boolean allocSampled = threadAllocationSamplingEnabled;
        long currentAllocated = allocSampled ? readTotalAllocatedBytes() : lastAllocatedBytes;

        if (lastSampleNanos > 0L && nowNanos > lastSampleNanos) {
            double elapsedSeconds = (nowNanos - lastSampleNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0.05) {
                long deltaGcMs = Math.max(0L, totalTime - lastGcTimeMs);
                this.recentGcPauseRateMsPerSec = deltaGcMs / elapsedSeconds;

                // A baseline taken before a pause would attribute the whole pause's allocations to one interval
                if (allocSampled && allocBaselineFresh
                        && currentAllocated >= lastAllocatedBytes && lastAllocatedBytes > 0L) {
                    this.recentAllocatedBytesPerSec = (long) ((currentAllocated - lastAllocatedBytes) / elapsedSeconds);
                }
            }
        }

        this.lastSampleNanos = nowNanos;
        this.lastGcTimeMs = totalTime;
        this.lastAllocatedBytes = currentAllocated;
        this.allocBaselineFresh = allocSampled;
    }

    private long readTotalAllocatedBytes() {
        if (threadBean == null) {
            return 0L;
        }
        Method total = this.getTotalThreadAllocatedBytesMethod;
        if (total != null) {
            try {
                Object raw = total.invoke(threadBean);
                if (raw instanceof Number && ((Number) raw).longValue() >= 0L) {
                    return ((Number) raw).longValue();
                }
            } catch (Throwable ignored) {
                this.getTotalThreadAllocatedBytesMethod = null;
            }
        }
        Method perThread = this.getThreadAllocatedBytesMethod;
        if (perThread == null) {
            return 0L;
        }
        try {
            long[] ids = threadBean.getAllThreadIds();
            Object raw = perThread.invoke(threadBean, (Object) ids);
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
            this.getThreadAllocatedBytesMethod = null;
        }
        return 0L;
    }

    private void samplePostGcPools() {
        if (oldGenPools.isEmpty()) {
            this.postGcHeapUsedBytes = 0L;
            this.postGcHeapMaxBytes = 0L;
            return;
        }
        long usedSum = 0L;
        long maxSum = 0L;
        for (MemoryPoolMXBean pool : oldGenPools) {
            try {
                MemoryUsage usage = pool.getCollectionUsage();
                if (usage != null && usage.getUsed() >= 0L) {
                    usedSum += usage.getUsed();
                    long poolMax = usage.getMax() > 0L ? usage.getMax() : this.heapMaxBytes;
                    if (poolMax > 0L) {
                        maxSum += poolMax;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        this.postGcHeapUsedBytes = usedSum;
        this.postGcHeapMaxBytes = maxSum;
    }

    public void setThreadAllocationSamplingEnabled(boolean enabled) {
        this.threadAllocationSamplingEnabled = enabled;
    }

    public boolean isThreadAllocationSamplingEnabled() {
        return threadAllocationSamplingEnabled;
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

    public long getPostGcHeapUsedBytes() {
        return postGcHeapUsedBytes;
    }

    public long getPostGcHeapMaxBytes() {
        return postGcHeapMaxBytes;
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

