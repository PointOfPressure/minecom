package dev.pointofpressure.minecom.data;

import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.List;

/** Exercises the vanilla-data engine without a running server. Run with --selftest or standalone. */
public final class SelfTest {
    private SelfTest() {}

    private static int passed, failed;
    private static final StringBuilder REPORT = new StringBuilder();

    public static String run() {
        passed = failed = 0;
        REPORT.setLength(0);
        VanillaData.load();
        REPORT.append("indexed: ").append(Recipes.shapedCount()).append(" shaped, ")
                .append(Recipes.shapelessCount()).append(" shapeless, ")
                .append(Recipes.smeltingCount()).append(" smeltable inputs\n");

        ItemStack ironPick = ItemStack.of(Material.IRON_PICKAXE);
        ItemStack shears = ItemStack.of(Material.SHEARS);
        ItemStack hand = ItemStack.AIR;

        check("stone + iron pickaxe -> cobblestone",
                drops(Block.STONE, ironPick).equals(List.of("minecraft:cobblestone x1")));
        check("grass_block -> dirt",
                drops(Block.GRASS_BLOCK, hand).equals(List.of("minecraft:dirt x1")));
        check("oak_log -> oak_log",
                drops(Block.OAK_LOG, hand).equals(List.of("minecraft:oak_log x1")));
        check("diamond_ore + iron pickaxe -> diamond",
                drops(Block.DIAMOND_ORE, ironPick).equals(List.of("minecraft:diamond x1")));
        check("oak_leaves + shears -> oak_leaves",
                drops(Block.OAK_LEAVES, shears).equals(List.of("minecraft:oak_leaves x1")));

        int saplings = 0, leaves = 0;
        for (int i = 0; i < 400; i++) {
            for (ItemStack drop : LootTables.blockDrops(Block.OAK_LEAVES, hand)) {
                if (drop.material() == Material.OAK_SAPLING) saplings++;
                if (drop.material() == Material.OAK_LEAVES) leaves++;
            }
        }
        check("oak_leaves bare hand: sapling ~5% (got " + saplings + "/400), never leaves (got " + leaves + ")",
                saplings > 2 && saplings < 60 && leaves == 0);

        int flint = 0;
        for (int i = 0; i < 400; i++) {
            for (ItemStack drop : LootTables.blockDrops(Block.GRAVEL, hand)) {
                if (drop.material() == Material.FLINT) flint++;
            }
        }
        check("gravel: flint ~10% (got " + flint + "/400)", flint > 10 && flint < 90);

        check("craft: 1 oak_log -> 4 oak_planks",
                craft2(Material.OAK_LOG, null, null, null).equals("minecraft:oak_planks x4"));
        check("craft: 2 planks vertical -> 4 sticks",
                craft2(Material.OAK_PLANKS, null, Material.OAK_PLANKS, null).equals("minecraft:stick x4"));
        check("craft: 4 planks -> crafting_table",
                craft2(Material.OAK_PLANKS, Material.OAK_PLANKS, Material.OAK_PLANKS, Material.OAK_PLANKS)
                        .equals("minecraft:crafting_table x1"));
        check("craft: wooden pickaxe (3 planks / stick / stick)",
                craft3(new Material[]{
                        Material.OAK_PLANKS, Material.OAK_PLANKS, Material.OAK_PLANKS,
                        null, Material.STICK, null,
                        null, Material.STICK, null
                }).equals("minecraft:wooden_pickaxe x1"));
        check("craft: 8 cobblestone ring -> furnace",
                craft3(new Material[]{
                        Material.COBBLESTONE, Material.COBBLESTONE, Material.COBBLESTONE,
                        Material.COBBLESTONE, null, Material.COBBLESTONE,
                        Material.COBBLESTONE, Material.COBBLESTONE, Material.COBBLESTONE
                }).equals("minecraft:furnace x1"));
        check("craft: offset stick recipe matches in corner",
                craft3(new Material[]{
                        null, null, Material.OAK_PLANKS,
                        null, null, Material.OAK_PLANKS,
                        null, null, null
                }).equals("minecraft:stick x4"));
        check("craft: garbage grid -> AIR",
                craft2(Material.DIRT, Material.DIAMOND, null, null).equals("air"));

        Recipes.Smelt iron = Recipes.smelt(Material.RAW_IRON);
        check("smelt raw_iron -> iron_ingot in 200 ticks",
                iron != null && iron.result().material() == Material.IRON_INGOT && iron.cookTicks() == 200);
        Recipes.Smelt stone = Recipes.smelt(Material.COBBLESTONE);
        check("smelt cobblestone -> stone", stone != null && stone.result().material() == Material.STONE);
        check("porkchop smeltable", Recipes.smelt(Material.PORKCHOP) != null);
        check("dirt not smeltable", Recipes.smelt(Material.DIRT) == null);
        check("coal is fuel 1600", Recipes.fuelTicks(Material.COAL) == 1600);
        check("planks are fuel 300", Recipes.fuelTicks(Material.OAK_PLANKS) == 300);
        check("dirt is not fuel", !Recipes.isFuel(Material.DIRT));

        int flesh = 0, runs = 200;
        for (int i = 0; i < runs; i++) {
            for (ItemStack drop : LootTables.entityDrops(EntityType.ZOMBIE)) {
                if (drop.material() == Material.ROTTEN_FLESH) flesh += drop.amount();
            }
        }
        check("zombie drops rotten flesh avg ~1 (got " + flesh + "/" + runs + ")",
                flesh > runs / 4 && flesh < runs * 3);
        check("sheep drops wool + mutton", !LootTables.entityDrops(EntityType.SHEEP).isEmpty());

        check("tag: oak_planks in #planks", VanillaData.itemHasTag(Material.OAK_PLANKS, "planks"));
        check("tag: stone mineable with pickaxe", VanillaData.blockHasTag(Block.STONE, "mineable/pickaxe"));
        check("tag: diamond_ore needs iron tool", VanillaData.blockHasTag(Block.DIAMOND_ORE, "needs_iron_tool"));

        // v0.3: block-state-aware loot
        Block youngWheat = Block.WHEAT.withProperty("age", "0");
        Block ripeWheat = Block.WHEAT.withProperty("age", "7");
        boolean youngOk = true;
        for (int i = 0; i < 50; i++) {
            for (ItemStack d : LootTables.blockDrops(youngWheat, hand)) {
                if (d.material() == Material.WHEAT) youngOk = false;
            }
        }
        check("young wheat never drops wheat", youngOk);
        boolean ripeWheatDrops = false;
        for (int i = 0; i < 20; i++) {
            for (ItemStack d : LootTables.blockDrops(ripeWheat, hand)) {
                if (d.material() == Material.WHEAT) ripeWheatDrops = true;
            }
        }
        check("ripe wheat drops wheat", ripeWheatDrops);

        // v0.3: item stats from official components
        float ironSword = Items.attackDamage(ItemStack.of(Material.IRON_SWORD));
        check("iron sword attack 6.0 from components (got " + ironSword + ")", ironSword == 6.0f);
        float diamondAxe = Items.attackDamage(ItemStack.of(Material.DIAMOND_AXE));
        check("diamond axe attack 9.0 from components (got " + diamondAxe + ")", diamondAxe == 9.0f);
        double chestplate = Items.armorPoints(ItemStack.of(Material.DIAMOND_CHESTPLATE));
        check("diamond chestplate armor 8.0 from components (got " + chestplate + ")", chestplate == 8.0);
        check("fall damage bypasses armor (official tag)",
                VanillaData.damageTypeHasTag("minecraft:fall", "bypasses_armor"));
        check("mob attack does not bypass armor",
                !VanillaData.damageTypeHasTag("minecraft:mob_attack", "bypasses_armor"));
        check("xp curve: level 30 = 1395 total",
                dev.pointofpressure.minecom.survival.Experience.xpForLevel(30) == 1395);
        check("mob xp: blaze=10, husk=5 (previously 0), wolf 1-3",
                dev.pointofpressure.minecom.survival.Experience.mobXp(EntityType.BLAZE) == 10
                        && dev.pointofpressure.minecom.survival.Experience.mobXp(EntityType.HUSK) == 5
                        && dev.pointofpressure.minecom.survival.Experience.mobXp(EntityType.WOLF) >= 1
                        && dev.pointofpressure.minecom.survival.Experience.mobXp(EntityType.WOLF) <= 3);
        var snowGen = new dev.pointofpressure.minecom.worldgen.vanilla.VanillaGen(20260708L);
        check("snow: snowy_plains is cold enough + has precipitation",
                snowGen.surface().hasPrecipitation("minecraft:snowy_plains")
                        && snowGen.surface().coldEnoughToSnow("minecraft:snowy_plains", 0, 90, 0));
        check("snow: desert has no precipitation (never snows)",
                !snowGen.surface().hasPrecipitation("minecraft:desert"));
        check("enchant coverage: mending/thorns/infinity/feather_falling now resolve (was a 9-entry allow-list)",
                dev.pointofpressure.minecom.data.Enchants.byName("mending") != null
                        && dev.pointofpressure.minecom.data.Enchants.byName("thorns") != null
                        && dev.pointofpressure.minecom.data.Enchants.byName("infinity") != null
                        && dev.pointofpressure.minecom.data.Enchants.byName("feather_falling") != null
                        && dev.pointofpressure.minecom.data.Enchants.byName("not_a_real_enchant") == null);
        check("mob xp: baby zombie is 2.5x (5 -> 12)",
                dev.pointofpressure.minecom.survival.Experience.mobXp(EntityType.ZOMBIE, true) == 12
                        && dev.pointofpressure.minecom.survival.Experience.mobXp(EntityType.ZOMBIE, false) == 5);

        // Structure placement (layer 1) — grid RNG verified bit-exact vs vanilla's
        // own RandomSpreadStructurePlacement over 266k chunks (0 mismatch); these
        // are known candidate chunks for seed 20260708 (freq=1 sets, no reduction).
        var struct = new dev.pointofpressure.minecom.worldgen.vanilla.VStructures(20260708L);
        check("ancient city placement grid (-43,-43)",
                struct.isStructureChunk("minecraft:ancient_cities", -43, -43));
        check("trial chamber placement grid (-26,-19)",
                struct.isStructureChunk("minecraft:trial_chambers", -26, -19));
        check("village placement grid (-18,5)",
                struct.isStructureChunk("minecraft:villages", -18, 5));
        check("pillager outpost placement grid (-105,-9)",
                struct.isStructureChunk("minecraft:pillager_outposts", -105, -9));
        check("placement is exclusive within a grid cell (no false neighbor)",
                !struct.isStructureChunk("minecraft:ancient_cities", -42, -43)
                        && !struct.isStructureChunk("minecraft:ancient_cities", -43, -42));

        // Stronghold concentric-rings placement — verified bit-exact vs vanilla's own
        // ChunkGeneratorStructureState.generateRingPositions (128/128 match, live world seed).
        var strongholdGen = new dev.pointofpressure.minecom.worldgen.vanilla.VanillaGen(20260708L);
        var ringPositions = strongholdGen.strongholds().positions();
        check("stronghold ring placement: 128 positions generated", ringPositions.size() == 128);
        check("stronghold ring placement: position 0 = (5,99) (bit-exact vs vanilla ChunkGeneratorStructureState)",
                ringPositions.get(0)[0] == 5 && ringPositions.get(0)[1] == 99);

        // Ancient city: real generation confirmed (not just placement-grid math) at a chunk
        // where the grid candidate ALSO has the required deep_dark biome (most grid
        // candidates don't — deep_dark is rare, so placement-grid-only checks like the one
        // above can pass even where the structure never actually generates). Counts real
        // ancient-city-specific blocks (chiseled_deepslate, soul_lantern/fire/sand, candles,
        // reinforced_deepslate, sculk_sensor) around chunk (-105,-37), seed 20260708.
        int acMats = 0;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                var acData = strongholdGen.decoratedData(-105 + dx, -37 + dz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = -64; y < 40; y++) {
                            var b = acData.get(x, y, z);
                            if (b == null) continue;
                            String n = b.key().asString();
                            if (n.contains("sculk") || n.contains("soul") || n.contains("reinforced_deepslate")
                                    || n.contains("candle") || n.contains("chiseled_deepslate")) acMats++;
                        }
                    }
                }
            }
        }
        check("ancient city real generation confirmed at (-105,-37): " + acMats + " deepslate-city/sculk/soul/candle blocks placed",
                acMats > 0);

        // Trial chamber: real generation confirmed at the SAME chunk already used for the
        // placement-grid check above (-26,-19) — unlike ancient_city, this chunk's biome
        // (plains, verified separately) genuinely satisfies trial_chambers' much broader
        // biome list, so the pre-existing placement-grid check was already at a valid
        // location. Counts real trial-chamber-specific blocks (tuff_bricks, chiseled_tuff,
        // polished_tuff, waxed copper family — deliberately excluding plain "tuff", which is
        // also a naturally-occurring deepslate-adjacent stone unrelated to any structure).
        int tcMats = 0;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                var tcData = strongholdGen.decoratedData(-26 + dx, -19 + dz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = -64; y < 20; y++) {
                            var b = tcData.get(x, y, z);
                            if (b == null) continue;
                            String n = b.key().asString();
                            if (n.contains("tuff_bricks") || n.contains("chiseled_tuff") || n.contains("polished_tuff")
                                    || n.contains("waxed_") || n.contains("trial_spawner") || n.contains("vault")) tcMats++;
                        }
                    }
                }
            }
        }
        check("trial chamber real generation confirmed at (-26,-19): " + tcMats + " tuff_bricks/copper/vault blocks placed",
                tcMats > 0);

        // Village: real generation confirmed at chunk (-21,-33) — NOT the pre-existing
        // placement-grid check's chunk (-18,5), whose ACTUAL jigsaw-assembly-center biome
        // turned out to be stony_shore (not plains), so the village legitimately never
        // generates there (a naive chunk-corner/middle biome guess is NOT a reliable proxy
        // for a jigsaw structure's real biome gate — the assembly's own center point is the
        // only correct thing to check; found via VJigsaw.assembleFull() directly). Counts
        // real village-specific blocks (oak_planks/door/stairs/slab/fence, cobblestone_wall,
        // composter, bell, hay_block, lectern, loom, cauldron, scaffolding, and the 6
        // job-site blocks — deliberately excluding oak_log, which is also naturally-occurring
        // and found ambiguous in an earlier probe this session).
        int vMats = 0;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                var vData = strongholdGen.decoratedData(-21 + dx, -33 + dz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 40; y < 110; y++) {
                            var b = vData.get(x, y, z);
                            if (b == null) continue;
                            String n = b.key().asString();
                            if (n.contains("oak_planks") || n.contains("oak_door") || n.contains("oak_stairs") || n.contains("oak_slab")
                                    || n.contains("oak_fence") || n.contains("cobblestone_wall") || n.contains("composter") || n.contains("bell")
                                    || n.contains("hay_block") || n.contains("lectern") || n.contains("loom") || n.contains("cauldron")
                                    || n.contains("scaffolding") || n.contains("smoker") || n.contains("blast_furnace") || n.contains("grindstone")
                                    || n.contains("fletching") || n.contains("cartography") || n.contains("stonecutter") || n.contains("barrel")) vMats++;
                        }
                    }
                }
            }
        }
        check("village real generation confirmed at (-21,-33): " + vMats + " oak_fence/bell/job-site blocks placed",
                vMats > 0);

        // Trail ruins: had NO SelfTest coverage at all before this check (not even
        // placement-grid math) — added using the CORRECT technique from the start (real
        // VJigsaw.assembleFull() center biome, not a naive chunk-corner/middle guess; see
        // the village entry above for why that distinction matters). Real match found at
        // chunk (13,-25), biome=taiga at the true assembly center, 19 pieces. Counts real
        // trail-ruins-specific blocks (mud_bricks, packed_mud, cracked_stone_bricks —
        // suspicious/archaeology sand and brush deliberately not required present, since
        // their placement is probabilistic per this project's established archaeology-skip
        // precedent elsewhere).
        int trMats = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                var trData = strongholdGen.decoratedData(13 + dx, -25 + dz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 60; y < 120; y++) {
                            var b = trData.get(x, y, z);
                            if (b == null) continue;
                            String n = b.key().asString();
                            if (n.contains("suspicious") || n.contains("archaeology") || n.contains("packed_mud")
                                    || n.contains("mud_bricks") || n.contains("brush") || n.contains("chiseled_stone_bricks")
                                    || n.contains("cracked_stone_bricks") || n.contains("moss_carpet")) trMats++;
                        }
                    }
                }
            }
        }
        check("trail ruins real generation confirmed at (13,-25): " + trMats + " mud_bricks/packed_mud blocks placed",
                trMats > 0);

        // Ruined portal (standard structure, both Setups — on_land_surface and underground —
        // plus the desert flavor; see VStructureManager's RPPiece javadoc for what's still
        // unimplemented: jungle/swamp/mountain/ocean/nether, mirror, and postProcess
        // erosion/decoration). Real placement-point search at known-good chunks for seed
        // 20260708: template chosen, rotated, sized, and Y-placed via the decompiled
        // RuinedPortalStructure algorithm.
        // y values below reflect the findSuitableY 4-corner solid-ground refinement scan
        // (added this session — see VStructureManager's refineRuinedPortalY javadoc); rotation/
        // size/blockCount are geometry-only and unaffected by the refinement.
        int[] rp1 = strongholdGen.structures().testRuinedPortalAt(-38, 5);
        check("ruined portal (on_land_surface) generates at (-38,5): rotation=CLOCKWISE_90, 10x8x9, 640 blocks, y=71",
                rp1 != null && rp1[0] == 1 && rp1[1] == 10 && rp1[2] == 8 && rp1[3] == 9
                        && rp1[4] == 640 && rp1[5] == 71);
        int[] rp2 = strongholdGen.structures().testRuinedPortalAt(-19, -39);
        check("ruined portal (on_land_surface) generates at (-19,-39): rotation=COUNTERCLOCKWISE_90, 8x9x9, 500 blocks, y=63",
                rp2 != null && rp2[0] == 3 && rp2[1] == 8 && rp2[2] == 9 && rp2[3] == 9
                        && rp2[4] == 500 && rp2[5] == 63);
        int[] rp3 = strongholdGen.structures().testRuinedPortalAt(5, 42);
        check("ruined portal (underground) generates at (5,42): rotation=CLOCKWISE_180, 9x12x9, 750 blocks, y=20 (well below surface)",
                rp3 != null && rp3[0] == 2 && rp3[1] == 9 && rp3[2] == 12 && rp3[3] == 9
                        && rp3[4] == 750 && rp3[5] == 20);
        int[] rp4 = strongholdGen.structures().testRuinedPortalAt("desert", -107, 171);
        check("ruined portal (desert, partly_buried) generates at (-107,171): rotation=CLOCKWISE_180, 9x12x9, 750 blocks, y=57",
                rp4 != null && rp4[0] == 2 && rp4[1] == 9 && rp4[2] == 12 && rp4[3] == 9
                        && rp4[4] == 750 && rp4[5] == 57);
        check("ruined portal placement grid rejects a non-candidate chunk (0,0)",
                strongholdGen.structures().testRuinedPortalAt(0, 0) == null);
        int[] rp5 = strongholdGen.structures().testRuinedPortalAt("jungle", -194, 82);
        check("ruined portal (jungle, on_land_surface) generates at (-194,82): rotation=NONE, 10x8x9, 640 blocks, y=69",
                rp5 != null && rp5[0] == 0 && rp5[1] == 10 && rp5[2] == 8 && rp5[3] == 9
                        && rp5[4] == 640 && rp5[5] == 69);
        int[] rp6 = strongholdGen.structures().testRuinedPortalAt("mountain", -68, -32);
        check("ruined portal (mountain, in_mountain) generates at (-68,-32): rotation=CLOCKWISE_90, 10x8x9, 640 blocks, y=128",
                rp6 != null && rp6[0] == 1 && rp6[1] == 10 && rp6[2] == 8 && rp6[3] == 9
                        && rp6[4] == 640 && rp6[5] == 128);
        int[] rp7 = strongholdGen.structures().testRuinedPortalAt("mountain", 23, -25);
        check("ruined portal (mountain) generates at (23,-25): rotation=CLOCKWISE_180, 9x12x9, 750 blocks, y=59",
                rp7 != null && rp7[0] == 2 && rp7[1] == 9 && rp7[2] == 12 && rp7[3] == 9
                        && rp7[4] == 750 && rp7[5] == 59);
        int[] rp8 = strongholdGen.structures().testRuinedPortalAt("ocean", -150, 98);
        check("ruined portal (ocean, on_ocean_floor) generates at (-150,98): rotation=CLOCKWISE_180, 14x9x9, 1054 blocks, y=35",
                rp8 != null && rp8[0] == 2 && rp8[1] == 14 && rp8[2] == 9 && rp8[3] == 9
                        && rp8[4] == 1054 && rp8[5] == 35);
        int[] rp9 = strongholdGen.structures().testRuinedPortalAt("swamp", -238, -395);
        check("ruined portal (swamp, on_ocean_floor) generates at (-238,-395): rotation=CLOCKWISE_180, 5x7x7, 212 blocks, y=63",
                rp9 != null && rp9[0] == 2 && rp9[1] == 5 && rp9[2] == 7 && rp9[3] == 7
                        && rp9[4] == 212 && rp9[5] == 63);

        // Ruined portal decoration: RuleProcessor (gold_block->air/lava rule/netherrack->magma),
        // BlockAgeProcessor ("mossiness" erosion), BlackstoneReplaceProcessor (nether only), and
        // the airPocket template-AIR filter, all applied per-block with a fresh position-seeded
        // RNG per processor (see VStructureManager's RPPiece javadoc). Real values at the same
        // known-good chunks/flavors as rp1-rp9 above, for seed 20260708.
        int[] rd1 = strongholdGen.structures().testRuinedPortalDecoratedAt("standard", -38, 5);
        check("ruined portal (standard, land) decorated at (-38,5): 91 solid blocks, 2 gold, 4 magma, 1 crying obsidian",
                rd1 != null && rd1[0] == 91 && rd1[1] == 2 && rd1[2] == 4 && rd1[3] == 0 && rd1[4] == 0
                        && rd1[5] == 1 && rd1[6] == 0);
        int[] rd2 = strongholdGen.structures().testRuinedPortalDecoratedAt("standard", 5, 42);
        check("ruined portal (standard, underground) decorated at (5,42): 750 solid blocks, 2 gold, 9 magma, 4 mossy, 5 cracked, 2 crying obsidian",
                rd2 != null && rd2[0] == 750 && rd2[1] == 2 && rd2[2] == 9 && rd2[3] == 4 && rd2[4] == 5
                        && rd2[5] == 2 && rd2[6] == 0);
        int[] rd3 = strongholdGen.structures().testRuinedPortalDecoratedAt("desert", -107, 171);
        check("ruined portal (desert) decorated at (-107,171): 206 solid blocks, 2 gold, 14 magma, 1 mossy, 6 cracked, 1 crying obsidian",
                rd3 != null && rd3[0] == 206 && rd3[1] == 2 && rd3[2] == 14 && rd3[3] == 1 && rd3[4] == 6
                        && rd3[5] == 1 && rd3[6] == 0);
        int[] rd4 = strongholdGen.structures().testRuinedPortalDecoratedAt("jungle", -194, 82);
        check("ruined portal (jungle) decorated at (-194,82): 640 solid blocks, 2 gold, 7 magma, 3 crying obsidian",
                rd4 != null && rd4[0] == 640 && rd4[1] == 2 && rd4[2] == 7 && rd4[3] == 0 && rd4[4] == 0
                        && rd4[5] == 3 && rd4[6] == 0);
        int[] rd5 = strongholdGen.structures().testRuinedPortalDecoratedAt("mountain", -68, -32);
        check("ruined portal (mountain, in_mountain) decorated at (-68,-32): 91 solid blocks, 2 gold, 1 magma, 1 crying obsidian",
                rd5 != null && rd5[0] == 91 && rd5[1] == 2 && rd5[2] == 1 && rd5[3] == 0 && rd5[4] == 0
                        && rd5[5] == 1 && rd5[6] == 0);
        int[] rd6 = strongholdGen.structures().testRuinedPortalDecoratedAt("ocean", -150, 98);
        check("ruined portal (ocean, on_ocean_floor) decorated at (-150,98): 218 solid blocks, 3 gold, 35 magma, 6 mossy, 3 crying obsidian",
                rd6 != null && rd6[0] == 218 && rd6[1] == 3 && rd6[2] == 35 && rd6[3] == 6 && rd6[4] == 0
                        && rd6[5] == 3 && rd6[6] == 0);
        int[] rd7 = strongholdGen.structures().testRuinedPortalDecoratedAt("swamp", -238, -395);
        check("ruined portal (swamp, on_ocean_floor) decorated at (-238,-395): 65 solid blocks, 1 gold, 1 magma, 3 crying obsidian",
                rd7 != null && rd7[0] == 65 && rd7[1] == 1 && rd7[2] == 1 && rd7[3] == 0 && rd7[4] == 0
                        && rd7[5] == 3 && rd7[6] == 0);
        check("ruined portal decoration hook returns null for an unknown flavor",
                strongholdGen.structures().testRuinedPortalDecoratedAt("nonexistent", -38, 5) == null);

        // Mineshaft (all 4 real piece kinds — Room/Corridor/Crossing/Stairs — see
        // VStructureManager's MSPiece javadoc). Real bit-exact piece-tree assembly at
        // known-good chunks for seed 20260708, decompiled from MineshaftStructure/MineshaftPieces.
        // Adding Stairs (kind=3, roll 70-79) recurses further than the corridor+crossing-only
        // tree, so piece counts/positions shifted again from that slice's expected values.
        int[] ms1 = strongholdGen.structures().testMineshaftAt("normal", -26, 19);
        check("mineshaft (normal) at (-26,19): 73 pieces, room box [-414,20,306 -> -405,26,314]",
                ms1 != null && ms1[0] == 73 && ms1[1] == -414 && ms1[2] == 20 && ms1[3] == 306
                        && ms1[4] == -405 && ms1[5] == 26 && ms1[6] == 314);
        int[] ms1p1 = strongholdGen.structures().testMineshaftPieceAt("normal", -26, 19, 1);
        check("mineshaft (normal) (-26,19) piece 1: corridor [-414,22,315 -> -412,24,334]",
                ms1p1 != null && ms1p1[0] == 1 && ms1p1[1] == -414 && ms1p1[2] == 22 && ms1p1[3] == 315
                        && ms1p1[4] == -412 && ms1p1[5] == 24 && ms1p1[6] == 334);
        int[] ms1p3 = strongholdGen.structures().testMineshaftPieceAt("normal", -26, 19, 3);
        check("mineshaft (normal) (-26,19) piece 3: crossing [-411,23,345 -> -407,25,349]",
                ms1p3 != null && ms1p3[0] == 2 && ms1p3[1] == -411 && ms1p3[2] == 23 && ms1p3[3] == 345
                        && ms1p3[4] == -407 && ms1p3[5] == 25 && ms1p3[6] == 349);
        int[] ms2 = strongholdGen.structures().testMineshaftAt("mesa", -364, 327);
        check("mineshaft (mesa) at (-364,327): 124 pieces, room box [-5822,62,5234 -> -5812,69,5241]",
                ms2 != null && ms2[0] == 124 && ms2[1] == -5822 && ms2[2] == 62 && ms2[3] == 5234
                        && ms2[4] == -5812 && ms2[5] == 69 && ms2[6] == 5241);
        check("mineshaft placement grid rejects a non-candidate chunk (0,0)",
                strongholdGen.structures().testMineshaftAt("normal", 0, 0) == null);

        // Crossing-specific: a two-floored crossing (box taller than 3, upper-floor branch
        // draws happened) and a single-floor crossing, both from MineShaftCrossing.findGenerationPoint
        // (single collision check, no retry loop, unlike corridors).
        int[] ms3p2 = strongholdGen.structures().testMineshaftPieceAt("normal", -200, -139, 2);
        check("mineshaft (normal) (-200,-139) piece 2: two-floored crossing [-3199,-36,-2247 -> -3195,-30,-2243]",
                ms3p2 != null && ms3p2[0] == 2 && ms3p2[1] == -3199 && ms3p2[2] == -36 && ms3p2[3] == -2247
                        && ms3p2[4] == -3195 && ms3p2[5] == -30 && ms3p2[6] == -2243
                        && (ms3p2[5] - ms3p2[2] + 1) > 3);
        int[] ms3p6 = strongholdGen.structures().testMineshaftPieceAt("normal", -200, -139, 6);
        check("mineshaft (normal) (-200,-139) piece 6: single-floor crossing [-3185,-35,-2287 -> -3181,-33,-2283]",
                ms3p6 != null && ms3p6[0] == 2 && ms3p6[1] == -3185 && ms3p6[2] == -35 && ms3p6[3] == -2287
                        && ms3p6[4] == -3181 && ms3p6[5] == -33 && ms3p6[6] == -2283
                        && (ms3p6[5] - ms3p6[2] + 1) <= 3);

        // Stairs-specific: no RNG draws at all — a fixed direction-dependent box, single
        // collision check, exactly one recursive child (MineShaftStairs.findStairs).
        int[] ms4p13 = strongholdGen.structures().testMineshaftPieceAt("normal", -200, -139, 13);
        check("mineshaft (normal) (-200,-139) piece 13: stairs [-3145,-41,-2286 -> -3137,-34,-2284]",
                ms4p13 != null && ms4p13[0] == 3 && ms4p13[1] == -3145 && ms4p13[2] == -41 && ms4p13[3] == -2286
                        && ms4p13[4] == -3137 && ms4p13[5] == -34 && ms4p13[6] == -2284);

        // Igloo (7th structure set wired): a single NBT-template structure with an optional
        // basement ladder chain, decompiled from IglooStructure/IglooPieces. seed 20260708.
        int[] ig1 = strongholdGen.structures().testIglooAt(13, -125);
        check("igloo (with basement) at (13,-125): 5 pieces, rot=1, top=[208,61,-2000]",
                ig1 != null && ig1[0] == 5 && ig1[1] == 1 && ig1[2] == 208 && ig1[3] == 61 && ig1[4] == -2000);
        int[] ig2 = strongholdGen.structures().testIglooAt(45, -116);
        check("igloo (no basement) at (45,-116): 1 piece, rot=1, top=[720,61,-1856]",
                ig2 != null && ig2[0] == 1 && ig2[1] == 1 && ig2[2] == 720 && ig2[3] == 61 && ig2[4] == -1856);
        int[] ig3 = strongholdGen.structures().testIglooAt(140, -52);
        check("igloo (with basement) at (140,-52): 12 pieces, rot=1, top=[2240,65,-832]",
                ig3 != null && ig3[0] == 12 && ig3[1] == 1 && ig3[2] == 2240 && ig3[3] == 65 && ig3[4] == -832);
        check("igloo placement grid rejects a non-candidate chunk (0,0)",
                strongholdGen.structures().testIglooAt(0, 0) == null);

        // Swamp hut (8th structure set wired): fully hand-coded direct block placement
        // (like mineshaft, no NBT template), decompiled from SwampHutStructure/SwampHutPiece.
        // All 4 orientations verified (dir: 0=NORTH, 1=SOUTH, 2=WEST, 3=EAST). seed 20260708.
        int[] sh1 = strongholdGen.structures().testSwampHutAt(-233, -340);
        check("swamp hut (NORTH) at (-233,-340): box=[-3728,65,-5440 -> -3722,71,-5432]",
                sh1 != null && sh1[0] == 0 && sh1[1] == -3728 && sh1[2] == 65 && sh1[3] == -5440
                        && sh1[4] == -3722 && sh1[5] == 71 && sh1[6] == -5432);
        int[] sh2 = strongholdGen.structures().testSwampHutAt(180, -62);
        check("swamp hut (SOUTH) at (180,-62): box=[2880,62,-992 -> 2886,68,-984]",
                sh2 != null && sh2[0] == 1 && sh2[1] == 2880 && sh2[2] == 62 && sh2[3] == -992
                        && sh2[4] == 2886 && sh2[5] == 68 && sh2[6] == -984);
        int[] sh3 = strongholdGen.structures().testSwampHutAt(-254, -384);
        check("swamp hut (WEST) at (-254,-384): box=[-4064,62,-6144 -> -4056,68,-6138]",
                sh3 != null && sh3[0] == 2 && sh3[1] == -4064 && sh3[2] == 62 && sh3[3] == -6144
                        && sh3[4] == -4056 && sh3[5] == 68 && sh3[6] == -6138);
        int[] sh4 = strongholdGen.structures().testSwampHutAt(-246, 174);
        check("swamp hut (EAST) at (-246,174): box=[-3936,62,2784 -> -3928,68,2790]",
                sh4 != null && sh4[0] == 3 && sh4[1] == -3936 && sh4[2] == 62 && sh4[3] == 2784
                        && sh4[4] == -3928 && sh4[5] == 68 && sh4[6] == 2790);
        check("swamp hut placement grid rejects a non-candidate chunk (0,0)",
                strongholdGen.structures().testSwampHutAt(0, 0) == null);

        // Jungle temple (9th structure set wired): fully hand-coded direct block placement
        // (like swamp hut, no NBT template), decompiled from JungleTempleStructure/
        // JungleTemplePiece/SinglePieceStructure. All 4 orientations verified. seed 20260708.
        int[] jt1 = strongholdGen.structures().testJungleTempleAt(-537, 45);
        check("jungle temple (NORTH) at (-537,45): box=[-8592,73,720 -> -8581,82,734], 2302 blocks",
                jt1 != null && jt1[0] == 0 && jt1[1] == -8592 && jt1[2] == 73 && jt1[3] == 720
                        && jt1[4] == -8581 && jt1[5] == 82 && jt1[6] == 734 && jt1[7] == 2302);
        int[] jt2 = strongholdGen.structures().testJungleTempleAt(-571, 133);
        check("jungle temple (SOUTH) at (-571,133): box=[-9136,72,2128 -> -9125,81,2142], 2302 blocks",
                jt2 != null && jt2[0] == 1 && jt2[1] == -9136 && jt2[2] == 72 && jt2[3] == 2128
                        && jt2[4] == -9125 && jt2[5] == 81 && jt2[6] == 2142 && jt2[7] == 2302);
        int[] jt3 = strongholdGen.structures().testJungleTempleAt(-596, -541);
        check("jungle temple (WEST) at (-596,-541): box=[-9536,66,-8656 -> -9522,75,-8645], 2302 blocks",
                jt3 != null && jt3[0] == 2 && jt3[1] == -9536 && jt3[2] == 66 && jt3[3] == -8656
                        && jt3[4] == -9522 && jt3[5] == 75 && jt3[6] == -8645 && jt3[7] == 2302);
        int[] jt4 = strongholdGen.structures().testJungleTempleAt(-562, 116);
        check("jungle temple (EAST) at (-562,116): box=[-8992,132,1856 -> -8978,141,1867], 2302 blocks",
                jt4 != null && jt4[0] == 3 && jt4[1] == -8992 && jt4[2] == 132 && jt4[3] == 1856
                        && jt4[4] == -8978 && jt4[5] == 141 && jt4[6] == 1867 && jt4[7] == 2302);
        check("jungle temple placement grid rejects a non-candidate chunk (0,0)",
                strongholdGen.structures().testJungleTempleAt(0, 0) == null);

        // Desert pyramid (10th structure set wired): the largest hand-coded structure this
        // project generates (21x15x21), decompiled from DesertPyramidStructure/
        // DesertPyramidPiece/SinglePieceStructure. 3 of 4 orientations verified (NORTH is
        // genuinely rare for this seed — not found in a +-500 chunk scan). seed 20260708.
        int[] dp1 = strongholdGen.structures().testDesertPyramidAt(-148, 151);
        check("desert pyramid (SOUTH) at (-148,151): box=[-2368,60,2416 -> -2348,74,2436], 6722 blocks",
                dp1 != null && dp1[0] == 1 && dp1[1] == -2368 && dp1[2] == 60 && dp1[3] == 2416
                        && dp1[4] == -2348 && dp1[5] == 74 && dp1[6] == 2436 && dp1[7] == 6722);
        int[] dp2 = strongholdGen.structures().testDesertPyramidAt(246, 294);
        check("desert pyramid (WEST) at (246,294): box=[3936,60,4704 -> 3956,74,4724], 6722 blocks",
                dp2 != null && dp2[0] == 2 && dp2[1] == 3936 && dp2[2] == 60 && dp2[3] == 4704
                        && dp2[4] == 3956 && dp2[5] == 74 && dp2[6] == 4724 && dp2[7] == 6722);
        int[] dp3 = strongholdGen.structures().testDesertPyramidAt(239, 387);
        check("desert pyramid (EAST) at (239,387): box=[3824,62,6192 -> 3844,76,6212], 6722 blocks",
                dp3 != null && dp3[0] == 3 && dp3[1] == 3824 && dp3[2] == 62 && dp3[3] == 6192
                        && dp3[4] == 3844 && dp3[5] == 76 && dp3[6] == 6212 && dp3[7] == 6722);
        check("desert pyramid placement grid rejects a non-candidate chunk (0,0)",
                strongholdGen.structures().testDesertPyramidAt(0, 0) == null);

        // Shipwreck, beached flavor (11th structure set wired): single NBT-template
        // structure, decompiled from ShipwreckStructure/ShipwreckPieces. All 4 rotations
        // verified. Regular (open-ocean) shipwrecks remain unimplemented (need OCEAN_FLOOR_WG,
        // same known gap blocking ruined_portal_ocean/swamp). seed 20260708.
        int[] sw1 = strongholdGen.structures().testShipwreckAt(-261, 169);
        check("shipwreck (rot=NONE) at (-261,169): 9x9x16, 414 blocks, originY=56",
                sw1 != null && sw1[0] == 0 && sw1[1] == 9 && sw1[2] == 9 && sw1[3] == 16 && sw1[4] == 414 && sw1[5] == 56);
        int[] sw2 = strongholdGen.structures().testShipwreckAt(64, 176);
        check("shipwreck (rot=CLOCKWISE_90) at (64,176): 9x9x16, 385 blocks, originY=57",
                sw2 != null && sw2[0] == 1 && sw2[1] == 9 && sw2[2] == 9 && sw2[3] == 16 && sw2[4] == 385 && sw2[5] == 57);
        int[] sw3 = strongholdGen.structures().testShipwreckAt(-295, 186);
        check("shipwreck (rot=CLOCKWISE_180) at (-295,186): 9x9x16, 385 blocks, originY=58",
                sw3 != null && sw3[0] == 2 && sw3[1] == 9 && sw3[2] == 9 && sw3[3] == 16 && sw3[4] == 385 && sw3[5] == 58);
        int[] sw4 = strongholdGen.structures().testShipwreckAt(51, 9);
        check("shipwreck (rot=COUNTERCLOCKWISE_90) at (51,9): 9x9x24, 321 blocks, originY=57",
                sw4 != null && sw4[0] == 3 && sw4[1] == 9 && sw4[2] == 9 && sw4[3] == 24 && sw4[4] == 321 && sw4[5] == 57);
        check("shipwreck placement grid rejects a non-candidate chunk (0,0)",
                strongholdGen.structures().testShipwreckAt(0, 0) == null);

        // Shipwreck, ocean flavor: uses the new OCEAN_FLOOR_WG accessor + mean-height (not
        // min+random offset like beached) Y placement. All 4 rotations verified.
        int[] swo1 = strongholdGen.structures().testShipwreckAt(false, -150, -127);
        check("ocean shipwreck (rot=NONE) at (-150,-127): 9x9x16, 385 blocks, originY=37",
                swo1 != null && swo1[0] == 0 && swo1[1] == 9 && swo1[2] == 9 && swo1[3] == 16 && swo1[4] == 385 && swo1[5] == 37);
        int[] swo2 = strongholdGen.structures().testShipwreckAt(false, -141, 53);
        check("ocean shipwreck (rot=CLOCKWISE_90) at (-141,53): 9x21x28, 729 blocks, originY=39",
                swo2 != null && swo2[0] == 1 && swo2[1] == 9 && swo2[2] == 21 && swo2[3] == 28 && swo2[4] == 729 && swo2[5] == 39);
        int[] swo3 = strongholdGen.structures().testShipwreckAt(false, -144, -64);
        check("ocean shipwreck (rot=CLOCKWISE_180) at (-144,-64): 9x9x16, 387 blocks, originY=45",
                swo3 != null && swo3[0] == 2 && swo3[1] == 9 && swo3[2] == 9 && swo3[3] == 16 && swo3[4] == 387 && swo3[5] == 45);
        int[] swo4 = strongholdGen.structures().testShipwreckAt(false, -136, -110);
        check("ocean shipwreck (rot=COUNTERCLOCKWISE_90) at (-136,-110): 9x9x16, 414 blocks, originY=39",
                swo4 != null && swo4[0] == 3 && swo4[1] == 9 && swo4[2] == 9 && swo4[3] == 16 && swo4[4] == 414 && swo4[5] == 39);

        // Buried treasure (13th structure set wired): a single-point structure using the
        // newly-added OCEAN_FLOOR_WG accessor (VanillaGen.oceanFloorBlock), decompiled from
        // BuriedTreasureStructure/BuriedTreasurePieces. seed 20260708.
        int[] bt1 = strongholdGen.structures().testBuriedTreasureAt(48, -13);
        check("buried treasure at (48,-13): chest=[777,49,-199]",
                bt1 != null && bt1[0] == 777 && bt1[1] == 49 && bt1[2] == -199);
        check("buried treasure placement grid rejects a non-candidate chunk (0,0)",
                strongholdGen.structures().testBuriedTreasureAt(0, 0) == null);

        // Nether fossil (14th structure set wired): the first Nether structure this project
        // generates, decompiled from NetherFossilStructure/NetherFossilPieces. Placed onto
        // this project's approximate (non-bit-exact, non-seed-tied) Nether terrain — see
        // NetherGen's netherFossils javadoc for why that's an accepted, pre-existing
        // simplification distinct from every overworld structure's bit-exact terrain base.
        int[] nf1 = dev.pointofpressure.minecom.worldgen.NetherGen.testNetherFossilAt(-60, 2);
        check("nether fossil (rot=NONE) at (-60,2): 5x5x7, 175 blocks, pos=[-947,58,40]",
                nf1 != null && nf1[0] == 0 && nf1[1] == 5 && nf1[2] == 5 && nf1[3] == 7 && nf1[4] == 175
                        && nf1[5] == -947 && nf1[6] == 58 && nf1[7] == 40);
        int[] nf2 = dev.pointofpressure.minecom.worldgen.NetherGen.testNetherFossilAt(-58, 0);
        check("nether fossil (rot=CLOCKWISE_90) at (-58,0): 7x5x5, 175 blocks, pos=[-913,115,11]",
                nf2 != null && nf2[0] == 1 && nf2[1] == 7 && nf2[2] == 5 && nf2[3] == 5 && nf2[4] == 175
                        && nf2[5] == -913 && nf2[6] == 115 && nf2[7] == 11);
        int[] nf3 = dev.pointofpressure.minecom.worldgen.NetherGen.testNetherFossilAt(-54, 20);
        check("nether fossil (rot=COUNTERCLOCKWISE_90) at (-54,20): 7x5x5, 175 blocks, pos=[-855,53,321]",
                nf3 != null && nf3[0] == 3 && nf3[1] == 7 && nf3[2] == 5 && nf3[3] == 5 && nf3[4] == 175
                        && nf3[5] == -855 && nf3[6] == 53 && nf3[7] == 321);
        check("nether fossil placement grid rejects a non-candidate chunk (0,0)",
                dev.pointofpressure.minecom.worldgen.NetherGen.testNetherFossilAt(0, 0) == null);

        // Ocean ruin, both flavors, real BlockRotProcessor integrity now applied (warm was
        // originally "full integrity", cold newly added as a genuine 3-stacked-template
        // shape): decompiled from OceanRuinStructure/OceanRuinPieces. Uses OCEAN_FLOOR_WG.
        // Cluster generation still NOT implemented — see VStructureManager's OceanRuinPiece
        // javadoc. Test hook shape changed to {pieceCount, rot, sizeX, sizeY, sizeZ, blocks,
        // baseX, baseY, baseZ} to expose cold's 3-piece stack. seed 20260708.
        int[] or1 = strongholdGen.structures().testOceanRuinAt(-139, 49);
        check("ocean ruin (warm, small) at (-139,49): 1 piece, rot=COUNTERCLOCKWISE_90, 6x7x7, 294 blocks, pos=[-2224,36,784]",
                or1 != null && or1[0] == 1 && or1[1] == 3 && or1[2] == 6 && or1[3] == 7 && or1[4] == 7 && or1[5] == 294
                        && or1[6] == -2224 && or1[7] == 36 && or1[8] == 784);
        int[] or2 = strongholdGen.structures().testOceanRuinAt(-140, 82);
        check("ocean ruin (warm, LARGE) at (-140,82): 1 piece, rot=CLOCKWISE_90, 16x16x16, 4096 blocks, pos=[-2240,34,1312]",
                or2 != null && or2[0] == 1 && or2[1] == 1 && or2[2] == 16 && or2[3] == 16 && or2[4] == 16 && or2[5] == 4096
                        && or2[6] == -2240 && or2[7] == 34 && or2[8] == 1312);
        check("ocean ruin placement grid rejects a non-candidate chunk (0,0)",
                strongholdGen.structures().testOceanRuinAt(0, 0) == null);
        int[] or3 = strongholdGen.structures().testOceanRuinAt(false, -138, -119);
        check("ocean ruin (COLD, 3-stacked brick/cracked/mossy) at (-138,-119): 3 pieces, rot=COUNTERCLOCKWISE_90, 6x7x7, 294 blocks each, pos=[-2208,41,-1904]",
                or3 != null && or3[0] == 3 && or3[1] == 3 && or3[2] == 6 && or3[3] == 7 && or3[4] == 7 && or3[5] == 294
                        && or3[6] == -2208 && or3[7] == 41 && or3[8] == -1904);

        // Ocean ruin cluster generation (addClusterRuins): when isLarge and a clusterProbability
        // (0.9) roll passes, 4-8 additional small same-flavor ruins scatter around the parent's
        // footprint. Implementing this surfaced and fixed a genuine RNG-order bug in the
        // already-shipped code: clusterProbability was being drawn BEFORE the parent's own
        // template-pool pick, but real addPieces draws it AFTER — only isLarge=true outcomes
        // were affected (or1/or3 above are non-large and unaffected; or2 above happens to be
        // large but byte-identical post-fix since all BIG_WARM_RUINS templates share the same
        // 16x16x16/4096-block footprint regardless of which specific one is picked).
        int[] or4 = strongholdGen.structures().testOceanRuinAt(true, -159, 87);
        check("ocean ruin (warm, LARGE+CLUSTER) at (-159,87): 8 pieces total (1 parent + 7 cluster), rot=CLOCKWISE_90, pos=[-2544,43,1392]",
                or4 != null && or4[0] == 8 && or4[1] == 1 && or4[2] == 16 && or4[3] == 16 && or4[4] == 16 && or4[5] == 4096
                        && or4[6] == -2544 && or4[7] == 43 && or4[8] == 1392);
        int[] or5 = strongholdGen.structures().testOceanRuinAt(false, -193, -179);
        check("ocean ruin (COLD, LARGE+CLUSTER) at (-193,-179): 27 pieces total (3 parent + 8 cluster x3-stack), rot=CLOCKWISE_180, pos=[-3088,49,-2864]",
                or5 != null && or5[0] == 27 && or5[1] == 2 && or5[2] == 16 && or5[3] == 16 && or5[4] == 16 && or5[5] == 4096
                        && or5[6] == -3088 && or5[7] == 49 && or5[8] == -2864);

        // Bastion remnant (16th structure set wired): reuses VJigsaw directly in
        // NetherGen.java (like nether_fossil), NOT through VStructureManager (nether-only).
        // Real placement-grid + biome + a defensive fortress-footprint exclusion (see
        // NetherGen's bastionRemnant javadoc for the real, probe-confirmed fortress/bastion
        // placement-grid conflict this works around). 167 real NBT templates + 5 named
        // processor lists (all verified always_true location predicates, safe with a
        // constant-AIR terrain-read stand-in). seed 20260708.
        int[] ba1 = dev.pointofpressure.minecom.worldgen.NetherGen.testBastionAt(-79, 27);
        check("bastion remnant at (-79,27): 100 pieces, center=[-1278,33,408]",
                ba1 != null && ba1[0] == 100 && ba1[1] == -1278 && ba1[2] == 33 && ba1[3] == 408);
        int[] ba2 = dev.pointofpressure.minecom.worldgen.NetherGen.testBastionAt(-77, -64);
        check("bastion remnant at (-77,-64): 177 pieces, center=[-1250,33,-1005]",
                ba2 != null && ba2[0] == 177 && ba2[1] == -1250 && ba2[2] == 33 && ba2[3] == -1005);
        check("bastion remnant placement grid rejects a non-candidate chunk (0,0)",
                dev.pointofpressure.minecom.worldgen.NetherGen.testBastionAt(0, 0) == null);
        check("bastion remnant correctly excluded at a known fortress-footprint conflict chunk (-231,40)",
                dev.pointofpressure.minecom.worldgen.NetherGen.testBastionAt(-231, 40) == null);

        // Ruined portal, nether flavor (7th and final real flavor — ruined_portals structure
        // set is now fully complete). Implemented in NetherGen.java (nether-dimension
        // structure, like nether fossil/bastion), reusing the overworld's PORTAL_TEMPLATES
        // pool. Both normal and GIANT templates verified. seed 20260708.
        int[] rpn1 = dev.pointofpressure.minecom.worldgen.NetherGen.testRuinedPortalNetherAt(-75, -25);
        check("ruined portal (nether) at (-75,-25): rot=COUNTERCLOCKWISE_90, 9x7x9, 510 blocks, y=54",
                rpn1 != null && rpn1[0] == 3 && rpn1[1] == 9 && rpn1[2] == 7 && rpn1[3] == 9 && rpn1[4] == 510 && rpn1[5] == 54);
        int[] rpn2 = dev.pointofpressure.minecom.worldgen.NetherGen.testRuinedPortalNetherAt(-38, -79);
        check("ruined portal (nether, GIANT) at (-38,-79): rot=COUNTERCLOCKWISE_90, 11x17x16, 2400 blocks, y=97",
                rpn2 != null && rpn2[0] == 3 && rpn2[1] == 11 && rpn2[2] == 17 && rpn2[3] == 16 && rpn2[4] == 2400 && rpn2[5] == 97);
        check("ruined portal (nether) placement grid rejects a non-candidate chunk (0,0)",
                dev.pointofpressure.minecom.worldgen.NetherGen.testRuinedPortalNetherAt(0, 0) == null);

        // Ruined portal (nether) decoration: same RuleProcessor/BlockAgeProcessor pipeline as the
        // overworld flavors, plus BlackstoneReplaceProcessor (real config: mossiness=0.0,
        // can_be_cold=false, replace_with_blackstone=true) — the material swap that makes nether
        // ruined portals look like blackstone rather than plain stone. seed-independent (SEED constant).
        int[] rdn1 = dev.pointofpressure.minecom.worldgen.NetherGen.testRuinedPortalNetherDecoratedAt(-75, -25);
        check("ruined portal (nether) decorated at (-75,-25): 510 solid blocks, 2 gold, 15 magma, 1 crying obsidian, 5 blackstone-family",
                rdn1 != null && rdn1[0] == 510 && rdn1[1] == 2 && rdn1[2] == 15 && rdn1[3] == 0 && rdn1[4] == 0
                        && rdn1[5] == 1 && rdn1[6] == 5);
        int[] rdn2 = dev.pointofpressure.minecom.worldgen.NetherGen.testRuinedPortalNetherDecoratedAt(-38, -79);
        check("ruined portal (nether, GIANT) decorated at (-38,-79): 2400 solid blocks, 2 gold, 15 magma, 9 crying obsidian, 143 blackstone-family",
                rdn2 != null && rdn2[0] == 2400 && rdn2[1] == 2 && rdn2[2] == 15 && rdn2[3] == 0 && rdn2[4] == 0
                        && rdn2[5] == 9 && rdn2[6] == 143);

        // Zombie weapon roll (Zombie.populateDefaultEquipmentSlots): ~1% trigger, then 1/6 sword, 1/6 spear, 4/6 shovel
        double weaponTrigger = dev.pointofpressure.minecom.mobs.ai.VanillaMobs.testZombieWeaponTriggerRate(300000);
        check("zombie weapon roll: trigger rate ~1% (got " + String.format("%.4f", weaponTrigger) + ")",
                weaponTrigger > 0.005 && weaponTrigger < 0.02);
        double shovelShare = dev.pointofpressure.minecom.mobs.ai.VanillaMobs.testZombieWeaponShovelShare(60000);
        check("zombie weapon roll: shovel share ~4/6=0.667 (got " + String.format("%.3f", shovelShare) + ")",
                shovelShare > 0.55 && shovelShare < 0.78);
        double babyRate = dev.pointofpressure.minecom.mobs.ai.VanillaMobs.testBabyZombieTriggerRate(200000);
        check("baby zombie roll: trigger rate ~5% (got " + String.format("%.4f", babyRate) + ")",
                babyRate > 0.03 && babyRate < 0.07);

        // ---- natural spawner (vanilla NaturalSpawner algorithm fidelity) ----
        var sp = dev.pointofpressure.minecom.mobs.VNaturalSpawner.class; // trigger static load
        check("spawn data: all overworld biomes loaded (>=50)",
                dev.pointofpressure.minecom.mobs.VNaturalSpawner.testBiomeCount() >= 50);
        check("mob cap: MONSTER over 289 spawnable chunks = 70",
                dev.pointofpressure.minecom.mobs.VNaturalSpawner.testCap("monster", 289) == 70);
        check("mob cap: CREATURE over 578 chunks = 20",
                dev.pointofpressure.minecom.mobs.VNaturalSpawner.testCap("creature", 578) == 20);
        check("mob cap: MONSTER over 100 chunks = 24 (70*100/289)",
                dev.pointofpressure.minecom.mobs.VNaturalSpawner.testCap("monster", 100) == 24);
        int[] cov = dev.pointofpressure.minecom.mobs.VNaturalSpawner.testRosterCoverage();
        check("roster covers ALL natural-spawn types (" + cov[0] + "/" + cov[1] + ")",
                cov[0] == cov[1]);
        check("category: zombie=MONSTER, cow=CREATURE",
                "MONSTER".equals(dev.pointofpressure.minecom.mobs.VNaturalSpawner.testCategoryOf("minecraft:zombie"))
                        && "CREATURE".equals(dev.pointofpressure.minecom.mobs.VNaturalSpawner.testCategoryOf("minecraft:cow")));
        // plains monster list: spider w=100, zombie w=90 -> spider picked more often than zombie
        double spider = dev.pointofpressure.minecom.mobs.VNaturalSpawner.testPickFrequency(
                "minecraft:plains", "monster", "minecraft:spider", 20000);
        double zombie = dev.pointofpressure.minecom.mobs.VNaturalSpawner.testPickFrequency(
                "minecraft:plains", "monster", "minecraft:zombie", 20000);
        check("weighted pick: plains spider(w100) > zombie(w90), both ~15-20% (spider=" +
                        String.format("%.3f", spider) + ", zombie=" + String.format("%.3f", zombie) + ")",
                spider > zombie && spider > 0.10 && spider < 0.25);

        // ---- End island terrain density (bit-exact vs vanilla EndIslandDensityFunction) ----
        double endCenter = dev.pointofpressure.minecom.worldgen.vanilla.VDensity.testEndDensity(20260708L, 0, 0);
        check("end island density: central island peak = 0.5625 (got " + endCenter + ")",
                Math.abs(endCenter - 0.5625) < 1e-9);
        double endFar = dev.pointofpressure.minecom.worldgen.vanilla.VDensity.testEndDensity(20260708L, 0, 1000);
        check("end island density: void gap between islands is negative (got " + String.format("%.4f", endFar) + ")",
                endFar < 0);
        // End terrain: solid central island, near-void far out
        var endGen = new dev.pointofpressure.minecom.worldgen.vanilla.VEndGen(20260708L);
        int centralSolid = 0;
        for (int y = 0; y < 128; y++) if (endGen.testSolid(0, y, 0)) centralSolid++;
        check("End terrain: central island is a solid mass (got " + centralSolid + " blocks tall)",
                centralSolid > 20);
        int farSolid = 0;
        for (int y = 0; y < 128; y++) if (endGen.testSolid(5000, y, 0)) farSolid++;
        check("End terrain: deep void far from center (got " + farSolid + " solid blocks)",
                farSolid < 60);
        // End biome source (TheEndBiomeSource): central island is the_end; outer is one of the 4 outer biomes
        check("End biome: centre is the_end", endGen.biomeAt(0, 0).equals("minecraft:the_end"));
        String farBiome = endGen.biomeAt(5000, 5000);
        check("End biome: far region is an outer End biome (got " + farBiome + ")",
                farBiome.equals("minecraft:end_highlands") || farBiome.equals("minecraft:end_midlands")
                        || farBiome.equals("minecraft:end_barrens") || farBiome.equals("minecraft:small_end_islands"));

        // End gateway ring position (EnderDragonFight.spawnNewGateway shape): deterministic, on the r=96 ring
        int[] gateRing = dev.pointofpressure.minecom.worldgen.vanilla.EndGateways.ringPosition(20260708L);
        double gateDist = Math.sqrt((double) gateRing[0] * gateRing[0] + (double) gateRing[2] * gateRing[2]);
        check("end gateway: ring position is deterministic and on the r=96 ring (got dist=" +
                        String.format("%.1f", gateDist) + ")",
                Math.abs(gateDist - 96.0) < 1.5);
        // End obsidian spikes: 10 spikes on the radius-42 ring, 2 iron-caged
        var spikes = dev.pointofpressure.minecom.worldgen.vanilla.VEndSpikes.spikes(20260708L);
        long caged = spikes.stream().filter(dev.pointofpressure.minecom.worldgen.vanilla.VEndSpikes.Spike::guarded).count();
        boolean onRing = spikes.stream().allMatch(s -> {
            int d = (int) Math.round(Math.sqrt((double) s.x() * s.x() + (double) s.z() * s.z()));
            return d >= 41 && d <= 43; // radius 42 (floor rounding)
        });
        check("End spikes: 10 spikes, 2 iron-caged, all on the r=42 ring (caged=" + caged + ")",
                spikes.size() == 10 && caged == 2 && onRing);

        REPORT.append(passed).append(" passed, ").append(failed).append(" failed\n");
        return REPORT.toString();
    }

    private static List<String> drops(Block block, ItemStack tool) {
        return LootTables.blockDrops(block, tool).stream()
                .map(s -> s.material().key().asString() + " x" + s.amount()).toList();
    }

    private static String craft2(Material a, Material b, Material c, Material d) {
        ItemStack[] grid = new ItemStack[]{stack(a), stack(b), stack(c), stack(d)};
        ItemStack result = Recipes.matchCrafting(grid, 2);
        return result.isAir() ? "air" : result.material().key().asString() + " x" + result.amount();
    }

    private static String craft3(Material[] mats) {
        ItemStack[] grid = new ItemStack[9];
        for (int i = 0; i < 9; i++) grid[i] = stack(mats[i]);
        ItemStack result = Recipes.matchCrafting(grid, 3);
        return result.isAir() ? "air" : result.material().key().asString() + " x" + result.amount();
    }

    private static ItemStack stack(Material m) {
        return m == null ? ItemStack.AIR : ItemStack.of(m);
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++; else failed++;
        REPORT.append(ok ? "PASS " : "FAIL ").append(name).append('\n');
    }

    public static void main(String[] args) {
        System.out.println(run());
    }
}
