package dev.pointofpressure.minecom.blocks;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.TaskSchedule;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The vanilla random-tick engine, ported from ServerLevel.tickChunk (26.1.2):
 * every game tick, each 16-block section of every chunk within 8 chunks of a
 * player rolls randomTickSpeed (3) random positions; a block found there runs
 * its registered handler. Empty sections skip via the palette count, matching
 * vanilla's isRandomlyTicking section gate in spirit (vanilla tracks ticking
 * blocks per section; we check for any blocks at all — AUDIT.md).
 * Handlers ported here: grass/mycelium spread + death (SpreadingSnowyBlock),
 * ice + snow-layer melt at block light &gt; 11 (IceBlock/SnowLayerBlock),
 * sugar cane + cactus growth (age 15 column growth, cactus flowers), farmland
 * moisture (FarmlandBlock), copper oxidation (ChangeOverTimeBlock /
 * WeatheringCopper: 0.05688889 roll, Manhattan-4 neighbor scan, squared
 * age-ratio chance, 0.75 modifier while unaffected), budding amethyst
 * (1/5 bud growth per face), bamboo column growth (1/3 roll, 16-block
 * cap, leaf-crown cascade, stage-based growth stop), and vine spread
 * (1/4 roll, corner-wrapping horizontal extension, upward/downward face
 * copying, 9x3x9 density cap — growth only, no neighbor-update-driven
 * detach), crop growth (CropBlock: light gate, 3x3 farmland-moisture
 * growth-speed scan, same-type-neighbor halving), and sapling growth
 * (SaplingBlock: light gate, 1/7 roll, a two-stage climb shared with bone
 * meal — stage 0 just advances to stage 1, only a stage-1 sapling actually
 * grows a tree). Light is the project's
 * behavioural model (VNaturalSpawner precedent): sky-exposed = at/above
 * surface, 15 by day / 4 at night, plus real Minestom block light. Snow
 * accumulation stays in survival/Snow.java. Precipitation ice freeze is a
 * follow-up (AUDIT.md).
 */
public final class RandomTicks {
    private RandomTicks() {}

    /** One block's random tick. */
    public interface Handler {
        void tick(Instance instance, Point pos, Block block);
    }

    private static final Map<String, Handler> HANDLERS = new ConcurrentHashMap<>();
    private static final int CHUNK_RADIUS = 8; // same player ring the spawner uses
    // thread: written by the playtest hook before the engine observes it
    private static volatile int tickSpeed = 3; // randomTickSpeed gamerule default
    private static Instance instance;

    public static void register(Instance overworld) {
        instance = overworld;
        registerHandlers();
        MinecraftServer.getSchedulerManager().buildTask(RandomTicks::tick)
                .repeat(TaskSchedule.tick(1)).schedule();
    }

    /** Playtest hook: crank the roll rate so growth becomes observable in seconds. */
    public static void setSpeedForTest(int speed) {
        tickSpeed = speed;
    }

    /** Playtest hook: fire the handler for whatever sits at pos, deterministically. */
    public static void forceTick(Instance in, Point pos) {
        Block block = in.getBlock(pos);
        Handler handler = handlerFor(block.key().value());
        if (handler != null) handler.tick(in, pos, block);
    }

    // ------------------------------------------------------------------ dispatch

    private static void tick() {
        if (instance == null || instance.getPlayers().isEmpty()) return;
        var rng = ThreadLocalRandom.current();
        Set<Long> visited = new HashSet<>();
        for (Player player : instance.getPlayers()) {
            int pcx = player.getPosition().chunkX(), pcz = player.getPosition().chunkZ();
            for (int cx = pcx - CHUNK_RADIUS; cx <= pcx + CHUNK_RADIUS; cx++) {
                for (int cz = pcz - CHUNK_RADIUS; cz <= pcz + CHUNK_RADIUS; cz++) {
                    if (!visited.add(((long) cx << 32) | (cz & 0xFFFFFFFFL))) continue;
                    Chunk chunk = instance.getChunk(cx, cz);
                    if (chunk == null) continue;
                    tickChunk(chunk, rng);
                }
            }
        }
    }

