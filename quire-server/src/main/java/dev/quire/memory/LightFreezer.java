package dev.quire.memory;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkStatus;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Freezes (sparse-encodes) the light nibbles of chunks whose light has been idle for a while.
 *
 * <p>The main thread only looks for candidates. Freezing is an updating operation on the nibbles, so it runs as
 * a task on the chunk system's area-dependent queue with the light write radius, the same area lock light
 * updates for that chunk hold: the light engine can never touch these nibbles while they are being frozen.
 */
public final class LightFreezer {
    /** Idle time before freezing, in clock units (~1.07 s); wall clock, since an overloaded server ticks slowly. */
    private static final int IDLE_UNITS = Integer.getInteger("quire.freezeLight.idleSeconds", 30);

    public static int clock() {
        return (int) (System.nanoTime() >>> 30);
    }
    private static final int MAX_QUEUED = 64;
    private static final long BUDGET_NANOS = 150_000L;

    private static final java.util.WeakHashMap<ServerLevel, int[]> CURSORS = new java.util.WeakHashMap<>(); // weak: unloaded worlds must not be retained
    // chunk -> queue time; entries of tasks that never ran (world unloaded) expire so chunks are not retained
    private static final ConcurrentHashMap<LevelChunk, Long> PENDING = new ConcurrentHashMap<>();
    private static final long PENDING_EXPIRY_NANOS = 120_000_000_000L;
    public static final LongAdder FROZEN = new LongAdder();
    public static final LongAdder QUEUED = new LongAdder();
    public static final LongAdder RAN = new LongAdder();
    public static final LongAdder CANDIDATE_SCANS = new LongAdder();

    private LightFreezer() {
    }

    public static void tick(final MinecraftServer server) {
        if (!SWMRNibbleArray.FREEZE) {
            return;
        }
        final long deadline = System.nanoTime() + BUDGET_NANOS;
        if (!PENDING.isEmpty()) {
            final long now = System.nanoTime();
            PENDING.values().removeIf(queued -> now - queued > PENDING_EXPIRY_NANOS);
        }
        final int idleBefore = clock() - IDLE_UNITS;
        for (final ServerLevel level : server.getAllLevels()) {
            final var loaded = ((ChunkSystemServerLevel) level).moonrise$getLoadedChunks();
            final LevelChunk[] raw = loaded.getRawDataUnchecked();
            final int size = loaded.size();
            if (size == 0) {
                continue;
            }
            final int[] cursor = CURSORS.computeIfAbsent(level, l -> new int[1]);
            for (int visited = 0; visited < size && PENDING.size() < MAX_QUEUED; visited++) {
                if ((visited & 15) == 0 && System.nanoTime() > deadline) {
                    return;
                }
                cursor[0] = (cursor[0] + 1) % size;
                CANDIDATE_SCANS.increment();
                final LevelChunk chunk = raw[cursor[0]];
                if (chunk == null || PENDING.containsKey(chunk)) {
                    continue;
                }
                if (needsFreeze(((StarlightChunk) chunk).starlight$getBlockNibbles(), idleBefore)
                    || needsFreeze(((StarlightChunk) chunk).starlight$getSkyNibbles(), idleBefore)) {
                    PENDING.put(chunk, System.nanoTime());
                    QUEUED.increment();
                    ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler().radiusAwareScheduler.queueTask(
                        chunk.getPos().x(), chunk.getPos().z(), ((ChunkSystemChunkStatus) ChunkStatus.LIGHT).moonrise$getWriteRadius(),
                        () -> {
                            RAN.increment();
                            try {
                                freeze(((StarlightChunk) chunk).starlight$getBlockNibbles(), idleBefore);
                                freeze(((StarlightChunk) chunk).starlight$getSkyNibbles(), idleBefore);
                            } finally {
                                PENDING.remove(chunk);
                            }
                        }, Priority.LOWEST);
                }
            }
        }
    }

    /** Racy hint: any idle initialised nibble not yet frozen. */
    private static boolean needsFreeze(final SWMRNibbleArray[] nibbles, final int idleBefore) {
        if (nibbles == null) {
            return false;
        }
        for (final SWMRNibbleArray nibble : nibbles) {
            if (nibble != null && nibble.quire$freezable() && nibble.quireLastUpdate - idleBefore < 0) {
                return true;
            }
        }
        return false;
    }

    private static void freeze(final SWMRNibbleArray[] nibbles, final int idleBefore) {
        if (nibbles == null) {
            return;
        }
        for (final SWMRNibbleArray nibble : nibbles) {
            if (nibble != null && nibble.quireLastUpdate - idleBefore < 0 && nibble.quire$freeze()) {
                FROZEN.increment();
            }
        }
    }
}
