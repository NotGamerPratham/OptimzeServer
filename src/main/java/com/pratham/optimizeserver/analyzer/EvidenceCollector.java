package com.pratham.optimizeserver.analyzer;

import com.pratham.optimizeserver.compatibility.CompatibilityReport;
import com.pratham.optimizeserver.metrics.MetricHistory;
import com.pratham.optimizeserver.metrics.MetricSnapshot;
import com.pratham.optimizeserver.monitoring.TaskMonitor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Aggregates current and historical metric snapshots, task distributions, and runtime metadata
 * into an immutable evidence bundle for diagnostic engines.
 */
public final class EvidenceCollector {

    public static final class EvidenceBundle {
        private final MetricSnapshot current;
        private final List<MetricSnapshot> recentHistory;
        private final Map<String, Integer> pendingTasksByPlugin;
        private final Map<String, Integer> pendingSyncTasksByPlugin;
        private final Map<String, Integer> activeWorkersByPlugin;
        private final CompatibilityReport compatibilityReport;
        private final String growingTaskPlugin;
        private final int growingTaskPluginStreak;

        public EvidenceBundle(MetricSnapshot current,
                              List<MetricSnapshot> recentHistory,
                              Map<String, Integer> pendingTasksByPlugin,
                              Map<String, Integer> activeWorkersByPlugin,
                              CompatibilityReport compatibilityReport) {
            this(current, recentHistory, pendingTasksByPlugin, Collections.<String, Integer>emptyMap(),
                    activeWorkersByPlugin, compatibilityReport, null, 0);
        }

        public EvidenceBundle(MetricSnapshot current,
                              List<MetricSnapshot> recentHistory,
                              Map<String, Integer> pendingTasksByPlugin,
                              Map<String, Integer> pendingSyncTasksByPlugin,
                              Map<String, Integer> activeWorkersByPlugin,
                              CompatibilityReport compatibilityReport,
                              String growingTaskPlugin,
                              int growingTaskPluginStreak) {
            this.current = current;
            this.recentHistory = recentHistory != null ? recentHistory : Collections.<MetricSnapshot>emptyList();
            this.pendingTasksByPlugin = pendingTasksByPlugin != null
                    ? pendingTasksByPlugin
                    : Collections.<String, Integer>emptyMap();
            this.pendingSyncTasksByPlugin = pendingSyncTasksByPlugin != null
                    ? pendingSyncTasksByPlugin
                    : Collections.<String, Integer>emptyMap();
            this.activeWorkersByPlugin = activeWorkersByPlugin != null
                    ? activeWorkersByPlugin
                    : Collections.<String, Integer>emptyMap();
            this.compatibilityReport = compatibilityReport;
            this.growingTaskPlugin = growingTaskPlugin;
            this.growingTaskPluginStreak = growingTaskPluginStreak;
        }

        public MetricSnapshot getCurrent() {
            return current;
        }

        public List<MetricSnapshot> getRecentHistory() {
            return recentHistory;
        }

        public Map<String, Integer> getPendingTasksByPlugin() {
            return pendingTasksByPlugin;
        }

        public Map<String, Integer> getPendingSyncTasksByPlugin() {
            return pendingSyncTasksByPlugin;
        }

        public Map<String, Integer> getActiveWorkersByPlugin() {
            return activeWorkersByPlugin;
        }

        public CompatibilityReport getCompatibilityReport() {
            return compatibilityReport;
        }

        public String getGrowingTaskPlugin() {
            return growingTaskPlugin;
        }

        public int getGrowingTaskPluginStreak() {
            return growingTaskPluginStreak;
        }
    }

    public EvidenceBundle collect(MetricHistory history,
                                  TaskMonitor taskMonitor,
                                  CompatibilityReport compatibilityReport,
                                  int historySamples) {
        MetricSnapshot latest = history != null ? history.getLatest() : null;
        if (latest == null) {
            latest = MetricSnapshot.builder().build();
        }
        List<MetricSnapshot> recent = history != null
                ? history.getRecent(Math.max(5, historySamples))
                : Collections.<MetricSnapshot>emptyList();
        Map<String, Integer> pending = taskMonitor != null
                ? taskMonitor.getPendingTasksByPlugin()
                : Collections.<String, Integer>emptyMap();
        Map<String, Integer> pendingSync = taskMonitor != null
                ? taskMonitor.getPendingSyncTasksByPlugin()
                : Collections.<String, Integer>emptyMap();
        Map<String, Integer> workers = taskMonitor != null
                ? taskMonitor.getActiveWorkersByPlugin()
                : Collections.<String, Integer>emptyMap();
        String growingPlugin = taskMonitor != null ? taskMonitor.getGrowingTaskPlugin() : null;
        int growingStreak = taskMonitor != null ? taskMonitor.getGrowingTaskPluginStreak() : 0;

        return new EvidenceBundle(latest, recent, pending, pendingSync, workers, compatibilityReport, growingPlugin, growingStreak);
    }
}

