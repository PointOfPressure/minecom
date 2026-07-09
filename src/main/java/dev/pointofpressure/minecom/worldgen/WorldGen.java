package dev.pointofpressure.minecom.worldgen;

import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

import java.util.Random;

/**
 * Overworld generator v2: continental terrain with oceans and mountain ranges,
 * temperature/moisture biomes, 3D-noise caves with deep lava, vanilla-ish ore
 * distribution (deepslate variants at depth), and per-biome decoration.
 * Fully deterministic from {@link #SEED}.
 */
public final class WorldGen implements Generator {
    private static final long SEED = 0x4D696E65636F6DL; // "Minecom"
    public static final int SEA_LEVEL = 62;
    private static final int LAVA_LEVEL = -54;
    private static final int WORLD_BOTTOM = -64;

    private enum BiomeKind { PLAINS, FOREST, BIRCH_FOREST, TAIGA, SNOWY_PLAINS, DESERT, BEACH, OCEAN, DEEP_OCEAN, PEAKS }

    @Override
    public void generate(GenerationUnit unit) {
        Point start = unit.absoluteStart();
        int minY = start.blockY();
        int maxY = minY + unit.size().blockY();
        int baseX = start.blockX(), baseZ = start.blockZ();
        var mod = unit.modifier();

        int[] heights = new int[256];
        BiomeKind[] biomes = new BiomeKind[256];
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int wx = baseX + dx, wz = baseZ + dz;
                int h = surfaceHeight(wx, wz);
                BiomeKind biome = biomeAt(wx, wz, h);
                heights[dx * 16 + dz] = h;
                biomes[dx * 16 + dz] = biome;
                column(unit, mod, wx, wz, h, biome, minY);
            }
        }
        ores(mod, baseX, baseZ, heights, biomes);
        clientBiomes(mod, baseX, baseZ, heights, minY, maxY);
    }

    // ------------------------------------------------------------------ terrain

    private void column(GenerationUnit unit, net.minestom.server.instance.generator.UnitModifier mod,
                        int wx, int wz, int h, BiomeKind biome, int minY) {
        mod.setBlock(wx, WORLD_BOTTOM, wz, Block.BEDROCK);
        int surfaceDepth = switch (biome) {
            case DESERT, BEACH -> 7;    // sand + sandstone body
            case PEAKS -> 0;
            default -> 4;
        };
        boolean surfaceCarved = carved(wx, h, wz) && h > SEA_LEVEL; // cave entrance at the surface

        for (int y = WORLD_BOTTOM + 1; y <= h; y++) {
            // bedrock gradient
            if (y <= WORLD_BOTTOM + 4 && hash01(mix(wx, y * 7 + 1, wz)) < (WORLD_BOTTOM + 5 - y) / 5.0) {
                mod.setBlock(wx, y, wz, Block.BEDROCK);
                continue;
            }
            boolean nearSeaFloor = h <= SEA_LEVEL + 1 && y > h - 8;
            if (!nearSeaFloor && carved(wx, y, wz)) {
                if (y <= LAVA_LEVEL) mod.setBlock(wx, y, wz, Block.LAVA);
                continue; // air
            }
            Block block;
            if (y > h - surfaceDepth) {
                block = surfaceBlock(biome, y, h);
            } else if (y < 0) {
                block = Block.DEEPSLATE;
            } else if (y < 8 && hash01(mix(wx, y, wz)) > y / 8.0) {
                block = Block.DEEPSLATE;
            } else {
                block = Block.STONE;
            }
            mod.setBlock(wx, y, wz, block);
        }

        if (h < SEA_LEVEL) {
            for (int y = h + 1; y <= SEA_LEVEL; y++) mod.setBlock(wx, y, wz, Block.WATER);
            return;
        }
        if (surfaceCarved) return;

        // top block + decorations
        if (biome == BiomeKind.SNOWY_PLAINS || biome == BiomeKind.TAIGA || (biome == BiomeKind.PEAKS && h > 120)) {
            mod.setBlock(wx, h + 1, wz, Block.SNOW);
        }
        decorate(unit, mod, wx, wz, h, biome);
    }

    private Block surfaceBlock(BiomeKind biome, int y, int h) {
        return switch (biome) {
            case DESERT, BEACH -> y > h - 4 ? Block.SAND : Block.SANDSTONE;
            case OCEAN, DEEP_OCEAN -> h > 55 ? Block.SAND : Block.GRAVEL;
            case PEAKS -> Block.STONE;
            default -> y == h ? Block.GRASS_BLOCK : Block.DIRT;
        };
    }

    /** Deterministic surface height; the single source of truth used by terrain, spawn search and ores. */
    public static int surfaceHeight(int x, int z) {
        double c = fbm2(x, z, 1 / 512.0, 3, 0);              // continentalness
        double hills = fbm2(x, z, 1 / 80.0, 4, 1);
        double h = 66 + c * 30 + hills * (10 + Math.max(0, c) * 14);
        double m = c - 0.35;
        if (m > 0) {
            double ridge = 1 - Math.abs(fbm2(x, z, 1 / 160.0, 3, 2));
            h += m * ridge * 200;
        }
        return (int) Math.max(WORLD_BOTTOM + 24, Math.min(250, Math.round(h)));
    }

    private static BiomeKind biomeAt(int x, int z, int h) {
        if (h > 122) return BiomeKind.PEAKS;
        if (h < SEA_LEVEL - 14) return BiomeKind.DEEP_OCEAN;
        if (h < SEA_LEVEL - 1) return BiomeKind.OCEAN;
        double temp = fbm2(x, z, 1 / 700.0, 2, 3) - (h - 70) * 0.004;
        double moist = fbm2(x, z, 1 / 550.0, 2, 4);
        if (h <= SEA_LEVEL + 2) return temp > 0.45 || moist < -0.45 ? BiomeKind.DESERT : BiomeKind.BEACH;
        if (temp > 0.4 && moist < 0.0) return BiomeKind.DESERT;
        if (temp < -0.42) return moist > 0.1 ? BiomeKind.TAIGA : BiomeKind.SNOWY_PLAINS;
        if (moist > 0.32) return temp > 0.12 ? BiomeKind.BIRCH_FOREST : BiomeKind.FOREST;
        if (moist > 0.18) return BiomeKind.FOREST;
        return BiomeKind.PLAINS;
    }

    public static boolean isSafeSpawn(int x, int z) {
        int h = surfaceHeight(x, z);
        if (h <= SEA_LEVEL + 2 || h > 110) return false;
        return !carved(x, h, z);
    }

    // ------------------------------------------------------------------ caves

    private static boolean carved(int x, int y, int z) {
        if (y <= WORLD_BOTTOM + 5) return false;
        double n = fbm3(x, y * 1.6, z, 1 / 36.0, 2, 10);
        if (Math.abs(n) < 0.055) return true;
        double cheese = fbm3(x, y * 1.2, z, 1 / 72.0, 2, 11);
        return cheese < -0.62;
    }

    // ------------------------------------------------------------------ decoration

    private void decorate(GenerationUnit unit, net.minestom.server.instance.generator.UnitModifier mod,
                          int wx, int wz, int h, BiomeKind biome) {
        double roll = hash01(mix(wx, 777, wz));
        switch (biome) {
            case PLAINS -> {
                if (roll < 0.0008) tree(unit, wx, h + 1, wz, Block.OAK_LOG, Block.OAK_LEAVES, false);
                else if (roll < 0.06) mod.setBlock(wx, h + 1, wz, Block.SHORT_GRASS);
                else if (roll < 0.068) mod.setBlock(wx, h + 1, wz, roll < 0.064 ? Block.DANDELION : Block.POPPY);
                sugarCane(mod, wx, wz, h, roll);
            }
            case FOREST, BIRCH_FOREST -> {
                boolean birch = biome == BiomeKind.BIRCH_FOREST ? roll * 7919 % 1 < 0.7 : roll * 7919 % 1 < 0.15;
                if (roll < 0.018) tree(unit, wx, h + 1, wz,
                        birch ? Block.BIRCH_LOG : Block.OAK_LOG,
                        birch ? Block.BIRCH_LEAVES : Block.OAK_LEAVES, false);
                else if (roll < 0.10) mod.setBlock(wx, h + 1, wz, Block.SHORT_GRASS);
                else if (roll < 0.104) mod.setBlock(wx, h + 1, wz, Block.POPPY);
                sugarCane(mod, wx, wz, h, roll);
            }
            case TAIGA -> {
                if (roll < 0.02) tree(unit, wx, h + 1, wz, Block.SPRUCE_LOG, Block.SPRUCE_LEAVES, true);
                else if (roll < 0.05) mod.setBlock(wx, h + 1, wz, Block.SHORT_GRASS);
            }
            case SNOWY_PLAINS -> {
                if (roll < 0.002) tree(unit, wx, h + 1, wz, Block.SPRUCE_LOG, Block.SPRUCE_LEAVES, true);
            }
            case DESERT -> {
                if (roll < 0.0025) {
                    int tall = 1 + (int) (hash01(mix(wx, 778, wz)) * 3);
                    for (int i = 1; i <= tall; i++) mod.setBlock(wx, h + i, wz, Block.CACTUS);
                } else if (roll < 0.008) {
                    mod.setBlock(wx, h + 1, wz, Block.DEAD_BUSH);
                }
            }
            case BEACH -> sugarCane(mod, wx, wz, h, roll);
            default -> { }
        }
    }

    private void sugarCane(net.minestom.server.instance.generator.UnitModifier mod, int wx, int wz, int h, double roll) {
        if (h != SEA_LEVEL || roll * 31 % 1 >= 0.10) return;
        boolean nearWater = surfaceHeight(wx + 1, wz) < SEA_LEVEL || surfaceHeight(wx - 1, wz) < SEA_LEVEL
                || surfaceHeight(wx, wz + 1) < SEA_LEVEL || surfaceHeight(wx, wz - 1) < SEA_LEVEL;
        if (!nearWater) return;
        int tall = 2 + (int) (hash01(mix(wx, 779, wz)) * 3);
        for (int i = 1; i <= tall; i++) mod.setBlock(wx, h + i, wz, Block.SUGAR_CANE);
    }

    private void tree(GenerationUnit unit, int x, int y, int z, Block log, Block leaves, boolean spruce) {
        int trunk = spruce ? 6 + (int) (hash01(mix(x, 780, z)) * 3) : 4 + (int) (hash01(mix(x, 780, z)) * 3);
        unit.fork(setter -> {
            for (int dy = 0; dy < trunk; dy++) setter.setBlock(x, y + dy, z, log);
            int top = y + trunk;
            if (spruce) {
                for (int layer = 0; layer < trunk - 2; layer++) {
                    int radius = (layer % 2 == 0) ? 1 + layer / 3 : 0;
                    int ly = top - layer;
                    for (int lx = -radius; lx <= radius; lx++) {
                        for (int lz = -radius; lz <= radius; lz++) {
                            if (lx == 0 && lz == 0) continue;
                            if (Math.abs(lx) == radius && Math.abs(lz) == radius && radius > 1) continue;
                            setter.setBlock(x + lx, ly, z + lz, leaves);
                        }
                    }
                }
                setter.setBlock(x, top + 1, z, leaves);
            } else {
                for (int lx = -2; lx <= 2; lx++) {
                    for (int lz = -2; lz <= 2; lz++) {
                        for (int ly = -2; ly <= 0; ly++) {
                            if (lx == 0 && lz == 0 && ly < 0) continue;
                            if (Math.abs(lx) == 2 && Math.abs(lz) == 2) continue;
                            if (ly == 0 && (Math.abs(lx) > 1 || Math.abs(lz) > 1)) continue;
                            setter.setBlock(x + lx, top + ly, z + lz, leaves);
                        }
                    }
                }
                setter.setBlock(x, top + 1, z, leaves);
            }
        });
    }

    // ------------------------------------------------------------------ ores

    private record Ore(Block stone, Block deepslate, int attempts, int size, int minY, int maxY) {}

    private static final Ore[] ORES = {
            new Ore(Block.COAL_ORE, Block.DEEPSLATE_COAL_ORE, 14, 12, 0, 130),
            new Ore(Block.COPPER_ORE, Block.DEEPSLATE_COPPER_ORE, 8, 9, -16, 112),
            new Ore(Block.IRON_ORE, Block.DEEPSLATE_IRON_ORE, 12, 7, -56, 72),
            new Ore(Block.GOLD_ORE, Block.DEEPSLATE_GOLD_ORE, 4, 6, -60, 30),
            new Ore(Block.REDSTONE_ORE, Block.DEEPSLATE_REDSTONE_ORE, 6, 6, -60, -16),
            new Ore(Block.LAPIS_ORE, Block.DEEPSLATE_LAPIS_ORE, 3, 5, -56, 30),
            new Ore(Block.DIAMOND_ORE, Block.DEEPSLATE_DIAMOND_ORE, 6, 5, -60, -4),
    };

    private void ores(net.minestom.server.instance.generator.UnitModifier mod,
                      int baseX, int baseZ, int[] heights, BiomeKind[] biomes) {
        Random rng = new Random(mix(baseX >> 4, 12345, baseZ >> 4) ^ SEED);
        for (Ore ore : ORES) {
            for (int i = 0; i < ore.attempts(); i++) {
                int ox = 3 + rng.nextInt(10), oz = 3 + rng.nextInt(10);
                int h = heights[ox * 16 + oz];
                int top = Math.min(ore.maxY(), h - 5);
                if (top <= ore.minY()) continue;
                int oy = ore.minY() + rng.nextInt(top - ore.minY());
                int size = 2 + rng.nextInt(ore.size());
                int x = ox, y = oy, z = oz;
                for (int s = 0; s < size; s++) {
                    int wx = baseX + x, wz = baseZ + z;
                    if (y > WORLD_BOTTOM + 5 && y < heights[x * 16 + z] - 4 && !carved(wx, y, wz)) {
                        mod.setBlock(wx, y, wz, y < 4 ? ore.deepslate() : ore.stone());
                    }
                    x = Math.max(3, Math.min(12, x + rng.nextInt(3) - 1));
                    z = Math.max(3, Math.min(12, z + rng.nextInt(3) - 1));
                    y = Math.max(ore.minY(), y + rng.nextInt(3) - 1);
                }
            }
        }
        // emerald: rare singles in peaks
        if (biomes[8 * 16 + 8] == BiomeKind.PEAKS) {
            for (int i = 0; i < 4; i++) {
                int ox = 3 + rng.nextInt(10), oz = 3 + rng.nextInt(10);
                int h = heights[ox * 16 + oz];
                if (h < 100) continue;
                int oy = 90 + rng.nextInt(h - 95);
                if (!carved(baseX + ox, oy, baseZ + oz)) {
                    mod.setBlock(baseX + ox, oy, baseZ + oz, Block.EMERALD_ORE);
                }
            }
        }
    }

    // ------------------------------------------------------------------ client biomes

    private void clientBiomes(net.minestom.server.instance.generator.UnitModifier mod,
                              int baseX, int baseZ, int[] heights, int minY, int maxY) {
        for (int bx = 0; bx < 4; bx++) {
            for (int bz = 0; bz < 4; bz++) {
                int wx = baseX + bx * 4 + 2, wz = baseZ + bz * 4 + 2;
                RegistryKey<Biome> key = clientBiome(biomeAt(wx, wz, heights[(bx * 4 + 2) * 16 + bz * 4 + 2]));
                for (int y = minY; y < maxY; y += 4) {
                    mod.setBiome(wx, y, wz, key);
                }
            }
        }
    }

    private static RegistryKey<Biome> clientBiome(BiomeKind kind) {
        return switch (kind) {
            case PLAINS -> Biome.PLAINS;
            case FOREST -> Biome.FOREST;
            case BIRCH_FOREST -> Biome.BIRCH_FOREST;
            case TAIGA -> Biome.TAIGA;
            case SNOWY_PLAINS -> Biome.SNOWY_PLAINS;
            case DESERT -> Biome.DESERT;
            case BEACH -> Biome.BEACH;
            case OCEAN -> Biome.OCEAN;
            case DEEP_OCEAN -> Biome.DEEP_OCEAN;
            case PEAKS -> Biome.STONY_PEAKS;
        };
    }

    // ------------------------------------------------------------------ noise

    /** 2D fractal value noise in [-1, 1]; `salt` decorrelates the fields. */
    private static double fbm2(double x, double z, double freq, int octaves, int salt) {
        double sum = 0, amp = 1, norm = 0;
        double fx = x * freq, fz = z * freq;
        for (int o = 0; o < octaves; o++) {
            sum += amp * value2(fx, fz, salt * 31 + o);
            norm += amp;
            amp *= 0.5;
            fx *= 2;
            fz *= 2;
        }
        return sum / norm;
    }

    private static double fbm3(double x, double y, double z, double freq, int octaves, int salt) {
        double sum = 0, amp = 1, norm = 0;
        double fx = x * freq, fy = y * freq, fz = z * freq;
        for (int o = 0; o < octaves; o++) {
            sum += amp * value3(fx, fy, fz, salt * 31 + o);
            norm += amp;
            amp *= 0.5;
            fx *= 2;
            fy *= 2;
            fz *= 2;
        }
        return sum / norm;
    }

    private static double value2(double x, double z, int salt) {
        int x0 = floor(x), z0 = floor(z);
        double fx = smooth(x - x0), fz = smooth(z - z0);
        double v00 = lattice(x0, salt, z0), v10 = lattice(x0 + 1, salt, z0);
        double v01 = lattice(x0, salt, z0 + 1), v11 = lattice(x0 + 1, salt, z0 + 1);
        double a = v00 + (v10 - v00) * fx;
        double b = v01 + (v11 - v01) * fx;
        return (a + (b - a) * fz) * 2 - 1;
    }

    private static double value3(double x, double y, double z, int salt) {
        int x0 = floor(x), y0 = floor(y), z0 = floor(z);
        double fx = smooth(x - x0), fy = smooth(y - y0), fz = smooth(z - z0);
        double v000 = lattice3(x0, y0, z0, salt), v100 = lattice3(x0 + 1, y0, z0, salt);
        double v010 = lattice3(x0, y0 + 1, z0, salt), v110 = lattice3(x0 + 1, y0 + 1, z0, salt);
        double v001 = lattice3(x0, y0, z0 + 1, salt), v101 = lattice3(x0 + 1, y0, z0 + 1, salt);
        double v011 = lattice3(x0, y0 + 1, z0 + 1, salt), v111 = lattice3(x0 + 1, y0 + 1, z0 + 1, salt);
        double x00 = v000 + (v100 - v000) * fx, x10 = v010 + (v110 - v010) * fx;
        double x01 = v001 + (v101 - v001) * fx, x11 = v011 + (v111 - v011) * fx;
        double y0v = x00 + (x10 - x00) * fy, y1v = x01 + (x11 - x01) * fy;
        return (y0v + (y1v - y0v) * fz) * 2 - 1;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lattice(int x, int salt, int z) {
        return hash01(mix(x, salt, z));
    }

    private static double lattice3(int x, int y, int z, int salt) {
        return hash01(mix(x, y * 668265263 + salt, z));
    }

    private static long mix(int x, int y, int z) {
        return x * 374761393L + y * 972663749L + z * 144305901L;
    }

    private static double hash01(long n) {
        long h = n ^ SEED;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) / (double) (1L << 53);
    }
}
