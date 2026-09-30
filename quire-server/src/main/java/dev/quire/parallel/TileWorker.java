package dev.quire.parallel;

import ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.IdentityHashMap;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;

/**
 * A tick worker. It is a {@link TickThread} so vanilla/Paper "main thread" assertions pass for the work it
 * is given; the tile scheduler guarantees that work never overlaps with other workers' areas, and hands
 * anything else (out-of-area access, plugin events, global side effects) back for exclusive execution.
 */
public final class TileWorker extends TickThread {
    final TileScheduler scheduler;
    final int index;

    /** Per-thread replacement for shared, non thread safe random generators. */
    public final SimpleThreadUnsafeRandom random = new SimpleThreadUnsafeRandom(System.nanoTime());
    /** Per-thread Level#getBlockRandomPos sequence. */
    public int randValue = (int) System.nanoTime();

    private final java.util.WeakHashMap<Level, CollectingNeighborUpdater> neighborUpdaters = new java.util.WeakHashMap<>(); // weak: unloaded worlds
    private final java.util.WeakHashMap<Level, PathTypeCache> pathTypeCaches = new java.util.WeakHashMap<>();
    /** Block positions whose path type this worker invalidated during the current phase. */
    final it.unimi.dsi.fastutil.longs.LongArrayList pathInvalidations = new it.unimi.dsi.fastutil.longs.LongArrayList();

    /** The task being run, or null when idle. */
    TileScheduler.TileTask task;
    /** The unit (entity, chunk, ...) currently being ticked, for escalation statistics. */
    Object currentUnit;
    /** Set by the coordinator when this worker may touch anything (all other workers are idle or parked). */
    volatile boolean exclusive;
    volatile boolean waitingForExclusive;

    TileWorker(final TileScheduler scheduler, final int index) {
        super("Quire Tile Worker #" + index);
        this.scheduler = scheduler;
        this.index = index;
        this.setDaemon(true);
    }

    @Override
    public void run() {
        this.scheduler.workerLoop(this);
    }

    public CollectingNeighborUpdater neighborUpdater(final Level level, final int maxChainedNeighborUpdates) {
        return this.neighborUpdaters.computeIfAbsent(level, l -> new CollectingNeighborUpdater(l, maxChainedNeighborUpdates));
    }

    public PathTypeCache pathTypeCache(final Level level) {
        return this.pathTypeCaches.computeIfAbsent(level, l -> new PathTypeCache());
    }

    void invalidatePathType(final Level level, final net.minecraft.core.BlockPos pos) {
        final PathTypeCache cache = this.pathTypeCaches.get(level);
        if (cache != null) {
            cache.invalidate(pos);
        }
    }

    boolean isExclusive() {
        return this.exclusive;
    }

    /** Called on this thread when it must not continue without exclusive access. Parks until granted. */
    void escalate(final String reason) {
        if (this.exclusive) {
            return;
        }
        QuireParallel.recordEscalation(this.currentUnit, reason);
        this.scheduler.requestExclusive(this, reason);
        while (!this.exclusive) {
            LockSupport.park(this);
        }
    }

    /** True when (chunkX, chunkZ) of level lies inside the safe area of the current task. */
    boolean canAccess(final Level level, final int chunkX, final int chunkZ) {
        final TileScheduler.TileTask task = this.task;
        return task == null || this.exclusive || (level == task.level
            && chunkX >= task.minSafeChunkX && chunkX <= task.maxSafeChunkX
            && chunkZ >= task.minSafeChunkZ && chunkZ <= task.maxSafeChunkZ);
    }
}
