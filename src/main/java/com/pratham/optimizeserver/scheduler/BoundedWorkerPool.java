package com.pratham.optimizeserver.scheduler;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded asynchronous worker and timer pool with exception isolation and telemetry counters.
 */
public final class BoundedWorkerPool {

    private final Logger logger;
    private final ThreadPoolExecutor workerExecutor;
    private final ScheduledThreadPoolExecutor timerExecutor;
    private final int queueCapacity;
    private final AtomicLong submittedTasks = new AtomicLong(0L);
    private final AtomicLong completedTasks = new AtomicLong(0L);
    private final AtomicLong rejectedTasks = new AtomicLong(0L);
    private final AtomicLong failedTasks = new AtomicLong(0L);
    private volatile boolean shuttingDown = false;

    public BoundedWorkerPool(final Logger logger,
                             int coreThreads,
                             int maxThreads,
                             int queueCapacity,
                             long keepAliveSeconds) {
        this.logger = logger;
        int safeCore = Math.max(1, coreThreads);
        int safeMax = Math.max(safeCore, maxThreads);
        this.queueCapacity = Math.max(16, queueCapacity);

        final AtomicInteger workerIndex = new AtomicInteger(1);
        ThreadFactory workerFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "OptimizeServer-Worker-" + workerIndex.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        };

        RejectedExecutionHandler rejectedHandler = new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                rejectedTasks.incrementAndGet();
            }
        };

        this.workerExecutor = new ThreadPoolExecutor(
                safeCore,
                safeMax,
                Math.max(10L, keepAliveSeconds),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(this.queueCapacity),
                workerFactory,
                rejectedHandler
        );
        this.workerExecutor.allowCoreThreadTimeOut(true);

        final AtomicInteger timerIndex = new AtomicInteger(1);
        ThreadFactory timerFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "OptimizeServer-Timer-" + timerIndex.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            }
        };

        this.timerExecutor = new ScheduledThreadPoolExecutor(1, timerFactory);
        this.timerExecutor.setRemoveOnCancelPolicy(true);
    }

    public boolean submitAsync(final Runnable task) {
        if (shuttingDown || task == null) {
            return false;
        }
        submittedTasks.incrementAndGet();
        try {
            workerExecutor.execute(wrapSafe(task));
            return true;
        } catch (RejectedExecutionException ex) {
            rejectedTasks.incrementAndGet();
            return false;
        }
    }

    public TaskHandle scheduleAsyncTimer(final Runnable task, long initialDelayMs, long periodMs) {
        if (shuttingDown || task == null) {
            return new NoOpTaskHandle();
        }
        final Runnable wrapped = new Runnable() {
            @Override
            public void run() {
                submitAsync(task);
            }
        };
        final ScheduledFuture<?> future = timerExecutor.scheduleAtFixedRate(
                wrapped,
                Math.max(0L, initialDelayMs),
                Math.max(1L, periodMs),
                TimeUnit.MILLISECONDS
        );
        return new FutureTaskHandle(future);
    }

    public TaskHandle scheduleAsyncLater(final Runnable task, long delayMs) {
        if (shuttingDown || task == null) {
            return new NoOpTaskHandle();
        }
        final Runnable wrapped = new Runnable() {
            @Override
            public void run() {
                submitAsync(task);
            }
        };
        final ScheduledFuture<?> future = timerExecutor.schedule(
                wrapped,
                Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS
        );
        return new FutureTaskHandle(future);
    }

    public synchronized void adjustPoolLimits(int newCore, int newMax) {
        int safeCore = Math.max(1, newCore);
        int safeMax = Math.max(safeCore, newMax);
        if (safeCore > workerExecutor.getMaximumPoolSize()) {
            workerExecutor.setMaximumPoolSize(safeMax);
            workerExecutor.setCorePoolSize(safeCore);
        } else {
            workerExecutor.setCorePoolSize(safeCore);
            workerExecutor.setMaximumPoolSize(safeMax);
        }
    }

    public int getCorePoolSize() {
        return workerExecutor.getCorePoolSize();
    }

    public int getMaximumPoolSize() {
        return workerExecutor.getMaximumPoolSize();
    }

    public int getActiveCount() {
        return workerExecutor.getActiveCount();
    }

    public int getQueueSize() {
        return workerExecutor.getQueue().size();
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public long getSubmittedTasks() {
        return submittedTasks.get();
    }

    public long getCompletedTasks() {
        return completedTasks.get();
    }

    public long getRejectedTasks() {
        return rejectedTasks.get();
    }

    public long getFailedTasks() {
        return failedTasks.get();
    }

    public void shutdownGracefully(long timeoutSeconds) {
        shuttingDown = true;
        timerExecutor.shutdownNow();
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(Math.max(1L, timeoutSeconds), TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public ScheduledExecutorService getTimerExecutor() {
        return timerExecutor;
    }

    private Runnable wrapSafe(final Runnable delegate) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    delegate.run();
                    completedTasks.incrementAndGet();
                } catch (Throwable t) {
                    failedTasks.incrementAndGet();
                    if (logger != null) {
                        logger.log(Level.WARNING, "[OptimizeServer] Uncaught exception in bounded async worker", t);
                    }
                }
            }
        };
    }

    private static final class FutureTaskHandle implements TaskHandle {
        private final Future<?> future;
        private volatile boolean cancelled = false;

        FutureTaskHandle(Future<?> future) {
            this.future = future;
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (future != null) {
                future.cancel(false);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled || (future != null && future.isCancelled());
        }
    }

    public static final class NoOpTaskHandle implements TaskHandle {
        private volatile boolean cancelled = true;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
