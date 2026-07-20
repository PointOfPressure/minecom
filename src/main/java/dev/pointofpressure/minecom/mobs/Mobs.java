package dev.pointofpressure.minecom.mobs;

import net.kyori.adventure.sound.Sound;
import dev.pointofpressure.minecom.TickPipeline;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityProjectile;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.ai.EntityAIGroupBuilder;
import net.minestom.server.entity.ai.goal.MeleeAttackGoal;
import net.minestom.server.entity.ai.goal.RandomLookAroundGoal;
import net.minestom.server.entity.ai.goal.RandomStrollGoal;
import net.minestom.server.entity.ai.goal.RangedAttackGoal;
import net.minestom.server.entity.ai.target.ClosestEntityTarget;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.entity.metadata.monster.CreeperMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mob roster, AI wiring, and the natural spawn cycle: passive animals on grass in
 * daylight, hostiles at night on the surface or in the deep dark, despawn when far
 * from every player. Creepers fuse and explode (entity damage only, no block damage).
 */
public final class Mobs {
    private Mobs() {}

    private static final Random RANDOM = new Random();
    private static final int HOSTILE_CAP = 24;
    private static final int PASSIVE_CAP = 8;

    private static final List<EntityType> PASSIVE = List.of(
            EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN);

    /**
     * Start the vanilla-faithful natural spawner (NaturalSpawner port). Runs the
     * per-chunk spawn decision every tick across a thread pool (the multithreading
     * thesis), despawns every second. Returns the spawner for benchmarking.
     */
    public static VNaturalSpawner startVanillaSpawner(Instance instance, VNaturalSpawner.BiomeAt biomeAt) {
        return startVanillaSpawner(instance, biomeAt, false);
    }

    /** Distinct timer name per spawner instance (overworld/nether/end). */
    private static int spawnerSeq = 0;

    public static VNaturalSpawner startVanillaSpawner(Instance instance, VNaturalSpawner.BiomeAt biomeAt, boolean noSkyLight) {
        VNaturalSpawner spawner = new VNaturalSpawner(instance, biomeAt, noSkyLight);
        long[] tick = {0};
        TickPipeline.register(TickPipeline.MOB_SPAWNING, "naturalSpawner" + (spawnerSeq++), () -> {
            long t = tick[0]++;
            spawner.spawnTick(t, true);
            if (t % 20 == 0) spawner.despawnTick();
        });
        return spawner;
    }

    /** Legacy crude spawner (superseded by {@link #startVanillaSpawner}); kept for reference. */
    public static void startSpawner(Instance instance) {
        MinecraftServer.getSchedulerManager().buildTask(() -> spawnCycle(instance))
                .repeat(TaskSchedule.tick(60))
                .schedule();
    }

    private static void spawnCycle(Instance instance) {
        var players = instance.getPlayers();
        if (players.isEmpty()) return;

        AtomicInteger hostile = new AtomicInteger();
        AtomicInteger passive = new AtomicInteger();
        for (var entity : instance.getEntities()) {
            if (!(entity instanceof EntityCreature mob) || mob.isDead()) continue;
            if (PASSIVE.contains(mob.getEntityType())) passive.incrementAndGet();
            else hostile.incrementAndGet();
            // despawn when far from every player
            boolean near = players.stream()
                    .anyMatch(p -> p.getPosition().distanceSquared(mob.getPosition()) < 96 * 96);
            if (!near) mob.remove();
        }

        for (Player player : players) {
            attemptSpawn(instance, player, hostile, passive);
        }
    }

