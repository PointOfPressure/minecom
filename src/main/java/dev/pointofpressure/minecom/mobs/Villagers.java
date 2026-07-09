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

        // a wandering trader occasionally appears near a player
        MinecraftServer.getSchedulerManager().buildTask(() -> spawnWanderingTrader(overworld))
                .repeat(TaskSchedule.minutes(20)).schedule();
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
     * Find a nearby eligible villager pair and produce one offspring. Requires a spare
     * bed nearby (this project's villagers don't farm/gather food into a personal
     * inventory the way vanilla's willingness check does, so the food-threshold half of
     * the real rule is approximated away — the bed-capacity half is real and enforced).
     */
    public static void breedTick(Instance instance, long now) {
        List<Entity> villagers = instance.getEntities().stream()
                .filter(e -> e.getEntityType() == EntityType.VILLAGER && !e.isRemoved()).toList();
        for (int i = 0; i < villagers.size(); i++) {
            Entity a = villagers.get(i);
            if (a.hasTag(BRED_AT) && now - a.getTag(BRED_AT) < BREED_COOLDOWN) continue;
            for (int j = i + 1; j < villagers.size(); j++) {
                Entity b = villagers.get(j);
                if (b.hasTag(BRED_AT) && now - b.getTag(BRED_AT) < BREED_COOLDOWN) continue;
                if (a.getPosition().distanceSquared(b.getPosition()) > 8 * 8) continue;
                long nearby = villagers.stream()
                        .filter(v -> v.getPosition().distanceSquared(a.getPosition()) < 16 * 16).count();
                if (nearby >= CROWD_LIMIT) continue;
                if (!hasSpareBed(instance, a.getPosition(), (int) nearby)) continue;
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
}
