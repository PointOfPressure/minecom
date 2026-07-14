package dev.pointofpressure.minecom.worldgen;

import dev.pointofpressure.minecom.worldgen.vanilla.VBlockRotate;
import dev.pointofpressure.minecom.worldgen.vanilla.VJigsaw;
import dev.pointofpressure.minecom.worldgen.vanilla.VStructureGen;
import dev.pointofpressure.minecom.worldgen.vanilla.VStructureManager;
import dev.pointofpressure.minecom.worldgen.vanilla.VStructures;
import dev.pointofpressure.minecom.worldgen.vanilla.VSurface;
import dev.pointofpressure.minecom.worldgen.vanilla.VTemplate;
import dev.pointofpressure.minecom.worldgen.vanilla.XRandom;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

import java.util.Map;
import java.util.Random;

/**
 * Nether terrain: a netherrack cave-world between bedrock floor and ceiling,
 * carved by 3D noise into caverns, with the lava sea at y=31, soul sand and
 * gravel patches, glowstone on cavern ceilings, and quartz/gold/ancient debris.
 * Deterministic from the seed. Height range 0-128.
 */
public final class NetherGen implements Generator {
    private static final long SEED = 0x4E65746865724DL; // "NetherM"
    public static final int LAVA_SEA = 31;
    private static final int CEILING = 127;

