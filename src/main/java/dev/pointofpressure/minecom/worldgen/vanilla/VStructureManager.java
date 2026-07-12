package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minestom.server.instance.block.Block;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ties the structure layers together for chunk generation: for a target chunk it
 * finds every candidate structure start within range (placement grid, layer 1),
 * validates the centre biome, assembles the jigsaw (cached), and places each
 * intersecting piece's blocks clipped to the chunk. Overworld jigsaw structures
 * with a constant/uniform rigid start (ancient_city, trial_chambers).
 */
public final class VStructureManager {

    /** One structure type wired for generation. */
    private static final class Type {
        final String setName;                 // placement set (VStructures)
        final VJigsaw.Def def;
        final Set<String> biomes;             // allowed centre biomes
        final Map<String, String> aliases;    // fixed pool-alias resolution (blocks-correct)
        final int radiusChunks;               // scan radius = ceil(maxDistH/16)+1
        final VBeardifier.Adjustment adjustment; // terrain_adaptation (NONE unless set)
        Type(String setName, VJigsaw.Def def, Set<String> biomes, Map<String, String> aliases) {
            this(setName, def, biomes, aliases, VBeardifier.Adjustment.NONE);
        }
        Type(String setName, VJigsaw.Def def, Set<String> biomes, Map<String, String> aliases, VBeardifier.Adjustment adjustment) {
            this.setName = setName; this.def = def; this.biomes = biomes; this.aliases = aliases;
            this.radiusChunks = (def.maxDistH / 16) + 2;
            this.adjustment = adjustment;
        }
    }

    private final VStructures placement;
    private final VJigsaw jigsaw;
    private final VBiome biomes;
    private final long seed;
    private final VJigsaw.SurfaceHeight surface;
    private final VJigsaw.SurfaceHeight oceanFloor;
    private final VSurface surfaceRef; // for coldEnoughToSnow (ruined portal "cold" flag); null when unavailable
    private final SubstanceAt substanceAt; // raw per-point terrain substance; null when unavailable
    private final int seaLevel;

    /** Raw per-point terrain substance query (VAquifer.STONE/AIR/WATER/LAVA), no heightmap scan. */
    public interface SubstanceAt { int at(int x, int y, int z); }
    private final List<Type> types = new ArrayList<>();

    /** Assembled start cache; null value = candidate that failed (biome/geometry). */
    private final Map<String, VJigsaw.Assembly> starts = new HashMap<>();

    /** Ruined-portal flavors (see RPPiece javadoc). */
    private final List<RPFlavor> ruinedPortalFlavors = new ArrayList<>();
    private final Map<String, RPPiece> ruinedPortalStarts = new HashMap<>();

    /** Mineshaft (see MSPiece javadoc). */
    private Set<String> mineshaftBiomes;
    private Set<String> mineshaftMesaBiomes;
    private Set<String> mineshaftBlockingBiomes;

    /** Igloo (see IGPiece javadoc). */
    private Set<String> iglooBiomes;
    private final Map<String, List<IGPiece>> iglooPieces = new HashMap<>();

    /** Swamp hut (see SHPiece javadoc). */
    private Set<String> swampHutBiomes;
    private final Map<String, SHPiece> swampHutStarts = new HashMap<>();

    /** Jungle temple (see JTPiece javadoc). */
    private Set<String> jungleTempleBiomes;
    private final Map<String, JTPiece> jungleTempleStarts = new HashMap<>();

    /** Desert pyramid (see DPPiece javadoc). */
    private Set<String> desertPyramidBiomes;
    private final Map<String, DPPiece> desertPyramidStarts = new HashMap<>();

    /** Shipwreck (see SWPiece javadoc). */
    private Set<String> shipwreckBeachedBiomes;
    private Set<String> shipwreckOceanBiomes;
    private final Map<String, SWPiece> shipwreckStarts = new HashMap<>();

    /** Buried treasure (see placeBuriedTreasures javadoc). */
    private Set<String> buriedTreasureBiomes;
    private final Map<String, int[]> buriedTreasureStarts = new HashMap<>();

    /** Ocean ruin, warm flavor only, single-piece (see OceanRuinPiece javadoc). */
    private Set<String> oceanRuinWarmBiomes;
    private Set<String> oceanRuinColdBiomes;
    private final Map<String, List<OceanRuinPiece>> oceanRuinStarts = new HashMap<>();

    /** Ocean monument (see placeOceanMonuments javadoc). */
    private Set<String> oceanMonumentSurroundingBiomes;
    private record OceanMonumentAssembly(VMonumentGen.Building building, List<VMonumentGen.RoomPiece> rooms) {}
    private final Map<String, OceanMonumentAssembly> oceanMonumentStarts = new HashMap<>();

    /** Woodland mansion (see placeWoodlandMansions javadoc). */
    private Set<String> woodlandMansionBiomes;
    private final Map<String, List<VMansionGen.Piece>> woodlandMansionStarts = new HashMap<>();

    public VStructureManager(long seed, VBiome biomes) {
        this(seed, biomes, null, 63);
    }

    public VStructureManager(long seed, VBiome biomes, VJigsaw.SurfaceHeight surface, int seaLevel) {
        this(seed, biomes, surface, null, seaLevel);
    }

    public VStructureManager(long seed, VBiome biomes, VJigsaw.SurfaceHeight surface, VJigsaw.SurfaceHeight oceanFloor, int seaLevel) {
        this(seed, biomes, surface, oceanFloor, null, seaLevel);
    }

    public VStructureManager(long seed, VBiome biomes, VJigsaw.SurfaceHeight surface, VJigsaw.SurfaceHeight oceanFloor, VSurface surfaceRef, int seaLevel) {
        this(seed, biomes, surface, oceanFloor, surfaceRef, null, seaLevel);
    }

    public VStructureManager(long seed, VBiome biomes, VJigsaw.SurfaceHeight surface, VJigsaw.SurfaceHeight oceanFloor,
                              VSurface surfaceRef, SubstanceAt substanceAt, int seaLevel) {
        this.seed = seed;
        this.placement = new VStructures(seed);
        this.jigsaw = new VJigsaw(seed);
        this.biomes = biomes;
        this.surface = surface;
        this.oceanFloor = oceanFloor;
        this.surfaceRef = surfaceRef;
        this.substanceAt = substanceAt;
        this.seaLevel = seaLevel;
        if (surface != null) this.jigsaw.setSurface(surface);

        types.add(new Type("minecraft:ancient_cities",
                VJigsaw.Def.constant("minecraft:ancient_city/city_center", "minecraft:city_anchor", 7, -27, 116, 116, false),
                loadBiomes("ancient_city"), Map.of(), VBeardifier.Adjustment.BEARD_BOX));
        types.add(new Type("minecraft:trial_chambers",
                VJigsaw.Def.uniform("minecraft:trial_chambers/chamber/end", null, 20, -40, -20, 116, 116, false, 10),
                loadBiomes("trial_chambers"), TRIAL_ALIASES, VBeardifier.Adjustment.ENCAPSULATE));
        if (surface != null) {
            for (String v : new String[]{"plains", "desert", "savanna", "snowy", "taiga"}) {
                types.add(new Type("minecraft:villages",
                        VJigsaw.Def.village("minecraft:village/" + v + "/town_centers", 6, 80),
                        loadBiomes("village_" + v), Map.of(), VBeardifier.Adjustment.BEARD_THIN));
            }
            types.add(new Type("minecraft:trail_ruins",
                    VJigsaw.Def.projected("minecraft:trail_ruins/tower", 7, -15, 80),
                    loadBiomes("trail_ruins"), Map.of(), VBeardifier.Adjustment.BURY));
            types.add(new Type("minecraft:pillager_outposts",
                    VJigsaw.Def.village("minecraft:pillager_outpost/base_plates", 7, 80),
                    loadBiomes("pillager_outpost"), Map.of(), VBeardifier.Adjustment.BEARD_THIN));
        }
        if (surface != null) {
            ruinedPortalFlavors.add(new RPFlavor("standard", List.of(
                    new RPSetup(RPPlacement.UNDERGROUND, 1.0F, 0.2F, false, false, true, false, 0.5F),
                    new RPSetup(RPPlacement.ON_LAND_SURFACE, 0.5F, 0.2F, false, false, true, false, 0.5F)),
                    loadBiomes("ruined_portal")));
            ruinedPortalFlavors.add(new RPFlavor("desert", List.of(
                    new RPSetup(RPPlacement.PARTLY_BURIED, 0.0F, 0.0F, false, false, false, false, 1.0F)),
                    loadBiomes("ruined_portal_desert")));
            ruinedPortalFlavors.add(new RPFlavor("jungle", List.of(
                    new RPSetup(RPPlacement.ON_LAND_SURFACE, 0.5F, 0.8F, true, true, false, false, 1.0F)),
                    loadBiomes("ruined_portal_jungle")));
            ruinedPortalFlavors.add(new RPFlavor("mountain", List.of(
                    new RPSetup(RPPlacement.IN_MOUNTAIN, 1.0F, 0.2F, false, false, true, false, 0.5F),
                    new RPSetup(RPPlacement.ON_LAND_SURFACE, 0.5F, 0.2F, false, false, true, false, 0.5F)),
                    loadBiomes("ruined_portal_mountain")));
            this.mineshaftBiomes = loadBiomes("mineshaft");
            this.mineshaftMesaBiomes = loadBiomes("mineshaft_mesa");
            this.mineshaftBlockingBiomes = loadBiomes("mineshaft_blocking");
            this.iglooBiomes = loadBiomes("igloo");
            this.swampHutBiomes = loadBiomes("swamp_hut");
            this.jungleTempleBiomes = loadBiomes("jungle_temple");
            this.desertPyramidBiomes = loadBiomes("desert_pyramid");
            this.shipwreckBeachedBiomes = loadBiomes("shipwreck_beached");
            this.woodlandMansionBiomes = loadBiomes("woodland_mansion");
        }
        if (oceanFloor != null) {
            this.buriedTreasureBiomes = loadBiomes("buried_treasure");
            this.shipwreckOceanBiomes = loadBiomes("shipwreck");
            this.oceanRuinWarmBiomes = loadBiomes("ocean_ruin_warm");
            this.oceanRuinColdBiomes = loadBiomes("ocean_ruin_cold");
            this.oceanMonumentSurroundingBiomes = loadBiomes("ocean_monument_surrounding");
        }
        if (surface != null && oceanFloor != null) {
            ruinedPortalFlavors.add(new RPFlavor("ocean", List.of(
                    new RPSetup(RPPlacement.ON_OCEAN_FLOOR, 0.0F, 0.8F, false, false, true, false, 1.0F)),
                    loadBiomes("ruined_portal_ocean")));
            ruinedPortalFlavors.add(new RPFlavor("swamp", List.of(
                    new RPSetup(RPPlacement.ON_OCEAN_FLOOR, 0.0F, 0.5F, false, true, false, false, 1.0F)),
                    loadBiomes("ruined_portal_swamp")));
        }
    }

    /** trial-chamber pool aliases: blocks-correct (spawner geometry identical; only mob nbt differs). */
    private static final Map<String, String> TRIAL_ALIASES = Map.of(
            "minecraft:trial_chambers/spawner/contents/ranged", "minecraft:trial_chambers/spawner/ranged/skeleton",
            "minecraft:trial_chambers/spawner/contents/slow_ranged", "minecraft:trial_chambers/spawner/slow_ranged/skeleton",
            "minecraft:trial_chambers/spawner/contents/melee", "minecraft:trial_chambers/spawner/melee/zombie",
            "minecraft:trial_chambers/spawner/contents/small_melee", "minecraft:trial_chambers/spawner/small_melee/slime");

    // ------------------------------------------------------------------ per-chunk placement

