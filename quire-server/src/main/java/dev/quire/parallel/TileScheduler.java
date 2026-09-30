package dev.quire.parallel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.world.level.Level;

/**
 * Runs one colour phase of tile tasks on the worker threads.
 *
 * <p>Protocol. {@code active} counts workers that are running task code without exclusive access and are
 * not parked. A worker that must leave its safe area, run plugin code, or touch global state escalates: it
 * leaves the active count and parks. When escalations are pending the coordinator (the server thread) enters
 * exclusive mode: idle workers stop taking new tasks, and once {@code active} reaches zero the escalated
 * workers are released one at a time, in task order, each finishing its whole tile alone. Then normal
 * parallel execution resumes for the remaining queued tasks.
 *
 * <p>Workers increment {@code active} before checking the exclusive flag and taking a task, and the
 * coordinator sets the flag before waiting for {@code active == 0}, so no task can start after the check.
 *
 * <p>Work that must run on the real main thread (plugin listeners, chunk loads) is handed to the coordinator,
 * which is idle while it waits.
 */
public final class TileScheduler {

    public static final class TileTask {
        final Level level;
        final int order;
        final int minSafeChunkX;
        final int minSafeChunkZ;
        final int maxSafeChunkX;
        final int maxSafeChunkZ;
        final Object[] units;
        final int unitCount;
        final Consumer<Object> runner;
        /** Global side effects queued by the worker, replayed on the main thread after the phase. */
        final List<Runnable> deferred = new ArrayList<>();

        public TileTask(final Level level, final int order, final int minSafeChunkX, final int minSafeChunkZ,
                        final int maxSafeChunkX, final int maxSafeChunkZ, final Object[] units, final int unitCount,
                        final Consumer<Object> runner) {
            this.level = level;
            this.order = order;
            this.minSafeChunkX = minSafeChunkX;
            this.minSafeChunkZ = minSafeChunkZ;
            this.maxSafeChunkX = maxSafeChunkX;
            this.maxSafeChunkZ = maxSafeChunkZ;
            this.units = units;
            this.unitCount = unitCount;
            this.runner = runner;
        }
    }

    private static final class MainTask {
        final Runnable runnable;
        final Thread waiter;
        volatile boolean done;
        Throwable error;

        MainTask(final Runnable runnable, final Thread waiter) {
            this.runnable = runnable;
            this.waiter = waiter;
        }
    }

    private final TileWorker[] workers;
    private final ConcurrentLinkedQueue<TileTask> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TileWorker> escalated = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<MainTask> mainTasks = new ConcurrentLinkedQueue<>();
    /** Tasks of the current phase that have not completed. */
    private final AtomicInteger remaining = new AtomicInteger();
    /** Workers running task code non-exclusively and not parked. */
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean exclusiveMode;
    private volatile long generation;
    private volatile Thread coordinator;

    // statistics (coordinator thread only)
    public long phases;
    public long tasksRun;
    public long escalations;

    TileScheduler(final int threads) {
        this.workers = new TileWorker[threads];
        for (int i = 0; i < threads; i++) {
            this.workers[i] = new TileWorker(this, i);
            this.workers[i].start();
        }
    }

    public int threads() {
        return this.workers.length;
    }

    public TileWorker[] workers() {
        return this.workers;
    }

    // ---- worker side ----

    void workerLoop(final TileWorker worker) {
        long seen = 0L;
        while (true) {
            while (this.generation == seen) {
                LockSupport.park(this);
            }
            seen = this.generation;
            while (true) {
                this.active.incrementAndGet();
                if (this.exclusiveMode) {
                    this.active.decrementAndGet();
                    LockSupport.unpark(this.coordinator);
                    while (this.exclusiveMode) {
                        LockSupport.park(this);
                    }
                    continue;
                }
                final TileTask task = this.queue.poll();
                if (task == null) {
                    this.active.decrementAndGet();
                    LockSupport.unpark(this.coordinator);
                    break;
                }
                this.runTask(worker, task);
            }
        }
    }

    private void runTask(final TileWorker worker, final TileTask task) {
        worker.task = task;
        try {
            final Object[] units = task.units;
            for (int i = 0, n = task.unitCount; i < n; i++) {
                worker.currentUnit = units[i];
                task.runner.accept(units[i]);
            }
            worker.currentUnit = null;
        } catch (final Throwable t) {
            this.failure.compareAndSet(null, t);
        } finally {
            worker.task = null;
            if (worker.exclusive) {
                // released by the coordinator; it is waiting for this task to complete
                worker.exclusive = false;
            } else {
                this.active.decrementAndGet();
            }
            this.remaining.decrementAndGet();
            LockSupport.unpark(this.coordinator);
        }
    }

    void requestExclusive(final TileWorker worker, final String reason) {
        worker.waitingForExclusive = true;
        this.escalated.add(worker);
        this.active.decrementAndGet(); // parked from here on
        LockSupport.unpark(this.coordinator);
    }

    /** Runs on the coordinator (main) thread and waits; the caller must already be exclusive. */
    void runOnMain(final TileWorker worker, final Runnable runnable) {
        final MainTask task = new MainTask(runnable, worker);
        this.mainTasks.add(task);
        LockSupport.unpark(this.coordinator);
        while (!task.done) {
            LockSupport.park(this);
        }
        if (task.error != null) {
            sneakyThrow(task.error);
        }
    }

    // ---- coordinator side ----

    private void drainMainTasks() {
        MainTask task;
        while ((task = this.mainTasks.poll()) != null) {
            try {
                task.runnable.run();
            } catch (final Throwable t) {
                task.error = t;
            }
            task.done = true;
            LockSupport.unpark(task.waiter);
        }
    }

    private void runExclusive() {
        this.exclusiveMode = true;
        // wait until nobody runs task code non-exclusively (idle workers see the flag and back off)
        while (this.active.get() != 0) {
            this.drainMainTasks();
            LockSupport.parkNanos(this, 20_000L);
        }
        final List<TileWorker> waiting = new ArrayList<>();
        TileWorker w;
        while ((w = this.escalated.poll()) != null) {
            waiting.add(w);
        }
        waiting.sort(Comparator.comparingInt(worker -> worker.task == null ? Integer.MAX_VALUE : worker.task.order));
        for (final TileWorker worker : waiting) {
            this.escalations++;
            final TileTask task = worker.task;
            worker.waitingForExclusive = false;
            worker.exclusive = true;
            LockSupport.unpark(worker);
            // the worker finishes its whole tile alone; it cannot escalate again, only hand work to us
            while (worker.task == task && task != null) {
                this.drainMainTasks();
                LockSupport.parkNanos(this, 20_000L);
            }
        }
        this.exclusiveMode = false;
        for (final TileWorker worker : this.workers) {
            LockSupport.unpark(worker);
        }
    }

    /** Runs the tasks of one colour phase; returns when all of them completed. */
    public void runPhase(final List<TileTask> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        this.phases++;
        this.tasksRun += tasks.size();
        this.coordinator = Thread.currentThread();
        this.remaining.set(tasks.size());
        this.queue.addAll(tasks);
        this.generation++;
        for (final TileWorker worker : this.workers) {
            LockSupport.unpark(worker);
        }

        while (this.remaining.get() != 0) {
            this.drainMainTasks();
            if (!this.escalated.isEmpty()) {
                this.runExclusive();
                continue;
            }
            LockSupport.parkNanos(this, 200_000L);
        }
        this.drainMainTasks();

        final Throwable t = this.failure.getAndSet(null);
        if (t != null) {
            sneakyThrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(final Throwable t) throws T {
        throw (T) t;
    }
}
