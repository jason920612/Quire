package dev.quire.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Lossless compression of chunk section block storage (-Dquire.compressSections=true).
 * Raw deflate at the fastest level: terrain sections shrink several times and inflate in microseconds.
 */
public final class SectionCompression {
    // -Dquire.compressSections=sparse (random-access sparse encoding) | deflate (or true) | off
    private static final String MODE = System.getProperty("quire.compressSections", "off");
    public static final boolean SPARSE = MODE.equalsIgnoreCase("sparse");
    public static final boolean DEFLATE = MODE.equalsIgnoreCase("deflate") || MODE.equalsIgnoreCase("true");
    public static final boolean ENABLED = SPARSE || DEFLATE;
    /** Debug: check every encoding decodes back to the original. */
    public static final boolean VERIFY = Boolean.getBoolean("quire.compressSections.verify");
    /** Only keep the sparse form when it is at most this fraction of the raw size. */
    public static final double SPARSE_MAX_RATIO = 0.9;
    /** Only keep the compressed form when it is at most this fraction of the raw size. */
    private static final double MAX_RATIO = 0.6;

    private static final ThreadLocal<Deflater> DEFLATERS = ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED, true));
    private static final ThreadLocal<Inflater> INFLATERS = ThreadLocal.withInitial(() -> new Inflater(true));
    private static final ThreadLocal<byte[]> BUFFERS = ThreadLocal.withInitial(() -> new byte[8 * 4096 + 64]);

    // statistics
    public static final java.util.concurrent.atomic.LongAdder COMPRESSED = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder INFLATED = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder FAILED = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder PACKED_BYTES = new java.util.concurrent.atomic.LongAdder();

    private SectionCompression() {
    }

    // debug: who inflates sections (-Dquire.compressSections.trace=true)
    public static final boolean TRACE = Boolean.getBoolean("quire.compressSections.trace");
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> INFLATE_CALLERS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void traceInflate() {
        final String caller = StackWalker.getInstance().walk(frames -> frames
            .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
            .filter(n -> !n.startsWith("PalettedContainer") && !n.startsWith("CompressedBitStorage") && !n.startsWith("FrozenBitStorage") && !n.startsWith("SparseBitStorage") && !n.startsWith("SectionCompression"))
            .limit(4)
            .reduce((a, b) -> a + " <- " + b).orElse("?"));
        INFLATE_CALLERS.computeIfAbsent(caller, k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    /** Deflated bytes of {@code raw}, or null when it does not compress well enough. */
    public static byte[] deflate(final long[] raw) {
        final byte[] in = new byte[raw.length * 8];
        ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().put(raw);
        final Deflater deflater = DEFLATERS.get();
        deflater.reset();
        deflater.setInput(in);
        deflater.finish();
        final byte[] buffer = BUFFERS.get();
        final int limit = (int) (in.length * MAX_RATIO);
        int len = 0;
        while (!deflater.finished()) {
            len += deflater.deflate(buffer, len, buffer.length - len);
            if (len > limit) {
                return null;
            }
        }
        final byte[] out = java.util.Arrays.copyOf(buffer, len);
        COMPRESSED.increment();
        PACKED_BYTES.add(len);
        return out;
    }

    public static final java.util.concurrent.atomic.LongAdder SINGLE_READS = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder SINGLE_READ_NANOS = new java.util.concurrent.atomic.LongAdder();

    /** The {@code cell}-th long of the raw data, inflating only the prefix up to it. */
    public static long inflateLong(final byte[] packed, final int cell) {
        final Inflater inflater = INFLATERS.get();
        inflater.reset();
        inflater.setInput(packed);
        final byte[] out = BUFFERS.get();
        final int need = (cell + 1) * 8;
        try {
            int off = 0;
            while (off < need) {
                final int n = inflater.inflate(out, off, need - off);
                if (n == 0 && (inflater.finished() || inflater.needsInput())) {
                    break;
                }
                off += n;
            }
            if (off != need) {
                throw new IllegalStateException("Corrupt compressed section: " + off + " of " + need + " bytes");
            }
        } catch (final DataFormatException e) {
            throw new IllegalStateException("Corrupt compressed section", e);
        }
        return (long) LONG_LE.get(out, cell * 8);
    }

    private static final java.lang.invoke.VarHandle LONG_LE = java.lang.invoke.MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    public static long[] inflate(final byte[] packed, final int rawLength) {
        final Inflater inflater = INFLATERS.get();
        inflater.reset();
        inflater.setInput(packed);
        final byte[] out = new byte[rawLength * 8];
        try {
            int off = 0;
            while (off < out.length) {
                final int n = inflater.inflate(out, off, out.length - off);
                if (n == 0 && (inflater.finished() || inflater.needsInput())) {
                    break;
                }
                off += n;
            }
            if (off != out.length) {
                throw new IllegalStateException("Corrupt compressed section: " + off + " of " + out.length + " bytes");
            }
        } catch (final DataFormatException e) {
            throw new IllegalStateException("Corrupt compressed section", e);
        }
        final long[] raw = new long[rawLength];
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(raw);
        return raw;
    }
}