    private static void attemptSpawn(Instance instance, Player player, AtomicInteger hostile, AtomicInteger passive) {
        long time = instance.getTime() % 24000;
        boolean night = time > 13000 && time < 23500;

        for (int i = 0; i < 3; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2;
            double dist = 24 + RANDOM.nextDouble() * 32;
            int x = (int) Math.floor(player.getPosition().x() + Math.cos(angle) * dist);
            int z = (int) Math.floor(player.getPosition().z() + Math.sin(angle) * dist);
            if (!instance.isChunkLoaded(x >> 4, z >> 4)) continue;

            int py = (int) player.getPosition().y();
            Integer ground = findGround(instance, x, py, z);
            if (ground == null) continue;
            Block floor = instance.getBlock(x, ground, z);
            Pos spawnPos = new Pos(x + 0.5, ground + 1, z + 0.5);

            boolean deepDark = ground < 40;
            if ((night || deepDark) && hostile.get() < HOSTILE_CAP) {
                spawnHostile(instance, spawnPos);
                hostile.incrementAndGet();
            } else if (!night && !deepDark && passive.get() < PASSIVE_CAP
                    && floor.compare(Block.GRASS_BLOCK) && RANDOM.nextInt(4) == 0) {
                spawnPassive(instance, spawnPos);
                passive.incrementAndGet();
            }
        }
    }

    /** Topmost solid block with 2 air above, scanning around the player's Y. */
    private static Integer findGround(Instance instance, int x, int nearY, int z) {
        for (int y = Math.min(nearY + 20, 250); y > Math.max(nearY - 24, -60); y--) {
            Block at = instance.getBlock(x, y, z);
            if (!at.isSolid()) continue;
            if (at.isLiquid()) return null;
            Block above = instance.getBlock(x, y + 1, z);
            Block above2 = instance.getBlock(x, y + 2, z);
            if (above.isAir() && above2.isAir()) return y;
            return null;
        }
        return null;
    }

    /** Public spawn API — every mob is built by the vanilla-ported brain in VanillaMobs. */
    public static EntityCreature spawn(String kind, Instance instance, Pos pos) {
        return switch (kind) {
            case "zombie" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.zombie(instance, pos);
            case "spider" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.spider(instance, pos);
            case "cave_spider" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.caveSpider(instance, pos);
            case "endermite" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.endermite(instance, pos);
            case "silverfish" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.silverfish(instance, pos);
            case "skeleton" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.skeleton(instance, pos);
            case "creeper" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.creeper(instance, pos);
            case "husk" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.husk(instance, pos);
            case "drowned" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.drowned(instance, pos);
            case "zombie_villager" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.zombieVillager(instance, pos);
            case "stray" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.stray(instance, pos);
            case "bogged" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.bogged(instance, pos);
            case "slime" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.slime(instance, pos);
            case "sulfur_cube" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.sulfurCube(instance, pos);
            case "enderman" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.enderman(instance, pos);
            case "piglin" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.piglin(instance, pos);
            case "piglin_brute" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.piglinBrute(instance, pos);
            case "hoglin" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.hoglin(instance, pos);
            case "zoglin" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.zoglin(instance, pos);
            case "giant" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.giant(instance, pos);
            case "strider" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.strider(instance, pos);
            case "breeze" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.breeze(instance, pos);
            case "warden" -> dev.pointofpressure.minecom.mobs.ai.WardenMob.spawn(instance, pos, false);
            case "creaking" -> dev.pointofpressure.minecom.mobs.ai.CreakingMob.spawn(instance, pos, null);
            case "happy_ghast" -> dev.pointofpressure.minecom.mobs.ai.HappyGhastMob.spawn(instance, pos);
            case "wither_skeleton" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.witherSkeleton(instance, pos);
            case "witch" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.witch(instance, pos);
            case "ghast" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.ghast(instance, pos);
            case "phantom" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.phantom(instance, pos);
            case "guardian" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.guardian(instance, pos);
            case "elder_guardian" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.elderGuardian(instance, pos);
            case "shulker" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.shulker(instance, pos);
            case "wither" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.wither(instance, pos);
            case "iron_golem" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.ironGolem(instance, pos);
            case "snow_golem" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.snowGolem(instance, pos);
            case "parched" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.parched(instance, pos);
            case "bat" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.bat(instance, pos);
            case "wandering_trader" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.wanderingTrader(instance, pos);
            case "villager" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.villager(instance, pos);
            case "pillager" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.pillager(instance, pos);
            case "vindicator" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.vindicator(instance, pos);
            case "evoker" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.evoker(instance, pos);
            case "illusioner" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.illusioner(instance, pos);
            case "ravager" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.ravager(instance, pos);
            case "cow", "pig", "sheep", "chicken", "mooshroom", "rabbit", "goat",
                 "llama", "turtle", "panda", "polar_bear", "armadillo", "camel", "fox", "frog",
                 "parrot", "ocelot" ->
                    dev.pointofpressure.minecom.mobs.ai.VanillaMobs.animal(kind, instance, pos);
            case "bee" -> dev.pointofpressure.minecom.mobs.Bees.spawn(instance, pos);
            case "allay" -> dev.pointofpressure.minecom.mobs.Allays.spawn(instance, pos);
            case "wolf" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.wolf(instance, pos);
            case "cat" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.cat(instance, pos);
            case "horse", "donkey", "mule", "skeleton_horse", "zombie_horse" ->
                    dev.pointofpressure.minecom.mobs.ai.VanillaMobs.horseFamily(kind, instance, pos);
            case "squid", "glow_squid", "cod", "salmon", "pufferfish", "tropical_fish", "dolphin", "axolotl", "nautilus" ->
                    dev.pointofpressure.minecom.mobs.ai.VanillaMobs.waterAnimal(kind, instance, pos);
            case "zombified_piglin" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.zombifiedPiglin(instance, pos);
            case "magma_cube" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.magmaCube(instance, pos);
            case "blaze" -> dev.pointofpressure.minecom.mobs.ai.VanillaMobs.blaze(instance, pos);
            default -> null;
        };
    }

