package dev.quire.worldgen;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.generator.SimpleDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction;

/**
 * Proves, per chunk, the height above which the final density is negative for every block, so the density of those
 * blocks (all their 3D noise) is never computed; they are filled as the aquifer fills any negative density there.
 *
 * <p>Upper bound of the final density at a cell corner: inside {@code interpolated} nodes (whose values are only
 * taken at cell corners) every subtree that does not depend on all three axes (terrain splines of continentalness,
 * erosion, ridges; height gradients) is replaced by its exact value at the corner; 3D subtrees keep their interval
 * ({@link DensityFunction#range()}, interval arithmetic over the tree), except that a {@code range_choice} takes only
 * the branch its input interval selects. Interpolation is a convex combination of the corners and everything outside
 * the interpolated nodes is bounded by its range, so the bound of a corner layer holds for all blocks of the cells
 * above it. Float interval arithmetic is monotone like the evaluation it bounds; a small margin covers the rest. The
 * structure term (beardifier) is exactly zero above its affected box, which the caller keeps below the cut; blending
 * with old chunks disables the proof.
 */
public final class SkyBound {
    public static final boolean ENABLED = !Boolean.getBoolean("quire.gen.noSkySkip");
    public static final boolean VERIFY = Boolean.getBoolean("quire.gen.verify");
    private static final float MARGIN = 1.0E-3F;

    public static final java.util.concurrent.atomic.LongAdder CHUNKS = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder SKIPPED_LAYERS = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder TOTAL_LAYERS = new java.util.concurrent.atomic.LongAdder();

    private SkyBound() {
    }

    private static final class Abort extends RuntimeException {
        Abort() {
            super(null, null, false, false);
        }
    }

    /** Leaf nodes of the bound tree: only {@link #range()} is ever used. */
    private abstract static class BoundNode implements DensityFunction {
        @Override
        public DensitySampler compileSampler(final DensityFunction.CompileContext context) {
            throw new UnsupportedOperationException("bound only");
        }

        @Override
        public DensityFunction rewriteChildren(final DfRewriteRule rule) {
            return this;
        }

        @Override
        public int domainAxes() {
            return DensityFunction.NO_AXES;
        }

