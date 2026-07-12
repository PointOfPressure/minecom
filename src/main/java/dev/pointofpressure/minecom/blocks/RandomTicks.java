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
 * age-ratio chance, 0.75 modifier while unaffected), and budding amethyst
 * (1/5 bud growth per face). Light is the project's behavioural model
 * (VNaturalSpawner precedent): sky-exposed = at/above surface, 15 by day /
 * 4 at night, plus real Minestom block light. Crop growth stays on
 * Farming.java's scheduled approximation for now (AUDIT.md); snow
 * accumulation stays in survival/Snow.java. Precipitation ice freeze, fire
 * spread, vines and bamboo are follow-ups (AUDIT.md).
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

    private static boolean isWeatheringCopper(String key) {
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

    // ------------------------------------------------------------------ light

    /** getMaxLocalRawBrightness, behavioural (VNaturalSpawner precedent). */
    private static int brightness(Instance in, Point pos) {
        int block = in.getBlockLight(pos.blockX(), pos.blockY(), pos.blockZ());
        int sky = skyExposed(in, pos) ? (isNight(in) ? 4 : 15) : 0;
        return Math.max(block, sky);
    }

    private static boolean skyExposed(Instance in, Point pos) {
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
