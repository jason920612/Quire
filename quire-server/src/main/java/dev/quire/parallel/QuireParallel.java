package dev.quire.parallel;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;

/**
 * Entry points of Quire's tile-parallel ticking (-Dquire.parallel=true).
 *
 * <p>The world is cut into tiles of 2^TILE_SHIFT x 2^TILE_SHIFT chunks, coloured by (tileX mod 3, tileZ mod 3).
 * All tiles of one colour tick at the same time. A task may touch its own tile plus one ring of tiles around
 * it; same-coloured tiles are two tiles apart, so those areas never overlap. Anything outside the area, plugin
 * events and global side effects escalate to exclusive execution (see {@link TileScheduler}).
 */
public final class QuireParallel {
    public static final boolean ENABLED = Boolean.getBoolean("quire.parallel");
    public static final int THREADS = Integer.getInteger("quire.parallel.threads",
        Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    /** Fixed tile size for entities (log2 chunks, testing); negative = measured choice between serial and shifts 0..2. */
    public static final int TILE_SHIFT = Integer.getInteger("quire.parallel.tileShift", -1);
    private static final int MAX_AUTO_SHIFT = 2;
    /** Below this many parallel-eligible units the phase overhead is not worth it; tick serially. */
    public static final int MIN_UNITS = Integer.getInteger("quire.parallel.minUnits", 128);

    private static volatile TileScheduler scheduler;

    private QuireParallel() {
    }

    public static TileScheduler scheduler() {
        TileScheduler s = scheduler;
        if (s == null) {
            synchronized (QuireParallel.class) {
                s = scheduler;
                if (s == null) {
                    s = scheduler = new TileScheduler(THREADS);
                }
            }
        }
        return s;
    }

    // ---------------------------------------------------------------- guards

    public static boolean onWorker() {
        return Thread.currentThread() instanceof TileWorker;
    }

    /** Testing only: probability of escalating on any guarded access, to exercise the exclusive paths. */
    public static final double CHAOS = Double.parseDouble(System.getProperty("quire.parallel.chaos", "0"));

    /** Access to (chunkX, chunkZ); escalates when outside the current task's safe area. */
    public static void checkChunk(final Level level, final int chunkX, final int chunkZ) {
        if (Thread.currentThread() instanceof TileWorker worker) {
            if (!worker.canAccess(level, chunkX, chunkZ)) {
                worker.escalate("chunk access");
            } else if (CHAOS > 0.0 && worker.random.nextDouble() < CHAOS) {
                worker.escalate("chaos");
            }
        }
    }

    public static void checkChunkRange(final Level level, final int minChunkX, final int minChunkZ, final int maxChunkX, final int maxChunkZ) {
        if (Thread.currentThread() instanceof TileWorker worker
            && (!worker.canAccess(level, minChunkX, minChunkZ) || !worker.canAccess(level, maxChunkX, maxChunkZ))) {
            worker.escalate("chunk range access");
        }
    }

    public static void checkBlock(final Level level, final BlockPos pos) {
        checkChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** Operations with global effects: continue only with exclusive access. */
    public static void requireExclusive(final String reason) {
        if (Thread.currentThread() instanceof TileWorker worker) {
            worker.escalate(reason);
        }
    }

    /** Runs on the real main thread (plugins must never run on a worker). Blocks the worker until done. */
    public static void runOnMain(final Runnable runnable) {
        if (Thread.currentThread() instanceof TileWorker worker) {
            worker.escalate("main thread handoff");
            worker.scheduler.runOnMain(worker, runnable);
        } else {
            runnable.run();
        }
    }

    /** Like {@link #runOnMain(Runnable)} but returns a value. */
    public static <T> T callOnMain(final java.util.function.Supplier<T> supplier) {
        if (!(Thread.currentThread() instanceof TileWorker)) {
            return supplier.get();
        }
        final Object[] result = new Object[1];
        runOnMain(() -> result[0] = supplier.get());
        @SuppressWarnings("unchecked")
        final T ret = (T) result[0];
        return ret;
    }

    // ---------------------------------------------------------------- shared helpers

    /**
     * Path type cache invalidation for a block change. On a worker only that worker's cache is updated
     * right away; the position is recorded and applied to every other cache after the phase (no other
     * worker reads that area during the phase).
     */
    public static void invalidatePathType(final ServerLevel level, final BlockPos pos) {
        if (Thread.currentThread() instanceof TileWorker worker) {
            worker.invalidatePathType(level, pos);
            worker.pathInvalidations.add(pos.asLong());
        } else {
            level.quire$mainPathTypeCache().invalidate(pos);
            if (scheduler != null) {
                for (final TileWorker worker : scheduler.workers()) {
                    worker.invalidatePathType(level, pos); // workers are idle whenever main changes blocks
                }
            }
        }
    }

    /**
     * sendBlockUpdated's path recomputation on a tile worker: mobs inside the safe area are handled now,
     * the rest (owned by other workers this phase) are checked on the main thread when the phase ends.
     */
    public static void updateNavigations(final ServerLevel level, final java.util.Set<net.minecraft.world.entity.Mob> navigatingMobs, final BlockPos pos) {
        final TileWorker worker = (TileWorker) Thread.currentThread();
        final BlockPos immutable = pos.immutable();
        final List<net.minecraft.world.entity.ai.navigation.PathNavigation> now = new ArrayList<>();
        boolean outside = false;
        for (final net.minecraft.world.entity.Mob mob : navigatingMobs) {
            if (worker.canAccess(level, mob.chunkPosition().x(), mob.chunkPosition().z())) {
                if (mob.getNavigation().shouldRecomputePath(immutable)) {
                    now.add(mob.getNavigation());
                }
            } else {
                outside = true;
            }
        }
        for (final net.minecraft.world.entity.ai.navigation.PathNavigation navigation : now) {
            navigation.recomputePath();
        }
        final TileScheduler.TileTask task = worker.task;
        if (outside && task != null && !worker.isExclusive()) {
            task.deferred.add(() -> {
                // on the main thread after the phase: the vanilla check over all mobs (mobs recomputed above
                // already have a fresh path)
                final List<net.minecraft.world.entity.ai.navigation.PathNavigation> later = new ArrayList<>();
                for (final net.minecraft.world.entity.Mob mob : navigatingMobs) {
                    if (mob.getNavigation().shouldRecomputePath(immutable)) {
                        later.add(mob.getNavigation());
                    }
                }
                for (final net.minecraft.world.entity.ai.navigation.PathNavigation navigation : later) {
                    navigation.recomputePath();
                }
            });
        }
    }

    // ---------------------------------------------------------------- escalation statistics / adaptive routing

    private static final class TypeStats {
        long units; // main thread only
        final java.util.concurrent.atomic.LongAdder escalations = new java.util.concurrent.atomic.LongAdder();
    }

    /** Entity types escalating more often than 1 in ESCALATION_ROUTE_RATIO are ticked serially instead. */
    public static final int ESCALATION_ROUTE_RATIO = Integer.getInteger("quire.parallel.routeRatio", 50);
    private static final java.util.concurrent.ConcurrentHashMap<Object, TypeStats> TYPE_STATS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> REASONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static long routingTicks;

    /** Routing key of a unit: entity type, block entity type, or "chunk". */
    private static Object kindOf(final Object unit) {
        if (unit instanceof Entity entity) {
            return entity.getType();
        }
        if (unit instanceof net.minecraft.world.level.block.entity.TickingBlockEntity ticker) {
            return "blockentity " + ticker.getType();
        }
        return "chunk";
    }

    static void recordEscalation(final Object unit, final String reason) {
        REASONS.computeIfAbsent(reason, k -> new java.util.concurrent.atomic.LongAdder()).increment();
        if (unit != null) {
            final TypeStats stats = TYPE_STATS.get(kindOf(unit));
            if (stats != null) {
                stats.escalations.increment();
            }
        }
    }

    /** Units of kinds that keep escalating are cheaper to tick serially than to stall their tile. */
    private static boolean routedSerial(final Object unit) {
        final TypeStats stats = TYPE_STATS.computeIfAbsent(kindOf(unit), k -> new TypeStats());
        if (stats.units >= 1000 && stats.escalations.sum() * ESCALATION_ROUTE_RATIO > stats.units) {
            return true; // units only count while ticked in parallel, so the decision holds until the history decays
        }
        stats.units++;
        return false;
    }

    private static void decayRouting() {
        // every minute decay the history (escalations faster than units, so the escalation ratio of a kind
        // routed to serial halves each minute and it is probed in parallel again after a few minutes)
        if (++routingTicks % 1200 == 0) {
            for (final TypeStats stats : TYPE_STATS.values()) {
                final long esc = stats.escalations.sumThenReset();
                stats.escalations.add(esc / 4);
                stats.units /= 2;
            }
        }
    }

    public static String statsSummary() {
        final StringBuilder sb = new StringBuilder();
        final TileScheduler s = scheduler;
        if (s != null) {
            sb.append("phases=").append(s.phases).append(" tasks=").append(s.tasksRun).append(" escalations=").append(s.escalations);
        }
        sb.append(" reasons=");
        REASONS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(6)
            .forEach(e -> sb.append(e.getKey()).append(':').append(e.getValue().sum()).append(' '));
        STAGES.forEach((level, controllers) -> {
            for (int i = 0; i < controllers.length; i++) {
                sb.append(level.getWorld().getName()).append('.').append(STAGE_NAMES[i]).append('[').append(controllers[i]).append("] ");
            }
        });
        sb.append("serialTypes=");
        TYPE_STATS.forEach((type, stats) -> {
            if (stats.units >= 1000 && stats.escalations.sum() * ESCALATION_ROUTE_RATIO > stats.units) {
                sb.append(type).append(' ');
            }
        });
        return sb.toString();
    }

    // ---------------------------------------------------------------- entities

    /** Whether this entity's tick is known to stay local and free of global side effects. */
    public static boolean canTickInParallel(final Entity entity) {
        if (entity instanceof ServerPlayer || entity.isPassenger() || entity.isVehicle()) {
            return false;
        }
        if (entity.getClass().getClassLoader() != Entity.class.getClassLoader()) {
            return false; // entity implemented by a plugin
        }
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            return true;
        }
        if (entity instanceof Mob mob) {
            return !(mob instanceof EnderDragon || mob instanceof WitherBoss || mob instanceof Warden
                || (mob instanceof Raider raider && raider.getCurrentRaid() != null)
                || mob.quire$hasPluginGoals());
        }
        return false;
    }

    /**
     * Ticks the given entities (already in tick order) with {@code tick}; parallel-eligible entities are
     * ticked tile-parallel, the rest serially afterwards in their original order.
     */
    public static void tickEntities(final ServerLevel level, final List<Entity> entities, final Consumer<Entity> tick) {
        final List<Entity> serial = new ArrayList<>();
        final List<Entity> candidates = new ArrayList<>(entities.size());
        decayRouting();
        for (final Entity entity : entities) {
            if (!canTickInParallel(entity) || routedSerial(entity)) {
                serial.add(entity);
            } else {
                candidates.add(entity);
            }
        }
        if (candidates.size() < MIN_UNITS) {
            for (final Entity entity : entities) {
                tick.accept(entity);
            }
            return;
        }

        final StageController controller = stage(level, STAGE_ENTITIES);
        final int arm = TILE_SHIFT >= 0 ? 1 + TILE_SHIFT : controller.choose();
        final long start = System.nanoTime();
        if (arm == StageController.SERIAL) {
            for (final Entity entity : entities) {
                tick.accept(entity);
            }
        } else {
            final int shift = arm - 1;
            final Long2ObjectOpenHashMap<ArrayList<Entity>> tiles = new Long2ObjectOpenHashMap<>();
            for (final Entity entity : candidates) {
                tiles.computeIfAbsent(tileKey(entity, shift), k -> new ArrayList<>()).add(entity);
            }
            @SuppressWarnings("unchecked")
            final Consumer<Object> runner = (Consumer<Object>) (Consumer<?>) tick;
            runColoured(level, tiles, runner, shift);
            for (final Entity entity : serial) {
                tick.accept(entity);
            }
        }
        controller.record(arm, System.nanoTime() - start, entities.size());
    }

    public static final int STAGE_ENTITIES = 0;
    public static final int STAGE_BLOCK_ENTITIES = 1;
    public static final int STAGE_CHUNKS = 2;
    private static final String[] STAGE_NAMES = {"entities", "blockEntities", "chunks"};
    private static final java.util.WeakHashMap<ServerLevel, StageController[]> STAGES = new java.util.WeakHashMap<>(); // weak: unloaded worlds

    /** Main thread only. Arms: serial, then tile shifts 0..MAX_AUTO_SHIFT. */
    static StageController stage(final ServerLevel level, final int stage) {
        return STAGES.computeIfAbsent(level, l -> new StageController[] {
            new StageController(MAX_AUTO_SHIFT + 2), new StageController(MAX_AUTO_SHIFT + 2), new StageController(MAX_AUTO_SHIFT + 2)
        })[stage];
    }

    /**
     * Ticks positioned units (block entities, chunks) serially or tile-parallel, whichever the stage's
     * controller measured to be fastest. {@code chunkKey} maps a unit to its chunk key (z high, x low).
     * Units keep their original relative order within a tile.
     */
    public static <T> void tickUnits(final ServerLevel level, final int stage, final List<T> units, final java.util.function.ToLongFunction<T> chunkKey,
                                     final Consumer<T> tick) {
        if (units.size() < MIN_UNITS) {
            for (final T unit : units) {
                tick.accept(unit);
            }
            return;
        }
        final StageController controller = stage(level, stage);
        final int arm = UNIT_TILE_SHIFT >= 0 ? 1 + UNIT_TILE_SHIFT : controller.choose();
        final long start = System.nanoTime();
        if (arm == StageController.SERIAL) {
            for (final T unit : units) {
                tick.accept(unit);
            }
        } else {
            final int shift = arm - 1;
            final Long2ObjectOpenHashMap<ArrayList<T>> tiles = new Long2ObjectOpenHashMap<>();
            for (final T unit : units) {
                final long chunk = chunkKey.applyAsLong(unit);
                final int tileX = ((int) chunk) >> shift;
                final int tileZ = ((int) (chunk >>> 32)) >> shift;
                tiles.computeIfAbsent(((long) tileZ << 32) | (tileX & 0xFFFFFFFFL), k -> new ArrayList<>()).add(unit);
            }
            @SuppressWarnings("unchecked")
            final Consumer<Object> runner = (Consumer<Object>) (Consumer<?>) tick;
            runColoured(level, tiles, runner, shift);
        }
        controller.record(arm, System.nanoTime() - start, units.size());
    }

    /** Fixed tile size for block entities / chunk ticks (testing); negative = measured choice. */
    public static final int UNIT_TILE_SHIFT = Integer.getInteger("quire.parallel.unitTileShift", -1);

    private static long tileKey(final Entity entity, final int shift) {
        final int tileX = entity.chunkPosition().x() >> shift;
        final int tileZ = entity.chunkPosition().z() >> shift;
        return ((long) tileZ << 32) | (tileX & 0xFFFFFFFFL);
    }

    private static <T> void runColoured(final ServerLevel level, final Long2ObjectOpenHashMap<ArrayList<T>> tiles, final Consumer<Object> runner, final int shift) {
        final int tileChunks = 1 << shift;
        final long[] keys = tiles.keySet().toLongArray();
        java.util.Arrays.sort(keys);
        final TileScheduler scheduler = scheduler();
        for (int colour = 0; colour < 9; colour++) {
            final List<TileScheduler.TileTask> tasks = new ArrayList<>();
            for (final long key : keys) {
                final int tileX = (int) key;
                final int tileZ = (int) (key >> 32);
                if (Math.floorMod(tileX, 3) + 3 * Math.floorMod(tileZ, 3) != colour) {
                    continue;
                }
                final ArrayList<T> units = tiles.get(key);
                final int minChunkX = tileX << shift;
                final int minChunkZ = tileZ << shift;
                tasks.add(new TileScheduler.TileTask(level, tasks.size(),
                    minChunkX - tileChunks, minChunkZ - tileChunks,
                    minChunkX + 2 * tileChunks - 1, minChunkZ + 2 * tileChunks - 1,
                    units.toArray(), units.size(), runner));
            }
            scheduler.runPhase(tasks);
            // replay deferred global side effects on the main thread, in task order
            for (final TileScheduler.TileTask task : tasks) {
                for (final Runnable action : task.deferred) {
                    action.run();
                }
            }
        }
        flushPathInvalidations(level, scheduler);
    }

    private static void flushPathInvalidations(final ServerLevel level, final TileScheduler scheduler) {
        // Each worker only invalidated its own cache; every other cache (other workers, main) must hear about it.
        final LongArrayList all = new LongArrayList();
        for (final TileWorker worker : scheduler.workers()) {
            final LongArrayList list = worker.pathInvalidations;
            all.addAll(list);
            list.clear();
        }
        if (all.isEmpty()) {
            return;
        }
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < all.size(); i++) {
            pos.set(BlockPos.getX(all.getLong(i)), BlockPos.getY(all.getLong(i)), BlockPos.getZ(all.getLong(i)));
            final BlockPos immutable = pos.immutable();
            level.quire$mainPathTypeCache().invalidate(immutable);
            for (final TileWorker worker : scheduler.workers()) {
                worker.invalidatePathType(level, immutable);
            }
        }
    }
}
