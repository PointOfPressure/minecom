package dev.pointofpressure.minecom.redstone;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.StateAdapter;
import dev.pointofpressure.minecom.blocks.Containers;
import dev.pointofpressure.minecom.data.Recipes;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Crafter (the 26.x auto-crafter). Ported from decompiled CrafterBlock /
 * CrafterBlockEntity (26.1.2): a rising redstone edge sets TRIGGERED and
 * schedules one craft attempt 4gt later; CRAFTING shows for 6gt; the result
 * ejects toward ORIENTATION.front() — merged into a container inventory if one
 * sits there, otherwise thrown as an item — then every non-empty slot shrinks
 * by one. Comparator signal = non-empty slots + locked slots. Clicking an
 * empty slot with an empty cursor toggles its lock; locked slots refuse items.
 * No quasi-connectivity (plain hasNeighborSignal, unlike dispensers).
 * Not modeled (AUDIT.md): recipe remainder items (e.g. bucket returns), the
 * craft fail/success level events, locked-slot client visuals (container
 * property packets), and vanilla's smallest-stack fill balancing on insert.
 */
public final class Crafters {
    private Crafters() {}

    static final Map<String, Inventory> CRAFTERS = new ConcurrentHashMap<>();
    private static final Map<String, Set<Integer>> LOCKED = new ConcurrentHashMap<>();
    private static final Map<Inventory, String> INV_KEYS = new ConcurrentHashMap<>();
    private static final int GRID_SLOTS = 9;

    public static Inventory inventory(Point pos) {
        String key = Containers.posKey(pos);
        return CRAFTERS.computeIfAbsent(key, k -> {
            Inventory inv = new Inventory(InventoryType.CRAFTER_3X3, Component.text("Crafter"));
            INV_KEYS.put(inv, k);
            return inv;
        });
    }

    public static void register(GlobalEventHandler events) {
        events.addListener(InventoryPreClickEvent.class, e -> {
            String key = e.getInventory() instanceof Inventory inv ? INV_KEYS.get(inv) : null;
            if (key == null || e.getSlot() < 0 || e.getSlot() >= GRID_SLOTS) return;
            Set<Integer> locked = LOCKED.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
            Inventory inv = (Inventory) e.getInventory();
            ItemStack inSlot = inv.getItemStack(e.getSlot());
            ItemStack cursor = e.getPlayer().getInventory().getCursorItem();
            if (locked.contains(e.getSlot())) {
                // locked slots accept nothing; an empty-cursor click unlocks
                e.setCancelled(true);
                if (cursor.isAir()) locked.remove(e.getSlot());
            } else if (inSlot.isAir() && cursor.isAir()) {
                e.setCancelled(true);
                locked.add(e.getSlot());
            }
            Redstone.markDirty(unkey(key));
        });
        Persist.register(persistence());
    }

