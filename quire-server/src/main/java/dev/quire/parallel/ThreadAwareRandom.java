package dev.quire.parallel;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A shared random source (Entity.SHARED_RANDOM, Level.random) that, on a tile worker, draws from that
 * worker's own stream instead. The shared generators are not thread safe; per-thread streams are
 * statistically equivalent (vanilla itself gives every entity its own generator).
 */
public final class ThreadAwareRandom implements RandomSource {
    private final RandomSource main;

    public ThreadAwareRandom(final RandomSource main) {
        this.main = main;
    }

    private RandomSource current() {
        return Thread.currentThread() instanceof TileWorker worker ? worker.random : this.main;
    }

    @Override
    public RandomSource fork() {
        return this.current().fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return this.current().forkPositional();
    }

    @Override
    public void setSeed(final long seed) {
        this.current().setSeed(seed);
    }

    @Override
    public int nextInt() {
        return this.current().nextInt();
    }

    @Override
    public int nextInt(final int bound) {
        return this.current().nextInt(bound);
    }

    @Override
    public int nextIntBetweenInclusive(final int min, final int max) {
        return this.current().nextIntBetweenInclusive(min, max);
    }

    @Override
    public long nextLong() {
        return this.current().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return this.current().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return this.current().nextFloat();
    }

    @Override
    public double nextDouble() {
        return this.current().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return this.current().nextGaussian();
    }

    @Override
    public double triangle(final double mean, final double spread) {
        return this.current().triangle(mean, spread);
    }

    @Override
    public float triangle(final float mean, final float spread) {
        return this.current().triangle(mean, spread);
    }

    @Override
    public void consumeCount(final int count) {
        this.current().consumeCount(count);
    }

    @Override
    public int nextInt(final int origin, final int bound) {
        return this.current().nextInt(origin, bound);
    }
}
