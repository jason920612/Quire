package dev.quire.memory;

/**
 * Immutable random-access sparse encoding of 4096 small values (at most 8 bits) laid out as a 16x16x16 cube,
 * index {@code x | z << 4 | y << 8} (the layout of both chunk section block storage and light nibbles).
 *
 * <p>The cube is split into 64 blocks of 4x4x4. A uniform block stores its value. A mixed block is stored in
 * whichever of two forms is smaller:
 * <ul>
 * <li>cells: split again into 8 cells of 2x2x2, each either uniform (value) or raw (8 entries, {@code bits} bytes);</li>
 * <li>local palette: the block's few distinct values, and 64 entries of {@code k} bits indexing them.</li>
 * </ul>
 * Terrain and light are spatially coherent, so this is a fraction of the plain size while a read is a few
 * shifts and array loads.
 *
 * <p>Layout of {@link #blob}: 64 bytes (per 4^3 block: value if uniform, else mixed-block ordinal), 3 bytes per
 * mixed block (16-bit payload offset, mode: 0 = cells, k = local palette of 2^k entries), the payloads, one pad
 * byte. Cells payload: cell mask, 8 cell values, raw cells. Local payload: 2^k palette values, 64 * k bits.
 */
public final class Sparse4096 {
    private static final int MIXED_ENTRY = 3;
    private static final int MAX_LOCAL_BITS = 4;

    private final long mixedBlocks;
    private final byte[] blob;
    private final int bits;

    private Sparse4096(final long mixedBlocks, final byte[] blob, final int bits) {
        this.mixedBlocks = mixedBlocks;
        this.blob = blob;
        this.bits = bits;
    }

    /** Encodes {@code v} (values below {@code 1 << bits}), or returns null when the blob would exceed {@code maxBytes}. */
    public static Sparse4096 encode(final int[] v, final int bits, final int maxBytes) {
        if (bits <= 0 || bits > 8) {
            return null;
        }
        final byte[] head = new byte[64];
        final byte[] entries = new byte[64 * MIXED_ENTRY];
        final byte[] payload = new byte[64 * (9 + 8 * bits) + 1];
        final byte[] cells = new byte[9 + 8 * bits + 1];
        final int[] local = new int[64];
        final int[] palette = new int[1 << MAX_LOCAL_BITS];
        long mixedBlocks = 0L;
        int mixed = 0;
        int payloadLength = 0;
        for (int blk = 0; blk < 64; blk++) {
            final int bx = (blk & 3) << 2, bz = ((blk >> 2) & 3) << 2, by = (blk >> 4) << 2;
            final int first = v[(by << 8) | (bz << 4) | bx];
            if (uniform(v, bx, by, bz, 4, first)) {
                head[blk] = (byte) first;
                continue;
            }
            mixedBlocks |= 1L << blk;
            head[blk] = (byte) mixed;

            // cells form
            java.util.Arrays.fill(cells, (byte) 0);
            int cellMask = 0;
            int cellsLength = 9;
            for (int c = 0; c < 8; c++) {
                final int cx = bx + ((c & 1) << 1), cz = bz + (((c >> 1) & 1) << 1), cy = by + ((c >> 2) << 1);
                final int f = v[(cy << 8) | (cz << 4) | cx];
                if (uniform(v, cx, cy, cz, 2, f)) {
                    cells[1 + c] = (byte) f;
                    continue;
                }
                cellMask |= 1 << c;
                for (int j = 0; j < 8; j++) {
                    pack(cells, cellsLength, j, bits, v[((cy + (j >> 2)) << 8) | ((cz + ((j >> 1) & 1)) << 4) | (cx + (j & 1))]);
                }
                cellsLength += bits;
            }
            cells[0] = (byte) cellMask;

            // local palette form
            int distinct = 0;
            boolean localPossible = true;
            for (int i = 0; i < 64 && localPossible; i++) {
                final int value = v[(((by + (i >> 4)) << 8) | ((bz + ((i >> 2) & 3)) << 4) | (bx + (i & 3)))];
                int id = -1;
                for (int p = 0; p < distinct; p++) {
                    if (palette[p] == value) {
                        id = p;
                        break;
                    }
                }
                if (id < 0) {
                    if (distinct == palette.length) {
                        localPossible = false;
                        break;
                    }
                    palette[distinct] = value;
                    id = distinct++;
                }
                local[i] = id;
            }
            final int k = localPossible ? 32 - Integer.numberOfLeadingZeros(distinct - 1) : 0;
            final int localLength = localPossible ? (1 << k) + 8 * k : Integer.MAX_VALUE;

            final int e = mixed * MIXED_ENTRY;
            entries[e] = (byte) payloadLength;
            entries[e + 1] = (byte) (payloadLength >>> 8);
            if (localLength < cellsLength) {
                entries[e + 2] = (byte) k;
                for (int p = 0; p < distinct; p++) {
                    payload[payloadLength + p] = (byte) palette[p];
                }
                final int data = payloadLength + (1 << k);
                for (int i = 0; i < 64; i++) {
                    pack(payload, data, i, k, local[i]);
                }
                payloadLength += localLength;
            } else {
                entries[e + 2] = 0;
                System.arraycopy(cells, 0, payload, payloadLength, cellsLength);
                payloadLength += cellsLength;
            }
            mixed++;
            if (64 + mixed * MIXED_ENTRY + payloadLength + 1 > maxBytes) {
                return null;
            }
        }
        final int payloadStart = 64 + mixed * MIXED_ENTRY;
        final byte[] blob = new byte[payloadStart + payloadLength + 1]; // +1: reads load two bytes
        System.arraycopy(head, 0, blob, 0, 64);
        System.arraycopy(entries, 0, blob, 64, mixed * MIXED_ENTRY);
        System.arraycopy(payload, 0, blob, payloadStart, payloadLength);
        return new Sparse4096(mixedBlocks, blob, bits);
    }

