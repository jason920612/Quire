package dev.quire.memory;

import ca.spottedleaf.moonrise.patches.collisions.shape.CachedShapeData;
import ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Canonicalises structurally identical voxel shapes. Every block state caches per-face slices of its shapes
 * (vanilla face shapes and Moonrise's clamped face shapes, 12 per shape); most of those slices are equal to
 * each other. Voxel shapes are immutable and their internal caches are idempotent, so equal shapes can share
 * one instance. Structural key: absolute coordinate partitions on each axis plus the voxel bit set.
 */
public final class ShapeInterner {
    public static final boolean ENABLED = !Boolean.getBoolean("quire.noShapeInterning");

    private record Key(double[] x, double[] y, double[] z, long[] voxels, int hash) {
        static Key of(final VoxelShape shape) {
            final CollisionVoxelShape s = (CollisionVoxelShape) shape;
            final double[] x = absolute(s.moonrise$rootCoordinatesX(), s.moonrise$offsetX());
            final double[] y = absolute(s.moonrise$rootCoordinatesY(), s.moonrise$offsetY());
            final double[] z = absolute(s.moonrise$rootCoordinatesZ(), s.moonrise$offsetZ());
            final CachedShapeData data = s.moonrise$getCachedVoxelData();
            final long[] voxels = data.voxelSet();
            int h = Arrays.hashCode(x);
            h = 31 * h + Arrays.hashCode(y);
            h = 31 * h + Arrays.hashCode(z);
            h = 31 * h + Arrays.hashCode(voxels);
            return new Key(x, y, z, voxels, h);
        }

        private static double[] absolute(final double[] root, final double offset) {
            final double[] ret = new double[root.length];
            for (int i = 0; i < root.length; i++) {
                ret[i] = root[i] + offset;
            }
            return ret;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof Key k && k.hash == this.hash && Arrays.equals(k.x, this.x) && Arrays.equals(k.y, this.y)
                && Arrays.equals(k.z, this.z) && Arrays.equals(k.voxels, this.voxels);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    private static final ConcurrentHashMap<Key, VoxelShape> SHAPES = new ConcurrentHashMap<>();
    private static final int MAX_SHAPES = Integer.getInteger("quire.shapeInterner.max", 200_000);

    private ShapeInterner() {
    }

    public static VoxelShape intern(final VoxelShape shape) {
        if (!ENABLED || shape.isEmpty() || shape == Shapes.block()) {
            return shape.isEmpty() && ENABLED ? Shapes.empty() : shape;
        }
        if (SHAPES.size() >= MAX_SHAPES) {
            return shape; // shapes made at runtime (dynamic block shapes) must not grow this without bound
        }
        final Key key = Key.of(shape);
        final VoxelShape existing = SHAPES.putIfAbsent(key, shape);
        return existing != null ? existing : shape;
    }

    public static int size() {
        return SHAPES.size();
    }
}
