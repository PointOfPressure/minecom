package dev.pointofpressure.minecom.redstone;

import dev.pointofpressure.minecom.blocks.Containers;
import dev.pointofpressure.minecom.blocks.Furnaces;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.timer.TaskSchedule;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hoppers: 5 slots, one item moved every 8 game ticks — push into the faced
 * container (furnace side loads fuel, top loads input), pull from the container
 * above, vacuum item entities above, disabled while redstone-powered.
 */
public final class Hoppers {
    private Hoppers() {}

    public static final Map<String, Inventory> HOPPERS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> COOLDOWN = new HashMap<>();
    private static final Map<String, Point> POSITIONS = new ConcurrentHashMap<>();
    private static Instance instance;

    public static void start(Instance overworld) {
        instance = overworld;
        MinecraftServer.getSchedulerManager().buildTask(Hoppers::tickAll)
                .repeat(TaskSchedule.tick(1)).schedule();
    }

    public static Inventory inventory(Point pos) {
        String key = Containers.posKey(pos);
        POSITIONS.put(key, pos);
        return HOPPERS.computeIfAbsent(key,
                k -> new Inventory(InventoryType.HOPPER, Component.text("Item Hopper")));
    }

    public static void remove(Instance inst, Point pos) {
        String key = Containers.posKey(pos);
        POSITIONS.remove(key);
        COOLDOWN.remove(key);
        Inventory inv = HOPPERS.remove(key);
        if (inv != null) Containers.spill(inst, pos, inv);
    }

    private static void tickAll() {
        if (instance == null) return;
        for (Map.Entry<String, Point> entry : POSITIONS.entrySet()) {
            String key = entry.getKey();
            Point pos = entry.getValue();
            if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
            Block block = instance.getBlock(pos);
            if (!block.key().value().equals("hopper")) continue;
            if (!"true".equals(block.getProperty("enabled"))) continue;
            int cd = COOLDOWN.merge(key, -1, Integer::sum);
            if (cd > 0) continue;
            COOLDOWN.put(key, 8);
            Inventory inv = HOPPERS.get(key);
            if (inv == null) continue;
            boolean acted = push(pos, block, inv);
            acted |= pull(pos, inv);
            acted |= vacuum(pos, inv);
            dev.pointofpressure.minecom.redstone.Redstone.markDirty(pos); // comparators re-read
        }
    }

    // ------------------------------------------------------------------ movement

    private static boolean push(Point pos, Block hopper, Inventory inv) {
        String facing = hopper.getProperty("facing");
        Point target = pos.add(Redstone.facingVec(facing));
        Block targetBlock = instance.getBlock(target);
        String key = targetBlock.key().value();

        int sourceSlot = -1;
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (!inv.getItemStack(slot).isAir()) { sourceSlot = slot; break; }
        }
        if (sourceSlot < 0) return false;
        ItemStack moving = inv.getItemStack(sourceSlot).withAmount(1);

        switch (key) {
            case "chest" -> {
                Inventory chest = Containers.CHESTS.computeIfAbsent(Containers.posKey(target),
                        k -> new Inventory(InventoryType.CHEST_3_ROW, Component.text("Chest")));
                if (!chest.addItemStack(moving)) return false;
            }
            case "hopper" -> {
                Inventory other = inventory(target);
                if (!other.addItemStack(moving)) return false;
            }
            case "dispenser", "dropper" -> {
                Inventory disp = Redstone.dispenserInventory(target);
                if (!disp.addItemStack(moving)) return false;
            }
            case "furnace" -> {
                Furnaces.State state = Furnaces.FURNACES.computeIfAbsent(Containers.posKey(target),
                        k -> new Furnaces.State());
                state.instance = instance;
                state.pos = target;
                int slot = facing.equals("down") ? 0 : 1; // top feeds input, sides feed fuel
                ItemStack existing = state.inv.getItemStack(slot);
                if (existing.isAir()) state.inv.setItemStack(slot, moving);
                else if (existing.material() == moving.material()
                        && existing.amount() < existing.material().maxStackSize()) {
                    state.inv.setItemStack(slot, existing.withAmount(existing.amount() + 1));
                } else return false;
            }
            default -> { return false; }
        }
        inv.setItemStack(sourceSlot, inv.getItemStack(sourceSlot).consume(1));
        return true;
    }

    private static boolean pull(Point pos, Inventory inv) {
        Point above = pos.add(0, 1, 0);
        Block block = instance.getBlock(above);
        Inventory source = switch (block.key().value()) {
            case "chest" -> Containers.CHESTS.get(Containers.posKey(above));
            case "hopper" -> HOPPERS.get(Containers.posKey(above));
            case "dispenser", "dropper" -> Redstone.DISPENSERS.get(Containers.posKey(above));
            case "furnace" -> {
                Furnaces.State s = Furnaces.FURNACES.get(Containers.posKey(above));
                yield s == null ? null : s.inv;
            }
            default -> null;
        };
        if (source == null) return false;
        boolean furnace = block.key().value().equals("furnace");
        for (int slot = 0; slot < source.getSize(); slot++) {
            if (furnace && slot != 2) continue; // only the output slot of furnaces
            ItemStack stack = source.getItemStack(slot);
            if (stack.isAir()) continue;
            if (inv.addItemStack(stack.withAmount(1))) {
                source.setItemStack(slot, stack.consume(1));
                return true;
            }
        }
        return false;
    }

    private static boolean vacuum(Point pos, Inventory inv) {
        for (var entity : instance.getEntities()) {
            if (!(entity instanceof ItemEntity item) || item.isRemoved()) continue;
            Point ep = entity.getPosition();
            if (ep.blockX() == pos.blockX() && ep.blockZ() == pos.blockZ()
                    && ep.blockY() >= pos.blockY() && ep.blockY() <= pos.blockY() + 1) {
                if (inv.addItemStack(item.getItemStack())) {
                    item.remove();
                    return true;
                }
            }
        }
        return false;
    }
}
