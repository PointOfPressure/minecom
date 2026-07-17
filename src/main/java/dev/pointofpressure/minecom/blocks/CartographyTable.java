package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.survival.Maps;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryItemChangeEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cartography table: ported from decompiled CartographyTableMenu (26.2, cached under
 * vanilla-src/). Slot 0 takes a filled map, slot 1 takes paper/a glass pane/another filled
 * map, slot 2 previews the result of whichever combination is present:
 *
 * <ul>
 * <li><b>map + paper</b>: {@link Maps#tryCartographyZoom} — zoom out one scale level (capped,
 * not locked).
 * <li><b>map + glass pane</b>: {@link Maps#tryLock} — locks the map's saved data in place.
 * <li><b>map + another filled map</b>: {@link Maps#tryClone} — a plain 2-count clone sharing
 * the same saved-data id.
 * </ul>
 *
 * Taking the result always consumes exactly 1 from BOTH input slots regardless of which branch
 * fired (CartographyTableMenu's result slot {@code onTake}: {@code slots.get(0).remove(1)} then
 * {@code slots.get(1).remove(1)} unconditionally) — unlike the grindstone/stonecutter's
 * single-input consumption. Deliberate simplification, matching Grindstone.java/Anvils.java's
 * own documented scope: {@code Slot.mayPlace} item-type gating on slots 0/1 isn't enforced
 * (an invalid item just fails to produce {@code compute}'s branches and sits inert, same
 * "no separate placement-reject path" precedent as every other station in this codebase).
 */
public final class CartographyTable {
    private CartographyTable() {}

    private static final Set<Inventory> OPEN = ConcurrentHashMap.newKeySet();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().equals("cartography_table")) return;
            e.setBlockingItemUse(true);
            Inventory inv = new Inventory(InventoryType.CARTOGRAPHY, Component.text("Cartography Table"));
            OPEN.add(inv);
            e.getPlayer().openInventory(inv);
        });
        events.addListener(InventoryItemChangeEvent.class, e -> {
            if (e.getSlot() != 0 && e.getSlot() != 1) return;
            if (e.getInventory() instanceof Inventory inv && OPEN.contains(inv)) {
                inv.setItemStack(2, compute(inv.getItemStack(0), inv.getItemStack(1)));
            }
        });
        events.addListener(InventoryPreClickEvent.class, e -> {
            if (!(e.getInventory() instanceof Inventory inv) || !OPEN.contains(inv)) return;
            if (e.getSlot() != 2) return;
            e.setCancelled(true);
            ItemStack mapStack = inv.getItemStack(0);
            ItemStack additionalStack = inv.getItemStack(1);
            ItemStack result = compute(mapStack, additionalStack);
            if (result.isAir()) return;
            Player player = e.getPlayer();
            if (!inv.getCursorItem(player).isAir()) return;
            // MapItem.onCraftedPostProcess: the lock/scale mutation is deferred to the moment
            // the result is actually taken, not menu-preview recompute (Maps.tryLock's doc).
            if (additionalStack.material() == Material.GLASS_PANE) Maps.commitLock(result);
            inv.setCursorItem(player, result);
            inv.setItemStack(0, mapStack.consume(1));
            inv.setItemStack(1, additionalStack.consume(1));
            inv.setItemStack(2, compute(inv.getItemStack(0), inv.getItemStack(1)));
        });
        events.addListener(InventoryCloseEvent.class, e -> {
            if (e.getInventory() instanceof Inventory inv && OPEN.remove(inv)) {
                for (int slot = 0; slot <= 1; slot++) {
                    ItemStack stack = inv.getItemStack(slot);
                    if (!stack.isAir()) Containers.giveOrDrop(e.getPlayer(), stack);
                }
            }
        });
    }

    /** CartographyTableMenu.setupResultSlot, decompile-verified. */
    public static ItemStack compute(ItemStack mapStack, ItemStack additionalStack) {
        if (mapStack.isAir() || additionalStack.isAir() || !Maps.isFilledMap(mapStack)) return ItemStack.AIR;
        ItemStack result;
        if (additionalStack.material() == Material.PAPER) {
            result = Maps.tryCartographyZoom(mapStack);
        } else if (additionalStack.material() == Material.GLASS_PANE) {
            result = Maps.tryLock(mapStack);
        } else if (Maps.isFilledMap(additionalStack)) {
            result = Maps.tryClone(mapStack);
        } else {
            return ItemStack.AIR;
        }
        return result == null ? ItemStack.AIR : result;
    }
}
