package dev.pointofpressure.minecom.redstone;

import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.EntityIndex;
import dev.pointofpressure.minecom.TickPipeline;
import dev.pointofpressure.minecom.StateAdapter;
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
import java.util.function.BiConsumer;

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
        Persist.register(persistence());
        TickPipeline.register(TickPipeline.BLOCK_ENTITIES, "hoppers", Hoppers::tickAll);
    }

    /** Hopper persistence (docs/PERSISTENCE.md): items; cooldown restarts fresh. */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "hopper";
            }

            @Override
            public void collect(Instance in, BiConsumer<Point, JsonObject> out) {
                POSITIONS.forEach((key, pos) -> {
                    Inventory inv = HOPPERS.get(key);
                    if (inv == null) return;
                    JsonObject o = new JsonObject();
                    o.add("items", Persist.writeItems(inv));
                    out.accept(pos, o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                Persist.readItems(data.getAsJsonArray("items"), inventory(pos));
            }

            @Override
            public void wipe() {
                HOPPERS.clear();
                POSITIONS.clear();
                COOLDOWN.clear();
            }
        };
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
            case "chest", "barrel" -> {
                String title = key.equals("barrel") ? "Barrel" : "Chest";
                Inventory chest = Containers.CHESTS.computeIfAbsent(Containers.posKey(target),
                        k -> new Inventory(InventoryType.CHEST_3_ROW, Component.text(title)));
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
            case "furnace", "blast_furnace", "smoker" -> {
                Furnaces.State state = Furnaces.FURNACES.computeIfAbsent(Containers.posKey(target),
                        k -> new Furnaces.State(key));
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
            default -> {
                // AbstractMinecartContainer implements Container the same as a block
                // entity, so a hopper faces/pushes into a chest/hopper minecart sitting
                // on a rail at the target position exactly like any other container —
                // previously this codebase's hopper<->minecart interop was one-way
                // (only the minecart's own vacuum() picked up loose item entities; a
                // stationary hopper never noticed a cart's inventory at all).
                var cart = dev.pointofpressure.minecom.blocks.Minecarts.containerCartAt(instance, target);
                if (cart == null) return false;
                Inventory cartInv = dev.pointofpressure.minecom.blocks.Minecarts.cartInventory(cart);
                if (!cartInv.addItemStack(moving)) return false;
            }
        }
        inv.setItemStack(sourceSlot, inv.getItemStack(sourceSlot).consume(1));
        return true;
    }

    /**
     * Pulls one item into inv from whatever's directly above pos: a block container,
     * or (public — also called by Minecarts.java so a moving hopper minecart can pull
     * from a chest/hopper/dispenser/furnace it passes under, the other decompile-
     * flagged half of hopper<->minecart interop) a chest/hopper minecart entity
     * sitting there.
     */
    public static boolean pull(Point pos, Inventory inv) {
        Point above = pos.add(0, 1, 0);
        Block block = instance.getBlock(above);
        String key = block.key().value();
        Inventory source = switch (key) {
            case "chest", "barrel" -> Containers.CHESTS.get(Containers.posKey(above));
            case "hopper" -> HOPPERS.get(Containers.posKey(above));
            case "dispenser", "dropper" -> Redstone.DISPENSERS.get(Containers.posKey(above));
            case "furnace", "blast_furnace", "smoker" -> {
                Furnaces.State s = Furnaces.FURNACES.get(Containers.posKey(above));
                yield s == null ? null : s.inv;
            }
            default -> null;
        };
        if (source == null) {
            var cart = dev.pointofpressure.minecom.blocks.Minecarts.containerCartAt(instance, above);
            if (cart == null) return false;
            source = dev.pointofpressure.minecom.blocks.Minecarts.cartInventory(cart);
        }
        boolean furnace = key.equals("furnace") || key.equals("blast_furnace") || key.equals("smoker");
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
        for (var entity : EntityIndex.inChunk(instance, pos)) {
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
