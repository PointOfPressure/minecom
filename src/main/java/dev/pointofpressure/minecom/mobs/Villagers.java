package dev.pointofpressure.minecom.mobs;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Populates generated villages: when a chunk loads, any village bell (placed by the
 * jigsaw town-centre pieces) that hasn't been seen yet gets a couple of wandering
 * villagers spawned beside it. A pragmatic stand-in until villagers are placed from
 * the template entity data with professions and trading.
 */
public final class Villagers {

    private static final Set<Long> SEEDED = ConcurrentHashMap.newKeySet();

    private Villagers() {}

    public static void register(Instance overworld) {
        overworld.eventNode().addListener(InstanceChunkLoadEvent.class, event -> {
            var chunk = event.getChunk();
            int cx = event.getChunkX(), cz = event.getChunkZ();
            int baseX = cx << 4, baseZ = cz << 4;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    // village bells sit near the surface — scan a tight band around the heightmap
                    int surface;
                    try { surface = chunk.worldSurfaceHeightmap().getHeight(x, z); }
                    catch (Exception ex) { surface = 80; }
                    for (int y = surface - 6; y <= surface + 3; y++) {
                        int wx = baseX + x, wz = baseZ + z;
                        if (!overworld.getBlock(wx, y, wz).compare(Block.BELL)) continue;
                        long key = ((long) wx << 32) | (wz & 0xFFFFFFFFL);
                        if (!SEEDED.add(key)) continue;
                        for (int i = 0; i < 2; i++) {
                            var villager = dev.pointofpressure.minecom.mobs.ai.VanillaMobs.villager(
                                    overworld, new Pos(wx + 0.5 + i, y + 1, wz + 0.5, 0f, 0f));
                            VillagerTrades.assignProfession(villager, overworld);
                        }
                    }
                }
            }
        });

        // villagers breed when a pair is close and the area isn't crowded
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            TICKS.addAndGet(40);
            breedTick(overworld, TICKS.get());
        }).repeat(TaskSchedule.tick(40)).schedule();

        // jobless villagers (freshly grown-up babies, or ones that wandered near a
        // job block built after they spawned) periodically try to claim one
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            for (Entity v : overworld.getEntities()) {
                if (v.getEntityType() == EntityType.VILLAGER && !v.isRemoved()) {
                    VillagerTrades.assignProfession(v, overworld);
                }
            }
        }).repeat(TaskSchedule.tick(40)).schedule();

        // villagers restock their trades while working at their job site during the day,
        // and panic/gossip clusters summon iron-golem defenders
        MinecraftServer.getSchedulerManager().buildTask(() -> restockSweep(overworld))
                .repeat(TaskSchedule.tick(40)).schedule();
        MinecraftServer.getSchedulerManager().buildTask(() -> golemSweep(overworld))
                .repeat(TaskSchedule.tick(60)).schedule();

        // a wandering trader occasionally appears near a player
        MinecraftServer.getSchedulerManager().buildTask(() -> spawnWanderingTrader(overworld))
                .repeat(TaskSchedule.minutes(20)).schedule();

        // personal food inventories: passive pickup + farmer harvesting/sharing
        VillagerFood.start(overworld);
    }

    private static void spawnWanderingTrader(Instance overworld) {
        var players = overworld.getPlayers();
        if (players.isEmpty()) return;
        boolean exists = overworld.getEntities().stream()
                .anyMatch(e -> e.getEntityType() == EntityType.WANDERING_TRADER && !e.isRemoved());
        if (exists) return;
        var player = players.iterator().next();
        int px = player.getPosition().blockX() + 12, pz = player.getPosition().blockZ() + 12;
        for (int y = 200; y > 40; y--) {
            if (!overworld.getBlock(px, y, pz).isAir() && overworld.getBlock(px, y + 1, pz).isAir()) {
                dev.pointofpressure.minecom.mobs.ai.VanillaMobs.wanderingTrader(
                        overworld, new Pos(px + 0.5, y + 1, pz + 0.5));
                return;
            }
        }
    }

    private static final Tag<Long> BRED_AT = Tag.Long("minecom:bred_at");
    private static final java.util.concurrent.atomic.AtomicLong TICKS = new java.util.concurrent.atomic.AtomicLong();
    private static final long BREED_COOLDOWN = 1200;   // ~60s
    private static final int CROWD_LIMIT = 10;

    /**
     * Find a nearby eligible villager pair and produce one offspring. Both the real
     * willingness halves are enforced: each parent needs 12 food points on hand
     * (Villager.canBreed: foodLevel + inventory food, from picked-up or farmed food —
     * see {@link VillagerFood}), and there must be a spare bed in the village.
     * Breeding digests 12 food points from each parent (eatAndDigestFood).
     */
    public static void breedTick(Instance instance, long now) {
        List<Entity> villagers = instance.getEntities().stream()
                .filter(e -> e.getEntityType() == EntityType.VILLAGER && !e.isRemoved()).toList();
        for (int i = 0; i < villagers.size(); i++) {
            Entity a = villagers.get(i);
            if (a.hasTag(BRED_AT) && now - a.getTag(BRED_AT) < BREED_COOLDOWN) continue;
            if (!VillagerFood.canBreed(a)) continue;
            for (int j = i + 1; j < villagers.size(); j++) {
                Entity b = villagers.get(j);
                if (b.hasTag(BRED_AT) && now - b.getTag(BRED_AT) < BREED_COOLDOWN) continue;
                if (!VillagerFood.canBreed(b)) continue;
                if (a.getPosition().distanceSquared(b.getPosition()) > 8 * 8) continue;
                long nearby = villagers.stream()
                        .filter(v -> v.getPosition().distanceSquared(a.getPosition()) < 16 * 16).count();
                if (nearby >= CROWD_LIMIT) continue;
                if (!hasSpareBed(instance, a.getPosition(), (int) nearby)) continue;
                VillagerFood.eatAndDigestFood(a);
                VillagerFood.eatAndDigestFood(b);
                Pos mid = a.getPosition().add(b.getPosition()).div(2);
                var baby = dev.pointofpressure.minecom.mobs.ai.VanillaMobs.villager(instance, mid.withView(0f, 0f));
                if (baby.getEntityMeta() instanceof net.minestom.server.entity.metadata.AgeableMobMeta meta) {
                    meta.setBaby(true);
                    baby.scheduler().buildTask(() -> {
                        if (!baby.isDead() && baby.getEntityMeta() instanceof net.minestom.server.entity.metadata.AgeableMobMeta m) {
                            m.setBaby(false);
                        }
                    }).delay(net.minestom.server.timer.TaskSchedule.tick(24000)).schedule(); // grows up in 20 min
                }
                a.setTag(BRED_AT, now);
                b.setTag(BRED_AT, now);
                return;   // one birth per tick
            }
        }
    }

    /** More beds within the village radius than villagers already living there. */
    private static boolean hasSpareBed(Instance instance, Pos center, int villagerCount) {
        int radius = 16;
        int bx = center.blockX(), by = center.blockY(), bz = center.blockZ();
        int beds = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    if (instance.getBlock(bx + dx, by + dy, bz + dz).key().value().endsWith("_bed")) {
                        beds++;
                        if (beds > villagerCount) return true;
                    }
                }
            }
        }
        return beds > villagerCount;
    }

    // ------------------------------------------------------------------ trade restocking

    /** How close a professioned villager must be to a matching job-site block to "work" it. */
    private static final int WORK_RADIUS = 4;

    /**
     * Villager WORK-activity restock trigger: professioned villagers standing at their job
     * site during the day restock their trades ({@link VillagerTrades#tryRestock}). Public so
     * PlayTest can drive it deterministically (flat playtest worlds don't run the scheduler).
     *
     * <p>Simplification (AUDIT.md): real vanilla restocks inside the {@code WorkAtPoi} brain
     * task, which paths the villager to its claimed POI ticket. This project has no villager
     * brain schedule, so "working" is approximated as being within {@link #WORK_RADIUS} of a
     * job-site block matching the villager's profession; "day" is instance day-time &lt; 12000.
     */
    public static void restockSweep(Instance instance) {
        long gameTime = instance.getTime();
        if (Math.floorMod(gameTime, 24000L) >= 12000L) return; // villagers work during the day
        for (Entity v : instance.getEntities()) {
            if (v.getEntityType() != EntityType.VILLAGER || v.isRemoved()) continue;
            String profession = v.getTag(VillagerTrades.PROFESSION);
            if (profession == null || !atJobSite(instance, v, profession)) continue;
            VillagerTrades.tryRestock(v, gameTime);
        }
    }

    private static boolean atJobSite(Instance instance, Entity villager, String profession) {
        Pos p = villager.getPosition();
        int bx = p.blockX(), by = p.blockY(), bz = p.blockZ();
        for (int dx = -WORK_RADIUS; dx <= WORK_RADIUS; dx++) {
            for (int dz = -WORK_RADIUS; dz <= WORK_RADIUS; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    if (profession.equals(VillagerTrades.JOB_SITE_BLOCKS.get(
                            instance.getBlock(bx + dx, by + dy, bz + dz)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ iron-golem spawning

    /** Villager.HOW_FAR_AWAY_TO_TALK_TO_OTHER_VILLAGERS_ABOUT_GOLEMS. */
    private static final int GOLEM_TALK_RADIUS = 10;
    /** Villager.HOW_MANY_VILLAGERS_NEED_TO_AGREE_TO_SPAWN_A_GOLEM. */
    private static final int VILLAGERS_NEEDED_TO_AGREE = 5;
    /** GolemSensor.MEMORY_TIME_TO_LIVE: how long a detected golem suppresses re-spawn. */
    private static final long GOLEM_DETECTED_TTL = 599L;
    /** Villager.gossip throttle (lastGossipTime): a villager gossips at most every 1200 ticks. */
    private static final long GOSSIP_COOLDOWN = 1200L;
    /** No two village golems spawn within this radius (SpawnUtil LEGACY_IRON_GOLEM + GolemSensor). */
    private static final int GOLEM_SEARCH_RADIUS = 16;

    /** Game-time until which this villager has a golem detected nearby (MemoryModuleType.GOLEM_DETECTED_RECENTLY). */
    private static final Tag<Long> GOLEM_DETECTED_UNTIL = Tag.Long("minecom:golem_detected_until").defaultValue(0L);
    private static final Tag<Long> LAST_GOSSIP = Tag.Long("minecom:villager_last_gossip").defaultValue(0L);

    /**
     * Village golem-spawn cadence: Villager.gossip fires spawnGolemIfNeeded when two nearby
     * villagers exchange gossip (throttled to {@link #GOSSIP_COOLDOWN}). This sweep models
     * that trigger — each villager whose gossip cooldown has elapsed attempts a spawn.
     *
     * <p>Simplification (AUDIT.md): the trigger is a periodic gossip-cadence sweep rather than
     * the mission-scoped "20% chance per attempt", which is not in the 26.2 decompile — the
     * real trigger is deterministic once conditions are met (Villager.spawnGolemIfNeeded has
     * no roll), gated only by the gossip cooldown modelled here.
     */
    public static void golemSweep(Instance instance) {
        long gameTime = instance.getTime();
        for (Entity v : instance.getEntities()) {
            if (v.getEntityType() != EntityType.VILLAGER || v.isRemoved()) continue;
            if (gameTime < v.getTag(LAST_GOSSIP) + GOSSIP_COOLDOWN && v.getTag(LAST_GOSSIP) != 0) continue;
            v.setTag(LAST_GOSSIP, gameTime);
            spawnGolemIfNeeded(v, instance, gameTime);
        }
    }

    /**
     * Villager.spawnGolemIfNeeded (decompiled): if this villager wants a golem and at least
     * {@link #VILLAGERS_NEEDED_TO_AGREE} villagers within {@link #GOLEM_TALK_RADIUS} agree,
     * summon one iron golem at a valid nearby spot (unless a golem is already within
     * {@link #GOLEM_SEARCH_RADIUS}, which instead marks everyone as having detected it).
     * Returns true iff a golem spawned. Deterministic — mirrors vanilla, no random roll.
     */
    public static boolean spawnGolemIfNeeded(Entity villager, Instance instance, long gameTime) {
        if (!wantsToSpawnGolem(villager, gameTime)) return false;
        List<Entity> nearby = new java.util.ArrayList<>();
        for (Entity e : instance.getEntities()) {
            if (e.getEntityType() == EntityType.VILLAGER && !e.isRemoved()
                    && e.getPosition().distanceSquared(villager.getPosition()) <= GOLEM_TALK_RADIUS * GOLEM_TALK_RADIUS) {
                nearby.add(e);
            }
        }
        long agree = nearby.stream().filter(v -> wantsToSpawnGolem(v, gameTime)).limit(VILLAGERS_NEEDED_TO_AGREE).count();
        if (agree < VILLAGERS_NEEDED_TO_AGREE) return false;

        if (ironGolemWithin(instance, villager.getPosition(), GOLEM_SEARCH_RADIUS)) {
            // GolemSensor.golemDetected: seeing a golem suppresses the whole cluster's attempts
            for (Entity v : nearby) markGolemDetected(v, gameTime);
            return false;
        }
        Pos spot = findGolemSpawn(instance, villager.getPosition());
        if (spot == null) return false;
        dev.pointofpressure.minecom.mobs.ai.VanillaMobs.ironGolem(instance, spot);
        for (Entity v : nearby) markGolemDetected(v, gameTime);
        return true;
    }

    /**
     * Villager.wantsToSpawnGolem: the villager slept recently and has no golem detected
     * nearby. Simplification (AUDIT.md): these villagers have no sleep AI, so "slept recently"
     * (Villager.golemSpawnConditionsMet, LAST_SLEPT within 24000) is treated as always true —
     * the gate reduces to the golem-detected memory, which is fully modelled.
     */
    private static boolean wantsToSpawnGolem(Entity villager, long gameTime) {
        return gameTime >= villager.getTag(GOLEM_DETECTED_UNTIL);
    }

    private static void markGolemDetected(Entity villager, long gameTime) {
        villager.setTag(GOLEM_DETECTED_UNTIL, gameTime + GOLEM_DETECTED_TTL);
    }

    private static boolean ironGolemWithin(Instance instance, Pos center, int radius) {
        double r2 = (double) radius * radius;
        for (Entity e : instance.getEntities()) {
            if (e.getEntityType() == EntityType.IRON_GOLEM && !e.isRemoved()
                    && e.getPosition().distanceSquared(center) <= r2) {
                return true;
            }
        }
        return false;
    }

    /**
     * A valid iron-golem spawn spot near the villager: a solid floor with two air blocks
     * above, searched outward from the villager. Simplification (AUDIT.md): a single-column
     * air check within a small box, standing in for SpawnUtil.trySpawnMob's full
     * 10x8x6 LEGACY_IRON_GOLEM bounding-box scan. Returns null if none is found.
     */
    private static Pos findGolemSpawn(Instance instance, Pos origin) {
        int ox = origin.blockX(), oy = origin.blockY(), oz = origin.blockZ();
        for (int radius = 1; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue; // ring only
                    for (int dy = -3; dy <= 3; dy++) {
                        int x = ox + dx, y = oy + dy, z = oz + dz;
                        if (!instance.getBlock(x, y - 1, z).isSolid()) continue;
                        if (!instance.getBlock(x, y, z).isAir() || !instance.getBlock(x, y + 1, z).isAir()) continue;
                        return new Pos(x + 0.5, y, z + 0.5);
                    }
                }
            }
        }
        return null;
    }
}
