package dev.quire.memory;

import net.minecraft.util.SimpleBitStorage;

/**
 * Chunk section block storage in the random-access {@link Sparse4096} encoding (at most 8 bits per entry).
 * Reads are cheap enough to keep hot sections encoded; writes inflate through the owner.
 */
public final class SparseBitStorage extends FrozenBitStorage {

    private final Sparse4096 sparse;
    private static final ThreadLocal<int[]> DECODE_BUFFER = ThreadLocal.withInitial(() -> new int[4096]);

    private SparseBitStorage(final Owner owner, final int bits, final int rawLength, final Sparse4096 sparse) {
        super(owner, bits, 4096, rawLength);
        this.sparse = sparse;
    }

    /** The sparse form of {@code storage}, or null when it would not be at most {@code maxRatio} of the raw size. */
    public static SparseBitStorage encode(final Owner owner, final SimpleBitStorage storage, final double maxRatio) {
        final int bits = storage.getBits();
        if (bits == 0 || bits > 8 || storage.getSize() != 4096) {
            return null;
        }
        final int[] v = new int[4096];
        storage.unpack(v);
        final int rawLength = storage.getRaw().length;
        final Sparse4096 sparse = Sparse4096.encode(v, bits, (int) (rawLength * 8 * maxRatio));
        return sparse == null ? null : new SparseBitStorage(owner, bits, rawLength, sparse);
    }

    @Override
    public int packedBytes() {
        return this.sparse.bytes();
    }

    @Override
    protected long[] decode() {
        final int bits = this.bits;
        final int valuesPerLong = 64 / bits;
        final long[] raw = new long[this.rawLength];
        final int[] values = DECODE_BUFFER.get();
        this.sparse.decodeAll(values);
        int i = 0;
        for (int cellIndex = 0; cellIndex < raw.length; cellIndex++) {
            long word = 0L;
            final int end = Math.min(4096, i + valuesPerLong);
            for (int shift = 0; i < end; i++, shift += bits) {
                word |= (long) values[i] << shift;
            }
            raw[cellIndex] = word;
        }
        return raw;
    }

    @Override
    protected int frozenGet(final int index) {
        return this.sparse.get(index);
    }

    @Override
    protected int frozenPeek(final int index) {
        return this.sparse.get(index);
    }
}
