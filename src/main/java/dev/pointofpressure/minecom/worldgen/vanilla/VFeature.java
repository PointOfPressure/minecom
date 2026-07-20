package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.instance.block.Block;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Vanilla biome decoration: per-chunk decoration seeding (Xoroshiro-backed
 * WorldgenRandom), the FeatureSorter global feature ordering, the placement
 * modifier pipeline (depth-first like vanilla's lazy streams), and exact ports
 * of the mineral features: ore, disk, spring. Unimplemented feature types are
 * skipped — safe, because every feature draws from its own setFeatureSeed
 * random, so skipping one never shifts another's randomness.
 */
public final class VFeature {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    /** Cross-chunk world access for decoration; heights are LevelReader-style (top block + 1). */
    public interface Canvas {
        Block get(int x, int y, int z);

        void set(int x, int y, int z, Block block);

        /** First free y above the top motion-blocking non-fluid block (OCEAN_FLOOR_WG). */
        int oceanFloorHeight(int x, int z);

        /** First free y above the top non-air block (WORLD_SURFACE_WG). */
        int worldSurfaceHeight(int x, int z);
    }

    private final VBiome biomes;
    private final VSurface surface;
    private final long seed;
    private final Set<String> possibleBiomeUniverse = new HashSet<>();

    // features_per_step: per step, ordered feature ids + id -> index map
    private final List<List<String>> stepFeatures = new ArrayList<>();
    private final List<Map<String, Integer>> stepIndex = new ArrayList<>();
    // biome -> per-step list of feature ids; and flattened per-biome set (for biome filter)
    private final Map<String, List<List<String>>> biomeFeatures = new HashMap<>();
    private final Map<String, Set<String>> biomeFeatureSet = new HashMap<>();
    private final JsonObject placedFeatures;
    private final JsonObject configuredFeatures;
    private final JsonObject blockTags;
    private final Map<String, Set<String>> tagCache = new HashMap<>();
    private final VTree trees = new VTree(this);

    public VFeature(VBiome biomes, VSurface surface, long seed) {
        this.biomes = biomes;
        this.surface = surface;
        this.seed = seed;
        Gson gson = new Gson();
        try (var a = res("/vanilla/features_per_step.json");
             var b = res("/vanilla/biome_features.json");
             var c = res("/vanilla/placed_features.json");
             var d = res("/vanilla/configured_features.json");
             var e = res("/vanilla/tags_block.json")) {
            JsonArray steps = gson.fromJson(new InputStreamReader(a, StandardCharsets.UTF_8), JsonArray.class);
            for (JsonElement stepEl : steps) {
                List<String> ids = new ArrayList<>();
                Map<String, Integer> index = new HashMap<>();
                for (JsonElement idEl : stepEl.getAsJsonArray()) {
                    index.put(idEl.getAsString(), ids.size());
                    ids.add(idEl.getAsString());
                }
                stepFeatures.add(ids);
                stepIndex.add(index);
            }
            JsonObject perBiome = gson.fromJson(new InputStreamReader(b, StandardCharsets.UTF_8), JsonObject.class);
            for (var en : perBiome.entrySet()) {
                List<List<String>> stepsList = new ArrayList<>();
                Set<String> flat = new HashSet<>();
                for (JsonElement stepEl : en.getValue().getAsJsonArray()) {
                    List<String> ids = new ArrayList<>();
                    for (JsonElement idEl : stepEl.getAsJsonArray()) {
                        ids.add(idEl.getAsString());
                        flat.add(idEl.getAsString());
                    }
                    stepsList.add(ids);
                }
                biomeFeatures.put(en.getKey(), stepsList);
                biomeFeatureSet.put(en.getKey(), flat);
                possibleBiomeUniverse.add(en.getKey());
            }
            placedFeatures = gson.fromJson(new InputStreamReader(c, StandardCharsets.UTF_8), JsonObject.class);
            configuredFeatures = gson.fromJson(new InputStreamReader(d, StandardCharsets.UTF_8), JsonObject.class);
            blockTags = gson.fromJson(new InputStreamReader(e, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Missing feature bundles", ex);
        }
    }

    private static java.io.InputStream res(String path) {
        return VFeature.class.getResourceAsStream(path);
    }

    Set<String> tag(String id) {
        Set<String> cached = tagCache.get(id);
        if (cached != null) return cached;
        Set<String> out = new HashSet<>();
        String plain = id.substring(id.indexOf(':') + 1);
        JsonObject entry = blockTags.has(plain) ? blockTags.getAsJsonObject(plain) : blockTags.getAsJsonObject(id);
        if (entry != null) {
            for (JsonElement e : entry.getAsJsonArray("values")) {
                String v = e.getAsString();
                if (v.startsWith("#")) out.addAll(tag(v.substring(1)));
                else out.add(v);
            }
        }
        tagCache.put(id, out);
        return out;
    }

    // ================================================================== decoration driver

    private final Map<Long, Set<String>> chunkBiomeCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Set<String>> eldest) {
                    return size() > 4096;
                }
            });

    private Set<String> chunkBiomeSet(int chunkX, int chunkZ) {
        return chunkBiomeCache.computeIfAbsent(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL), k -> {
            Set<String> set = new HashSet<>();
            int qBaseX = chunkX << 2, qBaseZ = chunkZ << 2;
            for (int qy = MIN_Y >> 2; qy < (MIN_Y + HEIGHT) >> 2; qy++) {
                for (int qx = 0; qx < 4; qx++) {
                    for (int qz = 0; qz < 4; qz++) {
                        set.add(biomes.biomeAt(qBaseX + qx, qy, qBaseZ + qz));
                    }
                }
            }
            return set;
        });
    }

    public void decorate(Canvas canvas, int chunkX, int chunkZ) {
        int minBlockX = chunkX << 4, minBlockZ = chunkZ << 4;
        XWorldgenRandom random = new XWorldgenRandom(0);
        long decorationSeed = random.setDecorationSeed(seed, minBlockX, minBlockZ);

        // biomes present in the 3x3 chunk neighborhood (all quarts, all sections)
        Set<String> possibleBiomes = new HashSet<>();
        for (int dcx = -1; dcx <= 1; dcx++) {
            for (int dcz = -1; dcz <= 1; dcz++) {
                possibleBiomes.addAll(chunkBiomeSet(chunkX + dcx, chunkZ + dcz));
            }
        }
        possibleBiomes.retainAll(possibleBiomeUniverse);

        int stepCount = stepFeatures.size();
        for (int step = 0; step < stepCount; step++) {
            TreeSet<Integer> indices = new TreeSet<>();
            for (String biome : possibleBiomes) {
                List<List<String>> perStep = biomeFeatures.get(biome);
                if (perStep != null && step < perStep.size()) {
                    Map<String, Integer> mapping = stepIndex.get(step);
                    for (String id : perStep.get(step)) {
                        Integer idx = mapping.get(id);
                        if (idx != null) indices.add(idx);
                    }
                }
            }
            for (int globalIndex : indices) {
                String featureId = stepFeatures.get(step).get(globalIndex);
                random.setFeatureSeed(decorationSeed, globalIndex, step);
                placePlaced(canvas, featureId, random, minBlockX, minBlockZ);
            }
        }
    }

    /** PlacedFeature.placeWithBiomeCheck: run the modifier pipeline depth-first. */
    private void placePlaced(Canvas canvas, String featureId, XWorldgenRandom random, int originX, int originZ) {
        JsonObject placed = placedFeatures.getAsJsonObject(featureId);
        if (placed == null) return;
        JsonArray placement = placed.getAsJsonArray("placement");
        JsonElement feature = placed.get("feature");
        placeRecursive(canvas, featureId, feature, placement, 0, random,
                originX, MIN_Y, originZ);
    }

    private void placeRecursive(Canvas canvas, String featureId, JsonElement feature, JsonArray placement,
                                int modIndex, XWorldgenRandom random, int x, int y, int z) {
        if (modIndex >= placement.size()) {
            placeConfigured(canvas, feature, random, x, y, z);
            return;
        }
        JsonObject mod = placement.get(modIndex).getAsJsonObject();
        String type = path(mod.get("type").getAsString());
        switch (type) {
            case "count" -> {
                int count = sampleInt(mod.get("count"), random);
                for (int i = 0; i < count; i++) {
                    placeRecursive(canvas, featureId, feature, placement, modIndex + 1, random, x, y, z);
                }
            }
            case "in_square" -> {
                int nx = random.nextInt(16) + x;
                int nz = random.nextInt(16) + z;
                placeRecursive(canvas, featureId, feature, placement, modIndex + 1, random, nx, y, nz);
            }
            case "height_range" -> {
                int ny = sampleHeight(mod.getAsJsonObject("height"), random);
                placeRecursive(canvas, featureId, feature, placement, modIndex + 1, random, x, ny, z);
            }
            case "biome" -> {
                String biome = surface.biomeAt(x, y, z);
                Set<String> set = biomeFeatureSet.get(biome);
                if (set != null && set.contains(featureId)) {
                    placeRecursive(canvas, featureId, feature, placement, modIndex + 1, random, x, y, z);
                }
            }
            case "rarity_filter" -> {
                int chance = mod.get("chance").getAsInt();
                if (random.nextFloat() < 1.0F / chance) {
                    placeRecursive(canvas, featureId, feature, placement, modIndex + 1, random, x, y, z);
                }
            }
            case "heightmap" -> {
                String hm = path(mod.get("heightmap").getAsString()).toUpperCase(java.util.Locale.ROOT);
                int ny = switch (hm) {
                    case "OCEAN_FLOOR_WG", "OCEAN_FLOOR" -> canvas.oceanFloorHeight(x, z);
                    case "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES" -> motionBlockingHeight(canvas, x, z);
                    default -> canvas.worldSurfaceHeight(x, z);
                };
                if (ny > MIN_Y) {
                    placeRecursive(canvas, featureId, feature, placement, modIndex + 1, random, x, ny, z);
                }
            }
            case "random_offset" -> {
                int xzSpread = sampleInt(mod.get("xz_spread"), random);
                int ySpread = sampleInt(mod.get("y_spread"), random);
                int xzSpread2 = sampleInt(mod.get("xz_spread"), random);
                placeRecursive(canvas, featureId, feature, placement, modIndex + 1, random,
                        x + xzSpread, y + ySpread, z + xzSpread2);
            }
            case "surface_water_depth_filter" -> {
                int maxDepth = mod.get("max_water_depth").getAsInt();
                if (canvas.worldSurfaceHeight(x, z) - canvas.oceanFloorHeight(x, z) <= maxDepth) {
                    placeRecursive(canvas, featureId, feature, placement, modIndex + 1, random, x, y, z);
                }
            }
            case "surface_relative_threshold_filter" -> {
                String hm = path(mod.get("heightmap").getAsString());
                long surfaceY = hm.toLowerCase(java.util.Locale.ROOT).startsWith("ocean")
                        ? canvas.oceanFloorHeight(x, z) : canvas.worldSurfaceHeight(x, z);
                long min = mod.has("min_inclusive") ? mod.get("min_inclusive").getAsLong() : Long.MIN_VALUE;
                long max = mod.has("max_inclusive") ? mod.get("max_inclusive").getAsLong() : Long.MAX_VALUE;
                if (surfaceY + min <= y && y <= surfaceY + max) {
                    placeRecursive(canvas, featureId, feature, placement, modIndex + 1, random, x, y, z);
                }
            }
            case "block_predicate_filter" -> {
                if (testPredicate(mod.getAsJsonObject("predicate"), canvas, x, y, z)) {
                    placeRecursive(canvas, featureId, feature, placement, modIndex + 1, random, x, y, z);
                }
            }
            case "environment_scan" -> {
                String dir = mod.get("direction_of_search").getAsString();
                int dy = dir.equals("up") ? 1 : -1;
                int maxSteps = mod.get("max_steps").getAsInt();
                JsonObject target = mod.getAsJsonObject("target_condition");
                JsonObject allowed = mod.has("allowed_search_condition") ? mod.getAsJsonObject("allowed_search_condition") : null;
                if (allowed == null || testPredicate(allowed, canvas, x, y, z)) {
                    int sy = y;
                    boolean found = false;
                    boolean outOfBounds = false;
                    for (int i = 0; i < maxSteps; i++) {
                        if (testPredicate(target, canvas, x, sy, z)) { found = true; break; }
                        sy += dy;
                        if (sy < MIN_Y || sy >= MIN_Y + HEIGHT) { outOfBounds = true; break; }
                        if (allowed != null && !testPredicate(allowed, canvas, x, sy, z)) break;
                    }
                    if (!outOfBounds && (found || testPredicate(target, canvas, x, sy, z))) {
                        placeRecursive(canvas, featureId, feature, placement, modIndex + 1, random, x, sy, z);
                    }
                }
            }
            default -> { } // unimplemented modifier: feature silently skipped
        }
    }

    // ================================================================== configured features

    private void placeConfigured(Canvas canvas, JsonElement featureRef, XWorldgenRandom random, int x, int y, int z) {
        JsonObject configured = featureRef.isJsonPrimitive()
                ? configuredFeatures.getAsJsonObject(featureRef.getAsString())
                : featureRef.getAsJsonObject();
        if (configured == null) return;
        String type = path(configured.get("type").getAsString());
        JsonObject config = configured.getAsJsonObject("config");
        switch (type) {
            case "ore" -> placeOre(canvas, config, random, x, y, z);
            case "disk" -> placeDisk(canvas, config, random, x, y, z);
            case "spring_feature" -> placeSpring(canvas, config, random, x, y, z);
            case "freeze_top_layer" -> placeFreezeTopLayer(canvas, x, z);
            case "simple_block" -> placeSimpleBlock(canvas, config, random, x, y, z);
            case "tree" -> trees.place(canvas, config, random, x, y, z);
            case "fallen_tree" -> trees.placeFallen(canvas, config, random, x, y, z);
            case "geode" -> placeGeode(canvas, config, random, x, y, z);
            case "vegetation_patch" -> placeVegetationPatch(canvas, config, random, x, y, z);
            case "sculk_patch" -> { if (SCULK_ENABLED) placeSculkPatch(canvas, config, random, x, y, z); }
            case "multiface_growth" -> {
                // sculk_vein and glow_lichen are the SAME vanilla MultifaceGrowthFeature; route
                // both through the faithful outward-walk port. sculk_vein stays gated.
                String blockName = config.has("block") ? config.get("block").getAsString() : "";
                boolean isSculkVein = blockName.equals("minecraft:sculk_vein");
                if (!isSculkVein || (SCULK_ENABLED && VEIN_ENABLED)) {
                    placeMultifaceGrowthGeneric(canvas, config, random, x, y, z);
                }
            }
            case "random_selector" -> {
                for (JsonElement e : config.getAsJsonArray("features")) {
                    JsonObject entry = e.getAsJsonObject();
                    if (random.nextFloat() < entry.get("chance").getAsFloat()) {
                        placeNestedPlaced(canvas, entry.get("feature"), random, x, y, z);
                        return;
                    }
                }
                placeNestedPlaced(canvas, config.get("default"), random, x, y, z);
            }
            case "random_boolean_selector" -> {
                boolean pick = random.nextInt(2) == 0 ? false : true; // nextBoolean = next(1)!=0
                placeNestedPlaced(canvas, pick ? config.get("feature_true") : config.get("feature_false"), random, x, y, z);
            }
            case "simple_random_selector" -> {
                JsonArray list = config.getAsJsonArray("features");
                int pick = random.nextInt(list.size());
                placeNestedPlaced(canvas, list.get(pick), random, x, y, z);
            }
            case "monster_room" -> placeMonsterRoom(canvas, random, x, y, z);
            default -> { } // unimplemented feature type
        }
    }

    // ------------------------------------------------------------------ sculk

    /**
     * Sculk generation is gated OFF by default. The VSculk port is a faithful
     * reimplementation of SculkSpreader/SculkPatchFeature, but sculk is a STOCHASTIC,
     * CROSS-CHUNK, order-dependent feature that spreads over the fully-placed world
     * (structures + ores). Under this generator's per-chunk, base-terrain feature
     * canvas the spread diverges enough to place net-harmful spurious sculk:
     * measured -0.06% overall exact-match on the ancient_city box. Enable with
     * -Dminecom.sculk=true once a persistent multi-chunk post-structure feature
     * buffer exists (the true prerequisite for sculk parity).
     */
    static final boolean SCULK_ENABLED = "true".equals(System.getProperty("minecom.sculk"));
    static final boolean VEIN_ENABLED = !"false".equals(System.getProperty("minecom.vein"));

    /** Adapter: VFeature.Canvas -> VSculk.World (null<->AIR conversion). */
    private static VSculk.World sculkWorld(Canvas canvas) {
        return new VSculk.World() {
            @Override public Block get(int x, int y, int z) {
                Block b = canvas.get(x, y, z);
                return b == null ? Block.AIR : b;
            }
            @Override public void set(int x, int y, int z, Block block) {
                canvas.set(x, y, z, block == null || block.isAir() ? null : block);
            }
        };
    }

    /** SculkPatchFeature (type minecraft:sculk_patch). */
    private void placeSculkPatch(Canvas canvas, JsonObject config, XWorldgenRandom random, int x, int y, int z) {
        int amountPerCharge = config.get("amount_per_charge").getAsInt();
        float catalystChance = config.get("catalyst_chance").getAsFloat();
        int chargeCount = config.get("charge_count").getAsInt();
        int growthRounds = config.get("growth_rounds").getAsInt();
        int spreadRounds = config.get("spread_rounds").getAsInt();
        int spreadAttempts = config.get("spread_attempts").getAsInt();
        int egMin = 0, egMax = 0;
        JsonElement eg = config.get("extra_rare_growths");
        if (eg != null && eg.isJsonObject()) {
            JsonObject o = eg.getAsJsonObject();
            egMin = o.get("min_inclusive").getAsInt();
            egMax = o.get("max_inclusive").getAsInt();
        }
        VSculk.placeSculkPatch(sculkWorld(canvas), x, y, z, random,
                amountPerCharge, catalystChance, chargeCount,
                spreadRounds, growthRounds, spreadAttempts, egMin, egMax);
    }

    /** MultifaceGrowthFeature, generic block target (glow_lichen and sculk_vein alike). */
    private void placeMultifaceGrowthGeneric(Canvas canvas, JsonObject config, XWorldgenRandom random, int x, int y, int z) {
        Block target = blockByName(config.get("block").getAsString());
        if (target == null) return;
        MultifaceParams p = parseMultifaceParams(config);
        VSculk.placeMultifaceGrowth(sculkWorld(canvas), target, x, y, z, random,
                p.searchRange, p.canFloor, p.canCeiling, p.canWall, p.chance, p.canOn);
    }

    private static Block blockByName(String name) {
        try {
            return Block.fromKey(name);
        } catch (Exception e) {
            return null;
        }
    }

    private record MultifaceParams(int searchRange, boolean canFloor, boolean canCeiling, boolean canWall,
                                    float chance, Set<String> canOn) {}

    private MultifaceParams parseMultifaceParams(JsonObject config) {
        int searchRange = config.has("search_range") ? config.get("search_range").getAsInt() : 10;
        boolean canFloor = config.has("can_place_on_floor") && config.get("can_place_on_floor").getAsBoolean();
        boolean canCeiling = config.has("can_place_on_ceiling") && config.get("can_place_on_ceiling").getAsBoolean();
        boolean canWall = config.has("can_place_on_wall") && config.get("can_place_on_wall").getAsBoolean();
        float chance = config.has("chance_of_spreading") ? config.get("chance_of_spreading").getAsFloat() : 0.5f;
        Set<String> canOn = new HashSet<>();
        if (config.has("can_be_placed_on")) {
            for (JsonElement e : config.getAsJsonArray("can_be_placed_on")) canOn.add(e.getAsString());
        }
        return new MultifaceParams(searchRange, canFloor, canCeiling, canWall, chance, canOn);
    }

    /** Nested placed-feature reference: registry id string or inline {feature, placement}. */
    private void placeNestedPlaced(Canvas canvas, JsonElement ref, XWorldgenRandom random, int x, int y, int z) {
        if (ref == null) return;
        JsonObject placed = ref.isJsonPrimitive() ? placedFeatures.getAsJsonObject(ref.getAsString())
                : ref.getAsJsonObject();
        if (placed == null) return;
        JsonArray placement = placed.has("placement") ? placed.getAsJsonArray("placement") : new JsonArray();
        placeRecursive(canvas, "", placed.get("feature"), placement, 0, random, x, y, z);
    }

    // ------------------------------------------------------------------ freeze_top_layer

    /** Blocks with no collision, invisible to the MOTION_BLOCKING heightmap. */
    private static final Set<String> NON_MOTION_BLOCKING = Set.of(
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
            "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid", "minecraft:allium",
            "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip", "minecraft:white_tulip",
            "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley",
            "minecraft:sunflower", "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony",
            "minecraft:dead_bush", "minecraft:seagrass", "minecraft:tall_seagrass", "minecraft:kelp",
            "minecraft:kelp_plant", "minecraft:sugar_cane", "minecraft:torchflower", "minecraft:pitcher_plant",
            "minecraft:closed_eyeblossom", "minecraft:open_eyeblossom", "minecraft:wildflowers",
            "minecraft:leaf_litter", "minecraft:bush", "minecraft:firefly_bush", "minecraft:cactus_flower",
            "minecraft:pale_moss_carpet", "minecraft:crimson_roots", "minecraft:warped_roots");

    private int motionBlockingHeight(Canvas canvas, int x, int z) {
        for (int y = MIN_Y + HEIGHT - 1; y >= MIN_Y; y--) {
            Block b = canvas.get(x, y, z);
            if (b == null) continue;
            if (!NON_MOTION_BLOCKING.contains(b.name())) return y + 1;
        }
        return MIN_Y;
    }

    private void placeFreezeTopLayer(Canvas canvas, int originX, int originZ) {
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = originX + dx;
                int z = originZ + dz;
                int y = motionBlockingHeight(canvas, x, z);
                String biomeTop = surface.biomeAt(x, y, z);
                // shouldFreeze at the block below (water -> ice)
                String biomeBelow = biomeTop; // vanilla samples biome at topPos for both checks
                Block below = canvas.get(x, y - 1, z);
                if (below == Block.WATER && !(surface.biomeTemperature(biomeBelow, x, y - 1, z) >= 0.15F)) {
                    canvas.set(x, y - 1, z, Block.ICE);
                    below = Block.ICE;
                }
                // shouldSnow at topPos
                if (surface.hasPrecipitation(biomeTop)
                        && surface.coldEnoughToSnow(biomeTop, x, y, z)
                        && canvas.get(x, y, z) == null
                        && below != null && below != Block.WATER && below != Block.LAVA
                        && !NON_MOTION_BLOCKING.contains(below.name())) {
                    canvas.set(x, y, z, Block.SNOW);
                    if (below.name().equals("minecraft:grass_block") || below.name().equals("minecraft:podzol")
                            || below.name().equals("minecraft:mycelium")) {
                        canvas.set(x, y - 1, z, below.withProperty("snowy", "true"));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ vegetation_patch

    /**
     * VegetationPatchFeature.place (floor/ceiling ground patches with vegetation on top — moss
     * patches, drip-leaf clay pools' non-waterlogged variant would need the waterlogged subclass,
     * unported). Real vanilla's HashSet<BlockPos> iteration order for distributeVegetation matters
     * for RNG-consumption order — reuses VTree.Pos's exact BlockPos.hashCode port for this.
     */
    private void placeVegetationPatch(Canvas canvas, JsonObject config, XWorldgenRandom random, int ox, int oy, int oz) {
        Set<String> replaceable = tag(config.get("replaceable").getAsString());
        JsonObject groundStateProvider = config.getAsJsonObject("ground_state");
        JsonElement vegFeature = config.get("vegetation_feature");
        boolean floor = !config.get("surface").getAsString().equals("ceiling");
        int inwardsDy = floor ? -1 : 1;   // CaveSurface.FLOOR -> DOWN, CEILING -> UP
        float extraBottomChance = config.get("extra_bottom_block_chance").getAsFloat();
        int verticalRange = config.get("vertical_range").getAsInt();
        float vegetationChance = config.get("vegetation_chance").getAsFloat();
        float extraEdgeChance = config.get("extra_edge_column_chance").getAsFloat();

        int xRadius = sampleInt(config.get("xz_radius"), random) + 1;
        int zRadius = sampleInt(config.get("xz_radius"), random) + 1;

        java.util.LinkedHashSet<VTree.Pos> surfaceIns = new java.util.LinkedHashSet<>();
        for (int dx = -xRadius; dx <= xRadius; dx++) {
            boolean isXEdge = dx == -xRadius || dx == xRadius;
            for (int dz = -zRadius; dz <= zRadius; dz++) {
                boolean isZEdge = dz == -zRadius || dz == zRadius;
                boolean isEdge = isXEdge || isZEdge;
                boolean isCorner = isXEdge && isZEdge;
                boolean isEdgeButNotCorner = isEdge && !isCorner;
                if (isCorner) continue;
                if (isEdgeButNotCorner && extraEdgeChance != 0.0F && !(random.nextFloat() > extraEdgeChance)) continue;

                int px = ox + dx, pz = oz + dz, py = oy;
                int offset = 0;
                while (canvas.get(px, py, pz) == null && offset < verticalRange) { py += inwardsDy; offset++; }
                int offset2 = 0;
                while (canvas.get(px, py, pz) != null && offset2 < verticalRange) { py -= inwardsDy; offset2++; }
                int belowY = py + inwardsDy;
                boolean posEmpty = canvas.get(px, py, pz) == null;
                Block belowBlock = canvas.get(px, belowY, pz);
                if (posEmpty && isFullCube(belowBlock)) {
                    int depth = sampleInt(config.get("depth"), random)
                            + ((extraBottomChance > 0.0F && random.nextFloat() < extraBottomChance) ? 1 : 0);
                    int groundX = px, groundY = belowY, groundZ = pz;
                    boolean placed = placeVegetationGroundColumn(canvas, groundStateProvider, replaceable, random,
                            px, belowY, pz, depth, inwardsDy);
                    if (placed) surfaceIns.add(new VTree.Pos(groundX, groundY, groundZ));
                }
            }
        }
        List<VTree.Pos> surface = new ArrayList<>(new HashSet<>(surfaceIns));
        for (VTree.Pos p : surface) {
            if (vegetationChance > 0.0F && random.nextFloat() < vegetationChance) {
                placeNestedPlaced(canvas, vegFeature, random, p.x(), p.y() - inwardsDy, p.z());
            }
        }
    }

    private boolean placeVegetationGroundColumn(Canvas canvas, JsonObject groundStateProvider, Set<String> replaceable,
                                                XWorldgenRandom random, int px, int py, int pz, int depth, int inwardsDy) {
        int y = py;
        for (int i = 0; i < depth; i++) {
            Block stateToPlace = ruleBasedState(groundStateProvider, canvas, random, px, y, pz);
            if (stateToPlace == null) return i != 0;
            Block belowState = canvas.get(px, y, pz);
            String belowName = belowState == null ? "minecraft:air" : belowState.key().asString();
            if (!stateToPlace.key().asString().equals(belowName)) {
                if (!replaceable.contains(belowName)) return i != 0;
                canvas.set(px, y, pz, stateToPlace);
                y += inwardsDy;
            }
        }
        return true;
    }

    private static boolean isFullCube(Block b) {
        if (b == null) return false;
        return VBlockShapes.isFullCube(b.key().asString(), b);
    }

    // ------------------------------------------------------------------ simple_block

    /** VegetationBlock.mayPlaceOn: state.is(#minecraft:supports_vegetation) (dirt/mud/moss/grass + farmland). */
    private boolean mayPlacePlantOn(Block below) {
        if (below == null) return false;
        return tag("minecraft:supports_vegetation").contains(below.name());
    }

    private void placeSimpleBlock(Canvas canvas, JsonObject config, XWorldgenRandom random, int x, int y, int z) {
        Block state = ruleBasedState(config.getAsJsonObject("to_place"), canvas, random, x, y, z);
        if (state == null) return;
        String name = state.name();
        boolean doublePlant = name.equals("minecraft:tall_grass") || name.equals("minecraft:large_fern")
                || name.equals("minecraft:sunflower") || name.equals("minecraft:lilac")
                || name.equals("minecraft:rose_bush") || name.equals("minecraft:peony")
                || name.equals("minecraft:pitcher_plant");
        // canSurvive: plants need dirt-family below (covers the vegetation features we handle)
        if (!mayPlacePlantOn(canvas.get(x, y - 1, z))) return;
        // only place into air (placement pipelines target air positions; guard replaces vanilla's canSurvive nuances)
        if (canvas.get(x, y, z) != null) return;
        if (doublePlant) {
            if (canvas.get(x, y + 1, z) != null) return;
            canvas.set(x, y, z, state.withProperty("half", "lower"));
            canvas.set(x, y + 1, z, state.withProperty("half", "upper"));
        } else {
            canvas.set(x, y, z, state);
        }
    }

    // ------------------------------------------------------------------ monster room (dungeon)

    private static final String[] MONSTER_ROOM_MOBS = {
            "minecraft:skeleton", "minecraft:zombie", "minecraft:zombie", "minecraft:spider"
    };

    /**
     * MonsterRoomFeature.place (decompile-verified against 26.2, cached at
     * vanilla-src/net/minecraft/world/level/levelgen/feature/MonsterRoomFeature.java): the
     * classic "dungeon" — a small cobble/mossy-cobblestone room carved from existing solid
     * terrain, validity-gated on a real air-pocket count (1-5 side openings at floor level, AND
     * a solid floor at dy=-1 and ceiling at dy=4 across the whole footprint — no partial rooms),
     * 0-2 loot chests (facing chosen via {@link #mrReorientChest}, ported from
     * StructurePiece.reorient, decompile-verified at
     * vanilla-src/net/minecraft/world/level/levelgen/structure/StructurePiece.java), and a
     * center spawner rolled uniformly from {skeleton, zombie, zombie, spider} (the doubled
     * zombie entry is real vanilla, not a bug — {@code Util.getRandom} on a 4-element array with
     * two zombie slots, i.e. zombie 50% / skeleton 25% / spider 25%). Placement (count/height
     * range) comes from the already-bundled {@code minecraft:monster_room} /
     * {@code minecraft:monster_room_deep} placed features (placed_features.json) — no new
     * worldgen data was needed, only this feature-type handler.
     */
    private boolean placeMonsterRoom(Canvas canvas, XWorldgenRandom random, int ox, int oy, int oz) {
        Set<String> cannotReplace = tag("minecraft:features_cannot_replace");
        int xr = random.nextInt(2) + 2;
        int minX = -xr - 1, maxX = xr + 1;
        int zr = random.nextInt(2) + 2;
        int minZ = -zr - 1, maxZ = zr + 1;
        int holeCount = 0;

        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = -1; dy <= 4; dy++) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    int hx = ox + dx, hy = oy + dy, hz = oz + dz;
                    boolean solid = mrSolid(canvas, hx, hy, hz);
                    if (dy == -1 && !solid) return false;
                    if (dy == 4 && !solid) return false;
                    if ((dx == minX || dx == maxX || dz == minZ || dz == maxZ) && dy == 0
                            && canvas.get(hx, hy, hz) == null && canvas.get(hx, hy + 1, hz) == null) {
                        holeCount++;
                    }
                }
            }
        }
        if (holeCount < 1 || holeCount > 5) return false;

        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = 3; dy >= -1; dy--) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    int wx = ox + dx, wy = oy + dy, wz = oz + dz;
                    Block wallState = canvas.get(wx, wy, wz);
                    boolean edge = dx == minX || dy == -1 || dz == minZ || dx == maxX || dy == 4 || dz == maxZ;
                    if (edge) {
                        if (wy >= MIN_Y && !mrSolid(canvas, wx, wy - 1, wz)) {
                            canvas.set(wx, wy, wz, Block.CAVE_AIR); // unconditional — matches real level.setBlock, not safeSetBlock
                        } else if (wallState != null && wallState.isSolid() && !mrIsChest(wallState)) {
                            if (dy == -1 && random.nextInt(4) != 0) {
                                mrSafeSet(canvas, wx, wy, wz, Block.MOSSY_COBBLESTONE, cannotReplace);
                            } else {
                                mrSafeSet(canvas, wx, wy, wz, Block.COBBLESTONE, cannotReplace);
                            }
                        }
                    } else if (!mrIsChest(wallState) && !mrIsSpawner(wallState)) {
                        mrSafeSet(canvas, wx, wy, wz, Block.CAVE_AIR, cannotReplace);
                    }
                }
            }
        }

        for (int cc = 0; cc < 2; cc++) {
            for (int i = 0; i < 3; i++) {
                int xc = ox + random.nextInt(xr * 2 + 1) - xr;
                int zc = oz + random.nextInt(zr * 2 + 1) - zr;
                if (canvas.get(xc, oy, zc) == null) {
                    int wallCount = 0;
                    if (mrSolid(canvas, xc + 1, oy, zc)) wallCount++;
                    if (mrSolid(canvas, xc - 1, oy, zc)) wallCount++;
                    if (mrSolid(canvas, xc, oy, zc + 1)) wallCount++;
                    if (mrSolid(canvas, xc, oy, zc - 1)) wallCount++;
                    if (wallCount == 1) {
                        Block chest = mrReorientChest(canvas, xc, oy, zc);
                        if (mrSafeSet(canvas, xc, oy, zc, chest, cannotReplace)) {
                            dev.pointofpressure.minecom.blocks.Containers.registerLoot(
                                    new net.minestom.server.coordinate.Vec(xc, oy, zc), "minecraft:chests/simple_dungeon");
                        }
                        break;
                    }
                }
            }
        }

        if (mrSafeSet(canvas, ox, oy, oz, Block.SPAWNER, cannotReplace)) {
            String entityId = MONSTER_ROOM_MOBS[random.nextInt(MONSTER_ROOM_MOBS.length)];
            dev.pointofpressure.minecom.blocks.ClassicSpawners.registerSpawnerOverworld(ox, oy, oz, entityId);
        }
        return true;
    }

    /** Test hook: exposes {@link #placeMonsterRoom} to PlayTest so it can drive real dungeon
     *  generation end to end (validity gate, wall carve, chest loot, spawner registration)
     *  against a hand-built or world-backed {@link Canvas}, the same way
     *  {@code VStructureManager.testRenderMineshaftSpiderCorridor} exposes mineshaft placement. */
    public boolean testPlaceMonsterRoom(Canvas canvas, XWorldgenRandom random, int x, int y, int z) {
        return placeMonsterRoom(canvas, random, x, y, z);
    }

    /** Test hook: a fresh, deterministically-seeded {@link XWorldgenRandom} for PlayTest to
     *  drive (and, via a throwaway probe instance with the same seed, predict) feature RNG. */
    public static XWorldgenRandom testRandom(long seed) {
        return new XWorldgenRandom(seed);
    }

    private static boolean mrSolid(Canvas canvas, int x, int y, int z) {
        Block b = canvas.get(x, y, z);
        return b != null && b.isSolid();
    }

    private static boolean mrOccludes(Canvas canvas, int x, int y, int z) {
        Block b = canvas.get(x, y, z);
        return b != null && b.registry().occludes();
    }

    private static boolean mrIsChest(Block b) {
        return b != null && b.name().equals("minecraft:chest");
    }

    private static boolean mrIsSpawner(Block b) {
        return b != null && b.name().equals("minecraft:spawner");
    }

    /** Feature.safeSetBlock: only overwrite a position whose current block is NOT tagged
     *  minecraft:features_cannot_replace. Returns whether the write happened, so callers can
     *  gate follow-up state (chest loot table, spawner mob id) the same way real vanilla gates
     *  on the block entity actually having been placed. */
    private boolean mrSafeSet(Canvas canvas, int x, int y, int z, Block block, Set<String> cannotReplace) {
        Block current = canvas.get(x, y, z);
        String name = current == null ? "minecraft:air" : current.name();
        if (cannotReplace.contains(name)) return false;
        canvas.set(x, y, z, block);
        return true;
    }

    /** StructurePiece.reorient (decompile-verified): face the chest away from its single
     *  occluding (isSolidRender-equivalent — Minestom's registry().occludes()) horizontal
     *  neighbor; with zero or 2+ occluding neighbors, or any neighbor already a chest (returned
     *  unmodified, default north-facing), probe a fixed north/opposite/clockwise/opposite
     *  sequence instead. isSolidRender is deliberately distinct from the plain isSolid() used by
     *  the wallCount check above — both ported faithfully, not merged. */
    private Block mrReorientChest(Canvas canvas, int x, int y, int z) {
        Block chest = Block.CHEST;
        VTemplate.Dir solidNeighbor = null;
        for (VTemplate.Dir dir : new VTemplate.Dir[]{VTemplate.Dir.NORTH, VTemplate.Dir.SOUTH, VTemplate.Dir.WEST, VTemplate.Dir.EAST}) {
            Block neighbor = canvas.get(x + dir.dx, y, z + dir.dz);
            if (mrIsChest(neighbor)) return chest;
            if (neighbor != null && neighbor.registry().occludes()) {
                if (solidNeighbor != null) { solidNeighbor = null; break; }
                solidNeighbor = dir;
            }
        }
        if (solidNeighbor != null) {
            return chest.withProperty("facing", solidNeighbor.opposite().name().toLowerCase());
        }
        VTemplate.Dir lockDir = VTemplate.Dir.NORTH;
        if (mrOccludes(canvas, x + lockDir.dx, y, z + lockDir.dz)) lockDir = lockDir.opposite();
        if (mrOccludes(canvas, x + lockDir.dx, y, z + lockDir.dz)) lockDir = lockDir.clockWiseY();
        if (mrOccludes(canvas, x + lockDir.dx, y, z + lockDir.dz)) lockDir = lockDir.opposite();
        return chest.withProperty("facing", lockDir.name().toLowerCase());
    }

    // ------------------------------------------------------------------ ore

    private record OreTarget(String ruleType, Set<String> matchBlocks, float probability, Block state) {}

    private void placeOre(Canvas canvas, JsonObject config, XWorldgenRandom random, int ox, int oy, int oz) {
        int size = config.get("size").getAsInt();
        float discard = config.get("discard_chance_on_air_exposure").getAsFloat();
        List<OreTarget> targets = parseTargets(config.getAsJsonArray("targets"));

        float dir = random.nextFloat() * (float) Math.PI;
        float spreadXY = size / 8.0F;
        int maxRadius = ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double x0 = ox + Math.sin(dir) * spreadXY;
        double x1 = ox - Math.sin(dir) * spreadXY;
        double z0 = oz + Math.cos(dir) * spreadXY;
        double z1 = oz - Math.cos(dir) * spreadXY;
        double y0 = oy + random.nextInt(3) - 2;
        double y1 = oy + random.nextInt(3) - 2;
        int xStart = ox - ceil(spreadXY) - maxRadius;
        int yStart = oy - 2 - maxRadius;
        int zStart = oz - ceil(spreadXY) - maxRadius;
        int sizeXZ = 2 * (ceil(spreadXY) + maxRadius);
        int sizeY = 2 * (2 + maxRadius);

        boolean anyBelowSurface = false;
        for (int xp = xStart; xp <= xStart + sizeXZ && !anyBelowSurface; xp++) {
            for (int zp = zStart; zp <= zStart + sizeXZ; zp++) {
                if (yStart <= canvas.oceanFloorHeight(xp, zp)) {
                    anyBelowSurface = true;
                    break;
                }
            }
        }
        if (!anyBelowSurface) return;

        BitSet tested = new BitSet(sizeXZ * sizeY * sizeXZ);
        double[] data = new double[size * 4];
        for (int i = 0; i < size; i++) {
            float step = (float) i / size;
            double xx = VNoise.lerp(step, x0, x1);
            double yy = VNoise.lerp(step, y0, y1);
            double zz = VNoise.lerp(step, z0, z1);
            double ss = random.nextDouble() * size / 16.0;
            double r = ((VCarver.sin((float) Math.PI * step) + 1.0F) * ss + 1.0) / 2.0;
            data[i * 4] = xx;
            data[i * 4 + 1] = yy;
            data[i * 4 + 2] = zz;
            data[i * 4 + 3] = r;
        }
        for (int i1 = 0; i1 < size - 1; i1++) {
            if (data[i1 * 4 + 3] <= 0.0) continue;
            for (int i2 = i1 + 1; i2 < size; i2++) {
                if (data[i2 * 4 + 3] <= 0.0) continue;
                double dx = data[i1 * 4] - data[i2 * 4];
                double dy = data[i1 * 4 + 1] - data[i2 * 4 + 1];
                double dz = data[i1 * 4 + 2] - data[i2 * 4 + 2];
                double dr = data[i1 * 4 + 3] - data[i2 * 4 + 3];
                if (dr * dr > dx * dx + dy * dy + dz * dz) {
                    if (dr > 0.0) data[i2 * 4 + 3] = -1.0;
                    else data[i1 * 4 + 3] = -1.0;
                }
            }
        }
        for (int i = 0; i < size; i++) {
            double r = data[i * 4 + 3];
            if (r < 0.0) continue;
            double xx = data[i * 4];
            double yy = data[i * 4 + 1];
            double zz = data[i * 4 + 2];
            int xMin = Math.max(VNoise.floor(xx - r), xStart);
            int yMin = Math.max(VNoise.floor(yy - r), yStart);
            int zMin = Math.max(VNoise.floor(zz - r), zStart);
            int xMax = Math.max(VNoise.floor(xx + r), xMin);
            int yMax = Math.max(VNoise.floor(yy + r), yMin);
            int zMax = Math.max(VNoise.floor(zz + r), zMin);
            for (int x = xMin; x <= xMax; x++) {
                double xd = (x + 0.5 - xx) / r;
                if (xd * xd >= 1.0) continue;
                for (int y = yMin; y <= yMax; y++) {
                    double yd = (y + 0.5 - yy) / r;
                    if (xd * xd + yd * yd >= 1.0) continue;
                    for (int z = zMin; z <= zMax; z++) {
                        double zd = (z + 0.5 - zz) / r;
                        if (xd * xd + yd * yd + zd * zd >= 1.0 || y < MIN_Y || y >= MIN_Y + HEIGHT) continue;
                        int bit = x - xStart + (y - yStart) * sizeXZ + (z - zStart) * sizeXZ * sizeY;
                        if (tested.get(bit)) continue;
                        tested.set(bit);
                        Block current = canvas.get(x, y, z);
                        for (OreTarget target : targets) {
                            if (canPlaceOre(canvas, current, random, discard, target, x, y, z)) {
                                canvas.set(x, y, z, target.state);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private List<OreTarget> parseTargets(JsonArray targets) {
        List<OreTarget> out = new ArrayList<>();
        for (JsonElement e : targets) {
            JsonObject t = e.getAsJsonObject();
            JsonObject rule = t.getAsJsonObject("target");
            String ruleType = path(rule.get("predicate_type").getAsString());
            Set<String> match = new HashSet<>();
            float probability = 1.0F;
            switch (ruleType) {
                case "tag_match" -> match = tag(rule.get("tag").getAsString());
                case "block_match" -> match.add(rule.get("block").getAsString());
                case "random_block_match" -> {
                    match.add(rule.get("block").getAsString());
                    probability = rule.get("probability").getAsFloat();
                }
                case "blockstate_match" -> match.add(rule.getAsJsonObject("block_state").get("Name").getAsString());
                case "always_true" -> match = null;
                default -> { }
            }
            out.add(new OreTarget(ruleType, match, probability, VSurface.parseBlockState(t.getAsJsonObject("state"))));
        }
        return out;
    }

    private boolean canPlaceOre(Canvas canvas, Block current, XWorldgenRandom random, float discard,
                                OreTarget target, int x, int y, int z) {
        String name = current == null ? "minecraft:air" : current.name();
        boolean matches = switch (target.ruleType) {
            case "always_true" -> true;
            case "random_block_match" -> target.matchBlocks.contains(name) && random.nextFloat() < target.probability;
            default -> target.matchBlocks != null && target.matchBlocks.contains(name);
        };
        if (!matches) return false;
        if (discard <= 0.0F) return true;
        if (discard < 1.0F && random.nextFloat() >= discard) return true;
        return !isAdjacentToAir(canvas, x, y, z);
    }

    private static boolean isAdjacentToAir(Canvas canvas, int x, int y, int z) {
        return canvas.get(x + 1, y, z) == null || canvas.get(x - 1, y, z) == null
                || canvas.get(x, y + 1, z) == null || canvas.get(x, y - 1, z) == null
                || canvas.get(x, y, z + 1) == null || canvas.get(x, y, z - 1) == null;
    }

    // ------------------------------------------------------------------ disk

    private void placeDisk(Canvas canvas, JsonObject config, XWorldgenRandom random, int ox, int oy, int oz) {
        int halfHeight = config.get("half_height").getAsInt();
        int top = oy + halfHeight;
        int bottom = oy - halfHeight - 1;
        int r = sampleInt(config.get("radius"), random);
        JsonObject target = config.getAsJsonObject("target");
        JsonObject provider = config.getAsJsonObject("state_provider");

        // BlockPos.betweenClosed iterates x, then z (then y — constant here)
        for (int z = oz - r; z <= oz + r; z++) {
            for (int x = ox - r; x <= ox + r; x++) {
                int xd = x - ox, zd = z - oz;
                if (xd * xd + zd * zd > r * r) continue;
                for (int y = top; y > bottom; y--) {
                    if (testPredicate(target, canvas, x, y, z)) {
                        Block state = ruleBasedState(provider, canvas, random, x, y, z);
                        if (state != null) canvas.set(x, y, z, state);
                    }
                }
            }
        }
    }

    private Block ruleBasedState(JsonObject provider, Canvas canvas, XWorldgenRandom random, int x, int y, int z) {
        String type = path(provider.get("type").getAsString());
        return switch (type) {
            case "simple_state_provider" -> VSurface.parseBlockState(provider.getAsJsonObject("state"));
            case "weighted_state_provider" -> {
                JsonArray entries = provider.getAsJsonArray("entries");
                int total = 0;
                for (JsonElement e : entries) total += e.getAsJsonObject().get("weight").getAsInt();
                int r = random.nextInt(total);
                Block picked = null;
                for (JsonElement e : entries) {
                    JsonObject entry = e.getAsJsonObject();
                    r -= entry.get("weight").getAsInt();
                    if (r < 0) { picked = VSurface.parseBlockState(entry.getAsJsonObject("data")); break; }
                }
                yield picked;
            }
            case "rule_based_state_provider" -> {
                for (JsonElement e : provider.getAsJsonArray("rules")) {
                    JsonObject rule = e.getAsJsonObject();
                    if (testPredicate(rule.getAsJsonObject("if_true"), canvas, x, y, z)) {
                        yield ruleBasedState(rule.getAsJsonObject("then"), canvas, random, x, y, z);
                    }
                }
                yield ruleBasedState(provider.getAsJsonObject("fallback"), canvas, random, x, y, z);
            }
            default -> null;
        };
    }

    // ------------------------------------------------------------------ spring

    private void placeSpring(Canvas canvas, JsonObject config, XWorldgenRandom random, int x, int y, int z) {
        Set<String> valid = new HashSet<>();
        JsonElement vb = config.get("valid_blocks");
        if (vb.isJsonArray()) for (JsonElement e : vb.getAsJsonArray()) valid.add(e.getAsString());
        else valid.add(vb.getAsString());
        // SpringConfiguration.CODEC defaults, omitted from 26.2's serialized data
        int rockCount = config.has("rock_count") ? config.get("rock_count").getAsInt() : 4;
        int holeCount = config.has("hole_count") ? config.get("hole_count").getAsInt() : 1;
        boolean requiresBelow = !config.has("requires_block_below")
                || config.get("requires_block_below").getAsBoolean();
        String fluid = config.getAsJsonObject("state").get("Name").getAsString();
        Block state = fluid.equals("minecraft:lava") ? Block.LAVA : Block.WATER;

        if (!is(canvas, x, y + 1, z, valid)) return;
        if (requiresBelow && !is(canvas, x, y - 1, z, valid)) return;
        Block current = canvas.get(x, y, z);
        if (current != null && !valid.contains(current.name())) return;
        int rocks = 0;
        if (is(canvas, x - 1, y, z, valid)) rocks++;
        if (is(canvas, x + 1, y, z, valid)) rocks++;
        if (is(canvas, x, y, z - 1, valid)) rocks++;
        if (is(canvas, x, y, z + 1, valid)) rocks++;
        if (is(canvas, x, y - 1, z, valid)) rocks++;
        int holes = 0;
        if (canvas.get(x - 1, y, z) == null) holes++;
        if (canvas.get(x + 1, y, z) == null) holes++;
        if (canvas.get(x, y, z - 1) == null) holes++;
        if (canvas.get(x, y, z + 1) == null) holes++;
        if (canvas.get(x, y - 1, z) == null) holes++;
        if (rocks == rockCount && holes == holeCount) {
            canvas.set(x, y, z, state);
        }
    }

    private static boolean is(Canvas canvas, int x, int y, int z, Set<String> valid) {
        Block b = canvas.get(x, y, z);
        return b != null && valid.contains(b.name());
    }


    // ------------------------------------------------------------------ geode

    /** Legacy-random NormalNoise (geodes seed one from WorldgenRandom(LegacyRandomSource(worldSeed))). */
    private VNoise.Improved geodeFirst, geodeSecond;
    private static final double GEODE_INPUT_FACTOR = 1.0181268882175227;
    private static final double GEODE_FREQ = Math.pow(2.0, -4);

    private synchronized void initGeodeNoise() {
        if (geodeFirst != null) return;
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(seed);
        // each PerlinNoise: forkPositional (one nextLong) then Improved(fromHashOf("octave_-4"))
        long posSeed1 = ((long) random.next(32) << 32) + random.next(32);
        geodeFirst = new VNoise.Improved(new VSurface.LegacyRandom("octave_-4".hashCode() ^ posSeed1));
        long posSeed2 = ((long) random.next(32) << 32) + random.next(32);
        geodeSecond = new VNoise.Improved(new VSurface.LegacyRandom("octave_-4".hashCode() ^ posSeed2));
    }

    private double geodeNoise(double x, double y, double z) {
        // NormalNoise with 1 octave at firstOctave -4: valueFactor = (1/6)/0.2
        double first = geodeFirst.noise(VNoise.wrap(x * GEODE_FREQ), VNoise.wrap(y * GEODE_FREQ), VNoise.wrap(z * GEODE_FREQ));
        double second = geodeSecond.noise(VNoise.wrap(x * GEODE_INPUT_FACTOR * GEODE_FREQ),
                VNoise.wrap(y * GEODE_INPUT_FACTOR * GEODE_FREQ), VNoise.wrap(z * GEODE_INPUT_FACTOR * GEODE_FREQ));
        return (first + second) * 0.8333333333333334;
    }

    private void placeGeode(Canvas canvas, JsonObject config, XWorldgenRandom random, int ox, int oy, int oz) {
        initGeodeNoise();
        JsonObject blocks = config.getAsJsonObject("blocks");
        JsonObject layers = config.getAsJsonObject("layers");
        JsonObject crack = config.getAsJsonObject("crack");
        // GeodeConfiguration/GeodeLayerSettings/GeodeCrackSettings codec defaults —
        // 26.2 omits default-valued fields from the serialized data
        int minGenOffset = optInt(config, "min_gen_offset", -16);
        int maxGenOffset = optInt(config, "max_gen_offset", 16);
        double noiseMultiplier = optDouble(config, "noise_multiplier", 0.05);
        int invalidThreshold = config.get("invalid_blocks_threshold").getAsInt();
        double useAlternateChance = optDouble(config, "use_alternate_layer0_chance", 0.0);
        boolean requireAlternate = !config.has("placements_require_layer0_alternate")
                || config.get("placements_require_layer0_alternate").getAsBoolean();
        double usePotentialChance = optDouble(config, "use_potential_placements_chance", 0.35);
        Set<String> invalidBlocks = tag(blocks.get("invalid_blocks").getAsString().substring(1));
        Set<String> cannotReplace = tag(blocks.get("cannot_replace").getAsString().substring(1));

        JsonElement outerWallDistance = config.has("outer_wall_distance")
                ? config.get("outer_wall_distance") : uniformJson(4, 5);
        JsonElement pointOffset = config.has("point_offset")
                ? config.get("point_offset") : uniformJson(1, 2);
        int numPoints = sampleInt(config.has("distribution_points")
                ? config.get("distribution_points") : uniformJson(3, 4), random);
        int outerWallMax = outerWallDistance.getAsJsonObject().get("max_inclusive").getAsInt();
        double crackSizeAdjustment = (double) numPoints / outerWallMax;
        double innerAir = 1.0 / Math.sqrt(optDouble(layers, "filling", 1.7));
        double innermostBlockLayer = 1.0 / Math.sqrt(optDouble(layers, "inner_layer", 2.2) + crackSizeAdjustment);
        double innerCrust = 1.0 / Math.sqrt(optDouble(layers, "middle_layer", 3.2) + crackSizeAdjustment);
        double outerCrust = 1.0 / Math.sqrt(optDouble(layers, "outer_layer", 4.2) + crackSizeAdjustment);
        double crackSize = 1.0 / Math.sqrt(optDouble(crack, "base_crack_size", 2.0)
                + random.nextDouble() / 2.0 + (numPoints > 3 ? crackSizeAdjustment : 0.0));
        boolean shouldGenerateCrack = random.nextFloat() < (float) optDouble(crack, "generate_crack_chance", 1.0);
        int crackPointOffset = optInt(crack, "crack_point_offset", 2);

        List<long[]> points = new ArrayList<>(); // {x, y, z, pointOffset}
        int numInvalid = 0;
        for (int i = 0; i < numPoints; i++) {
            int dx = sampleInt(outerWallDistance, random);
            int dy = sampleInt(outerWallDistance, random);
            int dz = sampleInt(outerWallDistance, random);
            int px = ox + dx, py = oy + dy, pz = oz + dz;
            Block state = canvas.get(px, py, pz);
            String name = state == null ? "minecraft:air" : state.name();
            if (state == null || invalidBlocks.contains(name)) {
                if (++numInvalid > invalidThreshold) return;
            }
            points.add(new long[]{px, py, pz, sampleInt(pointOffset, random)});
        }

        List<int[]> crackPoints = new ArrayList<>();
        if (shouldGenerateCrack) {
            int offsetIndex = random.nextInt(4);
            int crackOffset = numPoints * 2 + 1;
            switch (offsetIndex) {
                case 0 -> { crackPoints.add(new int[]{ox + crackOffset, oy + 7, oz});
                            crackPoints.add(new int[]{ox + crackOffset, oy + 5, oz});
                            crackPoints.add(new int[]{ox + crackOffset, oy + 1, oz}); }
                case 1 -> { crackPoints.add(new int[]{ox, oy + 7, oz + crackOffset});
                            crackPoints.add(new int[]{ox, oy + 5, oz + crackOffset});
                            crackPoints.add(new int[]{ox, oy + 1, oz + crackOffset}); }
                case 2 -> { crackPoints.add(new int[]{ox + crackOffset, oy + 7, oz + crackOffset});
                            crackPoints.add(new int[]{ox + crackOffset, oy + 5, oz + crackOffset});
                            crackPoints.add(new int[]{ox + crackOffset, oy + 1, oz + crackOffset}); }
                default -> { crackPoints.add(new int[]{ox, oy + 7, oz});
                             crackPoints.add(new int[]{ox, oy + 5, oz});
                             crackPoints.add(new int[]{ox, oy + 1, oz}); }
            }
        }

        Block filling = VSurface.parseBlockState(blocks.getAsJsonObject("filling_provider").getAsJsonObject("state"));
        Block innerLayer = VSurface.parseBlockState(blocks.getAsJsonObject("inner_layer_provider").getAsJsonObject("state"));
        Block alternateInner = VSurface.parseBlockState(blocks.getAsJsonObject("alternate_inner_layer_provider").getAsJsonObject("state"));
        Block middleLayer = VSurface.parseBlockState(blocks.getAsJsonObject("middle_layer_provider").getAsJsonObject("state"));
        Block outerLayer = VSurface.parseBlockState(blocks.getAsJsonObject("outer_layer_provider").getAsJsonObject("state"));
        JsonArray innerPlacements = blocks.getAsJsonArray("inner_placements");
        List<int[]> potentialCrystal = new ArrayList<>();

        // BlockPos.betweenClosed: x fastest, then y, then z
        for (int z = oz + minGenOffset; z <= oz + maxGenOffset; z++) {
            for (int y = oy + minGenOffset; y <= oy + maxGenOffset; y++) {
                for (int x = ox + minGenOffset; x <= ox + maxGenOffset; x++) {
                    double noiseOffset = geodeNoise(x, y, z) * noiseMultiplier;
                    double distSumShell = 0.0;
                    double distSumCrack = 0.0;
                    for (long[] point : points) {
                        double ddx = x - point[0], ddy = y - point[1], ddz = z - point[2];
                        distSumShell += 1.0 / Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz + point[3]) + noiseOffset;
                    }
                    for (int[] point : crackPoints) {
                        double ddx = x - point[0], ddy = y - point[1], ddz = z - point[2];
                        distSumCrack += 1.0 / Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz + crackPointOffset) + noiseOffset;
                    }
                    if (distSumShell < outerCrust) continue;
                    if (shouldGenerateCrack && distSumCrack >= crackSize && distSumShell < innerAir) {
                        geodeSet(canvas, cannotReplace, x, y, z, null);
                    } else if (distSumShell >= innerAir) {
                        geodeSet(canvas, cannotReplace, x, y, z, filling == Block.AIR ? null : filling);
                    } else if (distSumShell >= innermostBlockLayer) {
                        boolean useAlternate = random.nextFloat() < useAlternateChance;
                        geodeSet(canvas, cannotReplace, x, y, z, useAlternate ? alternateInner : innerLayer);
                        if ((!requireAlternate || useAlternate) && random.nextFloat() < usePotentialChance) {
                            potentialCrystal.add(new int[]{x, y, z});
                        }
                    } else if (distSumShell >= innerCrust) {
                        geodeSet(canvas, cannotReplace, x, y, z, middleLayer);
                    } else {
                        geodeSet(canvas, cannotReplace, x, y, z, outerLayer);
                    }
                }
            }
        }

        // crystal buds: Direction.values() order = down, up, north, south, west, east
        int[][] dirs = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        String[] facing = {"down", "up", "north", "south", "west", "east"};
        for (int[] pos : potentialCrystal) {
            int pick = random.nextInt(innerPlacements.size());
            Block bud = VSurface.parseBlockState(innerPlacements.get(pick).getAsJsonObject());
            for (int d = 0; d < 6; d++) {
                Block budFacing = bud.withProperty("facing", facing[d]);
                int px = pos[0] + dirs[d][0], py = pos[1] + dirs[d][1], pz = pos[2] + dirs[d][2];
                Block placeState = canvas.get(px, py, pz);
                boolean water = placeState == Block.WATER;
                if (placeState == null || water) {
                    geodeSet(canvas, cannotReplace, px, py, pz,
                            budFacing.withProperty("waterlogged", water ? "true" : "false"));
                    break;
                }
            }
        }
    }

    private void geodeSet(Canvas canvas, Set<String> cannotReplace, int x, int y, int z, Block state) {
        Block current = canvas.get(x, y, z);
        if (current != null && cannotReplace.contains(current.name())) return;
        canvas.set(x, y, z, state);
    }

    // ================================================================== predicates & providers

    /** Block predicates: matching_blocks (w/ offset), matching_block_tag, not/all_of/any_of/true. */
    boolean testPredicate(JsonObject predicate, Canvas canvas, int x, int y, int z) {
        String type = path(predicate.get("type").getAsString());
        int[] off = {0, 0, 0};
        if (predicate.has("offset")) {
            JsonArray o = predicate.getAsJsonArray("offset");
            off = new int[]{o.get(0).getAsInt(), o.get(1).getAsInt(), o.get(2).getAsInt()};
        }
        int px = x + off[0], py = y + off[1], pz = z + off[2];
        return switch (type) {
            case "true" -> true;
            case "matching_blocks" -> {
                Block b = canvas.get(px, py, pz);
                String name = b == null ? "minecraft:air" : b.name();
                JsonElement blocks = predicate.get("blocks");
                if (blocks.isJsonArray()) {
                    for (JsonElement e : blocks.getAsJsonArray()) {
                        if (e.getAsString().equals(name)) yield true;
                    }
                    yield false;
                }
                yield blocks.getAsString().equals(name);
            }
            case "matching_block_tag" -> {
                Block b = canvas.get(px, py, pz);
                String name = b == null ? "minecraft:air" : b.name();
                yield tag(predicate.get("tag").getAsString()).contains(name);
            }
            case "matching_fluids" -> {
                Block b = canvas.get(px, py, pz);
                String name = b == null ? "minecraft:air" : b.name();
                JsonElement fluids = predicate.get("fluids");
                if (fluids.isJsonArray()) {
                    for (JsonElement e : fluids.getAsJsonArray()) {
                        if (e.getAsString().equals(name)) yield true;
                    }
                    yield false;
                }
                yield fluids.getAsString().equals(name);
            }
            case "solid" -> isFullCube(canvas.get(px, py, pz));
            case "has_sturdy_face" -> isFullCube(canvas.get(px, py, pz)); // full-cube approximation (direction-agnostic), matches VSculk precedent
            case "would_survive" -> {
                String state = predicate.getAsJsonObject("state").get("Name").getAsString();
                if (state.endsWith("_sapling") || state.endsWith("_propagule")) {
                    yield mayPlacePlantOn(canvas.get(px, py - 1, pz));
                }
                yield true;
            }
            case "not" -> !testPredicate(predicate.getAsJsonObject("predicate"), canvas, x, y, z);
            case "all_of" -> {
                for (JsonElement e : predicate.getAsJsonArray("predicates")) {
                    if (!testPredicate(e.getAsJsonObject(), canvas, x, y, z)) yield false;
                }
                yield true;
            }
            case "any_of" -> {
                for (JsonElement e : predicate.getAsJsonArray("predicates")) {
                    if (testPredicate(e.getAsJsonObject(), canvas, x, y, z)) yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    /** IntProvider: constant / uniform / others unimplemented. */
    /** Optional-with-default codec fields, omitted by 26.2's serializer when at the default. */
    static int optInt(JsonObject o, String field, int def) {
        return o.has(field) ? o.get(field).getAsInt() : def;
    }

    static double optDouble(JsonObject o, String field, double def) {
        return o.has(field) ? o.get(field).getAsDouble() : def;
    }

    /** UniformInt.of(min, max) in IntProvider JSON form, for defaulted provider fields. */
    static JsonObject uniformJson(int min, int max) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:uniform");
        o.addProperty("min_inclusive", min);
        o.addProperty("max_inclusive", max);
        return o;
    }

    static int sampleInt(JsonElement json, XWorldgenRandom random) {
        if (json.isJsonPrimitive()) return json.getAsInt();
        JsonObject o = json.getAsJsonObject();
        String type = path(o.get("type").getAsString());
        return switch (type) {
            case "constant" -> o.get("value").getAsInt();
            case "uniform" -> {
                int min = o.get("min_inclusive").getAsInt();
                int max = o.get("max_inclusive").getAsInt();
                yield random.nextInt(max - min + 1) + min;
            }
            case "biased_to_bottom" -> {
                int min = o.get("min_inclusive").getAsInt();
                int max = o.get("max_inclusive").getAsInt();
                yield min + random.nextInt(random.nextInt(max - min + 1) + 1);
            }
            case "weighted_list" -> {
                JsonArray distribution = o.getAsJsonArray("distribution");
                int total = 0;
                for (JsonElement e : distribution) total += e.getAsJsonObject().get("weight").getAsInt();
                int r = random.nextInt(total);
                for (JsonElement e : distribution) {
                    JsonObject entry = e.getAsJsonObject();
                    r -= entry.get("weight").getAsInt();
                    if (r < 0) yield sampleInt(entry.get("data"), random);
                }
                yield 0;
            }
            case "trapezoid" -> {
                int min = o.get("min").getAsInt();
                int max = o.get("max").getAsInt();
                int plateau = o.has("plateau") ? o.get("plateau").getAsInt() : 0;
                int range = max - min;
                if (plateau >= range) yield random.nextInt(max - min + 1) + min;
                int plateauStart = (range - plateau) / 2;
                int plateauEnd = range - plateauStart;
                yield min + random.nextInt(plateauEnd + 1) + random.nextInt(plateauStart + 1);
            }
            default -> 0;
        };
    }

    private static int resolveAnchor(JsonObject anchor) {
        if (anchor.has("absolute")) return anchor.get("absolute").getAsInt();
        if (anchor.has("above_bottom")) return MIN_Y + anchor.get("above_bottom").getAsInt();
        if (anchor.has("below_top")) return MIN_Y + HEIGHT - 1 - anchor.get("below_top").getAsInt();
        throw new IllegalStateException("Unknown anchor " + anchor);
    }

    /** HeightProvider: uniform / trapezoid. */
    static int sampleHeight(JsonObject o, XWorldgenRandom random) {
        String type = path(o.get("type").getAsString());
        int min = resolveAnchor(o.getAsJsonObject("min_inclusive"));
        int max = resolveAnchor(o.getAsJsonObject("max_inclusive"));
        return switch (type) {
            case "uniform" -> min > max ? min : random.nextInt(max - min + 1) + min;
            case "weighted_list" -> {
                JsonArray distribution = o.getAsJsonArray("distribution");
                int total = 0;
                for (JsonElement e : distribution) total += e.getAsJsonObject().get("weight").getAsInt();
                int r = random.nextInt(total);
                for (JsonElement e : distribution) {
                    JsonObject entry = e.getAsJsonObject();
                    r -= entry.get("weight").getAsInt();
                    if (r < 0) yield sampleInt(entry.get("data"), random);
                }
                yield 0;
            }
            case "trapezoid" -> {
                int plateau = o.has("plateau") ? o.get("plateau").getAsInt() : 0;
                if (min > max) yield min;
                int range = max - min;
                if (plateau >= range) yield random.nextInt(max - min + 1) + min;
                int plateauStart = (range - plateau) / 2;
                int plateauEnd = range - plateauStart;
                yield min + random.nextInt(plateauEnd + 1) + random.nextInt(plateauStart + 1);
            }
            default -> min;
        };
    }

    private static int ceil(float v) {
        int i = (int) v;
        return v > i ? i + 1 : i;
    }

    private static String path(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // ================================================================== WorldgenRandom over Xoroshiro

    /** Vanilla WorldgenRandom(XoroshiroRandomSource): BitRandomSource ops over xoroshiro nextLong. */
    public static final class XWorldgenRandom {
        private XRandom source;

        XWorldgenRandom(long seed) {
            source = new XRandom(seed);
        }

        void setSeed(long seed) {
            source = new XRandom(seed);
        }

        int next(int bits) {
            return (int) (source.nextLong() >>> 64 - bits);
        }

        public int nextInt(int bound) {
            if ((bound & bound - 1) == 0) return (int) ((long) bound * next(31) >> 31);
            int sample, modulo;
            do {
                sample = next(31);
                modulo = sample % bound;
            } while (sample - modulo + (bound - 1) < 0);
            return modulo;
        }

        /** BitRandomSource.nextBoolean: one next(1) draw. */
        public boolean nextBoolean() {
            return next(1) != 0;
        }

        int nextIntBetweenInclusive(int min, int max) {
            return nextInt(max - min + 1) + min;
        }

        long nextLong() {
            int upper = next(32);
            int lower = next(32);
            return ((long) upper << 32) + lower;
        }

        float nextFloat() {
            return next(24) * 5.9604645E-8F;
        }

        double nextDouble() {
            int upper = next(26);
            int lower = next(27);
            return (((long) upper << 27) + lower) * (double) 1.110223E-16F;
        }

        long setDecorationSeed(long worldSeed, int minBlockX, int minBlockZ) {
            setSeed(worldSeed);
            long xScale = nextLong() | 1L;
            long zScale = nextLong() | 1L;
            long result = minBlockX * xScale + minBlockZ * zScale ^ worldSeed;
            setSeed(result);
            return result;
        }

        void setFeatureSeed(long decorationSeed, int index, int step) {
            setSeed(decorationSeed + index + 10000L * step);
        }
    }
}
