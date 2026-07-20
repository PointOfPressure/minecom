package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.instance.generator.UnitModifier;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The End dimension generator. Reuses the vanilla density-function engine
 * ({@link VDensity}) driven by {@code noise_settings_end.json}: the end-island
 * simplex terrain (verified bit-exact) plus the shared {@code end/sloped_cheese}
 * graph, evaluated on the End's 8x4x8 noise cells. Solid where final density &gt; 0
 * (end_stone), air elsewhere — the floating islands of The End.
 *
 * <p>Biomes, the end-stone surface nuances, and features (chorus, obsidian spikes,
 * the exit portal/gateway) are layered on separately; this class is the terrain base.
 */
public final class VEndGen implements Generator {

    static final int MIN_Y = 0;
    static final int HEIGHT = 128;   // end.json noise height

    private final VDensity.DF finalDensity;
    private final VSurface.Simplex islandNoise;   // TheEndBiomeSource erosion = cache_2d(end_islands)
    private final long seed;
    private final VStructures endCityPlacement;
    private final Set<String> endCityBiomes;
    private final Map<String, List<ECPiece>> endCityPieces = new HashMap<>();

    public VEndGen(long seed) {
        this.seed = seed;
        this.endCityPlacement = new VStructures(seed);
        this.endCityBiomes = VStructureManager.loadBiomes("end_city");
        Gson gson = new Gson();
        VSurface.LegacyRandom ir = new VSurface.LegacyRandom(seed);
        for (int i = 0; i < 17292; i++) ir.next(32);
        this.islandNoise = new VSurface.Simplex(ir);
        JsonObject dens = read(gson, "/vanilla/worldgen_density.json");
        JsonObject noise = read(gson, "/vanilla/worldgen_noise.json");
        JsonObject settings = read(gson, "/vanilla/noise_settings_end.json");

        Map<String, JsonElement> named = new HashMap<>();
        for (var e : dens.entrySet()) named.put(e.getKey(), e.getValue());
        Map<String, JsonObject> noises = new HashMap<>();
        for (var e : noise.entrySet()) noises.put(e.getKey(), e.getValue().getAsJsonObject());

        VDensity.Builder builder = new VDensity.Builder(named, noises, seed);
        builder.setCellSize(8, 4);   // End: size_horizontal 2 -> 8, size_vertical 1 -> 4
        // noise_settings_end.json: legacy_random_source=true — base_3d_noise is seeded from a
        // LegacyRandomSource, not Xoroshiro (RandomState.random selection). Without this the End's
        // blended noise is wrong, over-filling island edges with end_stone.
        builder.setLegacyRandomSource(settings.has("legacy_random_source")
                && settings.get("legacy_random_source").getAsBoolean());
        JsonObject router = settings.getAsJsonObject("noise_router");
        this.finalDensity = builder.build(router.get("final_density"));
    }

