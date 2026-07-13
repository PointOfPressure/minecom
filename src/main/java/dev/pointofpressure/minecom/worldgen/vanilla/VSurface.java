package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.instance.block.Block;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vanilla SurfaceSystem + SurfaceRules: replaces default stone with the biome
 * surface (grass/dirt/sand/badlands bands/snow...) by interpreting the real
 * surface_rule JSON, plus the eroded-badlands hoodoo and frozen-ocean iceberg
 * extensions. All noises, randoms and the biome zoom match vanilla bit-for-bit.
 */
public final class VSurface {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    private final VAquifer.Context genCtx;
    private final VBiome biomeSource;
    private final long biomeZoomSeed;
    private final int seaLevel;

    private final VNoise.Normal surfaceNoise;
    private final VNoise.Normal surfaceSecondaryNoise;
    private final VNoise.Normal clayBandsOffsetNoise;
    private final VNoise.Normal badlandsPillarNoise;
    private final VNoise.Normal badlandsPillarRoofNoise;
    private final VNoise.Normal badlandsSurfaceNoise;
    private final VNoise.Normal icebergPillarNoise;
    private final VNoise.Normal icebergPillarRoofNoise;
    private final VNoise.Normal icebergSurfaceNoise;
    private final XRandom.Positional noiseRandom;
    private final Map<String, XRandom.Positional> namedRandoms = new java.util.concurrent.ConcurrentHashMap<>();
    private final VDensity.Builder builder;
    private final Block[] clayBands;
    private final RuleSource ruleSource;

    /** biome id -> [baseTemperature, frozenModifier] */
    private final Map<String, float[]> climate = new HashMap<>();

    public VSurface(VDensity.Builder builder, VAquifer.Context genCtx, VBiome biomeSource,
                    JsonElement surfaceRuleJson, long seed, int seaLevel) {
        this.builder = builder;
        this.genCtx = genCtx;
        this.biomeSource = biomeSource;
        this.biomeZoomSeed = obfuscateSeed(seed);
        this.seaLevel = seaLevel;
        this.noiseRandom = builder.seedRandom();
        this.surfaceNoise = builder.noise("minecraft:surface");
        this.surfaceSecondaryNoise = builder.noise("minecraft:surface_secondary");
        this.clayBandsOffsetNoise = builder.noise("minecraft:clay_bands_offset");
        this.badlandsPillarNoise = builder.noise("minecraft:badlands_pillar");
        this.badlandsPillarRoofNoise = builder.noise("minecraft:badlands_pillar_roof");
        this.badlandsSurfaceNoise = builder.noise("minecraft:badlands_surface");
        this.icebergPillarNoise = builder.noise("minecraft:iceberg_pillar");
        this.icebergPillarRoofNoise = builder.noise("minecraft:iceberg_pillar_roof");
        this.icebergSurfaceNoise = builder.noise("minecraft:iceberg_surface");
        this.clayBands = generateBands(noiseRandom.fromHashOf("minecraft:clay_bands"));
        this.ruleSource = parseRule(surfaceRuleJson);

        try (var in = VSurface.class.getResourceAsStream("/vanilla/biome_climate.json")) {
            JsonObject data = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            for (var e : data.entrySet()) {
                JsonArray a = e.getValue().getAsJsonArray();
                climate.put(e.getKey(), new float[]{a.get(0).getAsFloat(),
                        "frozen".equals(a.get(1).getAsString()) ? 1 : 0,
                        a.get(2).getAsBoolean() ? 1 : 0});
            }
        } catch (Exception e) {
            throw new IllegalStateException("Missing biome climate bundle", e);
        }
    }

    // ================================================================== chunk column data

    /** Pre-surface chunk blocks + WORLD_SURFACE_WG heightmap, mutated by the surface pass. */
    public static final class ChunkData {
        final Block[] blocks = new Block[16 * 16 * HEIGHT]; // null = air
        final int[] heights = new int[16 * 16];             // top non-air y, MIN_Y-1 if none

        public ChunkData() {
            java.util.Arrays.fill(heights, MIN_Y - 1);
        }

        static int index(int x, int y, int z) {
            return ((y - MIN_Y) * 16 + z) * 16 + x;
        }

        public Block get(int x, int y, int z) {
            if (y < MIN_Y || y >= MIN_Y + HEIGHT) return null;
            return blocks[index(x, y, z)];
        }

        public void set(int x, int y, int z, Block block) {
            if (y < MIN_Y || y >= MIN_Y + HEIGHT) return;
            blocks[index(x, y, z)] = block;
            if (block != null && y > heights[z * 16 + x]) heights[z * 16 + x] = y;
        }

        /** ChunkAccess.getHeight(WORLD_SURFACE_WG): top non-air block y. */
        public int height(int x, int z) {
            return heights[z * 16 + x];
        }
    }