    /** ServerLevel.tickChunk's tickBlocks half: tickSpeed rolls per non-empty section. */
    private static void tickChunk(Chunk chunk, ThreadLocalRandom rng) {
        int minX = chunk.getChunkX() << 4, minZ = chunk.getChunkZ() << 4;
        for (int sectionY = chunk.getMinSection(); sectionY < chunk.getMaxSection(); sectionY++) {
            var section = chunk.getSection(sectionY);
            if (section.blockPalette().count() == 0) continue;
            int minY = sectionY << 4;
            for (int i = 0; i < tickSpeed; i++) {
                int x = minX + rng.nextInt(16);
                int y = minY + rng.nextInt(16);
                int z = minZ + rng.nextInt(16);
                Block block = chunk.getBlock(x, y, z);
                Handler handler = handlerFor(block.key().value());
                if (handler != null) handler.tick(chunk.getInstance(), new Vec(x, y, z), block);
            }
        }
    }

    /** Exact key hit, else the derived copper-oxidation family. */
    private static Handler handlerFor(String key) {
        Handler handler = HANDLERS.get(key);
        if (handler != null) return handler;
        return nextOxidation(key) != null ? OXIDATION : null;
    }

    // ------------------------------------------------------------------ handlers

    private static void registerHandlers() {
        HANDLERS.put("grass_block", spreading("grass_block"));
        HANDLERS.put("mycelium", spreading("mycelium"));
        HANDLERS.put("ice", RandomTicks::meltIce);
        HANDLERS.put("snow", RandomTicks::meltSnowLayer);
        HANDLERS.put("sugar_cane", RandomTicks::growSugarCane);
        HANDLERS.put("cactus", RandomTicks::growCactus);
        HANDLERS.put("farmland", RandomTicks::tickFarmland);
        HANDLERS.put("budding_amethyst", RandomTicks::growAmethyst);
        HANDLERS.put("bamboo", RandomTicks::growBamboo);
        HANDLERS.put("vine", RandomTicks::spreadVine);
        HANDLERS.put("wheat", RandomTicks::growCrop);
        HANDLERS.put("carrots", RandomTicks::growCrop);
        HANDLERS.put("potatoes", RandomTicks::growCrop);
        HANDLERS.put("beetroots", RandomTicks::growCrop);
        for (String kind : new String[]{"oak", "spruce", "birch", "jungle", "acacia",
                "dark_oak", "cherry", "pale_oak"}) {
            HANDLERS.put(kind + "_sapling", RandomTicks::growSapling);
        }
    }

    /**
     * BambooStalkBlock.randomTick (decompile-verified): only while STAGE 0 ("still growing").
     * 1/3 roll per random tick, needs the block directly above to be air lit &gt;= 9; grows one
     * segment if the existing column (counting downward from this block) is under the 16 cap.
     * Covers an already-generated/grown bamboo column continuing to grow taller — the separate
     * bamboo_sapling -&gt; bamboo maturation isn't modelled here (a different block/mechanic,
     * not yet decompiled — AUDIT.md).
     */
    private static void growBamboo(Instance in, Point pos, Block block) {
        if (!"0".equals(block.getProperty("stage"))) return;
        var rng = ThreadLocalRandom.current();
        if (rng.nextInt(3) != 0) return;
        if (!in.getBlock(pos.add(0, 1, 0)).isAir()) return;
        if (brightness(in, pos.add(0, 1, 0)) < 9) return;
        int heightBelow = 0;
        while (in.getBlock(pos.add(0, -heightBelow - 1, 0)).key().value().equals("bamboo")) heightBelow++;
        int height = heightBelow + 1;
        if (height >= 16) return;
        growBambooSegment(in, pos, block, rng, height);
    }

