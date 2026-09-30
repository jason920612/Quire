package dev.quire.memory;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Re-encodes block sections that were inflated (written to) but have not been inflated again for a while.
 *
 * <p>Runs on the main thread at the end of the tick with a small time budget, walking loaded chunks round robin.
 * In sparse mode the main thread only snapshots candidate storages; a background thread encodes the snapshots
 * and the main thread installs a result at the end of a later tick, under the container lock, only if the
 * storage is still the same instance with the same contents (so a write in between just discards the result).
 */
public final class SectionCompactor {
    private static final long BUDGET_NANOS = Long.getLong("quire.compressSections.budgetMicros", 300L) * 1000L;
    private static final int MAX_IN_FLIGHT = 2048;
    private static final IdentityHashMap<ServerLevel, int[]> CURSORS = new IdentityHashMap<>();
    public static final LongAdder PASSES = new LongAdder();
    public static final LongAdder DISCARDED = new LongAdder();

    private record Job(PalettedContainer<?> container, SimpleBitStorage expected, long[] snapshot) {}
    private record Result(Job job, FrozenBitStorage frozen) {}

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
    private static final ThreadPoolExecutor ENCODER = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
        final Thread thread = new Thread(r, "Quire Section Encoder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private SectionCompactor() {
    }

    public static void tick(final MinecraftServer server) {
        if (!SectionCompression.ENABLED) {
            return;
        }
        final long deadline = System.nanoTime() + BUDGET_NANOS;
        if (SectionCompression.SPARSE) {
            installResults(deadline);
        }
        final int now = LightFreezer.clock();
        for (final ServerLevel level : server.getAllLevels()) {
            final var loaded = ((ChunkSystemServerLevel) level).moonrise$getLoadedChunks();
            final LevelChunk[] raw = loaded.getRawDataUnchecked();
            final int size = loaded.size();
            if (size == 0) {
                continue;
            }
            final int[] cursor = CURSORS.computeIfAbsent(level, l -> new int[1]);
            for (int visited = 0; visited < size; visited++) {
                if ((visited & 7) == 0 && System.nanoTime() > deadline) {
                    return;
                }
                if (SectionCompression.SPARSE && IN_FLIGHT.get() >= MAX_IN_FLIGHT) {
                    return;
                }
                cursor[0] = (cursor[0] + 1) % size;
                if (cursor[0] == 0 && level == server.overworld()) {
                    PASSES.increment();
                }
                final LevelChunk chunk = raw[cursor[0]];
                if (chunk == null) {
                    continue;
                }
                for (final LevelChunkSection section : chunk.getSections()) {
                    final PalettedContainer<?> states = section.getStates();
                    if (states.quireHotUntil != 0 && now - states.quireHotUntil <= 0) {
                        continue;
                    }
                    if (!SectionCompression.SPARSE) {
                        states.quire$compress();
                        continue;
                    }
                    final SimpleBitStorage expected = states.quire$plainStorage();
                    if (expected == null) {
                        continue;
                    }
                    final long[] snapshot = states.quire$snapshotForEncoding();
                    if (snapshot == null) {
                        continue;
                    }
                    final Job job = new Job(states, expected, snapshot);
                    IN_FLIGHT.incrementAndGet();
                    ENCODER.execute(() -> {
                        FrozenBitStorage frozen = null;
                        try {
                            frozen = SparseBitStorage.encode(job.container(), new SimpleBitStorage(job.expected().getBits(), job.expected().getSize(), job.snapshot()),
                                SectionCompression.SPARSE_MAX_RATIO);
                        } finally {
                            RESULTS.add(new Result(job, frozen));
                        }
                    });
                }
            }
        }
    }

    private static void installResults(final long deadline) {
        Result result;
        int n = 0;
        while ((result = RESULTS.poll()) != null) {
            IN_FLIGHT.decrementAndGet();
            final Job job = result.job();
            if (!job.container().quire$installEncoded(job.expected(), job.snapshot(), result.frozen()) && result.frozen() != null) {
                DISCARDED.increment();
            }
            if ((++n & 63) == 0 && System.nanoTime() > deadline) {
                return;
            }
        }
    }

    public static String summary() {
        return "passes=" + PASSES.sum() + " failed=" + SectionCompression.FAILED.sum() + " discarded=" + DISCARDED.sum()
            + " compressed=" + SectionCompression.COMPRESSED.sum() + " inflated=" + SectionCompression.INFLATED.sum()
            + " packedMB=" + String.format("%.1f", SectionCompression.PACKED_BYTES.sum() / 1048576.0);
    }
}
