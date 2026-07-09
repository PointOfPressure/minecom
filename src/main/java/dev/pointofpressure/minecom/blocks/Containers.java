package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.data.Recipes;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interactive blocks: crafting tables, furnaces, chests (per-position inventories,
 * in-memory), and door/trapdoor/fence-gate toggling.
 */
public final class Containers {
    private Containers() {}

    /** Chest inventories keyed by "x,y,z"; exposed for persistence. */
    public static final Map<String, Inventory> CHESTS = new ConcurrentHashMap<>();

    public static String posKey(Point p) {
        return p.blockX() + "," + p.blockY() + "," + p.blockZ();
    }

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, Containers::interact);
        Crafting.register(events);
        Furnaces.register(events);
    }

    private static void interact(PlayerBlockInteractEvent e) {
        if (e.getPlayer().isSneaking() && !e.getPlayer().getItemInMainHand().isAir()) return;
        Block block = e.getBlock();
        String key = block.key().value();
        Player player = e.getPlayer();
        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();

        switch (key) {
            case "crafting_table" -> {
                e.setBlockingItemUse(true);
                Crafting.open(player);
            }
            case "furnace" -> {
                e.setBlockingItemUse(true);
                Furnaces.open(player, instance, pos, block);
            }
            case "hopper" -> {
                e.setBlockingItemUse(true);
                player.openInventory(dev.pointofpressure.minecom.redstone.Hoppers.inventory(pos));
            }
            case "chest" -> {
                e.setBlockingItemUse(true);
                player.openInventory(CHESTS.computeIfAbsent(posKey(pos),
                        p -> new Inventory(InventoryType.CHEST_3_ROW, Component.text("Chest"))));
            }
            default -> {
                if ((key.endsWith("_door") && !key.equals("iron_door"))) {
                    e.setBlockingItemUse(true);
                    toggleDoor(instance, pos, block);
                } else if ((key.endsWith("_trapdoor") && !key.equals("iron_trapdoor"))
                        || key.endsWith("_fence_gate")) {
                    e.setBlockingItemUse(true);
                    instance.setBlock(pos, toggled(block));
                }
            }
        }
    }

    private static Block toggled(Block block) {
        boolean open = "true".equals(block.getProperty("open"));
        return block.withProperty("open", open ? "false" : "true");
    }

    private static void toggleDoor(Instance instance, Point pos, Block block) {
        instance.setBlock(pos, toggled(block));
        boolean upper = "upper".equals(block.getProperty("half"));
        Point otherPos = pos.add(0, upper ? -1 : 1, 0);
        Block other = instance.getBlock(otherPos);
        if (other.key().value().equals(block.key().value())) {
            instance.setBlock(otherPos, toggled(other));
        }
    }

    /** Called when a container block is broken: spill contents and forget state. */
    public static void onBlockRemoved(Instance instance, Point pos, Block block) {
        String key = block.key().value();
        if (key.equals("chest")) {
            Inventory inv = CHESTS.remove(posKey(pos));
            if (inv != null) spill(instance, pos, inv);
        } else if (key.equals("furnace")) {
            Furnaces.remove(instance, pos);
        } else if (key.equals("dispenser") || key.equals("dropper")) {
            var inv = dev.pointofpressure.minecom.redstone.Redstone.DISPENSERS.remove(posKey(pos));
            if (inv != null) spill(instance, pos, inv);
        } else if (key.equals("hopper")) {
            dev.pointofpressure.minecom.redstone.Hoppers.remove(instance, pos);
        } else if (key.equals("brewing_stand")) {
            Brewing.onRemoved(instance, pos);
        }
    }

    public static void spill(Instance instance, Point pos, Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItemStack(i);
            if (!stack.isAir()) BlockRules.dropAt(instance, pos, stack);
            inv.setItemStack(i, ItemStack.AIR);
        }
    }

    /** Give to inventory or drop at the player's feet if full. */
    static void giveOrDrop(Player player, ItemStack stack) {
        if (stack.isAir()) return;
        if (!player.getInventory().addItemStack(stack)) {
            dev.pointofpressure.minecom.survival.Survival.dropItem(player, stack, true);
        }
    }

    static ItemStack stackOf(Material mat, int amount) {
        return amount <= 0 ? ItemStack.AIR : ItemStack.of(mat, amount);
    }
}
