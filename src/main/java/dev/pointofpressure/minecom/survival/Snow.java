package dev.pointofpressure.minecom.survival;

import dev.pointofpressure.minecom.Bootstrap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.TaskSchedule;

import java.util.Random;

/**
 * Snow accumulation and melting: in cold, precipitating biomes (the same
 * hasPrecipitation/coldEnoughToSnow check the worldgen freeze_top_layer feature uses),
 * exposed ground slowly builds up snow layers while it's raining/snowing, up to a full
 * 8-layer block (SnowLayerBlock.randomTick, minus the light-level gate — this project
 * has no reliable live light reads, matching the natural spawner's behavioural-light
 * approximation elsewhere). Wherever the biome is no longer cold enough — a warm biome,
 * or the same cold biome after the climate noise shifts — existing snow melts back down
 * a layer at a time. Bounded to a ring around each online player so cost doesn't scale
 * with world size.
 */
public final class Snow {
    private Snow() {}

    private static final Random RANDOM = new Random();

    public static void start(Instance instance) {
        MinecraftServer.getSchedulerManager().buildTask(() -> tick(instance))
                .repeat(TaskSchedule.tick(40)).schedule();
    }

    private static void tick(Instance instance) {
        var gen = Bootstrap.vanillaGen();
        if (gen == null) return;
        boolean raining = instance.getWeather().isRaining();

        for (Player player : instance.getPlayers()) {
            int px = player.getPosition().blockX(), pz = player.getPosition().blockZ();
            int py = player.getPosition().blockY();
            for (int i = 0; i < 6; i++) {
                int x = px + RANDOM.nextInt(33) - 16;
                int z = pz + RANDOM.nextInt(33) - 16;
                if (!instance.isChunkLoaded(x >> 4, z >> 4)) continue;
                Integer y = topSolid(instance, x, z, py);
                if (y == null) continue;
                String biome = gen.biomes().biomeAt(x >> 2, y >> 2, z >> 2);
                boolean canSnow = gen.surface().hasPrecipitation(biome)
                        && gen.surface().coldEnoughToSnow(biome, x, y + 1, z);
                if (raining && canSnow) accumulate(instance, x, y, z);
                else if (!canSnow) melt(instance, x, y, z);
            }
        }
    }

    private static Integer topSolid(Instance instance, int x, int z, int nearY) {
        for (int y = Math.min(nearY + 40, 319); y > Math.max(nearY - 60, -64); y--) {
            if (!instance.getBlock(x, y, z).isAir()) return y;
        }
        return null;
    }

    /** Test hook: run the accumulation step directly, bypassing weather/biome gating. */
    public static void testAccumulate(Instance instance, int x, int groundY, int z) {
        accumulate(instance, x, groundY, z);
    }

    /** Test hook: run the melt step directly, bypassing biome gating. */
    public static void testMelt(Instance instance, int x, int groundY, int z) {
        melt(instance, x, groundY, z);
    }

    private static void melt(Instance instance, int x, int groundY, int z) {
        Block above = instance.getBlock(x, groundY + 1, z);
        if (!above.compare(Block.SNOW)) return;
        String prop = above.getProperty("layers");
        int layers = prop == null ? 1 : Integer.parseInt(prop);
        instance.setBlock(x, groundY + 1, z, layers <= 1 ? Block.AIR : above.withProperty("layers", String.valueOf(layers - 1)));
    }

    private static void accumulate(Instance instance, int x, int groundY, int z) {
        Block ground = instance.getBlock(x, groundY, z);
        if (ground.isLiquid() || ground.key().value().contains("ice")) return;
        Block above = instance.getBlock(x, groundY + 1, z);
        if (above.compare(Block.SNOW)) {
            String prop = above.getProperty("layers");
            int layers = prop == null ? 1 : Integer.parseInt(prop);
            if (layers < 8) instance.setBlock(x, groundY + 1, z, above.withProperty("layers", String.valueOf(layers + 1)));
        } else if (above.isAir() && ground.isSolid()) {
            instance.setBlock(x, groundY + 1, z, Block.SNOW.withProperty("layers", "1"));
        }
    }
}
