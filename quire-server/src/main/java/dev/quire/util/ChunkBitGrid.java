package dev.quire.util;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * A bit grid mirroring a set of chunk keys inside a 128x128 chunk window, for membership tests without hashing.
 * The window is re-centred (and rebuilt from the set) when a key outside it is added; keys outside the window are
 * answered by the set itself, so {@link #contains} always equals {@code set.contains}.
 */
public final class ChunkBitGrid {
    private static final int SIZE = 128; // chunks per side
    private static final int WORDS_PER_ROW = SIZE / 64;
    private final long[] bits = new long[SIZE * WORDS_PER_ROW];
    private int originX = Integer.MIN_VALUE / 2, originZ = Integer.MIN_VALUE / 2; // window [origin, origin + SIZE)
    private int outside; // keys of the set outside the window

    private int index(final int chunkX, final int chunkZ) {
        final int dx = chunkX - this.originX, dz = chunkZ - this.originZ;
        if (dx < 0 || dz < 0 || dx >= SIZE || dz >= SIZE) {
            return -1;
        }
        return dz * SIZE + dx;
    }

    public boolean contains(final LongSet set, final long key) {
        final int i = this.index(CoordinateUtils.getChunkX(key), CoordinateUtils.getChunkZ(key));
        if (i < 0) {
            return this.outside != 0 && set.contains(key);
        }
        return (this.bits[i >>> 6] & (1L << i)) != 0L;
    }

    /** Call after {@code set.add(key)} succeeded. */
    public void added(final LongSet set, final long key) {
        final int chunkX = CoordinateUtils.getChunkX(key), chunkZ = CoordinateUtils.getChunkZ(key);
        int i = this.index(chunkX, chunkZ);
        if (i < 0) {
            this.recentre(set, chunkX, chunkZ);
            return; // rebuilt from the set, which already holds key
        }
        this.bits[i >>> 6] |= 1L << i;
    }

    /** Call after {@code set.remove(key)} succeeded. */
    public void removed(final long key) {
        final int i = this.index(CoordinateUtils.getChunkX(key), CoordinateUtils.getChunkZ(key));
        if (i >= 0) {
            this.bits[i >>> 6] &= ~(1L << i);
        } else {
            --this.outside;
        }
    }

    private void recentre(final LongSet set, final int chunkX, final int chunkZ) {
        this.originX = chunkX - SIZE / 2;
        this.originZ = chunkZ - SIZE / 2;
        java.util.Arrays.fill(this.bits, 0L);
        this.outside = 0;
        for (final LongIterator it = set.iterator(); it.hasNext(); ) {
            final long key = it.nextLong();
            final int i = this.index(CoordinateUtils.getChunkX(key), CoordinateUtils.getChunkZ(key));
            if (i >= 0) {
                this.bits[i >>> 6] |= 1L << i;
            } else {
                ++this.outside;
            }
        }
    }
}