    /**
     * BambooStalkBlock.growBamboo: LEAVES cascades down one crown when a third segment stacks
     * (the block below keeps LARGE only as long as nothing sits below IT too), AGE carries the
     * "thick stalk" flag once either this segment is thick or the block two below is bamboo,
     * and STAGE flips to "done growing" past height 11 on a 25% roll, or always at height 15.
     */
    private static void growBambooSegment(Instance in, Point pos, Block state, ThreadLocalRandom rng, int height) {
        Block below = in.getBlock(pos.add(0, -1, 0));
        Block twoBelow = in.getBlock(pos.add(0, -2, 0));
        boolean belowIsBamboo = below.key().value().equals("bamboo");
        boolean twoBelowIsBamboo = twoBelow.key().value().equals("bamboo");
        String leaves = "none";
        if (height >= 1) {
            if (!belowIsBamboo || "none".equals(below.getProperty("leaves"))) {
                leaves = "small";
            } else {
                leaves = "large";
                if (twoBelowIsBamboo) {
                    in.setBlock(pos.add(0, -1, 0), below.withProperty("leaves", "small"));
                    in.setBlock(pos.add(0, -2, 0), twoBelow.withProperty("leaves", "none"));
                }
            }
        }
        boolean thick = "1".equals(state.getProperty("age")) || twoBelowIsBamboo;
        boolean doneGrowing = (height >= 11 && rng.nextFloat() < 0.25f) || height == 15;
        in.setBlock(pos.add(0, 1, 0), Block.BAMBOO
                .withProperty("age", thick ? "1" : "0")
                .withProperty("leaves", leaves)
                .withProperty("stage", doneGrowing ? "1" : "0"));
    }

    // ------------------------------------------------------------------ vine spread

    private static final String[] VINE_HORIZONTAL = {"north", "south", "east", "west"};

    /**
     * VineBlock.randomTick (decompile-verified), the growth half only — the neighbor-update-
     * driven detach/survival check (canSurvive/updateShape) isn't ported, matching this
     * project's "grow via random tick, no generic support-removal system" scope (AUDIT.md).
     * SPREAD_VINES gamerule assumed true (no gamerule store in this project — behavioural
     * default, matches vanilla's own default). 1/4 roll, then a random one of 6 directions:
     * horizontal + not-yet-connected -> try to extend outward one block (preferring to wrap
     * around a corner via whichever of the CW/CCW neighbor faces this vine already has, else
     * hang a fresh face off the CW/CCW neighbor block itself, else a rare 5% upward-face poke);
     * otherwise (UP roll, or DOWN, or an already-connected horizontal face) try to grow upward
     * by copying a random subset of this block's own faces onto the block above, or fall
     * through to growing downward the same way. Density-capped at 5 vines in a 9x3x9 box
     * (canSpread) before any spread attempt. World min/max Y bounds aren't checked (no
     * established height-bounds accessor elsewhere in this codebase — AUDIT.md).
     */
    private static void spreadVine(Instance in, Point pos, Block block) {
        var rng = ThreadLocalRandom.current();
        if (rng.nextInt(4) != 0) return;
        String[] all = {"north", "south", "east", "west", "up", "down"};
        String testDirection = all[rng.nextInt(6)];

        if (isHorizontal(testDirection) && !hasVineFace(block, testDirection)) {
            if (!canSpreadVine(in, pos)) return;
            Point testPos = vineOffset(pos, testDirection);
            Block edge = in.getBlock(testPos);
            if (edge.isAir()) {
                String cw = Placement.clockwise(testDirection), ccw = Placement.counterClockwise(testDirection);
                boolean cwHas = hasVineFace(block, cw), ccwHas = hasVineFace(block, ccw);
                Point cwTestPos = vineOffset(testPos, cw), ccwTestPos = vineOffset(testPos, ccw);
                if (cwHas && isAcceptableVineNeighbor(in, cwTestPos, cw)) {
                    in.setBlock(testPos, Block.VINE.withProperty(cw, "true"));
                } else if (ccwHas && isAcceptableVineNeighbor(in, ccwTestPos, ccw)) {
                    in.setBlock(testPos, Block.VINE.withProperty(ccw, "true"));
                } else {
                    String opp = Placement.opposite(testDirection);
                    if (cwHas && in.getBlock(cwTestPos).isAir() && isAcceptableVineNeighbor(in, vineOffset(pos, cw), opp)) {
                        in.setBlock(cwTestPos, Block.VINE.withProperty(opp, "true"));
                    } else if (ccwHas && in.getBlock(ccwTestPos).isAir() && isAcceptableVineNeighbor(in, vineOffset(pos, ccw), opp)) {
                        in.setBlock(ccwTestPos, Block.VINE.withProperty(opp, "true"));
                    } else if (rng.nextFloat() < 0.05f && isAcceptableVineNeighbor(in, testPos.add(0, 1, 0), "up")) {
                        in.setBlock(testPos, Block.VINE.withProperty("up", "true"));
                    }
                }
            } else if (isAcceptableVineNeighbor(in, testPos, testDirection)) {
                in.setBlock(pos, block.withProperty(testDirection, "true"));
            }
            return;
        }

        if (testDirection.equals("up")) {
            Point abovePos = pos.add(0, 1, 0);
            if (isAcceptableVineNeighbor(in, abovePos, "up")) {
                in.setBlock(pos, block.withProperty("up", "true"));
                return;
            }
            if (in.getBlock(abovePos).isAir()) {
                if (!canSpreadVine(in, pos)) return;
                Block above = Block.VINE.withProperties(block.properties());
                for (String dir : VINE_HORIZONTAL) {
                    if (rng.nextBoolean() || !isAcceptableVineNeighbor(in, vineOffset(abovePos, dir), dir)) {
                        above = above.withProperty(dir, "false");
                    }
                }
                if (hasHorizontalVineConnection(above)) in.setBlock(abovePos, above);
                return;
            }
        }

        Point belowPos = pos.add(0, -1, 0);
        Block below = in.getBlock(belowPos);
        if (below.isAir() || below.key().value().equals("vine")) {
            Block before = below.isAir() ? Block.VINE : below;
            Block after = copyRandomVineFaces(block, before, rng);
            if (!after.equals(before) && hasHorizontalVineConnection(after)) {
                in.setBlock(belowPos, after);
            }
        }
    }