    /** Packs entry {@code index} of {@code width} bits at byte offset {@code start} (value spans at most two bytes). */
    private static void pack(final byte[] out, final int start, final int index, final int width, final int value) {
        final int bit = index * width;
        final int packed = value << (bit & 7);
        out[start + (bit >> 3)] |= (byte) packed;
        out[start + (bit >> 3) + 1] |= (byte) (packed >>> 8);
    }

    private static boolean uniform(final int[] v, final int bx, final int by, final int bz, final int size, final int first) {
        for (int y = by; y < by + size; y++) {
            for (int z = bz; z < bz + size; z++) {
                final int row = (y << 8) | (z << 4);
                for (int x = bx; x < bx + size; x++) {
                    if (v[row | x] != first) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int unpack(final byte[] blob, final int start, final int index, final int width) {
        final int bit = index * width;
        final int at = start + (bit >> 3);
        return (((blob[at] & 0xFF) | ((blob[at + 1] & 0xFF) << 8)) >>> (bit & 7)) & ((1 << width) - 1);
    }

    public int get(final int index) {
        final int x = index & 15, z = (index >> 4) & 15, y = index >> 8;
        final byte[] blob = this.blob;
        final int blk = ((y >> 2) << 4) | ((z >> 2) << 2) | (x >> 2);
        if ((this.mixedBlocks & (1L << blk)) == 0L) {
            return blob[blk] & 0xFF;
        }
        final int e = 64 + (blob[blk] & 0xFF) * MIXED_ENTRY;
        final int payload = 64 + Long.bitCount(this.mixedBlocks) * MIXED_ENTRY + ((blob[e] & 0xFF) | ((blob[e + 1] & 0xFF) << 8));
        final int k = blob[e + 2];
        if (k != 0) {
            final int local = ((y & 3) << 4) | ((z & 3) << 2) | (x & 3);
            return blob[payload + unpack(blob, payload + (1 << k), local, k)] & 0xFF;
        }
        final int cellMask = blob[payload] & 0xFF;
        final int c = (((y >> 1) & 1) << 2) | (((z >> 1) & 1) << 1) | ((x >> 1) & 1);
        if ((cellMask & (1 << c)) == 0) {
            return blob[payload + 1 + c] & 0xFF;
        }
        final int rank = Integer.bitCount(cellMask & ((1 << c) - 1));
        return unpack(blob, payload + 9 + rank * this.bits, ((y & 1) << 2) | ((z & 1) << 1) | (x & 1), this.bits);
    }

    public int bytes() {
        return this.blob.length;
    }
}
