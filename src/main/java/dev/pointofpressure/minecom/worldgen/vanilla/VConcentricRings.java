package dev.pointofpressure.minecom.worldgen.vanilla;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Bit-exact port of ChunkGeneratorStructureState.generateRingPositions for
 * ConcentricRingsStructurePlacement (used only by strongholds): a biome-biased
 * outward walk in expanding rings, each candidate resolved via a single 112-block
 * biome-source scan (BiomeSource.findBiomeHorizontal, findClosest=false — reservoir
 * sampling over one full square, not a spiral) using a forked random stream.
 * Independent per-candidate, so computed with a parallel stream (the project's
 * multithreading thesis) — vanilla itself farms this out to a background executor.
 */
public final class VConcentricRings {
    private final long seed;
    private final VBiome biomes;
    private final Set<String> preferredBiomes;
    private final int distance, spread, count;
    private volatile List<int[]> positions;

    public VConcentricRings(long seed, VBiome biomes, Set<String> preferredBiomes,
                             int distance, int spread, int count) {
        this.seed = seed;
        this.biomes = biomes;
        this.preferredBiomes = preferredBiomes;
        this.distance = distance;
        this.spread = spread;
        this.count = count;
    }

    /** The (up to {@code count}) ring chunk positions, computed and cached on first call. */
    public List<int[]> positions() {
        List<int[]> p = positions;
        if (p == null) {
            synchronized (this) {
                p = positions;
                if (p == null) positions = p = generate();
            }
        }
        return p;
    }

    /** Nearest ring position to a chunk coordinate (by squared chunk distance), or null if none. */
    public int[] nearestTo(int chunkX, int chunkZ) {
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (int[] pos : positions()) {
            long dx = pos[0] - chunkX, dz = pos[1] - chunkZ;
            long d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = pos; }
        }
        return best;
    }

    private List<int[]> generate() {
        if (count == 0) return List.of();

        // walk the angle/circle/spread state serially (each step depends on the last),
        // but each candidate's biome-search draws from an independently forked stream —
        // so the expensive part (the search itself) parallelizes cleanly.
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(seed);
        double angle = random.nextDouble() * Math.PI * 2;
        int positionInCircle = 0;
        int circle = 0;
        int spreadNow = spread;
        int[] initialX = new int[count], initialZ = new int[count];
        VSurface.LegacyRandom[] forks = new VSurface.LegacyRandom[count];

        for (int i = 0; i < count; i++) {
            double dist = 4 * distance + distance * circle * 6 + (random.nextDouble() - 0.5) * (distance * 2.5);
            initialX[i] = (int) Math.round(Math.cos(angle) * dist);
            initialZ[i] = (int) Math.round(Math.sin(angle) * dist);
            forks[i] = random.fork();

            angle += (Math.PI * 2) / spreadNow;
            if (++positionInCircle == spreadNow) {
                circle++;
                positionInCircle = 0;
                spreadNow += 2 * spreadNow / (circle + 1);
                spreadNow = Math.min(spreadNow, count - i);
                angle += random.nextDouble() * Math.PI * 2;
            }
        }

        int[][] resolved = IntStream.range(0, count).parallel()
                .mapToObj(i -> {
                    int[] found = findBiomePosition(initialX[i], initialZ[i], forks[i]);
                    return found != null ? found : new int[]{initialX[i], initialZ[i]};
                })
                .toArray(int[][]::new);

        List<int[]> out = new ArrayList<>(count);
        for (int[] pos : resolved) out.add(pos);
        return out;
    }

    /** BiomeSource.findBiomeHorizontal(sectionToBlock(cx,8),0,sectionToBlock(cz,8),112,allowed,random,false). */
    private int[] findBiomePosition(int chunkX, int chunkZ, VSurface.LegacyRandom random) {
        int originX = (chunkX << 4) + 8, originZ = (chunkZ << 4) + 8;
        int noiseCenterX = originX >> 2, noiseCenterZ = originZ >> 2;
        int noiseRadius = 112 >> 2; // QuartPos.fromBlock(112) = 28

        int[] result = null;
        int found = 0;
        for (int z = -noiseRadius; z <= noiseRadius; z++) {
            for (int x = -noiseRadius; x <= noiseRadius; x++) {
                int noiseX = noiseCenterX + x, noiseZ = noiseCenterZ + z;
                String biome = biomes.biomeAt(noiseX, 0, noiseZ);
                if (!preferredBiomes.contains(biome)) continue;
                if (result == null || random.nextInt(found + 1) == 0) {
                    result = new int[]{(noiseX << 2) >> 4, (noiseZ << 2) >> 4}; // block coord -> chunk coord
                }
                found++;
            }
        }
        return result;
    }
}
