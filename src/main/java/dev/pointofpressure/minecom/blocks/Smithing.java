package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.data.Recipes;
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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smithing table: ported from decompiled SmithingMenu / SmithingTransformRecipe / SmithingTrimRecipe
 * (26.2, cached under vanilla-src/). The four-slot station gating the netherite gear tier and armor
 * trims. Slot 0 = template, slot 1 = base, slot 2 = addition, slot 3 = result — the exact
 * SmithingMenu.{TEMPLATE,BASE,ADDITIONAL,RESULT}_SLOT layout.
 *
 * <p>The result is previewed by {@link Recipes#smithingResult} whenever an input slot changes
 * (SmithingMenu.createResult / slotsChanged) and covers both recipe kinds: a
 * {@code smithing_transform} (diamond gear + netherite ingot -> netherite gear, preserving
 * enchantments/damage/name/trim) and a {@code smithing_trim} (armor + trim material -> a trim
 * pattern stamped on). Taking the result consumes exactly 1 from each of the three input slots
 * (SmithingMenu.onTake shrinks slots 0, 1 and 2), same "take pulls from the cursor guard, no
 * per-slot mayPlace enforcement" precedent as every other station here.
 */
public final class Smithing {
    private Smithing() {}

    private static final Set<Inventory> OPEN = ConcurrentHashMap.newKeySet();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().equals("smithing_table")) return;
            e.setBlockingItemUse(true);
            Inventory inv = new Inventory(InventoryType.SMITHING, Component.text("Upgrade Gear"));
            OPEN.add(inv);
            e.getPlayer().openInventory(inv);
        });
        events.addListener(InventoryItemChangeEvent.class, e -> {
            if (e.getSlot() < 0 || e.getSlot() > 2) return;
            if (e.getInventory() instanceof Inventory inv && OPEN.contains(inv)) {
                inv.setItemStack(3, Recipes.smithingResult(
                        inv.getItemStack(0), inv.getItemStack(1), inv.getItemStack(2)));
            }
        });
        events.addListener(InventoryPreClickEvent.class, e -> {
            if (!(e.getInventory() instanceof Inventory inv) || !OPEN.contains(inv)) return;
            if (e.getSlot() != 3) return;
            e.setCancelled(true);
            ItemStack template = inv.getItemStack(0);
            ItemStack base = inv.getItemStack(1);
            ItemStack addition = inv.getItemStack(2);
            ItemStack result = Recipes.smithingResult(template, base, addition);
            if (result.isAir()) return;
            Player player = e.getPlayer();
            if (!inv.getCursorItem(player).isAir()) return;
            inv.setCursorItem(player, result);
            inv.setItemStack(0, template.consume(1));
            inv.setItemStack(1, base.consume(1));
            inv.setItemStack(2, addition.consume(1));
            inv.setItemStack(3, Recipes.smithingResult(
                    inv.getItemStack(0), inv.getItemStack(1), inv.getItemStack(2)));
        });
        events.addListener(InventoryCloseEvent.class, e -> {
            if (e.getInventory() instanceof Inventory inv && OPEN.remove(inv)) {
                for (int slot = 0; slot <= 2; slot++) {
                    ItemStack stack = inv.getItemStack(slot);
                    if (!stack.isAir()) Containers.giveOrDrop(e.getPlayer(), stack);
                }
            }
        });
    }
}
