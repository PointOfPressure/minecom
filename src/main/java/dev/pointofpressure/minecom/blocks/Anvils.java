package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.survival.Experience;
import net.kyori.adventure.text.Component;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryClickEvent;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.component.EnchantmentList;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anvils: combine two of the same item — durabilities add with a 12% bonus and
 * enchantments merge (equal levels bump one, higher level wins, capped at each
 * enchantment's found max). Costs 2 levels + 1 per merged enchantment.
 * Renaming needs a client text packet Minestom doesn't expose; not supported.
 */
public final class Anvils {
    private Anvils() {}

    private static final Set<Inventory> OPEN = ConcurrentHashMap.newKeySet();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            String key = e.getBlock().key().value();
            if (!key.equals("anvil") && !key.equals("chipped_anvil") && !key.equals("damaged_anvil")) return;
            e.setBlockingItemUse(true);
            Inventory inv = new Inventory(InventoryType.ANVIL, Component.text("Repair"));
            OPEN.add(inv);
            e.getPlayer().openInventory(inv);
        });
        events.addListener(InventoryClickEvent.class, e -> {
            if (e.getInventory() instanceof Inventory inv && OPEN.contains(inv) && e.getSlot() != 2) {
                recompute(inv);
            }
        });
        events.addListener(InventoryPreClickEvent.class, e -> {
            if (!(e.getInventory() instanceof Inventory inv) || !OPEN.contains(inv)) return;
            if (e.getSlot() != 2) return;
            e.setCancelled(true);
            ItemStack result = inv.getItemStack(2);
            if (result.isAir()) return;
            Player player = e.getPlayer();
            int cost = costOf(inv.getItemStack(0), inv.getItemStack(1));
            if (player.getLevel() < cost) return;
            if (!inv.getCursorItem(player).isAir()) return;
            inv.setCursorItem(player, result);
            inv.setItemStack(0, ItemStack.AIR);
            inv.setItemStack(1, ItemStack.AIR);
            inv.setItemStack(2, ItemStack.AIR);
            Experience.takeLevels(player, cost);
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

    private static void recompute(Inventory inv) {
        inv.setItemStack(2, combine(inv.getItemStack(0), inv.getItemStack(1)));
    }

    private static int costOf(ItemStack base, ItemStack addition) {
        EnchantmentList added = addition.isAir() ? null : addition.get(DataComponents.ENCHANTMENTS);
        return 2 + (added == null ? 0 : added.enchantments().size());
    }

    /** Anvil combine result, or AIR when the inputs don't merge. */
    public static ItemStack combine(ItemStack base, ItemStack addition) {
        if (base.isAir() || addition.isAir()) return ItemStack.AIR;
        if (base.material() != addition.material()) return ItemStack.AIR;

        ItemStack result = base;

        Integer max = base.get(DataComponents.MAX_DAMAGE);
        if (max != null && max > 0) {
            int baseDamage = base.get(DataComponents.DAMAGE) == null ? 0 : base.get(DataComponents.DAMAGE);
            int addDamage = addition.get(DataComponents.DAMAGE) == null ? 0 : addition.get(DataComponents.DAMAGE);
            int baseLeft = max - baseDamage;
            int addLeft = max - addDamage;
            int combined = Math.min(max, baseLeft + addLeft + max * 12 / 100);
            int newDamage = Math.max(0, max - combined);
            result = result.with(b -> b.set(DataComponents.DAMAGE, newDamage));
        }

        EnchantmentList baseEnch = base.get(DataComponents.ENCHANTMENTS);
        EnchantmentList addEnch = addition.get(DataComponents.ENCHANTMENTS);
        if (addEnch != null && !addEnch.enchantments().isEmpty()) {
            EnchantmentList merged = baseEnch == null ? EnchantmentList.EMPTY : baseEnch;
            for (var entry : addEnch.enchantments().entrySet()) {
                int baseLevel = merged.level(entry.getKey());
                int addLevel = entry.getValue();
                int newLevel = baseLevel == addLevel ? baseLevel + 1 : Math.max(baseLevel, addLevel);
                merged = merged.with(entry.getKey(), Math.min(5, newLevel));
            }
            EnchantmentList finalMerged = merged;
            result = result.with(b -> b.set(DataComponents.ENCHANTMENTS, finalMerged));
        }
        return result;
    }
}
