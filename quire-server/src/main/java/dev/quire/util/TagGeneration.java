package dev.quire.util;

/**
 * Counts how many times registry holders had their tags rebound (startup, /reload, datapack changes).
 * Caches of tag membership store the generation they were computed in and recompute when it moves.
 */
public final class TagGeneration {
    private static volatile int generation;

    private TagGeneration() {
    }

    public static int get() {
        return generation;
    }

    public static void bump() {
        generation = (generation + 1) & 0x3FFFFFFF;
    }
}
