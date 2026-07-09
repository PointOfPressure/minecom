package dev.pointofpressure.minecom.worldgen.vanilla;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanilla NoiseBasedAquifer reimplemented for Minecom: underground water/lava
 * pockets with barrier stone between different fluid levels. One instance per
 * chunk (grid caches are chunk-local, like vanilla's per-NoiseChunk aquifer).
 *
 * Substance codes: STONE (barrier / solid), AIR, WATER, LAVA. All internal
 * density evaluations that vanilla routes through SinglePointContext bypass
 * cell mode, matching vanilla's cache-skipping semantics.
 */
public final class VAquifer {
    public static final int STONE = 0, AIR = 1, WATER = 2, LAVA = 3;

    private static final int WAY_BELOW_MIN_Y = -32512; // DimensionType.MIN_Y (-2032) * 16

    /** Shared per-seed state: router functions + positional random + surface cache. */
    public static final class Context {
        final VDensity.DF barrierNoise;
        final VDensity.DF fluidLevelFloodednessNoise;
        final VDensity.DF fluidLevelSpreadNoise;
        final VDensity.DF lavaNoise;
        final VDensity.DF erosion;
        final VDensity.DF depth;
        final VDensity.DF preliminarySurface;
        final XRandom.Positional aquiferRandom;
        final int seaLevel;
        private final Map<Long, Integer> surfaceCache = new ConcurrentHashMap<>();

        public Context(VDensity.Builder builder,
                       com.google.gson.JsonObject noiseRouter, int seaLevel) {
            this.barrierNoise = builder.build(noiseRouter.get("barrier"));
            this.fluidLevelFloodednessNoise = builder.build(noiseRouter.get("fluid_level_floodedness"));
            this.fluidLevelSpreadNoise = builder.build(noiseRouter.get("fluid_level_spread"));
            this.lavaNoise = builder.build(noiseRouter.get("lava"));
            this.erosion = builder.build(noiseRouter.get("erosion"));
            this.depth = builder.build(noiseRouter.get("depth"));
            this.preliminarySurface = builder.build(noiseRouter.get("preliminary_surface_level"));
            this.aquiferRandom = builder.seedRandom().fromHashOf("minecraft:aquifer").forkPositional();
            this.seaLevel = seaLevel;
        }

        /** Vanilla NoiseChunk.preliminarySurfaceLevel: quart-quantized, cached, cell-mode-free. */
        int preliminarySurfaceLevel(int x, int z) {
            int quartX = (x >> 2) << 2;
            int quartZ = (z >> 2) << 2;
            long key = ((long) quartX << 32) | (quartZ & 0xFFFFFFFFL);
            Integer cached = surfaceCache.get(key);
            if (cached != null) return cached;
            boolean was = VDensity.cellMode();
            VDensity.cellModeRaw(false);
            try {
                int level = VNoise.floor(preliminarySurface.compute(quartX, 0, quartZ));
                surfaceCache.put(key, level);
                return level;
            } finally {
                VDensity.cellModeRaw(was);
            }
        }

        /** NoiseBasedChunkGenerator.createFluidPicker: lava below -54, else sea fluid. */
        FluidStatus globalFluid(int x, int y, int z) {
            return y < Math.min(-54, seaLevel)
                    ? new FluidStatus(-54, LAVA)
                    : new FluidStatus(seaLevel, WATER);
        }
    }

    record FluidStatus(int fluidLevel, int fluidType) {
        int at(int blockY) {
            return blockY < fluidLevel ? fluidType : AIR;
        }
    }

    private static final int[][] SURFACE_SAMPLING_OFFSETS_IN_CHUNKS = {
            {0, 0}, {-2, -1}, {-1, -1}, {0, -1}, {1, -1}, {-3, 0}, {-2, 0}, {-1, 0}, {1, 0},
            {-2, 1}, {-1, 1}, {0, 1}, {1, 1}
    };

    private final Context ctx;
    private final FluidStatus[] aquiferCache;
    private final long[] aquiferLocationCache;
    private final int skipSamplingAboveY;
    private final int minGridX, minGridY, minGridZ;
    private final int gridSizeX, gridSizeZ;

    public VAquifer(Context ctx, int chunkX, int chunkZ, int minBlockY, int yBlockSize) {
        this.ctx = ctx;
        int minBlockX = chunkX << 4, minBlockZ = chunkZ << 4;
        int maxBlockX = minBlockX + 15, maxBlockZ = minBlockZ + 15;
        this.minGridX = gridX(minBlockX - 5);
        int maxGridX = gridX(maxBlockX - 5) + 1;
        this.gridSizeX = maxGridX - minGridX + 1;
        this.minGridY = gridY(minBlockY + 1) - 1;
        int maxGridY = gridY(minBlockY + yBlockSize + 1) + 1;
        int gridSizeY = maxGridY - minGridY + 1;
        this.minGridZ = gridZ(minBlockZ - 5);
        int maxGridZ = gridZ(maxBlockZ - 5) + 1;
        this.gridSizeZ = maxGridZ - minGridZ + 1;
        int totalGridSize = gridSizeX * gridSizeY * gridSizeZ;
        this.aquiferCache = new FluidStatus[totalGridSize];
        this.aquiferLocationCache = new long[totalGridSize];
        Arrays.fill(aquiferLocationCache, Long.MAX_VALUE);
        int maxAdjustedSurfaceLevel = adjustSurfaceLevel(maxPreliminarySurfaceLevel(
                fromGridX(minGridX, 0), fromGridZ(minGridZ, 0), fromGridX(maxGridX, 9), fromGridZ(maxGridZ, 9)));
        int skipSamplingAboveGridY = gridY(maxAdjustedSurfaceLevel + 12) + 1;
        this.skipSamplingAboveY = fromGridY(skipSamplingAboveGridY, 11) - 1;
    }

    /** Vanilla NoiseChunk.maxPreliminarySurfaceLevel: max over 4-block sample grid. */
    private int maxPreliminarySurfaceLevel(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        int maxY = Integer.MIN_VALUE;
        for (int blockZ = minBlockZ; blockZ <= maxBlockZ; blockZ += 4) {
            for (int blockX = minBlockX; blockX <= maxBlockX; blockX += 4) {
                maxY = Math.max(maxY, ctx.preliminarySurfaceLevel(blockX, blockZ));
            }
        }
        return maxY;
    }

    private int getIndex(int gridX, int gridY, int gridZ) {
        int x = gridX - minGridX;
        int y = gridY - minGridY;
        int z = gridZ - minGridZ;
        return (y * gridSizeZ + z) * gridSizeX + x;
    }

    /** STONE if solid (density or barrier pressure), else AIR/WATER/LAVA. */
    public int computeSubstance(int posX, int posY, int posZ, double density) {
        if (density > 0.0) return STONE;
        FluidStatus globalFluid = ctx.globalFluid(posX, posY, posZ);
        if (posY > skipSamplingAboveY) return globalFluid.at(posY);
        if (globalFluid.at(posY) == LAVA) return LAVA;

        int xAnchor = gridX(posX - 5);
        int yAnchor = gridY(posY + 1);
        int zAnchor = gridZ(posZ - 5);
        int distanceSqr1 = Integer.MAX_VALUE, distanceSqr2 = Integer.MAX_VALUE, distanceSqr3 = Integer.MAX_VALUE;
        int closestIndex1 = 0, closestIndex2 = 0, closestIndex3 = 0;

        for (int x1 = 0; x1 <= 1; x1++) {
            for (int y1 = -1; y1 <= 1; y1++) {
                for (int z1 = 0; z1 <= 1; z1++) {
                    int spacedGridX = xAnchor + x1;
                    int spacedGridY = yAnchor + y1;
                    int spacedGridZ = zAnchor + z1;
                    int index = getIndex(spacedGridX, spacedGridY, spacedGridZ);
                    long existing = aquiferLocationCache[index];
                    long location;
                    if (existing != Long.MAX_VALUE) {
                        location = existing;
                    } else {
                        XRandom random = ctx.aquiferRandom.at(spacedGridX, spacedGridY, spacedGridZ);
                        location = blockPosAsLong(
                                fromGridX(spacedGridX, random.nextInt(10)),
                                fromGridY(spacedGridY, random.nextInt(9)),
                                fromGridZ(spacedGridZ, random.nextInt(10)));
                        aquiferLocationCache[index] = location;
                    }
                    int dx = blockPosX(location) - posX;
                    int dy = blockPosY(location) - posY;
                    int dz = blockPosZ(location) - posZ;
                    int newDistance = dx * dx + dy * dy + dz * dz;
                    if (distanceSqr1 >= newDistance) {
                        closestIndex3 = closestIndex2;
                        closestIndex2 = closestIndex1;
                        closestIndex1 = index;
                        distanceSqr3 = distanceSqr2;
                        distanceSqr2 = distanceSqr1;
                        distanceSqr1 = newDistance;
                    } else if (distanceSqr2 >= newDistance) {
                        closestIndex3 = closestIndex2;
                        closestIndex2 = index;
                        distanceSqr3 = distanceSqr2;
                        distanceSqr2 = newDistance;
                    } else if (distanceSqr3 >= newDistance) {
                        closestIndex3 = index;
                        distanceSqr3 = newDistance;
                    }
                }
            }
        }

        FluidStatus closestStatus1 = getAquiferStatus(closestIndex1);
        double similarity12 = similarity(distanceSqr1, distanceSqr2);
        int fluidState = closestStatus1.at(posY);
        if (similarity12 <= 0.0) return fluidState;
        if (fluidState == WATER && ctx.globalFluid(posX, posY - 1, posZ).at(posY - 1) == LAVA) return fluidState;

        double[] barrierNoiseValue = {Double.NaN};
        FluidStatus closestStatus2 = getAquiferStatus(closestIndex2);
        double barrier12 = similarity12 * calculatePressure(posX, posY, posZ, barrierNoiseValue, closestStatus1, closestStatus2);
        if (density + barrier12 > 0.0) return STONE;

        FluidStatus closestStatus3 = getAquiferStatus(closestIndex3);
        double similarity13 = similarity(distanceSqr1, distanceSqr3);
        if (similarity13 > 0.0) {
            double barrier13 = similarity12 * similarity13 * calculatePressure(posX, posY, posZ, barrierNoiseValue, closestStatus1, closestStatus3);
            if (density + barrier13 > 0.0) return STONE;
        }
        double similarity23 = similarity(distanceSqr2, distanceSqr3);
        if (similarity23 > 0.0) {
            double barrier23 = similarity12 * similarity23 * calculatePressure(posX, posY, posZ, barrierNoiseValue, closestStatus2, closestStatus3);
            if (density + barrier23 > 0.0) return STONE;
        }
        return fluidState;
    }

    private static double similarity(int distanceSqr1, int distanceSqr2) {
        return 1.0 - (distanceSqr2 - distanceSqr1) / 25.0;
    }

    private double calculatePressure(int posX, int posY, int posZ, double[] barrierNoiseValue,
                                     FluidStatus statusClosest1, FluidStatus statusClosest2) {
        int type1 = statusClosest1.at(posY);
        int type2 = statusClosest2.at(posY);
        if ((type1 == LAVA && type2 == WATER) || (type1 == WATER && type2 == LAVA)) return 2.0;

        int fluidYDiff = Math.abs(statusClosest1.fluidLevel - statusClosest2.fluidLevel);
        if (fluidYDiff == 0) return 0.0;
        double averageFluidY = 0.5 * (statusClosest1.fluidLevel + statusClosest2.fluidLevel);
        double howFarAboveAverageFluidPoint = posY + 0.5 - averageFluidY;
        double baseValue = fluidYDiff / 2.0;
        double distanceFromBarrierEdgeTowardsMiddle = baseValue - Math.abs(howFarAboveAverageFluidPoint);
        double gradient;
        if (howFarAboveAverageFluidPoint > 0.0) {
            double centerPoint = 0.0 + distanceFromBarrierEdgeTowardsMiddle;
            gradient = centerPoint > 0.0 ? centerPoint / 1.5 : centerPoint / 2.5;
        } else {
            double centerPoint = 3.0 + distanceFromBarrierEdgeTowardsMiddle;
            gradient = centerPoint > 0.0 ? centerPoint / 3.0 : centerPoint / 10.0;
        }
        double noiseValue;
        if (!(gradient < -2.0) && !(gradient > 2.0)) {
            double current = barrierNoiseValue[0];
            if (Double.isNaN(current)) {
                double barrier = ctx.barrierNoise.compute(posX, posY, posZ);
                barrierNoiseValue[0] = barrier;
                noiseValue = barrier;
            } else {
                noiseValue = current;
            }
        } else {
            noiseValue = 0.0;
        }
        return 2.0 * (noiseValue + gradient);
    }

    private static int gridX(int blockCoord) { return blockCoord >> 4; }
    private static int fromGridX(int gridCoord, int blockOffset) { return (gridCoord << 4) + blockOffset; }
    private static int gridY(int blockCoord) { return Math.floorDiv(blockCoord, 12); }
    private static int fromGridY(int gridCoord, int blockOffset) { return gridCoord * 12 + blockOffset; }
    private static int gridZ(int blockCoord) { return blockCoord >> 4; }
    private static int fromGridZ(int gridCoord, int blockOffset) { return (gridCoord << 4) + blockOffset; }

    private FluidStatus getAquiferStatus(int index) {
        FluidStatus old = aquiferCache[index];
        if (old != null) return old;
        long location = aquiferLocationCache[index];
        FluidStatus status = computeFluid(blockPosX(location), blockPosY(location), blockPosZ(location));
        aquiferCache[index] = status;
        return status;
    }

    private FluidStatus computeFluid(int x, int y, int z) {
        FluidStatus globalFluid = ctx.globalFluid(x, y, z);
        int lowestPreliminarySurface = Integer.MAX_VALUE;
        int topOfAquiferCell = y + 12;
        int bottomOfAquiferCell = y - 12;
        boolean surfaceAtCenterIsUnderGlobalFluidLevel = false;

        for (int[] offset : SURFACE_SAMPLING_OFFSETS_IN_CHUNKS) {
            int sampleX = x + (offset[0] << 4);
            int sampleZ = z + (offset[1] << 4);
            int preliminarySurfaceLevel = ctx.preliminarySurfaceLevel(sampleX, sampleZ);
            int adjustedSurfaceLevel = adjustSurfaceLevel(preliminarySurfaceLevel);
            boolean start = offset[0] == 0 && offset[1] == 0;
            if (start && bottomOfAquiferCell > adjustedSurfaceLevel) return globalFluid;
            boolean topPokesAboveSurface = topOfAquiferCell > adjustedSurfaceLevel;
            if (topPokesAboveSurface || start) {
                FluidStatus globalAtSurface = ctx.globalFluid(sampleX, adjustedSurfaceLevel, sampleZ);
                if (globalAtSurface.at(adjustedSurfaceLevel) != AIR) {
                    if (start) surfaceAtCenterIsUnderGlobalFluidLevel = true;
                    if (topPokesAboveSurface) return globalAtSurface;
                }
            }
            lowestPreliminarySurface = Math.min(lowestPreliminarySurface, preliminarySurfaceLevel);
        }

        int fluidSurfaceLevel = computeSurfaceLevel(x, y, z, globalFluid, lowestPreliminarySurface, surfaceAtCenterIsUnderGlobalFluidLevel);
        return new FluidStatus(fluidSurfaceLevel, computeFluidType(x, y, z, globalFluid, fluidSurfaceLevel));
    }

    private static int adjustSurfaceLevel(int preliminarySurfaceLevel) {
        return preliminarySurfaceLevel + 8;
    }

    private int computeSurfaceLevel(int x, int y, int z, FluidStatus globalFluid,
                                    int lowestPreliminarySurface, boolean surfaceAtCenterIsUnderGlobalFluidLevel) {
        boolean was = VDensity.cellMode();
        VDensity.cellModeRaw(false);
        try {
            double partiallyFloodedness;
            double fullyFloodedness;
            if (isDeepDarkRegion(x, y, z)) {
                partiallyFloodedness = -1.0;
                fullyFloodedness = -1.0;
            } else {
                int distanceBelowSurface = lowestPreliminarySurface + 8 - y;
                double floodednessFactor = surfaceAtCenterIsUnderGlobalFluidLevel
                        ? clampedMap(distanceBelowSurface, 0.0, 64.0, 1.0, 0.0) : 0.0;
                double floodednessNoiseValue = clamp(ctx.fluidLevelFloodednessNoise.compute(x, y, z), -1.0, 1.0);
                double fullyFloodedThreshold = map(floodednessFactor, 1.0, 0.0, -0.3, 0.8);
                double partiallyFloodedThreshold = map(floodednessFactor, 1.0, 0.0, -0.8, 0.4);
                partiallyFloodedness = floodednessNoiseValue - partiallyFloodedThreshold;
                fullyFloodedness = floodednessNoiseValue - fullyFloodedThreshold;
            }
            if (fullyFloodedness > 0.0) return globalFluid.fluidLevel;
            if (partiallyFloodedness > 0.0) return computeRandomizedFluidSurfaceLevel(x, y, z, lowestPreliminarySurface);
            return WAY_BELOW_MIN_Y;
        } finally {
            VDensity.cellModeRaw(was);
        }
    }

    /** OverworldBiomeBuilder.isDeepDarkRegion (float thresholds widened like vanilla). */
    private boolean isDeepDarkRegion(int x, int y, int z) {
        return ctx.erosion.compute(x, y, z) < -0.225F && ctx.depth.compute(x, y, z) > 0.9F;
    }

    private int computeRandomizedFluidSurfaceLevel(int x, int y, int z, int lowestPreliminarySurface) {
        int fluidLevelCellX = Math.floorDiv(x, 16);
        int fluidLevelCellY = Math.floorDiv(y, 40);
        int fluidLevelCellZ = Math.floorDiv(z, 16);
        int fluidCellMiddleY = fluidLevelCellY * 40 + 20;
        double fluidLevelSpread = ctx.fluidLevelSpreadNoise.compute(fluidLevelCellX, fluidLevelCellY, fluidLevelCellZ) * 10.0;
        int fluidLevelSpreadQuantized = VNoise.floor(fluidLevelSpread / 3.0) * 3;
        int targetFluidSurfaceLevel = fluidCellMiddleY + fluidLevelSpreadQuantized;
        return Math.min(lowestPreliminarySurface, targetFluidSurfaceLevel);
    }

    private int computeFluidType(int x, int y, int z, FluidStatus globalFluid, int fluidSurfaceLevel) {
        int fluidType = globalFluid.fluidType;
        if (fluidSurfaceLevel <= -10 && fluidSurfaceLevel != WAY_BELOW_MIN_Y && globalFluid.fluidType != LAVA) {
            int fluidTypeCellX = Math.floorDiv(x, 64);
            int fluidTypeCellY = Math.floorDiv(y, 40);
            int fluidTypeCellZ = Math.floorDiv(z, 64);
            double lavaNoiseValue = ctx.lavaNoise.compute(fluidTypeCellX, fluidTypeCellY, fluidTypeCellZ);
            if (Math.abs(lavaNoiseValue) > 0.3) fluidType = LAVA;
        }
        return fluidType;
    }

    // BlockPos.asLong packing: X 26 bits << 38, Z 26 bits << 12, Y 12 bits low
    private static long blockPosAsLong(int x, int y, int z) {
        long l = 0L;
        l |= (x & 0x3FFFFFFL) << 38;
        l |= (y & 0xFFFL);
        l |= (z & 0x3FFFFFFL) << 12;
        return l;
    }

    private static int blockPosX(long packed) { return (int) (packed >> 38); }
    private static int blockPosY(long packed) { return (int) (packed << 52 >> 52); }
    private static int blockPosZ(long packed) { return (int) (packed << 26 >> 38); }

    // Mth ports
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    private static double inverseLerp(double value, double from, double to) { return (value - from) / (to - from); }

    private static double map(double value, double fromMin, double fromMax, double toMin, double toMax) {
        return VNoise.lerp(inverseLerp(value, fromMin, fromMax), toMin, toMax);
    }

    private static double clampedMap(double value, double fromMin, double fromMax, double toMin, double toMax) {
        return clampedLerp(inverseLerp(value, fromMin, fromMax), toMin, toMax);
    }

    private static double clampedLerp(double delta, double start, double end) {
        if (delta < 0.0) return start;
        return delta > 1.0 ? end : VNoise.lerp(delta, start, end);
    }
}