        @Override
        public MapCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("bound only");
        }
    }

    private static final class Fixed extends BoundNode {
        private final Interval range;

        Fixed(final Interval range) {
            this.range = range;
        }

        @Override
        public Interval range() {
            return this.range;
        }
    }

    /** A 1D/2D subtree inside interpolation: its exact value (or value range over the tested layers) at the corner column. */
    private static final class Slot extends BoundNode {
        final DensityFunction function;
        final boolean dependsOnY;
        final boolean dependsOnXz;
        DensitySampler.Bound sampler;
        float lo, hi;
        float[] suffixMin, suffixMax; // over corner layers [L, top], for slots depending on Y

        Slot(final DensityFunction function, final int axes) {
            this.function = function;
            this.dependsOnY = (axes & DensityFunction.AXIS_Y) != 0;
            this.dependsOnXz = (axes & (DensityFunction.AXIS_X | DensityFunction.AXIS_Z)) != 0;
        }

        @Override
        public Interval range() {
            return Interval.of(this.lo, this.hi);
        }
    }

    /** range_choice that only takes the branch its input interval selects. */
    private static final class Choice extends BoundNode {
        final DensityFunction input;
        final double minInclusive, maxExclusive;
        final DensityFunction whenIn, whenOut;

        Choice(final DensityFunction input, final double minInclusive, final double maxExclusive, final DensityFunction whenIn, final DensityFunction whenOut) {
            this.input = input;
            this.minInclusive = minInclusive;
            this.maxExclusive = maxExclusive;
            this.whenIn = whenIn;
            this.whenOut = whenOut;
        }

        @Override
        public Interval range() {
            final Interval in = this.input.range();
            if (in.min() >= this.minInclusive && in.max() < this.maxExclusive) {
                return this.whenIn.range();
            }
            if (in.max() < this.minInclusive || in.min() >= this.maxExclusive) {
                return this.whenOut.range();
            }
            return Interval.encapsulating(this.whenIn.range(), this.whenOut.range());
        }
    }

    private static DensityFunction inline(final DensityFunction function) {
        DensityFunction f = function;
        DensityFunction next;
        while ((next = DfRewriteRule.INLINE_REFERENCE.rewrite(f)) != f) {
            f = next;
        }
        return f;
    }

    /** Builds the bound tree for one chunk. */
    private static final class Builder {
        final Map<DensityFunction, Integer> axes = new IdentityHashMap<>();
        final List<Slot> slots = new ArrayList<>();
        final Map<DensityFunction, Slot> slotByFunction = new IdentityHashMap<>();
        int cellXz = -1, cellY = -1;

        int axes(final DensityFunction f) {
            Integer a = this.axes.get(f);
            if (a == null) {
                a = f.domainAxes();
                this.axes.put(f, a);
            }
            return a;
        }

        DensityFunction outer(final DensityFunction function) {
            final DensityFunction f = inline(function);
            if (f instanceof InterpolatedFunction interpolated) {
                this.cells(interpolated);
                return this.inner(interpolated.input());
            }
            if (f == SimpleDensityFunction.BEARDIFIER) {
                return new Fixed(Interval.ofExact(0.0F));
            }
            if (f instanceof RangeChoiceFunction(DensityFunction input, float min, float max, DensityFunction whenIn, DensityFunction whenOut)) {
                return new Choice(this.outer(input), min, max, this.outer(whenIn), this.outer(whenOut));
            }
            return f.rewriteChildren(this::outer);
        }

        DensityFunction inner(final DensityFunction function) {
            final DensityFunction f = inline(function);
            if (f == SimpleDensityFunction.BEARDIFIER) {
                throw new Abort(); // not expected inside interpolation
            }
            if (this.axes(f) != DensityFunction.ALL_AXES) {
                return this.slotByFunction.computeIfAbsent(f, k -> {
                    final Slot slot = new Slot(k, this.axes(k));
                    this.slots.add(slot);
                    return slot;
                });
            }
            if (f instanceof InterpolatedFunction interpolated) {
                this.cells(interpolated);
                return this.inner(interpolated.input());
            }
            if (f instanceof RangeChoiceFunction(DensityFunction input, float min, float max, DensityFunction whenIn, DensityFunction whenOut)) {
                return new Choice(this.inner(input), min, max, this.inner(whenIn), this.inner(whenOut));
            }
            return f.rewriteChildren(this::inner);
        }

        void cells(final InterpolatedFunction interpolated) {
            if (this.cellXz == -1) {
                this.cellXz = interpolated.cellSizeXz();
                this.cellY = interpolated.cellSizeY();
            } else if (this.cellXz != interpolated.cellSizeXz() || this.cellY != interpolated.cellSizeY()) {
                throw new Abort(); // the proof is made on one corner grid
            }
        }
    }

    /**
     * The lowest block Y from which every block of the volume provably has negative final density, or
     * {@code volume.minBlockY() + volume.sizeY()} if nothing is proven. Aligned to the interpolation cell grid.
     */
    public static int provenAirFromY(final DensityFunction finalDensity, final DensitySamplerSet samplers, final DensityVolume volume) {
        final int top = volume.minBlockY() + volume.sizeY();
        if (!ENABLED) {
            return top;
        }
        final Builder builder = new Builder();
        final DensityFunction root;
        try {
            root = builder.outer(finalDensity);
        } catch (final Abort abort) {
            return top;
        }
        final int cellXz = builder.cellXz, cellY = builder.cellY;
        if (cellXz <= 0 || cellY <= 0 || volume.sizeX() % cellXz != 0 || volume.sizeZ() % cellXz != 0 || volume.sizeY() % cellY != 0) {
            return top;
        }
        for (final Slot slot : builder.slots) {
            slot.sampler = samplers.get(slot.function);
        }
        final int layers = volume.sizeY() / cellY; // corner layers 0..layers
        // Y-only slots: per layer once per chunk, as suffix ranges over layers [L, top]
        for (final Slot slot : builder.slots) {
            if (slot.dependsOnY) {
                slot.suffixMin = new float[layers + 1];
                slot.suffixMax = new float[layers + 1];
                if (!slot.dependsOnXz) {
                    fillSuffix(slot, volume, cellY, layers, volume.minBlockX(), volume.minBlockZ());
                }
            }
        }
        int cutLayer = 0;
        for (int cx = 0; cx <= volume.sizeX(); cx += cellXz) {
            for (int cz = 0; cz <= volume.sizeZ(); cz += cellXz) {
                final int x = volume.minBlockX() + cx, z = volume.minBlockZ() + cz;
                for (final Slot slot : builder.slots) {
                    if (!slot.dependsOnY) {
                        slot.lo = slot.hi = slot.sampler.sampleValue(x, 0, z);
                    } else if (slot.dependsOnXz) {
                        fillSuffix(slot, volume, cellY, layers, x, z);
                    }
                }
                if (provenFrom(root, builder.slots, cutLayer)) {
                    continue; // this column does not raise the cut
                }
                // smallest L > cutLayer with the bound over [L, top] negative (monotone in L: the ranges nest)
                int lo = cutLayer + 1, hi = layers + 1; // hi: nothing proven
                while (lo < hi) {
                    final int mid = (lo + hi) >>> 1;
                    if (provenFrom(root, builder.slots, mid)) {
                        hi = mid;
                    } else {
                        lo = mid + 1;
                    }
                }
                cutLayer = lo;
                if (cutLayer > layers) {
                    record(layers, 0);
                    return top;
                }
            }
        }
        record(layers, layers - cutLayer);
        return volume.minBlockY() + cutLayer * cellY;
    }

    private static void fillSuffix(final Slot slot, final DensityVolume volume, final int cellY, final int layers, final int x, final int z) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int layer = layers; layer >= 0; layer--) {
            final float v = slot.sampler.sampleValue(x, volume.minBlockY() + layer * cellY, z);
            min = Math.min(min, v);
            max = Math.max(max, v);
            slot.suffixMin[layer] = min;
            slot.suffixMax[layer] = max;
        }
    }

    /** Whether the bound over corner layers [layer, top] is negative. */
    private static boolean provenFrom(final DensityFunction root, final List<Slot> slots, final int layer) {
        for (final Slot slot : slots) {
            if (slot.dependsOnY) {
                slot.lo = slot.suffixMin[layer];
                slot.hi = slot.suffixMax[layer];
            }
        }
        return root.range().max() < -MARGIN;
    }

    private static void record(final int layers, final int skipped) {
        CHUNKS.increment();
        TOTAL_LAYERS.add(layers);
        SKIPPED_LAYERS.add(skipped);
        final long chunks = CHUNKS.sum();
        if (chunks % 1000 == 0) {
            LogUtils.getLogger().info("[Quire] sky bound: {} chunks, {}% of density layers proven air",
                chunks, String.format("%.1f", 100.0 * SKIPPED_LAYERS.sum() / Math.max(1, TOTAL_LAYERS.sum())));
        }
    }
}
