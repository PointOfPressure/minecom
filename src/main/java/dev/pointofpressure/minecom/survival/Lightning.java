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
     * Strikes at the given XZ, landing on the current top-solid block; damages nearby
     * living entities (LightningBolt.tick: 5 damage). Bounded: no fire-starting, no
     * charged-creeper/villager-to-witch conversion — real vanilla side effects this
     * project doesn't model.
     */
    public static void strikeAt(Instance instance, double x, double z) {
        int y = topSolidY(instance, (int) Math.floor(x), (int) Math.floor(z));
        if (y < -63) return;
        Pos pos = new Pos(x, y + 1, z);
        Entity bolt = new Entity(EntityType.LIGHTNING_BOLT);
        bolt.setInstance(instance, pos);
        for (Entity e : instance.getNearbyEntities(pos, 3.0)) {
            if (e instanceof LivingEntity le && !le.isDead()) {
                le.damage(DamageType.LIGHTNING_BOLT, 5f);
            }
        }
        bolt.scheduler().buildTask(bolt::remove).delay(TaskSchedule.tick(2)).schedule();
    }

    private static int topSolidY(Instance instance, int x, int z) {
        for (int y = 319; y >= -64; y--) {
            if (!instance.getBlock(x, y, z).isAir()) return y;
        }
        return -100;
    }
}
