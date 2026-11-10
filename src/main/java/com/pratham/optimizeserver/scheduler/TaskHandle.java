package com.pratham.optimizeserver.scheduler;

/**
 * Platform-neutral handle to a scheduled one-shot or repeating task.
 */
public interface TaskHandle {

    /**
     * Cancels the scheduled task if still active.
     */
    void cancel();

    /**
     * Returns true if the task has been cancelled.
     */
    boolean isCancelled();
}
