package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/**
 * CakeBlock: eating a slice (empty-hand right-click) advances BITES 0-6, restoring the real
 * 2 nutrition / 0.1 saturation-modifier (the same FoodData.eat formula Survival.java already
 * uses for component-based foods) each time; the 7th bite removes the block entirely instead
 * of going past 6. Comparator output follows the real (7-bites)*2 formula. NOT modeled:
 * inserting a candle into a full cake to make a candle_cake (CandleCakeBlock is a separate,
 * not-yet-implemented block family — see Candle.java's own scope note).
 */
public final class Cake {
    private Cake() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, Cake::interact);
    }

    private static void interact(PlayerBlockInteractEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("cake")) return;

        Player player = e.getPlayer();
        if (player.getFood() >= 20) return;
        e.setBlockingItemUse(true);

        player.setFood(Math.min(20, player.getFood() + 2));
        player.setFoodSaturation(Math.min(player.getFood(), player.getFoodSaturation() + 2 * 0.1f * 2f));

        int bites = Integer.parseInt(block.getProperty("bites"));
        if (bites < 6) {
            instance.setBlock(pos, block.withProperty("bites", String.valueOf(bites + 1)));
        } else {
            instance.setBlock(pos, Block.AIR);
        }
    }

    /** CakeBlock.getOutputSignal: (7 - bites) * 2. */
    public static int comparatorOutput(Block block) {
        int bites = Integer.parseInt(block.getProperty("bites"));
        return (7 - bites) * 2;
    }
}
