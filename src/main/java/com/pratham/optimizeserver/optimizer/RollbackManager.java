package com.pratham.optimizeserver.optimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages rollback operations and audit history for optimization modules.
 */
public final class RollbackManager {

    public static final class RollbackRecord {
        private final String moduleId;
        private final long timestampMillis;
        private final boolean success;
        private final String reason;

        public RollbackRecord(String moduleId, long timestampMillis, boolean success, String reason) {
            this.moduleId = moduleId;
            this.timestampMillis = timestampMillis;
            this.success = success;
            this.reason = reason;
        }

        public String getModuleId() {
            return moduleId;
        }

        public long getTimestampMillis() {
            return timestampMillis;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getReason() {
            return reason;
        }
    }

    private final Logger logger;
    private final List<RollbackRecord> history = new CopyOnWriteArrayList<RollbackRecord>();

    public RollbackManager(Logger logger) {
        this.logger = logger != null ? logger : Logger.getLogger("OptimizeServer");
    }

    public boolean rollbackModule(OptimizationModule module, String reason) {
        if (module == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        try {
            boolean ok = module.rollback();
            record(new RollbackRecord(module.getId(), now, ok, reason));
            return ok;
        } catch (Throwable t) {
            logger.log(Level.WARNING, "[OptimizeServer] Failed to rollback module " + module.getId(), t);
            record(new RollbackRecord(module.getId(), now, false, "Exception: " + t.getMessage()));
            return false;
        }
    }

    public int rollbackAll(Iterable<OptimizationModule> modules, String reason) {
        int count = 0;
        if (modules == null) {
            return 0;
        }
        for (OptimizationModule mod : modules) {
            if (mod != null && mod.isEnabled()) {
                if (rollbackModule(mod, reason)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void record(RollbackRecord entry) {
        history.add(entry);
        while (history.size() > 100) {
            history.remove(0);
        }
    }

    public List<RollbackRecord> getHistory() {
        return Collections.unmodifiableList(new ArrayList<RollbackRecord>(history));
    }
}

