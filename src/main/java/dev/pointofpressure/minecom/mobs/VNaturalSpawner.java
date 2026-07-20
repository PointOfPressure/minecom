package dev.pointofpressure.minecom.mobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * Faithful port of vanilla's {@code NaturalSpawner} (net.minecraft.world.level):
 * per-tick, over every chunk within {@value #SPAWN_DISTANCE_CHUNK} chunks of a
 * player, it attempts to spawn each below-cap mob category using the real
 * biome-weighted spawn lists, the 3-group pack loop, the 24-block player-distance
 * rule, per-type spawn-placement + spawn-rule predicates, and the 128/32-block
 * despawn rule. Mob caps use the vanilla {@code max*spawnableChunks/289} formula.
 *
 * <p>Vanilla spawning draws from the level's non-seeded random, so this matches the
 * ALGORITHM and probabilities (verified by unit tests), not bit-identical positions.
 * Sky light is modelled behaviourally (sky-exposure via the world-surface heightmap +
 * time of day, since Minestom has no server-side day/night sky-light recompute); block
 * light queries Minestom's real {@link net.minestom.server.instance.LightingChunk}
 * propagation directly (torches/lanterns/etc. correctly suppress nearby spawns). The
 * per-chunk decision phase is embarrassingly parallel (see {@link #spawnTickParallel})
 * — the project's multithreading thesis in action.
 */
public final class VNaturalSpawner {

    // ---- vanilla constants ----
    static final int MIN_SPAWN_DISTANCE = 24;          // min distance from player (squared 576)
    static final int SPAWN_DISTANCE_CHUNK = 8;         // spawnable-chunk radius around a player
    static final int MAGIC_NUMBER = 17 * 17;           // 289

    // ---- mob categories (MobCategory.java) ----
    enum Cat {
        MONSTER(70, false, false, 128),
        CREATURE(10, true, true, 128),
        AMBIENT(15, true, false, 128),
        AXOLOTLS(5, true, false, 128),
        UNDERGROUND_WATER_CREATURE(5, true, false, 128),
        WATER_CREATURE(5, true, false, 128),
        WATER_AMBIENT(20, true, false, 64),
        MISC(-1, true, true, 128);

        final int max;
        final boolean friendly;
        final boolean persistent;
        final int despawnDistance;
        static final int NO_DESPAWN_DISTANCE = 32;

        Cat(int max, boolean friendly, boolean persistent, int despawnDistance) {
            this.max = max; this.friendly = friendly; this.persistent = persistent; this.despawnDistance = despawnDistance;
        }
    }

    /** Spawn placement rule kind (SpawnPlacementTypes). */
    private enum Placement { ON_GROUND, IN_WATER, IN_LAVA, NO_RESTRICTIONS }

    /** One biome spawn-list entry (MobSpawnSettings.SpawnerData). */
    private record Entry(String type, int weight, int min, int max) {}

    // ---- static spawn data ----
    /** biome id -> category -> weighted spawn entries. */
    private static final Map<String, Map<Cat, List<Entry>>> SPAWNERS = new HashMap<>();
    /** entity type id -> its fixed category (derived from the bundle). */
    private static final Map<String, Cat> TYPE_CATEGORY = new HashMap<>();

    static {
        load();
    }

    private static void load() {
        try (InputStream in = VNaturalSpawner.class.getResourceAsStream("/vanilla/spawners.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject biomes = root.getAsJsonObject("biomes");
            for (var be : biomes.entrySet()) {
                Map<Cat, List<Entry>> perCat = new EnumMap<>(Cat.class);
                for (var ce : be.getValue().getAsJsonObject().entrySet()) {
                    Cat cat = catOf(ce.getKey());
                    if (cat == null) continue;
                    List<Entry> list = new ArrayList<>();
                    for (var el : ce.getValue().getAsJsonArray()) {
                        JsonObject o = el.getAsJsonObject();
                        String type = o.get("type").getAsString();
                        list.add(new Entry(type, o.get("weight").getAsInt(), o.get("min").getAsInt(), o.get("max").getAsInt()));
                        TYPE_CATEGORY.putIfAbsent(type, cat);
                    }
                    perCat.put(cat, list);
                }
                SPAWNERS.put(be.getKey(), perCat);
            }
        } catch (Exception e) {
            throw new IllegalStateException("spawners.json", e);
        }
    }

    private static Cat catOf(String name) {
        return switch (name) {
            case "monster" -> Cat.MONSTER;
            case "creature" -> Cat.CREATURE;
            case "ambient" -> Cat.AMBIENT;
            case "axolotls" -> Cat.AXOLOTLS;
            case "underground_water_creature" -> Cat.UNDERGROUND_WATER_CREATURE;
            case "water_creature" -> Cat.WATER_CREATURE;
            case "water_ambient" -> Cat.WATER_AMBIENT;
            default -> null; // "misc" and unknown: never natural-spawned
        };
    }

    // ---- roster we can actually build (Mobs.spawn) ----
    private static final Set<String> BUILDABLE = Set.of(
            // monsters
            "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:spider",
            "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager", "minecraft:stray",
            "minecraft:bogged", "minecraft:slime", "minecraft:sulfur_cube", "minecraft:cave_spider",
            "minecraft:zombified_piglin", "minecraft:magma_cube", "minecraft:enderman",
            "minecraft:piglin", "minecraft:hoglin",
            // creatures
            "minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken",
            "minecraft:mooshroom", "minecraft:rabbit", "minecraft:goat", "minecraft:horse",
            "minecraft:donkey", "minecraft:llama", "minecraft:turtle", "minecraft:panda",
            "minecraft:polar_bear", "minecraft:armadillo", "minecraft:camel", "minecraft:fox", "minecraft:frog",
            // water
            "minecraft:squid", "minecraft:glow_squid", "minecraft:cod", "minecraft:salmon",
            "minecraft:pufferfish", "minecraft:tropical_fish", "minecraft:dolphin", "minecraft:axolotl",
            "minecraft:nautilus",
            // other
            "minecraft:parched", "minecraft:bat", "minecraft:wolf", "minecraft:parrot", "minecraft:zombie_horse",
            "minecraft:strider", "minecraft:witch", "minecraft:ghast", "minecraft:ocelot",
            // nether fortress
            "minecraft:blaze", "minecraft:wither_skeleton");

    private static Placement placementOf(String type) {
        if (type.equals("minecraft:strider")) return Placement.IN_LAVA;   // lava-strider
        if (type.equals("minecraft:ghast")) return Placement.NO_RESTRICTIONS; // flying, spawns in air
        Cat c = TYPE_CATEGORY.get(type);
        if (c == Cat.WATER_CREATURE || c == Cat.WATER_AMBIENT
                || c == Cat.UNDERGROUND_WATER_CREATURE || c == Cat.AXOLOTLS) {
            return Placement.IN_WATER;
        }
        return Placement.ON_GROUND;
    }

    // ============================================================ per-instance driver

    private final Instance instance;
    /** biome lookup by block coords (server-side worldgen biomes). */
    private final BiomeAt biomeAt;
    /** dimensions with no sky (End) are always dark for monster spawning. */
    private final boolean noSkyLight;

    public interface BiomeAt { String biome(int x, int y, int z); }

    public VNaturalSpawner(Instance instance, BiomeAt biomeAt) {
        this(instance, biomeAt, false);
    }

    public VNaturalSpawner(Instance instance, BiomeAt biomeAt, boolean noSkyLight) {
        this.instance = instance;
        this.biomeAt = biomeAt;
        this.noSkyLight = noSkyLight;
    }

    /** Live mob-cap counters for the current tick, updated as mobs spawn. */
    private final EnumMap<Cat, AtomicInteger> counts = new EnumMap<>(Cat.class);

    // ============================================================ the spawn tick

    /**
     * One spawn tick. Phase 1 (parallel): decide spawns per chunk. Phase 2 (this
     * thread): materialise entities. Mirrors NaturalSpawner.spawnForChunk over the
     * spawnable-chunk set with the global mob-cap gate.
     */
    public void spawnTick(long worldTick, boolean parallel) {
        List<SpawnReq> requests = decide(worldTick, parallel);
        // materialise on this thread (entity add must be single-threaded)
        for (SpawnReq r : requests) {
            EntityCreature mob = Mobs.spawn(r.kind, instance, r.pos);
            if (mob != null) mob.setView((float) (ThreadLocalRandom.current().nextFloat() * 360f), 0f);
        }
    }

    /**
     * Phases 1-4: the embarrassingly-parallel spawn-decision phase. Returns the list
     * of spawns to materialise (does not touch entities), so it can run off the tick
     * thread or be benchmarked in isolation.
     */
    public List<SpawnReq> decide(long worldTick, boolean parallel) {
        // spawn_mobs gamerule (26.2's name for doMobSpawning): kills all natural spawning
        if (!dev.pointofpressure.minecom.GameRules.getBool("spawn_mobs")) return List.of();
        Set<Player> players = instance.getPlayers();
        if (players.isEmpty()) return List.of();

        // 1. spawnable chunks: within SPAWN_DISTANCE_CHUNK of any player, loaded, deduped
        List<long[]> spawnable = collectSpawnableChunks(players);
        int spawnableCount = spawnable.size();
        if (spawnableCount == 0) return List.of();

        // 2. count non-persistent mobs per category
        countMobs();

        // 3. which categories may spawn this tick (phase flags + global cap)
        boolean spawnEnemies = !dev.pointofpressure.minecom.Difficulty.isPeaceful() // Level.isSpawningMonsters
                && dev.pointofpressure.minecom.GameRules.getBool("spawn_monsters");
        boolean spawnFriendlies = worldTick % 400L == 0L; // creatures spawn every 400 ticks (like vanilla)
        List<Cat> categories = new ArrayList<>();
        for (Cat c : Cat.values()) {
            if (c == Cat.MISC) continue;
            if ((spawnFriendlies || !c.friendly) && (spawnEnemies || c.friendly)
                    && belowGlobalCap(c, spawnableCount)) {
                categories.add(c);
            }
        }
        if (categories.isEmpty()) return List.of();

        // 4. per-chunk spawn decisions (independent per chunk -> parallelisable)
        List<SpawnReq> requests = new ArrayList<>();
        if (parallel) {
            List<SpawnReq> synced = java.util.Collections.synchronizedList(requests);
            spawnable.parallelStream().forEach(ck ->
                    spawnForChunk((int) ck[0], (int) ck[1], categories, spawnableCount, synced));
        } else {
            for (long[] ck : spawnable) spawnForChunk((int) ck[0], (int) ck[1], categories, spawnableCount, requests);
        }
        return requests;
    }

    /** Public spawn request (kind, pos) — materialised by {@link #spawnTick}. */
    public record SpawnReq(String kind, Pos pos) {}

    // ---- spawnable chunk set ----

    /** Current spawnable-chunk count (for benchmarking/diagnostics). */
    public int spawnableChunkCount() {
        Set<Player> players = instance.getPlayers();
        return players.isEmpty() ? 0 : collectSpawnableChunks(players).size();
    }

    private List<long[]> collectSpawnableChunks(Set<Player> players) {
        // dedup via a set of packed chunk keys; only loaded chunks count (vanilla: entity-ticking chunks)
        Map<Long, long[]> out = new HashMap<>();
        for (Player p : players) {
            int pcx = p.getPosition().chunkX();
            int pcz = p.getPosition().chunkZ();
            for (int dz = -SPAWN_DISTANCE_CHUNK; dz <= SPAWN_DISTANCE_CHUNK; dz++) {
                for (int dx = -SPAWN_DISTANCE_CHUNK; dx <= SPAWN_DISTANCE_CHUNK; dx++) {
                    int cx = pcx + dx, cz = pcz + dz;
                    if (!instance.isChunkLoaded(cx, cz)) continue;
                    out.putIfAbsent(pack(cx, cz), new long[]{cx, cz});
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private void countMobs() {
        for (Cat c : Cat.values()) counts.computeIfAbsent(c, k -> new AtomicInteger()).set(0);
        for (Entity e : instance.getEntities()) {
            if (!(e instanceof EntityCreature mob) || mob.isDead()) continue;
            Cat c = TYPE_CATEGORY.getOrDefault(e.getEntityType().key().asString(), null);
            if (c == null || c == Cat.MISC) continue;
            counts.get(c).incrementAndGet();
        }
    }

    private boolean belowGlobalCap(Cat c, int spawnableCount) {
        int cap = c.max * spawnableCount / MAGIC_NUMBER;
        return counts.getOrDefault(c, ZERO).get() < cap;
    }

    private static final AtomicInteger ZERO = new AtomicInteger();

    // ---- spawnForChunk / spawnCategoryForChunk / ...ForPosition ----

    private void spawnForChunk(int cx, int cz, List<Cat> categories, int spawnableCount, List<SpawnReq> out) {
        for (Cat c : categories) {
            if (!belowGlobalCap(c, spawnableCount)) continue; // live re-check
            spawnCategoryForChunk(c, cx, cz, spawnableCount, out);
        }
    }

    private void spawnCategoryForChunk(Cat cat, int cx, int cz, int spawnableCount, List<SpawnReq> out) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int minBx = cx << 4, minBz = cz << 4;
        int x = minBx + rng.nextInt(16);
        int z = minBz + rng.nextInt(16);
        int top = worldSurfaceHeight(x, z) + 1;                 // WORLD_SURFACE height +1
        int minY = -64;
        int startY = randomBetweenInclusive(rng, minY, top);
        if (startY < minY + 1) return;
        spawnCategoryForPosition(cat, x, startY, z, cx, cz, spawnableCount, out);
    }

    private void spawnCategoryForPosition(Cat cat, int sx, int sy, int sz, int cx, int cz,
                                          int spawnableCount, List<SpawnReq> out) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Block startBlock = instance.getBlock(sx, sy, sz);
        if (isRedstoneConductor(startBlock)) return;

        int clusterSize = 0;
        for (int group = 0; group < 3; group++) {
            int x = sx, z = sz;
            Entry current = null;
            int max = (int) Math.ceil(rng.nextFloat() * 4.0F);
            int groupSize = 0;

            for (int ll = 0; ll < max; ll++) {
                x += rng.nextInt(6) - rng.nextInt(6);
                z += rng.nextInt(6) - rng.nextInt(6);
                // this cluster-drift walk can wander ~20 blocks from the starting chunk
                // (mirrors real vanilla's NaturalSpawner, which never needs this guard
                // because production chunk loading always keeps well ahead of it — see
                // docs/AUDIT.md's Portals.tryLight precedent for this project's own
                // established fix for that same class of gap); one guard here protects
                // every downstream check (weightedPick/isValidSpawnPositionForType/
                // isSpawnPositionOk/checkSpawnRules) instead of hunting each separately.
                if (!instance.isChunkLoaded(x >> 4, z >> 4)) continue;
                double xx = x + 0.5, zz = z + 0.5;
                Player near = nearestPlayer(xx, sy, zz);
                if (near == null) continue;
                double d2 = distSqr(near, xx, sy, zz);
                if (!isRightDistanceToPlayer(cx, cz, x, z, d2)) continue;

                if (current == null) {
                    current = weightedPick(cat, x, sy, z, rng);
                    if (current == null) break;                 // no spawns for this category here
                    max = current.min + rng.nextInt(1 + current.max - current.min);
                }

                if (!isValidSpawnPositionForType(cat, current, x, sy, z, d2)) continue;
                if (!belowGlobalCap(cat, spawnableCount)) return;

                String kind = BUILDABLE.contains(current.type) ? current.type.substring("minecraft:".length()) : null;
                if (kind == null) continue;                     // we can't build this mob: skip (deviation: vanilla would spawn it)

                out.add(new SpawnReq(kind, new Pos(xx, sy, zz)));
                counts.get(cat).incrementAndGet();
                clusterSize++;
                groupSize++;
                if (clusterSize >= maxSpawnClusterSize(current.type)) return;
                if (groupSize >= current.max) break;            // isMaxGroupSizeReached (approx: pack maxCount)
            }
        }
    }

    // ---- distance / validity gates ----

    private boolean isRightDistanceToPlayer(int cx, int cz, int x, int z, double d2) {
        if (d2 <= 576.0) return false;                          // MIN_SPAWN_DISTANCE^2 = 24^2
        // respawn-point proximity omitted (no world-spawn structure gate at runtime)
        return true;                                            // chunk is already known spawnable
    }

    private boolean isValidSpawnPositionForType(Cat cat, Entry e, int x, int y, int z, double d2) {
        Cat typeCat = TYPE_CATEGORY.getOrDefault(e.type, cat);
        if (typeCat == Cat.MISC) return false;
        // canSpawnFarFromPlayer: false for our mobs -> too far means invalid
        int dd = typeCat.despawnDistance;
        if (d2 > (double) dd * dd) return false;
        if (!isSpawnPositionOk(placementOf(e.type), x, y, z)) return false;
        if (!checkSpawnRules(e.type, x, y, z)) return false;
        return noCollision(x, y, z);
    }

    // ---- SpawnPlacementTypes.isSpawnPositionOk ----

    private boolean isSpawnPositionOk(Placement placement, int x, int y, int z) {
        return switch (placement) {
            case ON_GROUND -> {
                Block below = instance.getBlock(x, y - 1, z);
                if (!isValidSpawnGround(below)) yield false;
                yield isValidEmptySpawnBlock(x, y, z) && isValidEmptySpawnBlock(x, y + 1, z);
            }
            case IN_WATER -> instance.getBlock(x, y, z).compare(Block.WATER)
                    && !isRedstoneConductor(instance.getBlock(x, y + 1, z));
            case IN_LAVA -> instance.getBlock(x, y, z).compare(Block.LAVA);
            case NO_RESTRICTIONS -> true;
        };
    }

    /** BlockState.isValidSpawn: below must be a solid, non-leaf top surface (grass/stone/etc). */
    private boolean isValidSpawnGround(Block below) {
        if (below == null || below.isAir() || below.isLiquid()) return false;
        String n = below.key().asString();
        if (n.contains("leaves") || n.equals("minecraft:barrier") || n.contains("bedrock_")) return false;
        return below.isSolid();
    }

    /** NaturalSpawner.isValidEmptySpawnBlock: no full collision, no signal, no fluid, not dangerous. */
    private boolean isValidEmptySpawnBlock(int x, int y, int z) {
        Block b = instance.getBlock(x, y, z);
        if (b == null) return true;
        if (isFullCube(b)) return false;
        if (b.isLiquid()) return false;
        String n = b.key().asString();
        if (n.contains("fire") || n.equals("minecraft:cactus") || n.equals("minecraft:magma_block")
                || n.contains("campfire") || n.contains("sweet_berry")) return false;
        return true;
    }

    // ---- per-mob spawn-rule predicates (Monster / Animal checkSpawnRules) ----

    /** Reused by {@link dev.pointofpressure.minecom.blocks.ClassicSpawners} for BaseSpawner's identical mob.checkSpawnRules gate. */
    public boolean checkSpawnRules(String type, int x, int y, int z) {
        if (type.equals("minecraft:strider")) return true;                 // on lava, no grass/light gate
        if (type.equals("minecraft:ocelot")) return skyLightAt(x, y, z) > 8; // jungle, daytime (monster cap but day rule)
        Cat c = TYPE_CATEGORY.getOrDefault(type, null);
        if (c == Cat.MONSTER) return checkMonsterSpawnRules(x, y, z);
        if (c == Cat.CREATURE) return checkAnimalSpawnRules(x, y, z);
        return true;
    }

    /** Monster.checkMonsterSpawnRules: not peaceful (we assume normal) + isDarkEnoughToSpawn. */
    public boolean checkMonsterSpawnRules(int x, int y, int z) {
        return isDarkEnoughToSpawn(x, y, z);
    }

    /** Animal.checkAnimalSpawnRules: block below in #animals_spawnable_on (grass_block) + bright (light>8). */
    private boolean checkAnimalSpawnRules(int x, int y, int z) {
        Block below = instance.getBlock(x, y - 1, z);
        boolean grass = below != null && below.compare(Block.GRASS_BLOCK);
        return grass && skyLightAt(x, y, z) > 8;
    }

    /**
     * Monster.isDarkEnoughToSpawn (behavioural light model). Gate 1 uses the raw sky
     * LAYER (15 if sky-exposed) — culls ~half of open positions like vanilla. The
     * final gate uses TIME-ADJUSTED brightness (≈0 at night, 15 by day) so hostiles
     * spawn freely underground, at a reduced rate on the night surface, never by day.
     */
    private boolean isDarkEnoughToSpawn(int x, int y, int z) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int skyLayer = skyLightRawAt(x, y, z);
        if (skyLayer > rng.nextInt(32)) return false;           // getBrightness(SKY) gate
        int block = instance.getBlockLight(x, y, z);            // real Minestom-computed block light (torches etc.)
        if (block > 0) return false;
        int brightness = Math.max(skyLightAt(x, y, z), block);  // getMaxLocalRawBrightness (time-adjusted)
        return brightness <= rng.nextInt(8);                    // overworld monsterSpawnLightTest uniform[0,7]
    }

    // ---- collision ----

    /** Reused by {@link dev.pointofpressure.minecom.blocks.ClassicSpawners} for BaseSpawner's identical level.noCollision gate. */
    public boolean noCollision(int x, int y, int z) {
        // mob AABB spans y..y+1 (most land mobs ~2 blocks tall); require both non-full
        return !isFullCube(instance.getBlock(x, y, z)) && !isFullCube(instance.getBlock(x, y + 1, z));
    }

    // ---- light model ----

    /** Sky light layer value (15 if sky-exposed else 0), independent of time. */
    private int skyLightRawAt(int x, int y, int z) {
        if (noSkyLight) return 0;
        return canSeeSky(x, y, z) ? 15 : 0;
    }

    /** Time-adjusted sky light (used for the animal brightness gate). */
    private int skyLightAt(int x, int y, int z) {
        if (noSkyLight || !canSeeSky(x, y, z)) return 0;
        return isNight() ? 4 : 15;
    }

    private boolean canSeeSky(int x, int y, int z) {
        return y >= worldSurfaceHeight(x, z);
    }

    private boolean isNight() {
        long t = Math.floorMod(instance.getTime(), 24000L);
        return t > 13000 && t < 23000;
    }

    // ---- world-surface heightmap ----

    private int worldSurfaceHeight(int x, int z) {
        var chunk = instance.getChunk(x >> 4, z >> 4);
        if (chunk == null) return scanSurface(x, z);
        try {
            int h = chunk.worldSurfaceHeightmap().getHeight(x & 15, z & 15);
            if (h > -64) return h;
        } catch (Exception ignored) { }
        return scanSurface(x, z);
    }

    private int scanSurface(int x, int z) {
        for (int y = 319; y > -64; y--) {
            Block b = instance.getBlock(x, y, z);
            if (b != null && !b.isAir()) return y + 1;
        }
        return -63;
    }

    // ---- block classification ----

    private static boolean isRedstoneConductor(Block b) {
        return b != null && !b.isAir() && b.isSolid() && !b.isLiquid();
    }

    private static boolean isFullCube(Block b) {
        if (b == null || b.isAir()) return false;
        var reg = b.registry();
        if (reg == null) return b.isSolid();
        var shape = reg.collisionShape();
        return shape != null && shape.relativeStart().isZero()
                && shape.relativeEnd().x() == 1 && shape.relativeEnd().y() == 1 && shape.relativeEnd().z() == 1;
    }

    // ---- weighted biome pick ----

    /** NetherFortressStructure.FORTRESS_ENEMIES — monsters that spawn on nether brick. */
    private static final List<Entry> FORTRESS_ENEMIES = List.of(
            new Entry("minecraft:blaze", 10, 2, 3),
            new Entry("minecraft:zombified_piglin", 5, 4, 4),
            new Entry("minecraft:wither_skeleton", 8, 5, 5),
            new Entry("minecraft:skeleton", 2, 5, 5),
            new Entry("minecraft:magma_cube", 3, 4, 4));

    private Entry weightedPick(Cat cat, int x, int y, int z, ThreadLocalRandom rng) {
        // nether-fortress rule: monsters over nether brick draw from the fortress list.
        // spawnCategoryForPosition's own cluster-drift loop can walk the candidate
        // position up to ~20 blocks from its starting chunk (mirrors real vanilla's
        // NaturalSpawner, which never needs this guard because production chunk
        // loading always keeps well ahead of it — see docs/AUDIT.md's Portals.tryLight
        // precedent for this project's own established fix for that same class of gap).
        if (cat == Cat.MONSTER && instance.isChunkLoaded(x >> 4, z >> 4)) {
            Block below = instance.getBlock(x, y - 1, z);
            if (below != null && below.key().value().equals("nether_bricks")) {
                return pick(FORTRESS_ENEMIES, rng);
            }
        }
        String biome = biomeAt.biome(x, y, z);
        Map<Cat, List<Entry>> perCat = SPAWNERS.get(biome);
        if (perCat == null) return null;
        return pick(perCat.get(cat), rng);
    }

    private static Entry pick(List<Entry> list, ThreadLocalRandom rng) {
        if (list == null || list.isEmpty()) return null;
        int total = 0;
        for (Entry e : list) total += e.weight;
        if (total <= 0) return null;
        int roll = rng.nextInt(total);
        for (Entry e : list) {
            roll -= e.weight;
            if (roll < 0) return e;
        }
        return null;
    }

    // ---- misc vanilla helpers ----

    private static int maxSpawnClusterSize(String type) {
        return 4; // Mob.getMaxSpawnClusterSize default
    }

    private Player nearestPlayer(double x, double y, double z) {
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : instance.getPlayers()) {
            double d = distSqr(p, x, y, z);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private static double distSqr(Entity e, double x, double y, double z) {
        Point p = e.getPosition();
        double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int randomBetweenInclusive(ThreadLocalRandom r, int min, int max) {
        return r.nextInt(max - min + 1) + min;
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // ============================================================ despawn

    /** Mob.checkDespawn for every creature: 128-block instant, 32-128 random chance. */
    public void despawnTick() {
        Set<Player> players = instance.getPlayers();
        boolean peaceful = dev.pointofpressure.minecom.Difficulty.isPeaceful();
        for (Entity e : instance.getEntities()) {
            if (!(e instanceof EntityCreature mob) || mob.isDead()) continue;
            // TamableAnimal / AbstractHorse: a tame pet or mount never despawns and is
            // never a Peaceful-mode casualty, regardless of category — checked first
            // since untamed cats have no TYPE_CATEGORY entry at all (they only ever
            // spawn manually; real vanilla spawns them from villages, which this
            // project doesn't generate) and would otherwise wrongly fall into the
            // Cat.MONSTER default below.
            if (Taming.isTamed(mob) || Riding.isTamed(mob) || NameTags.isPersistent(mob)) continue;
            Cat c = TYPE_CATEGORY.getOrDefault(e.getEntityType().key().asString(), Cat.MONSTER);
            // Mob.checkDespawn: hostiles discard instantly on Peaceful
            if (peaceful && !c.friendly) { mob.remove(); continue; }
            if (c.persistent) continue; // creatures persist? no — CREATURE.persistent=true handled by removeWhenFarAway
            Player near = null;
            double d2 = Double.MAX_VALUE;
            for (Player p : players) {
                double d = distSqr(p, mob.getPosition().x(), mob.getPosition().y(), mob.getPosition().z());
                if (d < d2) { d2 = d; near = p; }
            }
            if (near == null) continue;
            int dd = c.despawnDistance;
            if (d2 > (double) dd * dd) { mob.remove(); continue; }
            int nd = Cat.NO_DESPAWN_DISTANCE;
            if (d2 > (double) nd * nd && ThreadLocalRandom.current().nextInt(800) == 0) {
                mob.remove();
            }
        }
    }

    // expose for tests
    static int globalCap(Cat c, int spawnableChunks) { return c.max * spawnableChunks / MAGIC_NUMBER; }

    // ============================================================ test hooks (SelfTest)

    public static int testCap(String category, int spawnableChunks) {
        return globalCap(catOf(category), spawnableChunks);
    }

    public static String testCategoryOf(String type) {
        Cat c = TYPE_CATEGORY.get(type);
        return c == null ? null : c.name();
    }

    public static int testBiomeCount() { return SPAWNERS.size(); }

    /** true if blaze is a fortress-enemy pick (drives brewing progression). */
    public static boolean testFortressHasBlaze() {
        return FORTRESS_ENEMIES.stream().anyMatch(e -> e.type.equals("minecraft:blaze"));
    }

    /** [buildable, total] distinct spawn types across all natural-spawn categories. */
    public static int[] testRosterCoverage() {
        Set<String> all = new java.util.HashSet<>();
        for (var perCat : SPAWNERS.values()) {
            for (var e : perCat.entrySet()) {
                if (e.getKey() == Cat.MISC) continue;
                for (Entry en : e.getValue()) all.add(en.type);
            }
        }
        int have = 0;
        for (String t : all) if (BUILDABLE.contains(t)) have++;
        return new int[]{have, all.size()};
    }

    /** total weight of a category's spawn list in a biome (0 if none). */
    public static int testWeightSum(String biome, String category) {
        Map<Cat, List<Entry>> perCat = SPAWNERS.get(biome);
        if (perCat == null) return 0;
        List<Entry> l = perCat.get(catOf(category));
        if (l == null) return 0;
        int t = 0;
        for (Entry e : l) t += e.weight;
        return t;
    }

    /** empirical weighted-pick frequency of `type` over n draws (order-independent RNG). */
    public static double testPickFrequency(String biome, String category, String type, int n) {
        Map<Cat, List<Entry>> perCat = SPAWNERS.get(biome);
        if (perCat == null) return 0;
        List<Entry> list = perCat.get(catOf(category));
        if (list == null || list.isEmpty()) return 0;
        int total = 0;
        for (Entry e : list) total += e.weight;
        int hits = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            int roll = rng.nextInt(total);
            for (Entry e : list) {
                roll -= e.weight;
                if (roll < 0) { if (e.type.equals(type)) hits++; break; }
            }
        }
        return (double) hits / n;
    }
}
