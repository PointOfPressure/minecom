package dev.pointofpressure.minecom.survival;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;
import net.minestom.server.timer.TaskSchedule;

import java.util.Random;

/**
 * Thunderstorms + lightning strikes. Minestom's {@code Weather} record already carries
 * a {@code thunderLevel} alongside {@code rainLevel} ({@code Weather.THUNDER} = rain 1,
 * thunder 1) — no new weather-state modeling needed, just driving it. Real vanilla
 * escalates an active rain storm to thundering with its own independent probability and
 * duration; bounded here to a flat chance-per-check while already raining, riding the
 * same storm's duration rather than tracking a second timer. Unblocks Channeling
 * (enchantment/channeling.json requires {@code weather_check: thundering=true}).
 */
public final class Lightning {
    private Lightning() {}

    private static final Random RANDOM = new Random();

    public static void start(Instance instance) {
        // escalate/de-escalate thunder alongside the existing rain cycle
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            Weather weather = instance.getWeather();
            if (weather.isRaining() && !isThundering(instance) && RANDOM.nextDouble() < 0.1) {
                instance.setWeather(Weather.THUNDER);
            } else if (!weather.isRaining() && isThundering(instance)) {
                instance.setWeather(Weather.CLEAR);
            }
        }).repeat(TaskSchedule.tick(200)).schedule();

        // random strikes near a random online player while thundering
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            if (!isThundering(instance)) return;
            var players = instance.getPlayers();
            if (players.isEmpty()) return;
            Player target = players.stream().skip(RANDOM.nextInt(players.size())).findFirst().orElse(null);
            if (target == null) return;
            double x = target.getPosition().x() + RANDOM.nextInt(65) - 32;
            double z = target.getPosition().z() + RANDOM.nextInt(65) - 32;
            strikeAt(instance, x, z);
        }).repeat(TaskSchedule.tick(600)).schedule();
    }

    public static boolean isThundering(Instance instance) {
        return instance.getWeather().thunderLevel() > 0f;
    }

    /** No solid block between this position and the build-height ceiling. */
    public static boolean canSeeSky(Instance instance, Pos pos) {
        int x = pos.blockX(), z = pos.blockZ();
        for (int y = 319; y > pos.blockY(); y--) {
            if (!instance.getBlock(x, y, z).isAir()) return false;
        }
        return true;
    }

    /**
     * Strikes at the given XZ, landing on the current top-solid block (or redirected to a
     * nearby exposed living entity — see below); damages nearby living entities
     * (LightningBolt.tick: 5 damage), charges any creeper struck (Creeper.thunderHit sets
     * DATA_IS_POWERED — real vanilla's charged-creeper source, see
     * {@link dev.pointofpressure.minecom.blocks.Explosions#explode}), and converts any
     * villager struck directly to a witch (Villager.thunderHit: unconditional on any
     * non-peaceful difficulty, no probability roll — replaces the damage/fire entirely,
     * matched here since this project has no difficulty setting below normal). Bounded: no
     * fire-starting, no lightning-rod redirection (ServerLevel.findLightningRod: a 128-block
     * POI-manager search, real vanilla's FIRST target-redirect check, tried before the
     * entity-redirect below — this project has no spatial index of placed rods and a brute-
     * force 128-radius scan on every strike would be far too expensive; needs a lightweight
     * tracked-position registry as a prerequisite, logged in docs/HANDOFF.md).
     */
    public static void strikeAt(Instance instance, double x, double z) {
        int y = topSolidY(instance, (int) Math.floor(x), (int) Math.floor(z));
        if (y < -63) return;
        Pos ground = new Pos(x, y + 1, z);
        Pos pos = redirectToEntity(instance, ground);
        Entity bolt = new Entity(EntityType.LIGHTNING_BOLT);
        bolt.setInstance(instance, pos);
        for (Entity e : instance.getNearbyEntities(pos, 3.0)) {
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            if (e.getEntityType() == EntityType.VILLAGER) {
                Pos at = le.getPosition();
                Instance in = le.getInstance();
                le.remove();
                dev.pointofpressure.minecom.mobs.ai.VanillaMobs.witch(in, at);
                continue;
            }
            le.damage(DamageType.LIGHTNING_BOLT, 5f);
            if (e.getEntityType() == EntityType.CREEPER
                    && e.getEntityMeta() instanceof net.minestom.server.entity.metadata.monster.CreeperMeta cm) {
                cm.setCharged(true);
            }
        }
        bolt.scheduler().buildTask(bolt::remove).delay(TaskSchedule.tick(2)).schedule();
    }

    /**
     * ServerLevel.findLightningTargetAround's entity-redirect fallback (tried after the rod
     * search, which this project doesn't implement — see the docs above): search a vertical
     * column from ground level up to the world ceiling, inflated 3 blocks horizontally, for any
     * sky-exposed living entity, and redirect the strike to a random one if any are found. This
     * is the real, commonly-noticed "you can get randomly struck by lightning during a storm"
     * mechanic — not just villager-to-witch and creeper-charging, which only trigger on a DIRECT
     * hit near wherever the strike already landed.
     */
    private static Pos redirectToEntity(Instance instance, Pos ground) {
        int minX = ground.blockX() - 3, maxX = ground.blockX() + 3;
        int minZ = ground.blockZ() - 3, maxZ = ground.blockZ() + 3;
        int minY = ground.blockY();
        java.util.List<LivingEntity> candidates = new java.util.ArrayList<>();
        for (Entity e : instance.getEntities()) {
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            Pos p = le.getPosition();
            if (p.blockX() < minX || p.blockX() > maxX || p.blockZ() < minZ || p.blockZ() > maxZ) continue;
            if (p.blockY() < minY) continue;
            if (!canSeeSky(instance, p)) continue;
            candidates.add(le);
        }
        if (candidates.isEmpty()) return ground;
        return candidates.get(RANDOM.nextInt(candidates.size())).getPosition();
    }

    private static int topSolidY(Instance instance, int x, int z) {
        for (int y = 319; y >= -64; y--) {
            if (!instance.getBlock(x, y, z).isAir()) return y;
        }
        return -100;
    }
}