    private static boolean isFluid(Block b) {
        return b == Block.WATER || b == Block.LAVA;
    }

    private static boolean isStoneLike(Block b) {
        return b != null && !isFluid(b);
    }

    // ================================================================== main pass

    public void buildSurface(ChunkData chunk, int chunkX, int chunkZ) {
        int minBlockX = chunkX << 4, minBlockZ = chunkZ << 4;
        Context context = new Context();
        SurfaceRule rule = ruleSource.apply(context);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int blockX = minBlockX + x;
                int blockZ = minBlockZ + z;
                int startingHeight = chunk.height(x, z) + 1;
                String surfaceBiome = biomeAt(blockX, startingHeight, blockZ);
                if (surfaceBiome.equals("minecraft:eroded_badlands")) {
                    erodedBadlandsExtension(chunk, x, z, blockX, blockZ, startingHeight);
                }

                int height = chunk.height(x, z) + 1;
                context.updateXZ(chunk, blockX, blockZ);
                int stoneAboveDepth = 0;
                int waterHeight = Integer.MIN_VALUE;
                int nextCeilingStoneY = Integer.MAX_VALUE;

                for (int y = height; y >= MIN_Y; y--) {
                    Block old = chunk.get(x, y, z);
                    if (old == null) {
                        stoneAboveDepth = 0;
                        waterHeight = Integer.MIN_VALUE;
                    } else if (isFluid(old)) {
                        if (waterHeight == Integer.MIN_VALUE) waterHeight = y + 1;
                    } else {
                        if (nextCeilingStoneY >= y) {
                            nextCeilingStoneY = -32512; // WAY_BELOW_MIN_Y
                            for (int lookaheadY = y - 1; lookaheadY >= MIN_Y - 1; lookaheadY--) {
                                if (!isStoneLike(chunk.get(x, lookaheadY, z))) {
                                    nextCeilingStoneY = lookaheadY + 1;
                                    break;
                                }
                            }
                        }
                        stoneAboveDepth++;
                        int stoneBelowDepth = y - nextCeilingStoneY + 1;
                        context.updateY(stoneAboveDepth, stoneBelowDepth, waterHeight, blockX, y, blockZ);
                        if (old == Block.STONE) {
                            Block state = rule.tryApply(blockX, y, blockZ);
                            if (state != null) chunk.set(x, y, z, state);
                        }
                    }
                }

                if (surfaceBiome.equals("minecraft:frozen_ocean") || surfaceBiome.equals("minecraft:deep_frozen_ocean")) {
                    frozenOceanExtension(context.getMinSurfaceLevel(), surfaceBiome, chunk, x, z, blockX, blockZ, startingHeight);
                }
            }
        }
    }

    /** SurfaceSystem.topMaterial: the surface block the rules would place at pos (carver grass fix). */
    Block topMaterial(ChunkData chunk, int blockX, int blockY, int blockZ, boolean underFluid) {
        Context context = new Context();
        SurfaceRule rule = ruleSource.apply(context);
        context.updateXZ(chunk, blockX, blockZ);
        context.updateY(1, 1, underFluid ? blockY + 1 : Integer.MIN_VALUE, blockX, blockY, blockZ);
        return rule.tryApply(blockX, blockY, blockZ);
    }

    int getSurfaceDepth(int blockX, int blockZ) {
        double noiseValue = surfaceNoise.getValue(blockX, 0.0, blockZ);
        return (int) (noiseValue * 2.75 + 3.0 + noiseRandom.at(blockX, 0, blockZ).nextDouble() * 0.25);
    }

    double getSurfaceSecondary(int blockX, int blockZ) {
        return surfaceSecondaryNoise.getValue(blockX, 0.0, blockZ);
    }

    // ================================================================== extensions

    private void erodedBadlandsExtension(ChunkData chunk, int x, int z, int blockX, int blockZ, int height) {
        double pillarBuffer = Math.min(
                Math.abs(badlandsSurfaceNoise.getValue(blockX, 0.0, blockZ) * 8.25),
                badlandsPillarNoise.getValue(blockX * 0.2, 0.0, blockZ * 0.2) * 15.0);
        if (pillarBuffer <= 0.0) return;
        double pillarFloor = Math.abs(badlandsPillarRoofNoise.getValue(blockX * 0.75, 0.0, blockZ * 0.75) * 1.5);
        double extensionTop = 64.0 + Math.min(pillarBuffer * pillarBuffer * 2.5, Math.ceil(pillarFloor * 50.0) + 24.0);
        int startY = VNoise.floor(extensionTop);
        if (height > startY) return;
        for (int y = startY; y >= MIN_Y; y--) {
            Block oldState = chunk.get(x, y, z);
            if (oldState == Block.STONE) break;
            if (oldState == Block.WATER) return;
        }
        for (int y = startY; y >= MIN_Y && chunk.get(x, y, z) == null; y--) {
            chunk.set(x, y, z, Block.STONE);
        }
    }

    private void frozenOceanExtension(int minSurfaceLevel, String surfaceBiome, ChunkData chunk,
                                      int x, int z, int blockX, int blockZ, int height) {
        double iceberg = Math.min(
                Math.abs(icebergSurfaceNoise.getValue(blockX, 0.0, blockZ) * 8.25),
                icebergPillarNoise.getValue(blockX * 1.28, 0.0, blockZ * 1.28) * 15.0);
        if (iceberg <= 1.8) return;
        double icebergRoof = Math.abs(icebergPillarRoofNoise.getValue(blockX * 1.17, 0.0, blockZ * 1.17) * 1.5);
        double top = Math.min(iceberg * iceberg * 1.2, Math.ceil(icebergRoof * 40.0) + 14.0);
        if (shouldMeltFrozenOceanIcebergSlightly(surfaceBiome, blockX, blockZ)) top -= 2.0;

        double extensionBottom;
        if (top > 2.0) {
            extensionBottom = seaLevel - top - 7.0;
            top += seaLevel;
        } else {
            top = 0.0;
            extensionBottom = 0.0;
        }
        double extensionTop = top;
        XRandom random = noiseRandom.at(blockX, 0, blockZ);
        int maxSnowDepth = 2 + random.nextInt(4);
        int minSnowHeight = seaLevel + 18 + random.nextInt(10);
        int snowDepth = 0;

        for (int y = Math.max(height, (int) top + 1); y >= minSurfaceLevel; y--) {
            Block block = chunk.get(x, y, z);
            if (block == null && y < (int) extensionTop && random.nextDouble() > 0.01
                    || block == Block.WATER && y > (int) extensionBottom && y < seaLevel && extensionBottom != 0.0
                       && random.nextDouble() > 0.15) {
                if (snowDepth <= maxSnowDepth && y > minSnowHeight) {
                    chunk.set(x, y, z, Block.SNOW_BLOCK);
                    snowDepth++;
                } else {
                    chunk.set(x, y, z, Block.PACKED_ICE);
                }
            }
        }
    }

    // ================================================================== clay bands

    private static Block[] generateBands(XRandom random) {
        Block[] clayBands = new Block[192];
        java.util.Arrays.fill(clayBands, Block.TERRACOTTA);
        for (int i = 0; i < clayBands.length; i++) {
            i += random.nextInt(5) + 1;
            if (i < clayBands.length) clayBands[i] = Block.ORANGE_TERRACOTTA;
        }
        makeBands(random, clayBands, 1, Block.YELLOW_TERRACOTTA);
        makeBands(random, clayBands, 2, Block.BROWN_TERRACOTTA);
        makeBands(random, clayBands, 1, Block.RED_TERRACOTTA);
        int whiteBandCount = random.nextIntBetweenInclusive(9, 15);
        int ix = 0;
        for (int start = 0; ix < whiteBandCount && start < clayBands.length; start += random.nextInt(16) + 4) {
            clayBands[start] = Block.WHITE_TERRACOTTA;
            if (start - 1 > 0 && random.nextBoolean()) clayBands[start - 1] = Block.LIGHT_GRAY_TERRACOTTA;
            if (start + 1 < clayBands.length && random.nextBoolean()) clayBands[start + 1] = Block.LIGHT_GRAY_TERRACOTTA;
            ix++;
        }
        return clayBands;
    }

    private static void makeBands(XRandom random, Block[] clayBands, int baseWidth, Block state) {
        int bandCount = random.nextIntBetweenInclusive(6, 15);
        for (int i = 0; i < bandCount; i++) {
            int width = baseWidth + random.nextInt(3);
            int start = random.nextInt(clayBands.length);
            for (int p = 0; start + p < clayBands.length && p < width; p++) {
                clayBands[start + p] = state;
            }
        }
    }

    Block getBand(int worldX, int y, int worldZ) {
        int offset = (int) Math.round(clayBandsOffsetNoise.getValue(worldX, 0.0, worldZ) * 4.0);
        return clayBands[(y + offset + clayBands.length) % clayBands.length];
    }

    // ================================================================== biome zoom (BiomeManager)

    static long obfuscateSeed(long seed) {
        try {
            byte[] le = new byte[8];
            for (int i = 0; i < 8; i++) le[i] = (byte) (seed >>> (i * 8));
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(le);
            long result = 0;
            for (int i = 7; i >= 0; i--) result = (result << 8) | (hash[i] & 0xFF);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long lcgNext(long seed, long salt) {
        seed *= seed * 6364136223846793005L + 1442695040888963407L;
        return seed + salt;
    }

    private static double getFiddle(long rval) {
        double uniform = Math.floorMod(rval >> 24, 1024) / 1024.0;
        return (uniform - 0.5) * 0.9;
    }

    private static double getFiddledDistance(long seed, int xRandom, int yRandom, int zRandom,
                                             double distanceX, double distanceY, double distanceZ) {
        long rval = lcgNext(seed, xRandom);
        rval = lcgNext(rval, yRandom);
        rval = lcgNext(rval, zRandom);
        rval = lcgNext(rval, xRandom);
        rval = lcgNext(rval, yRandom);
        rval = lcgNext(rval, zRandom);
        double fiddleX = getFiddle(rval);
        rval = lcgNext(rval, seed);
        double fiddleY = getFiddle(rval);
        rval = lcgNext(rval, seed);
        double fiddleZ = getFiddle(rval);
        double dz = distanceZ + fiddleZ, dy = distanceY + fiddleY, dx = distanceX + fiddleX;
        return dz * dz + dy * dy + dx * dx;
    }

    /** BiomeManager.getBiome: fuzzy-zoomed biome at block coords. */
    public String biomeAt(int x, int y, int z) {
        int absX = x - 2, absY = y - 2, absZ = z - 2;
        int parentX = absX >> 2, parentY = absY >> 2, parentZ = absZ >> 2;
        double fractX = (absX & 3) / 4.0, fractY = (absY & 3) / 4.0, fractZ = (absZ & 3) / 4.0;
        int minI = 0;
        double minFiddledDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            boolean xEven = (i & 4) == 0, yEven = (i & 2) == 0, zEven = (i & 1) == 0;
            int cornerX = xEven ? parentX : parentX + 1;
            int cornerY = yEven ? parentY : parentY + 1;
            int cornerZ = zEven ? parentZ : parentZ + 1;
            double dX = xEven ? fractX : fractX - 1.0;
            double dY = yEven ? fractY : fractY - 1.0;
            double dZ = zEven ? fractZ : fractZ - 1.0;
            double next = getFiddledDistance(biomeZoomSeed, cornerX, cornerY, cornerZ, dX, dY, dZ);
            if (minFiddledDistance > next) {
                minI = i;
                minFiddledDistance = next;
            }
        }
        int quartX = (minI & 4) == 0 ? parentX : parentX + 1;
        int quartY = (minI & 2) == 0 ? parentY : parentY + 1;
        int quartZ = (minI & 1) == 0 ? parentZ : parentZ + 1;
        // chunk biome storage clamps quart Y to the world range
        int clampedY = Math.max(MIN_Y >> 2, Math.min(((MIN_Y + HEIGHT) >> 2) - 1, quartY));
        return biomeSource.biomeAt(quartX, clampedY, quartZ);
    }

    // ================================================================== biome temperature (legacy noise)

    private static final PerlinSimplex TEMPERATURE_NOISE = new PerlinSimplex(new LegacyRandom(1234L), 1);
    private static final PerlinSimplex FROZEN_TEMPERATURE_NOISE = new PerlinSimplex(new LegacyRandom(3456L), 3);
    private static final PerlinSimplex BIOME_INFO_NOISE = new PerlinSimplex(new LegacyRandom(2345L), 1);

    float biomeTemperature(String biome, int x, int y, int z) {
        float[] c = climate.get(biome);
        float baseTemperature = c == null ? 0.5F : c[0];
        boolean frozen = c != null && c[1] == 1;
        float adjusted = baseTemperature;
        if (frozen) {
            double large = FROZEN_TEMPERATURE_NOISE.getValue(x * 0.05, z * 0.05) * 7.0;
            double edge = BIOME_INFO_NOISE.getValue(x * 0.2, z * 0.2);
            if (large + edge < 0.3) {
                double small = BIOME_INFO_NOISE.getValue(x * 0.09, z * 0.09);
                if (small < 0.8) adjusted = 0.2F;
            }
        }
        int snowLevel = seaLevel + 17;
        if (y > snowLevel) {
            float v = (float) (TEMPERATURE_NOISE.getValue(x / 8.0F, z / 8.0F) * 8.0);
            return adjusted - (v + y - snowLevel) * 0.05F / 40.0F;
        }
        return adjusted;
    }

    public boolean hasPrecipitation(String biome) {
        float[] c = climate.get(biome);
        return c != null && c.length > 2 && c[2] == 1;
    }

    public boolean coldEnoughToSnow(String biome, int x, int y, int z) {
        return biomeTemperature(biome, x, y, z) < 0.15F;
    }

    boolean shouldMeltFrozenOceanIcebergSlightly(String biome, int x, int z) {
        return biomeTemperature(biome, x, seaLevel, z) > 0.1F;
    }

    /** java.util.Random-compatible LCG (LegacyRandomSource / WorldgenRandom). */
    public static final class LegacyRandom implements VNoise.Rand {
        private long seed;

        public LegacyRandom(long seed) {
            setSeed(seed);
        }

        void setSeed(long seed) {
            this.seed = (seed ^ 25214903917L) & 281474976710655L;
        }

        int next(int bits) {
            seed = seed * 25214903917L + 11L & 281474976710655L;
            return (int) (seed >> 48 - bits);
        }

        @Override
        public int nextInt(int bound) {
            if ((bound & -bound) == bound) return (int) (bound * (long) next(31) >> 31);
            int bits, val;
            do {
                bits = next(31);
                val = bits % bound;
            } while (bits - val + (bound - 1) < 0);
            return val;
        }

        long nextLong() {
            return ((long) next(32) << 32) + next(32);
        }

        boolean nextBoolean() {
            return next(1) != 0;
        }

        public float nextFloat() {
            return next(24) * 5.9604645E-8F;
        }

        @Override
        public double nextDouble() {
            return (((long) next(26) << 27) + next(27)) * 0x1.0p-53;
        }

        /** WorldgenRandom.setLargeFeatureSeed (26.x: XOR combine, no |1). */
        public void setLargeFeatureSeed(long worldSeed, int chunkX, int chunkZ) {
            setSeed(worldSeed);
            long xScale = nextLong();
            long zScale = nextLong();
            setSeed(chunkX * xScale ^ chunkZ * zScale ^ worldSeed);
        }

        /** WorldgenRandom.setDecorationSeed (features): returns the decoration seed. */
        long setDecorationSeed(long worldSeed, int minBlockX, int minBlockZ) {
            setSeed(worldSeed);
            long xScale = nextLong() | 1L;
            long zScale = nextLong() | 1L;
            long result = minBlockX * xScale + minBlockZ * zScale ^ worldSeed;
            setSeed(result);
            return result;
        }

        /** WorldgenRandom.setFeatureSeed. */
        void setFeatureSeed(long decorationSeed, int index, int step) {
            setSeed(decorationSeed + index + 10000L * step);
        }

        /** WorldgenRandom.setLargeFeatureWithSalt (structure placement grid seeding). */
        void setLargeFeatureWithSalt(long worldSeed, int x, int z, int salt) {
            setSeed(x * 341873128712L + z * 132897987541L + worldSeed + salt);
        }

        /** RandomSource.fork() on a LegacyRandomSource: a fresh LCG seeded from one nextLong() draw. */
        LegacyRandom fork() {
            return new LegacyRandom(nextLong());
        }
    }

    /** SimplexNoise (2D sampling; constructor consumes randoms exactly like 3D vanilla). */
    static final class Simplex {
        private static final int[][] GRADIENT = VSurfaceGradients.GRADIENT;
        private static final double SQRT_3 = Math.sqrt(3.0);
        private static final double F2 = 0.5 * (SQRT_3 - 1.0);
        private static final double G2 = (3.0 - SQRT_3) / 6.0;
        private final int[] p = new int[512];
        final double xo, yo, zo;

        Simplex(LegacyRandom random) {
            this.xo = random.nextDouble() * 256.0;
            this.yo = random.nextDouble() * 256.0;
            this.zo = random.nextDouble() * 256.0;
            for (int i = 0; i < 256; i++) p[i] = i;
            for (int i = 0; i < 256; i++) {
                int offset = random.nextInt(256 - i);
                int tmp = p[i];
                p[i] = p[offset + i];
                p[offset + i] = tmp;
            }
        }

        private int p(int x) {
            return p[x & 0xFF];
        }

        private static double dot(int[] g, double x, double y) {
            return g[0] * x + g[1] * y;
        }

        private double corner(int index, double x, double y) {
            double t0 = 0.5 - x * x - y * y;
            if (t0 < 0.0) return 0.0;
            t0 *= t0;
            return t0 * t0 * dot(GRADIENT[index], x, y);
        }

        double getValue(double xin, double yin) {
            double s = (xin + yin) * F2;
            int i = VNoise.floor(xin + s);
            int j = VNoise.floor(yin + s);
            double t = (i + j) * G2;
            double x0 = xin - (i - t);
            double y0 = yin - (j - t);
            int i1, j1;
            if (x0 > y0) { i1 = 1; j1 = 0; } else { i1 = 0; j1 = 1; }
            double x1 = x0 - i1 + G2;
            double y1 = y0 - j1 + G2;
            double x2 = x0 - 1.0 + 2.0 * G2;
            double y2 = y0 - 1.0 + 2.0 * G2;
            int ii = i & 0xFF, jj = j & 0xFF;
            int gi0 = p(ii + p(jj)) % 12;
            int gi1 = p(ii + i1 + p(jj + j1)) % 12;
            int gi2 = p(ii + 1 + p(jj + 1)) % 12;
            return 70.0 * (corner(gi0, x0, y0) + corner(gi1, x1, y1) + corner(gi2, x2, y2));
        }
    }

    /** PerlinSimplexNoise for octave sets {0} and {-2,-1,0} (contiguous, ending at 0). */
    static final class PerlinSimplex {
        private final Simplex[] levels;
        private final double valueFactor;

        PerlinSimplex(LegacyRandom random, int octaves) {
            levels = new Simplex[octaves];
            for (int i = 0; i < octaves; i++) levels[i] = new Simplex(random);
            valueFactor = 1.0 / (Math.pow(2.0, octaves) - 1.0);
        }

        double getValue(double x, double y) {
            double value = 0.0;
            double factor = 1.0;
            double vf = valueFactor;
            for (Simplex level : levels) {
                value += level.getValue(x * factor, y * factor) * vf;
                factor /= 2.0;
                vf *= 2.0;
            }
            return value;
        }
    }

    // ================================================================== rule interpreter

    interface SurfaceRule {
        Block tryApply(int blockX, int blockY, int blockZ);
    }

    interface RuleSource {
        SurfaceRule apply(Context context);
    }

    interface Condition {
        boolean test();
    }

    interface ConditionSource {
        Condition apply(Context context);
    }

    /** Per-chunk mutable rule context (vanilla SurfaceRules.Context). */
    final class Context {
        ChunkData chunk;
        long lastUpdateXZ = -9223372036854775807L;
        long lastUpdateY = -9223372036854775807L;
        int blockX, blockZ, blockY;
        int surfaceDepth;
        long lastSurfaceDepth2Update = lastUpdateXZ - 1;
        double surfaceSecondary;
        long lastMinSurfaceLevelUpdate = lastUpdateXZ - 1;
        int minSurfaceLevel;
        long lastPreliminarySurfaceCellOrigin = Long.MAX_VALUE;
        final int[] preliminarySurfaceCache = new int[4];
        int waterHeight, stoneDepthBelow, stoneDepthAbove;
        String biome;
        int biomeX, biomeY, biomeZ;
        boolean biomeResolved;

        void updateXZ(ChunkData chunk, int blockX, int blockZ) {
            this.chunk = chunk;
            this.lastUpdateXZ++;
            this.lastUpdateY++;
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.surfaceDepth = getSurfaceDepth(blockX, blockZ);
        }

        void updateY(int stoneDepthAbove, int stoneDepthBelow, int waterHeight, int blockX, int blockY, int blockZ) {
            this.lastUpdateY++;
            this.blockY = blockY;
            this.waterHeight = waterHeight;
            this.stoneDepthBelow = stoneDepthBelow;
            this.stoneDepthAbove = stoneDepthAbove;
            this.biomeX = blockX;
            this.biomeY = blockY;
            this.biomeZ = blockZ;
            this.biomeResolved = false;
        }

        String biome() {
            if (!biomeResolved) {
                biome = biomeAt(biomeX, biomeY, biomeZ);
                biomeResolved = true;
            }
            return biome;
        }

        double surfaceSecondary() {
            if (lastSurfaceDepth2Update != lastUpdateXZ) {
                lastSurfaceDepth2Update = lastUpdateXZ;
                surfaceSecondary = getSurfaceSecondary(blockX, blockZ);
            }
            return surfaceSecondary;
        }

        int getMinSurfaceLevel() {
            if (lastMinSurfaceLevelUpdate != lastUpdateXZ) {
                lastMinSurfaceLevelUpdate = lastUpdateXZ;
                int cornerCellX = blockX >> 4;
                int cornerCellZ = blockZ >> 4;
                long origin = ((long) cornerCellX << 32) | (cornerCellZ & 0xFFFFFFFFL);
                if (lastPreliminarySurfaceCellOrigin != origin) {
                    lastPreliminarySurfaceCellOrigin = origin;
                    preliminarySurfaceCache[0] = genCtx.preliminarySurfaceLevel(cornerCellX << 4, cornerCellZ << 4);
                    preliminarySurfaceCache[1] = genCtx.preliminarySurfaceLevel((cornerCellX + 1) << 4, cornerCellZ << 4);
                    preliminarySurfaceCache[2] = genCtx.preliminarySurfaceLevel(cornerCellX << 4, (cornerCellZ + 1) << 4);
                    preliminarySurfaceCache[3] = genCtx.preliminarySurfaceLevel((cornerCellX + 1) << 4, (cornerCellZ + 1) << 4);
                }
                // Mth.lerp2(fx, fz, v00, v10, v01, v11) with float fractions
                double fx = (blockX & 15) / 16.0F;
                double fz = (blockZ & 15) / 16.0F;
                double v0 = preliminarySurfaceCache[0] + fx * (preliminarySurfaceCache[1] - preliminarySurfaceCache[0]);
                double v1 = preliminarySurfaceCache[2] + fx * (preliminarySurfaceCache[3] - preliminarySurfaceCache[2]);
                int level = VNoise.floor(v0 + fz * (v1 - v0));
                minSurfaceLevel = level + surfaceDepth - 8;
            }
            return minSurfaceLevel;
        }
    }

    /** Lazy condition memoized on an update counter. */
    private abstract static class LazyCondition implements Condition {
        private long lastUpdate;
        private boolean result;

        LazyCondition(long initial) {
            this.lastUpdate = initial - 1;
        }

        abstract long contextLastUpdate();

        abstract boolean compute();

        @Override
        public boolean test() {
            long now = contextLastUpdate();
            if (now != lastUpdate) {
                lastUpdate = now;
                result = compute();
            }
            return result;
        }
    }

    // ------------------------------------------------------------------ parsing

    private static String path(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private RuleSource parseRule(JsonElement json) {
        JsonObject o = json.getAsJsonObject();
        String type = path(o.get("type").getAsString());
        return switch (type) {
            case "sequence" -> {
                List<RuleSource> sources = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("sequence")) sources.add(parseRule(e));
                yield context -> {
                    if (sources.size() == 1) return sources.get(0).apply(context);
                    List<SurfaceRule> rules = new ArrayList<>(sources.size());
                    for (RuleSource s : sources) rules.add(s.apply(context));
                    return (x, y, z) -> {
                        for (SurfaceRule r : rules) {
                            Block state = r.tryApply(x, y, z);
                            if (state != null) return state;
                        }
                        return null;
                    };
                };
            }
            case "condition" -> {
                ConditionSource ifTrue = parseCondition(o.get("if_true"));
                RuleSource thenRun = parseRule(o.get("then_run"));
                yield context -> {
                    Condition condition = ifTrue.apply(context);
                    SurfaceRule followup = thenRun.apply(context);
                    return (x, y, z) -> condition.test() ? followup.tryApply(x, y, z) : null;
                };
            }
            case "block" -> {
                Block state = parseBlockState(o.getAsJsonObject("result_state"));
                yield context -> (x, y, z) -> state;
            }
            case "bandlands" -> context -> (x, y, z) -> getBand(x, y, z);
            default -> throw new IllegalStateException("Unknown surface rule " + type);
        };
    }

    static Block parseBlockState(JsonObject state) {
        Block block = Block.fromKey(state.get("Name").getAsString());
        if (block == null) throw new IllegalStateException("Unknown block " + state.get("Name"));
        if (state.has("Properties")) {
            Map<String, String> props = new HashMap<>();
            for (var e : state.getAsJsonObject("Properties").entrySet()) {
                props.put(e.getKey(), e.getValue().getAsString());
            }
            block = block.withProperties(props);
        }
        return block;
    }

    /** VerticalAnchor.resolveY for the overworld (-64..320). */
    private static int resolveAnchor(JsonObject anchor) {
        if (anchor.has("absolute")) return anchor.get("absolute").getAsInt();
        if (anchor.has("above_bottom")) return MIN_Y + anchor.get("above_bottom").getAsInt();
        if (anchor.has("below_top")) return MIN_Y + HEIGHT - 1 - anchor.get("below_top").getAsInt();
        throw new IllegalStateException("Unknown anchor " + anchor);
    }

    private ConditionSource parseCondition(JsonElement json) {
        JsonObject o = json.getAsJsonObject();
        String type = path(o.get("type").getAsString());
        return switch (type) {
            case "biome" -> {
                // 26.2 serializes single-biome conditions as a bare string, not a 1-element list
                Set<String> set = new HashSet<>();
                JsonElement biomeIs = o.get("biome_is");
                if (biomeIs.isJsonArray()) {
                    for (JsonElement e : biomeIs.getAsJsonArray()) set.add(e.getAsString());
                } else {
                    set.add(biomeIs.getAsString());
                }
                yield context -> new LazyCondition(context.lastUpdateY) {
                    long contextLastUpdate() { return context.lastUpdateY; }
                    boolean compute() { return set.contains(context.biome()); }
                };
            }
            case "noise_threshold" -> {
                VNoise.Normal noise = builder.noise(o.get("noise").getAsString());
                double min = o.get("min_threshold").getAsDouble();
                double max = o.get("max_threshold").getAsDouble();
                yield context -> new LazyCondition(context.lastUpdateXZ) {
                    long contextLastUpdate() { return context.lastUpdateXZ; }
                    boolean compute() {
                        double value = noise.getValue(context.blockX, 0.0, context.blockZ);
                        return value >= min && value <= max;
                    }
                };
            }
            case "vertical_gradient" -> {
                String randomName = o.get("random_name").getAsString();
                int trueAtAndBelow = resolveAnchor(o.getAsJsonObject("true_at_and_below"));
                int falseAtAndAbove = resolveAnchor(o.getAsJsonObject("false_at_and_above"));
                XRandom.Positional factory = namedRandoms.computeIfAbsent(
                        randomName.contains(":") ? randomName : "minecraft:" + randomName,
                        name -> noiseRandom.fromHashOf(name).forkPositional());
                yield context -> new LazyCondition(context.lastUpdateY) {
                    long contextLastUpdate() { return context.lastUpdateY; }
                    boolean compute() {
                        int blockY = context.blockY;
                        if (blockY <= trueAtAndBelow) return true;
                        if (blockY >= falseAtAndAbove) return false;
                        double probability = map(blockY, trueAtAndBelow, falseAtAndAbove, 1.0, 0.0);
                        return factory.at(context.blockX, blockY, context.blockZ).nextFloat() < probability;
                    }
                };
            }
            case "y_above" -> {
                int anchor = resolveAnchor(o.getAsJsonObject("anchor"));
                int multiplier = o.get("surface_depth_multiplier").getAsInt();
                boolean addStoneDepth = o.get("add_stone_depth").getAsBoolean();
                yield context -> new LazyCondition(context.lastUpdateY) {
                    long contextLastUpdate() { return context.lastUpdateY; }
                    boolean compute() {
                        return context.blockY + (addStoneDepth ? context.stoneDepthAbove : 0)
                                >= anchor + context.surfaceDepth * multiplier;
                    }
                };
            }
            case "water" -> {
                int offset = o.get("offset").getAsInt();
                int multiplier = o.get("surface_depth_multiplier").getAsInt();
                boolean addStoneDepth = o.get("add_stone_depth").getAsBoolean();
                yield context -> new LazyCondition(context.lastUpdateY) {
                    long contextLastUpdate() { return context.lastUpdateY; }
                    boolean compute() {
                        return context.waterHeight == Integer.MIN_VALUE
                                || context.blockY + (addStoneDepth ? context.stoneDepthAbove : 0)
                                   >= context.waterHeight + offset + context.surfaceDepth * multiplier;
                    }
                };
            }
            case "temperature" -> context -> new LazyCondition(context.lastUpdateY) {
                long contextLastUpdate() { return context.lastUpdateY; }
                boolean compute() {
                    return coldEnoughToSnow(context.biome(), context.biomeX, context.biomeY, context.biomeZ);
                }
            };
            case "steep" -> context -> new LazyCondition(context.lastUpdateXZ) {
                long contextLastUpdate() { return context.lastUpdateXZ; }
                boolean compute() {
                    int cx = context.blockX & 15;
                    int cz = context.blockZ & 15;
                    int zNorth = Math.max(cz - 1, 0);
                    int zSouth = Math.min(cz + 1, 15);
                    int heightNorth = context.chunk.height(cx, zNorth);
                    int heightSouth = context.chunk.height(cx, zSouth);
                    if (heightSouth >= heightNorth + 4) return true;
                    int xWest = Math.max(cx - 1, 0);
                    int xEast = Math.min(cx + 1, 15);
                    int heightWest = context.chunk.height(xWest, cz);
                    int heightEast = context.chunk.height(xEast, cz);
                    return heightWest >= heightEast + 4;
                }
            };
            case "not" -> {
                ConditionSource target = parseCondition(o.get("invert"));
                yield context -> {
                    Condition inner = target.apply(context);
                    return () -> !inner.test();
                };
            }
            case "hole" -> context -> new LazyCondition(context.lastUpdateXZ) {
                long contextLastUpdate() { return context.lastUpdateXZ; }
                boolean compute() { return context.surfaceDepth <= 0; }
            };
            case "above_preliminary_surface" -> context -> () -> context.blockY >= context.getMinSurfaceLevel();
            case "stone_depth" -> {
                int offset = o.get("offset").getAsInt();
                boolean addSurfaceDepth = o.get("add_surface_depth").getAsBoolean();
                int secondaryDepthRange = o.get("secondary_depth_range").getAsInt();
                boolean ceiling = "ceiling".equals(o.get("surface_type").getAsString());
                yield context -> new LazyCondition(context.lastUpdateY) {
                    long contextLastUpdate() { return context.lastUpdateY; }
                    boolean compute() {
                        int stoneDepth = ceiling ? context.stoneDepthBelow : context.stoneDepthAbove;
                        int surfaceDepth = addSurfaceDepth ? context.surfaceDepth : 0;
                        int secondary = secondaryDepthRange == 0 ? 0
                                : (int) map(context.surfaceSecondary(), -1.0, 1.0, 0.0, secondaryDepthRange);
                        return stoneDepth <= 1 + offset + surfaceDepth + secondary;
                    }
                };
            }
            default -> throw new IllegalStateException("Unknown surface condition " + type);
        };
    }

    private static double map(double value, double fromMin, double fromMax, double toMin, double toMax) {
        return VNoise.lerp((value - fromMin) / (fromMax - fromMin), toMin, toMax);
    }
}

/** The 16-entry simplex gradient table (shared with VNoise but int-typed 3D). */
final class VSurfaceGradients {
    static final int[][] GRADIENT = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
            {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
    };

    private VSurfaceGradients() {}
}
