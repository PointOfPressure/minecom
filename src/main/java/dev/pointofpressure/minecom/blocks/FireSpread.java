package dev.pointofpressure.minecom.blocks;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FireBlock's own tick (decompile-verified, 26.1.2) — deliberately NOT wired into
 * RandomTicks.java's chunk-sampled engine: real fire doesn't use a random tick at all, it
 * self-reschedules a SCHEDULED tick every 30+rand(10) ticks per block
 * (FireBlock.onPlace/getFireTickDelay), the same "tracked position + shared periodic
 * scheduler" shape Campfires/Jukebox already use here, not Redstone's daylight-detector/
 * lightning-rod tracked-position idiom (those are power sources polled by the redstone
 * sweep, this is a self-driving timer per block).
 *
 * Per tick: extinguish if unsupported, extinguish in nearby rain (unless the block below
 * is "infiniburn", i.e. netherrack/magma), otherwise age up (0-15, biases toward staying
 * put), burn out the 6 cardinal/vertical neighbors on their own odds (checkBurnOut — can
 * remove the neighbor block entirely, replacing it with fire, or prime TNT), then roll a
 * spread attempt across a 3x3x6 volume above/around, each candidate air block weighted by
 * the nearest flammable neighbor's igniteOdds, divided by (age+30) and difficulty-boosted.
 * Rain gates the spread half too (not the burnout half — matches decompiled order).
 *
 * Not modeled: EnvironmentAttributes.INCREASED_FIRE_BURNOUT (a per-dimension attribute
 * this project's environment-attribute system doesn't expose yet — treated as always off,
 * same "no gamerule store" class of simplification as SPREAD_VINES/doFireTick elsewhere),
 * isFaceSturdy's exact per-shape solidity (approximated as Block.isSolid(), the same
 * coarse-solidity pattern used throughout this file's siblings), and the nether/end
 * infiniburn tag (this project's fire mostly matters in the overworld, so only the
 * overworld's netherrack/magma_block pair is modeled — AUDIT.md).
 */
public final class FireSpread {
    private FireSpread() {}

    private static final Map<String, Point> POSITIONS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> COUNTDOWN = new ConcurrentHashMap<>();
    private static Instance instance;

    private static final Set<String> INFINIBURN = Set.of("netherrack", "magma_block");

    /** key -> [igniteOdds, burnOdds] (FireBlock.bootStrap, ported verbatim). */
    private static final Map<String, int[]> FLAMMABLE = new HashMap<>();

    private static void addAll(int ignite, int burn, String... keys) {
        for (String key : keys) FLAMMABLE.put(key, new int[]{ignite, burn});
    }

    static {
        String[] woods = {"oak", "spruce", "birch", "jungle", "acacia", "cherry",
                "dark_oak", "pale_oak", "mangrove"};
        for (String w : woods) {
            addAll(5, 20, w + "_planks", w + "_slab", w + "_fence_gate", w + "_fence", w + "_stairs");
            addAll(5, 5, w + "_log", "stripped_" + w + "_log", w + "_wood", "stripped_" + w + "_wood");
            addAll(30, 60, w + "_leaves");
            addAll(30, 20, w + "_shelf");
        }
        addAll(5, 20, "bamboo_planks", "bamboo_mosaic", "bamboo_slab", "bamboo_mosaic_slab",
                "bamboo_fence_gate", "bamboo_fence", "bamboo_stairs", "bamboo_mosaic_stairs");
        addAll(5, 5, "bamboo_block", "stripped_bamboo_block");
        addAll(30, 20, "bamboo_shelf");
        addAll(5, 20, "mangrove_roots");
        addAll(30, 20, "bookshelf");
        addAll(15, 100, "tnt");
        addAll(60, 100, "short_grass", "fern", "dead_bush", "short_dry_grass", "tall_dry_grass",
                "sunflower", "lilac", "rose_bush", "peony", "tall_grass", "large_fern", "dandelion",
                "golden_dandelion", "poppy", "open_eyeblossom", "closed_eyeblossom", "blue_orchid",
                "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
                "oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower", "pitcher_plant",
                "wither_rose", "pink_petals", "wildflowers", "leaf_litter", "cactus_flower",
                "sweet_berry_bush", "spore_blossom", "big_dripleaf", "big_dripleaf_stem",
                "small_dripleaf", "firefly_bush", "bush");
        String[] wools = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
                "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        for (String c : wools) {
            addAll(30, 60, c + "_wool");
            addAll(60, 20, c + "_carpet");
        }
        addAll(15, 100, "vine");
        addAll(5, 5, "coal_block");
        addAll(60, 20, "hay_block");
        addAll(15, 20, "target");
        addAll(5, 100, "pale_moss_block", "pale_moss_carpet", "pale_hanging_moss");
        addAll(30, 60, "dried_kelp_block", "azalea_leaves", "flowering_azalea_leaves");
        addAll(60, 60, "bamboo", "scaffolding");
        addAll(30, 20, "lectern");
        addAll(5, 20, "composter", "beehive");
        addAll(30, 20, "bee_nest");
        addAll(15, 60, "cave_vines", "cave_vines_plant");
        addAll(30, 60, "azalea", "flowering_azalea", "hanging_roots");
        addAll(15, 100, "glow_lichen");
    }

    public static void start(Instance overworld) {
        instance = overworld;
        dev.pointofpressure.minecom.Persist.register(persistence());
        MinecraftServer.getSchedulerManager().buildTask(FireSpread::tickAll)
                .repeat(TaskSchedule.tick(1)).schedule();
    }

    /**
     * Fire's own scheduled-tick countdown survives a restart too (docs/PERSISTENCE.md) — the
     * block itself was already persisted as part of chunk data regardless, but without this a
     * restart would silently stop spreading/aging/burning-out for every fire that was
     * mid-countdown, indistinguishable from the block just sitting inert.
     */
    private static dev.pointofpressure.minecom.StateAdapter persistence() {
        return new dev.pointofpressure.minecom.StateAdapter() {
            @Override
            public String kind() {
                return "fire_tick";
            }

            @Override
            public void collect(Instance in, java.util.function.BiConsumer<Point, com.google.gson.JsonObject> out) {
                POSITIONS.forEach((key, pos) -> {
                    com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                    o.addProperty("countdown", COUNTDOWN.getOrDefault(key, fireTickDelay()));
                    out.accept(pos, o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, com.google.gson.JsonObject data) {
                String key = Containers.posKey(pos);
                POSITIONS.put(key, pos);
                COUNTDOWN.put(key, data.get("countdown").getAsInt());
            }

            @Override
            public void wipe() {
                wipeForTest();
            }
        };
    }

    /** Arms (or re-arms) a fire block's own scheduled tick. Call after placing fire anywhere. */
    public static void track(Point pos) {
        String key = Containers.posKey(pos);
        POSITIONS.put(key, pos);
        COUNTDOWN.put(key, fireTickDelay());
    }

    /** Playtest hook: run one fire tick immediately, bypassing the 30+rand(10) real delay. */
    public static void forceTick(Instance in, Point pos) {
        instance = in;
        Block block = in.getBlock(pos);
        if (block.key().value().equals("fire")) tickFire(pos, block);
    }

    /** Playtest hook: drop all tracked positions (playtest wipe). */
    public static void wipeForTest() {
        POSITIONS.clear();
        COUNTDOWN.clear();
    }

    /** Playtest hook: is this position's own scheduled tick currently armed? */
    public static boolean isTrackedForTest(Point pos) {
        return POSITIONS.containsKey(Containers.posKey(pos));
    }

    /**
     * Playtest hook: force ONE spread attempt from {@code firePos} onto {@code candidate}, as if
     * the per-tick RNG roll ({@code rng.nextInt(rate) <= odds} in {@link #tickFire}) had
     * succeeded — every other real gate still applies untouched (candidate must be air with a
     * positive {@link #igniteOddsAround}, and not near rain). The roll itself is one Bernoulli
     * trial with single-digit-percent odds per real tick (age-dependent, ~0.7% for a lone
     * two-away neighbor), so a forced-tick loop bounded at any fixed iteration count is still a
     * race that can fail extremely rarely — this isolates the deterministic detection+placement
     * logic the check actually exists to catch a regression in (a near-but-not-touching
     * flammable neighbor must be found and ignited) from FireBlock's inherently probabilistic
     * timing, matching CONVENTIONS.md's "state gate, not a timing window" rule. Returns false if
     * the candidate isn't currently spread-eligible (no positive ignite odds, or rained out) —
     * true only after actually placing the fire.
     */
    public static boolean forceSpreadForTest(Point firePos, Point candidate) {
        Block fireBlock = instance.getBlock(firePos);
        if (!fireBlock.key().value().equals("fire")) return false;
        int age = age(fireBlock);
        if (igniteOddsAround(candidate) <= 0) return false;
        boolean raining = dev.pointofpressure.minecom.survival.WeatherCycle.isRaining(instance);
        if (raining && isNearRain(candidate)) return false;
        int spreadAge = Math.min(15, age + ThreadLocalRandom.current().nextInt(5) / 4);
        placeFire(candidate, spreadAge);
        return true;
    }

    private static void untrack(String key) {
        POSITIONS.remove(key);
        COUNTDOWN.remove(key);
    }

    private static int fireTickDelay() {
        return 30 + ThreadLocalRandom.current().nextInt(10);
    }

    private static void tickAll() {
        if (instance == null || POSITIONS.isEmpty()) return;
        for (String key : new ArrayList<>(POSITIONS.keySet())) {
            Point pos = POSITIONS.get(key);
            if (pos == null) continue;
            int left = COUNTDOWN.getOrDefault(key, 0) - 1;
            if (left > 0) {
                COUNTDOWN.put(key, left);
                continue;
            }
            Block block = instance.getBlock(pos);
            if (!block.key().value().equals("fire")) {
                untrack(key);
                continue;
            }
            tickFire(pos, block);
            if (instance.getBlock(pos).key().value().equals("fire")) {
                COUNTDOWN.put(key, fireTickDelay());
            } else {
                untrack(key);
            }
        }
    }

    private static void tickFire(Point pos, Block block) {
        var rng = ThreadLocalRandom.current();
        Point belowPos = pos.add(0, -1, 0);
        Block below = instance.getBlock(belowPos);
        boolean belowSturdy = below.isSolid();
        if (!belowSturdy && !isValidFireLocation(pos)) {
            instance.setBlock(pos, Block.AIR);
            return;
        }

        boolean infiniBurn = INFINIBURN.contains(below.key().value());
        int age = age(block);
        boolean raining = dev.pointofpressure.minecom.survival.WeatherCycle.isRaining(instance);
        if (!infiniBurn && raining && isNearRain(pos) && rng.nextFloat() < 0.2f + age * 0.03f) {
            instance.setBlock(pos, Block.AIR);
            return;
        }

        int newAge = Math.min(15, age + rng.nextInt(3) / 2);
        if (newAge != age) {
            block = block.withProperty("age", String.valueOf(newAge));
            instance.setBlock(pos, block);
            age = newAge;
        }

        if (!infiniBurn) {
            if (!isValidFireLocation(pos)) {
                if (!belowSturdy || age > 3) instance.setBlock(pos, Block.AIR);
                return;
            }
            if (age == 15 && rng.nextInt(4) == 0 && !canBurn(below.key().value())) {
                instance.setBlock(pos, Block.AIR);
                return;
            }
        }

        int diffBoost = dev.pointofpressure.minecom.Difficulty.current().ordinal() * 7;
        checkBurnOut(pos.add(1, 0, 0), 300, rng, age);
        checkBurnOut(pos.add(-1, 0, 0), 300, rng, age);
        checkBurnOut(belowPos, 250, rng, age);
        checkBurnOut(pos.add(0, 1, 0), 250, rng, age);
        checkBurnOut(pos.add(0, 0, 1), 300, rng, age);
        checkBurnOut(pos.add(0, 0, -1), 300, rng, age);

        for (int xx = -1; xx <= 1; xx++) {
            for (int zz = -1; zz <= 1; zz++) {
                for (int yy = -1; yy <= 4; yy++) {
                    if (xx == 0 && yy == 0 && zz == 0) continue;
                    int rate = 100 + (yy > 1 ? (yy - 1) * 100 : 0);
                    Point testPos = pos.add(xx, yy, zz);
                    int igniteOdds = igniteOddsAround(testPos);
                    if (igniteOdds <= 0) continue;
                    int odds = (igniteOdds + 40 + diffBoost) / (age + 30);
                    if (odds > 0 && rng.nextInt(rate) <= odds
                            && (!raining || !isNearRain(testPos))) {
                        int spreadAge = Math.min(15, age + rng.nextInt(5) / 4);
                        placeFire(testPos, spreadAge);
                    }
                }
            }
        }
    }

    private static void checkBurnOut(Point pos, int chance, ThreadLocalRandom rng, int age) {
        Block state = instance.getBlock(pos);
        int burnOdds = burnOdds(state.key().value());
        if (rng.nextInt(chance) >= burnOdds) return;
        if (rng.nextInt(age + 10) < 5 && !isRainingAtExact(pos)) {
            int newAge = Math.min(age + rng.nextInt(5) / 4, 15);
            placeFire(pos, newAge);
        } else {
            instance.setBlock(pos, Block.AIR);
            if (state.key().value().equals("tnt")) {
                dev.pointofpressure.minecom.blocks.Explosions.primeTnt(instance, pos, 80, null);
            }
        }
    }

    private static void placeFire(Point pos, int age) {
        instance.setBlock(pos, Block.FIRE.withProperty("age", String.valueOf(age)));
        track(pos);
    }

    private static boolean isValidFireLocation(Point pos) {
        for (int[] d : NEIGHBORS_6) {
            if (canBurn(instance.getBlock(pos.add(d[0], d[1], d[2])).key().value())) return true;
        }
        return false;
    }

    /** getIgniteOdds(LevelReader, pos): the strongest neighbor's odds, only if pos itself is air. */
    private static int igniteOddsAround(Point pos) {
        if (!instance.getBlock(pos).isAir()) return 0;
        int best = 0;
        for (int[] d : NEIGHBORS_6) {
            best = Math.max(best, igniteOdds(instance.getBlock(pos.add(d[0], d[1], d[2])).key().value()));
        }
        return best;
    }

    private static final int[][] NEIGHBORS_6 =
            {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static boolean canBurn(String key) {
        return igniteOdds(key) > 0;
    }

    private static int igniteOdds(String key) {
        int[] odds = FLAMMABLE.get(key);
        return odds == null ? 0 : odds[0];
    }

    private static int burnOdds(String key) {
        int[] odds = FLAMMABLE.get(key);
        return odds == null ? 0 : odds[1];
    }

    private static int age(Block block) {
        String age = block.getProperty("age");
        return age == null ? 0 : Integer.parseInt(age);
    }

    /** isNearRain: raining directly above, or one of the 4 horizontal neighbors, and sky-exposed. */
    private static boolean isNearRain(Point pos) {
        return isRainingAtExact(pos) || isRainingAtExact(pos.add(1, 0, 0)) || isRainingAtExact(pos.add(-1, 0, 0))
                || isRainingAtExact(pos.add(0, 0, 1)) || isRainingAtExact(pos.add(0, 0, -1));
    }

    private static boolean isRainingAtExact(Point pos) {
        return dev.pointofpressure.minecom.survival.WeatherCycle.isRaining(instance) && RandomTicks.skyExposed(instance, pos);
    }
}
