package dev.quire.memory;

import java.util.function.IntConsumer;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;

/**
 * A block storage kept in a compact read-only encoding. It is a lazy proxy: mutating inflates it once into a
 * normal {@link SimpleBitStorage}, installs that in the owning container (so later accesses take the normal
 * fast path) and delegates. Bulk read-only access (packet writing, saving, block counting) decodes into a
 * temporary array without inflating the container.
 *
 * <p>The proxy always delegates to the storage inflated from itself (same palette), never to whatever the
 * container holds later, so a caller holding an old {@code Data} sees a consistent palette/storage pair.
 */
public abstract class FrozenBitStorage implements BitStorage {

    public interface Owner {
        /** Inflates (once) the frozen storage currently installed, returning the live storage. */
        SimpleBitStorage quire$inflate(FrozenBitStorage frozen);
    }

    protected final Owner owner;
    protected final int bits;
    protected final int size;
    protected final int rawLength;
    protected volatile SimpleBitStorage inflated;

    protected FrozenBitStorage(final Owner owner, final int bits, final int size, final int rawLength) {
        this.owner = owner;
        this.bits = bits;
        this.size = size;
        this.rawLength = rawLength;
    }

    /** Heap bytes of the encoded form (excluding the object header). */
    public abstract int packedBytes();

    /** Decodes the full raw long[] (SimpleBitStorage layout). */
    protected abstract long[] decode();

    /** Reads one entry without inflating the container; may promote the container if it is read a lot. */
    protected abstract int frozenGet(int index);

    /** Reads one entry without ever inflating the container. */
    protected abstract int frozenPeek(int index);

    public final int rawLength() {
        return this.rawLength;
    }

    /** Called by the owner under its lock. */
    public final SimpleBitStorage inflateForOwner() {
        SimpleBitStorage live = this.inflated;
        if (live == null) {
            live = new SimpleBitStorage(this.bits, this.size, this.decode());
            this.inflated = live;
        }
        return live;
    }

    protected final SimpleBitStorage live() {
        final SimpleBitStorage live = this.inflated;
        return live != null ? live : this.owner.quire$inflate(this);
    }

    @Override
    public final int getAndSet(final int index, final int value) {
        return this.live().getAndSet(index, value);
    }

    @Override
    public final void set(final int index, final int value) {
        this.live().set(index, value);
    }

    @Override
    public final void fill(final int value) {
        this.live().fill(value);
    }

    @Override
    public final int get(final int index) {
        final SimpleBitStorage live = this.inflated;
        return live != null ? live.get(index) : this.frozenGet(index);
    }

    public final int peek(final int index) {
        final SimpleBitStorage live = this.inflated;
        return live != null ? live.get(index) : this.frozenPeek(index);
    }

    /** Read-only view (temporary copy unless already inflated). Writers must inflate through the owner first. */
    @Override
    public final long[] getRaw() {
        final SimpleBitStorage live = this.inflated;
        return live != null ? live.getRaw() : this.decode();
    }

    @Override
    public final int getSize() {
        return this.size;
    }

    @Override
    public final int getBits() {
        return this.bits;
    }

    @Override
    public final void getAll(final IntConsumer output) {
        this.temporary().getAll(output);
    }

    @Override
    public final void unpack(final int[] output) {
        this.temporary().unpack(output);
    }

    @Override
    public final BitStorage copy() {
        return this.temporary().copy();
    }

    @Override
    public final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<it.unimi.dsi.fastutil.shorts.ShortArrayList> moonrise$countEntries() {
        return this.temporary().moonrise$countEntries();
    }

    protected final SimpleBitStorage temporary() {
        final SimpleBitStorage live = this.inflated;
        return live != null ? live : new SimpleBitStorage(this.bits, this.size, this.decode());
    }
}
