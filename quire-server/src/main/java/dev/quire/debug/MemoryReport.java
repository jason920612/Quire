package dev.quire.debug;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Walks every loaded full chunk and reports where chunk memory goes: block storage by bits per entry,
 * uniform/air sections, and light nibbles (initialised, and how many hold a single value throughout).
 * Main thread only; used to decide memory optimisations.
 */
public final class MemoryReport {
    private MemoryReport() {
    }

    // estimate for a random-access sparse layout: 64 sub-blocks of 4^3, each uniform (palette id) or stored raw;
    // two-level: a mixed 4^3 block is split again into 8 sub-blocks of 2^3
    private static void sparseEstimate(final net.minecraft.util.BitStorage storage, final int bits, final long[] out) {
        final int[] v = new int[4096];
        storage.unpack(v);
        long oneLevel = 64 + 8, twoLevel = 64 + 8, hybrid = 64 + 8;
        int uniform = 0;
        for (int by = 0; by < 16; by += 4) for (int bz = 0; bz < 16; bz += 4) for (int bx = 0; bx < 16; bx += 4) {
            if (uniformCube(v, bx, by, bz, 4)) {
                uniform++;
                continue;
            }
            oneLevel += 64 * bits / 8;
            long cells = 11;
            for (int sy = by; sy < by + 4; sy += 2) for (int sz = bz; sz < bz + 4; sz += 2) for (int sx = bx; sx < bx + 4; sx += 2) {
                if (!uniformCube(v, sx, sy, sz, 2)) {
                    cells += bits; // 8 entries * bits / 8
                }
            }
            twoLevel += cells;
            // local palette for the 4^3 block: 3 header bytes + palette + 64 entries of ceil(log2(distinct)) bits
            final java.util.BitSet distinct = new java.util.BitSet();
            for (int y = by; y < by + 4; y++) for (int z = bz; z < bz + 4; z++) for (int x = bx; x < bx + 4; x++) {
                distinct.set(v[(y << 8) | (z << 4) | x]);
            }
            final int d = distinct.cardinality();
            final int k = 32 - Integer.numberOfLeadingZeros(d - 1);
            hybrid += Math.min(cells, 3 + d + 8L * k);
        }
        out[0]++;
        out[1] += storage.getRaw().length * 8L;
        out[2] += uniform;
        out[3] += oneLevel;
        out[4] += twoLevel;
        out[5] += 64;
        out[6] += hybrid;
    }

    private static long twoLevelBytes(final int[] v, final int bits) {
        long bytes = 64 + 8;
        for (int by = 0; by < 16; by += 4) for (int bz = 0; bz < 16; bz += 4) for (int bx = 0; bx < 16; bx += 4) {
            if (uniformCube(v, bx, by, bz, 4)) {
                continue;
            }
            bytes += 11;
            for (int sy = by; sy < by + 4; sy += 2) for (int sz = bz; sz < bz + 4; sz += 2) for (int sx = bx; sx < bx + 4; sx += 2) {
                if (!uniformCube(v, sx, sy, sz, 2)) {
                    bytes += bits;
                }
            }
        }
        return bytes;
    }

    private static boolean uniformCube(final int[] v, final int bx, final int by, final int bz, final int size) {
        final int first = v[(by << 8) | (bz << 4) | bx];
        for (int y = by; y < by + size; y++) for (int z = bz; z < bz + size; z++) for (int x = bx; x < bx + size; x++) {
            if (v[(y << 8) | (z << 4) | x] != first) {
                return false;
            }
        }
        return true;
    }

