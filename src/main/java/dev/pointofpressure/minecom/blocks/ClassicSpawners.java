package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Difficulty;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.StateAdapter;
import dev.pointofpressure.minecom.data.VanillaData;
import dev.pointofpressure.minecom.mobs.Mobs;
import dev.pointofpressure.minecom.mobs.VNaturalSpawner;
import dev.pointofpressure.minecom.survival.Experience;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockEntityDataPacket;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Classic {@code minecraft:spawner} block entities, decompile-verified against 26.2's
 * {@code BaseSpawner}/{@code SpawnerBlockEntity}/{@code SpawnData}/{@code SpawnerBlock}
 * (cached under {@code vanilla-src/net/minecraft/world/level/}). Unlike {@link TrialChambers}'
 * {@code trial_spawner} (which has real {@code trial_spawner_state}/{@code ominous} blockstate
 * properties), plain {@code minecraft:spawner} has NONE — confirmed against Minestom's own
 * block registry (zero properties, matching vanilla: spawner config lives entirely in
 * block-entity NBT). Every field this class needs (delay, spawn potentials, next-spawn-data)
 * therefore lives in this class's own position-keyed registry, the same shape as
 * {@code TrialChambers}' {@code SPAWNER_DEFS}/{@code SPAWNERS} maps but with no parallel
 * blockstate mirror to keep in sync.
 *
 * <p><b>Cycle</b> ({@link #tickSpawner}, {@code BaseSpawner.serverTick}): gated on a player
 * within {@code requiredPlayerRange} (default 16) AND the same "spawner block enabled" switch
 * real vanilla ties to the {@code doMobSpawning} gamerule — this project has no live gamerule
 * system, so it reuses the existing {@code config.naturalSpawns()} boot-time toggle a running
 * {@link VNaturalSpawner} already respects (see {@code Bootstrap}'s wiring). {@code spawnDelay}
 * of -1 (fresh placement) forces an immediate reroll; otherwise it counts down, and at 0 makes
 * up to {@code spawnCount} (default 4) attempts with the SAME picked {@code SpawnData} entry
 * (real vanilla picks once per burst, not per attempt): resolve the entity type (unknown id is
 * a hard fail — reroll + abort the whole burst), roll a position within {@code spawnRange}
 * (default 4, or the entry's own NBT {@code Pos} if present), a collision check, a per-mob
 * spawn-rule check (light level — real vanilla's {@code BaseSpawner} has NO ground-solidity
 * requirement for spawner-placed mobs, only {@code Monster.isDarkEnoughToSpawn}, confirmed from
 * the decompile), and a live nearby-same-type-entity re-count against {@code maxNearbyEntities}
 * (default 6) — a hard fail if over. Any success bursts a particle event and rerolls the delay;
 * an entirely-failed burst (every attempt a soft fail) retries next tick with no reroll, exactly
 * like real vanilla. The light-level and collision predicates are REUSED from
 * {@link VNaturalSpawner#checkMonsterSpawnRules} / {@link VNaturalSpawner#noCollision} (made
 * public on that class for this reuse) rather than forked — same underlying "is this hostile
 * mob's spot valid" logic natural spawning already verified.
 *
 * <p><b>Client sync</b>: a {@link BlockEntityDataPacket} (minimal Delay/SpawnData NBT) is sent
 * whenever the picked entry changes, plus a {@link WorldEventPacket} effect 1 (the real
 * {@code Blocks.SPAWNER} block-event id, {@code BaseSpawner.EVENT_SPAWN}) — together these are
 * what a real vanilla client needs to reset and locally re-drive its own {@code clientTick}
 * spin/particle animation, matching {@code SpawnerBlockEntity}'s
 * {@code setNextSpawnData}+{@code broadcastEvent} pair. A world-event 2004 (smoke+flame burst)
 * fires per successful spawn, matching {@code level.levelEvent(2004, pos, 0)}.
 *
 * <p><b>Breaking</b> ({@link #onBreak}): {@code SpawnerBlock} has no loot table in either real
 * vanilla or this project's bundled data (confirmed: no {@code minecraft:spawner} entry
 * anywhere in {@code loot_blocks.json}/Minestom's own registry) — Silk Touch changes nothing,
 * the block itself is never obtainable by breaking. {@code SpawnerBlock.spawnAfterBreak} DOES
 * unconditionally award {@code 15 + rand(15) + rand(15)} (15-43) XP whenever a non-creative
 * player breaks it, regardless of tool — reproduced directly; creative breaks award none,
 * matching {@code spawnAfterBreak}'s real caller passing {@code dropExperience=false} there.
 *
 * <p><b>Placement integration points</b> — {@link #registerSpawner} is called with the exact
 * fixed mob id real vanilla's own {@code SpawnerBlockEntity.setEntityId(EntityType, RandomSource)}
 * call sites use, from: {@code VStructureManager}'s mineshaft spider corridor (cave_spider),
 * {@code VStrongholdGen}'s portal-room spawner (silverfish), and {@code NetherGen}'s fortress
 * platform (blaze) — see each call site's own javadoc for the decompiled source citation.
 * {@link #registerTemplateBlockEntity} is the generic NBT-driven path (mirroring
 * {@code TrialChambers.registerTemplateBlockEntity}) for any FUTURE structure whose {@code .nbt}
 * template embeds a {@code minecraft:spawner} block with real {@code SpawnData}/
 * {@code SpawnPotentials} tags — none of this project's structures currently place spawners via
 * template (all four integration points above are procedural placements, matching real vanilla:
 * dungeons/mineshafts/fortresses/strongholds are NOT jigsaw/template structures either), but the
 * hook is wired into {@code VStructureGen} now so it's free the day one needs it — "reuse, don't
 * fork" per the trial-spawner registry this class's shape mirrors.
 *
 * <p><b>Persistence</b>: the DEFINITION (position + spawn potentials) IS persisted, for the
 * exact reason {@code TrialChambers}' own persistence javadoc documents — this project's
 * production config uses a real AnvilLoader, so an already-visited chunk restores its saved
 * (property-less) spawner block directly on restart, meaning the structure-placement call sites
 * above never fire again for it; without persisting the definition, an already-placed spawner
 * would go permanently inert after any restart. Runtime progress ({@code spawnDelay}/
 * {@code nextSpawnData}) is NOT persisted — an "in-flight state, acceptable loss" simplification
 * matching {@code TrialChambers}' own {@code currentMobs} precedent (a restored spawner just
 * starts as freshly placed, {@code spawnDelay=-1}, functionally harmless: worst case a ~40s
 * animation reset).
 *
 * <p><b>Deliberate simplifications</b> (AUDIT.md): {@code SpawnData} {@code equipment} is parsed
 * but never applied — no generic mob-equip-from-loot-table wiring exists anywhere in this
 * codebase yet, matching {@code TrialChambers}' identical gap (see its own javadoc). Peaceful
 * difficulty unconditionally soft-fails every spawn attempt (real vanilla only gates hostile
 * {@code SpawnData} this way; every spawner this project currently wires — cave_spider,
 * silverfish, blaze — is hostile-only, so this is behavior-equivalent today; a future friendly-
 * mob classic spawner would need the same per-entry category check real vanilla's
 * {@code custom_spawn_rules} branch has). Creative "pick block" is unimplemented — not a
 * spawner-specific gap, {@code PlayerPickBlockEvent} is unclaimed for every block in this
 * project.
 */
public final class ClassicSpawners {
    private ClassicSpawners() {}

    private static final int EVENT_SPAWN = 1; // BaseSpawner.EVENT_SPAWN / Blocks.SPAWNER block-event id

    private record SpawnEntry(String entityId, int weight, CompoundBinaryTag entityNbt) {}

    private static final class Data {
        final List<SpawnEntry> spawnPotentials = new ArrayList<>();
        SpawnEntry nextSpawnData;
        int spawnDelay = -1;
        int minSpawnDelay = 200;
        int maxSpawnDelay = 800;
        int spawnCount = 4;
        int maxNearbyEntities = 6;
        int requiredPlayerRange = 16;
        int spawnRange = 4;
    }

    // Position-keyed per-Instance: fortress blaze spawners live in the Nether, mineshaft/
    // stronghold spawners in the Overworld — two dimensions can share the same raw x,y,z, so the
    // registry is scoped per Instance (unlike TrialChambers, which is Overworld-only by design).
    private static final Map<Instance, Map<Long, Data>> SPAWNERS = new ConcurrentHashMap<>();
    private static final Map<Instance, VNaturalSpawner> RULES = new ConcurrentHashMap<>();
    private static boolean started;

    // Worldgen classes (VStructureManager, VStrongholdGen, NetherGen) run on generator threads
    // with only chunk coordinates, no live Instance reference — matching how TrialChambers'
    // registerTemplateBlockEntity needs none either. These are set once from Bootstrap (the one
    // place that DOES have both Instance references) so generation-time call sites can use the
    // dimension-less convenience overloads below instead of threading an Instance through every
    // structure generator's call chain.
    private static Instance overworldInstance;
    private static Instance netherInstance;

    /** Called once from Bootstrap; wires the shared ticker/listener/persistence exactly once regardless of dimension count. */
    public static void start(GlobalEventHandler events) {
        if (started) return;
        started = true;
        MinecraftServer.getSchedulerManager().buildTask(ClassicSpawners::tick)
                .repeat(TaskSchedule.tick(1)).schedule();
        events.addListener(PlayerBlockBreakEvent.class, ClassicSpawners::onBreak);
        Persist.register(persistence());
    }

    /**
     * Registers a dimension for classic-spawner ticking, reusing that dimension's already-built
     * {@link VNaturalSpawner} (same instance {@code Mobs.startVanillaSpawner} returns) for the
     * light/collision predicates — see class javadoc. Call once per dimension from Bootstrap,
     * inside the same {@code config.naturalSpawns()} gate real vanilla ties both systems to.
     */
    public static void registerInstance(Instance instance, VNaturalSpawner naturalSpawnRules) {
        RULES.put(instance, naturalSpawnRules);
        SPAWNERS.computeIfAbsent(instance, k -> new ConcurrentHashMap<>());
    }

    /**
     * Test hook / structure-placement hook: single-mob-type registration, matching real
     * vanilla's {@code SpawnerBlockEntity.setEntityId(EntityType, RandomSource)} — every one of
     * this project's four integration points uses this, never raw NBT (matching real vanilla:
     * dungeons/mineshaft/fortress/stronghold all call {@code setEntityId} too, never author
     * hand-written SpawnData NBT).
     */
    public static void registerSpawner(Instance instance, int x, int y, int z, String entityId) {
        Data d = new Data();
        d.spawnPotentials.add(new SpawnEntry(entityId, 1, null));
        SPAWNERS.computeIfAbsent(instance, k -> new ConcurrentHashMap<>()).put(pack(x, y, z), d);
    }

    /** Test hook: like {@link #registerSpawner} but with explicit min/max spawn delay overrides, for fast deterministic PlayTest coverage (production's default 200-800 tick range is real vanilla, but a 40s worst case is impractical to wait out per check). */
    public static void registerSpawnerForTest(Instance instance, int x, int y, int z, String entityId, int minDelay, int maxDelay) {
        Data d = new Data();
        d.spawnPotentials.add(new SpawnEntry(entityId, 1, null));
        d.minSpawnDelay = minDelay;
        d.maxSpawnDelay = maxDelay;
        SPAWNERS.computeIfAbsent(instance, k -> new ConcurrentHashMap<>()).put(pack(x, y, z), d);
    }

    /** Test hook: whether a spawner is currently registered at this position (definition presence, not runtime progress). */
    public static boolean testHasSpawner(Instance instance, int x, int y, int z) {
        return SPAWNERS.getOrDefault(instance, Map.of()).containsKey(pack(x, y, z));
    }

    /** Marks which registered {@link #registerInstance} dimension is the Overworld/Nether, for the two convenience overloads below. */
    public static void designateDimensions(Instance overworld, Instance nether) {
        overworldInstance = overworld;
        netherInstance = nether;
    }

    /** Overworld convenience for generation-time callers with no Instance reference (VStructureManager's mineshaft, VStrongholdGen's portal room). No-ops if natural spawns are disabled this run (matches natural spawning's own gate — see class javadoc). */
    public static void registerSpawnerOverworld(int x, int y, int z, String entityId) {
        if (overworldInstance != null) registerSpawner(overworldInstance, x, y, z, entityId);
    }

    /** Nether convenience for generation-time callers with no Instance reference (NetherGen's fortress). No-ops if natural spawns are disabled this run. */
    public static void registerSpawnerNether(int x, int y, int z, String entityId) {
        if (netherInstance != null) registerSpawner(netherInstance, x, y, z, entityId);
    }

    /**
     * Template-NBT driven registration (VStructureGen hook), mirroring
     * {@code TrialChambers.registerTemplateBlockEntity} — {@code BaseSpawner.load}, ported: reads
     * {@code SpawnData}/{@code SpawnPotentials} (falling back to a single-entry list built from
     * {@code SpawnData} alone, exactly like {@code BaseSpawner.load}'s
     * {@code WeightedList.of(nextSpawnData)} fallback) plus the Min/MaxSpawnDelay/SpawnCount/
     * MaxNearbyEntities/RequiredPlayerRange/SpawnRange shorts. An entry's {@code entity} compound
     * with more than just {@code id} is kept as custom entity NBT (not yet applied at spawn time
     * — see class javadoc's deliberate-simplifications note on equipment; the same gap, health/
     * custom-name/etc. from this NBT is parsed and stored for a future increment but not
     * currently read back in {@link #attemptSpawn}). Overworld-only (see
     * {@link #registerSpawnerOverworld}) — every bundled {@code .nbt} template this project
     * places comes from {@code VStructureGen}, which (like {@code VStructureManager}) only ever
     * generates the Overworld.
     */
    public static void registerTemplateBlockEntity(int x, int y, int z, String blockName, CompoundBinaryTag nbt) {
        if (nbt == null || !blockName.equals("minecraft:spawner") || overworldInstance == null) return;
        Data d = new Data();
        CompoundBinaryTag spawnData = nbt.getCompound("SpawnData");
        List<CompoundBinaryTag> potentials = new ArrayList<>();
        BinaryTag rawList = nbt.get("SpawnPotentials");
        if (rawList instanceof ListBinaryTag list) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof CompoundBinaryTag c) potentials.add(c);
            }
        }
        if (potentials.isEmpty() && !spawnData.keySet().isEmpty()) {
            CompoundBinaryTag synthetic = CompoundBinaryTag.builder()
                    .put("weight", net.kyori.adventure.nbt.IntBinaryTag.intBinaryTag(1))
                    .put("data", spawnData).build();
            potentials.add(synthetic);
        }
        for (CompoundBinaryTag entry : potentials) {
            CompoundBinaryTag data = entry.contains("data") ? entry.getCompound("data") : entry;
            CompoundBinaryTag entity = data.getCompound("entity");
            String id = entity.getString("id", null);
            if (id == null) continue;
            int weight = entry.getInt("weight", 1);
            d.spawnPotentials.add(new SpawnEntry(id, weight, entity.size() > 1 ? entity : null));
        }
        if (d.spawnPotentials.isEmpty()) return;
        d.minSpawnDelay = nbt.getInt("MinSpawnDelay", 200);
        d.maxSpawnDelay = nbt.getInt("MaxSpawnDelay", 800);
        d.spawnCount = nbt.getInt("SpawnCount", 4);
        d.maxNearbyEntities = nbt.getInt("MaxNearbyEntities", 6);
        d.requiredPlayerRange = nbt.getInt("RequiredPlayerRange", 16);
        d.spawnRange = nbt.getInt("SpawnRange", 4);
        SPAWNERS.computeIfAbsent(overworldInstance, k -> new ConcurrentHashMap<>()).put(pack(x, y, z), d);
    }

    // ------------------------------------------------------------------ persistence

    /**
     * See class javadoc's Persistence section. {@code Persist} only ever calls {@code collect}/
     * {@code restore} with the Overworld instance (it's a single-dimension framework project-wide
     * — the same limitation {@code TrialChambers} documents as "known limit"), so scoping to
     * {@code in} here automatically and correctly persists only Overworld spawners (mineshaft/
     * stronghold); the Nether fortress spawner inherits that same pre-existing, project-wide gap,
     * not a new one.
     */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "classic_spawner";
            }

            @Override
            public void collect(Instance in, java.util.function.BiConsumer<Point, JsonObject> out) {
                SPAWNERS.getOrDefault(in, Map.of()).forEach((key, d) -> {
                    JsonObject o = new JsonObject();
                    JsonArray potentials = new JsonArray();
                    for (SpawnEntry e : d.spawnPotentials) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("id", e.entityId());
                        entry.addProperty("weight", e.weight());
                        potentials.add(entry);
                    }
                    o.add("spawnPotentials", potentials);
                    o.addProperty("minSpawnDelay", d.minSpawnDelay);
                    o.addProperty("maxSpawnDelay", d.maxSpawnDelay);
                    o.addProperty("spawnCount", d.spawnCount);
                    o.addProperty("maxNearbyEntities", d.maxNearbyEntities);
                    o.addProperty("requiredPlayerRange", d.requiredPlayerRange);
                    o.addProperty("spawnRange", d.spawnRange);
                    out.accept(unpack(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject o) {
                Data d = new Data();
                for (var el : o.getAsJsonArray("spawnPotentials")) {
                    JsonObject entry = el.getAsJsonObject();
                    d.spawnPotentials.add(new SpawnEntry(entry.get("id").getAsString(), entry.get("weight").getAsInt(), null));
                }
                if (d.spawnPotentials.isEmpty()) return;
                d.minSpawnDelay = o.get("minSpawnDelay").getAsInt();
                d.maxSpawnDelay = o.get("maxSpawnDelay").getAsInt();
                d.spawnCount = o.get("spawnCount").getAsInt();
                d.maxNearbyEntities = o.get("maxNearbyEntities").getAsInt();
                d.requiredPlayerRange = o.get("requiredPlayerRange").getAsInt();
                d.spawnRange = o.get("spawnRange").getAsInt();
                SPAWNERS.computeIfAbsent(in, k -> new ConcurrentHashMap<>())
                        .put(pack(pos.blockX(), pos.blockY(), pos.blockZ()), d);
            }

            @Override
            public void wipe() {
                // Scoped to the Overworld ONLY, matching collect/restore's actual scope above
                // (Persist is single-dimension project-wide — see the "known limit" note in
                // TrialChambers' own persistence javadoc). StateAdapter#wipe() takes no Instance
                // parameter, so a naive `SPAWNERS.values().forEach(Map::clear)` would ALSO drop
                // the Nether's registry — data this adapter's own collect/restore never touch —
                // with no way to recover it (a wipe/reload round-trip test on Overworld data
                // must not have unrelated side effects on the Nether's already-generated,
                // never-to-regenerate fortress spawner).
                if (overworldInstance != null) {
                    Map<Long, Data> m = SPAWNERS.get(overworldInstance);
                    if (m != null) m.clear();
                }
            }
        };
    }

    // ------------------------------------------------------------------ tick cycle

    private static void tick() {
        for (Map.Entry<Instance, Map<Long, Data>> dim : SPAWNERS.entrySet()) {
            Instance instance = dim.getKey();
            VNaturalSpawner rules = RULES.get(instance);
            for (Map.Entry<Long, Data> e : dim.getValue().entrySet()) {
                tickSpawner(instance, rules, e.getKey(), e.getValue());
            }
        }
    }

    private static void tickSpawner(Instance instance, VNaturalSpawner rules, long key, Data d) {
        Point pos = unpack(key);
        if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) return;
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("spawner")) {
            SPAWNERS.getOrDefault(instance, Map.of()).remove(key);
            return;
        }
        if (!isNearPlayer(instance, pos, d.requiredPlayerRange)) return;

        if (d.spawnDelay == -1) {
            reroll(instance, pos, d);
        }
        if (d.spawnDelay > 0) {
            d.spawnDelay--;
            return;
        }

        SpawnEntry data = d.nextSpawnData != null ? d.nextSpawnData : pickWeighted(d.spawnPotentials);
        d.nextSpawnData = data;
        boolean spawnedAny = false;
        for (int c = 0; c < d.spawnCount; c++) {
            AttemptResult result = attemptSpawn(instance, rules, pos, d, data);
            if (result == AttemptResult.HARD_FAIL) {
                reroll(instance, pos, d);
                return;
            }
            if (result == AttemptResult.SUCCESS) spawnedAny = true;
            // SOFT_FAIL and SUCCESS both continue to the next attempt, matching BaseSpawner's loop.
        }
        if (spawnedAny) reroll(instance, pos, d);
    }

    private enum AttemptResult { SUCCESS, SOFT_FAIL, HARD_FAIL }

    /** BaseSpawner.serverTick's per-attempt body, one iteration. */
    private static AttemptResult attemptSpawn(Instance instance, VNaturalSpawner rules, Point pos, Data d, SpawnEntry data) {
        EntityType type = EntityType.fromKey(data.entityId());
        if (type == null) return AttemptResult.HARD_FAIL; // entityType.isEmpty()

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double x = pos.blockX() + (rng.nextDouble() - rng.nextDouble()) * d.spawnRange + 0.5;
        double y = pos.blockY() + rng.nextInt(3) - 1;
        double z = pos.blockZ() + (rng.nextDouble() - rng.nextDouble()) * d.spawnRange + 0.5;
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);

        if (rules != null && !rules.noCollision(bx, by, bz)) return AttemptResult.SOFT_FAIL;

        // Peaceful gates hostile SpawnData only; every mob wired to this system today is hostile-only (see class javadoc).
        if (Difficulty.isPeaceful()) return AttemptResult.SOFT_FAIL;
        if (rules != null && !rules.checkSpawnRules(data.entityId(), bx, by, bz)) return AttemptResult.SOFT_FAIL;

        String kind = VanillaData.path(data.entityId());
        EntityCreature mob = Mobs.spawn(kind, instance, new Pos(x, y, z));
        if (mob == null) return AttemptResult.HARD_FAIL; // entity == null after load

        // BaseSpawner: new AABB(pos, pos+1).inflate(spawnRange) — bit-exact box, not an approximation.
        double minX = pos.blockX() - d.spawnRange, maxX = pos.blockX() + 1 + d.spawnRange;
        double minY = pos.blockY() - d.spawnRange, maxY = pos.blockY() + 1 + d.spawnRange;
        double minZ = pos.blockZ() - d.spawnRange, maxZ = pos.blockZ() + 1 + d.spawnRange;
        int nearBy = 0;
        for (Entity e : instance.getEntities()) {
            if (e.getEntityType() != type) continue;
            Pos p = e.getPosition();
            if (p.x() >= minX && p.x() <= maxX && p.y() >= minY && p.y() <= maxY && p.z() >= minZ && p.z() <= maxZ) nearBy++;
        }
        // real vanilla counts BEFORE adding the new entity to the world; Mobs.spawn already added
        // it by the time we can count, so the just-spawned mob is included here — offsetting the
        // threshold to `>` (instead of vanilla's pre-add `>=`) reproduces the same real cutoff.
        if (nearBy > d.maxNearbyEntities) {
            mob.remove();
            return AttemptResult.HARD_FAIL;
        }

        mob.setView(rng.nextFloat() * 360.0F, 0.0F);
        instance.sendGroupedPacket(new WorldEventPacket(2004, pos, 0, false));
        return AttemptResult.SUCCESS;
    }

    /** BaseSpawner.delay: reroll delay + pick the next SpawnData, and sync the client. */
    private static void reroll(Instance instance, Point pos, Data d) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        d.spawnDelay = d.maxSpawnDelay <= d.minSpawnDelay
                ? d.minSpawnDelay
                : d.minSpawnDelay + rng.nextInt(d.maxSpawnDelay - d.minSpawnDelay);
        d.nextSpawnData = pickWeighted(d.spawnPotentials);
        syncClient(instance, pos, d);
        instance.sendGroupedPacket(new WorldEventPacket(EVENT_SPAWN, pos, 0, false));
    }

    /** SpawnerBlockEntity's anonymous BaseSpawner.setNextSpawnData override: level.sendBlockUpdated. */
    private static void syncClient(Instance instance, Point pos, Data d) {
        CompoundBinaryTag.Builder entity = CompoundBinaryTag.builder();
        if (d.nextSpawnData != null) entity.putString("id", d.nextSpawnData.entityId());
        CompoundBinaryTag spawnData = CompoundBinaryTag.builder().put("entity", entity.build()).build();
        CompoundBinaryTag tag = CompoundBinaryTag.builder()
                .putShort("Delay", (short) d.spawnDelay)
                .putShort("MinSpawnDelay", (short) d.minSpawnDelay)
                .putShort("MaxSpawnDelay", (short) d.maxSpawnDelay)
                .put("SpawnData", spawnData)
                .build();
        instance.sendGroupedPacket(new BlockEntityDataPacket(pos, net.minestom.server.instance.block.BlockEntityType.MOB_SPAWNER, tag));
    }

    private static SpawnEntry pickWeighted(List<SpawnEntry> potentials) {
        if (potentials.isEmpty()) return null;
        int total = 0;
        for (SpawnEntry e : potentials) total += Math.max(1, e.weight());
        int pick = ThreadLocalRandom.current().nextInt(total);
        for (SpawnEntry e : potentials) {
            pick -= Math.max(1, e.weight());
            if (pick < 0) return e;
        }
        return potentials.get(potentials.size() - 1);
    }

    private static boolean isNearPlayer(Instance instance, Point pos, int range) {
        double cx = pos.blockX() + 0.5, cy = pos.blockY() + 0.5, cz = pos.blockZ() + 0.5;
        for (Player p : instance.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            double dx = p.getPosition().x() - cx, dy = p.getPosition().y() - cy, dz = p.getPosition().z() - cz;
            if (dx * dx + dy * dy + dz * dz <= (double) range * range) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ breaking

    /** SpawnerBlock.spawnAfterBreak: 15+rand(15)+rand(15) XP, no item drop, ever — see class javadoc. */
    private static void onBreak(PlayerBlockBreakEvent e) {
        if (e.isCancelled()) return;
        if (!e.getBlock().key().value().equals("spawner")) return;
        if (e.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        long key = pack(e.getBlockPosition().blockX(), e.getBlockPosition().blockY(), e.getBlockPosition().blockZ());
        SPAWNERS.getOrDefault(e.getInstance(), Map.of()).remove(key);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int xp = 15 + rng.nextInt(15) + rng.nextInt(15);
        Experience.orb(e.getInstance(), e.getBlockPosition().add(0.5, 0.5, 0.5), xp);
    }

    // ------------------------------------------------------------------ util

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static Point unpack(long k) {
        return new Vec((int) (k >> 38), (int) (k << 26 >> 52), (int) (k << 38 >> 38));
    }
}
