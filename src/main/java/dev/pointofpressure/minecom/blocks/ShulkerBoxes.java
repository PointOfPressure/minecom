package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.StateAdapter;
import net.kyori.adventure.text.Component;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * ShulkerBoxBlock/ShulkerBoxBlockEntity (decompile-verified): the one container in real vanilla
 * whose contents travel with the ITEM, not the block position. Placing a shulker_box item that
 * carries DataComponents.CONTAINER (a raw List&lt;ItemStack&gt; component) hydrates a fresh
 * 27-slot inventory from it. Breaking a non-empty one in survival (playerWillDestroy, gated on
 * !preventsBlockDrops — i.e. not a creative instamine) drops a shulker_box item with its current
 * contents packed right back into that same component instead of spilling them on the ground;
 * breaking an empty one, or a creative instamine of either, just discards/never-drops like any
 * other block, with no contents to lose. Comparator reads the same generic slot-fullness formula
 * as a chest (AbstractContainerMenu.getRedstoneSignalFromBlockEntity is the identical formula
 * under a different name). Not modelled: the open-lid physical obstruction check (a real shulker
 * box refuses to open if something's blocking its swing-open animation box) — this project opens
 * unconditionally, a narrow simplification of a rarely-hit placement edge case.
 */
public final class ShulkerBoxes {
    private ShulkerBoxes() {}

    private static final Map<String, Inventory> INVENTORIES = new ConcurrentHashMap<>();

    /** The shulker box inventory at this position, if one has ever been opened/placed there. */
    public static Inventory inventoryAt(Point pos) {
        return INVENTORIES.get(Containers.posKey(pos));
    }

    public static void register(GlobalEventHandler events) {
        Persist.register(persistence());
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (!e.getBlock().key().value().endsWith("shulker_box")) return;
            ItemStack used = e.getPlayer().getItemInHand(e.getHand());
            List<ItemStack> stored = used.get(DataComponents.CONTAINER);
            if (stored == null || stored.stream().allMatch(ItemStack::isAir)) return;
            Inventory inv = new Inventory(InventoryType.CHEST_3_ROW, Component.text("Shulker Box"));
            for (int i = 0; i < Math.min(stored.size(), inv.getSize()); i++) inv.setItemStack(i, stored.get(i));
            INVENTORIES.put(Containers.posKey(e.getBlockPosition()), inv);
        });

        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().endsWith("shulker_box")) return;
            e.setBlockingItemUse(true);
            Inventory inv = INVENTORIES.computeIfAbsent(Containers.posKey(e.getBlockPosition()),
                    p -> new Inventory(InventoryType.CHEST_3_ROW, Component.text("Shulker Box")));
            e.getPlayer().openInventory(inv);
        });
    }

    /**
     * Called from BlockRules.onBreak in place of the generic loot-table drop for shulker boxes —
     * always forgets the position's inventory; only spawns a componented replacement item when
     * {@code drop} is true (survival-harvested) and it actually held something.
     */
    public static void onBroken(Instance instance, Point pos, Block block, boolean drop) {
        Inventory inv = INVENTORIES.remove(Containers.posKey(pos));
        if (!drop) return;
        Material mat = block.registry().material();
        if (mat == null) return;
        // Minestom's CONTAINER component is a plain List<ItemStack> with no per-entry slot
        // index (unlike real vanilla's sparse {slot, item} format), so slot position can only
        // survive the round trip if list index IS slot index — every slot goes in, air or not.
        List<ItemStack> contents = new ArrayList<>();
        boolean anyItems = false;
        if (inv != null) {
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack s = inv.getItemStack(i);
                contents.add(s);
                if (!s.isAir()) anyItems = true;
            }
        }
        ItemStack out = anyItems ? ItemStack.of(mat).with(DataComponents.CONTAINER, contents) : ItemStack.of(mat);
        BlockRules.dropAt(instance, pos, out);
    }

    /** Shulker box persistence (docs/PERSISTENCE.md): the 27-slot inventory, same shape as chests. */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "shulker_box";
            }

            @Override
            public void collect(Instance in, BiConsumer<Point, JsonObject> out) {
                INVENTORIES.forEach((key, inv) -> {
                    JsonObject o = new JsonObject();
                    o.add("items", Persist.writeItems(inv));
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                Inventory inv = new Inventory(InventoryType.CHEST_3_ROW, Component.text("Shulker Box"));
                Persist.readItems(data.getAsJsonArray("items"), inv);
                INVENTORIES.put(Persist.posKey(pos), inv);
            }

            @Override
            public void wipe() {
                INVENTORIES.clear();
            }
        };
    }
}