    public static void write(final Path file) throws IOException {
        final StringBuilder sb = new StringBuilder();
        long chunks = 0, sections = 0, airSections = 0, uniformBlockSections = 0, randomTicking = 0;
        long blockStorageBytes = 0, biomeStorageBytes = 0, compressedSections = 0, compressedRawBytes = 0;
        final TreeMap<Integer, long[]> byBits = new TreeMap<>(); // bits -> [sections, bytes, paletteSizeSum]
        final long[] light = new long[11]; // 0 total, 1 init, 2 uniform0, 3 uniform15, 4 uniformOther, 5 null, 6 uninit, 7 hidden
        final long[] skyLight = new long[11];
        final long[] sparse = new long[7];
        final long[] rawSections = new long[2]; // non-uniform plain block storages: cold (compactable), hot // 0 sections, 1 raw bytes, 2 uniform 4^3 sub-blocks, 3 one-level bytes, 4 two-level bytes, 5 sub-blocks

        for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            final LevelChunk[] loaded = ((ChunkSystemServerLevel) level).moonrise$getLoadedChunks().getRawDataUnchecked();
            final int size = ((ChunkSystemServerLevel) level).moonrise$getLoadedChunks().size();
            for (int c = 0; c < size; c++) {
                final LevelChunk chunk = loaded[c];
                if (chunk == null) {
                    continue;
                }
                chunks++;
                for (final LevelChunkSection section : chunk.getSections()) {
                    sections++;
                    if (section.hasOnlyAir()) {
                        airSections++;
                    }
                    if (section.isRandomlyTicking()) {
                        randomTicking++;
                    }
                    final PalettedContainer.Data<?> data = section.getStates().data;
                    final int bits = data.storage().getBits();
                    final long bytes;
                    if (data.storage() instanceof dev.quire.memory.FrozenBitStorage compressed) {
                        bytes = compressed.packedBytes();
                        compressedSections++;
                        compressedRawBytes += compressed.rawLength() * 8L;
                    } else {
                        bytes = data.storage().getRaw().length * 8L;
                    }
                    blockStorageBytes += bytes;
                    if (bits > 0) {
                        sparseEstimate(data.storage(), bits, sparse);
                        if (data.storage() instanceof net.minecraft.util.SimpleBitStorage) {
                            final int hot = section.getStates().quireHotUntil;
                            final boolean cold = hot == 0 || dev.quire.memory.LightFreezer.clock() - hot > 0;
                            rawSections[cold ? 0 : 1]++;
                        }
                    }
                    if (bits == 0) {
                        uniformBlockSections++;
                    }
                    final long[] b = byBits.computeIfAbsent(bits, k -> new long[3]);
                    b[0]++;
                    b[1] += bytes;
                    b[2] += data.palette().getSize();
                    biomeStorageBytes += ((PalettedContainer<?>) section.getBiomes()).data.storage().getRaw().length * 8L;
                }
                count(((StarlightChunk) chunk).starlight$getBlockNibbles(), light);
                count(((StarlightChunk) chunk).starlight$getSkyNibbles(), skyLight);
            }
        }