    /** Crafter persistence (docs/PERSISTENCE.md): grid items + locked slots. */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "crafter";
            }

            @Override
            public void collect(Instance in, BiConsumer<Point, JsonObject> out) {
                CRAFTERS.forEach((key, inv) -> {
                    JsonObject o = new JsonObject();
                    o.add("items", Persist.writeItems(inv));
                    Set<Integer> locked = LOCKED.get(key);
                    if (locked != null && !locked.isEmpty()) {
                        JsonArray arr = new JsonArray();
                        locked.forEach(arr::add);
                        o.add("locked", arr);
                    }
                    out.accept(unkey(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                Inventory inv = inventory(pos);
                Persist.readItems(data.getAsJsonArray("items"), inv);
                if (data.has("locked")) {
                    Set<Integer> locked = LOCKED.computeIfAbsent(
                            Containers.posKey(pos), k -> ConcurrentHashMap.newKeySet());
                    data.getAsJsonArray("locked").forEach(el -> locked.add(el.getAsInt()));
                }
            }

            @Override
            public void wipe() {
                CRAFTERS.clear();
                LOCKED.clear();
                INV_KEYS.clear();
            }
        };
    }

    /** Redstone hook: rising edge triggers, falling edge resets (no QC). */
    static void evaluate(Instance instance, Point pos, Block block) {
        boolean powered = Redstone.activated(pos);
        boolean triggered = "true".equals(block.getProperty("triggered"));
        if (powered && !triggered) {
            instance.setBlock(pos, block.withProperty("triggered", "true"));
            Redstone.schedule(4, () -> craft(instance, pos));
        } else if (!powered && triggered) {
            instance.setBlock(pos, block
                    .withProperty("triggered", "false")
                    .withProperty("crafting", "false"));
        }
    }

    private static void craft(Instance instance, Point pos) {
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("crafter")) return;
        Inventory inv = CRAFTERS.get(Containers.posKey(pos));
        if (inv == null) return;
        Set<Integer> locked = LOCKED.getOrDefault(Containers.posKey(pos), Set.of());
        ItemStack[] grid = new ItemStack[GRID_SLOTS];
        for (int i = 0; i < GRID_SLOTS; i++) {
            grid[i] = locked.contains(i) ? ItemStack.AIR : inv.getItemStack(i);
        }
        ItemStack result = Recipes.matchCrafting(grid, 3);
        if (result.isAir()) return;

        instance.setBlock(pos, block.withProperty("crafting", "true"));
        Redstone.schedule(6, () -> {
            Block now = instance.getBlock(pos);
            if (now.key().value().equals("crafter") && "true".equals(now.getProperty("crafting"))) {
                instance.setBlock(pos, now.withProperty("crafting", "false"));
            }
        });

        eject(instance, pos, block, result);
        for (int i = 0; i < GRID_SLOTS; i++) {
            ItemStack s = inv.getItemStack(i);
            if (!s.isAir()) inv.setItemStack(i, s.consume(1));
        }
        Redstone.neighborsChanged(pos); // comparators re-read the fill signal
    }

    /** front() of the ORIENTATION property — the segment before the underscore. */
    private static Vec front(Block crafter) {
        String orientation = crafter.getProperty("orientation");
        String name = orientation == null ? "north" : orientation.substring(0, orientation.indexOf('_'));
        return Redstone.facingVec(name);
    }

    private static void eject(Instance instance, Point pos, Block crafter, ItemStack result) {
        Vec dir = front(crafter);
        Point frontPos = pos.add(dir);
        Inventory target = containerAt(instance, frontPos);
        ItemStack remaining = result;
        if (target != null) {
            remaining = insert(target, remaining);
        }
        if (!remaining.isAir()) {
            var item = new ItemEntity(remaining);
            item.setPickupDelay(java.time.Duration.ofMillis(500));
            item.setInstance(instance, new Vec(
                    pos.blockX() + 0.5 + dir.x() * 0.7,
                    pos.blockY() + 0.5 + dir.y() * 0.7,
                    pos.blockZ() + 0.5 + dir.z() * 0.7));
            item.setVelocity(dir.mul(2).add(0, 0.5, 0));
        }
    }

    /** Known session container inventories at a position (chest family, hopper, dispenser, crafter). */
    private static Inventory containerAt(Instance instance, Point pos) {
        String key = instance.getBlock(pos).key().value();
        String posKey = Containers.posKey(pos);
        if (key.equals("chest") || key.equals("trapped_chest") || key.equals("barrel")) {
            return Containers.CHESTS.get(posKey);
        }
        if (key.endsWith("shulker_box")) {
            return dev.pointofpressure.minecom.blocks.ShulkerBoxes.inventoryAt(pos);
        }
        if (key.equals("hopper")) return Hoppers.HOPPERS.get(posKey);
        if (key.equals("dispenser") || key.equals("dropper")) return Redstone.DISPENSERS.get(posKey);
        if (key.equals("crafter")) return CRAFTERS.get(posKey);
        return null;
    }

    /** First-fit merge; a crafter target's locked slots are skipped. Returns what didn't fit. */
    private static ItemStack insert(Inventory target, ItemStack stack) {
        String targetKey = INV_KEYS.get(target);
        Set<Integer> locked = targetKey == null ? Set.of()
                : LOCKED.getOrDefault(targetKey, Set.of());
        int size = targetKey != null ? GRID_SLOTS : target.getSize();
        int amount = stack.amount();
        for (int i = 0; i < size && amount > 0; i++) {
            if (locked.contains(i)) continue;
            ItemStack existing = target.getItemStack(i);
            if (existing.isAir()) {
                int put = Math.min(amount, stack.material().maxStackSize());
                target.setItemStack(i, stack.withAmount(put));
                amount -= put;
            } else if (existing.material() == stack.material()
                    && existing.amount() < existing.material().maxStackSize()) {
                int put = Math.min(amount, existing.material().maxStackSize() - existing.amount());
                target.setItemStack(i, existing.withAmount(existing.amount() + put));
                amount -= put;
            }
        }
        if (targetKey != null) Redstone.markDirty(unkey(targetKey));
        return amount <= 0 ? ItemStack.AIR : stack.withAmount(amount);
    }

    /** CrafterBlockEntity.getRedstoneSignal: non-empty slots + locked slots. */
    static int comparatorOutput(Point pos) {
        Inventory inv = CRAFTERS.get(Containers.posKey(pos));
        Set<Integer> locked = LOCKED.getOrDefault(Containers.posKey(pos), Set.of());
        int count = 0;
        for (int i = 0; i < GRID_SLOTS; i++) {
            if (locked.contains(i) || (inv != null && !inv.getItemStack(i).isAir())) count++;
        }
        return count;
    }

    /** Containers.posKey is "x,y,z" — parse it back for dirty marking. */
    private static Point unkey(String key) {
        String[] parts = key.split(",");
        return new Vec(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }
}