    private static Block copyRandomVineFaces(Block from, Block to, ThreadLocalRandom rng) {
        for (String dir : VINE_HORIZONTAL) {
            if (rng.nextBoolean() && hasVineFace(from, dir)) to = to.withProperty(dir, "true");
        }
        return to;
    }

    private static boolean hasHorizontalVineConnection(Block block) {
        for (String dir : VINE_HORIZONTAL) if (hasVineFace(block, dir)) return true;
        return false;
    }

    private static boolean hasVineFace(Block block, String dir) {
        return "true".equals(block.getProperty(dir));
    }

    private static boolean isHorizontal(String dir) {
        return dir.equals("north") || dir.equals("south") || dir.equals("east") || dir.equals("west");
    }

    private static Point vineOffset(Point pos, String dir) {
        return dir.equals("up") ? pos.add(0, 1, 0) : dir.equals("down") ? pos.add(0, -1, 0) : Placement.offset(pos, dir);
    }

    /**
     * MultifaceBlock.canAttachTo, behavioural: real vanilla checks the neighbor's exact face
     * shape; this project has no per-face voxel-shape model (same coarse-solidity
     * approximation used elsewhere, e.g. RandomTicks' own skyExposed/enderman-holdable
     * checks), so any solid neighbor block counts as an acceptable attachment surface
     * regardless of which specific face is queried.
     */
    private static boolean isAcceptableVineNeighbor(Instance in, Point pos, String directionToNeighbor) {
        return in.getBlock(pos).isSolid();
    }