        // chunks held by chunk holders that are not full (generation/light ring around the loaded area)
        long holders = 0, nonFull = 0, nonFullSections = 0, nonFullStorageBytes = 0, nonFullNonUniform = 0;
        final TreeMap<String, long[]> nonFullByStatus = new TreeMap<>();
        for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            for (final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder holder
                : ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolders()) {
                holders++;
                final net.minecraft.world.level.chunk.ChunkAccess access = holder.getCurrentChunk();
                if (access == null || access instanceof LevelChunk || access instanceof net.minecraft.world.level.chunk.ImposterProtoChunk) {
                    continue;
                }
                nonFull++;
                final long[] st = nonFullByStatus.computeIfAbsent(String.valueOf(access.getPersistedStatus()), k -> new long[2]);
                st[0]++;
                for (final LevelChunkSection section : access.getSections()) {
                    if (section == null) {
                        continue;
                    }
                    nonFullSections++;
                    final long bytes = section.getStates().data.storage().getRaw().length * 8L;
                    nonFullStorageBytes += bytes;
                    st[1] += bytes;
                    if (bytes > 0) {
                        nonFullNonUniform++;
                    }
                }
            }
        }
        sb.append(String.format("chunk holders=%d, non-full chunks in memory=%d sections=%d (non-uniform %d) storage=%.1f MB%n",
            holders, nonFull, nonFullSections, nonFullNonUniform, nonFullStorageBytes / 1048576.0));
        nonFullByStatus.forEach((status, v) -> sb.append(String.format("  %-24s chunks=%d storage=%.1f MB%n", status, v[0], v[1] / 1048576.0)));
        sb.append(String.format("full chunks=%d sections=%d (%.1f per chunk)%n", chunks, sections, sections / (double) Math.max(1, chunks)));
        sb.append(String.format("air-only sections=%d uniform block sections=%d randomly ticking sections=%d%n", airSections, uniformBlockSections, randomTicking));
        sb.append(String.format("block storage long[] = %.1f MB, biome storage = %.1f MB%n", blockStorageBytes / 1048576.0, biomeStorageBytes / 1048576.0));
        sb.append(String.format("compressed sections=%d (%.1f MB raw held as compressed), %s%n", compressedSections, compressedRawBytes / 1048576.0,
            dev.quire.memory.SectionCompactor.summary()));
        sb.append(String.format("single reads of compressed sections=%d (avg %.2f us, total %.1f ms)%n", dev.quire.memory.SectionCompression.SINGLE_READS.sum(),
            dev.quire.memory.SectionCompression.SINGLE_READ_NANOS.sum() / 1000.0 / Math.max(1, dev.quire.memory.SectionCompression.SINGLE_READS.sum()), dev.quire.memory.SectionCompression.SINGLE_READ_NANOS.sum() / 1e6));
        sb.append(String.format("sparse estimate over %d sections: raw %.1f MB, uniform 4^3 sub-blocks %.1f%%, one-level %.1f MB, two-level (4^3 then 2^3) %.1f MB, hybrid (cells or local palette) %.1f MB%n",
            sparse[0], sparse[1] / 1048576.0, 100.0 * sparse[2] / Math.max(1, sparse[5]), sparse[3] / 1048576.0, sparse[4] / 1048576.0, sparse[6] / 1048576.0));
        sb.append(String.format("plain block sections: cold=%d hot=%d%n", rawSections[0], rawSections[1]));
        sb.append("bits  sections     MB   avgPalette\n");
        byBits.forEach((bits, v) -> sb.append(String.format("%4d %9d %7.1f %8.1f%n", bits, v[0], v[1] / 1048576.0, v[2] / (double) Math.max(1, v[0]))));
        if (!dev.quire.memory.SectionCompression.INFLATE_CALLERS.isEmpty()) {
            sb.append("inflated by:\n");
            dev.quire.memory.SectionCompression.INFLATE_CALLERS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(15)
                .forEach(e -> sb.append(String.format("%9d  %s%n", e.getValue().sum(), e.getKey())));
        }
        appendLight(sb, "block light", light);
        appendLight(sb, "sky light", skyLight);
        Files.writeString(file, sb.toString());
    }

    private static void appendLight(final StringBuilder sb, final String name, final long[] v) {
        sb.append(String.format("%s: nibbles=%d initialised=%d (%.1f MB) uniform0=%d uniform15=%d uniformOther=%d null=%d uninit=%d hidden=%d%n",
            name, v[0], v[1], v[1] * 2048 / 1048576.0, v[2], v[3], v[4], v[5], v[6], v[7]));
        sb.append(String.format("  non-uniform=%d raw %.1f MB, two-level sparse estimate %.1f MB, frozen=%d (total frozen %d)%n", v[8], v[8] * 2048 / 1048576.0, v[9] / 1048576.0,
            v[10], dev.quire.memory.LightFreezer.FROZEN.sum()));
        REASONS.forEach((k, v2) -> sb.append("  reason ").append(k).append(": ").append(v2[0]).append(System.lineSeparator()));
        REASONS.clear();
        sb.append(String.format("  freezer scans=%d queued=%d ran=%d%n", dev.quire.memory.LightFreezer.CANDIDATE_SCANS.sum(), dev.quire.memory.LightFreezer.QUEUED.sum(), dev.quire.memory.LightFreezer.RAN.sum()));
    }

    private static final java.util.TreeMap<String, long[]> REASONS = new java.util.TreeMap<>();

    private static void count(final SWMRNibbleArray[] nibbles, final long[] out) {
        if (nibbles == null) {
            return;
        }
        for (final SWMRNibbleArray nibble : nibbles) {
            if (nibble == null) {
                continue;
            }
            out[0]++;
            if (nibble.isNullNibbleVisible()) {
                out[5]++;
            } else if (nibble.isUninitialisedVisible()) {
                out[6]++;
            } else if (nibble.isHiddenVisible()) {
                out[7]++;
            } else if (nibble.isInitialisedVisible()) {
                out[1]++;
                if (nibble.isFrozen()) {
                    out[10]++;
                }
                REASONS.computeIfAbsent(nibble.quire$notFreezableReason() + " idle=" + (dev.quire.memory.LightFreezer.clock() - nibble.quireLastUpdate > 30), k -> new long[1])[0]++;
                final int first = nibble.getVisible(0);
                boolean uniform = true;
                for (int i = 1; i < 4096; i++) {
                    if (nibble.getVisible(i) != first) {
                        uniform = false;
                        break;
                    }
                }
                if (uniform) {
                    out[first == 0 ? 2 : first == 15 ? 3 : 4]++;
                } else {
                    final int[] v = new int[4096];
                    for (int i = 0; i < 4096; i++) {
                        v[i] = nibble.getVisible(i);
                    }
                    out[8]++;
                    out[9] += twoLevelBytes(v, 4);
                }
            }
        }
    }
}
