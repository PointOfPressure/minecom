package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PumpkinBlock.useItemOn: shearing an uncarved pumpkin drops 4 pumpkin_seeds (carve/pumpkin.json:
 * a flat set_count(4), no roll) and replaces it with a carved_pumpkin facing away from the
 * player — clicking a side face carves toward that exact side; clicking the top/bottom face
 * uses the player's own horizontal facing (reversed), since there's no side to derive it from.
 */
public final class PumpkinCarving {
    private PumpkinCarving() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, PumpkinCarving::useOnBlock);
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        if (e.getItemStack().material() != Material.SHEARS) return;
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        if (!instance.getBlock(pos).key().value().equals("pumpkin")) return;

        Player player = e.getPlayer();
        String direction = faceString(e.getBlockFace());
        if (direction == null) { // top/bottom click: carve away from the player's own facing
            direction = Placement.opposite(Placement.horizontalFacing(player.getPosition().yaw()));
        }

        Random random = ThreadLocalRandom.current();
        double stepX = stepX(direction), stepZ = stepZ(direction);
        ItemEntity seeds = new ItemEntity(ItemStack.of(Material.PUMPKIN_SEEDS, 4));
        seeds.setInstance(instance, new net.minestom.server.coordinate.Pos(
                pos.x() + 0.5 + stepX * 0.65, pos.y() + 0.1, pos.z() + 0.5 + stepZ * 0.65));
        seeds.setVelocity(new Vec(0.05 * stepX + random.nextDouble() * 0.02, 0.05, 0.05 * stepZ + random.nextDouble() * 0.02));

        instance.setBlock(pos, Block.CARVED_PUMPKIN.withProperty("facing", direction));
        player.setItemInHand(e.getHand(),
                dev.pointofpressure.minecom.data.Items.damageItem(player, e.getItemStack(), 1));
    }

    /** BlockFace on the Y axis means "carve away from the player", not a literal facing. */
    private static String faceString(BlockFace face) {
        return switch (face) {
            case NORTH -> "north";
            case SOUTH -> "south";
            case EAST -> "east";
            case WEST -> "west";
            default -> null; // TOP / BOTTOM
        };
    }

    private static double stepX(String facing) {
        return switch (facing) { case "east" -> 1; case "west" -> -1; default -> 0; };
    }

    private static double stepZ(String facing) {
        return switch (facing) { case "south" -> 1; case "north" -> -1; default -> 0; };
    }
}
