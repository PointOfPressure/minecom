package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.survival.Experience;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.GameMode;
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
 * enchantment's own real max level — AnvilMenu.createResult clamps to
 * enchantment.getMaxLevel(), NOT a flat 5; Mending/Infinity/Multishot/Riptide
 * max at 1, Unbreaking/Looting/Fortune/Power at 3-5, etc.). Base cost is 2
 * levels + 1 per merged enchantment, PLUS AnvilMenu's real "prior work
 * penalty": each item carries a REPAIR_COST component that's added as a flat
 * tax on top of the next combine's price, then the result's own REPAIR_COST
 * becomes calculateIncreasedRepairCost(max(both inputs)) = old*2+1 — so an
 * item anvil-combined repeatedly gets exponentially more expensive to touch
 * again (0, 1, 3, 7, 15, 31, ...). Real vanilla also refuses (shows no
 * result) once total cost reaches 40+ outside creative ("Too Expensive!").
 * Renaming needs a client text packet Minestom doesn't expose; not
 * supported. Repairing with the tool's raw crafting material (e.g. iron
 * ingots on an iron pickaxe) is also not modeled — only same-item combining.
 */
public final class Anvils {
    private Anvils() {}

    private static final Set<Inventory> OPEN = ConcurrentHashMap.newKeySet();
    private static final int TOO_EXPENSIVE = 40;

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
                recompute(inv, e.getPlayer());
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
            boolean creative = player.getGameMode() == GameMode.CREATIVE;
            if (!creative && (player.getLevel() < cost || cost >= TOO_EXPENSIVE)) return;
            if (!inv.getCursorItem(player).isAir()) return;
            inv.setCursorItem(player, result);
            inv.setItemStack(0, ItemStack.AIR);
            inv.setItemStack(1, ItemStack.AIR);
            inv.setItemStack(2, ItemStack.AIR);
            if (!creative) Experience.takeLevels(player, cost);
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

    private static void recompute(Inventory inv, Player player) {
        ItemStack base = inv.getItemStack(0);
        ItemStack addition = inv.getItemStack(1);
        ItemStack result = combine(base, addition);
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (!result.isAir() && !creative && costOf(base, addition) >= TOO_EXPENSIVE) {
            result = ItemStack.AIR; // AnvilMenu.createResult: "Too Expensive!" hides the result entirely
        }
        inv.setItemStack(2, result);
    }

    /** AnvilMenu.createResult: base price + the REPAIR_COST "tax" carried by both inputs. */
    public static int costOf(ItemStack base, ItemStack addition) {
        if (base.isAir() || addition.isAir()) return 0;
        EnchantmentList added = addition.get(DataComponents.ENCHANTMENTS);
        int price = 2 + (added == null ? 0 : added.enchantments().size());
        int baseRepair = base.get(DataComponents.REPAIR_COST) == null ? 0 : base.get(DataComponents.REPAIR_COST);
        int addRepair = addition.get(DataComponents.REPAIR_COST) == null ? 0 : addition.get(DataComponents.REPAIR_COST);
        return price + baseRepair + addRepair;
    }

    /** AnvilMenu.calculateIncreasedRepairCost: baseCost*2+1, applied to the result on every combine. */
    private static int increasedRepairCost(ItemStack base, ItemStack addition) {
        int baseRepair = base.get(DataComponents.REPAIR_COST) == null ? 0 : base.get(DataComponents.REPAIR_COST);
        int addRepair = addition.get(DataComponents.REPAIR_COST) == null ? 0 : addition.get(DataComponents.REPAIR_COST);
        return Math.max(baseRepair, addRepair) * 2 + 1;
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
                var enchantment = MinecraftServer.getEnchantmentRegistry().get(entry.getKey());
                int realMax = enchantment == null ? 5 : enchantment.maxLevel();
                merged = merged.with(entry.getKey(), Math.min(realMax, newLevel));
            }
            EnchantmentList finalMerged = merged;
            result = result.with(b -> b.set(DataComponents.ENCHANTMENTS, finalMerged));
        }

        int newRepairCost = increasedRepairCost(base, addition);
        result = result.with(b -> b.set(DataComponents.REPAIR_COST, newRepairCost));
        return result;
    }
}
