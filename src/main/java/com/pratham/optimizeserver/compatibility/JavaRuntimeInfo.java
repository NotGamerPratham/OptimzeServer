package com.pratham.optimizeserver.compatibility;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Captures JVM version, vendor, active garbage collectors, and command-line flags.
 */
public final class JavaRuntimeInfo {

    private final String versionString;
    private final int featureVersion;
    private final String vmName;
    private final String vendor;
    private final int availableProcessors;
    private final List<String> jvmArguments;
    private final List<String> collectorNames;

    public JavaRuntimeInfo(String versionString,
                           int featureVersion,
                           String vmName,
                           String vendor,
                           int availableProcessors,
                           List<String> jvmArguments,
                           List<String> collectorNames) {
        this.versionString = versionString;
        this.featureVersion = featureVersion;
        this.vmName = vmName;
        this.vendor = vendor;
        this.availableProcessors = availableProcessors;
        this.jvmArguments = Collections.unmodifiableList(new ArrayList<String>(jvmArguments));
        this.collectorNames = Collections.unmodifiableList(new ArrayList<String>(collectorNames));
    }

    public static JavaRuntimeInfo detect() {
        String ver = System.getProperty("java.version", "1.8.0");
        int feature = parseFeatureVersion(ver);
        String vm = System.getProperty("java.vm.name", "Unknown VM");
        String vendor = System.getProperty("java.vendor", "Unknown Vendor");
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());

        List<String> args = new ArrayList<String>();
        try {
            RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
            if (runtimeBean != null && runtimeBean.getInputArguments() != null) {
                args.addAll(runtimeBean.getInputArguments());
            }
        } catch (Throwable ignored) {
        }

        List<String> gcs = new ArrayList<String>();
        try {
            List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
            if (beans != null) {
                for (GarbageCollectorMXBean bean : beans) {
                    if (bean != null && bean.getName() != null) {
                        gcs.add(bean.getName());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return new JavaRuntimeInfo(ver, feature, vm, vendor, cpus, args, gcs);
    }

    public static int parseFeatureVersion(String version) {
        if (version == null || version.isEmpty()) {
            return 8;
        }
        String cleaned = version.trim();
        if (cleaned.startsWith("1.")) {
            cleaned = cleaned.substring(2);
        }
        int dotIdx = cleaned.indexOf('.');
        int dashIdx = cleaned.indexOf('-');
        int underscoreIdx = cleaned.indexOf('_');
        int end = cleaned.length();
        if (dotIdx > 0 && dotIdx < end) end = dotIdx;
        if (dashIdx > 0 && dashIdx < end) end = dashIdx;
        if (underscoreIdx > 0 && underscoreIdx < end) end = underscoreIdx;
        try {
            return Integer.parseInt(cleaned.substring(0, end));
        } catch (NumberFormatException ex) {
            return 8;
        }
    }

    public String getVersionString() {
        return versionString;
    }

    public int getFeatureVersion() {
        return featureVersion;
    }

    public String getVmName() {
        return vmName;
    }

    public String getVendor() {
        return vendor;
    }

    public int getAvailableProcessors() {
        return availableProcessors;
    }

    public List<String> getJvmArguments() {
        return jvmArguments;
    }

    public List<String> getCollectorNames() {
        return collectorNames;
    }

    public boolean isUsingG1GC() {
        for (String gc : collectorNames) {
            if (gc.toLowerCase(Locale.ROOT).contains("g1")) {
                return true;
            }
        }
        for (String arg : jvmArguments) {
            if (arg.contains("+UseG1GC")) {
                return true;
            }
        }
        return false;
    }

    public boolean isUsingZGC() {
        for (String gc : collectorNames) {
            if (gc.toLowerCase(Locale.ROOT).contains("zgc")) {
                return true;
            }
        }
        for (String arg : jvmArguments) {
            if (arg.contains("+UseZGC")) {
                return true;
            }
        }
        return false;
    }

    public boolean hasExplicitDisableExplicitGC() {
        for (String arg : jvmArguments) {
            if (arg.contains("+DisableExplicitGC")) {
                return true;
            }
        }
        return false;
    }

    public boolean hasMatchingXmsXmx() {
        String xms = null;
        String xmx = null;
        for (String arg : jvmArguments) {
            if (arg.startsWith("-Xms")) {
                xms = arg.substring(4);
            } else if (arg.startsWith("-Xmx")) {
                xmx = arg.substring(4);
            }
        }
        return xms != null && xms.equalsIgnoreCase(xmx);
    }
}
