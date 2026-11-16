package com.pratham.optimizeserver.monitoring;

import com.pratham.optimizeserver.compatibility.ManagementBeans;
import com.pratham.optimizeserver.metrics.RollingWindow;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;

/**
 * Monitors JVM process CPU utilization, system CPU utilization, processor count, and saturation trends.
 */
public final class CpuMonitor {

    private final OperatingSystemMXBean osBean;
    private final int availableProcessors;
    private volatile Method getProcessCpuLoadMethod;
    private volatile Method getSystemCpuLoadMethod;
    private final RollingWindow processCpuWindow = new RollingWindow(60L, 120);
    private final RollingWindow systemCpuWindow = new RollingWindow(60L, 120);

    private volatile double latestProcessCpuPercent = 0.0;
    private volatile double latestSystemCpuPercent = 0.0;

    public CpuMonitor() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.availableProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());
        initReflection();
    }

    private void initReflection() {
        if (osBean == null) {
            return;
        }
        // Resolve through the exported com.sun.management interface: the implementation class is in a
        // non-exported JDK package, so the old lookup silently failed on Java 16+ and CPU read as 0%.
        Class<?> accessibleType = ManagementBeans.accessibleType(osBean, ManagementBeans.OS_BEAN_INTERFACE);
        this.getProcessCpuLoadMethod = ManagementBeans.findUsable(osBean, accessibleType, "getProcessCpuLoad");
        Method systemLoad = ManagementBeans.findUsable(osBean, accessibleType, "getCpuLoad"); // Java 14+
        if (systemLoad == null) {
            systemLoad = ManagementBeans.findUsable(osBean, accessibleType, "getSystemCpuLoad");
        }
        this.getSystemCpuLoadMethod = systemLoad;
    }

    /**
     * True when process CPU load comes from the JVM's native counter rather than the load-average estimate.
     */
    public boolean isNativeProcessCpuLoadAvailable() {
        return getProcessCpuLoadMethod != null;
    }

    public void sample(long nowNanos) {
        double procPercent = readLoadPercent(getProcessCpuLoadMethod, true);
        double sysPercent = readLoadPercent(getSystemCpuLoadMethod, false);
        if (procPercent < 0.0 && osBean != null) {
            double loadAvg = osBean.getSystemLoadAverage();
            if (loadAvg >= 0.0) {
                procPercent = Math.min(100.0, (loadAvg / availableProcessors) * 100.0);
            } else {
                procPercent = 0.0;
            }
        }
        if (sysPercent < 0.0) {
            sysPercent = procPercent;
        }

        this.latestProcessCpuPercent = procPercent;
        this.latestSystemCpuPercent = sysPercent;
        processCpuWindow.record(procPercent, nowNanos);
        systemCpuWindow.record(sysPercent, nowNanos);
    }

    private double readLoadPercent(Method method, boolean processLoad) {
        if (method == null || osBean == null) {
            return -1.0;
        }
        try {
            Object raw = method.invoke(osBean);
            if (raw instanceof Number) {
                double ratio = ((Number) raw).doubleValue();
                if (ratio >= 0.0 && !Double.isNaN(ratio)) {
                    return Math.min(100.0, ratio * 100.0);
                }
            }
        } catch (Throwable ignored) {
            // Stop paying for a reflective exception every second once an accessor proves unusable
            if (processLoad) {
                this.getProcessCpuLoadMethod = null;
            } else {
                this.getSystemCpuLoadMethod = null;
            }
        }
        return -1.0;
    }

    public double getLatestProcessCpuPercent() {
        return latestProcessCpuPercent;
    }

    public double getLatestSystemCpuPercent() {
        return latestSystemCpuPercent;
    }

    public int getAvailableProcessors() {
        return availableProcessors;
    }

    public RollingWindow.WindowStats getProcessCpuStats(long nowNanos) {
        return processCpuWindow.computeStats(nowNanos);
    }

    public RollingWindow.WindowStats getSystemCpuStats(long nowNanos) {
        return systemCpuWindow.computeStats(nowNanos);
    }
}

