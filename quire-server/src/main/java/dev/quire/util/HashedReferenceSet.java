package dev.quire.util;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

/**
 * A {@link ReferenceOpenHashSet} whose membership test can take the key's mixed identity hash precomputed
 * ({@link #hash}), so a caller testing the same keys against many sets reads each key's header once.
 */
public final class HashedReferenceSet<K> extends ReferenceOpenHashSet<K> {
    public static int hash(final Object k) {
        return HashCommon.mix(System.identityHashCode(k));
    }

    /** Same as {@code contains(k)} for a non-null {@code k} with {@code hash == hash(k)}. */
    public boolean containsHashed(final Object k, final int hash) {
        final Object[] key = this.key;
        final int mask = this.mask;
        int pos = hash & mask;
        Object curr;
        while ((curr = key[pos]) != null) {
            if (curr == k) {
                return true;
            }
            pos = pos + 1 & mask;
        }
        return false;
    }
}
