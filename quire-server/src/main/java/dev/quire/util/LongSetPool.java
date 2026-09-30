package dev.quire.util;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Per-thread pool of scratch {@link LongOpenHashSet}s for short-lived, non-escaping use.
 * Depth-indexed so reentrant callers each get their own set.
 */
public final class LongSetPool {
    private static final int MAX_RETAINED_CAPACITY = 256;
    private static final ThreadLocal<LongSetPool> POOLS = ThreadLocal.withInitial(LongSetPool::new);

    private LongOpenHashSet[] sets = new LongOpenHashSet[4];
    private int depth;

    private LongSetPool() {
    }

    public static LongSetPool get() {
        return POOLS.get();
    }

    public LongOpenHashSet acquire() {
        if (this.depth == this.sets.length) {
            this.sets = java.util.Arrays.copyOf(this.sets, this.depth * 2);
        }
        LongOpenHashSet set = this.sets[this.depth];
        if (set == null) {
            set = this.sets[this.depth] = new LongOpenHashSet();
        }
        this.depth++;
        return set;
    }

    public void release(final LongOpenHashSet set) {
        final int index = --this.depth;
        if (this.sets[index] != set) {
            throw new IllegalStateException("LongSetPool released out of order");
        }
        if (set.size() > MAX_RETAINED_CAPACITY) {
            // do not keep an oversized table around (clear() cost scales with capacity)
            this.sets[index] = null;
        } else {
            set.clear();
        }
    }
}