    /** VineBlock.canSpread: a 9x3x9 box (radius 4 horizontally, 1 vertically) caps at 5 vines. */
    private static boolean canSpreadVine(Instance in, Point pos) {
        int count = 5;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (in.getBlock(pos.add(dx, dy, dz)).key().value().equals("vine")) {
                        if (--count <= 0) return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * SpreadingSnowyBlock.randomTick: die to dirt when smothered, else at
     * brightness >= 9 above try 4 nearby dirt blocks (±1 xz, -3..+1 y).
     */
    private static Handler spreading(String self) {
        return (in, pos, block) -> {
            if (smothered(in, pos)) {
                in.setBlock(pos, Block.DIRT);
                return;
            }
            if (brightness(in, pos.add(0, 1, 0)) < 9) return;
            var rng = ThreadLocalRandom.current();
            for (int i = 0; i < 4; i++) {
                Point target = pos.add(rng.nextInt(3) - 1, rng.nextInt(5) - 3, rng.nextInt(3) - 1);
                if (!in.isChunkLoaded(target.blockX() >> 4, target.blockZ() >> 4)) continue;
                if (!in.getBlock(target).key().value().equals("dirt")) continue;
                if (smothered(in, target)) continue;
                in.setBlock(target, Block.fromKey(self));
            }
        };
    }

    /** canStayAlive: full-solid or fluid-covered above kills grass; snow layer 1 is fine. */
    private static boolean smothered(Instance in, Point pos) {
        Block above = in.getBlock(pos.add(0, 1, 0));
        String key = above.key().value();
        if (key.equals("snow") && "1".equals(above.getProperty("layers"))) return false;
        if (key.equals("water")) return true;
        return above.isSolid() && above.registry().occludes();
    }

    /** IceBlock.randomTick: block light > 11 melts to water. */
    private static void meltIce(Instance in, Point pos, Block block) {
        if (in.getBlockLight(pos.blockX(), pos.blockY(), pos.blockZ()) > 11) {
            in.setBlock(pos, Block.WATER);
            dev.pointofpressure.minecom.redstone.Redstone.neighborsChanged(pos);
        }
    }

    /** SnowLayerBlock.randomTick: block light > 11 removes the layers. */
    private static void meltSnowLayer(Instance in, Point pos, Block block) {
        if (in.getBlockLight(pos.blockX(), pos.blockY(), pos.blockZ()) > 11) {
            in.setBlock(pos, Block.AIR);
        }
    }

    /** SugarCaneBlock.randomTick: columns to 3, AGE 15 grows, else AGE+1. */
    private static void growSugarCane(Instance in, Point pos, Block block) {
        if (!in.getBlock(pos.add(0, 1, 0)).isAir()) return;
        int height = 1;
        while (in.getBlock(pos.add(0, -height, 0)).key().value().equals("sugar_cane")) height++;
        if (height >= 3) return;
        int age = Integer.parseInt(block.getProperty("age"));
        if (age == 15) {
            in.setBlock(pos.add(0, 1, 0), Block.SUGAR_CANE);
            in.setBlock(pos, block.withProperty("age", "0"));
        } else {
            in.setBlock(pos, block.withProperty("age", String.valueOf(age + 1)));
        }
    }

    /**
     * CactusBlock.randomTick: columns to 3, AGE 8 may sprout a flower
     * (10%, or 25% on a full column), AGE 15 grows, else AGE+1.
     */
    private static void growCactus(Instance in, Point pos, Block block) {
        if (!in.getBlock(pos.add(0, 1, 0)).isAir()) return;
        int age = Integer.parseInt(block.getProperty("age"));
        int height = 1;
        while (in.getBlock(pos.add(0, -height, 0)).key().value().equals("cactus")) {
            if (++height == 3 && age == 15) return;
        }
        var rng = ThreadLocalRandom.current();
        if (age == 8) {
            // a sprouted flower blocks the column from then on (vanilla 26.x)
            double flowerChance = height >= 3 ? 0.25 : 0.1;
            if (rng.nextDouble() <= flowerChance) {
                in.setBlock(pos.add(0, 1, 0), Block.CACTUS_FLOWER);
            }
        } else if (age == 15 && height < 3) {
            in.setBlock(pos.add(0, 1, 0), Block.CACTUS);
            in.setBlock(pos, block.withProperty("age", "0"));
            return;
        }
        if (age < 15) {
            in.setBlock(pos, block.withProperty("age", String.valueOf(age + 1)));
        }
    }

    /**
     * FarmlandBlock.randomTick: water within the 9x2x9 box (or rain) keeps
     * MOISTURE at 7; otherwise it drains by 1, and dry farmland with nothing
     * planted on it reverts to dirt.
     */
    private static void tickFarmland(Instance in, Point pos, Block block) {
        int moisture = Integer.parseInt(block.getProperty("moisture"));
        boolean wet = nearWater(in, pos)
                || dev.pointofpressure.minecom.survival.WeatherCycle.isRaining(in);
        if (wet) {
            if (moisture < 7) in.setBlock(pos, block.withProperty("moisture", "7"));
        } else if (moisture > 0) {
            in.setBlock(pos, block.withProperty("moisture", String.valueOf(moisture - 1)));
        } else if (!cropAbove(in, pos)) {
            in.setBlock(pos, Block.DIRT);
        }
    }

    private static boolean nearWater(Instance in, Point pos) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    String key = in.getBlock(pos.add(dx, dy, dz)).key().value();
                    if (key.equals("water")) return true;
                }
            }
        }
        return false;
    }

    /** #maintains_farmland, approximated by the crops Farming plants. */
    private static boolean cropAbove(Instance in, Point pos) {
        String above = in.getBlock(pos.add(0, 1, 0)).key().value();
        return switch (above) {
            case "wheat", "carrots", "potatoes", "beetroots", "melon_stem", "pumpkin_stem",
                 "attached_melon_stem", "attached_pumpkin_stem", "torchflower_crop",
                 "pitcher_crop" -> true;
            default -> false;
        };
    }

    /**
     * CropBlock.randomTick/getGrowthSpeed (decompile-verified,
     * vanilla-src/net/minecraft/world/level/block/CropBlock.java): light gate (raw brightness
     * &gt;= 9), then a 3x3 farmland-moisture-weighted growth-speed scan below the crop (center
     * cell full weight, ring cells /4; unmoistened farmland contributes 1.0, moistened 3.0),
     * halved if the crop has same-type neighbors on both axes (west/east AND north/south) or if
     * only a diagonal same-type neighbor exists, then a
     * {@code nextInt((int)(25.0F/growthSpeed)+1)==0} roll advances age by one. Replaces
     * Farming.java's old flat 100-tick/20%-roll sweep (AUDIT.md) — applies to any
     * wheat/carrots/potatoes/beetroots block, not just Farming.CROPS-tracked ones (a fidelity
     * improvement: real vanilla growth isn't restricted to player-planted crops).
     */
    private static void growCrop(Instance in, Point pos, Block block) {
        if (brightness(in, pos) < 9) return;
        String age = block.getProperty("age");
        if (age == null) return;
        int current = Integer.parseInt(age);
        int max = Farming.maxAge(block);
        if (current >= max) return;
        var rng = ThreadLocalRandom.current();
        float speed = cropGrowthSpeed(in, pos, block.key().value());
        if (rng.nextInt((int) (25.0F / speed) + 1) == 0) {
            in.setBlock(pos, block.withProperty("age", String.valueOf(current + 1)));
        }
    }

    private static float cropGrowthSpeed(Instance in, Point pos, String cropKey) {
        float speed = 1.0F;
        Point below = pos.add(0, -1, 0);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                float weight = 0.0F;
                Block belowState = in.getBlock(below.add(dx, 0, dz));
                if (belowState.key().value().equals("farmland")) {
                    weight = 1.0F;
                    String moisture = belowState.getProperty("moisture");
                    if (moisture != null && !moisture.equals("0")) weight = 3.0F;
                }
                if (dx != 0 || dz != 0) weight /= 4.0F;
                speed += weight;
            }
        }
        boolean sameEW = sameCrop(in, pos.add(-1, 0, 0), cropKey) || sameCrop(in, pos.add(1, 0, 0), cropKey);
        boolean sameNS = sameCrop(in, pos.add(0, 0, -1), cropKey) || sameCrop(in, pos.add(0, 0, 1), cropKey);
        if (sameEW && sameNS) {
            speed /= 2.0F;
        } else if (sameCrop(in, pos.add(-1, 0, -1), cropKey) || sameCrop(in, pos.add(1, 0, -1), cropKey)
                || sameCrop(in, pos.add(1, 0, 1), cropKey) || sameCrop(in, pos.add(-1, 0, 1), cropKey)) {
            speed /= 2.0F;
        }
        return speed;
    }

    private static boolean sameCrop(Instance in, Point pos, String cropKey) {
        return in.getBlock(pos).key().value().equals(cropKey);
    }

    /**
     * SaplingBlock.randomTick/advanceTree (decompile-verified): light gate (raw brightness
     * directly above the sapling &gt;= 9), then a 1/7 roll. A stage-0 sapling that passes just
     * cycles to stage 1 — growing the actual tree needs a SECOND successful roll on a stage-1
     * sapling (Farming.advanceTree, shared with bone meal). Replaces Farming's old one-shot
     * 60-120-second scheduled-delay approximation that grew straight to a tree on placement,
     * ignoring both the light gate and the two-stage climb.
     */
    private static void growSapling(Instance in, Point pos, Block block) {
        if (brightness(in, pos.add(0, 1, 0)) < 9) return;
        if (ThreadLocalRandom.current().nextInt(7) != 0) return;
        Farming.advanceTree(in, pos, block);
    }

    /** BuddingAmethystBlock.randomTick: 1/5 to advance a bud on a random face. */
    private static void growAmethyst(Instance in, Point pos, Block block) {
        var rng = ThreadLocalRandom.current();
        if (rng.nextInt(5) != 0) return;
        int face = rng.nextInt(6);
        int dx = face == 0 ? 1 : face == 1 ? -1 : 0;
        int dy = face == 2 ? 1 : face == 3 ? -1 : 0;
        int dz = face == 4 ? 1 : face == 5 ? -1 : 0;
        Point target = pos.add(dx, dy, dz);
        Block at = in.getBlock(target);
        String key = at.key().value();
        String facing = facingName(dx, dy, dz);
        String next = null;
        if (at.isAir()) next = "small_amethyst_bud";
        else if (key.equals("small_amethyst_bud") && facing.equals(at.getProperty("facing"))) next = "medium_amethyst_bud";
        else if (key.equals("medium_amethyst_bud") && facing.equals(at.getProperty("facing"))) next = "large_amethyst_bud";
        else if (key.equals("large_amethyst_bud") && facing.equals(at.getProperty("facing"))) next = "amethyst_cluster";
        if (next != null) {
            in.setBlock(target, Block.fromKey(next).withProperty("facing", facing));
        }
    }

    private static String facingName(int dx, int dy, int dz) {
        if (dx > 0) return "east";
        if (dx < 0) return "west";
        if (dy > 0) return "up";
        if (dy < 0) return "down";
        return dz > 0 ? "south" : "north";
    }

    // ------------------------------------------------------------------ copper oxidation

    private static final Handler OXIDATION = RandomTicks::oxidize;

    /**
     * ChangeOverTimeBlock.changeOverTime + getNextState: a 0.05688889 roll,
     * then a Manhattan-4 neighbor scan — any less-weathered copper neighbor
     * vetoes; otherwise chance = ((older+1)/(older+same+1))^2, x0.75 while
     * unaffected. Properties carry over (stairs keep facing etc.).
     */
    private static void oxidize(Instance in, Point pos, Block block) {
        var rng = ThreadLocalRandom.current();
        if (rng.nextFloat() >= 0.05688889f) return;
        String key = block.key().value();
        int ownAge = oxidationAge(key);
        int same = 0, older = 0;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4 + Math.abs(dx); dy <= 4 - Math.abs(dx); dy++) {
                int budget = 4 - Math.abs(dx) - Math.abs(dy);
                for (int dz = -budget; dz <= budget; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    String neighbor = in.getBlock(pos.add(dx, dy, dz)).key().value();
                    if (!isWeatheringCopper(neighbor)) continue;
                    int age = oxidationAge(neighbor);
                    if (age < ownAge) return;
                    if (age > ownAge) older++;
                    else same++;
                }
            }
        }
        float ratio = (float) (older + 1) / (older + same + 1);
        float chance = ratio * ratio * (ownAge == 0 ? 0.75f : 1.0f);
        if (rng.nextFloat() < chance) {
            String next = nextOxidation(key);
            Block nextBlock = Block.fromKey(next);
            if (nextBlock != null) {
                in.setBlock(pos, nextBlock.withProperties(block.properties()));
            }
        }
    }

    /** Public so CopperWaxing can gate honeycomb application to real copper-family blocks. */
    public static boolean isWeatheringCopper(String key) {
        return nextOxidation(key) != null || key.startsWith("oxidized_");
    }

    private static int oxidationAge(String key) {
        if (key.startsWith("exposed_")) return 1;
        if (key.startsWith("weathered_")) return 2;
        if (key.startsWith("oxidized_")) return 3;
        return 0;
    }

    /**
     * WeatheringCopper.NEXT_BY_BLOCK, derived from key names: the next stage
     * prefixes exposed_/weathered_/oxidized_ onto the base name (copper_block
     * itself weathers to exposed_copper). Null = not a weathering block
     * (waxed variants never match; oxidized is terminal). Public for SelfTest.
     */
    public static String nextOxidation(String key) {
        if (key.startsWith("waxed_") || key.startsWith("oxidized_")) return null;
        String base;
        String nextPrefix;
        if (key.startsWith("exposed_")) {
            base = key.substring("exposed_".length());
            nextPrefix = "weathered_";
        } else if (key.startsWith("weathered_")) {
            base = key.substring("weathered_".length());
            nextPrefix = "oxidized_";
        } else {
            base = key;
            nextPrefix = "exposed_";
        }
        if (base.equals("copper_block")) base = "copper";
        else if (!base.contains("copper")) return null;
        String next = nextPrefix + base;
        return Block.fromKey(next) != null ? next : null;
    }

    /**
     * WeatheringCopper.PREVIOUS_BY_BLOCK (the NEXT_BY_BLOCK bimap's inverse) — the exact
     * mirror of nextOxidation above: peel one exposed_/weathered_/oxidized_ prefix back off,
     * restoring the copper_block/copper irregular base-name swap. Null at the unweathered
     * base (nothing to scrape further) or for waxed/non-copper keys. Public for AxeItem's
     * scrape interaction (CopperWaxing).
     */
    public static String previousOxidation(String key) {
        if (key.startsWith("waxed_")) return null;
        String rest, prevPrefix;
        if (key.startsWith("oxidized_")) {
            rest = key.substring("oxidized_".length());
            prevPrefix = "weathered_";
        } else if (key.startsWith("weathered_")) {
            rest = key.substring("weathered_".length());
            prevPrefix = "exposed_";
        } else if (key.startsWith("exposed_")) {
            rest = key.substring("exposed_".length());
            prevPrefix = "";
        } else {
            return null; // already the unweathered base
        }
        String candidate = prevPrefix.isEmpty()
                ? (rest.equals("copper") ? "copper_block" : rest)
                : prevPrefix + rest;
        return Block.fromKey(candidate) != null ? candidate : null;
    }

    // ------------------------------------------------------------------ light

    /** getMaxLocalRawBrightness, behavioural (VNaturalSpawner precedent). */
    private static int brightness(Instance in, Point pos) {
        int block = in.getBlockLight(pos.blockX(), pos.blockY(), pos.blockZ());
        int sky = skyExposed(in, pos) ? (isNight(in) ? 4 : 15) : 0;
        return Math.max(block, sky);
    }

    /** Package-private (not private): reused by FireSpread's isNearRain. */
    static boolean skyExposed(Instance in, Point pos) {
        for (int y = pos.blockY(); y <= pos.blockY() + 48; y++) {
            Block above = in.getBlock(pos.blockX(), y, pos.blockZ());
            if (above.isSolid() && above.registry().occludes()) return false;
        }
        return true;
    }

    private static boolean isNight(Instance in) {
        long t = Math.floorMod(in.getTime(), 24000L);
        return t >= 13000 && t <= 23000;
    }
}
