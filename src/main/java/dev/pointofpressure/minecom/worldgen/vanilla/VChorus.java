package dev.pointofpressure.minecom.worldgen.vanilla;

import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.Map;

/**
 * Chorus plants: the branching stem-and-flower growth scattered across the End
 * highlands. Faithful port of ChorusFlowerBlock.generatePlant/growTreeRecursive
 * (the worldgen entry — the organic post-placement randomTick regrowth is not
 * ported) plus ChorusPlantBlock.getStateWithConnections for the 6-way connection
 * blockstate. Placement itself (count 0-4 per chunk, in_square, MOTION_BLOCKING
 * heightmap, end_highlands only) is a standalone per-chunk pass on the live
 * instance, matching the project's other bounded End features (spikes, dragon).
 */
public final class VChorus {

    private static final int[][] HORIZONTAL = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}}; // N,E,S,W

    private VChorus() {}

    /** Scatter chorus plants across one End chunk (call once per chunk load). */
    public static void placeChunk(Instance end, int chunkX, int chunkZ, long worldSeed,
                                   dev.pointofpressure.minecom.worldgen.vanilla.VEndGen gen) {
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(worldSeed);
        random.setLargeFeatureSeed(worldSeed, chunkX, chunkZ);
        int count = random.nextInt(5); // uniform 0..4 inclusive

        for (int i = 0; i < count; i++) {
            int x = baseX + random.nextInt(16);
            int z = baseZ + random.nextInt(16);
            if (!"minecraft:end_highlands".equals(gen.biomeAt(x, z))) continue;
            int y = topSolidY(end, x, z);
            if (y < 0) continue;
            int originY = y + 1;
            if (!end.getBlock(x, originY, z).isAir()) continue;
            if (!end.getBlock(x, originY - 1, z).compare(Block.END_STONE)) continue; // #supports_chorus_plant
            generatePlant(end, x, originY, z, random, x, z, 8);
        }
    }

    /** Test hook: grow a plant at a fixed spot, skipping the biome/heightmap search. */
    public static void testPlaceAt(Instance end, int x, int y, int z, long worldSeed) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(worldSeed);
        generatePlant(end, x, y, z, random, x, z, 8);
    }

    private static int topSolidY(Instance end, int x, int z) {
        for (int y = 127; y >= 0; y--) if (!end.getBlock(x, y, z).isAir()) return y;
        return -1;
    }

    /** ChorusFlowerBlock.generatePlant. */
    private static void generatePlant(Instance end, int x, int y, int z, VSurface.LegacyRandom random,
                                       int startX, int startZ, int maxHorizontalSpread) {
        setPlant(end, x, y, z);
        growTreeRecursive(end, x, y, z, random, startX, startZ, maxHorizontalSpread, 0);
    }

    /** ChorusFlowerBlock.growTreeRecursive. */
    private static void growTreeRecursive(Instance end, int cx, int cy, int cz, VSurface.LegacyRandom random,
                                           int startX, int startZ, int maxSpread, int depth) {
        int height = random.nextInt(4) + 1;
        if (depth == 0) height++;

        for (int i = 0; i < height; i++) {
            int ty = cy + i + 1;
            if (!allNeighborsEmpty(end, cx, ty, cz, -1)) return;
            setPlant(end, cx, ty, cz);
            setPlant(end, cx, ty - 1, cz);
        }

        boolean placedStem = false;
        if (depth < 4) {
            int stems = random.nextInt(4);
            if (depth == 0) stems++;

            for (int i = 0; i < stems; i++) {
                int di = random.nextInt(4);
                int dx = HORIZONTAL[di][0], dz = HORIZONTAL[di][1];
                int tx = cx + dx, ty = cy + height, tz = cz + dz;
                if (Math.abs(tx - startX) < maxSpread && Math.abs(tz - startZ) < maxSpread
                        && end.getBlock(tx, ty, tz).isAir() && end.getBlock(tx, ty - 1, tz).isAir()
                        && allNeighborsEmpty(end, tx, ty, tz, (di + 2) % 4)) {
                    placedStem = true;
                    setPlant(end, tx, ty, tz);
                    setPlant(end, tx - dx, ty, tz - dz);
                    growTreeRecursive(end, tx, ty, tz, random, startX, startZ, maxSpread, depth + 1);
                }
            }
        }

        if (!placedStem) {
            end.setBlock(cx, cy + height, cz, Block.CHORUS_FLOWER.withProperty("age", "5"));
        }
    }

    /** allNeighborsEmpty(level, pos, ignoreDir): the 4 horizontal neighbors, one optionally skipped. */
    private static boolean allNeighborsEmpty(Instance end, int x, int y, int z, int ignoreDir) {
        for (int d = 0; d < 4; d++) {
            if (d == ignoreDir) continue;
            if (!end.getBlock(x + HORIZONTAL[d][0], y, z + HORIZONTAL[d][1]).isAir()) return false;
        }
        return true;
    }

    /** setBlock(pos, ChorusPlantBlock.getStateWithConnections(...)): recompute this cell's 6-way links. */
    private static void setPlant(Instance end, int x, int y, int z) {
        end.setBlock(x, y, z, Block.CHORUS_PLANT.withProperties(connections(end, x, y, z)));
    }

    private static Map<String, String> connections(Instance end, int x, int y, int z) {
        boolean down = isPlantOrFlower(end, x, y - 1, z) || end.getBlock(x, y - 1, z).compare(Block.END_STONE);
        boolean up = isPlantOrFlower(end, x, y + 1, z);
        boolean north = isPlantOrFlower(end, x, y, z - 1);
        boolean south = isPlantOrFlower(end, x, y, z + 1);
        boolean east = isPlantOrFlower(end, x + 1, y, z);
        boolean west = isPlantOrFlower(end, x - 1, y, z);
        return Map.of("down", String.valueOf(down), "up", String.valueOf(up),
                "north", String.valueOf(north), "south", String.valueOf(south),
                "east", String.valueOf(east), "west", String.valueOf(west));
    }

    private static boolean isPlantOrFlower(Instance end, int x, int y, int z) {
        Block b = end.getBlock(x, y, z);
        return b.compare(Block.CHORUS_PLANT) || b.compare(Block.CHORUS_FLOWER);
    }
}
