package dev.quire.memory;

import net.minecraft.util.SimpleBitStorage;

/**
 * Block storage kept deflated. Cheap to hold (terrain deflates ~5x) but a single read costs a partial inflate,
 * so a container read repeatedly within a short window is promoted (inflated for good).
 */
public final class CompressedBitStorage extends FrozenBitStorage {

    final byte[] packed;

    public CompressedBitStorage(final Owner owner, final byte[] packed, final int bits, final int size, final int rawLength) {
        super(owner, bits, size, rawLength);
        this.packed = packed;
    }

    @Override
    public int packedBytes() {
        return this.packed.length;
    }

    @Override
    protected long[] decode() {
        return SectionCompression.inflate(this.packed, this.rawLength);
    }

    /** Uncached reads within the current window; a section read from repeatedly is inflated for good. */
    private int peekMisses;
    private int peekWindowStart;
    private static final int PROMOTE_AFTER_MISSES = 3;
    private static final int PROMOTE_WINDOW_TICKS = 40;

    @Override
    protected int frozenGet(final int index) {
        final Object[] cache = PEEK.get();
        final int slot = (System.identityHashCode(this) & (PEEK_CACHE - 1)) << 1;
        if (cache[slot] == this) {
            return ((SimpleBitStorage) cache[slot + 1]).get(index);
        }
        final int now = net.minecraft.server.MinecraftServer.currentTick;
        if (now - this.peekWindowStart > PROMOTE_WINDOW_TICKS) {
            this.peekWindowStart = now;
            this.peekMisses = 0;
        }
        if (++this.peekMisses >= PROMOTE_AFTER_MISSES) {
            // read repeatedly: it is hot. Prefer a sparse (compact, cheap to read) form over a plain one
            final int value = this.owner.quire$promote(this, index);
            if (value >= 0) {
                return value;
            }
            return this.owner.quire$inflate(this).get(index);
        }
        return this.readSingle(index);
    }

    /** One entry without inflating the container: inflates only up to the long holding it, into a scratch buffer. */
    private int readSingle(final int index) {
        final long start = System.nanoTime();
        final int valuesPerLong = 64 / this.bits;
        final int cell = index / valuesPerLong;
        final int shift = (index - cell * valuesPerLong) * this.bits;
        final long word = SectionCompression.inflateLong(this.packed, cell);
        SectionCompression.SINGLE_READS.increment();
        SectionCompression.SINGLE_READ_NANOS.add(System.nanoTime() - start);
        return (int) ((word >>> shift) & ((1L << this.bits) - 1L));
    }

    // Read-only access that does not inflate the container: a tiny per-thread cache of decompressed copies.
    // The compressed contents never change (writes inflate and replace the storage), so a copy stays valid.
    private static final int PEEK_CACHE = 16;
    private static final ThreadLocal<Object[]> PEEK = ThreadLocal.withInitial(() -> new Object[PEEK_CACHE * 2]);

    @Override
    protected int frozenPeek(final int index) {
        final Object[] cache = PEEK.get();
        final int slot = (System.identityHashCode(this) & (PEEK_CACHE - 1)) << 1;
        if (cache[slot] == this) {
            return ((SimpleBitStorage) cache[slot + 1]).get(index);
        }
        final SimpleBitStorage copy = new SimpleBitStorage(this.bits, this.size, this.decode());
        cache[slot] = this;
        cache[slot + 1] = copy;
        return copy.get(index);
    }
}
