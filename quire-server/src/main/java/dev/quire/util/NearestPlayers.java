package dev.quire.util;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Exact nearest-player queries using Moonrise's per-chunk nearby-player lists instead of scanning every player.
 *
 * <p>The list of a chunk for a map type with radius r (chunks) holds every player whose chunk is within r of it, so a
 * player not in the list is horizontally more than 16r blocks away from any point of that chunk. A result is only
 * returned when that bound proves no other player can win; ties keep the vanilla winner (first in players() order).
 * Otherwise the caller falls back to the full scan. NearbyPlayers is updated synchronously on section changes, except
 * on tile workers (deferred level callbacks), where this is never used.
 */
public final class NearestPlayers {
    /** Returned when the query could not be answered here; the caller must do the full scan. */
    public static final Object NOT_HANDLED = new Object();

    private static final NearbyPlayers.NearbyMapType[] TYPES = {
        NearbyPlayers.NearbyMapType.GENERAL_REALLY_SMALL, NearbyPlayers.NearbyMapType.GENERAL_SMALL, NearbyPlayers.NearbyMapType.GENERAL
    };
    private static final int[] RADIUS_BLOCKS = {
        NearbyPlayers.GENERAL_REALLY_SMALL_AREA_VIEW_DISTANCE_BLOCKS, NearbyPlayers.GENERAL_SMALL_AREA_VIEW_DISTANCE_BLOCKS, NearbyPlayers.GENERAL_AREA_VIEW_DISTANCE_BLOCKS
    };
    private static final int MIN_PLAYERS = 24;
    public static final boolean ENABLED = !Boolean.getBoolean("quire.nearestPlayers.off");

    private NearestPlayers() {
    }

    private static boolean usable(final ServerLevel level, final List<ServerPlayer> players) {
        return ENABLED && players.size() >= MIN_PLAYERS && !(Thread.currentThread() instanceof dev.quire.parallel.TileWorker)
            && ((ChunkSystemServerLevel) level).moonrise$getNearbyPlayers().quire$playerCount() == players.size();
    }

    /** EntityGetter#getNearestPlayer(x, y, z, range, predicate). */
    public static Object nearest(final ServerLevel level, final double x, final double y, final double z, final double range,
                                 final Predicate<Entity> predicate) {
        final List<ServerPlayer> players = level.players();
        if (!usable(level, players)) {
            return NOT_HANDLED;
        }
        final NearbyPlayers nearby = ((ChunkSystemServerLevel) level).moonrise$getNearbyPlayers();
        final int chunkX = Mth.floor(x) >> 4;
        final int chunkZ = Mth.floor(z) >> 4;
        for (int t = 0; t < TYPES.length; ++t) {
            final double covered = RADIUS_BLOCKS[t];
            final ReferenceList<ServerPlayer> list = nearby.getPlayersByChunk(chunkX, chunkZ, TYPES[t]);
            double best = -1.0;
            Player result = null;
            if (list != null) {
                final ServerPlayer[] raw = list.getRawDataUnchecked();
                for (int i = 0, len = list.size(); i < len; ++i) {
                    final ServerPlayer player = raw[i];
                    // distance first: the predicate (pure) only runs for a player that would become the result
                    final double dist = player.distanceToSqr(x, y, z);
                    if ((range < 0.0 || dist < range * range) && (best == -1.0 || dist < best || (dist == best && before(players, player, result)))
                        && (predicate == null || predicate.test(player))) {
                        best = dist;
                        result = player;
                    }
                }
            }
            // players outside the list are horizontally farther than `covered` blocks
            if (result != null && best <= covered * covered) {
                return result;
            }
            if (range >= 0.0 && range <= covered) {
                return result; // every player within range is in the list
            }
        }
        return NOT_HANDLED;
    }

    /** ServerEntityGetter#getNearestEntity(players, conditions, source, x, y, z) where passing needs distance(source) <= bound. */
    public static Object nearestTargetable(final ServerLevel level, final net.minecraft.world.entity.ai.targeting.TargetingConditions conditions,
                                           final net.minecraft.world.entity.LivingEntity source, final double x, final double y, final double z) {
        final List<ServerPlayer> players = level.players();
        final double bound = conditions.quire$maxPassingDistance(source);
        if (bound < 0.0 || !usable(level, players)) {
            return NOT_HANDLED;
        }
        final NearbyPlayers nearby = ((ChunkSystemServerLevel) level).moonrise$getNearbyPlayers();
        final int chunkX = Mth.floor(source.getX()) >> 4;
        final int chunkZ = Mth.floor(source.getZ()) >> 4;
        for (int t = 0; t < TYPES.length; ++t) {
            if (bound > RADIUS_BLOCKS[t]) {
                continue; // a passing player could be outside this list
            }
            final ReferenceList<ServerPlayer> list = nearby.getPlayersByChunk(chunkX, chunkZ, TYPES[t]);
            if (list == null) {
                return null;
            }
            double best = -1.0;
            Player result = null;
            final ServerPlayer[] raw = list.getRawDataUnchecked();
            for (int i = 0, len = list.size(); i < len; ++i) {
                final ServerPlayer player = raw[i];
                // distance first: the conditions (pure; may raycast) only run for a player that would become the result
                final double dist = player.distanceToSqr(x, y, z);
                if ((best == -1.0 || dist < best || (dist == best && before(players, player, result))) && conditions.test(level, source, player)) {
                    best = dist;
                    result = player;
                }
            }
            return result;
        }
        return NOT_HANDLED;
    }

    /** Whether a comes before b in the players() list (vanilla keeps the first of equally near players). */
    private static boolean before(final List<ServerPlayer> players, final ServerPlayer a, final Player b) {
        return b != null && players.indexOf(a) < players.indexOf(b);
    }
}
