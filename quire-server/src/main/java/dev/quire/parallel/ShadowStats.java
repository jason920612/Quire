package dev.quire.parallel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shadow mode (-Dquire.shadow=true): ticking stays single threaded, but every unit of tick work
 * (one entity, one block entity, one chunk) records how far from its own chunk it reached and whether
 * it fired a plugin event that has listeners. From that we can compute, for any tile size, how often a
 * parallel tile scheduler would have to escalate a unit to exclusive/main-thread execution.
 */
public final class ShadowStats {
    public static final boolean ENABLED = Boolean.getBoolean("quire.shadow");

    /** Distance buckets 0..MAX_BUCKET-1 in chunks (Chebyshev), last bucket = MAX_BUCKET or more. */
    private static final int MAX_BUCKET = 17;

    private static Thread activeThread;
    private static String unitKind;
    private static int unitChunkX;
    private static int unitChunkZ;
    private static int maxDistance;
    private static boolean firedEvent;

    // per kind: [units, events, hist[0..MAX_BUCKET]]
    private static final Map<String, long[]> STATS = new HashMap<>();
    private static final Map<String, long[]> EVENTS = new HashMap<>();
    private static long startNanos = System.nanoTime();

    private ShadowStats() {
    }

    public static void begin(final String kind, final int chunkX, final int chunkZ) {
        activeThread = Thread.currentThread();
        unitKind = kind;
        unitChunkX = chunkX;
        unitChunkZ = chunkZ;
        maxDistance = 0;
        firedEvent = false;
    }

    public static void end() {
        if (activeThread == null) {
            return;
        }
        final long[] s = STATS.computeIfAbsent(unitKind, k -> new long[2 + MAX_BUCKET + 1]);
        s[0]++;
        if (firedEvent) {
            s[1]++;
        }
        s[2 + Math.min(maxDistance, MAX_BUCKET)]++;
        activeThread = null;
    }

    private static boolean tracking() {
        return activeThread != null && Thread.currentThread() == activeThread;
    }

    public static void access(final int chunkX, final int chunkZ) {
        if (!tracking()) {
            return;
        }
        final int d = Math.max(Math.abs(chunkX - unitChunkX), Math.abs(chunkZ - unitChunkZ));
        if (d > maxDistance) {
            maxDistance = d;
        }
    }

    public static void accessRange(final int minChunkX, final int minChunkZ, final int maxChunkX, final int maxChunkZ) {
        if (!tracking()) {
            return;
        }
        final int d = Math.max(
            Math.max(Math.abs(minChunkX - unitChunkX), Math.abs(maxChunkX - unitChunkX)),
            Math.max(Math.abs(minChunkZ - unitChunkZ), Math.abs(maxChunkZ - unitChunkZ)));
        if (d > maxDistance) {
            maxDistance = d;
        }
    }

    public static void event(final Object event) {
        if (!tracking()) {
            return;
        }
        firedEvent = true;
        EVENTS.computeIfAbsent(unitKind + " / " + event.getClass().getSimpleName(), k -> new long[1])[0]++;
    }

    public static void reset() {
        STATS.clear();
        EVENTS.clear();
        startNanos = System.nanoTime();
    }

    public static void dump(final Path file) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append(String.format("seconds=%.1f%n", (System.nanoTime() - startNanos) / 1e9));
        sb.append("Share of units whose farthest access stays within N chunks of their own chunk (cumulative).\n");
        sb.append("A tile scheduler with T-chunk tiles keeps every unit within T chunks safe.\n");
        sb.append(String.format("%-44s %10s %8s %7s %7s %7s %7s %7s %7s %7s%n", "kind", "units", "event%", "<=0", "<=1", "<=2", "<=4", "<=8", "<=16", ">16"));
        final List<Map.Entry<String, long[]>> list = new ArrayList<>(STATS.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        for (final Map.Entry<String, long[]> e : list) {
            final long[] s = e.getValue();
            final double n = Math.max(1, s[0]);
            final double[] cum = new double[MAX_BUCKET + 1];
            long acc = 0;
            for (int i = 0; i <= MAX_BUCKET; i++) {
                acc += s[2 + i];
                cum[i] = 100.0 * acc / n;
            }
            sb.append(String.format("%-44s %10d %7.2f%% %6.2f%% %6.2f%% %6.2f%% %6.2f%% %6.2f%% %6.2f%% %6.2f%%%n",
                e.getKey(), s[0], 100.0 * s[1] / n, cum[0], cum[1], cum[2], cum[4], cum[8], cum[16], 100.0 - cum[16]));
        }
        sb.append("\nEvents with listeners fired inside tick units:\n");
        final List<Map.Entry<String, long[]>> ev = new ArrayList<>(EVENTS.entrySet());
        ev.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        for (final Map.Entry<String, long[]> e : ev) {
            sb.append(String.format("%10d  %s%n", e.getValue()[0], e.getKey()));
        }
        Files.writeString(file, sb.toString());
    }
}
