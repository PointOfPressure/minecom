package dev.pointofpressure.minecom.blocks;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/**
 * Player-built golem construction (CarvedPumpkinBlock.trySpawnGolem, decompiled): placing a
 * carved pumpkin (or jack o'lantern) as the last block of a golem pattern turns the pattern
 * into a mob. Snow golem — pumpkin over two vertically-stacked snow blocks — is checked
 * first, then iron golem — pumpkin over a T of 4 iron blocks (a central column of two, with
 * an arm to either side of the upper block, along either horizontal axis). The pattern
 * blocks are consumed and the golem spawns where its feet were.
 *
 * <p>Not modelled (AUDIT.md): the copper golem pattern (its chest/weathering machinery is a
 * separate feature), and IronGolem.setPlayerCreated (village-defense targeting already keys
 * off a fixed hostile whitelist, so the flag has no effect here).
 */
public final class Golems {
    private Golems() {}

    public static void register(GlobalEventHandler events) {
        // PlayerBlockPlaceEvent fires BEFORE the block is written to the world, so defer the
        // pattern check by one tick (mirrors CarvedPumpkinBlock.onPlace, which runs post-set).
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (!isPumpkin(e.getBlock())) return;
            Instance instance = e.getInstance();
            Point pos = e.getBlockPosition();
            MinecraftServer.getSchedulerManager().scheduleNextTick(() -> tryBuildGolem(instance, pos));
        });
    }

    private static boolean isPumpkin(Block block) {
        String id = block.key().value();
        return id.equals("carved_pumpkin") || id.equals("jack_o_lantern");
    }

    private static boolean isPumpkin(Instance instance, Point p) {
        return isPumpkin(instance.getBlock(p));
    }

    /**
     * Check the golem patterns anchored at a just-placed pumpkin at {@code top} and, if one
     * matches, consume its blocks and spawn the golem. Snow golem is checked before iron, as
     * in vanilla. Public so the pumpkin-carving path (carving a pumpkin atop a pattern also
     * triggers a spawn in vanilla) and PlayTest can drive it.
     */
    public static void tryBuildGolem(Instance instance, Point top) {
        if (!isPumpkin(instance, top)) return;
        Point below = top.add(0, -1, 0);
        Point below2 = top.add(0, -2, 0);

        // snow golem: pumpkin over two snow blocks
        if (is(instance, below, Block.SNOW_BLOCK) && is(instance, below2, Block.SNOW_BLOCK)) {
            clear(instance, top, below, below2);
            dev.pointofpressure.minecom.mobs.ai.VanillaMobs.snowGolem(instance, feet(below2));
            return;
        }

        // iron golem: pumpkin over a T of 4 iron blocks (central column of 2 + two arms
        // flanking the upper block) along either horizontal axis
        if (is(instance, below, Block.IRON_BLOCK) && is(instance, below2, Block.IRON_BLOCK)) {
            int[][] axes = {{1, 0}, {0, 1}};
            for (int[] a : axes) {
                Point armA = below.add(a[0], 0, a[1]);
                Point armB = below.add(-a[0], 0, -a[1]);
                if (is(instance, armA, Block.IRON_BLOCK) && is(instance, armB, Block.IRON_BLOCK)) {
                    clear(instance, top, below, below2, armA, armB);
                    dev.pointofpressure.minecom.mobs.ai.VanillaMobs.ironGolem(instance, feet(below2));
                    return;
                }
            }
        }
    }

    private static boolean is(Instance instance, Point p, Block block) {
        return instance.getBlock(p).compare(block);
    }

    private static Pos feet(Point bottom) {
        return new Pos(bottom.blockX() + 0.5, bottom.blockY(), bottom.blockZ() + 0.5);
    }

    private static void clear(Instance instance, Point... points) {
        for (Point p : points) instance.setBlock(p, Block.AIR);
    }
}
