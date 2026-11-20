package com.pratham.optimizeserver.reporting;

import com.pratham.optimizeserver.metrics.MetricSnapshot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Writes metric snapshots as CSV files under {@code plugins/OptimizeServer/exports/} for charts and spreadsheets.
 * File I/O: call from an async worker on live servers.
 */
public final class MetricExporter {

    public static final String HEADER = "timestamp_utc,tps_5s,mspt_avg,mspt_p95,mspt_p99,process_cpu_percent,"
            + "heap_used_mb,heap_max_mb,post_gc_heap_mb,gc_pause_ms_per_sec,players,loaded_chunks,entities,"
            + "block_entities,pending_tasks";

    static final int MAX_EXPORT_FILES = 20;

    private MetricExporter() {
    }

    public static String toCsv(List<MetricSnapshot> snapshots) {
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder(HEADER.length() + snapshots.size() * 120);
        sb.append(HEADER).append('\n');
        for (MetricSnapshot s : snapshots) {
            sb.append(utc.format(new Date(s.getTimestampMillis()))).append(',')
                    .append(num(s.getTps5s())).append(',')
                    .append(num(s.getAverageMspt())).append(',')
                    .append(num(s.getP95Mspt())).append(',')
                    .append(num(s.getP99Mspt())).append(',')
                    .append(num(s.getProcessCpuLoadPercent())).append(',')
                    .append(s.getHeapUsedBytes() / (1024L * 1024L)).append(',')
                    .append(s.getHeapMaxBytes() / (1024L * 1024L)).append(',')
                    .append(s.getPostGcHeapUsedBytes() / (1024L * 1024L)).append(',')
                    .append(num(s.getRecentGcPauseRateMsPerSec())).append(',')
                    .append(s.getOnlinePlayers()).append(',')
                    .append(s.getLoadedChunks()).append(',')
                    .append(s.getTotalEntities()).append(',')
                    .append(s.getTileEntityCount()).append(',')
                    .append(s.getPendingServerTasks()).append('\n');
        }
        return sb.toString();
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * Writes a new timestamped CSV and prunes the oldest exports beyond {@link #MAX_EXPORT_FILES}.
     *
     * @return the written file
     */
    public static File write(File exportsDir, List<MetricSnapshot> snapshots) throws IOException {
        if (!exportsDir.exists() && !exportsDir.mkdirs()) {
            throw new IOException("Cannot create export folder " + exportsDir);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File target = new File(exportsDir, "metrics-" + stamp + ".csv");
        int suffix = 1;
        while (target.exists()) {
            target = new File(exportsDir, "metrics-" + stamp + "-" + (suffix++) + ".csv");
        }
        Files.write(target.toPath(), toCsv(snapshots).getBytes(StandardCharsets.UTF_8));
        prune(exportsDir);
        return target;
    }

    static void prune(File exportsDir) {
        File[] files = exportsDir.listFiles();
        if (files == null) {
            return;
        }
        java.util.List<File> csv = new java.util.ArrayList<File>();
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith("metrics-") && f.getName().endsWith(".csv")) {
                csv.add(f);
            }
        }
        if (csv.size() <= MAX_EXPORT_FILES) {
            return;
        }
        File[] sorted = csv.toArray(new File[0]);
        Arrays.sort(sorted);
        for (int i = 0; i < sorted.length - MAX_EXPORT_FILES; i++) {
            if (!sorted[i].delete()) {
                sorted[i].deleteOnExit();
            }
        }
    }
}