    @Override
    public void generate(GenerationUnit unit) {
        var start = unit.absoluteStart();
        int baseX = start.blockX(), baseZ = start.blockZ();
        var mod = unit.modifier();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int wx = baseX + dx, wz = baseZ + dz;
                for (int y = 0; y <= CEILING; y++) {
                    // bedrock shells
                    if (y <= 4 && h(mix(wx, y, wz)) < (5 - y) / 5.0) {
                        mod.setBlock(wx, y, wz, Block.BEDROCK);
                        continue;
                    }
                    if (y >= CEILING - 4 && h(mix(wx, y * 3, wz)) < (y - (CEILING - 5)) / 5.0) {
                        mod.setBlock(wx, y, wz, Block.BEDROCK);
                        continue;
                    }
                    boolean carved = carved(wx, y, wz);
                    if (carved) {
                        if (y <= LAVA_SEA) mod.setBlock(wx, y, wz, Block.LAVA);
                        // else air (cavern)
                        continue;
                    }
                    mod.setBlock(wx, y, wz, material(wx, y, wz));
                }
                decorateColumn(mod, wx, wz);
            }
        }
        ores(mod, baseX, baseZ);
        fortress(mod, baseX, baseZ);
        netherFossils(mod, baseX, baseZ);
        bastionRemnant(mod, baseX, baseZ);
        ruinedPortalNether(mod, baseX, baseZ);
        for (int bx = 0; bx < 4; bx++) {
            for (int bz = 0; bz < 4; bz++) {
                RegistryKey<Biome> biome = Biome.NETHER_WASTES;
                double soul = fbm2(baseX + bx * 4 + 2, baseZ + bz * 4 + 2, 1 / 200.0, 2, 7);
                if (soul > 0.45) biome = Biome.SOUL_SAND_VALLEY;
                else if (soul < -0.45) biome = Biome.CRIMSON_FOREST;
                for (int y = 0; y < 128; y += 4) {
                    mod.setBiome(baseX + bx * 4 + 2, y, baseZ + bz * 4 + 2, biome);
                }
            }
        }
    }

    /** Big caverns from two 3D noise fields; opens wide around the lava sea level. */
    private static boolean carved(int x, int y, int z) {
        if (y <= 4 || y >= CEILING - 4) return false;
        double n = fbm3(x, y * 1.4, z, 1 / 48.0, 3, 1);
        double heightBias = y < 45 ? 0.18 : y > 100 ? -0.1 : 0.05;
        return n + heightBias > 0.18;
    }

    /** Nether biome id at a column (matches the generator's soul-noise assignment). */
    public static String biomeAt(int x, int z) {
        double soul = fbm2(x, z, 1 / 200.0, 2, 7);
        if (soul > 0.45) return "minecraft:soul_sand_valley";
        if (soul < -0.45) return "minecraft:crimson_forest";
        return "minecraft:nether_wastes";
    }

    private static final int FORTRESS_SPACING = 432;   // ~27 chunks
    private static final int FORTRESS_Y = 66;

    /**
     * A bounded nether fortress: a nether-brick platform + railed bridges, plus one blaze
     * spawner ({@code ClassicSpawners.registerSpawnerNether}) at the platform's own center —
     * real vanilla places a blaze spawner in a specific "nether_bridge" piece variant that has
     * no equivalent here (this platform is a documented stand-in, not a piece-tree port, see the
     * class javadoc), so a single fixed position is used instead of a real piece-driven one; this
     * is the same kind of approximation already accepted for this method's platform/bridge shape
     * itself.
     */
    private static void fortress(net.minestom.server.instance.generator.UnitModifier mod, int baseX, int baseZ) {
        int fcx = Math.floorDiv(baseX, FORTRESS_SPACING) * FORTRESS_SPACING + FORTRESS_SPACING / 2;
        int fcz = Math.floorDiv(baseZ, FORTRESS_SPACING) * FORTRESS_SPACING + FORTRESS_SPACING / 2;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int wx = baseX + dx, wz = baseZ + dz;
                int lx = wx - fcx, lz = wz - fcz;
                boolean platform = Math.abs(lx) <= 6 && Math.abs(lz) <= 6;
                boolean bridgeX = Math.abs(lz) <= 1 && lx > 6 && lx <= 30;
                boolean bridgeZ = Math.abs(lx) <= 1 && lz > 6 && lz <= 30;
                if (!(platform || bridgeX || bridgeZ)) continue;
                mod.setBlock(wx, FORTRESS_Y, wz, Block.NETHER_BRICKS);   // walkway
                boolean edge = platform ? (Math.abs(lx) == 6 || Math.abs(lz) == 6)
                        : (bridgeX ? Math.abs(lz) == 1 : Math.abs(lx) == 1);
                for (int y = FORTRESS_Y + 1; y <= FORTRESS_Y + 5; y++) mod.setBlock(wx, y, wz, Block.AIR);
                if (lx == 0 && lz == 0) {
                    mod.setBlock(wx, FORTRESS_Y + 1, wz, Block.SPAWNER);
                    dev.pointofpressure.minecom.blocks.ClassicSpawners.registerSpawnerNether(
                            wx, FORTRESS_Y + 1, wz, "minecraft:blaze");
                }
                if (edge) {
                    mod.setBlock(wx, FORTRESS_Y + 1, wz, Block.NETHER_BRICK_FENCE);
                    mod.setBlock(wx, FORTRESS_Y + 2, wz, Block.NETHER_BRICK_FENCE);
                }
            }
        }
    }

    /** Test hook: the world position of the blaze spawner in the fortress cell containing (worldX, worldZ) — pure math, matches {@link #fortress}'s own center calc. */
    public static int[] testFortressSpawnerPos(int worldX, int worldZ) {
        int fcx = Math.floorDiv(worldX, FORTRESS_SPACING) * FORTRESS_SPACING + FORTRESS_SPACING / 2;
        int fcz = Math.floorDiv(worldZ, FORTRESS_SPACING) * FORTRESS_SPACING + FORTRESS_SPACING / 2;
        return new int[]{fcx, FORTRESS_Y + 1, fcz};
    }

    // ------------------------------------------------------------------ nether fossils

    /**
     * Single NBT-template structure, decompiled from `NetherFossilStructure`/
     * `NetherFossilPieces`. Unlike every overworld structure this project generates, this
     * project's Nether terrain (`NetherGen`, this whole file) is NOT a bit-exact decompiled
     * port of real vanilla — it's a deterministic-but-approximate cavern-noise generator
     * keyed off its own internal `SEED` constant, not the user's actual world seed (the
     * SAME simplification already accepted for this file's pre-existing `fortress(...)`
     * method, which also ignores the world seed and uses pure positional math). Nether
     * fossil PLACEMENT reuses the real vanilla algorithm and RNG mechanics faithfully
     * (`VStructures`' random-spread placement grid, `VSurface.LegacyRandom`'s real
     * `setLargeFeatureSeed` LCG, the real chunk-relative-X/Z + uniform-height-then-scan-down
     * generation-point search, the real rotation + 14-template weighted pool draw) — only
     * the underlying TERRAIN it's placed onto is approximate, consistent with everything
     * else already generated in this dimension. Real vanilla's height search decrements Y
     * from a uniform `[32, belowTop(2)]` sample until finding `current.isAir() &&
     * (below.is(SOUL_SAND) || below.isFaceSturdy(UP))`; reimplemented using this project's
     * own `carved(x,y,z)` cavern predicate (carved=air, not-carved=solid) as the substance
     * check, treating ANY solid block as qualifying (not just soul sand specifically — a
     * reasonable simplification given `carved()` doesn't distinguish material types, the
     * same coarse-substance tradeoff already used for buried treasure's `SAND`/`SANDSTONE`
     * stand-ins). Real vanilla's sea-level floor for this scan is the Nether's own sea level;
     * this project's Nether has no separate "sea level" concept, so the existing
     * `LAVA_SEA`(31) constant is reused as the scan floor, matching its real role as this
     * dimension's liquid-surface analog. NOT implemented: the dried ghast entity spawn
     * (`placeDriedGhast`'s positional-random entity placement — this project's Nether
     * generation is entirely block-based with no entity-spawn-at-worldgen-time mechanism,
     * matching every other structure's established "no worldgen-time entity spawns"
     * precedent this session).
     */
    private record FossilPiece(VTemplate template, int baseX, int baseY, int baseZ, VTemplate.Rot rotation) {}

    private static final String NETHER_FOSSIL_SET = "minecraft:nether_fossils";
    private static final VStructures NETHER_FOSSIL_PLACEMENT = new VStructures(SEED);
    private static final String[] NETHER_FOSSIL_TEMPLATES = {
            "minecraft:nether_fossils/fossil_1", "minecraft:nether_fossils/fossil_2", "minecraft:nether_fossils/fossil_3",
            "minecraft:nether_fossils/fossil_4", "minecraft:nether_fossils/fossil_5", "minecraft:nether_fossils/fossil_6",
            "minecraft:nether_fossils/fossil_7", "minecraft:nether_fossils/fossil_8", "minecraft:nether_fossils/fossil_9",
            "minecraft:nether_fossils/fossil_10", "minecraft:nether_fossils/fossil_11", "minecraft:nether_fossils/fossil_12",
            "minecraft:nether_fossils/fossil_13", "minecraft:nether_fossils/fossil_14"};
    private static final Map<Long, FossilPiece> FOSSIL_CACHE = java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    private static void netherFossils(net.minestom.server.instance.generator.UnitModifier mod, int baseX, int baseZ) {
        int chunkX = baseX >> 4, chunkZ = baseZ >> 4;
        int minX = baseX, minZ = baseZ, maxX = baseX + 15, maxZ = baseZ + 15;
        int r = 1; // fossil templates are a handful of blocks wide; 1-chunk radius is safe
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ccx = chunkX + dx, ccz = chunkZ + dz;
                if (!NETHER_FOSSIL_PLACEMENT.isStructureChunk(NETHER_FOSSIL_SET, ccx, ccz)) continue;
                long key = ((long) ccx << 32) ^ (ccz & 0xFFFFFFFFL);
                FossilPiece piece = FOSSIL_CACHE.containsKey(key) ? FOSSIL_CACHE.get(key) : assembleFossil(ccx, ccz);
                FOSSIL_CACHE.put(key, piece);
                if (piece == null) continue;
                for (VTemplate.BlockInfo b : piece.template().blocks) {
                    int[] wp = VTemplate.transform(b.x, b.y, b.z, piece.rotation(), 0, 0);
                    int wx = wp[0] + piece.baseX(), wy = wp[1] + piece.baseY(), wz = wp[2] + piece.baseZ();
                    if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                    String name = b.state.key().asString();
                    if (name.equals("minecraft:structure_block") || name.equals("minecraft:structure_void") || name.equals("minecraft:air")) continue;
                    mod.setBlock(wx, wy, wz, VBlockRotate.rotate(b.state, piece.rotation()));
                }
            }
        }
    }

    /** Test hook: {rotationOrdinal, sizeX, sizeY, sizeZ, blockCount, baseX, baseY, baseZ}, or null. */
    public static int[] testNetherFossilAt(int chunkX, int chunkZ) {
        if (!NETHER_FOSSIL_PLACEMENT.isStructureChunk(NETHER_FOSSIL_SET, chunkX, chunkZ)) return null;
        FossilPiece p = assembleFossil(chunkX, chunkZ);
        if (p == null) return null;
        return new int[]{p.rotation().ordinal(), p.template().sizeX, p.template().sizeY, p.template().sizeZ,
                p.template().blocks.size(), p.baseX(), p.baseY(), p.baseZ()};
    }

    private static FossilPiece assembleFossil(int ccx, int ccz) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(SEED, ccx, ccz);

        int west = ccx << 4, north = ccz << 4;
        int blockX = west + random.nextInt(16);
        int blockZ = north + random.nextInt(16);
        int y = 32 + random.nextInt(125 - 32 + 1); // HeightProvider.uniform(absolute 32, belowTop 2 of CEILING=127)

        while (y > LAVA_SEA) {
            if (carved(blockX, y, blockZ) && !carved(blockX, y - 1, blockZ)) break;
            y--;
        }
        if (y <= LAVA_SEA) return null;

        if (!biomeAt(blockX, blockZ).equals("minecraft:soul_sand_valley")) return null;

        VTemplate.Rot rotation = VTemplate.Rot.VALUES[random.nextInt(4)];
        String templateLoc = NETHER_FOSSIL_TEMPLATES[random.nextInt(NETHER_FOSSIL_TEMPLATES.length)];
        VTemplate template = VTemplate.load(templateLoc);
        if (template.sizeX == 0) return null;

        return new FossilPiece(template, blockX, y, blockZ, rotation);
    }

    // ------------------------------------------------------------------ bastion remnant

    /**
     * Reuses the general `VJigsaw` engine directly (like `pillager_outpost`, not through
     * `VStructureManager` since that's overworld-only) — real vanilla's `bastion_remnant`
     * config is `type: minecraft:jigsaw`, `start_height.absolute=33` (a fixed Y, no
     * heightmap projection — matches `VJigsaw.Def.constant`, the same shape already proven
     * for `ancient_city`). `size=6` -> maxDepth=6, `max_distance_from_center=80` ->
     * maxDistH=maxDistV=80, `use_expansion_hack=false`. 167 real NBT templates extracted
     * (bridge/hoglin_stable/units/treasure/mobs/blocks subtrees) plus 5 named processor
     * lists this project didn't have before (`bastion_generic_degradation` was already
     * pre-staged from an earlier point predating this session; `bridge`,
     * `rampart_degradation`, `entrance_replacement`, `stable_degradation` extracted this
     * entry). **A real, verified-safe simplification, not a guess**: all 5 of these
     * processor lists exclusively use `minecraft:rule` processors whose
     * `location_predicate` is `minecraft:always_true` in every single rule — confirmed by
     * grepping every extracted file before relying on this — meaning the real predicate
     * NEVER actually inspects the existing-world-block read (`Sink.get(x,y,z)`) it's
     * technically passed. This project's Nether generator has no terrain read-back
     * capability at all (`UnitModifier` is write-only, unlike the overworld's
     * `VanillaGen`/`decoratedData` cache), so a trivial constant-`AIR` `Sink` is used here —
     * verified behaviorally equivalent to a real read-back Sink for bastion specifically,
     * NOT a general-purpose claim about arbitrary future processor lists. **The
     * fortress/bastion placement-grid conflict, investigated and confirmed real**: real
     * vanilla's `nether_complexes` structure_set weights fortress(2) and bastion_remnant(3)
     * as MUTUALLY EXCLUSIVE picks per placement-grid cell, but this project's pre-existing
     * `fortress(...)` method (predates this session) places a fortress UNCONDITIONALLY at
     * every `FORTRESS_SPACING`(432)-block grid cell, with no placement-grid gate at all — a
     * probe (`ProbeBastionFortressConflict.java`) scanning a 600x600-chunk area for seed
     * 20260708 found a real, non-negligible 1.44% collision rate (7 of 487 real bastion
     * candidates geometrically overlapped the ALWAYS-PRESENT fortress footprint of their
     * nearest grid cell). Rather than the invasive fix (migrating the pre-existing,
     * already-verified fortress code to the real placement-grid model), this project uses a
     * lightweight, additive defensive check: skip bastion placement entirely at any
     * candidate whose position falls within its nearest fortress cell's footprint (the same
     * platform/bridge-shape check `fortress()` itself uses), touching zero pre-existing
     * fortress code. NOT implemented: fine-grained bastion sub-structure content that would
     * need real terrain reads for correctness beyond the `always_true` degradation rules
     * (there is none, per the analysis above — this is a complete structural placement, not
     * a partial slice, modulo the terrain-read caveat itself).
     */
    private static final String BASTION_SET = "minecraft:nether_complexes";
    private static final VStructures BASTION_PLACEMENT = new VStructures(SEED);
    private static final VJigsaw BASTION_JIGSAW = new VJigsaw(SEED);
    private static final VJigsaw.Def BASTION_DEF = VJigsaw.Def.constant("minecraft:bastion/starts", null, 6, 33, 80, 80, false);
    private static final java.util.Set<String> BASTION_BIOMES = java.util.Set.of(
            "minecraft:crimson_forest", "minecraft:nether_wastes", "minecraft:soul_sand_valley", "minecraft:warped_forest");
    private static final Map<Long, VJigsaw.Assembly> BASTION_CACHE = java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    private static void bastionRemnant(net.minestom.server.instance.generator.UnitModifier mod, int baseX, int baseZ) {
        int chunkX = baseX >> 4, chunkZ = baseZ >> 4;
        int minX = baseX, minZ = baseZ, maxX = baseX + 15, maxZ = baseZ + 15;
        int r = BASTION_DEF.maxDistH / 16 + 2;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ccx = chunkX + dx, ccz = chunkZ + dz;
                if (!BASTION_PLACEMENT.isStructureChunk(BASTION_SET, ccx, ccz)) continue;
                long key = ((long) ccx << 32) ^ (ccz & 0xFFFFFFFFL);
                VJigsaw.Assembly asm = BASTION_CACHE.containsKey(key) ? BASTION_CACHE.get(key) : assembleBastion(ccx, ccz);
                BASTION_CACHE.put(key, asm);
                if (asm == null) continue;
                for (VJigsaw.Piece piece : asm.pieces) {
                    int[] bb = piece.bb;
                    if (bb[3] < minX || bb[0] > maxX || bb[5] < minZ || bb[2] > maxZ) continue;
                    VStructureGen.placePieceClipped(piece, minX, minZ, maxX, maxZ, new VStructureGen.Canvas() {
                        public Block get(int x, int y, int z) { return Block.AIR; }
                        public void set(int x, int y, int z, Block block) { mod.setBlock(x, y, z, block); }
                    });
                }
            }
        }
    }

    /** Test hook: {pieceCount, centerX, centerY, centerZ}, or null. */
    public static int[] testBastionAt(int chunkX, int chunkZ) {
        if (!BASTION_PLACEMENT.isStructureChunk(BASTION_SET, chunkX, chunkZ)) return null;
        VJigsaw.Assembly asm = assembleBastion(chunkX, chunkZ);
        if (asm == null) return null;
        return new int[]{asm.pieces.size(), asm.centerX, asm.centerY, asm.centerZ};
    }

    private static VJigsaw.Assembly assembleBastion(int ccx, int ccz) {
        VJigsaw.Assembly asm = BASTION_JIGSAW.assembleFull(BASTION_DEF, ccx, ccz, Map.of());
        if (asm == null) return null;
        if (!BASTION_BIOMES.contains(biomeAt(asm.centerX, asm.centerZ))) return null;
        if (isInFortressFootprint(asm.centerX, asm.centerZ)) return null; // see class javadoc: fortress/bastion conflict
        return asm;
    }

    /** Same platform/bridge shape fortress() itself carves, checked against the NEAREST fortress cell (incl. neighbors). */
    private static boolean isInFortressFootprint(int wx, int wz) {
        for (int dcx = -1; dcx <= 1; dcx++) {
            for (int dcz = -1; dcz <= 1; dcz++) {
                int fcx = Math.floorDiv(wx, FORTRESS_SPACING) * FORTRESS_SPACING + FORTRESS_SPACING / 2 + dcx * FORTRESS_SPACING;
                int fcz = Math.floorDiv(wz, FORTRESS_SPACING) * FORTRESS_SPACING + FORTRESS_SPACING / 2 + dcz * FORTRESS_SPACING;
                int lx = wx - fcx, lz = wz - fcz;
                boolean platform = Math.abs(lx) <= 6 && Math.abs(lz) <= 6;
                boolean bridge = (Math.abs(lz) <= 1 && Math.abs(lx) <= 30) || (Math.abs(lx) <= 1 && Math.abs(lz) <= 30);
                if (platform || bridge) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ ruined portal (nether flavor)

    /**
     * The 7th and final real vanilla `ruined_portal` biome flavor, closing out the
     * `ruined_portals` structure set (6/7 flavors were already wired via
     * `VStructureManager`'s overworld `RPFlavor`/`RPSetup` scaffolding earlier this
     * session). `ruined_portal_nether` generates IN the Nether dimension, so — like
     * `nether_fossil`/`bastion_remnant` — it's implemented directly here rather than
     * through `VStructureManager` (overworld-only). Reuses the SAME `PORTAL_TEMPLATES`
     * (10)/`GIANT_PORTAL_TEMPLATES`(3) NBT template pools and `RUINED_PORTAL_SET`
     * placement grid already extracted/wired for the overworld flavors — no new resources
     * needed. Single-Setup list (weight 1.0, `air_pocket_probability=0.5` — a real
     * consumed draw, not a fast-path 0/1), decompiled precisely from
     * `RuinedPortalStructure.findSuitableY`'s `IN_NETHER` branch — **genuinely simpler
     * than the overworld flavors, not harder**: it's a PURE RNG Y-range pick with NO
     * heightmap/terrain read at all (unlike `ON_LAND_SURFACE`/`ON_OCEAN_FLOOR`/
     * `UNDERGROUND`/`PARTLY_BURIED`/`IN_MOUNTAIN`, which all consult `surfaceYAtCenter`):
     * if `airPocket`, uniform `[32,100]`; else 50/50 between `[27,29]` (a "sunken in lava"
     * placement) and `[29,100]`. NOT implemented: `replace_with_blackstone=true` (a
     * material-substitution decoration pass — cobblestone-family blocks in the template
     * swap to blackstone-family for the nether-appropriate "ruined" look — matches the
     * established "moss/vines skipped for other flavors" simplification precedent, same
     * class of detail), the 4-corner solid-ground Y refinement scan (matches every other
     * ruined portal flavor's identical documented simplification), and Mirror application
     * (drawn from RNG for stream-order correctness, never applied — matches every other
     * flavor).
     */
    private static final String RUINED_PORTAL_SET = "minecraft:ruined_portals";
    private static final int RUINED_PORTAL_RADIUS = 2;
    private static final VStructures RUINED_PORTAL_PLACEMENT = new VStructures(SEED);
    private static final String[] PORTAL_TEMPLATES = {
            "minecraft:ruined_portal/portal_1", "minecraft:ruined_portal/portal_2", "minecraft:ruined_portal/portal_3",
            "minecraft:ruined_portal/portal_4", "minecraft:ruined_portal/portal_5", "minecraft:ruined_portal/portal_6",
            "minecraft:ruined_portal/portal_7", "minecraft:ruined_portal/portal_8", "minecraft:ruined_portal/portal_9",
            "minecraft:ruined_portal/portal_10"};
    private static final String[] GIANT_PORTAL_TEMPLATES = {
            "minecraft:ruined_portal/giant_portal_1", "minecraft:ruined_portal/giant_portal_2", "minecraft:ruined_portal/giant_portal_3"};
    private static final java.util.Set<String> RUINED_PORTAL_NETHER_BIOMES = java.util.Set.of(
            "minecraft:nether_wastes", "minecraft:soul_sand_valley", "minecraft:crimson_forest",
            "minecraft:warped_forest", "minecraft:basalt_deltas");

    private record RuinedPortalPiece(VTemplate template, int baseX, int originY, int baseZ, VTemplate.Rot rotation, int pivotX, int pivotZ, boolean airPocket, boolean mirrored) {}

    private static final Map<Long, RuinedPortalPiece> RUINED_PORTAL_CACHE = java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    /**
     * Pure, no-write replica of {@link #generate}'s main per-column terrain decision (bedrock
     * shells, carved cavern/lava-sea, else {@link #material}) — lets ruined_portal_nether do
     * real terrain reads (ProtectedBlockProcessor/LavaSubmergedBlockProcessor) despite
     * Minestom's {@code UnitModifier} being write-only, the same limitation every other Nether
     * structure feature this session has been blocked on. Deliberately does NOT account for
     * decoration (ores/glowstone/etc) or earlier-placed structures within the SAME chunk render
     * — a real, bounded simplification (base terrain substance only), sufficient for what
     * Protected/LavaSubmerged actually need to check (is there a protected block or lava here),
     * not a full read-back of everything this generator could ever write.
     */
    private static Block netherBaseTerrainAt(int x, int y, int z) {
        if (y <= 4 && h(mix(x, y, z)) < (5 - y) / 5.0) return Block.BEDROCK;
        if (y >= CEILING - 4 && h(mix(x, y * 3, z)) < (y - (CEILING - 5)) / 5.0) return Block.BEDROCK;
        if (carved(x, y, z)) return y <= LAVA_SEA ? Block.LAVA : Block.AIR;
        return material(x, y, z);
    }

    private static void ruinedPortalNether(net.minestom.server.instance.generator.UnitModifier mod, int baseX, int baseZ) {
        int chunkX = baseX >> 4, chunkZ = baseZ >> 4;
        int minX = baseX, minZ = baseZ, maxX = baseX + 15, maxZ = baseZ + 15;
        int r = RUINED_PORTAL_RADIUS;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ccx = chunkX + dx, ccz = chunkZ + dz;
                if (!RUINED_PORTAL_PLACEMENT.isStructureChunk(RUINED_PORTAL_SET, ccx, ccz)) continue;
                long key = ((long) ccx << 32) ^ (ccz & 0xFFFFFFFFL);
                RuinedPortalPiece piece = RUINED_PORTAL_CACHE.containsKey(key) ? RUINED_PORTAL_CACHE.get(key) : assembleRuinedPortalNether(ccx, ccz);
                RUINED_PORTAL_CACHE.put(key, piece);
                if (piece == null) continue;
                for (VTemplate.BlockInfo b : piece.template().blocks) {
                    int[] wp = VTemplate.transformMirrored(b.x, b.y, b.z, piece.mirrored(), piece.rotation(), piece.pivotX(), piece.pivotZ());
                    int wx = wp[0] + piece.baseX(), wy = wp[1] + piece.originY(), wz = wp[2] + piece.baseZ();
                    if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                    String name = b.state.key().asString();
                    if (name.equals("minecraft:structure_block") || name.equals("minecraft:structure_void")) continue;
                    if (!piece.airPocket() && name.equals("minecraft:air")) continue;
                    Block preExisting = netherBaseTerrainAt(wx, wy, wz);
                    if (VStructureManager.RP_PROTECTED_BLOCKS.contains(preExisting.name())) continue;
                    // BlockState.mirror() is applied BEFORE rotate() in real vanilla — same order as the overworld flavors.
                    Block block = piece.mirrored() ? VStructureManager.mirrorFrontBack(b.state) : b.state;
                    block = VBlockRotate.rotate(block, piece.rotation());
                    block = applyRuinedPortalNetherRules(block, new VSurface.LegacyRandom(XRandom.blockSeed(wx, wy, wz)));
                    // ruined_portal_nether: mossiness=0.0, can_be_cold=false, replace_with_blackstone=true
                    block = VStructureManager.applyRuinedPortalMossiness(block, 0.0F, new VSurface.LegacyRandom(XRandom.blockSeed(wx, wy, wz)));
                    if (preExisting.name().equals("minecraft:lava") && VStructureManager.isRPNonFull(block.name())) block = Block.LAVA;
                    block = VStructureManager.applyBlackstoneReplace(block);
                    mod.setBlock(wx, wy, wz, block);
                }
            }
        }
    }

    /** Test hook: {rotationOrdinal, sizeX, sizeY, sizeZ, blockCount, originY}, or null. */
    public static int[] testRuinedPortalNetherAt(int chunkX, int chunkZ) {
        if (!RUINED_PORTAL_PLACEMENT.isStructureChunk(RUINED_PORTAL_SET, chunkX, chunkZ)) return null;
        RuinedPortalPiece p = assembleRuinedPortalNether(chunkX, chunkZ);
        if (p == null) return null;
        return new int[]{p.rotation().ordinal(), p.template().sizeX, p.template().sizeY, p.template().sizeZ,
                p.template().blocks.size(), p.originY()};
    }

    /**
     * Test hook: {placedBlockCount, goldBlockCount(pre-rule), magmaBlockCount, mossyStoneBricksCount,
     * crackedStoneBricksCount, cryingObsidianCount, blackstoneFamilyCount} after the full decoration
     * pipeline (nether flavor: mossiness=0.0, cold=false, replace_with_blackstone=true), or null.
     */
    public static int[] testRuinedPortalNetherDecoratedAt(int chunkX, int chunkZ) {
        if (!RUINED_PORTAL_PLACEMENT.isStructureChunk(RUINED_PORTAL_SET, chunkX, chunkZ)) return null;
        RuinedPortalPiece p = assembleRuinedPortalNether(chunkX, chunkZ);
        if (p == null) return null;
        int total = 0, gold = 0, magma = 0, mossy = 0, cracked = 0, cryingObsidian = 0, blackstoneFamily = 0;
        for (VTemplate.BlockInfo b : p.template().blocks) {
            int[] wp = VTemplate.transform(b.x, b.y, b.z, p.rotation(), p.pivotX(), p.pivotZ());
            int wx = wp[0] + p.baseX(), wy = wp[1] + p.originY(), wz = wp[2] + p.baseZ();
            String name = b.state.key().asString();
            if (name.equals("minecraft:structure_block") || name.equals("minecraft:structure_void")) continue;
            if (!p.airPocket() && name.equals("minecraft:air")) continue;
            if (name.equals("minecraft:gold_block")) gold++;
            Block block = VBlockRotate.rotate(b.state, p.rotation());
            block = applyRuinedPortalNetherRules(block, new VSurface.LegacyRandom(XRandom.blockSeed(wx, wy, wz)));
            block = VStructureManager.applyRuinedPortalMossiness(block, 0.0F, new VSurface.LegacyRandom(XRandom.blockSeed(wx, wy, wz)));
            block = VStructureManager.applyBlackstoneReplace(block);
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

    /** RuinedPortalStructure.findSuitableY's trailing corner-scan, Nether-terrain version (see call site javadoc). */
    private static int refineRuinedPortalNetherY(int minX, int minZ, int maxX, int maxZ, int startY) {
        int[][] corners = {{minX, minZ}, {maxX, minZ}, {minX, maxZ}, {maxX, maxZ}};
        int minY = 15; // Nether MIN_Y (0) + 15, matching real heightAccessor.getMinY()+15
        int projectedY = startY;
        for (; projectedY > minY; projectedY--) {
            int solidCount = 0;
            for (int[] c : corners) {
                String name = netherBaseTerrainAt(c[0], projectedY, c[1]).name();
                boolean solid = !name.equals("minecraft:air") && !name.equals("minecraft:lava");
                if (solid && ++solidCount == 3) return projectedY;
            }
        }
        return projectedY;
    }

    private static RuinedPortalPiece assembleRuinedPortalNether(int ccx, int ccz) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(SEED, ccx, ccz);

        boolean airPocket = random.nextFloat() < 0.5F; // sample(random, 0.5F): real draw, not a 0/1 fast path

        boolean giant = random.nextFloat() < 0.05F;
        String[] pool = giant ? GIANT_PORTAL_TEMPLATES : PORTAL_TEMPLATES;
        VTemplate template = VTemplate.load(pool[random.nextInt(pool.length)]);
        if (template.sizeX == 0) return null;

        VTemplate.Rot rotation = VTemplate.Rot.VALUES[random.nextInt(4)];
        boolean mirrored = random.nextFloat() >= 0.5F; // Mirror.FRONT_BACK, same draw/formula as the overworld flavors

        int pivotX = template.sizeX / 2, pivotZ = template.sizeZ / 2;
        int baseX = ccx << 4, baseZ = ccz << 4;
        int[] c1 = VTemplate.transformMirrored(0, 0, 0, mirrored, rotation, pivotX, pivotZ);
        int[] c2 = VTemplate.transformMirrored(template.sizeX - 1, template.sizeY - 1, template.sizeZ - 1, mirrored, rotation, pivotX, pivotZ);
        int centerX = baseX + (Math.min(c1[0], c2[0]) + Math.max(c1[0], c2[0])) / 2;
        int centerZ = baseZ + (Math.min(c1[2], c2[2]) + Math.max(c1[2], c2[2])) / 2;

        // findSuitableY(IN_NETHER): pure RNG range, no heightmap/terrain read at all (unlike
        // every overworld placement kind, whose initial Y comes from a heightmap sample).
        int originY;
        if (airPocket) {
            originY = 32 + random.nextInt(100 - 32 + 1);
        } else if (random.nextFloat() < 0.5F) {
            originY = 27 + random.nextInt(29 - 27 + 1);
        } else {
            originY = 29 + random.nextInt(100 - 29 + 1);
        }

        // findSuitableY's trailing 4-corner solid-ground corner-scan DOES still apply after the
        // above (real vanilla runs it unconditionally for every VerticalPlacement) — now
        // reachable thanks to netherBaseTerrainAt (see its own javadoc), matching the overworld
        // flavors' refineRuinedPortalY (VStructureManager) exactly, just using real Nether
        // terrain substances (bedrock/netherrack/soul_sand/gravel/blackstone = solid; air/lava =
        // not) in place of the overworld's coarse VAquifer.STONE check.
        int minX = baseX + Math.min(c1[0], c2[0]), maxX = baseX + Math.max(c1[0], c2[0]);
        int minZ = baseZ + Math.min(c1[2], c2[2]), maxZ = baseZ + Math.max(c1[2], c2[2]);
        originY = refineRuinedPortalNetherY(minX, minZ, maxX, maxZ, originY);

        if (!RUINED_PORTAL_NETHER_BIOMES.contains(biomeAt(centerX, centerZ))) return null;

        return new RuinedPortalPiece(template, baseX, originY, baseZ, rotation, pivotX, pivotZ, airPocket, mirrored);
    }

    /**
     * RuleProcessor for the nether flavor, simplified from the general overworld version
     * (VStructureManager.applyRuinedPortalRuleProcessor): ruined_portal_nether is never
     * on_ocean_floor and never cold (can_be_cold=false in the real JSON config), so the lava
     * rule collapses to the single non-cold/non-ocean-floor branch (20% -> magma_block).
     */
    private static Block applyRuinedPortalNetherRules(Block block, VSurface.LegacyRandom rng) {
        String name = block.name();
        if (name.equals("minecraft:gold_block")) return rng.nextFloat() < 0.3F ? Block.AIR : block;
        if (name.equals("minecraft:lava")) return rng.nextFloat() < 0.2F ? Block.MAGMA_BLOCK : block;
        if (name.equals("minecraft:netherrack")) return rng.nextFloat() < 0.07F ? Block.MAGMA_BLOCK : block;
        return block;
    }

    private static Block material(int x, int y, int z) {
        double patch = fbm3(x, y, z, 1 / 24.0, 2, 3);
        double soul = fbm2(x, z, 1 / 200.0, 2, 7);
        if (soul > 0.45 && patch > 0.1 && y < 70) return Block.SOUL_SAND;
        if (patch > 0.42) return Block.GRAVEL;
        if (patch < -0.48) return Block.BLACKSTONE;
        return Block.NETHERRACK;
    }

    /** Glowstone clusters hang under solid ceilings above open caverns. */
    private void decorateColumn(net.minestom.server.instance.generator.UnitModifier mod, int wx, int wz) {
        double roll = h(mix(wx, 999, wz));
        if (roll >= 0.012) return;
        for (int y = 100; y > LAVA_SEA + 8; y--) {
            if (!carved(wx, y, wz) && carved(wx, y - 1, wz)) {
                int size = 1 + (int) (h(mix(wx, 998, wz)) * 3);
                for (int i = 0; i < size; i++) mod.setBlock(wx, y - 1 - i, wz, Block.GLOWSTONE);
                return;
            }
        }
    }

    private static final int[][] NETHER_ORES = {
            // block ordinal marker, attempts, size, minY, maxY  (resolved below)
    };

    private void ores(net.minestom.server.instance.generator.UnitModifier mod, int baseX, int baseZ) {
        Random rng = new Random(mix(baseX >> 4, 777, baseZ >> 4) ^ SEED);
        placeOre(mod, rng, baseX, baseZ, Block.NETHER_QUARTZ_ORE, 14, 10, 10, 117);
        placeOre(mod, rng, baseX, baseZ, Block.NETHER_GOLD_ORE, 8, 8, 10, 117);
        placeOre(mod, rng, baseX, baseZ, Block.ANCIENT_DEBRIS, 2, 2, 8, 22);
        placeOre(mod, rng, baseX, baseZ, Block.MAGMA_BLOCK, 4, 8, LAVA_SEA - 2, LAVA_SEA + 6);
    }

    private void placeOre(net.minestom.server.instance.generator.UnitModifier mod, Random rng,
                          int baseX, int baseZ, Block ore, int attempts, int size, int minY, int maxY) {
        for (int i = 0; i < attempts; i++) {
            int x = 3 + rng.nextInt(10), z = 3 + rng.nextInt(10);
            int y = minY + rng.nextInt(Math.max(1, maxY - minY));
            int blob = 1 + rng.nextInt(size);
            for (int s = 0; s < blob; s++) {
                int wx = baseX + x, wz = baseZ + z;
                if (y > 4 && y < CEILING - 4 && !carved(wx, y, wz)) {
                    mod.setBlock(wx, y, wz, ore);
                }
                x = Math.max(3, Math.min(12, x + rng.nextInt(3) - 1));
                z = Math.max(3, Math.min(12, z + rng.nextInt(3) - 1));
                y = Math.max(minY, y + rng.nextInt(3) - 1);
            }
        }
    }

    /** A guaranteed-solid floor position near the given x/z for portal placement. */
    public static int floorNear(int x, int z) {
        for (int y = LAVA_SEA + 5; y < 100; y++) {
            if (!carved(x, y, z) && carved(x, y + 1, z) && carved(x, y + 2, z) && carved(x, y + 3, z)) {
                return y;
            }
        }
        return 70;
    }

    // ------------------------------------------------------------------ noise (same lattice family as WorldGen)

    private static double fbm2(double x, double z, double freq, int octaves, int salt) {
        double sum = 0, amp = 1, norm = 0, fx = x * freq, fz = z * freq;
        for (int o = 0; o < octaves; o++) {
            sum += amp * value2(fx, fz, salt * 31 + o);
            norm += amp; amp *= 0.5; fx *= 2; fz *= 2;
        }
        return sum / norm;
    }

    private static double fbm3(double x, double y, double z, double freq, int octaves, int salt) {
        double sum = 0, amp = 1, norm = 0, fx = x * freq, fy = y * freq, fz = z * freq;
        for (int o = 0; o < octaves; o++) {
            sum += amp * value3(fx, fy, fz, salt * 31 + o);
            norm += amp; amp *= 0.5; fx *= 2; fy *= 2; fz *= 2;
        }
        return sum / norm;
    }

    private static double value2(double x, double z, int salt) {
        int x0 = fl(x), z0 = fl(z);
        double fx = sm(x - x0), fz = sm(z - z0);
        double v00 = h(mix(x0, salt, z0)), v10 = h(mix(x0 + 1, salt, z0));
        double v01 = h(mix(x0, salt, z0 + 1)), v11 = h(mix(x0 + 1, salt, z0 + 1));
        double a = v00 + (v10 - v00) * fx, b = v01 + (v11 - v01) * fx;
        return (a + (b - a) * fz) * 2 - 1;
    }

    private static double value3(double x, double y, double z, int salt) {
        int x0 = fl(x), y0 = fl(y), z0 = fl(z);
        double fx = sm(x - x0), fy = sm(y - y0), fz = sm(z - z0);
        double v000 = h(mix(x0, y0 * 668265263 + salt, z0)), v100 = h(mix(x0 + 1, y0 * 668265263 + salt, z0));
        double v010 = h(mix(x0, (y0 + 1) * 668265263 + salt, z0)), v110 = h(mix(x0 + 1, (y0 + 1) * 668265263 + salt, z0));
        double v001 = h(mix(x0, y0 * 668265263 + salt, z0 + 1)), v101 = h(mix(x0 + 1, y0 * 668265263 + salt, z0 + 1));
        double v011 = h(mix(x0, (y0 + 1) * 668265263 + salt, z0 + 1)), v111 = h(mix(x0 + 1, (y0 + 1) * 668265263 + salt, z0 + 1));
        double x00 = v000 + (v100 - v000) * fx, x10 = v010 + (v110 - v010) * fx;
        double x01 = v001 + (v101 - v001) * fx, x11 = v011 + (v111 - v011) * fx;
        double y0v = x00 + (x10 - x00) * fy, y1v = x01 + (x11 - x01) * fy;
        return (y0v + (y1v - y0v) * fz) * 2 - 1;
    }

    private static int fl(double v) { int i = (int) v; return v < i ? i - 1 : i; }
    private static double sm(double t) { return t * t * (3 - 2 * t); }

    private static long mix(int x, int y, int z) {
        return x * 374761393L + y * 972663749L + z * 144305901L;
    }

    private static double h(long n) {
        long v = n ^ SEED;
        v ^= v >>> 33; v *= 0xFF51AFD7ED558CCDL; v ^= v >>> 33; v *= 0xC4CEB9FE1A85EC53L; v ^= v >>> 33;
        return (v >>> 11) / (double) (1L << 53);
    }
}
