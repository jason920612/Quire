package dev.quire.parallel;

import net.minecraft.world.level.entity.LevelCallback;

/**
 * Wraps the level's entity callbacks (tick list, tracker, navigating mobs, scoreboard, waypoints, plugin
 * add/remove events). Invoked on a tile worker, the call is queued on the running tile task and replayed on
 * the main thread, in task order, when the colour phase ends. On the main thread it runs immediately.
 */
public final class DeferringLevelCallback<T> implements LevelCallback<T> {
    private final LevelCallback<T> delegate;

    public DeferringLevelCallback(final LevelCallback<T> delegate) {
        this.delegate = delegate;
    }

    private static boolean defer(final Runnable action) {
        if (Thread.currentThread() instanceof TileWorker worker) {
            final TileScheduler.TileTask task = worker.task;
            if (task != null && !worker.isExclusive()) {
                task.deferred.add(action);
                return true;
            }
            if (worker.isExclusive()) {
                // exclusive: nothing else runs, but plugin events inside must still run on the main thread
                QuireParallel.runOnMain(action);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onCreated(final T entity) {
        if (!defer(() -> this.delegate.onCreated(entity))) {
            this.delegate.onCreated(entity);
        }
    }

    @Override
    public void onDestroyed(final T entity) {
        if (!defer(() -> this.delegate.onDestroyed(entity))) {
            this.delegate.onDestroyed(entity);
        }
    }

    @Override
    public void onTickingStart(final T entity) {
        if (!defer(() -> this.delegate.onTickingStart(entity))) {
            this.delegate.onTickingStart(entity);
        }
    }

    @Override
    public void onTickingEnd(final T entity) {
        if (!defer(() -> this.delegate.onTickingEnd(entity))) {
            this.delegate.onTickingEnd(entity);
        }
    }

    @Override
    public void onTrackingStart(final T entity) {
        if (!defer(() -> this.delegate.onTrackingStart(entity))) {
            this.delegate.onTrackingStart(entity);
        }
    }

    @Override
    public void onTrackingEnd(final T entity) {
        if (!defer(() -> this.delegate.onTrackingEnd(entity))) {
            this.delegate.onTrackingEnd(entity);
        }
    }

    @Override
    public void onSectionChange(final T entity) {
        if (!defer(() -> this.delegate.onSectionChange(entity))) {
            this.delegate.onSectionChange(entity);
        }
    }
}