    /** Nether population: piglins common, magma cubes near lava, blazes rare. */
    public static void startNetherSpawner(Instance nether) {
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            var players = nether.getPlayers();
            if (players.isEmpty()) return;
            long mobs = nether.getEntities().stream()
                    .filter(en -> en instanceof EntityCreature c && !c.isDead()).count();
            for (var entity : nether.getEntities()) {
                if (entity instanceof EntityCreature mob && !mob.isDead()
                        && players.stream().noneMatch(p ->
                                p.getPosition().distanceSquared(mob.getPosition()) < 96 * 96)) {
                    mob.remove();
                }
            }
            if (mobs >= 16) return;
            for (Player player : players) {
                double angle = RANDOM.nextDouble() * Math.PI * 2;
                double dist = 16 + RANDOM.nextDouble() * 24;
                int x = (int) Math.floor(player.getPosition().x() + Math.cos(angle) * dist);
                int z = (int) Math.floor(player.getPosition().z() + Math.sin(angle) * dist);
                if (!nether.isChunkLoaded(x >> 4, z >> 4)) continue;
                Integer ground = findGround(nether, x, (int) player.getPosition().y(), z);
                if (ground == null) continue;
                Pos spawnPos = new Pos(x + 0.5, ground + 1, z + 0.5);
                int roll = RANDOM.nextInt(100);
                if (roll < 55) spawn("zombified_piglin", nether, spawnPos);
                else if (roll < 85) spawn("magma_cube", nether, spawnPos);
                else spawn("blaze", nether, spawnPos);
            }
        }).repeat(TaskSchedule.tick(80)).schedule();
    }

    private static void spawnHostile(Instance instance, Pos pos) {
        int roll = RANDOM.nextInt(100);
        if (roll < 40) spawn("zombie", instance, pos);
        else if (roll < 65) spawn("spider", instance, pos);
        else if (roll < 85) spawn("skeleton", instance, pos);
        else spawn("creeper", instance, pos);
    }

    private static void spawnPassive(Instance instance, Pos pos) {
        spawn(PASSIVE.get(RANDOM.nextInt(PASSIVE.size())).key().value(), instance, pos);
    }









}