    @Override
    public void generate(GenerationUnit unit) {
        var start = unit.absoluteStart();
        int baseX = start.blockX();
        int baseZ = start.blockZ();
        int minY = start.blockY();
        int maxY = minY + unit.size().blockY();
        var mod = unit.modifier();

        int lo = Math.max(minY, MIN_Y);
        int hi = Math.min(maxY, MIN_Y + HEIGHT);

        VDensity.setCellMode(true);
        try {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int wx = baseX + x, wz = baseZ + z;
                    for (int y = lo; y < hi; y++) {
                        if (finalDensity.compute(wx, y, wz) > 0.0) {
                            mod.setBlock(wx, y, wz, Block.END_STONE);
                        }
                    }
                }
            }
        } finally {
            VDensity.setCellMode(false);
        }

        for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) {
                RegistryKey<Biome> biome = biomeKey(baseX + (qx << 2), baseZ + (qz << 2));
                for (int y = Math.max(lo, MIN_Y); y < hi; y += 4) {
                    mod.setBiome(baseX + (qx << 2), y, baseZ + (qz << 2), biome);
                }
            }
        }

        placeEndCities(baseX >> 4, baseZ >> 4, baseX, baseZ, baseX + 15, baseZ + 15, mod);
    }

    // ------------------------------------------------------------------ end city

    /**
     * Decompiled from {@code EndCityStructure}/{@code EndCityPieces}. `startHouseTower`'s fixed
     * 4-piece linear chain (base_floor -> second_floor_1 -> third_floor_1 -> third_roof) AND
     * the recursive tree (`TOWER_GENERATOR`/`TOWER_BRIDGE_GENERATOR`/`HOUSE_TOWER_GENERATOR`/
     * `FAT_TOWER_GENERATOR`, mutually recursive, `genDepth<=8`-bounded, real RNG-driven
     * tower-height/bridge/ship branching, with `StructurePiece.findCollisionPiece`-equivalent
     * bounding-box collision detection) are both ported — see {@link #recursiveChildren} for
     * the batch-buffering/collision-scoping semantics this required getting right. Real
     * vanilla's generation-point algorithm (`Structure.getLowestYIn5by5BoxOffset7Blocks` ->
     * `getLowestY` -> `getCornerHeights`, 4-corner {@code WORLD_SURFACE_WG} minimum at a fixed
     * 5x5-box-offset-7-blocks footprint whose direction depends on rotation) and piece-linking
     * (`StructureTemplate.calculateConnectedPosition`, which — since no EndCityPiece ever sets an
     * explicit rotation pivot — collapses to `childPos = parentPos + transform(fixedOffset,
     * parentRotation, pivotX=0, pivotZ=0)`, directly reusing this project's already-bit-exact-
     * verified {@link VTemplate#transform}) are both ported precisely. No blockstate decoration
     * processors (mossiness/protected-blocks/etc — none of which real vanilla even applies to
     * end_city anyway; its own `BlockIgnoreProcessor` handling is the only per-block processor,
     * already reimplemented via the existing name-check convention used by every other NBT-
     * template structure this session). `handleDataMarker` decoration is split out: its two
     * entity spawns ("Elytra" item-frame + "Sentry" shulkers) are ported in
     * {@link EndCityDecorations} (the block-only generate pass can't spawn entities, so they are
     * decorated on chunk load via {@link #shipMarkersInChunk}); the "Chest" marker only sets a
     * loot table on the chest block already placed by the template, which stays the established
     * loot-contents skip.
     */
    /**
     * {@code overwrite}: real vanilla's {@code BlockIgnoreProcessor} choice — true=STRUCTURE_BLOCK
     * only (template air IS placed, blending nothing), false=STRUCTURE_AND_AIR (template air is
     * skipped, so existing terrain/earlier pieces show through gaps). {@code genDepth}:
     * {@code StructurePiece.getGenDepth} — used ONLY for collision-group exemption (a batch of
     * children sharing one random {@code childTag} value are exempt from colliding with a piece
     * whose genDepth equals their PARENT's, i.e. the piece they're directly attached to; -1 is a
     * sentinel real vanilla applies to specific bridge endpoint pieces to force them to always
     * participate in collision checks against everything, never exempted).
     */
    private record ECPiece(VTemplate template, int baseX, int baseY, int baseZ, VTemplate.Rot rotation, boolean overwrite, int genDepth) {
        ECPiece withGenDepth(int gd) { return new ECPiece(template, baseX, baseY, baseZ, rotation, overwrite, gd); }
    }

    private static final String END_CITY_SET = "minecraft:end_cities";
    private static final int END_CITY_RADIUS = 2; // largest single piece (third_floor_1 etc) well under 32 blocks

    private void placeEndCities(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ, net.minestom.server.instance.generator.UnitModifier mod) {
        int r = END_CITY_RADIUS;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ccx = chunkX + dx, ccz = chunkZ + dz;
                if (!endCityPlacement.isStructureChunk(END_CITY_SET, ccx, ccz)) continue;
                String key = ccx + ":" + ccz;
                List<ECPiece> pieces = endCityPieces.containsKey(key) ? endCityPieces.get(key) : assembleEndCity(ccx, ccz);
                endCityPieces.put(key, pieces);
                if (pieces == null) continue;
                for (ECPiece p : pieces) {
                    for (VTemplate.BlockInfo b : p.template.blocks) {
                        int[] wp = VTemplate.transform(b.x, b.y, b.z, p.rotation, 0, 0);
                        int wx = wp[0] + p.baseX, wy = wp[1] + p.baseY, wz = wp[2] + p.baseZ;
                        if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                        String name = b.state.key().asString();
                        if (name.equals("minecraft:structure_block") || name.equals("minecraft:structure_void")) continue;
                        if (!p.overwrite && name.equals("minecraft:air")) continue;
                        mod.setBlock(wx, wy, wz, VBlockRotate.rotate(b.state, p.rotation));
                    }
                }
            }
        }
    }

    /** Test hook: {pieceCount, baseX, baseY, baseZ, rotationOrdinal} for the piece chain's first (base_floor) piece, or null. */
    public int[] testEndCityAt(int chunkX, int chunkZ) {
        if (!endCityPlacement.isStructureChunk(END_CITY_SET, chunkX, chunkZ)) return null;
        List<ECPiece> pieces = assembleEndCity(chunkX, chunkZ);
        if (pieces == null) return null;
        ECPiece base = pieces.get(0);
        return new int[]{pieces.size(), base.baseX, base.baseY, base.baseZ, base.rotation.ordinal()};
    }

    // ------------------------------------------------------------- end-ship data markers

    /**
     * A ship data-marker (an {@code EndCityPieces} {@code Blocks.STRUCTURE_BLOCK} in DATA mode)
     * in world coordinates. {@code kind} is the marker's {@code metadata} string ("Elytra",
     * "Sentry" or "Chest"); {@code facing} is the ship piece's rotation applied to
     * {@code Direction.SOUTH}, matching {@code EndCityPieces.handleDataMarker}'s
     * {@code this.placeSettings.getRotation().rotate(Direction.SOUTH)} for the framed elytra.
     */
    public record ShipMarker(int x, int y, int z, String kind, VTemplate.Dir facing) {}

    // How far (in chunks) a ship can sit from its city's root chunk: the ship is attached at the
    // end of a recursive bridge chain with a fixed {@code -70..-60}-block Z offset, so its markers
    // can land several chunks away from where {@code isStructureChunk} anchors the city.
    private static final int SHIP_SEARCH_RADIUS = 8;
    private static final VTemplate SHIP_TEMPLATE = VTemplate.load("minecraft:end_city/ship");

    /**
     * Every end-ship data marker (Elytra/Sentry/Chest) whose world position falls inside the
     * given chunk, across any end city rooted within {@link #SHIP_SEARCH_RADIUS}. Drives
     * {@link EndCityDecorations}: the framed elytra + shulker guards are entity spawns that the
     * block-only {@code generate} pass can't place, so they are decorated in on chunk load.
     */
    public List<ShipMarker> shipMarkersInChunk(int chunkX, int chunkZ) {
        List<ShipMarker> out = new ArrayList<>();
        int r = SHIP_SEARCH_RADIUS;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ccx = chunkX + dx, ccz = chunkZ + dz;
                if (!endCityPlacement.isStructureChunk(END_CITY_SET, ccx, ccz)) continue;
                String key = ccx + ":" + ccz;
                List<ECPiece> pieces = endCityPieces.containsKey(key) ? endCityPieces.get(key) : assembleEndCity(ccx, ccz);
                endCityPieces.put(key, pieces);
                if (pieces == null) continue;
                for (ECPiece p : pieces) {
                    if (p.template != SHIP_TEMPLATE) continue;
                    for (VTemplate.BlockInfo b : p.template.blocks) {
                        if (b.nbt == null || !b.state.key().asString().equals("minecraft:structure_block")) continue;
                        String meta = b.nbt.getString("metadata");
                        if (meta == null || meta.isEmpty()) continue;
                        int[] wp = VTemplate.transform(b.x, b.y, b.z, p.rotation, 0, 0);
                        int wx = wp[0] + p.baseX, wy = wp[1] + p.baseY, wz = wp[2] + p.baseZ;
                        if ((wx >> 4) != chunkX || (wz >> 4) != chunkZ) continue;
                        out.add(new ShipMarker(wx, wy, wz, meta, p.rotation.rotate(VTemplate.Dir.SOUTH)));
                    }
                }
            }
        }
        return out;
    }

    /** Test hook: {wx,wy,wz, facingDirOrdinal} of the ship's Elytra marker for the city rooted at (chunkX,chunkZ), or null if that city has no ship. */
    public int[] shipElytraMarker(int chunkX, int chunkZ) {
        if (!endCityPlacement.isStructureChunk(END_CITY_SET, chunkX, chunkZ)) return null;
        List<ECPiece> pieces = assembleEndCity(chunkX, chunkZ);
        if (pieces == null) return null;
        for (ECPiece p : pieces) {
            if (p.template != SHIP_TEMPLATE) continue;
            for (VTemplate.BlockInfo b : p.template.blocks) {
                if (b.nbt == null || !"Elytra".equals(b.nbt.getString("metadata"))) continue;
                int[] wp = VTemplate.transform(b.x, b.y, b.z, p.rotation, 0, 0);
                return new int[]{wp[0] + p.baseX, wp[1] + p.baseY, wp[2] + p.baseZ, p.rotation.rotate(VTemplate.Dir.SOUTH).ordinal()};
            }
        }
        return null;
    }

    /**
     * Test hook: real end-to-end material verification (not just assembled-piece geometry) —
     * runs the ACTUAL {@link #generate} code path across a chunk window around the given
     * center, through a synthetic {@link GenerationUnit}/{@link UnitModifier} implementing only
     * what {@code generate} actually calls ({@code setBlock}/{@code setBiome}; every other
     * abstract method is a no-op since this project's generation loop never calls them), and
     * tallies how many placed blocks contain any of the given name substrings. Matches the same
     * real-generation verification rigor `VanillaGen.decoratedData()`-based checks give
     * ancient_city/trial_chambers elsewhere in this project's SelfTest — end_city had no
     * equivalent until this hook, since {@code VEndGen} doesn't have a cached-chunk-data
     * accessor the way `VanillaGen` does.
     */
    public int materialCountAt(int chunkX, int chunkZ, int radiusChunks, String... nameSubstrings) {
        int[] count = {0};
        Consumer<Block> tally = b -> {
            String name = b.name();
            for (String s : nameSubstrings) if (name.contains(s)) { count[0]++; return; }
        };
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                generate(fakeUnit(chunkX + dx, chunkZ + dz, tally));
            }
        }
        return count[0];
    }

    private static GenerationUnit fakeUnit(int cx, int cz, Consumer<Block> tally) {
        var start = new net.minestom.server.coordinate.BlockVec(cx << 4, 0, cz << 4);
        var size = new net.minestom.server.coordinate.BlockVec(16, HEIGHT, 16);
        return new GenerationUnit() {
            public UnitModifier modifier() {
                return new UnitModifier() {
                    public void setRelative(int x, int y, int z, Block b) { setBlock(x, y, z, b); }
                    public void setAll(Supplier s) { }
                    public void setAllRelative(Supplier s) { }
                    public void fill(Block b) { }
                    public void fill(Point a, Point c, Block b) { }
                    public void fillHeight(int a, int b, Block c) { }
                    public void fillBiome(RegistryKey<Biome> b) { }
                    public void setBlock(int x, int y, int z, Block b) { tally.accept(b); }
                    public void setBiome(int x, int y, int z, RegistryKey<Biome> biome) { }
                };
            }
            public net.minestom.server.coordinate.BlockVec size() { return size; }
            public net.minestom.server.coordinate.BlockVec absoluteStart() { return start; }
            public net.minestom.server.coordinate.BlockVec absoluteEnd() { return start.add(size); }
            public GenerationUnit fork(Point a, Point b) { throw new UnsupportedOperationException(); }
            public void fork(Consumer<Block.Setter> c) { }
        };
    }

    private List<ECPiece> assembleEndCity(int ccx, int ccz) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, ccx, ccz);

        VTemplate.Rot rotation = VTemplate.Rot.VALUES[random.nextInt(4)];

        int offsetX = 5, offsetZ = 5;
        switch (rotation) {
            case CLOCKWISE_90 -> offsetX = -5;
            case CLOCKWISE_180 -> { offsetX = -5; offsetZ = -5; }
            case COUNTERCLOCKWISE_90 -> offsetZ = -5;
            default -> { }
        }
        int blockX = (ccx << 4) + 7, blockZ = (ccz << 4) + 7;
        int lowestY = Math.min(
                Math.min(firstOccupiedHeight(blockX, blockZ), firstOccupiedHeight(blockX, blockZ + offsetZ)),
                Math.min(firstOccupiedHeight(blockX + offsetX, blockZ), firstOccupiedHeight(blockX + offsetX, blockZ + offsetZ)));
        if (lowestY < 60) return null;

        if (!endCityBiomes.contains(biomeAt(blockX, blockZ))) return null;

        VTemplate baseFloor = VTemplate.load("minecraft:end_city/base_floor");
        VTemplate secondFloor1 = VTemplate.load("minecraft:end_city/second_floor_1");
        VTemplate thirdFloor1 = VTemplate.load("minecraft:end_city/third_floor_1");
        VTemplate thirdRoof = VTemplate.load("minecraft:end_city/third_roof");
        if (baseFloor.sizeX == 0 || secondFloor1.sizeX == 0 || thirdFloor1.sizeX == 0 || thirdRoof.sizeX == 0) return null;

        List<ECPiece> pieces = new ArrayList<>();
        pieces.add(new ECPiece(baseFloor, blockX, lowestY, blockZ, rotation, true, 0));

        int[] off1 = VTemplate.transform(-1, 0, -1, rotation, 0, 0);
        int x1 = blockX + off1[0], y1 = lowestY + off1[1], z1 = blockZ + off1[2];
        pieces.add(new ECPiece(secondFloor1, x1, y1, z1, rotation, false, 0));

        int[] off2 = VTemplate.transform(-1, 4, -1, rotation, 0, 0);
        int x2 = x1 + off2[0], y2 = y1 + off2[1], z2 = z1 + off2[2];
        pieces.add(new ECPiece(thirdFloor1, x2, y2, z2, rotation, false, 0));

        int[] off3 = VTemplate.transform(-1, 8, -1, rotation, 0, 0);
        int x3 = x2 + off3[0], y3 = y2 + off3[1], z3 = z2 + off3[2];
        ECPiece thirdRoofPiece = new ECPiece(thirdRoof, x3, y3, z3, rotation, true, 0);
        pieces.add(thirdRoofPiece);

        boolean[] shipCreated = {false};
        ECGenerator tg = (gd, p, off, ps, r) -> towerGenerator(gd, p, off, ps, r, shipCreated);
        recursiveChildren(tg, 1, thirdRoofPiece, null, pieces, random);
        return pieces;
    }

    // -------------------------------------------------------- end city recursive towers/bridges

    @FunctionalInterface
    private interface ECGenerator {
        boolean generate(int genDepth, ECPiece parent, int[] offset, List<ECPiece> pieces, VSurface.LegacyRandom random);
    }

    /**
     * EndCityPieces.recursiveChildren: {@code gen} populates a FRESH local buffer (its own
     * "pieces" parameter — NOT the {@code pieces} argument this method received), every piece
     * in that buffer is tagged with ONE shared random {@code childTag}, then EACH is checked for
     * bounding-box collision against {@code pieces} (the argument this method received — i.e.
     * whatever's already accumulated at THIS level, not the true global list once nesting is
     * involved: a nested {@code recursiveChildren} call inside a generator receives THAT
     * generator's own local buffer as ITS "pieces" argument, so collision-checking naturally
     * narrows to "this branch's own pieces so far" the deeper the recursion goes) — a collision
     * against any piece whose genDepth differs from {@code parent}'s aborts the WHOLE batch (not
     * just the colliding piece); only on full success does the batch merge into {@code pieces}.
     * The real -1 genDepth sentinel {@code TOWER_BRIDGE_GENERATOR} applies to its bridge_piece/
     * bridge_end pieces is NOT reproduced — tracing the real code shows THIS wrapper's own
     * childTag-tagging loop runs immediately after generate() returns and unconditionally
     * overwrites it, so the sentinel has no observable effect on the real algorithm either.
     */
    private boolean recursiveChildren(ECGenerator gen, int genDepth, ECPiece parent, int[] offset, List<ECPiece> pieces, VSurface.LegacyRandom random) {
        if (genDepth > 8) return false;
        List<ECPiece> childPieces = new ArrayList<>();
        if (gen.generate(genDepth, parent, offset, childPieces, random)) {
            int childTag = random.next(32); // RandomSource.nextInt() no-arg == next(32), matching java.util.Random
            List<ECPiece> tagged = new ArrayList<>();
            for (ECPiece c : childPieces) tagged.add(c.withGenDepth(childTag));
            boolean collision = false;
            for (ECPiece c : tagged) {
                int[] bb = ecBoundingBox(c.template(), c.baseX(), c.baseY(), c.baseZ(), c.rotation());
                ECPiece hit = findCollision(pieces, bb);
                if (hit != null && hit.genDepth() != parent.genDepth()) { collision = true; break; }
            }
            if (!collision) { pieces.addAll(tagged); return true; }
        }
        return false;
    }

    private static int[] ecBoundingBox(VTemplate t, int posX, int posY, int posZ, VTemplate.Rot rot) {
        int[] c1 = VTemplate.transform(0, 0, 0, rot, 0, 0);
        int[] c2 = VTemplate.transform(t.sizeX - 1, t.sizeY - 1, t.sizeZ - 1, rot, 0, 0);
        int minX = Math.min(c1[0], c2[0]) + posX, minY = Math.min(c1[1], c2[1]) + posY, minZ = Math.min(c1[2], c2[2]) + posZ;
        int maxX = Math.max(c1[0], c2[0]) + posX, maxY = Math.max(c1[1], c2[1]) + posY, maxZ = Math.max(c1[2], c2[2]) + posZ;
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** StructurePiece.findCollisionPiece: first piece in the list whose bbox intersects, or null. */
    private static ECPiece findCollision(List<ECPiece> pieces, int[] bb) {
        for (ECPiece p : pieces) {
            int[] pbb = ecBoundingBox(p.template(), p.baseX(), p.baseY(), p.baseZ(), p.rotation());
            if (pbb[0] <= bb[3] && pbb[3] >= bb[0] && pbb[1] <= bb[4] && pbb[4] >= bb[1] && pbb[2] <= bb[5] && pbb[5] >= bb[2]) return p;
        }
        return null;
    }

    private static ECPiece addPiece(List<ECPiece> pieces, ECPiece parent, int offX, int offY, int offZ, String templateName, VTemplate.Rot rotation, boolean overwrite) {
        VTemplate t = VTemplate.load("minecraft:end_city/" + templateName);
        int[] off = VTemplate.transform(offX, offY, offZ, parent.rotation(), 0, 0);
        ECPiece child = new ECPiece(t, parent.baseX() + off[0], parent.baseY() + off[1], parent.baseZ() + off[2], rotation, overwrite, 0);
        pieces.add(child);
        return child;
    }

    /** Rotation.getRotated: combine two rotations by ordinal addition mod 4 (matches VTemplate.Rot's declaration order: NONE/CLOCKWISE_90/CLOCKWISE_180/COUNTERCLOCKWISE_90, identical to real Rotation). */
    private static VTemplate.Rot combineRotation(VTemplate.Rot a, VTemplate.Rot b) {
        return VTemplate.Rot.VALUES[(a.ordinal() + b.ordinal()) % 4];
    }

    private static final int[][] TOWER_BRIDGES = {{0, 1, -1, 0}, {1, 6, -1, 1}, {3, 0, -1, 5}, {2, 5, -1, 6}}; // {rotOrdinal, offX, offY, offZ}
    private static final int[][] FAT_TOWER_BRIDGES = {{0, 4, -1, 0}, {1, 12, -1, 4}, {3, 0, -1, 8}, {2, 8, -1, 12}};

    /**
     * EndCityPieces.HOUSE_TOWER_GENERATOR/TOWER_GENERATOR/TOWER_BRIDGE_GENERATOR/
     * FAT_TOWER_GENERATOR, all taking a shared {@code shipCreated} flag. Real vanilla's
     * {@code shipCreated} is an instance field on the SINGLETON {@code TOWER_BRIDGE_GENERATOR}
     * object, reset once via {@code .init()} per {@code startHouseTower} call — meaning it's
     * shared across the WHOLE recursive tree for one end city (at most one ship total, never
     * per-branch); reimplemented as a single {@code boolean[1]} created ONCE in
     * {@link #assembleEndCity} and threaded through every one of these methods as an explicit
     * parameter, rather than (incorrectly) creating a fresh one per generator invocation.
     */
    private boolean houseTowerGenerator(int genDepth, ECPiece parent, int[] offset, List<ECPiece> pieces, VSurface.LegacyRandom random, boolean[] shipCreated) {
        if (genDepth > 8) return false;
        VTemplate.Rot rotation = parent.rotation();
        ECPiece last = addPiece(pieces, parent, offset[0], offset[1], offset[2], "base_floor", rotation, true);
        int numFloors = random.nextInt(3);
        if (numFloors == 0) {
            addPiece(pieces, last, -1, 4, -1, "base_roof", rotation, true);
        } else if (numFloors == 1) {
            last = addPiece(pieces, last, -1, 0, -1, "second_floor_2", rotation, false);
            last = addPiece(pieces, last, -1, 8, -1, "second_roof", rotation, false);
            ECGenerator tg = (gd, p, off, ps, r) -> towerGenerator(gd, p, off, ps, r, shipCreated);
            recursiveChildren(tg, genDepth + 1, last, null, pieces, random);
        } else {
            last = addPiece(pieces, last, -1, 0, -1, "second_floor_2", rotation, false);
            last = addPiece(pieces, last, -1, 4, -1, "third_floor_2", rotation, false);
            last = addPiece(pieces, last, -1, 8, -1, "third_roof", rotation, true);
            ECGenerator tg = (gd, p, off, ps, r) -> towerGenerator(gd, p, off, ps, r, shipCreated);
            recursiveChildren(tg, genDepth + 1, last, null, pieces, random);
        }
        return true;
    }

    private boolean towerBridgeGenerator(int genDepth, ECPiece parent, int[] offset, List<ECPiece> pieces, VSurface.LegacyRandom random, boolean[] shipCreated) {
        VTemplate.Rot rotation = parent.rotation();
        int bridgeLength = random.nextInt(4) + 1;
        ECPiece last = addPiece(pieces, parent, 0, 0, -4, "bridge_piece", rotation, true);
        int nextY = 0;
        for (int i = 0; i < bridgeLength; i++) {
            if (random.nextBoolean()) {
                last = addPiece(pieces, last, 0, nextY, -4, "bridge_piece", rotation, true);
                nextY = 0;
            } else if (random.nextBoolean()) {
                last = addPiece(pieces, last, 0, nextY, -4, "bridge_steep_stairs", rotation, true);
                nextY = 4;
            } else {
                last = addPiece(pieces, last, 0, nextY, -8, "bridge_gentle_stairs", rotation, true);
                nextY = 4;
            }
        }
        if (!shipCreated[0] && random.nextInt(10 - genDepth) == 0) {
            addPiece(pieces, last, -8 + random.nextInt(8), nextY, -70 + random.nextInt(10), "ship", rotation, true);
            shipCreated[0] = true;
        } else {
            ECGenerator htg = (gd, p, off, ps, r) -> houseTowerGenerator(gd, p, off, ps, r, shipCreated);
            if (!recursiveChildren(htg, genDepth + 1, last, new int[]{-3, nextY + 1, -11}, pieces, random)) return false;
        }
        addPiece(pieces, last, 4, nextY, 0, "bridge_end", combineRotation(rotation, VTemplate.Rot.CLOCKWISE_180), true);
        return true;
    }

    private boolean fatTowerGenerator(int genDepth, ECPiece parent, int[] offset, List<ECPiece> pieces, VSurface.LegacyRandom random, boolean[] shipCreated) {
        VTemplate.Rot rotation = parent.rotation();
        ECPiece last = addPiece(pieces, parent, -3, 4, -3, "fat_tower_base", rotation, true);
        last = addPiece(pieces, last, 0, 4, 0, "fat_tower_middle", rotation, true);
        for (int i = 0; i < 2 && random.nextInt(3) != 0; i++) {
            last = addPiece(pieces, last, 0, 8, 0, "fat_tower_middle", rotation, true);
            for (int[] bridge : FAT_TOWER_BRIDGES) {
                if (random.nextBoolean()) {
                    ECPiece bridgeStart = addPiece(pieces, last, bridge[1], bridge[2], bridge[3],
                            "bridge_end", combineRotation(rotation, VTemplate.Rot.VALUES[bridge[0]]), true);
                    ECGenerator tbg = (gd, p, off, ps, r) -> towerBridgeGenerator(gd, p, off, ps, r, shipCreated);
                    recursiveChildren(tbg, genDepth + 1, bridgeStart, null, pieces, random);
                }
            }
        }
        addPiece(pieces, last, -2, 8, -2, "fat_tower_top", rotation, true);
        return true;
    }

    /** Root of the recursive tree, invoked from {@link #assembleEndCity}. */
    private boolean towerGenerator(int genDepth, ECPiece parent, int[] offset, List<ECPiece> pieces, VSurface.LegacyRandom random, boolean[] shipCreated) {
        VTemplate.Rot rotation = parent.rotation();
        ECPiece last = addPiece(pieces, parent, 3 + random.nextInt(2), -3, 3 + random.nextInt(2), "tower_base", rotation, true);
        last = addPiece(pieces, last, 0, 7, 0, "tower_piece", rotation, true);
        ECPiece bridgePiece = random.nextInt(3) == 0 ? last : null;
        int towerHeight = 1 + random.nextInt(3);
        for (int i = 0; i < towerHeight; i++) {
            last = addPiece(pieces, last, 0, 4, 0, "tower_piece", rotation, true);
            if (i < towerHeight - 1 && random.nextBoolean()) bridgePiece = last;
        }
        if (bridgePiece != null) {
            for (int[] bridge : TOWER_BRIDGES) {
                if (random.nextBoolean()) {
                    ECPiece bridgeStart = addPiece(pieces, bridgePiece, bridge[1], bridge[2], bridge[3],
                            "bridge_end", combineRotation(rotation, VTemplate.Rot.VALUES[bridge[0]]), true);
                    ECGenerator tbg = (gd, p, off, ps, r) -> towerBridgeGenerator(gd, p, off, ps, r, shipCreated);
                    recursiveChildren(tbg, genDepth + 1, bridgeStart, null, pieces, random);
                }
            }
            addPiece(pieces, last, -1, 4, -1, "tower_top", rotation, true);
        } else {
            if (genDepth != 7) {
                ECGenerator ftg = (gd, p, off, ps, r) -> fatTowerGenerator(gd, p, off, ps, r, shipCreated);
                return recursiveChildren(ftg, genDepth + 1, last, null, pieces, random);
            }
            addPiece(pieces, last, -1, 4, -1, "tower_top", rotation, true);
        }
        return true;
    }

    /** Structure.getCornerHeights (one corner): first occupied Y scanning down from the noise ceiling, matching WORLD_SURFACE_WG's "first solid, +1" convention used elsewhere in this project. */
    private int firstOccupiedHeight(int x, int z) {
        for (int y = MIN_Y + HEIGHT - 1; y >= MIN_Y; y--) {
            if (testSolid(x, y, z)) return y + 1;
        }
        return MIN_Y;
    }

    /** TheEndBiomeSource.getNoiseBiome: center=the_end, else by the island-noise erosion value. */
    public String biomeAt(int blockX, int blockZ) {
        int chunkX = blockX >> 4, chunkZ = blockZ >> 4;
        if ((long) chunkX * chunkX + (long) chunkZ * chunkZ <= 4096L) return "minecraft:the_end";
        int wbx = (chunkX * 2 + 1) * 8, wbz = (chunkZ * 2 + 1) * 8;
        double h = (VDensity.endHeightValue(islandNoise, wbx / 8, wbz / 8) - 8.0) / 128.0;
        if (h > 0.25) return "minecraft:end_highlands";
        if (h >= -0.0625) return "minecraft:end_midlands";
        return h < -0.21875 ? "minecraft:small_end_islands" : "minecraft:end_barrens";
    }

    private RegistryKey<Biome> biomeKey(int blockX, int blockZ) {
        return switch (biomeAt(blockX, blockZ)) {
            case "minecraft:end_highlands" -> Biome.END_HIGHLANDS;
            case "minecraft:end_midlands" -> Biome.END_MIDLANDS;
            case "minecraft:small_end_islands" -> Biome.SMALL_END_ISLANDS;
            case "minecraft:end_barrens" -> Biome.END_BARRENS;
            default -> Biome.THE_END;
        };
    }

    /** Test hook: solid (density &gt; 0) at a block, in cell mode. */
    public boolean testSolid(int x, int y, int z) {
        VDensity.setCellMode(true);
        try {
            return finalDensity.compute(x, y, z) > 0.0;
        } finally {
            VDensity.setCellMode(false);
        }
    }

    private static JsonObject read(Gson gson, String resource) {
        try (var in = VEndGen.class.getResourceAsStream(resource)) {
            return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new IllegalStateException("Missing " + resource, e);
        }
    }
}
