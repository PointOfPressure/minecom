package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Empty-hand right-click harvesting: sweet berry bush (age>1 required) and cave vine glow
 * berries (berries=true required), both real vanilla mechanics ported from
 * SweetBerryBushBlock.useWithoutItem / CaveVines.use. NOT modeled: random-tick-driven growth
 * (bushes/vines placed via worldgen or bonemeal can already be at a harvestable state, but
 * there's no periodic aging simulation yet to bring a fresh planting up to that state on its
 * own) — this project has no generic random-tick crop-growth system at all yet, a broader,
 * separate gap this doesn't attempt to fill.
 */
public final class Harvesting {
    private Harvesting() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, Harvesting::interact);
    }

    private static void interact(PlayerBlockInteractEvent e) {
        String key = e.getBlock().key().value();
        if (key.equals("sweet_berry_bush")) {
            harvestBerryBush(e);
        } else if (key.equals("cave_vines") || key.equals("cave_vines_plant")) {
            harvestCaveVine(e);
        }
    }

    /** SweetBerryBushBlock.useWithoutItem: age>1 required; drops more at full maturity (age 3). */
    private static void harvestBerryBush(PlayerBlockInteractEvent e) {
        Block block = e.getBlock();
        int age = Integer.parseInt(block.getProperty("age"));
        if (age <= 1) return;
        e.setBlockingItemUse(true);

        Random random = ThreadLocalRandom.current();
        int count = 1 + random.nextInt(2); // pool 2: uniform(1,2), always rolled
        if (age == 3) count += 1;          // pool 1: +1, conditional on age==3
        drop(e.getInstance(), e.getBlockPosition(), Material.SWEET_BERRIES, count);

        Instance instance = e.getInstance();
        instance.setBlock(e.getBlockPosition(), block.withProperty("age", "1"));
    }

    /** CaveVines.use: berries=true required; always exactly 1 glow_berries, then clears the state. */
    private static void harvestCaveVine(PlayerBlockInteractEvent e) {
        Block block = e.getBlock();
        if (!"true".equals(block.getProperty("berries"))) return;
        e.setBlockingItemUse(true);

        drop(e.getInstance(), e.getBlockPosition(), Material.GLOW_BERRIES, 1);
        e.getInstance().setBlock(e.getBlockPosition(), block.withProperty("berries", "false"));
    }

    private static void drop(Instance instance, Point pos, Material material, int count) {
        ItemEntity drop = new ItemEntity(ItemStack.of(material, count));
        drop.setInstance(instance, new net.minestom.server.coordinate.Pos(
                pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5));
        drop.setPickupDelay(java.time.Duration.ofMillis(200));
    }
}