    /** Place all structure blocks that fall inside chunk (chunkX,chunkZ) into the canvas. */
    public void placeInChunk(int chunkX, int chunkZ, VStructureGen.Canvas canvas) {
        int minX = chunkX << 4, minZ = chunkZ << 4, maxX = minX + 15, maxZ = minZ + 15;
        for (Type type : types) {
            int r = type.radiusChunks;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int ccx = chunkX + dx, ccz = chunkZ + dz;
                    if (!placement.isStructureChunk(type.setName, ccx, ccz)) continue;
                    VJigsaw.Assembly start = startFor(type, ccx, ccz);
                    if (start == null) continue;
                    for (VJigsaw.Piece piece : start.pieces) {
                        int[] bb = piece.bb;
                        if (bb[3] < minX || bb[0] > maxX || bb[5] < minZ || bb[2] > maxZ) continue; // no XZ overlap
                        VStructureGen.placePieceClipped(piece, minX, minZ, maxX, maxZ, canvas);
                    }
                }
            }
        }
        if (surface != null) placeRuinedPortals(chunkX, chunkZ, minX, minZ, maxX, maxZ, canvas);
        if (surface != null) placeMineshafts(chunkX, chunkZ, minX, minZ, maxX, maxZ, canvas);
        if (surface != null) placeIgloos(chunkX, chunkZ, minX, minZ, maxX, maxZ, canvas);
        if (surface != null) placeSwampHuts(chunkX, chunkZ, minX, minZ, maxX, maxZ, canvas);
        if (surface != null) placeJungleTemples(chunkX, chunkZ, minX, minZ, maxX, maxZ, canvas);
        if (surface != null) placeDesertPyramids(chunkX, chunkZ, minX, minZ, maxX, maxZ, canvas);
        if (surface != null) placeShipwrecks(chunkX, chunkZ, minX, minZ, maxX, maxZ, canvas);
        if (oceanFloor != null) placeBuriedTreasures(chunkX, chunkZ, minX, minZ, maxX, maxZ, canvas);
        if (oceanFloor != null) placeOceanRuins(chunkX, chunkZ, minX, minZ, maxX, maxZ, canvas);
        if (oceanFloor != null) placeOceanMonuments(chunkX, chunkZ, minX, minZ, maxX, maxZ, canvas);
        if (surface != null) placeWoodlandMansions(chunkX, chunkZ, minX, minZ, maxX, maxZ, canvas);
    }

    /**
     * Diagnostic test hook for any generic jigsaw {@link Type} (village/pillager_outpost/
     * trail_ruins/ancient_city/trial_chambers): {@code {isStructureChunk, assemblySucceeded,
     * pieceCount}} for the given set name at the given chunk (no radius scan — checks that exact
     * chunk only, matching how real vanilla's own placement grid anchors one candidate per
     * region).
     */
    public int[] testTypeAt(String setName, int chunkX, int chunkZ) {
        for (Type type : types) {
            if (!type.setName.equals(setName)) continue;
            boolean isChunk = placement.isStructureChunk(setName, chunkX, chunkZ);
            if (!isChunk) return new int[]{0, 0, 0, 0};
            VJigsaw.Assembly rawAssembly = jigsaw.assembleFull(type.def, chunkX, chunkZ, type.aliases);
            if (rawAssembly == null) return new int[]{1, 0, 0, 0};
            boolean biomeOk = biomeOk(type, rawAssembly);
            VJigsaw.Assembly start = startFor(type, chunkX, chunkZ);
            return new int[]{1, 1, biomeOk ? 1 : 0, start == null ? 0 : start.pieces.size()};
        }
        return null;
    }

    /** Diagnostic: the real biome sampled at a jigsaw assembly's center, for testTypeAt debugging. */
    public String testTypeBiomeAt(String setName, int chunkX, int chunkZ) {
        for (Type type : types) {
            if (!type.setName.equals(setName)) continue;
            VJigsaw.Assembly a = jigsaw.assembleFull(type.def, chunkX, chunkZ, type.aliases);
            if (a == null) return null;
            return biomes.biomeAt(a.centerX >> 2, a.centerY >> 2, a.centerZ >> 2) + " (center=" + a.centerX + "," + a.centerY + "," + a.centerZ + ")";
        }
        return null;
    }

    // ------------------------------------------------------------------ ruined portal

    /**
     * Single NBT-template structure (not jigsaw), decompiled from RuinedPortalStructure /
     * RuinedPortalPiece. All 7 real biome-flavored setups sharing this placement grid are
     * wired here (standard/desert/jungle/mountain/ocean/swamp); the 7th, nether, is
     * implemented separately in {@code NetherGen} (different dimension/generator). The
     * corner-scan solid-ground Y refinement (`findSuitableY`'s post-search downward scan for
     * 3-of-4 solid corners) IS ported ({@link #refineRuinedPortalY}), gated on a raw per-point
     * terrain-substance accessor (`substanceAt`) that's null for the SelfTest-only constructor
     * overloads — approximated as "substance == STONE" rather than real per-blockstate opacity
     * (this project's raw density query has no carving/surface-rule granularity). Mirror IS
     * applied ({@code Mirror.FRONT_BACK}, the only value real vanilla ever draws here — X
     * negation before rotation, see {@link VTemplate#transformMirrored}, {@link
     * #pivotBoundingBoxMirrored}, {@link #mirrorFrontBack}) — both position and blockstate
     * (facing/multiface-booleans/rotation-property, NOT stairs "shape", a documented scope
     * cut). Multiple flavors sharing one placement grid is a
     * further known approximation: real vanilla's `StructureSet` picks exactly ONE of the 7
     * flavors per candidate chunk via its own weighted draw before that flavor's own biome
     * check ever runs; this project instead lets every wired flavor independently attempt
     * every candidate chunk and relies on each flavor's biome list being (by design)
     * essentially non-overlapping with the others to avoid double-placing — acceptable given
     * no two currently-wired flavors' biome lists intersect.
     * <p>
     * Decoration is ported: the RuleProcessor trio (gold_block->air 30%, lava->magma/
     * netherrack per placement+cold, netherrack->magma 7% non-cold), BlockAgeProcessor
     * ("mossiness" erosion look), and BlackstoneReplaceProcessor (nether flavor) all run per
     * placed block, each with its own fresh position-seeded RNG matching real vanilla's
     * per-processor {@code settings.getRandom(pos)}/{@code RandomSource.create(Mth.getSeed
     * (pos))} semantics (independent streams, no cross-processor RNG sharing — confirmed from
     * decompile). airPocket is applied (governs whether template AIR blocks are placed or
     * skipped) and "cold" is resolved via the same `coldEnoughToSnow` check used elsewhere in
     * this project (`VSurface`), gated on `canBeCold` per Setup. ProtectedBlockProcessor
     * (skip placement where existing terrain is bedrock/spawner/chest/end_portal_frame/
     * reinforced_deepslate/trial_spawner/vault — real `BlockTags.FEATURES_CANNOT_REPLACE`, a
     * flat 7-entry tag) is ported for the overworld flavors via {@code canvas.get} (available
     * here, unlike {@code NetherGen}'s write-only {@code UnitModifier} — the nether flavor
     * still skips this check for that reason, matching the same documented Canvas-read
     * limitation already noted for bastion). LavaSubmergedBlockProcessor is also ported (see
     * {@link #isRPNonFull}: real per-blockstate collision-shape data isn't modeled, but the
     * "is this shape non-full" test collapses to a simple name check since stairs/slabs/walls/
     * iron_bars are the only non-full outputs this structure's material palette can ever
     * produce) — a position that was LAVA before this structure placed anything stays LAVA if
     * the decorated output there is non-full, same overworld-only Canvas-read caveat as
     * ProtectedBlockProcessor. postProcess's spreadNetherrack/addNetherrackDripColumns
     * (environmental blending into surrounding terrain) and the vines/overgrown-leaves
     * bounding-box scan are both ported too (see {@link #spreadRuinedPortalNetherrack} and
     * {@link #addRuinedPortalVinesAndLeaves}) — real vanilla's per-shape collision-face test
     * for vine attachment is approximated as a name/property check over the closed material
     * palette this structure can ever produce (see {@link #isRPFaceFullHorizontal}), since
     * this project doesn't model generic per-blockstate collision shapes. All overworld-flavor
     * gaps are now Canvas-read-limitation-only (nether flavor still lacks every terrain-reading
     * feature — Protected/LavaSubmerged/spreadNetherrack/vines — since {@code NetherGen}'s
     * {@code UnitModifier} is write-only). BlockAgeProcessor's stairs-facing/half orientation
     * when moss-replacing a full stone block is drawn (RNG stream position preserved) but not
     * applied to the output state — a cosmetic-only deviation.
     */
    private record RPPiece(VTemplate template, int baseX, int originY, int baseZ,
                            VTemplate.Rot rotation, int pivotX, int pivotZ, int[] bb,
                            RPPlacement placement, boolean airPocket, float mossiness, boolean cold,
                            boolean replaceWithBlackstone, boolean overgrown, boolean vines, boolean mirrored) {}

    private enum RPPlacement { ON_LAND_SURFACE, UNDERGROUND, PARTLY_BURIED, IN_MOUNTAIN, ON_OCEAN_FLOOR }

    /** One real vanilla Setup entry (RuinedPortalStructure.Setup). */
    private record RPSetup(RPPlacement placement, float airPocketProbability, float mossiness,
                            boolean overgrown, boolean vines, boolean canBeCold, boolean replaceWithBlackstone, float weight) {}

    private record RPFlavor(String name, List<RPSetup> setups, Set<String> biomes) {}

    private static final String RUINED_PORTAL_SET = "minecraft:ruined_portals";
    // 2 covered the largest template's own footprint; spreadNetherrack now reaches up to ~14
    // blocks beyond the piece's bounding box, so bumped for safety margin (matches the same
    // reasoning applied to OCEAN_RUIN_RADIUS's cluster-generation bump).
    private static final int RUINED_PORTAL_RADIUS = 3;
    private static final int NETHERRACK_SPREAD_MAX_DISTANCE = 14;
    private static final float[] NETHERRACK_SPREAD_PROBABILITY = {
            1f, 1f, 1f, 1f, 1f, 1f, 1f, 0.9f, 0.9f, 0.8f, 0.7f, 0.6f, 0.4f, 0.2f};
    private static final String[] PORTAL_TEMPLATES = {
            "minecraft:ruined_portal/portal_1", "minecraft:ruined_portal/portal_2", "minecraft:ruined_portal/portal_3",
            "minecraft:ruined_portal/portal_4", "minecraft:ruined_portal/portal_5", "minecraft:ruined_portal/portal_6",
            "minecraft:ruined_portal/portal_7", "minecraft:ruined_portal/portal_8", "minecraft:ruined_portal/portal_9",
            "minecraft:ruined_portal/portal_10"};
    private static final String[] GIANT_PORTAL_TEMPLATES = {
            "minecraft:ruined_portal/giant_portal_1", "minecraft:ruined_portal/giant_portal_2", "minecraft:ruined_portal/giant_portal_3"};

    private void placeRuinedPortals(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int r = RUINED_PORTAL_RADIUS;
        for (RPFlavor flavor : ruinedPortalFlavors) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int ccx = chunkX + dx, ccz = chunkZ + dz;
                    if (!placement.isStructureChunk(RUINED_PORTAL_SET, ccx, ccz)) continue;
                    String key = flavor.name() + ':' + ccx + ':' + ccz;
                    RPPiece piece = ruinedPortalStarts.containsKey(key) ? ruinedPortalStarts.get(key) : assembleRuinedPortal(flavor, ccx, ccz);
                    ruinedPortalStarts.put(key, piece);
                    if (piece == null) continue;
                    int[] bb = piece.bb;
                    int pad = NETHERRACK_SPREAD_MAX_DISTANCE; // spreadNetherrack reaches beyond the piece's own bbox
                    if (bb[3] + pad < minX || bb[0] - pad > maxX || bb[5] + pad < minZ || bb[2] - pad > maxZ) continue;
                    for (VTemplate.BlockInfo b : piece.template.blocks) {
                        int[] wp = VTemplate.transformMirrored(b.x, b.y, b.z, piece.mirrored, piece.rotation, piece.pivotX, piece.pivotZ);
                        int wx = wp[0] + piece.baseX, wy = wp[1] + piece.originY, wz = wp[2] + piece.baseZ;
                        if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                        String name = b.state.key().asString();
                        if (name.equals("minecraft:structure_block") || name.equals("minecraft:structure_void")) continue;
                        if (!piece.airPocket && name.equals("minecraft:air")) continue;
                        Block preExisting = canvasGet(canvas, wx, wy, wz);
                        if (RP_PROTECTED_BLOCKS.contains(preExisting.name())) continue;
                        // BlockState.mirror() is applied BEFORE rotate() in real vanilla — same order here.
                        Block block = piece.mirrored ? mirrorFrontBack(b.state) : b.state;
                        block = VBlockRotate.rotate(block, piece.rotation);
                        block = applyRuinedPortalRuleProcessor(block, piece.placement, piece.cold,
                                new VSurface.LegacyRandom(XRandom.blockSeed(wx, wy, wz)));
                        block = applyRuinedPortalMossiness(block, piece.mossiness,
                                new VSurface.LegacyRandom(XRandom.blockSeed(wx, wy, wz)));
                        if (preExisting.name().equals("minecraft:lava") && isRPNonFull(block.name())) block = Block.LAVA;
                        if (piece.replaceWithBlackstone) block = applyBlackstoneReplace(block);
                        canvas.set(wx, wy, wz, block);
                        registerContainerLoot(name, b.nbt, wx, wy, wz);
                    }
                    spreadRuinedPortalNetherrack(piece, canvas, minX, minZ, maxX, maxZ);
                    if (piece.vines || piece.overgrown) addRuinedPortalVinesAndLeaves(piece, canvas, minX, minZ, maxX, maxZ);
                }
            }
        }
    }

    /**
     * RuinedPortalPiece.spreadNetherrack + addNetherrackDripColumn: blends the portal into its
     * surroundings with a distance-probability netherrack/magma scatter (real vanilla's own
     * hardcoded 14-entry probability-by-Manhattan-distance table) around the piece's bounding-box
     * center, each hit followed by a short downward "drip column" and (if `overgrown`) a chance of
     * jungle leaves above. Real vanilla threads ONE shared `RandomSource` through this whole
     * per-piece postProcess call in a fixed iteration order — this project instead uses
     * independent position-seeded RNG per decision (the same simplification already applied to
     * every other per-block ruined-portal processor this session), which is REQUIRED here (not
     * just a style choice) since this project's per-chunk clipped placement can invoke this same
     * piece's spread pass from multiple different chunk renders — a shared advancing stream would
     * make the result depend on chunk-generation order, which position-seeding avoids by
     * construction. `distanceAdjustment` (real vanilla: one draw per postProcess call) is instead
     * derived once per PIECE via a piece-identity seed, so every chunk render agrees on it.
     */
    private void spreadRuinedPortalNetherrack(RPPiece piece, VStructureGen.Canvas canvas, int minX, int minZ, int maxX, int maxZ) {
        int[] bb = piece.bb;
        int centerX = (bb[0] + bb[3]) / 2, centerZ = (bb[2] + bb[5]) / 2;
        boolean followGroundSurface = piece.placement == RPPlacement.ON_LAND_SURFACE || piece.placement == RPPlacement.ON_OCEAN_FLOOR;
        int xSpan = bb[3] - bb[0] + 1, zSpan = bb[5] - bb[2] + 1;
        int averageWidth = (xSpan + zSpan) / 2;
        VSurface.LegacyRandom pieceRng = new VSurface.LegacyRandom(XRandom.blockSeed(centerX, bb[1], centerZ));
        int distanceAdjustment = pieceRng.nextInt(Math.max(1, 8 - averageWidth / 2));
        int maxDistance = NETHERRACK_SPREAD_MAX_DISTANCE;
        VJigsaw.SurfaceHeight heightSource = piece.placement == RPPlacement.ON_OCEAN_FLOOR ? oceanFloor : surface;

        for (int x = centerX - maxDistance; x <= centerX + maxDistance; x++) {
            if (x < minX - 1 || x > maxX + 1) continue;
            for (int z = centerZ - maxDistance; z <= centerZ + maxDistance; z++) {
                if (z < minZ - 1 || z > maxZ + 1) continue;
                int distance = Math.abs(x - centerX) + Math.abs(z - centerZ);
                int adjustedDistance = Math.max(0, distance + distanceAdjustment);
                if (adjustedDistance >= maxDistance) continue;
                VSurface.LegacyRandom spreadRng = new VSurface.LegacyRandom(XRandom.blockSeed(x, 0, z));
                if (spreadRng.nextDouble() >= NETHERRACK_SPREAD_PROBABILITY[adjustedDistance]) continue;
                int surfaceY = heightSource.firstFreeWg(x, z) - 1;
                int y = followGroundSurface ? surfaceY : Math.min(bb[1], surfaceY);
                if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
                if (Math.abs(y - bb[1]) > 3) continue;
                Block existing = canvasGet(canvas, x, y, z);
                if (!canBeReplacedByNetherrackOrMagma(existing)) continue;
                VSurface.LegacyRandom blockRng = new VSurface.LegacyRandom(XRandom.blockSeed(x, y, z));
                canvas.set(x, y, z, netherrackOrMagma(piece.cold, blockRng));
                if (piece.overgrown) maybeAddRPLeavesAbove(canvas, x, y, z, blockRng);
                addNetherrackDripColumn(canvas, x, y - 1, z, piece.cold);
            }
        }
    }

    private static boolean canBeReplacedByNetherrackOrMagma(Block state) {
        String name = state.name();
        return !name.equals("minecraft:air") && !name.equals("minecraft:obsidian")
                && !RP_PROTECTED_BLOCKS.contains(name) && !name.equals("minecraft:lava");
    }

    private static Block netherrackOrMagma(boolean cold, VSurface.LegacyRandom rng) {
        return !cold && rng.nextFloat() < 0.07F ? Block.MAGMA_BLOCK : Block.NETHERRACK;
    }

    private static void maybeAddRPLeavesAbove(VStructureGen.Canvas canvas, int x, int y, int z, VSurface.LegacyRandom rng) {
        if (rng.nextFloat() < 0.5F && canvasGet(canvas, x, y, z).name().equals("minecraft:netherrack") && canvasGet(canvas, x, y + 1, z).name().equals("minecraft:air")) {
            canvas.set(x, y + 1, z, Block.JUNGLE_LEAVES.withProperty("persistent", "true"));
        }
    }

    /** Each Y-level's own position-seeded RNG handles both its block-type pick and the "continue one more level down" draw, self-contained per position. */
    private void addNetherrackDripColumn(VStructureGen.Canvas canvas, int x, int y, int z, boolean cold) {
        int curY = y;
        int remainingCap = 8;
        while (true) {
            VSurface.LegacyRandom rng = new VSurface.LegacyRandom(XRandom.blockSeed(x, curY, z));
            canvas.set(x, curY, z, netherrackOrMagma(cold, rng));
            if (remainingCap <= 0 || rng.nextFloat() >= 0.5F) break;
            remainingCap--;
            curY--;
        }
    }

    private static final String[] RP_HORIZONTAL_DIRS = {"north", "east", "south", "west"};

    /**
     * RuinedPortalPiece's `vines`/`overgrown` postProcess loop: iterates the WHOLE piece
     * bounding box (real vanilla: `BlockPos.betweenClosedStream(getBoundingBox())`, not just
     * placed template blocks — a template-air position skipped by `airPocket=false` could hold
     * pre-existing terrain that's still a valid vine/leaves candidate), calling `maybeAddVines`
     * and/or `maybeAddLeavesAbove` per position depending on which flags are set. Runs AFTER
     * {@link #spreadRuinedPortalNetherrack} (matches real postProcess call order), so this is
     * also a SECOND, independent leaves check beyond the one already fired inline during the
     * spread pass — real vanilla genuinely does check leaves twice (once per freshly-spread
     * netherrack block during spreadNetherrack itself, once again here for every bbox position),
     * reimplemented faithfully rather than deduplicated.
     */
    private void addRuinedPortalVinesAndLeaves(RPPiece piece, VStructureGen.Canvas canvas, int minX, int minZ, int maxX, int maxZ) {
        int[] bb = piece.bb;
        for (int x = Math.max(bb[0], minX); x <= Math.min(bb[3], maxX); x++) {
            for (int z = Math.max(bb[2], minZ); z <= Math.min(bb[5], maxZ); z++) {
                for (int y = bb[1]; y <= bb[4]; y++) {
                    if (piece.vines) {
                        VSurface.LegacyRandom rng = new VSurface.LegacyRandom(XRandom.blockSeed(x, y, z));
                        maybeAddRPVine(canvas, x, y, z, rng);
                    }
                    if (piece.overgrown) {
                        VSurface.LegacyRandom rng = new VSurface.LegacyRandom(XRandom.blockSeed(x, y, z) ^ 0x5EAF5EAFL);
                        maybeAddRPLeavesAbove(canvas, x, y, z, rng);
                    }
                }
            }
        }
    }

    private static void maybeAddRPVine(VStructureGen.Canvas canvas, int x, int y, int z, VSurface.LegacyRandom rng) {
        Block state = canvasGet(canvas, x, y, z);
        String name = state.name();
        if (name.equals("minecraft:air") || name.equals("minecraft:vine")) return;
        String dir = RP_HORIZONTAL_DIRS[rng.nextInt(4)];
        int[] off = rpDirOffset(dir);
        int nx = x + off[0], nz = z + off[1];
        if (!canvasGet(canvas, nx, y, nz).name().equals("minecraft:air")) return;
        if (!isRPFaceFullHorizontal(state, dir)) return;
        String attachFace = rpOpposite(dir);
        canvas.set(nx, y, nz, Block.VINE.withProperty(attachFace, "true"));
    }

    private static int[] rpDirOffset(String dir) {
        return switch (dir) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "east" -> new int[]{1, 0};
            default -> new int[]{-1, 0}; // west
        };
    }

    private static String rpOpposite(String dir) {
        return switch (dir) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east"; // west
        };
    }

    /**
     * Block.isFaceFull(state.getCollisionShape(), direction) reimplemented as a name/property
     * check over ruined portal's closed material palette (this project doesn't model generic
     * per-blockstate collision shapes) rather than real per-shape geometry: full-cube materials
     * (stone/cobblestone/blackstone/netherrack/obsidian/magma/gold_block families, and any
     * material not otherwise recognized) are face-full on every horizontal side; slabs are NEVER
     * horizontally face-full (their footprint is full XZ but only half-height, so a horizontal
     * cross-section is never a complete square); stairs are face-full on every side except the
     * one their `facing` property opens toward (the solid "back"/side faces, not the open step
     * face); walls are face-full only on sides with a non-`none` connection property.
     */
    /**
     * {@code Canvas.get} legitimately returns {@code null} for a position that's air/unset
     * (both {@code VanillaGen}'s real Canvas implementations store air as a null array/map
     * entry rather than an explicit {@code Block.AIR} reference, as a memory optimization) —
     * every decoration read in this file must go through this normalizer rather than calling
     * {@code canvas.get} directly, or a genuinely-air position throws an NPE the moment
     * anything calls {@code .name()}/{@code .properties()} on the result (a real bug caught
     * this session: the very first `canvas.get()` call added for mineshaft's floor-plank
     * decoration crashed SelfTest outright on an air position under a corridor).
     */
    private static Block canvasGet(VStructureGen.Canvas canvas, int x, int y, int z) {
        Block b = canvas.get(x, y, z);
        return b != null ? b : Block.AIR;
    }

    /** Container-block LootTable NBT (RandomizableContainer), rolled on first open — shared by
     *  every structure placement path in this package (both this class's hand-placed structure
     *  types and VStructureGen's jigsaw/template path). */
    static void registerContainerLoot(String name, net.kyori.adventure.nbt.CompoundBinaryTag nbt,
                                       int wx, int wy, int wz) {
        if (nbt == null || !nbt.keySet().contains("LootTable")) return;
        if (!(name.equals("minecraft:chest") || name.equals("minecraft:trapped_chest")
                || name.equals("minecraft:barrel") || name.equals("minecraft:dispenser")
                || name.equals("minecraft:dropper") || name.equals("minecraft:hopper"))) return;
        dev.pointofpressure.minecom.blocks.Containers.registerLoot(
                new net.minestom.server.coordinate.Vec(wx, wy, wz), nbt.getString("LootTable"));
    }

    /**
     * BlockState.mirror(Mirror.FRONT_BACK): real vanilla's {@code Direction.mirror} flips only
     * EAST&lt;-&gt;WEST (X-axis directions), leaving NORTH/SOUTH untouched — reimplemented for
     * this project's property model: "facing" east/west swap, the "east"/"west" multiface
     * connection booleans swap, and the 0-15 "rotation" property (signs/banners) maps via
     * {@code (16-r)%16} (real {@code Mirror.mirror(rotation,16)} for FRONT_BACK, verified
     * algebraically equivalent to the decompiled two-branch formula). Deliberately NOT handled:
     * stairs "shape" (inner_left&lt;-&gt;inner_right / outer_left&lt;-&gt;outer_right) — mirroring
     * genuinely flips handedness in a way plain rotation doesn't, but this project's ruined-
     * portal material palette essentially never contains shaped stairs corners in practice, and
     * this is a real, deliberate scope cut rather than an oversight.
     */
    public static Block mirrorFrontBack(Block block) {
        Map<String, String> props = block.properties();
        if (props.isEmpty()) return block;
        Map<String, String> out = null;

        String facing = props.get("facing");
        if (facing != null && (facing.equals("east") || facing.equals("west"))) {
            out = new HashMap<>(props);
            out.put("facing", facing.equals("east") ? "west" : "east");
        }

        String rotation = props.get("rotation");
        if (rotation != null) {
            int v = Integer.parseInt(rotation);
            int nv = (16 - v) % 16;
            if (nv != v) { if (out == null) out = new HashMap<>(props); out.put("rotation", Integer.toString(nv)); }
        }

        if (props.containsKey("east") || props.containsKey("west")) {
            if (out == null) out = new HashMap<>(props);
            String e = props.get("east"), w = props.get("west");
            if (e != null) out.put("west", e); else out.remove("west");
            if (w != null) out.put("east", w); else out.remove("east");
        }

        return out == null ? block : block.withProperties(out);
    }

    private static boolean isRPFaceFullHorizontal(Block block, String direction) {
        String name = block.name();
        if (name.endsWith("_slab")) return false;
        if (name.endsWith("_stairs")) {
            String facing = block.properties().get("facing");
            return facing == null || !facing.equals(direction);
        }
        if (name.endsWith("_wall")) {
            String v = block.properties().get(direction);
            return v != null && !v.equals("none") && !v.equals("false");
        }
        if (name.equals("minecraft:iron_bars") || name.equals("minecraft:iron_chain")) return false;
        return true;
    }

    /** Test hook for the "standard" flavor (see the 2-arg overload for other flavors). */
    public int[] testRuinedPortalAt(int chunkX, int chunkZ) {
        return testRuinedPortalAt("standard", chunkX, chunkZ);
    }

    /**
     * Test hook: {rotationOrdinal, template sizeX, sizeY, sizeZ, blockCount, originY} for
     * the named ruined-portal flavor ("standard" or "desert") at the given chunk, or null
     * if nothing generates there (not a candidate chunk, unknown flavor, or missing/wrong
     * biome — see RPPiece javadoc for what's out of scope).
     */
    public int[] testRuinedPortalAt(String flavorName, int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(RUINED_PORTAL_SET, chunkX, chunkZ)) return null;
        RPFlavor flavor = null;
        for (RPFlavor f : ruinedPortalFlavors) if (f.name().equals(flavorName)) { flavor = f; break; }
        if (flavor == null) return null;
        RPPiece p = assembleRuinedPortal(flavor, chunkX, chunkZ);
        if (p == null) return null;
        return new int[]{p.rotation.ordinal(), p.template.sizeX, p.template.sizeY, p.template.sizeZ,
                p.template.blocks.size(), p.originY};
    }

    /**
     * Test hook: {placedBlockCount, goldBlockCount(pre-rule), magmaBlockCount, mossyStoneBricksCount,
     * crackedStoneBricksCount, cryingObsidianCount, blackstoneFamilyCount} after applying the full
     * decoration pipeline (RuleProcessor -> BlockAgeProcessor -> BlackstoneReplace, airPocket filter),
     * or null if nothing generates there. Runs the exact same per-block logic as placeRuinedPortals.
     */
    public int[] testRuinedPortalDecoratedAt(String flavorName, int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(RUINED_PORTAL_SET, chunkX, chunkZ)) return null;
        RPFlavor flavor = null;
        for (RPFlavor f : ruinedPortalFlavors) if (f.name().equals(flavorName)) { flavor = f; break; }
        if (flavor == null) return null;
        RPPiece p = assembleRuinedPortal(flavor, chunkX, chunkZ);
        if (p == null) return null;
        int total = 0, gold = 0, magma = 0, mossy = 0, cracked = 0, cryingObsidian = 0, blackstoneFamily = 0;
        for (VTemplate.BlockInfo b : p.template.blocks) {
            int[] wp = VTemplate.transform(b.x, b.y, b.z, p.rotation, p.pivotX, p.pivotZ);
            int wx = wp[0] + p.baseX, wy = wp[1] + p.originY, wz = wp[2] + p.baseZ;
            String name = b.state.key().asString();
            if (name.equals("minecraft:structure_block") || name.equals("minecraft:structure_void")) continue;
            if (!p.airPocket && name.equals("minecraft:air")) continue;
            if (name.equals("minecraft:gold_block")) gold++;
            Block block = VBlockRotate.rotate(b.state, p.rotation);
            block = applyRuinedPortalRuleProcessor(block, p.placement, p.cold, new VSurface.LegacyRandom(XRandom.blockSeed(wx, wy, wz)));
            block = applyRuinedPortalMossiness(block, p.mossiness, new VSurface.LegacyRandom(XRandom.blockSeed(wx, wy, wz)));
            if (p.replaceWithBlackstone) block = applyBlackstoneReplace(block);
            total++;
            String outName = block.name();
            if (outName.equals("minecraft:magma_block")) magma++;
            else if (outName.equals("minecraft:mossy_stone_bricks")) mossy++;
            else if (outName.equals("minecraft:cracked_stone_bricks")) cracked++;
            else if (outName.equals("minecraft:crying_obsidian")) cryingObsidian++;
            else if (outName.contains("blackstone") || outName.equals("minecraft:iron_chain")) blackstoneFamily++;
        }
        return new int[]{total, gold, magma, mossy, cracked, cryingObsidian, blackstoneFamily};
    }

    private static final int MIN_Y = -64; // overworld world floor (RuinedPortalStructure: heightAccessor.getMinY())

    /** RuinedPortalStructure.findSuitableY's trailing corner-scan (see call site javadoc). */
    private int refineRuinedPortalY(int[] bb0, int startY) {
        int[][] corners = {{bb0[0], bb0[2]}, {bb0[3], bb0[2]}, {bb0[0], bb0[5]}, {bb0[3], bb0[5]}};
        int minY = MIN_Y + 15;
        int projectedY = startY;
        for (; projectedY > minY; projectedY--) {
            int solidCount = 0;
            for (int[] c : corners) {
                if (substanceAt.at(c[0], projectedY, c[1]) == VAquifer.STONE && ++solidCount == 3) return projectedY;
            }
        }
        return projectedY;
    }

    private RPPiece assembleRuinedPortal(RPFlavor flavor, int ccx, int ccz) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, ccx, ccz);

        RPSetup setup;
        List<RPSetup> setups = flavor.setups();
        if (setups.size() > 1) {
            float total = 0F;
            for (RPSetup s : setups) total += s.weight();
            float pick = random.nextFloat();
            RPSetup chosen = null;
            for (RPSetup s : setups) {
                pick -= s.weight() / total;
                if (pick < 0F) { chosen = s; break; }
            }
            setup = chosen != null ? chosen : setups.get(setups.size() - 1);
        } else {
            setup = setups.get(0); // real vanilla: single-Setup lists skip the weighted draw entirely
        }

        boolean airPocket = sampleAirPocket(random, setup.airPocketProbability());

        boolean giant = random.nextFloat() < 0.05F;
        String[] pool = giant ? GIANT_PORTAL_TEMPLATES : PORTAL_TEMPLATES;
        String templateLoc = pool[random.nextInt(pool.length)];
        VTemplate template = VTemplate.load(templateLoc);
        if (template.sizeX == 0) return null; // missing template guard (VTemplate.EMPTY)

        VTemplate.Rot rotation = VTemplate.Rot.VALUES[random.nextInt(4)];
        // RuinedPortalStructure: `random.nextFloat() < 0.5F ? Mirror.NONE : Mirror.FRONT_BACK`
        // (never LEFT_RIGHT) — FRONT_BACK negates the raw X coordinate, applied before rotation.
        boolean mirrored = random.nextFloat() >= 0.5F;

        int pivotX = template.sizeX / 2, pivotZ = template.sizeZ / 2;
        int baseX = ccx << 4, baseZ = ccz << 4;
        int[] bb0 = pivotBoundingBoxMirrored(template, baseX, 0, baseZ, mirrored, rotation, pivotX, pivotZ);
        int centerX = (bb0[0] + bb0[3]) / 2, centerZ = (bb0[2] + bb0[5]) / 2;
        int ySpan = bb0[4] - bb0[1] + 1;

        // RuinedPortalPiece.getHeightMapType: ON_OCEAN_FLOOR sources surfaceYAtCenter from
        // OCEAN_FLOOR_WG, every other placement from WORLD_SURFACE_WG.
        int surfaceYAtCenter = (setup.placement() == RPPlacement.ON_OCEAN_FLOOR ? oceanFloor : surface).firstFreeWg(centerX, centerZ) - 1;
        int originY = switch (setup.placement()) {
            case ON_LAND_SURFACE, ON_OCEAN_FLOOR -> surfaceYAtCenter; // sits directly on the heightmap surface
            case UNDERGROUND -> {
                // findSuitableY(UNDERGROUND): getRandomWithinInterval(random, minY, surfaceYAtCenter - ySpan).
                int minY = MIN_Y + 15;
                int maxY = surfaceYAtCenter - ySpan;
                yield minY < maxY ? minY + random.nextInt(maxY - minY + 1) : maxY;
            }
            case PARTLY_BURIED ->
                // findSuitableY(PARTLY_BURIED): surfaceYAtCenter - ySpan + randomBetweenInclusive(2, 8).
                surfaceYAtCenter - ySpan + (2 + random.nextInt(7));
            case IN_MOUNTAIN -> {
                // findSuitableY(IN_MOUNTAIN): getRandomWithinInterval(random, 70, surfaceYAtCenter - ySpan).
                int maxY = surfaceYAtCenter - ySpan;
                yield 70 < maxY ? 70 + random.nextInt(maxY - 70 + 1) : maxY;
            }
        };

        // findSuitableY's trailing corner-scan: refine the candidate Y downward until 3-of-4
        // bottom corners sit on solid ground (or bottom out at minY). Real vanilla scans 4
        // full BlockState columns from a chunk-generation NoiseColumn; this project only has a
        // raw density-substance query available (no carving/surface-rule granularity), so
        // "opaque" is approximated as "substance == STONE" — sound for the purpose (avoiding a
        // portal floating over a cliff edge or clipping into a slope), not exact for edge cases
        // like carved caves intersecting a corner exactly at the scanned Y.
        if (substanceAt != null) {
            originY = refineRuinedPortalY(bb0, originY);
        }

        String biome = biomes.biomeAt(centerX >> 2, originY >> 2, centerZ >> 2);
        if (!flavor.biomes().contains(biome)) return null;

        // RuinedPortalStructure: cold is resolved (Biome.coldEnoughToSnow at origin) only when
        // canBeCold is set; the biome/position reused here is the same approximation the
        // structure's own biome gate already uses (see class javadoc).
        boolean cold = setup.canBeCold() && surfaceRef != null && surfaceRef.coldEnoughToSnow(biome, centerX, originY, centerZ);

        int[] bb = {bb0[0], originY + bb0[1], bb0[2], bb0[3], originY + bb0[4], bb0[5]};
        return new RPPiece(template, baseX, originY, baseZ, rotation, pivotX, pivotZ, bb,
                setup.placement(), airPocket, setup.mossiness(), cold, setup.replaceWithBlackstone(), setup.overgrown(), setup.vines(), mirrored);
    }

    // ------------------------------------------------------------------ ruined portal decoration

    private static Block applyRuinedPortalRuleProcessor(Block block, RPPlacement placement, boolean cold, VSurface.LegacyRandom rng) {
        String name = block.name();
        if (name.equals("minecraft:gold_block")) {
            return rng.nextFloat() < 0.3F ? Block.AIR : block;
        }
        if (name.equals("minecraft:lava")) {
            if (placement == RPPlacement.ON_OCEAN_FLOOR) return Block.MAGMA_BLOCK;
            if (cold) return Block.NETHERRACK;
            return rng.nextFloat() < 0.2F ? Block.MAGMA_BLOCK : block;
        }
        if (!cold && name.equals("minecraft:netherrack")) {
            return rng.nextFloat() < 0.07F ? Block.MAGMA_BLOCK : block;
        }
        return block;
    }

    /** ProtectedBlockProcessor(BlockTags.FEATURES_CANNOT_REPLACE): skip placement where existing terrain already holds one of these. Public: also used by NetherGen (different package)'s ruined_portal_nether. */
    public static final Set<String> RP_PROTECTED_BLOCKS = Set.of("minecraft:bedrock", "minecraft:spawner",
            "minecraft:chest", "minecraft:end_portal_frame", "minecraft:reinforced_deepslate",
            "minecraft:trial_spawner", "minecraft:vault");

    private static final Set<String> RP_STAIR_MATERIALS = Set.of("minecraft:cobblestone_stairs",
            "minecraft:mossy_cobblestone_stairs", "minecraft:stone_stairs", "minecraft:stone_brick_stairs");
    private static final Set<String> RP_SLAB_MATERIALS = Set.of("minecraft:cobblestone_slab",
            "minecraft:mossy_cobblestone_slab", "minecraft:smooth_stone_slab", "minecraft:stone_slab", "minecraft:stone_brick_slab");
    private static final Set<String> RP_WALL_MATERIALS = Set.of("minecraft:stone_brick_wall", "minecraft:cobblestone_wall");

    /**
     * BlockAgeProcessor("mossiness"): the "ruined" erosion look. Each branch's RNG draw count
     * matches the decompile exactly (including the full-stone-block branch's two "wasted"
     * facing/half draws used to eagerly build both candidate stairs before the mossy/non-mossy
     * pick) so the final block-type decision lands on the same stream position as real vanilla;
     * the drawn facing/half values themselves are not applied to the output state (cosmetic only).
     */
    public static Block applyRuinedPortalMossiness(Block block, float mossiness, VSurface.LegacyRandom rng) {
        String name = block.name();
        if (name.equals("minecraft:stone_bricks") || name.equals("minecraft:stone") || name.equals("minecraft:chiseled_stone_bricks")) {
            if (rng.nextFloat() >= 0.5F) return block;
            rng.nextInt(4); rng.nextInt(2); // STONE_BRICK_STAIRS facing/half (built eagerly, unused)
            rng.nextInt(4); rng.nextInt(2); // MOSSY_STONE_BRICK_STAIRS facing/half (built eagerly, unused)
            boolean useMossy = rng.nextFloat() < mossiness;
            int idx = rng.nextInt(2);
            if (useMossy) return idx == 0 ? Block.MOSSY_STONE_BRICKS : Block.MOSSY_STONE_BRICK_STAIRS;
            return idx == 0 ? Block.CRACKED_STONE_BRICKS : Block.STONE_BRICK_STAIRS;
        }
        if (RP_STAIR_MATERIALS.contains(name)) {
            if (rng.nextFloat() >= 0.5F) return block;
            boolean useMossy = rng.nextFloat() < mossiness;
            int idx = rng.nextInt(2);
            if (useMossy) return idx == 0 ? withRPProps(Block.MOSSY_STONE_BRICK_STAIRS, block) : Block.MOSSY_STONE_BRICK_SLAB;
            return idx == 0 ? Block.STONE_SLAB : Block.STONE_BRICK_SLAB;
        }
        if (RP_SLAB_MATERIALS.contains(name)) {
            return rng.nextFloat() < mossiness ? withRPProps(Block.MOSSY_STONE_BRICK_SLAB, block) : block;
        }
        if (RP_WALL_MATERIALS.contains(name)) {
            return rng.nextFloat() < mossiness ? withRPProps(Block.MOSSY_STONE_BRICK_WALL, block) : block;
        }
        if (name.equals("minecraft:obsidian")) {
            return rng.nextFloat() < 0.15F ? Block.CRYING_OBSIDIAN : block;
        }
        return block;
    }

    private static Block withRPProps(Block target, Block source) {
        return target.withProperties(source.properties());
    }

    /**
     * LavaSubmergedBlockProcessor: {@code !Block.isShapeFullBlock(state.getShape())} reimplemented
     * as a name-pattern test rather than real per-blockstate collision-shape data (which this
     * project doesn't model) — sound here because ruined portal's entire post-decoration material
     * palette (RuleProcessor/BlockAgeProcessor/BlackstoneReplace outputs included) has exactly one
     * class of non-full shapes: stairs/slabs/walls (any family) and iron_bars/iron_chain. Every
     * other possible output (stone/cobblestone/blackstone family, gold_block, netherrack, magma_
     * block, obsidian/crying_obsidian, air) is a true full cube. Package-visible: also used by
     * NetherGen's ruined_portal_nether.
     */
    public static boolean isRPNonFull(String name) {
        return name.endsWith("_stairs") || name.endsWith("_slab") || name.endsWith("_wall")
                || name.equals("minecraft:iron_bars") || name.equals("minecraft:iron_chain");
    }

    private static final Map<String, Block> BLACKSTONE_REPLACEMENTS = Map.ofEntries(
            Map.entry("minecraft:cobblestone", Block.BLACKSTONE),
            Map.entry("minecraft:mossy_cobblestone", Block.BLACKSTONE),
            Map.entry("minecraft:stone", Block.POLISHED_BLACKSTONE),
            Map.entry("minecraft:stone_bricks", Block.POLISHED_BLACKSTONE_BRICKS),
            Map.entry("minecraft:mossy_stone_bricks", Block.POLISHED_BLACKSTONE_BRICKS),
            Map.entry("minecraft:cobblestone_stairs", Block.BLACKSTONE_STAIRS),
            Map.entry("minecraft:mossy_cobblestone_stairs", Block.BLACKSTONE_STAIRS),
            Map.entry("minecraft:stone_stairs", Block.POLISHED_BLACKSTONE_STAIRS),
            Map.entry("minecraft:stone_brick_stairs", Block.POLISHED_BLACKSTONE_BRICK_STAIRS),
            Map.entry("minecraft:mossy_stone_brick_stairs", Block.POLISHED_BLACKSTONE_BRICK_STAIRS),
            Map.entry("minecraft:cobblestone_slab", Block.BLACKSTONE_SLAB),
            Map.entry("minecraft:mossy_cobblestone_slab", Block.BLACKSTONE_SLAB),
            Map.entry("minecraft:smooth_stone_slab", Block.POLISHED_BLACKSTONE_SLAB),
            Map.entry("minecraft:stone_slab", Block.POLISHED_BLACKSTONE_SLAB),
            Map.entry("minecraft:stone_brick_slab", Block.POLISHED_BLACKSTONE_BRICK_SLAB),
            Map.entry("minecraft:mossy_stone_brick_slab", Block.POLISHED_BLACKSTONE_BRICK_SLAB),
            Map.entry("minecraft:stone_brick_wall", Block.POLISHED_BLACKSTONE_BRICK_WALL),
            Map.entry("minecraft:mossy_stone_brick_wall", Block.POLISHED_BLACKSTONE_BRICK_WALL),
            Map.entry("minecraft:cobblestone_wall", Block.BLACKSTONE_WALL),
            Map.entry("minecraft:mossy_cobblestone_wall", Block.BLACKSTONE_WALL),
            Map.entry("minecraft:chiseled_stone_bricks", Block.CHISELED_POLISHED_BLACKSTONE),
            Map.entry("minecraft:cracked_stone_bricks", Block.CRACKED_POLISHED_BLACKSTONE_BRICKS),
            Map.entry("minecraft:iron_bars", Block.IRON_CHAIN));

    public static Block applyBlackstoneReplace(Block block) {
        Block target = BLACKSTONE_REPLACEMENTS.get(block.name());
        if (target == null) return block;
        Map<String, String> src = block.properties();
        Map<String, String> props = new HashMap<>();
        if (src.containsKey("facing")) props.put("facing", src.get("facing"));
        if (src.containsKey("half")) props.put("half", src.get("half"));
        if (src.containsKey("type")) props.put("type", src.get("type"));
        return props.isEmpty() ? target : target.withProperties(props);
    }

    /** RuinedPortalStructure.sample(random, limit): consumes an RNG draw unless limit is 0 or 1. */
    private static boolean sampleAirPocket(VSurface.LegacyRandom random, float limit) {
        if (limit == 0.0F) return false;
        if (limit == 1.0F) return true;
        return random.nextFloat() < limit;
    }

    /** Template bounding box with a non-zero pivot (VTemplate.boundingBox always assumes pivot 0,0). */
    private static int[] pivotBoundingBox(VTemplate t, int posX, int posY, int posZ, VTemplate.Rot rot, int pivotX, int pivotZ) {
        int[] c1 = VTemplate.transform(0, 0, 0, rot, pivotX, pivotZ);
        int[] c2 = VTemplate.transform(t.sizeX - 1, t.sizeY - 1, t.sizeZ - 1, rot, pivotX, pivotZ);
        int minX = Math.min(c1[0], c2[0]) + posX, minY = Math.min(c1[1], c2[1]) + posY, minZ = Math.min(c1[2], c2[2]) + posZ;
        int maxX = Math.max(c1[0], c2[0]) + posX, maxY = Math.max(c1[1], c2[1]) + posY, maxZ = Math.max(c1[2], c2[2]) + posZ;
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** {@link #pivotBoundingBox} with mirror support (see {@link VTemplate#transformMirrored}) — ruined-portal-only, doesn't touch the shared unmirrored helper. */
    private static int[] pivotBoundingBoxMirrored(VTemplate t, int posX, int posY, int posZ, boolean mirrored, VTemplate.Rot rot, int pivotX, int pivotZ) {
        int[] c1 = VTemplate.transformMirrored(0, 0, 0, mirrored, rot, pivotX, pivotZ);
        int[] c2 = VTemplate.transformMirrored(t.sizeX - 1, t.sizeY - 1, t.sizeZ - 1, mirrored, rot, pivotX, pivotZ);
        int minX = Math.min(c1[0], c2[0]) + posX, minY = Math.min(c1[1], c2[1]) + posY, minZ = Math.min(c1[2], c2[2]) + posZ;
        int maxX = Math.max(c1[0], c2[0]) + posX, maxY = Math.max(c1[1], c2[1]) + posY, maxZ = Math.max(c1[2], c2[2]) + posZ;
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    // ------------------------------------------------------------------ mineshaft

    /**
     * Decompiled from MineshaftStructure/MineshaftPieces. Real vanilla mineshafts are a
     * recursive piece tree (starting {@code MineShaftRoom} -> {@code MineShaftCorridor} ->
     * {@code MineShaftCrossing}/{@code MineShaftRoom}/{@code MineShaftStairs}, each
     * hand-placed block-by-block — no NBT templates, unlike every other structure this
     * project generates) with inter-piece collision detection
     * (`StructurePiecesBuilder.findCollisionPiece`) and a terrain-aware invalid-location
     * gate (`isInInvalidLocation`: rejects a piece whose edges touch liquid, or whose
     * center biome is tagged `minecraft:mineshaft_blocking`). All four piece kinds are
     * implemented: the starting {@code MineShaftRoom} (bit-exact 3-RNG-draw bounding box),
     * {@code MineShaftCorridor} (bit-exact geometry, collision detection against every
     * previously-placed piece, the real end-piece and side-branch RNG-driven fan-out,
     * depth<=8 and distance<=80-from-root limits), {@code MineShaftCrossing} (single
     * collision check with no retry loop, unlike corridor's length-shrinking retry; the
     * real fixed-order NORTH/WEST/EAST/SOUTH two-floored upper-branch gating via
     * `nextBoolean()`), and {@code MineShaftStairs} (no RNG draws at all — a fixed
     * direction-dependent box, single collision check, exactly one recursive child) — with
     * the real vertical placement (`moveBelowSeaLevel` for NORMAL, surface-height-centered
     * for MESA) applied to the WHOLE assembled tree's union bounding box, matching
     * `StructurePiecesBuilder.offsetPiecesVertically` shifting every piece together. Every
     * piece's RNG consumption matches real vanilla exactly (including corridor's
     * `hasRails`/`spiderCorridor` draws, consumed in the real short-circuit order even
     * though the resulting rails/cobwebs are not placed), so the piece tree's SHAPE is
     * bit-exact vanilla in full — the only remaining gap is decoration detail, not
     * structure. Carving is simplified to fully-open (not 80%-probabilistic-ceiling for
     * corridors) tunnels/rooms/crossings/staircases — real vanilla's ceiling openness comes
     * from a SEPARATE decoration-time RNG stream (`postProcess`'s own random, distinct from
     * the piece-tree-building random used here), not from the piece-placement stream this
     * class replicates, so there was nothing bit-exact to preserve there in the first
     * place. `isInInvalidLocation` IS implemented ({@link #msIsInInvalidLocation} — see its
     * own javadoc for the per-chunk-vs-per-piece evaluation caveat). Corridor decoration is
     * PARTIALLY ported (see {@link #placeMineshaftCorridor}): floor planks and cobwebs (both
     * regular per-section and the `spiderCorridor` ceiling strip) are real, using position-
     * seeded RNG in place of real vanilla's single continuously-advancing postProcess stream
     * (same chunk-render-order-independence requirement as ruined portal's spreadNetherrack).
     * `hasRails`/`spiderCorridor` were previously drawn for RNG-stream correctness but their
     * VALUES discarded — while fixing this a genuine bug surfaced: `spiderCorridor`'s draw
     * result was never captured at all (always treated false), now fixed. Still NOT
     * decorated: support beams (fence posts/torches), rails, and chest loot (real vanilla
     * spawns a chest MINECART ENTITY here, not a block — matches the established no-worldgen-
     * entity-spawn precedent) — all deferred as a bounded next increment.
     */
    private enum MSKind { ROOM, CORRIDOR, CROSSING, STAIRS }

    private enum MSDir { NORTH, SOUTH, WEST, EAST }

    /**
     * {@code flag} is CROSSING-only: MineShaftCrossing.isTwoFloored (unused, false, for ROOM/CORRIDOR).
     * {@code hasRails}/{@code spiderCorridor} are CORRIDOR-only (false elsewhere) — previously
     * drawn during piece-tree construction for RNG-stream correctness but discarded; now carried
     * through so postProcess-level decoration (cobwebs) can act on {@code spiderCorridor}.
     */
    private record MSPiece(MSKind kind, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                            MSDir dir, int numSections, int genDepth, boolean flag,
                            boolean hasRails, boolean spiderCorridor) {
        MSPiece shiftY(int dy) {
            return new MSPiece(kind, minX, minY + dy, minZ, maxX, maxY + dy, maxZ, dir, numSections, genDepth, flag, hasRails, spiderCorridor);
        }
    }

    private static final String MINESHAFT_SET = "minecraft:mineshafts";
    private static final int MINESHAFT_RADIUS = 6; // corridors can reach up to 80 blocks from the root room
    private static final int MINESHAFT_START_Y = 50;
    private static final int MINESHAFT_MAX_DEPTH = 8;
    private static final int MINESHAFT_MAX_DIST = 80;

    private final Map<String, List<MSPiece>> mineshaftPieces = new HashMap<>();

    private void placeMineshafts(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int r = MINESHAFT_RADIUS;
        for (String flavor : new String[]{"normal", "mesa"}) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int ccx = chunkX + dx, ccz = chunkZ + dz;
                    if (!placement.isStructureChunk(MINESHAFT_SET, ccx, ccz)) continue;
                    String key = flavor + ':' + ccx + ':' + ccz;
                    List<MSPiece> pieces = mineshaftPieces.containsKey(key) ? mineshaftPieces.get(key) : assembleMineshaft(flavor, ccx, ccz);
                    mineshaftPieces.put(key, pieces);
                    if (pieces == null) continue;
                    boolean mesa = flavor.equals("mesa");
                    for (MSPiece p : pieces) {
                        if (p.maxX() < minX || p.minX() > maxX || p.maxZ() < minZ || p.minZ() > maxZ) continue;
                        if (msIsInInvalidLocation(p, minX, minZ, maxX, maxZ, canvas)) continue;
                        if (p.kind() == MSKind.ROOM) placeMineshaftRoom(p, minX, minZ, maxX, maxZ, canvas);
                        else if (p.kind() == MSKind.CORRIDOR) placeMineshaftCorridor(p, mesa, minX, minZ, maxX, maxZ, canvas);
                        else if (p.kind() == MSKind.CROSSING) placeMineshaftCrossing(p, mesa, minX, minZ, maxX, maxZ, canvas);
                        else placeMineshaftStairs(p, minX, minZ, maxX, maxZ, canvas);
                    }
                }
            }
        }
    }

    /**
     * MineShaftPiece.isInInvalidLocation: skip carving this piece entirely if its center biome
     * is `minecraft:mineshaft_blocking` (real tag: `deep_dark` only) or any of its 6 bounding
     * faces touches a liquid block. Real vanilla evaluates this against a `chunkBB` covering
     * the WHOLE structure-generation region (multiple chunks) in one pass; this project's
     * per-chunk-clipped architecture instead evaluates it per RENDER CHUNK, clamping the
     * piece's bbox (±1) to the CURRENT chunk's window — a piece spanning several chunks could
     * theoretically get inconsistent verdicts across different chunk renders (unlike real
     * vanilla's single atomic evaluation), but is consistent with the same per-chunk-clipped
     * simplification already applied to every other terrain-reading structure feature this
     * session, and low-risk in practice since liquid/biome data rarely varies enough within a
     * mineshaft piece's small span to flip the verdict between adjacent chunks.
     */
    private boolean msIsInInvalidLocation(MSPiece p, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int x0 = Math.max(p.minX() - 1, minX), x1 = Math.min(p.maxX() + 1, maxX);
        int z0 = Math.max(p.minZ() - 1, minZ), z1 = Math.min(p.maxZ() + 1, maxZ);
        int y0 = p.minY() - 1, y1 = p.maxY() + 1;
        if (x0 > x1 || z0 > z1) return false; // piece's ±1-padded bbox doesn't reach into this render chunk at all

        int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
        if (mineshaftBlockingBiomes.contains(biomes.biomeAt(cx >> 2, cy >> 2, cz >> 2))) return true;

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (msIsLiquid(canvasGet(canvas, x, y0, z)) || msIsLiquid(canvasGet(canvas, x, y1, z))) return true;
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                if (msIsLiquid(canvasGet(canvas, x, y, z0)) || msIsLiquid(canvasGet(canvas, x, y, z1))) return true;
            }
        }
        for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                if (msIsLiquid(canvasGet(canvas, x0, y, z)) || msIsLiquid(canvasGet(canvas, x1, y, z))) return true;
            }
        }
        return false;
    }

    private static boolean msIsLiquid(Block block) {
        String name = block.name();
        return name.equals("minecraft:water") || name.equals("minecraft:lava");
    }

    private void placeMineshaftRoom(MSPiece p, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        // generateBox: hollow the interior out from minY+1 to min(minY+3, maxY), CAVE_AIR everywhere (edge == fill here).
        int boxY1 = Math.min(p.minY() + 3, p.maxY());
        for (int x = p.minX(); x <= p.maxX(); x++) {
            if (x < minX || x > maxX) continue;
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                if (z < minZ || z > maxZ) continue;
                for (int y = p.minY() + 1; y <= boxY1; y++) canvas.set(x, y, z, Block.CAVE_AIR);
            }
        }
        // generateUpperHalfSphere: an ellipsoid dome over the room from minY+4 to maxY.
        float diagX = p.maxX() - p.minX() + 1, diagY = p.maxY() - p.minY() - 3, diagZ = p.maxZ() - p.minZ() + 1;
        float cx = p.minX() + diagX / 2.0F, cz = p.minZ() + diagZ / 2.0F;
        for (int y = p.minY() + 4; y <= p.maxY(); y++) {
            float ny = (y - (p.minY() + 4)) / diagY;
            for (int x = p.minX(); x <= p.maxX(); x++) {
                if (x < minX || x > maxX) continue;
                float nx = (x - cx) / (diagX * 0.5F);
                for (int z = p.minZ(); z <= p.maxZ(); z++) {
                    if (z < minZ || z > maxZ) continue;
                    float nz = (z - cz) / (diagZ * 0.5F);
                    if (nx * nx + ny * ny + nz * nz <= 1.05F) canvas.set(x, y, z, Block.CAVE_AIR);
                }
            }
        }
    }

    /**
     * Fully-open 3-wide tunnel (see MSPiece javadoc for why the 80%-ceiling variance isn't
     * reproduced), plus MineShaftCorridor.postProcess's floor-plank strip, per-section support
     * beams ({@link #msPlaceSupport} — fence posts + plank cap/strip + flanking torches, gated
     * on a real "is there ceiling above" check), and cobweb decoration (all real, position-
     * seeded rather than a shared continuously-advancing stream — same chunk-render-order-
     * independence requirement already established for ruined portal's spreadNetherrack), and
     * rails ({@link #msMaybePlaceRail} — real floor-solidity-gated). Still NOT decorated:
     * chest-loot (real vanilla spawns a chest MINECART ENTITY here, not a block — matches the
     * established no-worldgen-entity-spawn precedent), and the spider spawner (a BLOCK, but
     * needs the spawner mob-type API plus the same entity-spawn-adjacent caution).
     */
    private void placeMineshaftCorridor(MSPiece p, boolean mesa, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        for (int x = p.minX(); x <= p.maxX(); x++) {
            if (x < minX || x > maxX) continue;
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                if (z < minZ || z > maxZ) continue;
                for (int y = p.minY(); y <= p.maxY(); y++) canvas.set(x, y, z, Block.CAVE_AIR);
            }
        }
        int length = p.numSections() * 5 - 1;
        Block planks = mesa ? Block.DARK_OAK_PLANKS : Block.OAK_PLANKS;

        // MineShaftCorridor.postProcess's floor-plank strip: setPlanksBlock at local (x in [0,2], y=-1, z in [0,length]).
        for (int lx = 0; lx <= 2; lx++) {
            for (int lz = 0; lz <= length; lz++) {
                msSetPlanksBlock(p, planks, lx, -1, lz, minX, minZ, maxX, maxZ, canvas);
            }
        }

        // Per-section support beams, then cobwebs (8 candidate positions, real vanilla probability tiers).
        for (int section = 0; section < p.numSections(); section++) {
            int lz = 2 + section * 5;
            msPlaceSupport(p, mesa, lz, minX, minZ, maxX, maxZ, canvas);
            msMaybePlaceCobWeb(p, 0.1F, 0, 2, lz - 1, minX, minZ, maxX, maxZ, canvas);
            msMaybePlaceCobWeb(p, 0.1F, 2, 2, lz - 1, minX, minZ, maxX, maxZ, canvas);
            msMaybePlaceCobWeb(p, 0.1F, 0, 2, lz + 1, minX, minZ, maxX, maxZ, canvas);
            msMaybePlaceCobWeb(p, 0.1F, 2, 2, lz + 1, minX, minZ, maxX, maxZ, canvas);
            msMaybePlaceCobWeb(p, 0.05F, 0, 2, lz - 2, minX, minZ, maxX, maxZ, canvas);
            msMaybePlaceCobWeb(p, 0.05F, 2, 2, lz - 2, minX, minZ, maxX, maxZ, canvas);
            msMaybePlaceCobWeb(p, 0.05F, 0, 2, lz + 2, minX, minZ, maxX, maxZ, canvas);
            msMaybePlaceCobWeb(p, 0.05F, 2, 2, lz + 2, minX, minZ, maxX, maxZ, canvas);
        }

        // spiderCorridor: a ceiling cobweb strip (x in [0,2], y in [0,1], z in [0,length], 60%, isInterior-gated).
        if (p.spiderCorridor()) {
            for (int lx = 0; lx <= 2; lx++) {
                for (int ly = 0; ly <= 1; ly++) {
                    for (int lz = 0; lz <= length; lz++) {
                        msMaybePlaceCeilingCobweb(p, lx, ly, lz, minX, minZ, maxX, maxZ, canvas);
                    }
                }
            }
        }

        // hasRails: a rail track down the corridor's center line, gated on a real solid-floor
        // check, at 0.7 probability where isInterior (below the real surface) else 0.9.
        if (p.hasRails()) {
            for (int lz = 0; lz <= length; lz++) {
                msMaybePlaceRail(p, lz, minX, minZ, maxX, maxZ, canvas);
            }
        }
    }

    /** StructurePiece.getWorldX/getWorldZ/getWorldY: piece-orientation-dependent local -> world transform. */
    private static int[] msWorldPos(MSPiece p, int x, int y, int z) {
        int wx = switch (p.dir()) {
            case NORTH, SOUTH -> p.minX() + x;
            case WEST -> p.maxX() - z;
            case EAST -> p.minX() + z;
        };
        int wz = switch (p.dir()) {
            case NORTH -> p.maxZ() - z;
            case SOUTH -> p.minZ() + z;
            case WEST, EAST -> p.minZ() + x;
        };
        return new int[]{wx, p.minY() + y, wz};
    }

    /** StructurePiece.isInterior: world position is within the render-chunk clip AND below the real terrain surface. */
    private boolean msIsInterior(int wx, int wy, int wz, int minX, int minZ, int maxX, int maxZ) {
        if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) return false;
        VJigsaw.SurfaceHeight heightSource = oceanFloor != null ? oceanFloor : surface;
        return heightSource != null && wy < heightSource.firstFreeWg(wx, wz);
    }

    private void msSetPlanksBlock(MSPiece p, Block planks, int lx, int ly, int lz, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int[] wp = msWorldPos(p, lx, ly + 1, lz); // isInterior checks (x, y+1, z)
        if (!msIsInterior(wp[0], wp[1], wp[2], minX, minZ, maxX, maxZ)) return;
        int[] target = msWorldPos(p, lx, ly, lz);
        if (target[0] < minX || target[0] > maxX || target[2] < minZ || target[2] > maxZ) return;
        Block existing = canvasGet(canvas, target[0], target[1], target[2]);
        if (!msIsSturdyTop(existing)) canvas.set(target[0], target[1], target[2], planks);
    }

    private void msMaybePlaceCobWeb(MSPiece p, float probability, int lx, int ly, int lz, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int[] wp = msWorldPos(p, lx, ly + 1, lz);
        if (!msIsInterior(wp[0], wp[1], wp[2], minX, minZ, maxX, maxZ)) return;
        int[] target = msWorldPos(p, lx, ly, lz);
        if (target[0] < minX || target[0] > maxX || target[2] < minZ || target[2] > maxZ) return;
        VSurface.LegacyRandom rng = new VSurface.LegacyRandom(XRandom.blockSeed(target[0], target[1], target[2]));
        if (rng.nextFloat() >= probability) return;
        if (!msHasSturdyNeighbours(target[0], target[1], target[2], canvas)) return;
        canvas.set(target[0], target[1], target[2], Block.COBWEB);
    }

    private void msMaybePlaceCeilingCobweb(MSPiece p, int lx, int ly, int lz, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int[] wp = msWorldPos(p, lx, ly + 1, lz);
        if (!msIsInterior(wp[0], wp[1], wp[2], minX, minZ, maxX, maxZ)) return;
        int[] target = msWorldPos(p, lx, ly, lz);
        if (target[0] < minX || target[0] > maxX || target[2] < minZ || target[2] > maxZ) return;
        VSurface.LegacyRandom rng = new VSurface.LegacyRandom(XRandom.blockSeed(target[0], target[1], target[2]) ^ 0xC0B4EBL);
        if (rng.nextFloat() < 0.6F) canvas.set(target[0], target[1], target[2], Block.COBWEB);
    }

    /**
     * MineShaftCorridor's `hasRails` loop: a rail at local (1,0,z) down the corridor's center
     * line, gated on a real solid-floor check at local (1,-1,z) — approximated the same way as
     * every other "is this a full-cube substrate" check this session ({@link #msIsSturdyTop}
     * for "not air-like", standing in for real vanilla's `!isAir && isSolidRender`) — at 0.7
     * probability where {@link #msIsInterior} (below the real surface) else 0.9. Real vanilla
     * always uses `RailShape.NORTH_SOUTH` regardless of the corridor's own orientation (verified
     * from the decompile, not assumed) — reproduced bit-exactly even though it can look visually
     * disconnected for an EAST/WEST-running corridor, since that's genuinely what real vanilla
     * generates (Minestom's placed blockstates don't get vanilla's live rail-neighbor
     * auto-reshape that a player-placed rail would).
     */
    private void msMaybePlaceRail(MSPiece p, int lz, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int[] floorPos = msWorldPos(p, 1, -1, lz);
        if (floorPos[0] < minX || floorPos[0] > maxX || floorPos[2] < minZ || floorPos[2] > maxZ) return;
        if (!msIsSturdyTop(canvasGet(canvas, floorPos[0], floorPos[1], floorPos[2]))) return;

        int[] railPos = msWorldPos(p, 1, 0, lz);
        if (railPos[0] < minX || railPos[0] > maxX || railPos[2] < minZ || railPos[2] > maxZ) return;
        int[] interiorCheck = msWorldPos(p, 1, 1, lz);
        float probability = msIsInterior(interiorCheck[0], interiorCheck[1], interiorCheck[2], minX, minZ, maxX, maxZ) ? 0.7F : 0.9F;
        VSurface.LegacyRandom rng = new VSurface.LegacyRandom(XRandom.blockSeed(railPos[0], railPos[1], railPos[2]) ^ 0x8A11L);
        if (rng.nextFloat() < probability) canvas.set(railPos[0], railPos[1], railPos[2], Block.RAIL.withProperty("shape", "north_south"));
    }

    /**
     * MineShaftCorridor.placeSupport: a fence-post support beam at local (x0=0,x1=2,y0=0,y1=2,
     * z), gated on {@link #msIsSupportingBox} (real ceiling above, not an opened-up 80%-
     * variance gap — this project's fully-open carving means every candidate passes this check
     * in practice, but the check is still real and cheap to keep). 25% two separate plank caps;
     * 75% one connecting plank strip plus two 5%-chance flanking wall torches.
     */
    private void msPlaceSupport(MSPiece p, boolean mesa, int lz, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        if (!msIsSupportingBox(p, 0, 2, 2, lz, canvas)) return;
        Block planks = mesa ? Block.DARK_OAK_PLANKS : Block.OAK_PLANKS;
        Block fence = mesa ? Block.DARK_OAK_FENCE : Block.OAK_FENCE;

        for (int ly = 0; ly <= 1; ly++) {
            msPlaceIfInBounds(p, 0, ly, lz, fence.withProperty("west", "true"), minX, minZ, maxX, maxZ, canvas);
            msPlaceIfInBounds(p, 2, ly, lz, fence.withProperty("east", "true"), minX, minZ, maxX, maxZ, canvas);
        }

        int[] capSeedPos = msWorldPos(p, 0, 2, lz);
        VSurface.LegacyRandom capRng = new VSurface.LegacyRandom(XRandom.blockSeed(capSeedPos[0], capSeedPos[1], capSeedPos[2]) ^ 0x5A11EDL);
        if (capRng.nextInt(4) == 0) {
            msPlaceIfInBounds(p, 0, 2, lz, planks, minX, minZ, maxX, maxZ, canvas);
            msPlaceIfInBounds(p, 2, 2, lz, planks, minX, minZ, maxX, maxZ, canvas);
        } else {
            for (int lx = 0; lx <= 2; lx++) msPlaceIfInBounds(p, lx, 2, lz, planks, minX, minZ, maxX, maxZ, canvas);
            msMaybePlaceTorch(p, 1, 2, lz - 1, "south", minX, minZ, maxX, maxZ, canvas);
            msMaybePlaceTorch(p, 1, 2, lz + 1, "north", minX, minZ, maxX, maxZ, canvas);
        }
    }

    private boolean msIsSupportingBox(MSPiece p, int lx0, int lx1, int ly1, int lz, VStructureGen.Canvas canvas) {
        for (int lx = lx0; lx <= lx1; lx++) {
            int[] wp = msWorldPos(p, lx, ly1 + 1, lz);
            if (canvasGet(canvas, wp[0], wp[1], wp[2]).name().equals("minecraft:air")) return false;
        }
        return true;
    }

    private void msPlaceIfInBounds(MSPiece p, int lx, int ly, int lz, Block block, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int[] wp = msWorldPos(p, lx, ly, lz);
        if (wp[0] < minX || wp[0] > maxX || wp[2] < minZ || wp[2] > maxZ) return;
        canvas.set(wp[0], wp[1], wp[2], block);
    }

    private void msMaybePlaceTorch(MSPiece p, int lx, int ly, int lz, String facing, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int[] wp = msWorldPos(p, lx, ly, lz);
        if (wp[0] < minX || wp[0] > maxX || wp[2] < minZ || wp[2] > maxZ) return;
        VSurface.LegacyRandom rng = new VSurface.LegacyRandom(XRandom.blockSeed(wp[0], wp[1], wp[2]) ^ 0x704C4800L);
        if (rng.nextFloat() < 0.05F) canvas.set(wp[0], wp[1], wp[2], Block.WALL_TORCH.withProperty("facing", facing));
    }

    /** Approximates BlockState.isFaceSturdy(UP): "not air-like" — full-cube ground/stone/liquid substrate counts as sturdy, gaps don't. */
    private static boolean msIsSturdyTop(Block block) {
        String name = block.name();
        return !name.equals("minecraft:air") && !name.equals("minecraft:cave_air")
                && !name.equals("minecraft:water") && !name.equals("minecraft:lava");
    }

    /** Approximates hasSturdyNeighbours(count=2): at least 2 of the 6 axis-neighbors are non-air-like. */
    private static boolean msHasSturdyNeighbours(int x, int y, int z, VStructureGen.Canvas canvas) {
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int sturdy = 0;
        for (int[] o : offsets) {
            if (msIsSturdyTop(canvasGet(canvas, x + o[0], y + o[1], z + o[2])) && ++sturdy >= 2) return true;
        }
        return false;
    }

    /**
     * MineShaftCrossing.postProcess carving, plus (unconditionally, both two-floored and
     * single-floor — real vanilla's own `placeSupportPillar`/floor-plank calls sit AFTER the
     * two-floored/single-floor if/else, not inside it) 4 corner support pillars — real
     * vanilla operates on the crossing's OWN `boundingBox` directly here (no orientation
     * transform, unlike corridor's local-offset decoration), matching this piece's carving
     * code above which already works in absolute world coordinates — and a full-footprint
     * floor-plank strip one below the crossing's floor.
     */
    private void placeMineshaftCrossing(MSPiece p, boolean mesa, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        if (p.flag()) {
            carveBox(p.minX() + 1, p.minY(), p.minZ(), p.maxX() - 1, p.minY() + 2, p.maxZ(), minX, minZ, maxX, maxZ, canvas);
            carveBox(p.minX(), p.minY(), p.minZ() + 1, p.maxX(), p.minY() + 2, p.maxZ() - 1, minX, minZ, maxX, maxZ, canvas);
            carveBox(p.minX() + 1, p.maxY() - 2, p.minZ(), p.maxX() - 1, p.maxY(), p.maxZ(), minX, minZ, maxX, maxZ, canvas);
            carveBox(p.minX(), p.maxY() - 2, p.minZ() + 1, p.maxX(), p.maxY(), p.maxZ() - 1, minX, minZ, maxX, maxZ, canvas);
            carveBox(p.minX() + 1, p.minY() + 3, p.minZ() + 1, p.maxX() - 1, p.minY() + 3, p.maxZ() - 1, minX, minZ, maxX, maxZ, canvas);
        } else {
            carveBox(p.minX() + 1, p.minY(), p.minZ(), p.maxX() - 1, p.maxY(), p.maxZ(), minX, minZ, maxX, maxZ, canvas);
            carveBox(p.minX(), p.minY(), p.minZ() + 1, p.maxX(), p.maxY(), p.maxZ() - 1, minX, minZ, maxX, maxZ, canvas);
        }

        Block planks = mesa ? Block.DARK_OAK_PLANKS : Block.OAK_PLANKS;
        msPlaceSupportPillar(planks, p.minX() + 1, p.minY(), p.minZ() + 1, p.maxY(), minX, minZ, maxX, maxZ, canvas);
        msPlaceSupportPillar(planks, p.minX() + 1, p.minY(), p.maxZ() - 1, p.maxY(), minX, minZ, maxX, maxZ, canvas);
        msPlaceSupportPillar(planks, p.maxX() - 1, p.minY(), p.minZ() + 1, p.maxY(), minX, minZ, maxX, maxZ, canvas);
        msPlaceSupportPillar(planks, p.maxX() - 1, p.minY(), p.maxZ() - 1, p.maxY(), minX, minZ, maxX, maxZ, canvas);

        int floorY = p.minY() - 1;
        for (int x = p.minX(); x <= p.maxX(); x++) {
            if (x < minX || x > maxX) continue;
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                if (z < minZ || z > maxZ) continue;
                Block existing = canvasGet(canvas, x, floorY, z);
                if (!msIsSturdyTop(existing)) canvas.set(x, floorY, z, planks);
            }
        }
    }

    /** MineShaftCrossing.placeSupportPillar: a full solid plank column, only if there's real ceiling above y1. */
    private void msPlaceSupportPillar(Block planks, int x, int y0, int z, int y1, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return;
        if (canvasGet(canvas, x, y1 + 1, z).name().equals("minecraft:air")) return;
        for (int y = y0; y <= y1; y++) canvas.set(x, y, z, planks);
    }

    private void carveBox(int bx0, int by0, int bz0, int bx1, int by1, int bz1,
                           int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        for (int x = bx0; x <= bx1; x++) {
            if (x < minX || x > maxX) continue;
            for (int z = bz0; z <= bz1; z++) {
                if (z < minZ || z > maxZ) continue;
                for (int y = by0; y <= by1; y++) canvas.set(x, y, z, Block.CAVE_AIR);
            }
        }
    }

    /** MineShaftStairs.postProcess carving: a landing box, a lower box, and a 5-step staircase between them. */
    private void placeMineshaftStairs(MSPiece p, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int bx = p.minX(), by = p.minY(), bz = p.minZ();
        carveBox(bx, by + 5, bz, bx + 2, by + 7, bz + 1, minX, minZ, maxX, maxZ, canvas);
        carveBox(bx, by, bz + 7, bx + 2, by + 2, bz + 8, minX, minZ, maxX, maxZ, canvas);
        for (int i = 0; i < 5; i++) {
            int y0 = by + 5 - i - (i < 4 ? 1 : 0);
            int y1 = by + 7 - i;
            int z = bz + 2 + i;
            carveBox(bx, y0, z, bx + 2, y1, z, minX, minZ, maxX, maxZ, canvas);
        }
    }

    /** Test hook: {pieceCount, roomMinX, roomMinY, roomMinZ, roomMaxX, roomMaxY, roomMaxZ} for the given chunk/flavor, or null. */
    public int[] testMineshaftAt(String flavor, int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(MINESHAFT_SET, chunkX, chunkZ)) return null;
        List<MSPiece> pieces = assembleMineshaft(flavor, chunkX, chunkZ);
        if (pieces == null) return null;
        MSPiece room = pieces.get(0);
        return new int[]{pieces.size(), room.minX(), room.minY(), room.minZ(), room.maxX(), room.maxY(), room.maxZ()};
    }

    /** Test hook: {kind (0=room,1=corridor,2=crossing,3=stairs), minX, minY, minZ, maxX, maxY, maxZ} for piece index i, or null if out of range. */
    public int[] testMineshaftPieceAt(String flavor, int chunkX, int chunkZ, int i) {
        List<MSPiece> pieces = assembleMineshaft(flavor, chunkX, chunkZ);
        if (pieces == null || i >= pieces.size()) return null;
        MSPiece p = pieces.get(i);
        int kind = switch (p.kind()) { case ROOM -> 0; case CORRIDOR -> 1; case CROSSING -> 2; case STAIRS -> 3; };
        return new int[]{kind, p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ()};
    }

    private List<MSPiece> assembleMineshaft(String flavor, int ccx, int ccz) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, ccx, ccz);
        random.nextDouble(); // MineshaftStructure.findGenerationPoint: context.random().nextDouble(), consumed and unused

        int west = (ccx << 4) + 2, north = (ccz << 4) + 2;
        int minX = west, minY = MINESHAFT_START_Y, minZ = north;
        int maxX = west + 7 + random.nextInt(6);
        int maxY = MINESHAFT_START_Y + 4 + random.nextInt(6); // 54 + random.nextInt(6)
        int maxZ = north + 7 + random.nextInt(6);
        MSPiece room = new MSPiece(MSKind.ROOM, minX, minY, minZ, maxX, maxY, maxZ, null, 0, 0, false, false, false);

        List<MSPiece> pieces = new ArrayList<>();
        pieces.add(room);
        addChildrenRoom(room, pieces, random, west, north);

        int unionMinY = Integer.MAX_VALUE, unionMaxY = Integer.MIN_VALUE;
        int unionMinX = Integer.MAX_VALUE, unionMaxX = Integer.MIN_VALUE, unionMinZ = Integer.MAX_VALUE, unionMaxZ = Integer.MIN_VALUE;
        for (MSPiece p : pieces) {
            unionMinY = Math.min(unionMinY, p.minY()); unionMaxY = Math.max(unionMaxY, p.maxY());
            unionMinX = Math.min(unionMinX, p.minX()); unionMaxX = Math.max(unionMaxX, p.maxX());
            unionMinZ = Math.min(unionMinZ, p.minZ()); unionMaxZ = Math.max(unionMaxZ, p.maxZ());
        }

        int dy;
        if (flavor.equals("normal")) {
            // moveBelowSeaLevel(seaLevel, chunkGenerator.getMinY(), random, offset=10) over the WHOLE tree's union bbox.
            int ySpan = unionMaxY - unionMinY + 1;
            int maxYLimit = seaLevel - 10;
            int y1Pos = ySpan + MIN_Y + 1;
            if (y1Pos < maxYLimit) y1Pos += random.nextInt(maxYLimit - y1Pos);
            dy = y1Pos - unionMaxY;
        } else {
            // MESA: center the union bbox's Y between seaLevel and the real surface height at its center.
            int centerX = (unionMinX + unionMaxX) / 2, centerZ = (unionMinZ + unionMaxZ) / 2, centerY = (unionMinY + unionMaxY) / 2;
            int surfaceHeight = surface.firstFreeWg(centerX, centerZ) - 1;
            int targetY = surfaceHeight <= seaLevel ? seaLevel : seaLevel + random.nextInt(surfaceHeight - seaLevel + 1);
            dy = targetY - centerY;
        }
        List<MSPiece> shifted = new ArrayList<>(pieces.size());
        for (MSPiece p : pieces) shifted.add(p.shiftY(dy));

        MSPiece shiftedRoom = shifted.get(0);
        int centerX = (shiftedRoom.minX() + shiftedRoom.maxX()) / 2, centerZ = (shiftedRoom.minZ() + shiftedRoom.maxZ()) / 2,
                centerY = (shiftedRoom.minY() + shiftedRoom.maxY()) / 2;
        String biome = biomes.biomeAt(centerX >> 2, centerY >> 2, centerZ >> 2);
        Set<String> allowed = flavor.equals("normal") ? mineshaftBiomes : mineshaftMesaBiomes;
        if (!allowed.contains(biome)) return null;

        return shifted;
    }

    private static boolean msCollides(List<MSPiece> pieces, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (MSPiece p : pieces) {
            if (!(maxX < p.minX() || minX > p.maxX() || maxY < p.minY() || minY > p.maxY() || maxZ < p.minZ() || minZ > p.maxZ())) return true;
        }
        return false;
    }

    /** MineshaftPieces.generateAndAddPiece: depth/distance limits, then dispatch + recurse. */
    private void generateAndAddPiece(List<MSPiece> pieces, VSurface.LegacyRandom random, int footX, int footY, int footZ,
                                      MSDir dir, int depth, int startMinX, int startMinZ) {
        if (depth > MINESHAFT_MAX_DEPTH) return;
        if (Math.abs(footX - startMinX) > MINESHAFT_MAX_DIST || Math.abs(footZ - startMinZ) > MINESHAFT_MAX_DIST) return;
        MSPiece newPiece = createRandomShaftPiece(pieces, random, footX, footY, footZ, dir, depth + 1);
        if (newPiece != null) {
            pieces.add(newPiece);
            if (newPiece.kind() == MSKind.CORRIDOR) addChildrenCorridor(newPiece, pieces, random, startMinX, startMinZ);
            else if (newPiece.kind() == MSKind.CROSSING) addChildrenCrossing(newPiece, pieces, random, startMinX, startMinZ);
            else if (newPiece.kind() == MSKind.STAIRS) addChildrenStairs(newPiece, pieces, random, startMinX, startMinZ);
        }
    }

    /** MineshaftPieces.createRandomShaftPiece: 80% corridor / 10% stairs / 10% crossing. */
    private MSPiece createRandomShaftPiece(List<MSPiece> pieces, VSurface.LegacyRandom random, int footX, int footY, int footZ,
                                            MSDir dir, int genDepth) {
        int roll = random.nextInt(100);
        if (roll >= 80) return createCrossing(pieces, random, footX, footY, footZ, dir, genDepth);
        if (roll >= 70) return createStairs(pieces, random, footX, footY, footZ, dir, genDepth);
        return createCorridor(pieces, random, footX, footY, footZ, dir, genDepth);
    }

    /** MineShaftCorridor.findCorridorSize + constructor. */
    private MSPiece createCorridor(List<MSPiece> pieces, VSurface.LegacyRandom random, int footX, int footY, int footZ,
                                    MSDir dir, int genDepth) {
        int[] box = null;
        for (int corridorLength = random.nextInt(3) + 2; corridorLength > 0; corridorLength--) {
            int len = corridorLength * 5;
            int bx0, by0, bz0, bx1, by1, bz1;
            switch (dir) {
                case SOUTH -> { bx0 = 0; by0 = 0; bz0 = 0; bx1 = 2; by1 = 2; bz1 = len - 1; }
                case WEST -> { bx0 = -(len - 1); by0 = 0; bz0 = 0; bx1 = 0; by1 = 2; bz1 = 2; }
                case EAST -> { bx0 = 0; by0 = 0; bz0 = 0; bx1 = len - 1; by1 = 2; bz1 = 2; }
                default -> { bx0 = 0; by0 = 0; bz0 = -(len - 1); bx1 = 2; by1 = 2; bz1 = 0; } // NORTH
            }
            int minX = bx0 + footX, minY = by0 + footY, minZ = bz0 + footZ, maxX = bx1 + footX, maxY = by1 + footY, maxZ = bz1 + footZ;
            if (!msCollides(pieces, minX, minY, minZ, maxX, maxY, maxZ)) { box = new int[]{minX, minY, minZ, maxX, maxY, maxZ}; break; }
        }
        if (box == null) return null;

        boolean hasRails = random.nextInt(3) == 0;
        // spiderCorridor only drawn when !hasRails (real: `!hasRails && random.nextInt(23)==0`) —
        // this draw's RESULT was previously discarded (always treated as false); now captured.
        boolean spiderCorridor = !hasRails && random.nextInt(23) == 0;

        int numSections = (dir == MSDir.NORTH || dir == MSDir.SOUTH) ? (box[5] - box[2] + 1) / 5 : (box[3] - box[0] + 1) / 5;
        return new MSPiece(MSKind.CORRIDOR, box[0], box[1], box[2], box[3], box[4], box[5], dir, numSections, genDepth, false, hasRails, spiderCorridor);
    }

    /** MineShaftCrossing.findCrossing + constructor. No length-retry loop — a single collision check, unlike corridors. */
    private MSPiece createCrossing(List<MSPiece> pieces, VSurface.LegacyRandom random, int footX, int footY, int footZ,
                                    MSDir dir, int genDepth) {
        int y1 = random.nextInt(4) == 0 ? 6 : 2;
        int bx0, by0, bz0, bx1, by1, bz1;
        switch (dir) {
            case SOUTH -> { bx0 = -1; by0 = 0; bz0 = 0; bx1 = 3; by1 = y1; bz1 = 4; }
            case WEST -> { bx0 = -4; by0 = 0; bz0 = -1; bx1 = 0; by1 = y1; bz1 = 3; }
            case EAST -> { bx0 = 0; by0 = 0; bz0 = -1; bx1 = 4; by1 = y1; bz1 = 3; }
            default -> { bx0 = -1; by0 = 0; bz0 = -4; bx1 = 3; by1 = y1; bz1 = 0; } // NORTH
        }
        int minX = bx0 + footX, minY = by0 + footY, minZ = bz0 + footZ, maxX = bx1 + footX, maxY = by1 + footY, maxZ = bz1 + footZ;
        if (msCollides(pieces, minX, minY, minZ, maxX, maxY, maxZ)) return null;
        boolean twoFloored = (maxY - minY + 1) > 3;
        return new MSPiece(MSKind.CROSSING, minX, minY, minZ, maxX, maxY, maxZ, dir, 0, genDepth, twoFloored, false, false);
    }

    /** MineShaftStairs.findStairs + constructor. No RNG draws at all — a fixed direction-dependent box, single collision check. */
    private MSPiece createStairs(List<MSPiece> pieces, VSurface.LegacyRandom random, int footX, int footY, int footZ,
                                  MSDir dir, int genDepth) {
        int bx0, by0, bz0, bx1, by1, bz1;
        switch (dir) {
            case SOUTH -> { bx0 = 0; by0 = -5; bz0 = 0; bx1 = 2; by1 = 2; bz1 = 8; }
            case WEST -> { bx0 = -8; by0 = -5; bz0 = 0; bx1 = 0; by1 = 2; bz1 = 2; }
            case EAST -> { bx0 = 0; by0 = -5; bz0 = 0; bx1 = 8; by1 = 2; bz1 = 2; }
            default -> { bx0 = 0; by0 = -5; bz0 = -8; bx1 = 2; by1 = 2; bz1 = 0; } // NORTH
        }
        int minX = bx0 + footX, minY = by0 + footY, minZ = bz0 + footZ, maxX = bx1 + footX, maxY = by1 + footY, maxZ = bz1 + footZ;
        if (msCollides(pieces, minX, minY, minZ, maxX, maxY, maxZ)) return null;
        return new MSPiece(MSKind.STAIRS, minX, minY, minZ, maxX, maxY, maxZ, dir, 0, genDepth, false, false, false);
    }

    /** MineShaftRoom.addChildren: up to 4 walls, spaced attempts along each wall's span. */
    private void addChildrenRoom(MSPiece room, List<MSPiece> pieces, VSurface.LegacyRandom random, int startMinX, int startMinZ) {
        int xSpan = room.maxX() - room.minX() + 1, zSpan = room.maxZ() - room.minZ() + 1;
        int heightSpace = Math.max(1, (room.maxY() - room.minY() + 1) - 3 - 1);

        int pos = 0;
        while (pos < xSpan) {
            pos += random.nextInt(xSpan);
            if (pos + 3 > xSpan) break;
            generateAndAddPiece(pieces, random, room.minX() + pos, room.minY() + random.nextInt(heightSpace) + 1, room.minZ() - 1,
                    MSDir.NORTH, 0, startMinX, startMinZ);
            pos += 4;
        }
        pos = 0;
        while (pos < xSpan) {
            pos += random.nextInt(xSpan);
            if (pos + 3 > xSpan) break;
            generateAndAddPiece(pieces, random, room.minX() + pos, room.minY() + random.nextInt(heightSpace) + 1, room.maxZ() + 1,
                    MSDir.SOUTH, 0, startMinX, startMinZ);
            pos += 4;
        }
        pos = 0;
        while (pos < zSpan) {
            pos += random.nextInt(zSpan);
            if (pos + 3 > zSpan) break;
            generateAndAddPiece(pieces, random, room.minX() - 1, room.minY() + random.nextInt(heightSpace) + 1, room.minZ() + pos,
                    MSDir.WEST, 0, startMinX, startMinZ);
            pos += 4;
        }
        pos = 0;
        while (pos < zSpan) {
            pos += random.nextInt(zSpan);
            if (pos + 3 > zSpan) break;
            generateAndAddPiece(pieces, random, room.maxX() + 1, room.minY() + random.nextInt(heightSpace) + 1, room.minZ() + pos,
                    MSDir.EAST, 0, startMinX, startMinZ);
            pos += 4;
        }
    }

    /** MineShaftCorridor.addChildren: one end-piece (orientation-dependent 4-way branch) + side branches every 5 blocks. */
    private void addChildrenCorridor(MSPiece corridor, List<MSPiece> pieces, VSurface.LegacyRandom random, int startMinX, int startMinZ) {
        int depth = corridor.genDepth();
        int endSelection = random.nextInt(4);
        int minX = corridor.minX(), minZ = corridor.minZ(), maxX = corridor.maxX(), maxZ = corridor.maxZ(), minY = corridor.minY();
        switch (corridor.dir()) {
            case NORTH -> {
                if (endSelection <= 1) generateAndAddPiece(pieces, random, minX, minY - 1 + random.nextInt(3), minZ - 1, MSDir.NORTH, depth, startMinX, startMinZ);
                else if (endSelection == 2) generateAndAddPiece(pieces, random, minX - 1, minY - 1 + random.nextInt(3), minZ, MSDir.WEST, depth, startMinX, startMinZ);
                else generateAndAddPiece(pieces, random, maxX + 1, minY - 1 + random.nextInt(3), minZ, MSDir.EAST, depth, startMinX, startMinZ);
            }
            case SOUTH -> {
                if (endSelection <= 1) generateAndAddPiece(pieces, random, minX, minY - 1 + random.nextInt(3), maxZ + 1, MSDir.SOUTH, depth, startMinX, startMinZ);
                else if (endSelection == 2) generateAndAddPiece(pieces, random, minX - 1, minY - 1 + random.nextInt(3), maxZ - 3, MSDir.WEST, depth, startMinX, startMinZ);
                else generateAndAddPiece(pieces, random, maxX + 1, minY - 1 + random.nextInt(3), maxZ - 3, MSDir.EAST, depth, startMinX, startMinZ);
            }
            case WEST -> {
                if (endSelection <= 1) generateAndAddPiece(pieces, random, minX - 1, minY - 1 + random.nextInt(3), minZ, MSDir.WEST, depth, startMinX, startMinZ);
                else if (endSelection == 2) generateAndAddPiece(pieces, random, minX, minY - 1 + random.nextInt(3), minZ - 1, MSDir.NORTH, depth, startMinX, startMinZ);
                else generateAndAddPiece(pieces, random, minX, minY - 1 + random.nextInt(3), maxZ + 1, MSDir.SOUTH, depth, startMinX, startMinZ);
            }
            case EAST -> {
                if (endSelection <= 1) generateAndAddPiece(pieces, random, maxX + 1, minY - 1 + random.nextInt(3), minZ, MSDir.EAST, depth, startMinX, startMinZ);
                else if (endSelection == 2) generateAndAddPiece(pieces, random, maxX - 3, minY - 1 + random.nextInt(3), minZ - 1, MSDir.NORTH, depth, startMinX, startMinZ);
                else generateAndAddPiece(pieces, random, maxX - 3, minY - 1 + random.nextInt(3), maxZ + 1, MSDir.SOUTH, depth, startMinX, startMinZ);
            }
        }

        if (depth < MINESHAFT_MAX_DEPTH) {
            if (corridor.dir() != MSDir.NORTH && corridor.dir() != MSDir.SOUTH) {
                for (int x = minX + 3; x + 3 <= maxX; x += 5) {
                    int sel = random.nextInt(5);
                    if (sel == 0) generateAndAddPiece(pieces, random, x, minY, minZ - 1, MSDir.NORTH, depth + 1, startMinX, startMinZ);
                    else if (sel == 1) generateAndAddPiece(pieces, random, x, minY, maxZ + 1, MSDir.SOUTH, depth + 1, startMinX, startMinZ);
                }
            } else {
                for (int z = minZ + 3; z + 3 <= maxZ; z += 5) {
                    int sel = random.nextInt(5);
                    if (sel == 0) generateAndAddPiece(pieces, random, minX - 1, minY, z, MSDir.WEST, depth + 1, startMinX, startMinZ);
                    else if (sel == 1) generateAndAddPiece(pieces, random, maxX + 1, minY, z, MSDir.EAST, depth + 1, startMinX, startMinZ);
                }
            }
        }
    }

    /** MineShaftCrossing.addChildren: 3 unconditional fan-out pieces + (if two-floored) 4 independently-gated upper-floor pieces. */
    private void addChildrenCrossing(MSPiece crossing, List<MSPiece> pieces, VSurface.LegacyRandom random, int startMinX, int startMinZ) {
        int depth = crossing.genDepth();
        int minX = crossing.minX(), minZ = crossing.minZ(), maxX = crossing.maxX(), maxZ = crossing.maxZ(), minY = crossing.minY();
        switch (crossing.dir()) {
            case NORTH -> {
                generateAndAddPiece(pieces, random, minX + 1, minY, minZ - 1, MSDir.NORTH, depth, startMinX, startMinZ);
                generateAndAddPiece(pieces, random, minX - 1, minY, minZ + 1, MSDir.WEST, depth, startMinX, startMinZ);
                generateAndAddPiece(pieces, random, maxX + 1, minY, minZ + 1, MSDir.EAST, depth, startMinX, startMinZ);
            }
            case SOUTH -> {
                generateAndAddPiece(pieces, random, minX + 1, minY, maxZ + 1, MSDir.SOUTH, depth, startMinX, startMinZ);
                generateAndAddPiece(pieces, random, minX - 1, minY, minZ + 1, MSDir.WEST, depth, startMinX, startMinZ);
                generateAndAddPiece(pieces, random, maxX + 1, minY, minZ + 1, MSDir.EAST, depth, startMinX, startMinZ);
            }
            case WEST -> {
                generateAndAddPiece(pieces, random, minX + 1, minY, minZ - 1, MSDir.NORTH, depth, startMinX, startMinZ);
                generateAndAddPiece(pieces, random, minX + 1, minY, maxZ + 1, MSDir.SOUTH, depth, startMinX, startMinZ);
                generateAndAddPiece(pieces, random, minX - 1, minY, minZ + 1, MSDir.WEST, depth, startMinX, startMinZ);
            }
            case EAST -> {
                generateAndAddPiece(pieces, random, minX + 1, minY, minZ - 1, MSDir.NORTH, depth, startMinX, startMinZ);
                generateAndAddPiece(pieces, random, minX + 1, minY, maxZ + 1, MSDir.SOUTH, depth, startMinX, startMinZ);
                generateAndAddPiece(pieces, random, maxX + 1, minY, minZ + 1, MSDir.EAST, depth, startMinX, startMinZ);
            }
        }
        if (crossing.flag()) { // isTwoFloored — real order is fixed NORTH/WEST/EAST/SOUTH regardless of crossing.dir()
            if (random.nextBoolean()) generateAndAddPiece(pieces, random, minX + 1, minY + 3 + 1, minZ - 1, MSDir.NORTH, depth, startMinX, startMinZ);
            if (random.nextBoolean()) generateAndAddPiece(pieces, random, minX - 1, minY + 3 + 1, minZ + 1, MSDir.WEST, depth, startMinX, startMinZ);
            if (random.nextBoolean()) generateAndAddPiece(pieces, random, maxX + 1, minY + 3 + 1, minZ + 1, MSDir.EAST, depth, startMinX, startMinZ);
            if (random.nextBoolean()) generateAndAddPiece(pieces, random, minX + 1, minY + 3 + 1, maxZ + 1, MSDir.SOUTH, depth, startMinX, startMinZ);
        }
    }

    /** MineShaftStairs.addChildren: exactly one child, in the fixed direction matching the stairs' own orientation. */
    private void addChildrenStairs(MSPiece stairs, List<MSPiece> pieces, VSurface.LegacyRandom random, int startMinX, int startMinZ) {
        int depth = stairs.genDepth();
        switch (stairs.dir()) {
            case NORTH -> generateAndAddPiece(pieces, random, stairs.minX(), stairs.minY(), stairs.minZ() - 1, MSDir.NORTH, depth, startMinX, startMinZ);
            case SOUTH -> generateAndAddPiece(pieces, random, stairs.minX(), stairs.minY(), stairs.maxZ() + 1, MSDir.SOUTH, depth, startMinX, startMinZ);
            case WEST -> generateAndAddPiece(pieces, random, stairs.minX() - 1, stairs.minY(), stairs.minZ(), MSDir.WEST, depth, startMinX, startMinZ);
            case EAST -> generateAndAddPiece(pieces, random, stairs.maxX() + 1, stairs.minY(), stairs.minZ(), MSDir.EAST, depth, startMinX, startMinZ);
        }
    }

    // ------------------------------------------------------------------ igloo

    /**
     * Single NBT-template structure (like ruined portal), decompiled from IglooStructure/
     * IglooPieces. Real vanilla igloos are up to 3 stacked templates: always a "top" piece
     * (igloo/top.nbt, the surface hut), and with 50% probability ALSO a basement chain —
     * one "laboratory" piece (igloo/bottom.nbt) at the bottom, connected to the top by
     * `depth-1` repeating "ladder" segments (igloo/middle.nbt, 3 blocks apart), where
     * `depth = 4 + random.nextInt(8)` (4-11). Real vanilla has each piece independently
     * re-probe the terrain's WORLD_SURFACE_WG height at a shared world XZ point (the
     * entrance, local (3,0,0) rotated around each piece's own template-specific pivot) and
     * shift by `height - GENERATION_HEIGHT(90) - 1` — verified algebraically that the
     * per-template OFFSETS/PIVOTS tables are specifically designed so every piece converges
     * on the exact same world XZ point and therefore the exact same height/shift value
     * regardless of which piece computes it, so this project computes the shift ONCE (using
     * the top piece's own offset-zero/pivot) and applies it uniformly to every piece,
     * rather than redundantly re-deriving an identical value per piece like real vanilla
     * does. NOT implemented: the basement laboratory's chest loot-table assignment
     * (`handleDataMarker`'s "chest" data marker — the physical chest block from the
     * template IS placed normally, only its loot-table CONTENTS assignment is skipped,
     * matching this project's established precedent for ruined portal/mineshaft chests),
     * and the top piece's below-trapdoor solid-ground check (`postProcess`'s
     * trapdoor->snow_block fallback — a minor decoration-correctness detail, not shape).
     */
    private record IGPiece(VTemplate template, int baseX, int baseY, int baseZ, VTemplate.Rot rotation, int pivotX, int pivotZ) {}

    private static final String IGLOO_SET = "minecraft:igloos";
    private static final int IGLOO_RADIUS = 2;
    private static final int IGLOO_GEN_HEIGHT = 90;
    private static final String IGLOO_TOP = "minecraft:igloo/top";
    private static final String IGLOO_LADDER = "minecraft:igloo/middle";
    private static final String IGLOO_LAB = "minecraft:igloo/bottom";

    private void placeIgloos(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int r = IGLOO_RADIUS;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ccx = chunkX + dx, ccz = chunkZ + dz;
                if (!placement.isStructureChunk(IGLOO_SET, ccx, ccz)) continue;
                String key = ccx + ":" + ccz;
                List<IGPiece> pieces = iglooPieces.containsKey(key) ? iglooPieces.get(key) : assembleIgloo(ccx, ccz);
                iglooPieces.put(key, pieces);
                if (pieces == null) continue;
                for (IGPiece p : pieces) {
                    for (VTemplate.BlockInfo b : p.template().blocks) {
                        int[] wp = VTemplate.transform(b.x, b.y, b.z, p.rotation(), p.pivotX(), p.pivotZ());
                        int wx = wp[0] + p.baseX(), wy = wp[1] + p.baseY(), wz = wp[2] + p.baseZ();
                        if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                        String name = b.state.key().asString();
                        if (name.equals("minecraft:structure_block") || name.equals("minecraft:structure_void")) continue;
                        canvas.set(wx, wy, wz, VBlockRotate.rotate(b.state, p.rotation()));
                        registerContainerLoot(name, b.nbt, wx, wy, wz);
                        // IglooPiece.handleDataMarker("chest", ...): the lab chest has no LootTable
                        // of its own in the template NBT (see registerContainerLoot's no-op above) —
                        // real vanilla assigns BuiltInLootTables.IGLOO_CHEST via the data-marker path
                        // instead, which this project doesn't model generically (structure_block
                        // metadata markers are skipped entirely elsewhere); hardcode the one chest
                        // this specific template ever places.
                        if (name.equals("minecraft:chest")) {
                            dev.pointofpressure.minecom.blocks.Containers.registerLoot(
                                    new net.minestom.server.coordinate.Vec(wx, wy, wz), "minecraft:chests/igloo_chest");
                        }
                    }
                }
            }
        }
    }

    /** Test hook: {pieceCount, rotationOrdinal, topBaseX, topBaseY, topBaseZ} for the igloo at the given chunk, or null. */
    public int[] testIglooAt(int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(IGLOO_SET, chunkX, chunkZ)) return null;
        List<IGPiece> pieces = assembleIgloo(chunkX, chunkZ);
        if (pieces == null) return null;
        IGPiece top = pieces.get(pieces.size() - 1);
        return new int[]{pieces.size(), top.rotation().ordinal(), top.baseX(), top.baseY(), top.baseZ()};
    }

    private List<IGPiece> assembleIgloo(int ccx, int ccz) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, ccx, ccz);

        int startX = ccx << 4, startZ = ccz << 4;
        VTemplate.Rot rotation = VTemplate.Rot.VALUES[random.nextInt(4)];

        List<IGPiece> pieces = new ArrayList<>();
        boolean hasBasement = random.nextDouble() < 0.5;
        if (hasBasement) {
            int depth = random.nextInt(8) + 4;
            VTemplate lab = VTemplate.load(IGLOO_LAB);
            if (lab.sizeX != 0) pieces.add(new IGPiece(lab, startX, IGLOO_GEN_HEIGHT - 3 - depth * 3, startZ - 2, rotation, 3, 7));
            VTemplate ladder = VTemplate.load(IGLOO_LADDER);
            if (ladder.sizeX != 0) {
                for (int i = 0; i < depth - 1; i++) {
                    pieces.add(new IGPiece(ladder, startX + 2, IGLOO_GEN_HEIGHT - 3 - i * 3, startZ + 4, rotation, 1, 1));
                }
            }
        }
        VTemplate top = VTemplate.load(IGLOO_TOP);
        if (top.sizeX == 0) return null;
        pieces.add(new IGPiece(top, startX, IGLOO_GEN_HEIGHT, startZ, rotation, 3, 5));

        // Real vanilla re-probes WORLD_SURFACE_WG per piece, but the OFFSETS/PIVOTS tables
        // are designed so it always converges to the same world XZ/shift (see javadoc above) —
        // computed once here via the top piece's own offset(0,0,0)/pivot(3,5).
        int[] entrance = VTemplate.transform(3, 0, 0, rotation, 3, 5);
        int entranceX = startX + entrance[0], entranceZ = startZ + entrance[2];
        int height = surface.firstFreeWg(entranceX, entranceZ) - 1;
        int dy = height - IGLOO_GEN_HEIGHT - 1;

        List<IGPiece> shifted = new ArrayList<>(pieces.size());
        for (IGPiece p : pieces) shifted.add(new IGPiece(p.template(), p.baseX(), p.baseY() + dy, p.baseZ(), p.rotation(), p.pivotX(), p.pivotZ()));

        IGPiece finalTop = shifted.get(shifted.size() - 1);
        String biome = biomes.biomeAt(finalTop.baseX() >> 2, finalTop.baseY() >> 2, finalTop.baseZ() >> 2);
        if (!iglooBiomes.contains(biome)) return null;
        return shifted;
    }

    // ------------------------------------------------------------------ swamp hut

    /**
     * Fully hand-coded direct block placement (ScatteredFeaturePiece/StructurePiece,
     * decompiled from SwampHutStructure/SwampHutPiece) — a single, non-recursive piece,
     * unlike mineshaft's tree, but the SAME "no NBT template" placement mechanism. A random
     * horizontal direction (NORTH/SOUTH/WEST/EAST) picks BOTH the piece's local->world
     * coordinate mapping (StructurePiece.getWorldX/Y/Z: an axis-swap + reflection, not a
     * general 4-way rotation) AND a Mirror/Rotation pair applied to every placed block's
     * FACING property (StructurePiece.setOrientation's exact table: NORTH=identity,
     * SOUTH=mirror-only, WEST=mirror-then-rotate-90, EAST=rotate-90-only — verified against
     * the decompiled `Mirror.LEFT_RIGHT.mirror(Direction)`: swaps NORTH&lt;-&gt;SOUTH, leaves
     * EAST/WEST unchanged). The hut's actual Y placement comes from
     * `ScatteredFeaturePiece.updateAverageGroundHeight` — the real average of
     * MOTION_BLOCKING_NO_LEAVES height across the WHOLE footprint (not the single-point
     * WORLD_SURFACE_WG probe `onTopOfChunkCenter` uses only for the initial biome check) —
     * approximated here with this project's `surface.firstFreeWg` (already used everywhere
     * else for post-terrain height), a reasonable stand-in given swamp huts rarely sit under
     * leaf canopy at their exact footprint. Real vanilla also recomputes this average
     * PER-CHUNK the first time any overlapping chunk is decorated (so a hut straddling 2
     * chunks could in principle average over a partial footprint depending on decoration
     * order) — this project always averages over the FULL footprint once, matching the
     * overwhelmingly common case where the structure's own start chunk decorates first. NOT
     * implemented: the 4 corner `fillColumnDown` support-log pillars (terrain-read-dependent,
     * matching this project's established precedent of skipping structural decoration that
     * needs to read already-generated terrain — see mineshaft's skipped support pillars),
     * the witch/cat entity spawn (this project's structure-placement layer is block-only; no
     * other world-gen-time structure spawns entities directly either — villages populate via
     * a separate post-placement bell mechanism, a different pipeline entirely), and the 4
     * corner stairs' OUTER_LEFT/OUTER_RIGHT shape mirroring/rotation (facing IS correctly
     * mirrored/rotated per orientation; only the literal shape enum value is left unmirrored
     * — a minor cosmetic gap at the roof's 4 corners for 3 of the 4 possible orientations).
     */
    private enum SHDir { NORTH, SOUTH, WEST, EAST }

    private record SHPiece(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, SHDir dir) {}

    private static final String SWAMP_HUT_SET = "minecraft:swamp_huts";
    private static final int SWAMP_HUT_RADIUS = 1;

    private void placeSwampHuts(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int r = SWAMP_HUT_RADIUS;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ccx = chunkX + dx, ccz = chunkZ + dz;
                if (!placement.isStructureChunk(SWAMP_HUT_SET, ccx, ccz)) continue;
                String key = ccx + ":" + ccz;
                SHPiece p = swampHutStarts.containsKey(key) ? swampHutStarts.get(key) : assembleSwampHut(ccx, ccz);
                swampHutStarts.put(key, p);
                if (p == null) continue;
                if (p.maxX() < minX || p.minX() > maxX || p.maxZ() < minZ || p.minZ() > maxZ) continue;
                placeSwampHutBlocks(p, minX, minZ, maxX, maxZ, canvas);
            }
        }
    }

    /** Test hook: {dirOrdinal(NORTH=0/SOUTH=1/WEST=2/EAST=3), minX, minY, minZ, maxX, maxY, maxZ}, or null. */
    public int[] testSwampHutAt(int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(SWAMP_HUT_SET, chunkX, chunkZ)) return null;
        SHPiece p = assembleSwampHut(chunkX, chunkZ);
        if (p == null) return null;
        return new int[]{p.dir().ordinal(), p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ()};
    }

    private SHPiece assembleSwampHut(int ccx, int ccz) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, ccx, ccz);

        SHDir dir = SHDir.values()[random.nextInt(4)];
        int west = ccx << 4, north = ccz << 4;
        int minX, maxX, minZ, maxZ;
        if (dir == SHDir.NORTH || dir == SHDir.SOUTH) {
            minX = west; maxX = west + 6; minZ = north; maxZ = north + 8; // width=7 (X axis), depth=9 (Z axis)
        } else {
            minX = west; maxX = west + 8; minZ = north; maxZ = north + 6; // depth=9 (X axis), width=7 (Z axis)
        }

        long total = 0;
        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                total += surface.firstFreeWg(x, z) - 1;
                count++;
            }
        }
        int minY = (int) (total / count);

        // Biome check uses the chunk-MIDDLE point's WORLD_SURFACE_WG height (onTopOfChunkCenter),
        // a different point/height than the footprint-averaged minY used for actual block placement.
        int midX = west + 8, midZ = north + 8;
        int midY = surface.firstFreeWg(midX, midZ) - 1;
        String biome = biomes.biomeAt(midX >> 2, midY >> 2, midZ >> 2);
        if (!swampHutBiomes.contains(biome)) return null;

        return new SHPiece(minX, minY, minZ, maxX, minY + 6, maxZ, dir);
    }

    private static int shWorldX(SHPiece p, int x, int z) {
        return switch (p.dir()) {
            case NORTH, SOUTH -> p.minX() + x;
            case WEST -> p.maxX() - z;
            case EAST -> p.minX() + z;
        };
    }

    private static int shWorldZ(SHPiece p, int x, int z) {
        return switch (p.dir()) {
            case NORTH -> p.maxZ() - z;
            case SOUTH -> p.minZ() + z;
            case WEST, EAST -> p.minZ() + x;
        };
    }

    /** setOrientation's exact Mirror-then-Rotation table, applied to a "north"/"south"/"east"/"west" facing value. */
    private static String shFacing(String facing, SHDir dir) {
        String m = switch (dir) {
            case SOUTH, WEST -> switch (facing) { // Mirror.LEFT_RIGHT: swap north<->south, leave east/west
                case "north" -> "south";
                case "south" -> "north";
                default -> facing;
            };
            default -> facing;
        };
        return switch (dir) {
            case WEST, EAST -> switch (m) { // Rotation.CLOCKWISE_90: north->east->south->west->north
                case "north" -> "east";
                case "east" -> "south";
                case "south" -> "west";
                case "west" -> "north";
                default -> m;
            };
            default -> m;
        };
    }

    private static void shBox(SHPiece p, int lx0, int ly0, int lz0, int lx1, int ly1, int lz1, Block block,
                               int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        for (int lx = lx0; lx <= lx1; lx++) {
            for (int lz = lz0; lz <= lz1; lz++) {
                int wx = shWorldX(p, lx, lz), wz = shWorldZ(p, lx, lz);
                if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                for (int ly = ly0; ly <= ly1; ly++) canvas.set(wx, p.minY() + ly, wz, block);
            }
        }
    }

    private static void shBlock(SHPiece p, int lx, int ly, int lz, Block block,
                                 int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int wx = shWorldX(p, lx, lz), wz = shWorldZ(p, lx, lz);
        if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) return;
        canvas.set(wx, p.minY() + ly, wz, block);
    }

    private void placeSwampHutBlocks(SHPiece p, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        Block planks = Block.SPRUCE_PLANKS, log = Block.OAK_LOG;
        shBox(p, 1, 1, 1, 5, 1, 7, planks, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 1, 4, 2, 5, 4, 7, planks, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 2, 1, 0, 4, 1, 0, planks, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 2, 2, 2, 3, 3, 2, planks, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 1, 2, 3, 1, 3, 6, planks, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 5, 2, 3, 5, 3, 6, planks, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 2, 2, 7, 4, 3, 7, planks, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 1, 0, 2, 1, 3, 2, log, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 5, 0, 2, 5, 3, 2, log, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 1, 0, 7, 1, 3, 7, log, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 5, 0, 7, 5, 3, 7, log, minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 2, 3, 2, Block.OAK_FENCE, minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 3, 3, 7, Block.OAK_FENCE, minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 1, 3, 4, Block.AIR, minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 5, 3, 4, Block.AIR, minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 5, 3, 5, Block.AIR, minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 1, 3, 5, Block.POTTED_RED_MUSHROOM, minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 3, 2, 6, Block.CRAFTING_TABLE, minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 4, 2, 6, Block.CAULDRON, minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 1, 2, 1, Block.OAK_FENCE, minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 5, 2, 1, Block.OAK_FENCE, minX, minZ, maxX, maxZ, canvas);

        Block northStairs = Block.SPRUCE_STAIRS.withProperty("facing", shFacing("north", p.dir()));
        Block eastStairs = Block.SPRUCE_STAIRS.withProperty("facing", shFacing("east", p.dir()));
        Block westStairs = Block.SPRUCE_STAIRS.withProperty("facing", shFacing("west", p.dir()));
        Block southStairs = Block.SPRUCE_STAIRS.withProperty("facing", shFacing("south", p.dir()));
        shBox(p, 0, 4, 1, 6, 4, 1, northStairs, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 0, 4, 2, 0, 4, 7, eastStairs, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 6, 4, 2, 6, 4, 7, westStairs, minX, minZ, maxX, maxZ, canvas);
        shBox(p, 0, 4, 8, 6, 4, 8, southStairs, minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 0, 4, 1, northStairs.withProperty("shape", "outer_right"), minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 6, 4, 1, northStairs.withProperty("shape", "outer_left"), minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 0, 4, 8, southStairs.withProperty("shape", "outer_left"), minX, minZ, maxX, maxZ, canvas);
        shBlock(p, 6, 4, 8, southStairs.withProperty("shape", "outer_right"), minX, minZ, maxX, maxZ, canvas);
    }

    // ------------------------------------------------------------------ jungle temple

    /**
     * Fully hand-coded direct block placement (`ScatteredFeaturePiece`/`StructurePiece`,
     * decompiled from `JungleTempleStructure`/`SinglePieceStructure`/`JungleTemplePiece`) —
     * the same "no NBT template", single-piece-non-recursive mechanism as swamp hut, but a
     * much larger structure (12x10x15) with real cobblestone/mossy-cobblestone per-block RNG
     * texturing (`MossStoneSelector`) and a redstone-trap interior (tripwires -> dispensers,
     * a hidden lever-and-piston vault). `SinglePieceStructure.findGenerationPoint` adds a
     * gate this project's other `ScatteredFeaturePiece` structures don't have: a real 4-corner
     * `getLowestY` check (`WORLD_SURFACE_WG` at all 4 corners of the 12x15 footprint) that
     * rejects the ENTIRE candidate before any piece/RNG draw happens if any corner sits below
     * sea level (i.e. jungle temples never generate hanging over open water). Since block
     * placement (including the moss/cobblestone RNG picks) needs to be IDENTICAL no matter
     * which of up to 4 overlapping chunks calls into it, and since the RNG draws for those
     * picks can't be safely re-run independently per chunk (that would silently desync the
     * pattern depending on chunk-decoration order, unlike this project's other structures
     * where all RNG happens once during tree/piece ASSEMBLY, not at per-chunk placement
     * time), the ENTIRE local block layout (every wall, floor, air pocket, trap wire, and
     * moss-selector pick) is precomputed ONCE into a flat `List&lt;JTBlock&gt;` during
     * assembly and cached on the `JTPiece` — placement is then a pure transform+clip replay
     * with zero RNG involvement, keeping the structure identical regardless of chunk-overlap
     * decoration order. Position uses the SAME `getWorldX/Y/Z` axis-swap+reflection model as
     * swamp hut (see `SHPiece` javadoc) since both extend `ScatteredFeaturePiece`; the
     * per-direction Mirror-then-Rotate table is applied to every directional-FACING block
     * (stairs, dispenser, lever, piston, repeater) but NOT to the per-axis-boolean blocks
     * (tripwire, vine, redstone wire) — those are placed with their literal decompiled
     * property values regardless of orientation, a documented simplification (bit-exact
     * shape/positions in all 4 orientations; the trap-wire "wiring diagram" is only
     * guaranteed visually correct for the NORTH orientation, matching this project's
     * established precedent of simplifying orientation-dependent DECORATION detail while
     * keeping structure shape exact — see swamp hut's un-mirrored stair corner shapes for
     * the same class of tradeoff). NOT implemented: chest reorientation (`StructurePiece.
     * reorient`'s solid-neighbor scan — chests are placed with a fixed facing instead),
     * dispenser/chest loot-table CONTENTS assignment (containers ARE placed as real blocks;
     * matches established precedent for every other structure's chests/dispensers this
     * session), and the 2 trap dispensers' actual arrow-firing mechanism activation (this is
     * a worldgen-time block-placement gap only — the redstone/tripwire circuitry, once
     * placed, would already work through this project's existing real redstone engine like
     * any player-built circuit, since none of vanilla's trap logic is special-cased outside
     * of standard redstone propagation).
     */
    private enum JTDir { NORTH, SOUTH, WEST, EAST }

    private record JTBlock(int x, int y, int z, Block block, boolean rotateFacing) {}

    private record JTPiece(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, JTDir dir, List<JTBlock> blocks) {}

    private static final String JUNGLE_TEMPLE_SET = "minecraft:jungle_temples";
    private static final int JUNGLE_TEMPLE_RADIUS = 1;
    private static final int JUNGLE_TEMPLE_WIDTH = 12;
    private static final int JUNGLE_TEMPLE_DEPTH = 15;

    private void placeJungleTemples(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int r = JUNGLE_TEMPLE_RADIUS;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ccx = chunkX + dx, ccz = chunkZ + dz;
                if (!placement.isStructureChunk(JUNGLE_TEMPLE_SET, ccx, ccz)) continue;
                String key = ccx + ":" + ccz;
                JTPiece p = jungleTempleStarts.containsKey(key) ? jungleTempleStarts.get(key) : assembleJungleTemple(ccx, ccz);
                jungleTempleStarts.put(key, p);
                if (p == null) continue;
                if (p.maxX() < minX || p.minX() > maxX || p.maxZ() < minZ || p.minZ() > maxZ) continue;
                for (JTBlock b : p.blocks()) {
                    int wx = jtWorldX(p, b.x(), b.z()), wz = jtWorldZ(p, b.x(), b.z());
                    if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                    Block block = b.block();
                    if (b.rotateFacing()) {
                        String f = block.getProperty("facing");
                        if (f != null) block = block.withProperty("facing", jtFacing(f, p.dir()));
                    }
                    canvas.set(wx, p.minY() + b.y(), wz, block);
                    String bk = block.key().value();
                    if (bk.equals("chest")) {
                        dev.pointofpressure.minecom.blocks.Containers.registerLoot(
                                new net.minestom.server.coordinate.Vec(wx, p.minY() + b.y(), wz),
                                "minecraft:chests/jungle_temple");
                    } else if (bk.equals("dispenser")) {
                        dev.pointofpressure.minecom.blocks.Containers.registerLoot(
                                new net.minestom.server.coordinate.Vec(wx, p.minY() + b.y(), wz),
                                "minecraft:chests/jungle_temple_dispenser");
                    }
                }
            }
        }
    }

    /** Test hook: {dirOrdinal(NORTH=0/SOUTH=1/WEST=2/EAST=3), minX, minY, minZ, maxX, maxY, maxZ, blockCount}, or null. */
    public int[] testJungleTempleAt(int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(JUNGLE_TEMPLE_SET, chunkX, chunkZ)) return null;
        JTPiece p = assembleJungleTemple(chunkX, chunkZ);
        if (p == null) return null;
        return new int[]{p.dir().ordinal(), p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(), p.blocks().size()};
    }

    private JTPiece assembleJungleTemple(int ccx, int ccz) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, ccx, ccz);

        int west = ccx << 4, north = ccz << 4;
        int h00 = surface.firstFreeWg(west, north) - 1;
        int h01 = surface.firstFreeWg(west, north + JUNGLE_TEMPLE_DEPTH) - 1;
        int h10 = surface.firstFreeWg(west + JUNGLE_TEMPLE_WIDTH, north) - 1;
        int h11 = surface.firstFreeWg(west + JUNGLE_TEMPLE_WIDTH, north + JUNGLE_TEMPLE_DEPTH) - 1;
        if (Math.min(Math.min(h00, h01), Math.min(h10, h11)) < seaLevel) return null;

        JTDir dir = JTDir.values()[random.nextInt(4)];
        int minX, maxX, minZ, maxZ;
        if (dir == JTDir.NORTH || dir == JTDir.SOUTH) {
            minX = west; maxX = west + JUNGLE_TEMPLE_WIDTH - 1; minZ = north; maxZ = north + JUNGLE_TEMPLE_DEPTH - 1;
        } else {
            minX = west; maxX = west + JUNGLE_TEMPLE_DEPTH - 1; minZ = north; maxZ = north + JUNGLE_TEMPLE_WIDTH - 1;
        }

        long total = 0;
        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                total += surface.firstFreeWg(x, z) - 1;
                count++;
            }
        }
        int minY = (int) (total / count);

        int midX = west + 8, midZ = north + 8;
        int midY = surface.firstFreeWg(midX, midZ) - 1;
        String biome = biomes.biomeAt(midX >> 2, midY >> 2, midZ >> 2);
        if (!jungleTempleBiomes.contains(biome)) return null;

        List<JTBlock> blocks = new ArrayList<>();
        buildJungleTempleLayout(random, blocks);
        return new JTPiece(minX, minY, minZ, maxX, minY + 9, maxZ, dir, blocks);
    }

    private static int jtWorldX(JTPiece p, int x, int z) {
        return switch (p.dir()) {
            case NORTH, SOUTH -> p.minX() + x;
            case WEST -> p.maxX() - z;
            case EAST -> p.minX() + z;
        };
    }

    private static int jtWorldZ(JTPiece p, int x, int z) {
        return switch (p.dir()) {
            case NORTH -> p.maxZ() - z;
            case SOUTH -> p.minZ() + z;
            case WEST, EAST -> p.minZ() + x;
        };
    }

    /** Same setOrientation Mirror-then-Rotate table as swamp hut's shFacing (see SHPiece javadoc); UP/DOWN pass through. */
    private static String jtFacing(String facing, JTDir dir) {
        if (facing.equals("up") || facing.equals("down")) return facing;
        String m = switch (dir) {
            case SOUTH, WEST -> switch (facing) {
                case "north" -> "south";
                case "south" -> "north";
                default -> facing;
            };
            default -> facing;
        };
        return switch (dir) {
            case WEST, EAST -> switch (m) {
                case "north" -> "east";
                case "east" -> "south";
                case "south" -> "west";
                case "west" -> "north";
                default -> m;
            };
            default -> m;
        };
    }

    private static void jtAdd(List<JTBlock> out, int x, int y, int z, Block block) {
        out.add(new JTBlock(x, y, z, block, false));
    }

    private static void jtAddFacing(List<JTBlock> out, int x, int y, int z, Block block) {
        out.add(new JTBlock(x, y, z, block, true));
    }

    private static void jtAddBox(List<JTBlock> out, int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) jtAdd(out, x, y, z, block);
    }

    /** Real iteration order (y outer, x middle, z inner) + one selector RNG draw per block, matching StructurePiece.generateBox. */
    private static void jtAddSelectorBox(List<JTBlock> out, VSurface.LegacyRandom random, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    Block block = random.nextFloat() < 0.4F ? Block.COBBLESTONE : Block.MOSSY_COBBLESTONE;
                    jtAdd(out, x, y, z, block);
                }
            }
        }
    }

    private void buildJungleTempleLayout(VSurface.LegacyRandom random, List<JTBlock> out) {
        jtAddSelectorBox(out, random, 0, -4, 0, 11, 0, 14);
        jtAddSelectorBox(out, random, 2, 1, 2, 9, 2, 2);
        jtAddSelectorBox(out, random, 2, 1, 12, 9, 2, 12);
        jtAddSelectorBox(out, random, 2, 1, 3, 2, 2, 11);
        jtAddSelectorBox(out, random, 9, 1, 3, 9, 2, 11);
        jtAddSelectorBox(out, random, 1, 3, 1, 10, 6, 1);
        jtAddSelectorBox(out, random, 1, 3, 13, 10, 6, 13);
        jtAddSelectorBox(out, random, 1, 3, 2, 1, 6, 12);
        jtAddSelectorBox(out, random, 10, 3, 2, 10, 6, 12);
        jtAddSelectorBox(out, random, 2, 3, 2, 9, 3, 12);
        jtAddSelectorBox(out, random, 2, 6, 2, 9, 6, 12);
        jtAddSelectorBox(out, random, 3, 7, 3, 8, 7, 11);
        jtAddSelectorBox(out, random, 4, 8, 4, 7, 8, 10);
        jtAddBox(out, 3, 1, 3, 8, 2, 11, Block.AIR);
        jtAddBox(out, 4, 3, 6, 7, 3, 9, Block.AIR);
        jtAddBox(out, 2, 4, 2, 9, 5, 12, Block.AIR);
        jtAddBox(out, 4, 6, 5, 7, 6, 9, Block.AIR);
        jtAddBox(out, 5, 7, 6, 6, 7, 8, Block.AIR);
        jtAddBox(out, 5, 1, 2, 6, 2, 2, Block.AIR);
        jtAddBox(out, 5, 2, 12, 6, 2, 12, Block.AIR);
        jtAddBox(out, 5, 5, 1, 6, 5, 1, Block.AIR);
        jtAddBox(out, 5, 5, 13, 6, 5, 13, Block.AIR);
        jtAdd(out, 1, 5, 5, Block.AIR);
        jtAdd(out, 10, 5, 5, Block.AIR);
        jtAdd(out, 1, 5, 9, Block.AIR);
        jtAdd(out, 10, 5, 9, Block.AIR);

        for (int z = 0; z <= 14; z += 14) {
            jtAddSelectorBox(out, random, 2, 4, z, 2, 5, z);
            jtAddSelectorBox(out, random, 4, 4, z, 4, 5, z);
            jtAddSelectorBox(out, random, 7, 4, z, 7, 5, z);
            jtAddSelectorBox(out, random, 9, 4, z, 9, 5, z);
        }
        jtAddSelectorBox(out, random, 5, 6, 0, 6, 6, 0);
        for (int x = 0; x <= 11; x += 11) {
            for (int z = 2; z <= 12; z += 2) jtAddSelectorBox(out, random, x, 4, z, x, 5, z);
            jtAddSelectorBox(out, random, x, 6, 5, x, 6, 5);
            jtAddSelectorBox(out, random, x, 6, 9, x, 6, 9);
        }
        jtAddSelectorBox(out, random, 2, 7, 2, 2, 9, 2);
        jtAddSelectorBox(out, random, 9, 7, 2, 9, 9, 2);
        jtAddSelectorBox(out, random, 2, 7, 12, 2, 9, 12);
        jtAddSelectorBox(out, random, 9, 7, 12, 9, 9, 12);
        jtAddSelectorBox(out, random, 4, 9, 4, 4, 9, 4);
        jtAddSelectorBox(out, random, 7, 9, 4, 7, 9, 4);
        jtAddSelectorBox(out, random, 4, 9, 10, 4, 9, 10);
        jtAddSelectorBox(out, random, 7, 9, 10, 7, 9, 10);
        jtAddSelectorBox(out, random, 5, 9, 7, 6, 9, 7);

        Block eastStairs = Block.COBBLESTONE_STAIRS.withProperty("facing", "east");
        Block westStairs = Block.COBBLESTONE_STAIRS.withProperty("facing", "west");
        Block southStairs = Block.COBBLESTONE_STAIRS.withProperty("facing", "south");
        Block northStairs = Block.COBBLESTONE_STAIRS.withProperty("facing", "north");
        jtAddFacing(out, 5, 9, 6, northStairs);
        jtAddFacing(out, 6, 9, 6, northStairs);
        jtAddFacing(out, 5, 9, 8, southStairs);
        jtAddFacing(out, 6, 9, 8, southStairs);
        jtAddFacing(out, 4, 0, 0, northStairs);
        jtAddFacing(out, 5, 0, 0, northStairs);
        jtAddFacing(out, 6, 0, 0, northStairs);
        jtAddFacing(out, 7, 0, 0, northStairs);
        jtAddFacing(out, 4, 1, 8, northStairs);
        jtAddFacing(out, 4, 2, 9, northStairs);
        jtAddFacing(out, 4, 3, 10, northStairs);
        jtAddFacing(out, 7, 1, 8, northStairs);
        jtAddFacing(out, 7, 2, 9, northStairs);
        jtAddFacing(out, 7, 3, 10, northStairs);
        jtAddSelectorBox(out, random, 4, 1, 9, 4, 1, 9);
        jtAddSelectorBox(out, random, 7, 1, 9, 7, 1, 9);
        jtAddSelectorBox(out, random, 4, 1, 10, 7, 2, 10);
        jtAddSelectorBox(out, random, 5, 4, 5, 6, 4, 5);
        jtAddFacing(out, 4, 4, 5, eastStairs);
        jtAddFacing(out, 7, 4, 5, westStairs);

        for (int i = 0; i < 4; i++) {
            jtAddFacing(out, 5, -i, 6 + i, southStairs);
            jtAddFacing(out, 6, -i, 6 + i, southStairs);
            jtAddBox(out, 5, -i, 7 + i, 6, -i, 9 + i, Block.AIR);
        }
        jtAddBox(out, 1, -3, 12, 10, -1, 13, Block.AIR);
        jtAddBox(out, 1, -3, 1, 3, -1, 13, Block.AIR);
        jtAddBox(out, 1, -3, 1, 9, -1, 5, Block.AIR);
        for (int z = 1; z <= 13; z += 2) jtAddSelectorBox(out, random, 1, -3, z, 1, -2, z);
        for (int z = 2; z <= 12; z += 2) jtAddSelectorBox(out, random, 1, -1, z, 3, -1, z);
        jtAddSelectorBox(out, random, 2, -2, 1, 5, -2, 1);
        jtAddSelectorBox(out, random, 7, -2, 1, 9, -2, 1);
        jtAddSelectorBox(out, random, 6, -3, 1, 6, -3, 1);
        jtAddSelectorBox(out, random, 6, -1, 1, 6, -1, 1);

        jtAddFacing(out, 1, -3, 8, Block.TRIPWIRE_HOOK.withProperty("facing", "east").withProperty("attached", "true"));
        jtAddFacing(out, 4, -3, 8, Block.TRIPWIRE_HOOK.withProperty("facing", "west").withProperty("attached", "true"));
        Block tripwireEW = Block.TRIPWIRE.withProperty("east", "true").withProperty("west", "true").withProperty("attached", "true");
        jtAdd(out, 2, -3, 8, tripwireEW);
        jtAdd(out, 3, -3, 8, tripwireEW);
        Block redstoneWireNS = Block.REDSTONE_WIRE.withProperty("north", "side").withProperty("south", "side");
        jtAdd(out, 5, -3, 7, redstoneWireNS);
        jtAdd(out, 5, -3, 6, redstoneWireNS);
        jtAdd(out, 5, -3, 5, redstoneWireNS);
        jtAdd(out, 5, -3, 4, redstoneWireNS);
        jtAdd(out, 5, -3, 3, redstoneWireNS);
        jtAdd(out, 5, -3, 2, redstoneWireNS);
        jtAdd(out, 5, -3, 1, Block.REDSTONE_WIRE.withProperty("north", "side").withProperty("west", "side"));
        jtAdd(out, 4, -3, 1, Block.REDSTONE_WIRE.withProperty("east", "side").withProperty("west", "side"));
        jtAdd(out, 3, -3, 1, Block.MOSSY_COBBLESTONE);
        jtAddFacing(out, 3, -2, 1, Block.DISPENSER.withProperty("facing", "north"));
        jtAdd(out, 3, -2, 2, Block.VINE.withProperty("south", "true"));
        jtAddFacing(out, 7, -3, 1, Block.TRIPWIRE_HOOK.withProperty("facing", "north").withProperty("attached", "true"));
        jtAddFacing(out, 7, -3, 5, Block.TRIPWIRE_HOOK.withProperty("facing", "south").withProperty("attached", "true"));
        Block tripwireNS = Block.TRIPWIRE.withProperty("north", "true").withProperty("south", "true").withProperty("attached", "true");
        jtAdd(out, 7, -3, 2, tripwireNS);
        jtAdd(out, 7, -3, 3, tripwireNS);
        jtAdd(out, 7, -3, 4, tripwireNS);
        jtAdd(out, 8, -3, 6, Block.REDSTONE_WIRE.withProperty("east", "side").withProperty("west", "side"));
        jtAdd(out, 9, -3, 6, Block.REDSTONE_WIRE.withProperty("west", "side").withProperty("south", "side"));
        jtAdd(out, 9, -3, 5, Block.REDSTONE_WIRE.withProperty("north", "side").withProperty("south", "up"));
        jtAdd(out, 9, -3, 4, Block.MOSSY_COBBLESTONE);
        jtAdd(out, 9, -2, 4, redstoneWireNS);
        jtAddFacing(out, 9, -2, 3, Block.DISPENSER.withProperty("facing", "west"));
        jtAdd(out, 8, -1, 3, Block.VINE.withProperty("east", "true"));
        jtAdd(out, 8, -2, 3, Block.VINE.withProperty("east", "true"));
        jtAddFacing(out, 8, -3, 3, Block.CHEST.withProperty("facing", "north"));
        jtAdd(out, 9, -3, 2, Block.MOSSY_COBBLESTONE);
        jtAdd(out, 8, -3, 1, Block.MOSSY_COBBLESTONE);
        jtAdd(out, 4, -3, 5, Block.MOSSY_COBBLESTONE);
        jtAdd(out, 5, -2, 5, Block.MOSSY_COBBLESTONE);
        jtAdd(out, 5, -1, 5, Block.MOSSY_COBBLESTONE);
        jtAdd(out, 6, -3, 5, Block.MOSSY_COBBLESTONE);
        jtAdd(out, 7, -2, 5, Block.MOSSY_COBBLESTONE);
        jtAdd(out, 7, -1, 5, Block.MOSSY_COBBLESTONE);
        jtAdd(out, 8, -3, 5, Block.MOSSY_COBBLESTONE);
        jtAddSelectorBox(out, random, 9, -1, 1, 9, -1, 5);
        jtAddBox(out, 8, -3, 8, 10, -1, 10, Block.AIR);
        jtAdd(out, 8, -2, 11, Block.CHISELED_STONE_BRICKS);
        jtAdd(out, 9, -2, 11, Block.CHISELED_STONE_BRICKS);
        jtAdd(out, 10, -2, 11, Block.CHISELED_STONE_BRICKS);
        Block lever = Block.LEVER.withProperty("facing", "north").withProperty("face", "wall");
        jtAddFacing(out, 8, -2, 12, lever);
        jtAddFacing(out, 9, -2, 12, lever);
        jtAddFacing(out, 10, -2, 12, lever);
        jtAddSelectorBox(out, random, 8, -3, 8, 8, -3, 10);
        jtAddSelectorBox(out, random, 10, -3, 8, 10, -3, 10);
        jtAdd(out, 10, -2, 9, Block.MOSSY_COBBLESTONE);
        jtAdd(out, 8, -2, 9, redstoneWireNS);
        jtAdd(out, 8, -2, 10, redstoneWireNS);
        jtAdd(out, 10, -1, 9, Block.REDSTONE_WIRE.withProperty("north", "side").withProperty("south", "side")
                .withProperty("east", "side").withProperty("west", "side"));
        jtAddFacing(out, 9, -2, 8, Block.STICKY_PISTON.withProperty("facing", "up"));
        jtAddFacing(out, 10, -2, 8, Block.STICKY_PISTON.withProperty("facing", "west"));
        jtAddFacing(out, 10, -1, 8, Block.STICKY_PISTON.withProperty("facing", "west"));
        jtAddFacing(out, 10, -2, 10, Block.REPEATER.withProperty("facing", "north"));
        jtAddFacing(out, 9, -3, 10, Block.CHEST.withProperty("facing", "north"));
    }

    // ------------------------------------------------------------------ desert pyramid

    /**
     * Fully hand-coded direct block placement (`ScatteredFeaturePiece`/`StructurePiece`,
     * decompiled from `DesertPyramidStructure`/`DesertPyramidPiece`/`SinglePieceStructure`)
     * — the largest structure this project generates (21x15x21), reusing the same
     * precompute-once-into-a-cached-list architecture and `getWorldX/Y/Z` coordinate model
     * as jungle temple/swamp hut. A stepped sandstone pyramid over a below-ground TNT trap
     * room (pressure plate wired to 9 TNT blocks) and a 4-chest hidden cellar reached via a
     * short external staircase. Two DELIBERATE, DOCUMENTED divergences from real vanilla's
     * exact RNG mechanics, both low-stakes (cosmetic only, never affect structure shape or
     * block positions): (1) real vanilla's `addCellarStairs` variant pick and
     * `placeCollapsedRoofPiece`'s per-block sand-vs-sandstone roll both use
     * `level.getRandom()` — the server's own MUTABLE, non-seed-derived random state, NOT the
     * deterministic per-structure random — meaning even real vanilla has no fixed,
     * reproducible answer here; this project always picks the same fixed choice instead
     * (documented, not a fidelity loss since there was nothing bit-exact to match in the
     * first place). (2) `placeCollapsedRoofPiece`'s exact roof block AND the entire
     * `afterPlace` suspicious-sand archaeology pass (which decides which of the "potential
     * suspicious sand" positions collected during placement become `SUSPICIOUS_SAND` with
     * real archaeology loot vs. plain `SAND`) both use `RandomSource.createThreadLocalInstance
     * (worldSeed).forkPositional()` — a positional-random system built on a fundamentally
     * different RNG algorithm (Xoroshiro-based) than the legacy LCG `LegacyRandom` this
     * project implements everywhere else; porting it would mean adding a whole second RNG
     * engine for one archaeology-loot cosmetic detail, so — matching this project's
     * established precedent of skipping loot-table CONTENTS while still placing the
     * container blocks themselves (ruined portal/mineshaft/igloo/jungle temple chests) —
     * every "potential suspicious sand" position is placed as plain `SAND` directly, and the
     * collapsed-roof holes are always `SAND` (not the ~33% `SANDSTONE` mix). The ONE real,
     * structurally-meaningful RNG draw (`updateHeightPositionToLowestGroundHeight`'s
     * `-random.nextInt(3)` Y-offset, which shifts the WHOLE structure) and the 4 corner
     * `createChest` calls' loot-seed draws (content skipped, matching precedent, but the
     * draw itself doesn't matter since nothing downstream reads it) both continue from the
     * SAME single per-chunk `LegacyRandom` stream used for the direction draw — real vanilla
     * technically reseeds `postProcess`'s random independently per decorated chunk (a
     * decoration-time reseed, distinct from the piece-construction-time stream the direction
     * draw uses), a nuance this project intentionally does not replicate for any of its
     * hand-coded `ScatteredFeaturePiece` structures (swamp hut has no postProcess RNG at
     * all; jungle temple's moss-selector texture and this height offset are the only two
     * postProcess draws across all 3 structures) since none of these values feed anything
     * outside their own leaf structure — the divergence, if real, only affects a small
     * integer Y-offset and cosmetic block texturing, never structure shape or reachability.
     * `skipAir` (real vanilla's "only place into non-air") is treated as always-true for the
     * few cellar-room wall calls that use it, since those walls sit inside the solid base
     * platform this project already places first, matching the overwhelming common case.
     * Chest reorientation (`StructurePiece.reorient`) is skipped — all 4 corner chests use a
     * fixed NORTH facing, matching jungle temple's identical simplification.
     */
    private enum DPDir { NORTH, SOUTH, WEST, EAST }

    private record DPBlock(int x, int y, int z, Block block, boolean rotateFacing) {}

    private record DPPiece(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, DPDir dir, List<DPBlock> blocks) {}

    private static final String DESERT_PYRAMID_SET = "minecraft:desert_pyramids";
    private static final int DESERT_PYRAMID_RADIUS = 2;
    private static final int DESERT_PYRAMID_WIDTH = 21;
    private static final int DESERT_PYRAMID_DEPTH = 21;

    private void placeDesertPyramids(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int r = DESERT_PYRAMID_RADIUS;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ccx = chunkX + dx, ccz = chunkZ + dz;
                if (!placement.isStructureChunk(DESERT_PYRAMID_SET, ccx, ccz)) continue;
                String key = ccx + ":" + ccz;
                DPPiece p = desertPyramidStarts.containsKey(key) ? desertPyramidStarts.get(key) : assembleDesertPyramid(ccx, ccz);
                desertPyramidStarts.put(key, p);
                if (p == null) continue;
                if (p.maxX() < minX || p.minX() > maxX || p.maxZ() < minZ || p.minZ() > maxZ) continue;
                for (DPBlock b : p.blocks()) {
                    int wx = dpWorldX(p, b.x(), b.z()), wz = dpWorldZ(p, b.x(), b.z());
                    if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                    Block block = b.block();
                    if (b.rotateFacing()) {
                        String f = block.getProperty("facing");
                        if (f != null) block = block.withProperty("facing", dpFacing(f, p.dir()));
                    }
                    canvas.set(wx, p.minY() + b.y(), wz, block);
                    if (block.key().value().equals("chest")) {
                        dev.pointofpressure.minecom.blocks.Containers.registerLoot(
                                new net.minestom.server.coordinate.Vec(wx, p.minY() + b.y(), wz),
                                "minecraft:chests/desert_pyramid");
                    }
                }
            }
        }
    }

    /** Test hook: {dirOrdinal(NORTH=0/SOUTH=1/WEST=2/EAST=3), minX, minY, minZ, maxX, maxY, maxZ, blockCount}, or null. */
    public int[] testDesertPyramidAt(int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(DESERT_PYRAMID_SET, chunkX, chunkZ)) return null;
        DPPiece p = assembleDesertPyramid(chunkX, chunkZ);
        if (p == null) return null;
        return new int[]{p.dir().ordinal(), p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(), p.blocks().size()};
    }

    private DPPiece assembleDesertPyramid(int ccx, int ccz) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, ccx, ccz);

        int west = ccx << 4, north = ccz << 4;
        int h00 = surface.firstFreeWg(west, north) - 1;
        int h01 = surface.firstFreeWg(west, north + DESERT_PYRAMID_DEPTH) - 1;
        int h10 = surface.firstFreeWg(west + DESERT_PYRAMID_WIDTH, north) - 1;
        int h11 = surface.firstFreeWg(west + DESERT_PYRAMID_WIDTH, north + DESERT_PYRAMID_DEPTH) - 1;
        if (Math.min(Math.min(h00, h01), Math.min(h10, h11)) < seaLevel) return null;

        DPDir dir = DPDir.values()[random.nextInt(4)];
        int minX = west, maxX = west + DESERT_PYRAMID_WIDTH - 1, minZ = north, maxZ = north + DESERT_PYRAMID_DEPTH - 1;

        int lowest = Integer.MAX_VALUE;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) lowest = Math.min(lowest, surface.firstFreeWg(x, z) - 1);
        }
        int minY = lowest - random.nextInt(3);

        int midX = west + 8, midZ = north + 8;
        int midY = surface.firstFreeWg(midX, midZ) - 1;
        String biome = biomes.biomeAt(midX >> 2, midY >> 2, midZ >> 2);
        if (!desertPyramidBiomes.contains(biome)) return null;

        List<DPBlock> blocks = new ArrayList<>();
        buildDesertPyramidLayout(blocks);
        return new DPPiece(minX, minY, minZ, maxX, minY + 14, maxZ, dir, blocks);
    }

    private static int dpWorldX(DPPiece p, int x, int z) {
        return switch (p.dir()) {
            case NORTH, SOUTH -> p.minX() + x;
            case WEST -> p.maxX() - z;
            case EAST -> p.minX() + z;
        };
    }

    private static int dpWorldZ(DPPiece p, int x, int z) {
        return switch (p.dir()) {
            case NORTH -> p.maxZ() - z;
            case SOUTH -> p.minZ() + z;
            case WEST, EAST -> p.minZ() + x;
        };
    }

    /** Same setOrientation Mirror-then-Rotate table as swamp hut/jungle temple; UP/DOWN pass through. */
    private static String dpFacing(String facing, DPDir dir) {
        if (facing.equals("up") || facing.equals("down")) return facing;
        String m = switch (dir) {
            case SOUTH, WEST -> switch (facing) {
                case "north" -> "south";
                case "south" -> "north";
                default -> facing;
            };
            default -> facing;
        };
        return switch (dir) {
            case WEST, EAST -> switch (m) {
                case "north" -> "east";
                case "east" -> "south";
                case "south" -> "west";
                case "west" -> "north";
                default -> m;
            };
            default -> m;
        };
    }

    private static void dpAdd(List<DPBlock> out, int x, int y, int z, Block block) {
        out.add(new DPBlock(x, y, z, block, false));
    }

    private static void dpAddFacing(List<DPBlock> out, int x, int y, int z, Block block) {
        out.add(new DPBlock(x, y, z, block, true));
    }

    private static void dpAddBox(List<DPBlock> out, int x0, int y0, int z0, int x1, int y1, int z1, Block edge, Block fill) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean interior = y != y0 && y != y1 && x != x0 && x != x1 && z != z0 && z != z1;
                    dpAdd(out, x, y, z, interior ? fill : edge);
                }
            }
        }
    }

    private void buildDesertPyramidLayout(List<DPBlock> out) {
        Block sandstone = Block.SANDSTONE, air = Block.AIR, cut = Block.CUT_SANDSTONE, chiseled = Block.CHISELED_SANDSTONE;
        dpAddBox(out, 0, -4, 0, DESERT_PYRAMID_WIDTH - 1, 0, DESERT_PYRAMID_DEPTH - 1, sandstone, sandstone);
        for (int pos = 1; pos <= 9; pos++) {
            dpAddBox(out, pos, pos, pos, DESERT_PYRAMID_WIDTH - 1 - pos, pos, DESERT_PYRAMID_DEPTH - 1 - pos, sandstone, sandstone);
            dpAddBox(out, pos + 1, pos, pos + 1, DESERT_PYRAMID_WIDTH - 2 - pos, pos, DESERT_PYRAMID_DEPTH - 2 - pos, air, air);
        }
        // fillColumnDown support pillars skipped (terrain-read-dependent; matches mineshaft/swamp-hut precedent).

        int w = DESERT_PYRAMID_WIDTH;
        Block northStairs = Block.SANDSTONE_STAIRS.withProperty("facing", "north");
        Block southStairs = Block.SANDSTONE_STAIRS.withProperty("facing", "south");
        Block eastStairs = Block.SANDSTONE_STAIRS.withProperty("facing", "east");
        Block westStairs = Block.SANDSTONE_STAIRS.withProperty("facing", "west");
        dpAddBox(out, 0, 0, 0, 4, 9, 4, sandstone, air);
        dpAddBox(out, 1, 10, 1, 3, 10, 3, sandstone, sandstone);
        dpAddFacing(out, 2, 10, 0, northStairs);
        dpAddFacing(out, 2, 10, 4, southStairs);
        dpAddFacing(out, 0, 10, 2, eastStairs);
        dpAddFacing(out, 4, 10, 2, westStairs);
        dpAddBox(out, w - 5, 0, 0, w - 1, 9, 4, sandstone, air);
        dpAddBox(out, w - 4, 10, 1, w - 2, 10, 3, sandstone, sandstone);
        dpAddFacing(out, w - 3, 10, 0, northStairs);
        dpAddFacing(out, w - 3, 10, 4, southStairs);
        dpAddFacing(out, w - 5, 10, 2, eastStairs);
        dpAddFacing(out, w - 1, 10, 2, westStairs);
        dpAddBox(out, 8, 0, 0, 12, 4, 4, sandstone, air);
        dpAddBox(out, 9, 1, 0, 11, 3, 4, air, air);
        dpAdd(out, 9, 1, 1, cut);
        dpAdd(out, 9, 2, 1, cut);
        dpAdd(out, 9, 3, 1, cut);
        dpAdd(out, 10, 3, 1, cut);
        dpAdd(out, 11, 3, 1, cut);
        dpAdd(out, 11, 2, 1, cut);
        dpAdd(out, 11, 1, 1, cut);
        dpAddBox(out, 4, 1, 1, 8, 3, 3, sandstone, air);
        dpAddBox(out, 4, 1, 2, 8, 2, 2, air, air);
        dpAddBox(out, 12, 1, 1, 16, 3, 3, sandstone, air);
        dpAddBox(out, 12, 1, 2, 16, 2, 2, air, air);
        dpAddBox(out, 5, 4, 5, w - 6, 4, w - 6, sandstone, sandstone);
        dpAddBox(out, 9, 4, 9, 11, 4, 11, air, air);
        dpAddBox(out, 8, 1, 8, 8, 3, 8, cut, cut);
        dpAddBox(out, 12, 1, 8, 12, 3, 8, cut, cut);
        dpAddBox(out, 8, 1, 12, 8, 3, 12, cut, cut);
        dpAddBox(out, 12, 1, 12, 12, 3, 12, cut, cut);
        dpAddBox(out, 1, 1, 5, 4, 4, 11, sandstone, sandstone);
        dpAddBox(out, w - 5, 1, 5, w - 2, 4, 11, sandstone, sandstone);
        dpAddBox(out, 6, 7, 9, 6, 7, 11, sandstone, sandstone);
        dpAddBox(out, w - 7, 7, 9, w - 7, 7, 11, sandstone, sandstone);
        dpAddBox(out, 5, 5, 9, 5, 7, 11, cut, cut);
        dpAddBox(out, w - 6, 5, 9, w - 6, 7, 11, cut, cut);
        dpAdd(out, 5, 5, 10, air);
        dpAdd(out, 5, 6, 10, air);
        dpAdd(out, 6, 6, 10, air);
        dpAdd(out, w - 6, 5, 10, air);
        dpAdd(out, w - 6, 6, 10, air);
        dpAdd(out, w - 7, 6, 10, air);
        dpAddBox(out, 2, 4, 4, 2, 6, 4, air, air);
        dpAddBox(out, w - 3, 4, 4, w - 3, 6, 4, air, air);
        dpAddFacing(out, 2, 4, 5, northStairs);
        dpAddFacing(out, 2, 3, 4, northStairs);
        dpAddFacing(out, w - 3, 4, 5, northStairs);
        dpAddFacing(out, w - 3, 3, 4, northStairs);
        dpAddBox(out, 1, 1, 3, 2, 2, 3, sandstone, sandstone);
        dpAddBox(out, w - 3, 1, 3, w - 2, 2, 3, sandstone, sandstone);
        dpAdd(out, 1, 1, 2, sandstone);
        dpAdd(out, w - 2, 1, 2, sandstone);
        dpAdd(out, 1, 2, 2, Block.SANDSTONE_SLAB);
        dpAdd(out, w - 2, 2, 2, Block.SANDSTONE_SLAB);
        dpAddFacing(out, 2, 1, 2, westStairs);
        dpAddFacing(out, w - 3, 1, 2, eastStairs);
        dpAddBox(out, 4, 3, 5, 4, 3, 17, sandstone, sandstone);
        dpAddBox(out, w - 5, 3, 5, w - 5, 3, 17, sandstone, sandstone);
        dpAddBox(out, 3, 1, 5, 4, 2, 16, air, air);
        dpAddBox(out, w - 6, 1, 5, w - 5, 2, 16, air, air);
        for (int z = 5; z <= 17; z += 2) {
            dpAdd(out, 4, 1, z, cut);
            dpAdd(out, 4, 2, z, chiseled);
            dpAdd(out, w - 5, 1, z, cut);
            dpAdd(out, w - 5, 2, z, chiseled);
        }
        Block orange = Block.ORANGE_TERRACOTTA, blue = Block.BLUE_TERRACOTTA;
        dpAdd(out, 10, 0, 7, orange);
        dpAdd(out, 10, 0, 8, orange);
        dpAdd(out, 9, 0, 9, orange);
        dpAdd(out, 11, 0, 9, orange);
        dpAdd(out, 8, 0, 10, orange);
        dpAdd(out, 12, 0, 10, orange);
        dpAdd(out, 7, 0, 10, orange);
        dpAdd(out, 13, 0, 10, orange);
        dpAdd(out, 9, 0, 11, orange);
        dpAdd(out, 11, 0, 11, orange);
        dpAdd(out, 10, 0, 12, orange);
        dpAdd(out, 10, 0, 13, orange);
        dpAdd(out, 10, 0, 10, blue);

        for (int x = 0; x <= w - 1; x += w - 1) {
            dpAdd(out, x, 2, 1, cut);
            dpAdd(out, x, 2, 2, orange);
            dpAdd(out, x, 2, 3, cut);
            dpAdd(out, x, 3, 1, cut);
            dpAdd(out, x, 3, 2, orange);
            dpAdd(out, x, 3, 3, cut);
            dpAdd(out, x, 4, 1, orange);
            dpAdd(out, x, 4, 2, chiseled);
            dpAdd(out, x, 4, 3, orange);
            dpAdd(out, x, 5, 1, cut);
            dpAdd(out, x, 5, 2, orange);
            dpAdd(out, x, 5, 3, cut);
            dpAdd(out, x, 6, 1, orange);
            dpAdd(out, x, 6, 2, chiseled);
            dpAdd(out, x, 6, 3, orange);
            dpAdd(out, x, 7, 1, orange);
            dpAdd(out, x, 7, 2, orange);
            dpAdd(out, x, 7, 3, orange);
            dpAdd(out, x, 8, 1, cut);
            dpAdd(out, x, 8, 2, cut);
            dpAdd(out, x, 8, 3, cut);
        }
        for (int x = 2; x <= w - 3; x += w - 3 - 2) {
            dpAdd(out, x - 1, 2, 0, cut);
            dpAdd(out, x, 2, 0, orange);
            dpAdd(out, x + 1, 2, 0, cut);
            dpAdd(out, x - 1, 3, 0, cut);
            dpAdd(out, x, 3, 0, orange);
            dpAdd(out, x + 1, 3, 0, cut);
            dpAdd(out, x - 1, 4, 0, orange);
            dpAdd(out, x, 4, 0, chiseled);
            dpAdd(out, x + 1, 4, 0, orange);
            dpAdd(out, x - 1, 5, 0, cut);
            dpAdd(out, x, 5, 0, orange);
            dpAdd(out, x + 1, 5, 0, cut);
            dpAdd(out, x - 1, 6, 0, orange);
            dpAdd(out, x, 6, 0, chiseled);
            dpAdd(out, x + 1, 6, 0, orange);
            dpAdd(out, x - 1, 7, 0, orange);
            dpAdd(out, x, 7, 0, orange);
            dpAdd(out, x + 1, 7, 0, orange);
            dpAdd(out, x - 1, 8, 0, cut);
            dpAdd(out, x, 8, 0, cut);
            dpAdd(out, x + 1, 8, 0, cut);
        }
        dpAddBox(out, 8, 4, 0, 12, 6, 0, cut, cut);
        dpAdd(out, 8, 6, 0, air);
        dpAdd(out, 12, 6, 0, air);
        dpAdd(out, 9, 5, 0, orange);
        dpAdd(out, 10, 5, 0, chiseled);
        dpAdd(out, 11, 5, 0, orange);

        dpAddBox(out, 8, -14, 8, 12, -11, 12, cut, cut);
        dpAddBox(out, 8, -10, 8, 12, -10, 12, chiseled, chiseled);
        dpAddBox(out, 8, -9, 8, 12, -9, 12, cut, cut);
        dpAddBox(out, 8, -8, 8, 12, -1, 12, sandstone, sandstone);
        dpAddBox(out, 9, -11, 9, 11, -1, 11, air, air);
        dpAdd(out, 10, -11, 10, Block.STONE_PRESSURE_PLATE);
        dpAddBox(out, 9, -13, 9, 11, -13, 11, Block.TNT, air);
        dpAdd(out, 8, -11, 10, air);
        dpAdd(out, 8, -10, 10, air);
        dpAdd(out, 7, -10, 10, chiseled);
        dpAdd(out, 7, -11, 10, cut);
        dpAdd(out, 12, -11, 10, air);
        dpAdd(out, 12, -10, 10, air);
        dpAdd(out, 13, -10, 10, chiseled);
        dpAdd(out, 13, -11, 10, cut);
        dpAdd(out, 10, -11, 8, air);
        dpAdd(out, 10, -10, 8, air);
        dpAdd(out, 10, -10, 7, chiseled);
        dpAdd(out, 10, -11, 7, cut);
        dpAdd(out, 10, -11, 12, air);
        dpAdd(out, 10, -10, 12, air);
        dpAdd(out, 10, -10, 13, chiseled);
        dpAdd(out, 10, -11, 13, cut);

        // 4 corner chests (Plane.HORIZONTAL order NORTH/SOUTH/WEST/EAST); reorientation skipped (fixed NORTH facing, matches jungle temple).
        dpAddFacing(out, 10, -11, 8, Block.CHEST.withProperty("facing", "north"));
        dpAddFacing(out, 10, -11, 12, Block.CHEST.withProperty("facing", "north"));
        dpAddFacing(out, 8, -11, 10, Block.CHEST.withProperty("facing", "north"));
        dpAddFacing(out, 12, -11, 10, Block.CHEST.withProperty("facing", "north"));

        buildDesertPyramidCellar(out, cut, chiseled, orange, blue);
    }

    private void buildDesertPyramidCellar(List<DPBlock> out, Block cut, Block chiseled, Block orange, Block blue) {
        int x = 16, y = -4, z = 13; // roomCenter
        dpAddFacing(out, 13, -1, 17, Block.SANDSTONE_STAIRS.withProperty("facing", "west"));
        dpAddFacing(out, 14, -2, 17, Block.SANDSTONE_STAIRS.withProperty("facing", "west"));
        dpAddFacing(out, 15, -3, 17, Block.SANDSTONE_STAIRS.withProperty("facing", "west"));
        Block sand = Block.SAND, sandstone = Block.SANDSTONE;
        dpAdd(out, x - 4, y + 4, z + 4, sand);
        dpAdd(out, x - 3, y + 4, z + 4, sand);
        dpAdd(out, x - 2, y + 4, z + 4, sand);
        dpAdd(out, x - 1, y + 4, z + 4, sand);
        dpAdd(out, x, y + 4, z + 4, sand);
        dpAdd(out, x - 2, y + 3, z + 4, sand);
        dpAdd(out, x - 1, y + 3, z + 4, sand); // real vanilla: level.getRandom() variant, non-reproducible even in vanilla (see javadoc)
        dpAdd(out, x, y + 3, z + 4, sandstone);
        dpAdd(out, x - 1, y + 2, z + 4, sand);
        dpAdd(out, x, y + 2, z + 4, sandstone);
        dpAdd(out, x, y + 1, z + 4, sand);

        dpAddBox(out, x - 3, y + 1, z - 3, x - 3, y + 1, z + 2, cut, cut);
        dpAddBox(out, x + 3, y + 1, z - 3, x + 3, y + 1, z + 2, cut, cut);
        dpAddBox(out, x - 3, y + 1, z - 3, x + 3, y + 1, z - 2, cut, cut);
        dpAddBox(out, x - 3, y + 1, z + 3, x + 3, y + 1, z + 3, cut, cut);
        dpAddBox(out, x - 3, y + 2, z - 3, x - 3, y + 2, z + 2, chiseled, chiseled);
        dpAddBox(out, x + 3, y + 2, z - 3, x + 3, y + 2, z + 2, chiseled, chiseled);
        dpAddBox(out, x - 3, y + 2, z - 3, x + 3, y + 2, z - 2, chiseled, chiseled);
        dpAddBox(out, x - 3, y + 2, z + 3, x + 3, y + 2, z + 3, chiseled, chiseled);
        dpAddBox(out, x - 3, -1, z - 3, x - 3, -1, z + 2, cut, cut);
        dpAddBox(out, x + 3, -1, z - 3, x + 3, -1, z + 2, cut, cut);
        dpAddBox(out, x - 3, -1, z - 3, x + 3, -1, z - 2, cut, cut);
        dpAddBox(out, x - 3, -1, z + 3, x + 3, -1, z + 3, cut, cut);

        // placeSandBox + placeCollapsedRoof: real vanilla defers these to a positional-random
        // archaeology pass (afterPlace); this project places plain SAND directly (see javadoc).
        for (int ry = y + 1; ry <= y + 3; ry++) {
            for (int rx = x - 2; rx <= x + 2; rx++) {
                for (int rz = z - 2; rz <= z + 2; rz++) dpAdd(out, rx, ry, rz, Block.SAND);
            }
        }
        for (int rx = x - 2; rx <= x + 2; rx++) {
            for (int rz = z - 2; rz <= z + 2; rz++) dpAdd(out, rx, y + 4, rz, Block.SAND);
        }

        dpAdd(out, x, y, z, blue);
        dpAdd(out, x + 1, y, z - 1, orange);
        dpAdd(out, x + 1, y, z + 1, orange);
        dpAdd(out, x - 1, y, z - 1, orange);
        dpAdd(out, x - 1, y, z + 1, orange);
        dpAdd(out, x + 2, y, z, orange);
        dpAdd(out, x - 2, y, z, orange);
        dpAdd(out, x, y, z + 2, orange);
        dpAdd(out, x, y, z - 2, orange);
        dpAdd(out, x + 3, y, z, orange);
        dpAdd(out, x + 3, y + 1, z, Block.SAND);
        dpAdd(out, x + 3, y + 2, z, Block.SAND);
        dpAdd(out, x + 4, y + 1, z, cut);
        dpAdd(out, x + 4, y + 2, z, chiseled);
        dpAdd(out, x - 3, y, z, orange);
        dpAdd(out, x - 3, y + 1, z, Block.SAND);
        dpAdd(out, x - 3, y + 2, z, Block.SAND);
        dpAdd(out, x - 4, y + 1, z, cut);
        dpAdd(out, x - 4, y + 2, z, chiseled);
        dpAdd(out, x, y, z + 3, orange);
        dpAdd(out, x, y + 1, z + 3, Block.SAND);
        dpAdd(out, x, y + 2, z + 3, Block.SAND);
        dpAdd(out, x, y, z - 3, orange);
        dpAdd(out, x, y + 1, z - 3, Block.SAND);
        dpAdd(out, x, y + 2, z - 3, Block.SAND);
        dpAdd(out, x, y + 1, z - 4, cut);
        dpAdd(out, x, -2, z - 4, chiseled);
    }

    // ------------------------------------------------------------------ shipwreck

    /**
     * Single NBT-template structure (like ruined portal/igloo), decompiled from
     * `ShipwreckStructure`/`ShipwreckPieces`. Real vanilla's `minecraft:shipwrecks`
     * structure set weights TWO structures onto one placement grid: `minecraft:shipwreck`
     * (open-ocean, sits on `OCEAN_FLOOR_WG`, 20-template pool) and
     * `minecraft:shipwreck_beached` (sits on `WORLD_SURFACE_WG`, restricted to
     * `#minecraft:is_beach` biomes, 11-template pool) — BOTH are now wired, using the
     * `OCEAN_FLOOR_WG` accessor added for buried treasure. Both flavors sharing one
     * placement grid follows this project's established "independently try every wired
     * flavor, rely on non-overlapping biome lists" simplification (same as ruined portal's
     * flavor model — beach biomes and ocean biomes don't intersect, so nothing
     * double-places). Template selection uses a fixed pivot `(4, 0, 15)` shared by ALL 20
     * templates (unlike igloo's per-template pivot table), `Mirror.NONE` always (no mirror
     * draw at all — real vanilla's `ShipwreckPiece` never draws one, unlike ruined portal).
     * **Y placement genuinely differs between the two flavors, not just the heightmap
     * source** — decompiled precisely rather than assumed symmetric: BEACHED samples the
     * MINIMUM height across the whole footprint and feeds it through
     * `calculateBeachedPosition` (`minY - templateSizeY/2 - random.nextInt(3)`, a real RNG
     * draw that partially buries the wreck), while OCEAN uses the raw MEAN (average) height
     * across the footprint directly — `adjustPositionHeight(mean)`, ZERO RNG draw at all for
     * the ocean flavor's position. `BlockIgnoreProcessor.STRUCTURE_AND_AIR` (vs. ruined
     * portal's narrower `STRUCTURE_BLOCK`-only processor) means real vanilla does NOT carve
     * the template's own AIR blocks into existing terrain here — reimplemented by skipping
     * `minecraft:air` entries during placement, in addition to the usual
     * structure_block/structure_void skip. NOT implemented: chest loot-table CONTENTS for
     * the 3 marker types (map/treasure/supply — the physical chest blocks ARE placed via the
     * normal template block list, matching every other NBT-template structure's established
     * precedent this session), and the `isTooBigToFitInWorldGenRegion` 32-block-size
     * fallback path (none of the 20 templates approach that size, confirmed by inspecting
     * their loaded `VTemplate` dimensions for the 11 beached ones already this session — the
     * 9 additional ocean-only templates share the same naming/size family).
     */
    private record SWPiece(VTemplate template, int baseX, int originY, int baseZ, VTemplate.Rot rotation) {}

    private static final String SHIPWRECK_SET = "minecraft:shipwrecks";
    private static final int SHIPWRECK_RADIUS = 2;
    private static final int SHIPWRECK_PIVOT_X = 4, SHIPWRECK_PIVOT_Z = 15;
    private static final String[] SHIPWRECK_BEACHED_TEMPLATES = {
            "minecraft:shipwreck/with_mast", "minecraft:shipwreck/sideways_full", "minecraft:shipwreck/sideways_fronthalf",
            "minecraft:shipwreck/sideways_backhalf", "minecraft:shipwreck/rightsideup_full", "minecraft:shipwreck/rightsideup_fronthalf",
            "minecraft:shipwreck/rightsideup_backhalf", "minecraft:shipwreck/with_mast_degraded", "minecraft:shipwreck/rightsideup_full_degraded",
            "minecraft:shipwreck/rightsideup_fronthalf_degraded", "minecraft:shipwreck/rightsideup_backhalf_degraded"};
    private static final String[] SHIPWRECK_OCEAN_TEMPLATES = {
            "minecraft:shipwreck/with_mast", "minecraft:shipwreck/upsidedown_full", "minecraft:shipwreck/upsidedown_fronthalf",
            "minecraft:shipwreck/upsidedown_backhalf", "minecraft:shipwreck/sideways_full", "minecraft:shipwreck/sideways_fronthalf",
            "minecraft:shipwreck/sideways_backhalf", "minecraft:shipwreck/rightsideup_full", "minecraft:shipwreck/rightsideup_fronthalf",
            "minecraft:shipwreck/rightsideup_backhalf", "minecraft:shipwreck/with_mast_degraded", "minecraft:shipwreck/upsidedown_full_degraded",
            "minecraft:shipwreck/upsidedown_fronthalf_degraded", "minecraft:shipwreck/upsidedown_backhalf_degraded",
            "minecraft:shipwreck/sideways_full_degraded", "minecraft:shipwreck/sideways_fronthalf_degraded", "minecraft:shipwreck/sideways_backhalf_degraded",
            "minecraft:shipwreck/rightsideup_full_degraded", "minecraft:shipwreck/rightsideup_fronthalf_degraded",
            "minecraft:shipwreck/rightsideup_backhalf_degraded"};

    private void placeShipwrecks(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int r = SHIPWRECK_RADIUS;
        for (boolean isBeached : new boolean[]{true, false}) {
            if (!isBeached && oceanFloor == null) continue;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int ccx = chunkX + dx, ccz = chunkZ + dz;
                    if (!placement.isStructureChunk(SHIPWRECK_SET, ccx, ccz)) continue;
                    String key = (isBeached ? "beached:" : "ocean:") + ccx + ":" + ccz;
                    SWPiece piece = shipwreckStarts.containsKey(key) ? shipwreckStarts.get(key) : assembleShipwreck(isBeached, ccx, ccz);
                    shipwreckStarts.put(key, piece);
                    if (piece == null) continue;
                    // ShipwreckPiece.handleDataMarker: the 3 chest-flavor markers (supply/map/
                    // treasure) sit one block directly above their target chest in template-
                    // relative space (rotation only touches x/z, so this holds after rotation
                    // too) — the chest's own NBT carries no LootTable, unlike most other
                    // structures' containers (see registerContainerLoot's no-op there).
                    Map<String, String> shipwreckMarkers = new HashMap<>();
                    for (VTemplate.BlockInfo mb : piece.template().blocks) {
                        if (mb.nbt != null && mb.state.key().asString().equals("minecraft:structure_block")
                                && mb.nbt.keySet().contains("metadata")) {
                            shipwreckMarkers.put(mb.x + "," + (mb.y - 1) + "," + mb.z, mb.nbt.getString("metadata"));
                        }
                    }
                    for (VTemplate.BlockInfo b : piece.template().blocks) {
                        int[] wp = VTemplate.transform(b.x, b.y, b.z, piece.rotation(), SHIPWRECK_PIVOT_X, SHIPWRECK_PIVOT_Z);
                        int wx = wp[0] + piece.baseX(), wy = wp[1] + piece.originY(), wz = wp[2] + piece.baseZ();
                        if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                        String name = b.state.key().asString();
                        if (name.equals("minecraft:structure_block") || name.equals("minecraft:structure_void") || name.equals("minecraft:air")) continue;
                        canvas.set(wx, wy, wz, VBlockRotate.rotate(b.state, piece.rotation()));
                        registerContainerLoot(name, b.nbt, wx, wy, wz);
                        if (name.equals("minecraft:chest")) {
                            String flavor = shipwreckMarkers.get(b.x + "," + b.y + "," + b.z);
                            String table = flavor == null ? null : switch (flavor) {
                                case "supply_chest" -> "minecraft:chests/shipwreck_supply";
                                case "map_chest" -> "minecraft:chests/shipwreck_map";
                                case "treasure_chest" -> "minecraft:chests/shipwreck_treasure";
                                default -> null;
                            };
                            if (table != null) {
                                dev.pointofpressure.minecom.blocks.Containers.registerLoot(
                                        new net.minestom.server.coordinate.Vec(wx, wy, wz), table);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Test hook for the beached flavor (see the 3-arg overload for the ocean flavor). */
    public int[] testShipwreckAt(int chunkX, int chunkZ) {
        return testShipwreckAt(true, chunkX, chunkZ);
    }

    /** Test hook: {rotationOrdinal, template sizeX, sizeY, sizeZ, blockCount, originY}, or null. */
    public int[] testShipwreckAt(boolean isBeached, int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(SHIPWRECK_SET, chunkX, chunkZ)) return null;
        SWPiece p = assembleShipwreck(isBeached, chunkX, chunkZ);
        if (p == null) return null;
        return new int[]{p.rotation().ordinal(), p.template().sizeX, p.template().sizeY, p.template().sizeZ, p.template().blocks.size(), p.originY()};
    }

    private SWPiece assembleShipwreck(boolean isBeached, int ccx, int ccz) {
        if (!isBeached && oceanFloor == null) return null;
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, ccx, ccz);

        VTemplate.Rot rotation = VTemplate.Rot.VALUES[random.nextInt(4)];
        String[] pool = isBeached ? SHIPWRECK_BEACHED_TEMPLATES : SHIPWRECK_OCEAN_TEMPLATES;
        String templateLoc = pool[random.nextInt(pool.length)];
        VTemplate template = VTemplate.load(templateLoc);
        if (template.sizeX == 0) return null;

        int baseX = ccx << 4, baseZ = ccz << 4;
        int[] bb0 = pivotBoundingBox(template, baseX, 0, baseZ, rotation, SHIPWRECK_PIVOT_X, SHIPWRECK_PIVOT_Z);
        VJigsaw.SurfaceHeight heightmap = isBeached ? surface : oceanFloor;

        int originY;
        if (isBeached) {
            int minSampled = Integer.MAX_VALUE;
            for (int x = bb0[0]; x <= bb0[3]; x++) {
                for (int z = bb0[2]; z <= bb0[5]; z++) minSampled = Math.min(minSampled, heightmap.firstFreeWg(x, z) - 1);
            }
            originY = minSampled - template.sizeY / 2 - random.nextInt(3);
        } else {
            long total = 0;
            int count = 0;
            for (int x = bb0[0]; x <= bb0[3]; x++) {
                for (int z = bb0[2]; z <= bb0[5]; z++) { total += heightmap.firstFreeWg(x, z) - 1; count++; }
            }
            originY = (int) (total / count); // ShipwreckPiece.postProcess: mean height, no RNG draw for the ocean flavor
        }

        int centerX = (bb0[0] + bb0[3]) / 2, centerZ = (bb0[2] + bb0[5]) / 2;
        String biome = biomes.biomeAt(centerX >> 2, originY >> 2, centerZ >> 2);
        Set<String> allowedBiomes = isBeached ? shipwreckBeachedBiomes : shipwreckOceanBiomes;
        if (!allowedBiomes.contains(biome)) return null;

        return new SWPiece(template, baseX, originY, baseZ, rotation);
    }

    // ------------------------------------------------------------------ buried treasure

    /**
     * Single-point structure (no NBT template, no jigsaw), decompiled from
     * `BuriedTreasureStructure`/`BuriedTreasurePieces`. The generation point is
     * chunk-relative `(9, 90, 9)`, with the real placement Y sourced from `OCEAN_FLOOR_WG`
     * (not `WORLD_SURFACE_WG`) — the FIRST structure in this project to need it, via the
     * newly-added `VanillaGen.oceanFloorBlock` accessor (continues scanning past
     * AIR/WATER/LAVA substances to the first solid ground, unlike `topBlock`/`firstFreeWg`
     * which stops at the first non-air substance INCLUDING water). This gap was previously
     * documented as blocking `ruined_portal_ocean`/`ruined_portal_swamp` and regular
     * (non-beached) shipwrecks too — the accessor now exists and could unblock those in a
     * future increment, though they aren't wired yet. Real vanilla's `postProcess` here has
     * ZERO RNG draws (a genuinely deterministic terrain carve given the start XZ): scan down
     * from the ocean floor to the first solid block (already exactly what `oceanFloorBlock`
     * computes), then replace all 6 face-neighbors of that point with either "hard" fill (if
     * the neighbor's own floor is open beneath it too, and the neighbor isn't the UP
     * direction) or "soft" fill otherwise, and place a chest at the point itself. This
     * project's density/aquifer substance model is coarse (`VAquifer` only distinguishes
     * STONE/AIR/WATER/LAVA, not the specific stone variants — sandstone/andesite/granite/
     * diorite — real vanilla's actual block-state checks look for) and this project's
     * structure-placement `Canvas` is write-only with no read-back capability (the same
     * limitation already documented for mineshaft's `isInInvalidLocation`/desert pyramid's
     * `skipAir`) — so the real material-dependent fill choice (whatever block happens to
     * already be there) is approximated with fixed stand-ins (`SAND` for soft, `SANDSTONE`
     * for hard), and the 6-neighbor open/solid determination uses per-column
     * `OCEAN_FLOOR_WG` height comparisons rather than genuine block-state reads — a
     * reasonable approximation given the algorithm's real-world effect is almost always
     * "hollow out a small sand pocket around a chest just under the sea floor" regardless of
     * the exact surrounding material. Chest loot-table CONTENTS skipped (matches every other
     * structure's established precedent this session); the chest block itself IS placed.
     */
    private static final String BURIED_TREASURE_SET = "minecraft:buried_treasures";

    private void placeBuriedTreasures(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        if (!placement.isStructureChunk(BURIED_TREASURE_SET, chunkX, chunkZ)) return;
        String key = chunkX + ":" + chunkZ;
        int[] chest = buriedTreasureStarts.containsKey(key) ? buriedTreasureStarts.get(key) : assembleBuriedTreasure(chunkX, chunkZ);
        buriedTreasureStarts.put(key, chest);
        if (chest == null) return;
        int cx = chest[0], cy = chest[1], cz = chest[2];
        if (cx < minX || cx > maxX || cz < minZ || cz > maxZ) return; // never spans chunks (single point + 1-block neighbors)

        Block soft = Block.SAND, hard = Block.SANDSTONE;
        // {dx,dy,dz,isUp}
        int[][] dirs = {{0, -1, 0, 0}, {0, 1, 0, 1}, {0, 0, -1, 0}, {0, 0, 1, 0}, {-1, 0, 0, 0}, {1, 0, 0, 0}};
        for (int[] d : dirs) {
            int nx = cx + d[0], ny = cy + d[1], nz = cz + d[2];
            int neighborFloor = oceanFloor.firstFreeWg(nx, nz) - 1;
            if (ny <= neighborFloor) continue; // neighbor is solid ground already, real vanilla leaves it alone
            boolean belowOpen = (ny - 1) > neighborFloor;
            Block fill = (belowOpen && d[3] == 0) ? hard : soft;
            canvas.set(nx, ny, nz, fill);
        }
        canvas.set(cx, cy, cz, Block.CHEST.withProperty("facing", "north"));
        dev.pointofpressure.minecom.blocks.Containers.registerLoot(
                new net.minestom.server.coordinate.Vec(cx, cy, cz), "minecraft:chests/buried_treasure");
    }

    /** Test hook: {x, y, z} of the treasure chest at the given chunk, or null. */
    public int[] testBuriedTreasureAt(int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(BURIED_TREASURE_SET, chunkX, chunkZ)) return null;
        return assembleBuriedTreasure(chunkX, chunkZ);
    }

    private int[] assembleBuriedTreasure(int chunkX, int chunkZ) {
        int west = chunkX << 4, north = chunkZ << 4;

        int midX = west + 8, midZ = north + 8; // onTopOfChunkCenter: chunk MIDDLE, for the biome check only
        int midY = oceanFloor.firstFreeWg(midX, midZ) - 1;
        String biome = biomes.biomeAt(midX >> 2, midY >> 2, midZ >> 2);
        if (!buriedTreasureBiomes.contains(biome)) return null;

        int cx = west + 9, cz = north + 9; // BuriedTreasureStructure.generatePieces: chunkPos.getBlockX/Z(9)
        int cy = oceanFloor.firstFreeWg(cx, cz) - 1; // already the "scan down to first solid" result
        return new int[]{cx, cy, cz};
    }

    // ------------------------------------------------------------------ ocean ruin

    /**
     * NBT-template structure(s) (like shipwreck/ruined portal), decompiled from
     * `OceanRuinStructure`/`OceanRuinPieces`. Real vanilla's `minecraft:ocean_ruins`
     * structure set weights TWO structures with genuinely different piece shapes: WARM
     * (a single weighted template from `WARM_RUINS`/`BIG_WARM_RUINS`) and COLD (THREE
     * templates — brick/cracked/mossy variants — stacked at the exact SAME position, one
     * shared template-pool-index draw, each with a different `BlockRotProcessor` integrity:
     * `baseIntegrity`(brick) → 0.7(cracked) → 0.5(mossy), placed in that order so later
     * (lower-integrity) layers partially show through the earlier ones wherever their
     * per-block rot roll fails — both flavors are wired. Both use the real
     * `BlockRotProcessor` formula (this project's `VProcessors`' `block_rot` case,
     * confirmed applicable here since it needs no terrain read — position-seeded via
     * `XRandom.blockSeed`, no `rottable_blocks` tag filter for a plain
     * `new BlockRotProcessor(integrity)`, matching real vanilla's construction): reimplemented
     * inline as `blockRotSurvives`, keeping every OTHER simplification already documented
     * for warm's original single-piece slice. Cluster generation (`addClusterRuins`) IS now
     * ported: when `isLarge` and a `clusterProbability` (0.9) roll passes, 4-8 additional
     * SMALL same-flavor ruins are scattered at 8 fixed offset-formula candidate positions
     * around the parent's 16x16 footprint, skipping any whose small 5x6 footprint estimate
     * would bounding-box-overlap the parent (`addOceanRuinCluster`/`addOceanRuinPiece`/
     * `nextIntInclusive`) — this also fixed a genuine pre-existing RNG-order bug found while
     * implementing it: the old code drew `clusterProbability` BEFORE the parent's
     * template-pool pick, but real `addPieces` calls `addPiece(parent)` (which draws the
     * template index) BEFORE evaluating the cluster roll; only `isLarge=true` outcomes were
     * affected (fixed, verified via probe, the one existing LARGE SelfTest check's expected
     * values recomputed). `OCEAN_RUIN_RADIUS` bumped 2->4 so the per-chunk candidate scan
     * reaches far enough to catch cluster members landing beyond the parent's own footprint
     * near chunk-render boundaries. Still simplified: the `getHeight` terrain-variance
     * corner-scan Y refinement (raw `OCEAN_FLOOR_WG` used directly, same limitation class as
     * mineshaft's `isInInvalidLocation`, now applied per-cluster-piece too), the
     * suspicious-sand/gravel archaeology processor (matches the established loot-CONTENTS-skip
     * precedent), and the drowned entity spawn (matches the no-worldgen-entity-spawn
     * precedent). Position: fixed pivot `(0,0)`, `BlockIgnoreProcessor.STRUCTURE_AND_AIR`
     * (reimplemented via the `minecraft:air` name check, matching shipwreck).
     */
    private record OceanRuinPiece(VTemplate template, int baseX, int baseY, int baseZ, VTemplate.Rot rotation, float integrity) {}

    private static final String OCEAN_RUIN_SET = "minecraft:ocean_ruins";
    // 2 covers a single piece's own footprint; cluster members can land up to ~33 blocks beyond
    // the parent's chunk origin (8-24 block offset formulas + a ~9-block small-template span),
    // so a candidate chunk up to ~4 chunks from the render chunk can still contribute cluster blocks.
    private static final int OCEAN_RUIN_RADIUS = 4;
    private static final String[] OCEAN_RUIN_WARM_SMALL = {
            "minecraft:underwater_ruin/warm_1", "minecraft:underwater_ruin/warm_2", "minecraft:underwater_ruin/warm_3",
            "minecraft:underwater_ruin/warm_4", "minecraft:underwater_ruin/warm_5", "minecraft:underwater_ruin/warm_6",
            "minecraft:underwater_ruin/warm_7", "minecraft:underwater_ruin/warm_8"};
    private static final String[] OCEAN_RUIN_WARM_BIG = {
            "minecraft:underwater_ruin/big_warm_4", "minecraft:underwater_ruin/big_warm_5",
            "minecraft:underwater_ruin/big_warm_6", "minecraft:underwater_ruin/big_warm_7"};
    private static final String[] OCEAN_RUIN_COLD_BRICK_SMALL = {
            "minecraft:underwater_ruin/brick_1", "minecraft:underwater_ruin/brick_2", "minecraft:underwater_ruin/brick_3",
            "minecraft:underwater_ruin/brick_4", "minecraft:underwater_ruin/brick_5", "minecraft:underwater_ruin/brick_6",
            "minecraft:underwater_ruin/brick_7", "minecraft:underwater_ruin/brick_8"};
    private static final String[] OCEAN_RUIN_COLD_CRACKED_SMALL = {
            "minecraft:underwater_ruin/cracked_1", "minecraft:underwater_ruin/cracked_2", "minecraft:underwater_ruin/cracked_3",
            "minecraft:underwater_ruin/cracked_4", "minecraft:underwater_ruin/cracked_5", "minecraft:underwater_ruin/cracked_6",
            "minecraft:underwater_ruin/cracked_7", "minecraft:underwater_ruin/cracked_8"};
    private static final String[] OCEAN_RUIN_COLD_MOSSY_SMALL = {
            "minecraft:underwater_ruin/mossy_1", "minecraft:underwater_ruin/mossy_2", "minecraft:underwater_ruin/mossy_3",
            "minecraft:underwater_ruin/mossy_4", "minecraft:underwater_ruin/mossy_5", "minecraft:underwater_ruin/mossy_6",
            "minecraft:underwater_ruin/mossy_7", "minecraft:underwater_ruin/mossy_8"};
    private static final String[] OCEAN_RUIN_COLD_BRICK_BIG = {
            "minecraft:underwater_ruin/big_brick_1", "minecraft:underwater_ruin/big_brick_2",
            "minecraft:underwater_ruin/big_brick_3", "minecraft:underwater_ruin/big_brick_8"};
    private static final String[] OCEAN_RUIN_COLD_CRACKED_BIG = {
            "minecraft:underwater_ruin/big_cracked_1", "minecraft:underwater_ruin/big_cracked_2",
            "minecraft:underwater_ruin/big_cracked_3", "minecraft:underwater_ruin/big_cracked_8"};
    private static final String[] OCEAN_RUIN_COLD_MOSSY_BIG = {
            "minecraft:underwater_ruin/big_mossy_1", "minecraft:underwater_ruin/big_mossy_2",
            "minecraft:underwater_ruin/big_mossy_3", "minecraft:underwater_ruin/big_mossy_8"};
    private static final float OCEAN_RUIN_LARGE_PROBABILITY = 0.3F;
    private static final float OCEAN_RUIN_CLUSTER_PROBABILITY = 0.9F;

    private void placeOceanRuins(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int r = OCEAN_RUIN_RADIUS;
        for (boolean warm : new boolean[]{true, false}) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int ccx = chunkX + dx, ccz = chunkZ + dz;
                    if (!placement.isStructureChunk(OCEAN_RUIN_SET, ccx, ccz)) continue;
                    String key = (warm ? "warm:" : "cold:") + ccx + ":" + ccz;
                    List<OceanRuinPiece> pieces = oceanRuinStarts.containsKey(key) ? oceanRuinStarts.get(key) : assembleOceanRuin(warm, ccx, ccz);
                    oceanRuinStarts.put(key, pieces);
                    if (pieces == null) continue;
                    for (OceanRuinPiece piece : pieces) {
                        for (VTemplate.BlockInfo b : piece.template().blocks) {
                            int[] wp = VTemplate.transform(b.x, b.y, b.z, piece.rotation(), 0, 0);
                            int wx = wp[0] + piece.baseX(), wy = wp[1] + piece.baseY(), wz = wp[2] + piece.baseZ();
                            if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                            String name = b.state.key().asString();
                            if (name.equals("minecraft:structure_block") || name.equals("minecraft:structure_void") || name.equals("minecraft:air")) continue;
                            if (!blockRotSurvives(wx, wy, wz, piece.integrity())) continue;
                            canvas.set(wx, wy, wz, VBlockRotate.rotate(b.state, piece.rotation()));
                            registerContainerLoot(name, b.nbt, wx, wy, wz);
                        }
                    }
                }
            }
        }
    }

    /** Real BlockRotProcessor formula (VProcessors' block_rot case, no rottable_blocks tag): position-seeded, no ordering. */
    private static boolean blockRotSurvives(int wx, int wy, int wz, float integrity) {
        if (integrity >= 1.0F) return true;
        VSurface.LegacyRandom rng = new VSurface.LegacyRandom(XRandom.blockSeed(wx, wy, wz));
        return rng.nextFloat() <= integrity;
    }

    /** Test hook for the warm flavor (see the 3-arg overload for cold). */
    public int[] testOceanRuinAt(int chunkX, int chunkZ) {
        return testOceanRuinAt(true, chunkX, chunkZ);
    }

    /** Test hook: {pieceCount, rotationOrdinal, sizeX, sizeY, sizeZ, blockCount, baseX, baseY, baseZ} of piece 0, or null. */
    public int[] testOceanRuinAt(boolean warm, int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(OCEAN_RUIN_SET, chunkX, chunkZ)) return null;
        List<OceanRuinPiece> pieces = assembleOceanRuin(warm, chunkX, chunkZ);
        if (pieces == null) return null;
        OceanRuinPiece p = pieces.get(0);
        return new int[]{pieces.size(), p.rotation().ordinal(), p.template().sizeX, p.template().sizeY, p.template().sizeZ,
                p.template().blocks.size(), p.baseX(), p.baseY(), p.baseZ()};
    }

    private List<OceanRuinPiece> assembleOceanRuin(boolean warm, int ccx, int ccz) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, ccx, ccz);

        int west = ccx << 4, north = ccz << 4;
        int midX = west + 8, midZ = north + 8; // onTopOfChunkCenter: chunk MIDDLE, for the biome check only
        int midY = oceanFloor.firstFreeWg(midX, midZ) - 1;
        String biome = biomes.biomeAt(midX >> 2, midY >> 2, midZ >> 2);
        Set<String> allowedBiomes = warm ? oceanRuinWarmBiomes : oceanRuinColdBiomes;
        if (!allowedBiomes.contains(biome)) return null;

        VTemplate.Rot rotation = VTemplate.Rot.VALUES[random.nextInt(4)];

        boolean isLarge = random.nextFloat() <= OCEAN_RUIN_LARGE_PROBABILITY;
        float baseIntegrity = isLarge ? 0.9F : 0.8F;

        int baseX = west, baseZ = north; // chunkPos.getMinBlockX/Z(); placeholder Y (90) is fully overwritten below, matching real postProcess
        int baseY = oceanFloor.firstFreeWg(baseX, baseZ) - 1; // simplified: raw OCEAN_FLOOR_WG at the template origin (no corner-scan variance refinement)

        // addPieces: addPiece(parent) [draws the template-pool index] runs BEFORE the
        // clusterProbability roll — order matters since both consume `random`.
        List<OceanRuinPiece> pieces = new ArrayList<>();
        addOceanRuinPiece(pieces, warm, isLarge, baseIntegrity, baseX, baseY, baseZ, rotation, random);
        if (pieces.isEmpty()) return null; // missing-template guard (VTemplate.EMPTY)

        if (isLarge && random.nextFloat() <= OCEAN_RUIN_CLUSTER_PROBABILITY) {
            addOceanRuinCluster(pieces, warm, baseX, baseZ, rotation, random);
        }
        return pieces;
    }

    private void addOceanRuinPiece(List<OceanRuinPiece> out, boolean warm, boolean isLarge, float baseIntegrity,
                                    int baseX, int baseY, int baseZ, VTemplate.Rot rotation, VSurface.LegacyRandom random) {
        if (warm) {
            String[] pool = isLarge ? OCEAN_RUIN_WARM_BIG : OCEAN_RUIN_WARM_SMALL;
            VTemplate template = VTemplate.load(pool[random.nextInt(pool.length)]);
            if (template.sizeX == 0) return;
            out.add(new OceanRuinPiece(template, baseX, baseY, baseZ, rotation, baseIntegrity));
        } else {
            String[] brickPool = isLarge ? OCEAN_RUIN_COLD_BRICK_BIG : OCEAN_RUIN_COLD_BRICK_SMALL;
            String[] crackedPool = isLarge ? OCEAN_RUIN_COLD_CRACKED_BIG : OCEAN_RUIN_COLD_CRACKED_SMALL;
            String[] mossyPool = isLarge ? OCEAN_RUIN_COLD_MOSSY_BIG : OCEAN_RUIN_COLD_MOSSY_SMALL;
            int idx = random.nextInt(brickPool.length); // ONE shared index draw across all 3 variant pools
            VTemplate brick = VTemplate.load(brickPool[idx]), cracked = VTemplate.load(crackedPool[idx]), mossy = VTemplate.load(mossyPool[idx]);
            if (brick.sizeX == 0 || cracked.sizeX == 0 || mossy.sizeX == 0) return;
            out.add(new OceanRuinPiece(brick, baseX, baseY, baseZ, rotation, baseIntegrity));
            out.add(new OceanRuinPiece(cracked, baseX, baseY, baseZ, rotation, 0.7F));
            out.add(new OceanRuinPiece(mossy, baseX, baseY, baseZ, rotation, 0.5F));
        }
    }

    /**
     * OceanRuinPieces.addClusterRuins: 4-8 additional SMALL (non-large), same-flavor ruins
     * scattered around the parent's 16x16 chunk footprint at 8 fixed offset-formula candidate
     * positions (real vanilla's own hardcoded geometry, not derived), skipping any candidate
     * whose own small 5x6 footprint estimate would bounding-box-overlap the parent. Each
     * cluster member's Y uses this project's existing `oceanFloor.firstFreeWg` simplification
     * (same as every other ocean-ruin piece) rather than real `OceanRuinPiece.postProcess`'s
     * per-piece "scan for a flat area" height-refinement algorithm.
     */
    private void addOceanRuinCluster(List<OceanRuinPiece> pieces, boolean warm, int baseX, int baseZ,
                                      VTemplate.Rot parentRotation, VSurface.LegacyRandom random) {
        int[] corner = VTemplate.transform(15, 0, 15, parentRotation, 0, 0);
        int cornerX = baseX + corner[0], cornerZ = baseZ + corner[2];
        int originX = Math.min(baseX, cornerX), originZ = Math.min(baseZ, cornerZ);
        int parentMinX = originX, parentMaxX = Math.max(baseX, cornerX);
        int parentMinZ = originZ, parentMaxZ = Math.max(baseZ, cornerZ);

        List<int[]> candidates = new ArrayList<>(8);
        candidates.add(new int[]{originX + (-16 + nextIntInclusive(random, 1, 8)), originZ + (16 + nextIntInclusive(random, 1, 7))});
        candidates.add(new int[]{originX + (-16 + nextIntInclusive(random, 1, 8)), originZ + nextIntInclusive(random, 1, 7)});
        candidates.add(new int[]{originX + (-16 + nextIntInclusive(random, 1, 8)), originZ + (-16 + nextIntInclusive(random, 4, 8))});
        candidates.add(new int[]{originX + nextIntInclusive(random, 1, 7), originZ + (16 + nextIntInclusive(random, 1, 7))});
        candidates.add(new int[]{originX + nextIntInclusive(random, 1, 7), originZ + (-16 + nextIntInclusive(random, 4, 6))});
        candidates.add(new int[]{originX + (16 + nextIntInclusive(random, 1, 7)), originZ + (16 + nextIntInclusive(random, 3, 8))});
        candidates.add(new int[]{originX + (16 + nextIntInclusive(random, 1, 7)), originZ + nextIntInclusive(random, 1, 7)});
        candidates.add(new int[]{originX + (16 + nextIntInclusive(random, 1, 7)), originZ + (-16 + nextIntInclusive(random, 4, 8))});

        int ruins = nextIntInclusive(random, 4, 8);
        for (int i = 0; i < ruins; i++) {
            if (candidates.isEmpty()) break;
            int idx = random.nextInt(candidates.size());
            int[] pos = candidates.remove(idx);
            VTemplate.Rot nextRotation = VTemplate.Rot.VALUES[random.nextInt(4)];
            int[] nc = VTemplate.transform(5, 0, 6, nextRotation, 0, 0);
            int nMinX = Math.min(pos[0], pos[0] + nc[0]), nMaxX = Math.max(pos[0], pos[0] + nc[0]);
            int nMinZ = Math.min(pos[1], pos[1] + nc[2]), nMaxZ = Math.max(pos[1], pos[1] + nc[2]);
            boolean intersectsParent = nMinX <= parentMaxX && nMaxX >= parentMinX && nMinZ <= parentMaxZ && nMaxZ >= parentMinZ;
            if (!intersectsParent) {
                int cy = oceanFloor.firstFreeWg(pos[0], pos[1]) - 1;
                addOceanRuinPiece(pieces, warm, false, 0.8F, pos[0], cy, pos[1], nextRotation, random);
            }
        }
    }

    /** Mth.nextInt(random, min, max): uniform inclusive [min,max]. */
    private static int nextIntInclusive(VSurface.LegacyRandom random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    // ------------------------------------------------------------------ ocean monument

    /**
     * The full real branching structure algorithm (room-graph maze-CLOSING + fixed-priority
     * room-shape fitting + all ~15 room-content piece types + the hardcoded outer shell), ported
     * in {@link VMonumentGen} across three staged increments this session. Wired here as a
     * single ocean-anchored piece (unlike stronghold's live chunk-load-event architecture —
     * ocean monuments generate through the normal offline chunk-population pipeline like
     * end_city/bastion, since their placement Y is a fixed absolute constant (39-61ish,
     * `MonumentBuilding`'s own hardcoded Y=39 base — real ocean monuments do NOT conform to the
     * local ocean floor depth, a genuine, well-known vanilla quirk, confirmed from the decompile
     * never applying any oceanFloor-derived Y offset to the piece itself), so no live-Instance
     * terrain-height read is needed at placement time the way stronghold's real corridor Y does.
     * Biome gate: ALL biomes within a 29-block radius of `(chunkMinX+9, seaLevel, chunkMinZ+9)`
     * must be ocean/river (`#required_ocean_monument_surrounding` — real vanilla's own ALL-match
     * area check, sampled here at quart resolution within the spherical radius, matching real
     * `BiomeManager.getBiomesWithin`'s own sampling grain).
     */
    private static final String OCEAN_MONUMENT_SET = "minecraft:ocean_monuments";
    private static final int OCEAN_MONUMENT_BASE_Y = 39;

    private void placeOceanMonuments(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int r = 5; // 58-block max shell extent (~4 chunks) plus slack for wings/orientation
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ccx = chunkX + dx, ccz = chunkZ + dz;
                if (!placement.isStructureChunk(OCEAN_MONUMENT_SET, ccx, ccz)) continue;
                String key = ccx + ":" + ccz;
                OceanMonumentAssembly asm = oceanMonumentStarts.containsKey(key) ? oceanMonumentStarts.get(key) : assembleOceanMonument(ccx, ccz);
                oceanMonumentStarts.put(key, asm);
                if (asm == null) continue;
                VMonumentGen.Sink sink = new VMonumentGen.Sink() {
                    public Block get(int x, int y, int z) { return canvas.get(x, y, z); }
                    public void set(int x, int y, int z, Block b) { canvas.set(x, y, z, b); }
                };
                VMonumentGen.renderShell(asm.building(), sink, seaLevel, chunkX, chunkZ);
                VMonumentGen.renderRooms(asm.rooms(), seed, sink, seaLevel, chunkX, chunkZ);
            }
        }
    }

    /** Test hook: matches testBuriedTreasureAt/testOceanRuinAt convention — gated on both the real placement grid and the biome check. */
    public OceanMonumentAssembly testOceanMonumentAt(int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(OCEAN_MONUMENT_SET, chunkX, chunkZ)) return null;
        return assembleOceanMonument(chunkX, chunkZ);
    }

    /** Test hook: force-assemble (biome-gated only, bypassing the placement grid) — for probing biome-check density independently. */
    public OceanMonumentAssembly testOceanMonumentBiomeOnlyAt(int chunkX, int chunkZ) {
        return assembleOceanMonument(chunkX, chunkZ);
    }

    private OceanMonumentAssembly assembleOceanMonument(int chunkX, int chunkZ) {
        int checkX = (chunkX << 4) + 9, checkZ = (chunkZ << 4) + 9;
        if (!oceanMonumentBiomeOk(checkX, checkZ)) return null;

        int west = (chunkX << 4) - 29, north = (chunkZ << 4) - 29;
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, chunkX, chunkZ);
        VTemplate.Dir orientation = switch (random.nextInt(4)) {
            case 0 -> VTemplate.Dir.SOUTH;
            case 1 -> VTemplate.Dir.WEST;
            case 2 -> VTemplate.Dir.NORTH;
            default -> VTemplate.Dir.EAST;
        };
        VMonumentGen.Graph graph = VMonumentGen.generateRoomGraph(random);
        VMonumentGen.fitRoomShapes(graph, random);
        VMonumentGen.Building building = VMonumentGen.makeBuilding(west, OCEAN_MONUMENT_BASE_Y, north, orientation);
        List<VMonumentGen.RoomPiece> rooms = VMonumentGen.resolveRoomPieces(building, graph);
        return new OceanMonumentAssembly(building, rooms);
    }

    /** Real getBiomesWithin: ALL points within the radius (spherical, quart-resolution grid) must match. */
    private boolean oceanMonumentBiomeOk(int centerX, int centerZ) {
        int r = 29;
        for (int dx = -r; dx <= r; dx += 4) {
            for (int dz = -r; dz <= r; dz += 4) {
                if (dx * dx + dz * dz > r * r) continue;
                String biome = biomes.biomeAt((centerX + dx) >> 2, seaLevel >> 2, (centerZ + dz) >> 2);
                if (!oceanMonumentSurroundingBiomes.contains(biome)) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ woodland mansion

    /**
     * The full real algorithm ({@link VMansionGen} — the room-grid maze/room-identification
     * pass plus the whole {@code MansionPiecePlacer} NBT-template dispatch, both ported this
     * session across 3 staged increments) wired here as the final structure this project needed.
     * Placement uses real vanilla's own {@code Structure.getLowestYIn5by5BoxOffset7Blocks}
     * algorithm — offset 7 blocks into the chunk, then a further 5-block offset in a
     * rotation-dependent direction, taking the MIN of the 4 corner surface heights — the exact
     * same algorithm already ported for end_city (confirmed identical rotation-to-offset
     * mapping and corner-height-min formula by cross-checking against {@code VEndGen}'s own
     * already-verified implementation rather than re-deriving). Rejects below Y 60, matching
     * real vanilla exactly. Biome gate is a simple single-point check (`dark_forest`/
     * `pale_garden`, unlike ocean monument's area gate).
     * <p>NOT ported: {@code WoodlandMansionStructure.afterPlace}'s cobblestone foundation-fill
     * pass (fills any gap between the mansion's lowest floor and the real ground below) — a
     * cosmetic pass that prevents floating corners on uneven dark-forest terrain, matching the
     * established precedent of documenting purely cosmetic simplifications elsewhere in this
     * project (e.g. NetherGen's dried ghast, bastion's fine sub-structure content) rather than
     * blocking the structure's actual completion on it.
     */
    private static final String WOODLAND_MANSION_SET = "minecraft:woodland_mansions";
    private static final int WOODLAND_MANSION_RADIUS = 6; // real mansions span up to ~90 blocks (~6 chunks) including wings/roof

    private void placeWoodlandMansions(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, VStructureGen.Canvas canvas) {
        int r = WOODLAND_MANSION_RADIUS;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ccx = chunkX + dx, ccz = chunkZ + dz;
                if (!placement.isStructureChunk(WOODLAND_MANSION_SET, ccx, ccz)) continue;
                String key = ccx + ":" + ccz;
                List<VMansionGen.Piece> pieces = woodlandMansionStarts.containsKey(key) ? woodlandMansionStarts.get(key) : assembleWoodlandMansion(ccx, ccz);
                woodlandMansionStarts.put(key, pieces);
                if (pieces == null) continue;
                VMansionGen.Sink sink = (x, y, z, b) -> {
                    if (x < minX || x > maxX || z < minZ || z > maxZ) return;
                    canvas.set(x, y, z, b);
                };
                VMansionGen.render(pieces, sink, chunkX, chunkZ);
            }
        }
    }

    /** Test hook: matches testOceanMonumentAt's convention — gated on both the real placement grid and the biome check. */
    public List<VMansionGen.Piece> testWoodlandMansionAt(int chunkX, int chunkZ) {
        if (!placement.isStructureChunk(WOODLAND_MANSION_SET, chunkX, chunkZ)) return null;
        return assembleWoodlandMansion(chunkX, chunkZ);
    }

    private List<VMansionGen.Piece> assembleWoodlandMansion(int chunkX, int chunkZ) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, chunkX, chunkZ);
        VTemplate.Rot rotation = VTemplate.Rot.VALUES[random.nextInt(4)];

        int offsetX = 5, offsetZ = 5;
        switch (rotation) {
            case CLOCKWISE_90 -> offsetX = -5;
            case CLOCKWISE_180 -> { offsetX = -5; offsetZ = -5; }
            case COUNTERCLOCKWISE_90 -> offsetZ = -5;
            default -> { }
        }
        int blockX = (chunkX << 4) + 7, blockZ = (chunkZ << 4) + 7;
        int lowestY = Math.min(
                Math.min(surface.firstFreeWg(blockX, blockZ), surface.firstFreeWg(blockX, blockZ + offsetZ)),
                Math.min(surface.firstFreeWg(blockX + offsetX, blockZ), surface.firstFreeWg(blockX + offsetX, blockZ + offsetZ)));
        if (lowestY < 60) return null;

        String biome = biomes.biomeAt(blockX >> 2, lowestY >> 2, blockZ >> 2);
        if (!woodlandMansionBiomes.contains(biome)) return null;

        return VMansionGen.generateMansion(random, new VMansionGen.Pos(blockX, lowestY, blockZ), rotation);
    }

    /** Assembled beard-data cache, keyed by chunk (packed x/z). */
    private final Map<Long, VBeardifier> beardCache = new HashMap<>();

    /**
     * Beardifier.forStructuresInChunk port: gathers rigid pieces + jigsaw junctions from every
     * terrain-adapting structure type within reach of this chunk, for the "beardifier" density
     * function node to carve terrain around during noise/terrain shaping (before block placement).
     */
    public VBeardifier beardDataForChunk(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        VBeardifier cached = beardCache.get(key);
        if (cached != null) return cached;

        int chunkMinX = chunkX << 4, chunkMinZ = chunkZ << 4;
        List<VBeardifier.Rigid> rigids = new ArrayList<>();
        List<VBeardifier.Junction> junctions = new ArrayList<>();
        for (Type type : types) {
            if (type.adjustment == VBeardifier.Adjustment.NONE) continue;
            int r = type.radiusChunks;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int ccx = chunkX + dx, ccz = chunkZ + dz;
                    if (!placement.isStructureChunk(type.setName, ccx, ccz)) continue;
                    VJigsaw.Assembly start = startFor(type, ccx, ccz);
                    if (start == null) continue;
                    for (VJigsaw.Piece piece : start.pieces) {
                        int[] bb = piece.bb;
                        // StructurePiece.isCloseToChunk(chunkPos, 12): XZ AABB overlap only.
                        boolean close = bb[3] >= chunkMinX - 12 && bb[0] <= chunkMinX + 15 + 12
                                && bb[5] >= chunkMinZ - 12 && bb[2] <= chunkMinZ + 15 + 12;
                        if (!close) continue;
                        if (piece.element.rigid) {
                            rigids.add(new VBeardifier.Rigid(bb.clone(), type.adjustment, piece.groundLevelDelta));
                        }
                        for (int[] j : piece.junctions) {
                            int jx = j[0], jy = j[1], jz = j[2];
                            if (jx > chunkMinX - 12 && jz > chunkMinZ - 12 && jx < chunkMinX + 15 + 12 && jz < chunkMinZ + 15 + 12) {
                                junctions.add(new VBeardifier.Junction(jx, jy, jz));
                            }
                        }
                    }
                }
            }
        }
        VBeardifier result = (rigids.isEmpty() && junctions.isEmpty()) ? VBeardifier.EMPTY : new VBeardifier(rigids, junctions);
        beardCache.put(key, result);
        return result;
    }

    private VJigsaw.Assembly startFor(Type type, int ccx, int ccz) {
        // key must be per-TYPE: the 5 village types share one setName, so keying on
        // setName alone collided (first type's null cached for all → only plains generated).
        String key = type.setName + ':' + type.def.startPool + ':' + ccx + ':' + ccz;
        if (starts.containsKey(key)) return starts.get(key);
        VJigsaw.Assembly a = jigsaw.assembleFull(type.def, ccx, ccz, type.aliases);
        if (a != null && !biomeOk(type, a)) a = null;
        starts.put(key, a);
        return a;
    }

    private boolean biomeOk(Type type, VJigsaw.Assembly a) {
        String biome = biomes.biomeAt(a.centerX >> 2, a.centerY >> 2, a.centerZ >> 2);
        return type.biomes.contains(biome);
    }

    // ------------------------------------------------------------------ biome tag loading

    static Set<String> loadBiomes(String name) {
        try (InputStream in = VStructureManager.class.getResourceAsStream("/vanilla/structure_biomes/" + name + ".json")) {
            JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            Set<String> out = new HashSet<>();
            for (var v : root.getAsJsonArray("values")) out.add(v.getAsString());
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("missing structure_biomes/" + name, e);
        }
    }
}
