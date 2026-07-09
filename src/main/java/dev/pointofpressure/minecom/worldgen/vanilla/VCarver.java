package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.instance.block.Block;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Vanilla cave + canyon carvers: per-source-chunk WorldgenRandom large-feature
 * seeding over a 17x17 neighborhood, ellipsoid tunnels/rooms/canyons carved into
 * the target chunk through the aquifer (air/water/lava/barrier), with the
 * grass-fix topMaterial repair. Bit-exact ports of CaveWorldCarver and
 * CanyonWorldCarver, including Mth's 65536-entry float sin table.
 */
public final class VCarver {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    private final VanillaGen gen;
    private final VSurface surface;
    private final long seed;
    private final CaveConfig cave;
    private final CaveConfig caveExtra;
    private final CanyonConfig canyon;
    private final Set<String> replaceable;
    private final Map<String, String[]> biomeCarvers = new java.util.HashMap<>();

    public VCarver(VanillaGen gen, VSurface surface, long seed) {
        this.gen = gen;
        this.surface = surface;
        this.seed = seed;
        Gson gson = new Gson();
        JsonObject carvers;
        JsonObject biomes;
        JsonObject tags;
        try (var a = VCarver.class.getResourceAsStream("/vanilla/carvers.json");
             var b = VCarver.class.getResourceAsStream("/vanilla/biome_carvers.json");
             var c = VCarver.class.getResourceAsStream("/vanilla/tags_block.json")) {
            carvers = gson.fromJson(new InputStreamReader(a, StandardCharsets.UTF_8), JsonObject.class);
            biomes = gson.fromJson(new InputStreamReader(b, StandardCharsets.UTF_8), JsonObject.class);
            tags = gson.fromJson(new InputStreamReader(c, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new IllegalStateException("Missing carver bundles", e);
        }
        this.cave = new CaveConfig(carvers.getAsJsonObject("minecraft:cave").getAsJsonObject("config"));
        this.caveExtra = new CaveConfig(carvers.getAsJsonObject("minecraft:cave_extra_underground").getAsJsonObject("config"));
        this.canyon = new CanyonConfig(carvers.getAsJsonObject("minecraft:canyon").getAsJsonObject("config"));
        this.replaceable = resolveTag(tags, "minecraft:overworld_carver_replaceables");
        for (var e : biomes.entrySet()) {
            JsonArray list = e.getValue().getAsJsonArray();
            String[] ids = new String[list.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = list.get(i).getAsString();
            biomeCarvers.put(e.getKey(), ids);
        }
    }

    private static Set<String> resolveTag(JsonObject tags, String tag) {
        Set<String> out = new HashSet<>();
        String key = tag.substring(tag.indexOf(':') + 1);
        JsonObject entry = tags.has(key) ? tags.getAsJsonObject(key) : tags.getAsJsonObject(tag);
        JsonArray values = entry.getAsJsonArray("values");
        for (JsonElement e : values) {
            String v = e.getAsString();
            if (v.startsWith("#")) out.addAll(resolveTag(tags, v.substring(1)));
            else out.add(v);
        }
        return out;
    }

    // ================================================================== driver

    /** NoiseBasedChunkGenerator.applyCarvers for one target chunk. */
    public void carve(VSurface.ChunkData chunk, int chunkX, int chunkZ, VAquifer aquifer) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0);
        BitSet mask = new BitSet(16 * 16 * HEIGHT);

        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                int sourceX = chunkX + dx, sourceZ = chunkZ + dz;
                String biome = gen.biomes().biomeAt((sourceX << 4) >> 2, 0, (sourceZ << 4) >> 2);
                String[] carvers = biomeCarvers.getOrDefault(biome,
                        new String[]{"minecraft:cave", "minecraft:cave_extra_underground", "minecraft:canyon"});
                for (int index = 0; index < carvers.length; index++) {
                    random.setLargeFeatureSeed(seed + index, sourceX, sourceZ);
                    switch (carvers[index]) {
                        case "minecraft:cave" -> {
                            if (random.nextFloat() <= cave.probability)
                                carveCave(cave, chunk, chunkX, chunkZ, random, aquifer, sourceX, sourceZ, mask);
                        }
                        case "minecraft:cave_extra_underground" -> {
                            if (random.nextFloat() <= caveExtra.probability)
                                carveCave(caveExtra, chunk, chunkX, chunkZ, random, aquifer, sourceX, sourceZ, mask);
                        }
                        case "minecraft:canyon" -> {
                            if (random.nextFloat() <= canyon.probability)
                                carveCanyon(canyon, chunk, chunkX, chunkZ, random, aquifer, sourceX, sourceZ, mask);
                        }
                        default -> { }
                    }
                }
            }
        }
    }

    // ================================================================== configs

    /** Float/height providers parsed from the carver config JSON. */
    private interface FloatProvider {
        float sample(VSurface.LegacyRandom random);
    }

    private static FloatProvider parseFloat(JsonElement json) {
        if (json.isJsonPrimitive()) {
            float v = json.getAsFloat();
            return r -> v;
        }
        JsonObject o = json.getAsJsonObject();
        String type = o.get("type").getAsString();
        if (type.endsWith("uniform")) {
            float min = o.get("min_inclusive").getAsFloat();
            float max = o.get("max_exclusive").getAsFloat();
            return r -> r.nextFloat() * (max - min) + min;
        }
        if (type.endsWith("trapezoid")) {
            float min = o.get("min").getAsFloat();
            float max = o.get("max").getAsFloat();
            float plateau = o.get("plateau").getAsFloat();
            return r -> {
                float range = max - min;
                float u = (range - plateau) / 2.0F;
                float v = range - u;
                return min + r.nextFloat() * v + r.nextFloat() * u;
            };
        }
        throw new IllegalStateException("Unknown float provider " + type);
    }

    private static int resolveAnchor(JsonObject anchor) {
        if (anchor.has("absolute")) return anchor.get("absolute").getAsInt();
        if (anchor.has("above_bottom")) return MIN_Y + anchor.get("above_bottom").getAsInt();
        if (anchor.has("below_top")) return MIN_Y + HEIGHT - 1 - anchor.get("below_top").getAsInt();
        throw new IllegalStateException("Unknown anchor " + anchor);
    }

    /** UniformHeight provider. */
    private record HeightProvider(int min, int max) {
        static HeightProvider parse(JsonObject o) {
            return new HeightProvider(resolveAnchor(o.getAsJsonObject("min_inclusive")),
                    resolveAnchor(o.getAsJsonObject("max_inclusive")));
        }

        int sample(VSurface.LegacyRandom random) {
            return min > max ? min : random.nextInt(max - min + 1) + min;
        }
    }

    private static final class CaveConfig {
        final float probability;
        final HeightProvider y;
        final FloatProvider yScale;
        final int lavaLevel;
        final FloatProvider horizontalRadiusMultiplier;
        final FloatProvider verticalRadiusMultiplier;
        final FloatProvider floorLevel;

        CaveConfig(JsonObject c) {
            probability = c.get("probability").getAsFloat();
            y = HeightProvider.parse(c.getAsJsonObject("y"));
            yScale = parseFloat(c.get("yScale"));
            lavaLevel = resolveAnchor(c.getAsJsonObject("lava_level"));
            horizontalRadiusMultiplier = parseFloat(c.get("horizontal_radius_multiplier"));
            verticalRadiusMultiplier = parseFloat(c.get("vertical_radius_multiplier"));
            floorLevel = parseFloat(c.get("floor_level"));
        }
    }

    private static final class CanyonConfig {
        final float probability;
        final HeightProvider y;
        final FloatProvider yScale;
        final int lavaLevel;
        final FloatProvider verticalRotation;
        final FloatProvider distanceFactor;
        final FloatProvider thickness;
        final int widthSmoothness;
        final FloatProvider horizontalRadiusFactor;
        final float verticalRadiusDefaultFactor;
        final float verticalRadiusCenterFactor;

        CanyonConfig(JsonObject c) {
            probability = c.get("probability").getAsFloat();
            y = HeightProvider.parse(c.getAsJsonObject("y"));
            yScale = parseFloat(c.get("yScale"));
            lavaLevel = resolveAnchor(c.getAsJsonObject("lava_level"));
            verticalRotation = parseFloat(c.get("vertical_rotation"));
            JsonObject shape = c.getAsJsonObject("shape");
            distanceFactor = parseFloat(shape.get("distance_factor"));
            thickness = parseFloat(shape.get("thickness"));
            widthSmoothness = shape.get("width_smoothness").getAsInt();
            horizontalRadiusFactor = parseFloat(shape.get("horizontal_radius_factor"));
            verticalRadiusDefaultFactor = shape.get("vertical_radius_default_factor").getAsFloat();
            verticalRadiusCenterFactor = shape.get("vertical_radius_center_factor").getAsFloat();
        }
    }

    // ================================================================== cave carver

    private interface SkipChecker {
        boolean shouldSkip(double xd, double yd, double zd, int y);
    }

    private void carveCave(CaveConfig config, VSurface.ChunkData chunk, int chunkX, int chunkZ,
                           VSurface.LegacyRandom random, VAquifer aquifer, int sourceX, int sourceZ, BitSet mask) {
        int maxDistance = (4 * 2 - 1) * 16;
        int caveCount = random.nextInt(random.nextInt(random.nextInt(15) + 1) + 1);
        for (int caveIndex = 0; caveIndex < caveCount; caveIndex++) {
            double x = (sourceX << 4) + random.nextInt(16);
            double y = config.y.sample(random);
            double z = (sourceZ << 4) + random.nextInt(16);
            double horizontalRadiusMultiplier = config.horizontalRadiusMultiplier.sample(random);
            double verticalRadiusMultiplier = config.verticalRadiusMultiplier.sample(random);
            double floorLevel = config.floorLevel.sample(random);
            SkipChecker skipChecker = (xd, yd, zd, worldY) ->
                    yd <= floorLevel || xd * xd + yd * yd + zd * zd >= 1.0;
            int tunnels = 1;
            if (random.nextInt(4) == 0) {
                double yScale = config.yScale.sample(random);
                float roomThickness = 1.0F + random.nextFloat() * 6.0F;
                double horizontalRadius = 1.5 + sin((float) (Math.PI / 2)) * roomThickness;
                carveEllipsoid(config.lavaLevel, chunk, chunkX, chunkZ, aquifer,
                        x + 1.0, y, z, horizontalRadius, horizontalRadius * yScale, mask, skipChecker);
                tunnels += random.nextInt(4);
            }
            for (int i = 0; i < tunnels; i++) {
                float horizontalRotation = random.nextFloat() * (float) (Math.PI * 2);
                float verticalRotation = (random.nextFloat() - 0.5F) / 4.0F;
                float thickness = random.nextFloat() * 2.0F + random.nextFloat();
                if (random.nextInt(10) == 0) {
                    thickness *= random.nextFloat() * random.nextFloat() * 3.0F + 1.0F;
                }
                int distance = maxDistance - random.nextInt(maxDistance / 4);
                createTunnel(config, chunk, chunkX, chunkZ, random.nextLong(), aquifer,
                        x, y, z, horizontalRadiusMultiplier, verticalRadiusMultiplier,
                        thickness, horizontalRotation, verticalRotation, 0, distance, 1.0, mask, skipChecker);
            }
        }
    }

    private void createTunnel(CaveConfig config, VSurface.ChunkData chunk, int chunkX, int chunkZ,
                              long tunnelSeed, VAquifer aquifer, double x, double y, double z,
                              double horizontalRadiusMultiplier, double verticalRadiusMultiplier,
                              float thickness, float horizontalRotation, float verticalRotation,
                              int step, int dist, double yScale, BitSet mask, SkipChecker skipChecker) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(tunnelSeed);
        int splitPoint = random.nextInt(dist / 2) + dist / 4;
        boolean steep = random.nextInt(6) == 0;
        float yRota = 0.0F;
        float xRota = 0.0F;

        for (int currentStep = step; currentStep < dist; currentStep++) {
            double horizontalRadius = 1.5 + sin((float) Math.PI * currentStep / dist) * thickness;
            double verticalRadius = horizontalRadius * yScale;
            float cosX = cos(verticalRotation);
            x += cos(horizontalRotation) * cosX;
            y += sin(verticalRotation);
            z += sin(horizontalRotation) * cosX;
            verticalRotation *= steep ? 0.92F : 0.7F;
            verticalRotation += xRota * 0.1F;
            horizontalRotation += yRota * 0.1F;
            xRota *= 0.9F;
            yRota *= 0.75F;
            xRota += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0F;
            yRota += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0F;
            if (currentStep == splitPoint && thickness > 1.0F) {
                createTunnel(config, chunk, chunkX, chunkZ, random.nextLong(), aquifer, x, y, z,
                        horizontalRadiusMultiplier, verticalRadiusMultiplier,
                        random.nextFloat() * 0.5F + 0.5F, horizontalRotation - (float) (Math.PI / 2),
                        verticalRotation / 3.0F, currentStep, dist, 1.0, mask, skipChecker);
                createTunnel(config, chunk, chunkX, chunkZ, random.nextLong(), aquifer, x, y, z,
                        horizontalRadiusMultiplier, verticalRadiusMultiplier,
                        random.nextFloat() * 0.5F + 0.5F, horizontalRotation + (float) (Math.PI / 2),
                        verticalRotation / 3.0F, currentStep, dist, 1.0, mask, skipChecker);
                return;
            }
            if (random.nextInt(4) != 0) {
                if (!canReach(chunkX, chunkZ, x, z, currentStep, dist, thickness)) return;
                carveEllipsoid(config.lavaLevel, chunk, chunkX, chunkZ, aquifer, x, y, z,
                        horizontalRadius * horizontalRadiusMultiplier,
                        verticalRadius * verticalRadiusMultiplier, mask, skipChecker);
            }
        }
    }

    // ================================================================== canyon carver

    private void carveCanyon(CanyonConfig config, VSurface.ChunkData chunk, int chunkX, int chunkZ,
                             VSurface.LegacyRandom random, VAquifer aquifer, int sourceX, int sourceZ, BitSet mask) {
        int maxDistance = (4 * 2 - 1) * 16;
        double x = (sourceX << 4) + random.nextInt(16);
        int y = config.y.sample(random);
        double z = (sourceZ << 4) + random.nextInt(16);
        float horizontalRotation = random.nextFloat() * (float) (Math.PI * 2);
        float verticalRotation = config.verticalRotation.sample(random);
        double yScale = config.yScale.sample(random);
        float thickness = config.thickness.sample(random);
        int distance = (int) (maxDistance * config.distanceFactor.sample(random));
        doCarveCanyon(config, chunk, chunkX, chunkZ, random.nextLong(), aquifer,
                x, y, z, thickness, horizontalRotation, verticalRotation, 0, distance, yScale, mask);
    }

    private void doCarveCanyon(CanyonConfig config, VSurface.ChunkData chunk, int chunkX, int chunkZ,
                               long tunnelSeed, VAquifer aquifer, double x, double y, double z,
                               float thickness, float horizontalRotation, float verticalRotation,
                               int step, int distance, double yScale, BitSet mask) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(tunnelSeed);
        float[] widthFactorPerHeight = new float[HEIGHT];
        float widthFactor = 1.0F;
        for (int yIndex = 0; yIndex < HEIGHT; yIndex++) {
            if (yIndex == 0 || random.nextInt(config.widthSmoothness) == 0) {
                widthFactor = 1.0F + random.nextFloat() * random.nextFloat();
            }
            widthFactorPerHeight[yIndex] = widthFactor * widthFactor;
        }
        SkipChecker skipChecker = (xd, yd, zd, worldY) -> {
            int yIndex = worldY - MIN_Y;
            return (xd * xd + zd * zd) * widthFactorPerHeight[yIndex - 1] + yd * yd / 6.0 >= 1.0;
        };
        float yRota = 0.0F;
        float xRota = 0.0F;

        for (int currentStep = step; currentStep < distance; currentStep++) {
            double horizontalRadius = 1.5 + sin(currentStep * (float) Math.PI / distance) * thickness;
            double verticalRadius = horizontalRadius * yScale;
            horizontalRadius *= config.horizontalRadiusFactor.sample(random);
            // updateVerticalRadius
            float verticalMultiplier = 1.0F - Math.abs(0.5F - (float) currentStep / distance) * 2.0F;
            float factor = config.verticalRadiusDefaultFactor + config.verticalRadiusCenterFactor * verticalMultiplier;
            verticalRadius = factor * verticalRadius * (random.nextFloat() * (1.0F - 0.75F) + 0.75F);

            float xc = cos(verticalRotation);
            float xs = sin(verticalRotation);
            x += cos(horizontalRotation) * xc;
            y += xs;
            z += sin(horizontalRotation) * xc;
            verticalRotation *= 0.7F;
            verticalRotation += xRota * 0.05F;
            horizontalRotation += yRota * 0.05F;
            xRota *= 0.8F;
            yRota *= 0.5F;
            xRota += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0F;
            yRota += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0F;
            if (random.nextInt(4) != 0) {
                if (!canReach(chunkX, chunkZ, x, z, currentStep, distance, thickness)) return;
                carveEllipsoid(config.lavaLevel, chunk, chunkX, chunkZ, aquifer, x, y, z,
                        horizontalRadius, verticalRadius, mask, skipChecker);
            }
        }
    }

    // ================================================================== ellipsoid + block carving

    private static boolean canReach(int chunkX, int chunkZ, double x, double z, int currentStep, int totalSteps, float thickness) {
        double xMid = (chunkX << 4) + 8;
        double zMid = (chunkZ << 4) + 8;
        double xd = x - xMid;
        double zd = z - zMid;
        double remaining = totalSteps - currentStep;
        double rr = thickness + 2.0F + 16.0F;
        return xd * xd + zd * zd - remaining * remaining <= rr * rr;
    }

    private void carveEllipsoid(int lavaLevel, VSurface.ChunkData chunk, int chunkX, int chunkZ,
                                VAquifer aquifer, double x, double y, double z,
                                double horizontalRadius, double verticalRadius, BitSet mask, SkipChecker skipChecker) {
        double centerX = (chunkX << 4) + 8;
        double centerZ = (chunkZ << 4) + 8;
        double maxDelta = 16.0 + horizontalRadius * 2.0;
        if (Math.abs(x - centerX) > maxDelta || Math.abs(z - centerZ) > maxDelta) return;
        int chunkMinX = chunkX << 4;
        int chunkMinZ = chunkZ << 4;
        int minXIndex = Math.max(VNoise.floor(x - horizontalRadius) - chunkMinX - 1, 0);
        int maxXIndex = Math.min(VNoise.floor(x + horizontalRadius) - chunkMinX, 15);
        int minY = Math.max(VNoise.floor(y - verticalRadius) - 1, MIN_Y + 1);
        int maxY = Math.min(VNoise.floor(y + verticalRadius) + 1, MIN_Y + HEIGHT - 1 - 7);
        int minZIndex = Math.max(VNoise.floor(z - horizontalRadius) - chunkMinZ - 1, 0);
        int maxZIndex = Math.min(VNoise.floor(z + horizontalRadius) - chunkMinZ, 15);

        for (int xIndex = minXIndex; xIndex <= maxXIndex; xIndex++) {
            int worldX = chunkMinX + xIndex;
            double xd = (worldX + 0.5 - x) / horizontalRadius;
            for (int zIndex = minZIndex; zIndex <= maxZIndex; zIndex++) {
                int worldZ = chunkMinZ + zIndex;
                double zd = (worldZ + 0.5 - z) / horizontalRadius;
                if (xd * xd + zd * zd >= 1.0) continue;
                boolean hasGrass = false;
                for (int worldY = maxY; worldY > minY; worldY--) {
                    double yd = (worldY - 0.5 - y) / verticalRadius;
                    if (skipChecker.shouldSkip(xd, yd, zd, worldY)) continue;
                    int maskIndex = ((worldY - MIN_Y) << 8) | (zIndex << 4) | xIndex;
                    if (mask.get(maskIndex)) continue;
                    mask.set(maskIndex);
                    hasGrass = carveBlock(lavaLevel, chunk, xIndex, worldY, zIndex, worldX, worldZ, aquifer, hasGrass);
                }
            }
        }
    }

    /** WorldCarver.carveBlock: returns the updated hasGrass flag. */
    private boolean carveBlock(int lavaLevel, VSurface.ChunkData chunk, int x, int y, int z,
                               int worldX, int worldZ, VAquifer aquifer, boolean hasGrass) {
        Block blockState = chunk.get(x, y, z);
        String name = blockState == null ? "minecraft:air" : blockState.name();
        if (name.equals("minecraft:grass_block") || name.equals("minecraft:mycelium")) {
            hasGrass = true;
        }
        if (!replaceable.contains(name)) return hasGrass;

        // getCarveState
        Block state;
        boolean fluid;
        if (y <= lavaLevel) {
            state = Block.LAVA;
            fluid = true;
        } else {
            int substance = aquifer.computeSubstance(worldX, y, worldZ, 0.0);
            if (substance == VAquifer.STONE) return hasGrass;
            state = switch (substance) {
                case VAquifer.WATER -> Block.WATER;
                case VAquifer.LAVA -> Block.LAVA;
                default -> null; // air
            };
            fluid = substance == VAquifer.WATER || substance == VAquifer.LAVA;
        }
        chunk.set(x, y, z, state);

        if (hasGrass) {
            Block below = chunk.get(x, y - 1, z);
            if (below != null && below.name().equals("minecraft:dirt")) {
                Block topMaterial = surface.topMaterial(chunk, worldX, y - 1, worldZ, fluid);
                if (topMaterial != null) chunk.set(x, y - 1, z, topMaterial);
            }
        }
        return hasGrass;
    }

    // ================================================================== Mth sin table

    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < 65536; i++) {
            SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0);
        }
    }

    static float sin(float value) {
        return SIN[(int) (value * 10430.378F) & 0xFFFF];
    }

    static float cos(float value) {
        return SIN[(int) (value * 10430.378F + 16384.0F) & 0xFFFF];
    }
}
