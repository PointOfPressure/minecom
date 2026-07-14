package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.data.Enchants;
import net.kyori.adventure.text.Component;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.registry.RegistryKey;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grindstone: ported from decompiled GrindstoneMenu (26.2, cached under
 * vanilla-src/). Two independent operations depending on what's in the two input
 * slots:
 *
 * <ul>
 * <li><b>Single item</b>: pure disenchant. Strips every NON-curse enchantment;
 * curses (tags/enchantment/curse.json — Binding/Vanishing) are kept. An unenchanted
 * item alone produces no result at all (grindstone only "does" something).
 * <li><b>Two identical items</b>: repair-merge like the anvil, but a flat 5% bonus
 * (not the anvil's 12%) and NO enchantment-compatibility/anvil-cost math — every
 * non-curse enchantment from the second item just upgrades onto the first
 * (max(existing,new) level, no cap-at-real-max clamp), then the same curse-stripping
 * pass runs on the merged result.
 * </ul>
 *
 * On taking the result, both non-curse enchantments removed are refunded as XP —
 * NOT via anvil_cost, but each enchantment's own table min_cost(level)
 * (EnchantmentHelper.getMinCost) summed across both input items, then
 * half-to-full randomized: ceil(sum/2) + random(ceil(sum/2)).
 */
public final class Grindstone {
    private Grindstone() {}

    private static final Set<Inventory> OPEN = ConcurrentHashMap.newKeySet();
    private static final Random RANDOM = new Random();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().equals("grindstone")) return;
            e.setBlockingItemUse(true);
            Inventory inv = new Inventory(InventoryType.GRINDSTONE, Component.text("Repair & Disenchant"));
            OPEN.add(inv);
            e.getPlayer().openInventory(inv);
        });
        events.addListener(net.minestom.server.event.inventory.InventoryItemChangeEvent.class, e -> {
            if (e.getSlot() != 0 && e.getSlot() != 1) return;
            if (e.getInventory() instanceof Inventory inv && OPEN.contains(inv)) {
                inv.setItemStack(2, compute(inv.getItemStack(0), inv.getItemStack(1)));
            }
        });
        events.addListener(InventoryPreClickEvent.class, e -> {
            if (!(e.getInventory() instanceof Inventory inv) || !OPEN.contains(inv)) return;
            if (e.getSlot() != 2) return;
            e.setCancelled(true);
            ItemStack result = inv.getItemStack(2);
            if (result.isAir()) return;
            Player player = e.getPlayer();
            if (!inv.getCursorItem(player).isAir()) return;
            ItemStack a = inv.getItemStack(0);
            ItemStack b = inv.getItemStack(1);
            inv.setCursorItem(player, result);
            int xp = experienceAmount(a, b);
            inv.setItemStack(0, ItemStack.AIR);
            inv.setItemStack(1, ItemStack.AIR);
            inv.setItemStack(2, ItemStack.AIR);
            if (xp > 0) {
                dev.pointofpressure.minecom.survival.Experience.orb(player.getInstance(), player.getPosition(), xp);
            }
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

    /** GrindstoneMenu.computeResult. */
    public static ItemStack compute(ItemStack input, ItemStack addition) {
        boolean hasAny = !input.isAir() || !addition.isAir();
        if (!hasAny) return ItemStack.AIR;
        if (input.amount() > 1 || addition.amount() > 1) return ItemStack.AIR;
        boolean both = !input.isAir() && !addition.isAir();
        if (!both) {
            ItemStack item = input.isAir() ? addition : input;
            return hasAnyEnchantments(item) ? removeNonCurses(item) : ItemStack.AIR;
        }
        return mergeItems(input, addition);
    }

    /** GrindstoneMenu.mergeItems: 5% bonus durability merge (anvil uses 12%), then curse-only enchant strip. */
    private static ItemStack mergeItems(ItemStack input, ItemStack addition) {
        if (input.material() != addition.material()) return ItemStack.AIR;

        Integer maxDamage = input.get(DataComponents.MAX_DAMAGE);
        boolean damageable = maxDamage != null && maxDamage > 0;
        int count = 1;
        if (!damageable) {
            if (input.maxStackSize() < 2 || !input.isSimilar(addition)) return ItemStack.AIR;
            count = 2;
        }

        ItemStack result = input.withAmount(count);
        if (damageable) {
            Integer additionMax = addition.get(DataComponents.MAX_DAMAGE);
            int durability = Math.max(maxDamage, additionMax == null ? 0 : additionMax);
            int remaining1 = maxDamage - damageOf(input);
            int remaining2 = (additionMax == null ? maxDamage : additionMax) - damageOf(addition);
            int remaining = remaining1 + remaining2 + durability * 5 / 100;
            int newDamage = Math.max(0, durability - remaining);
            int finalDurability = durability;
            result = result.with(b -> {
                b.set(DataComponents.MAX_DAMAGE, finalDurability);
                b.set(DataComponents.DAMAGE, newDamage);
            });
        }

        result = mergeEnchantsFrom(result, addition);
        return removeNonCurses(result);
    }

    /** GrindstoneMenu.mergeEnchantsFrom: upgrade every non-curse (or not-yet-present curse) enchantment. */
    private static ItemStack mergeEnchantsFrom(ItemStack target, ItemStack source) {
        EnchantmentList sourceList = source.get(DataComponents.ENCHANTMENTS);
        if (sourceList == null || sourceList.enchantments().isEmpty()) return target;
        EnchantmentList targetList = target.get(DataComponents.ENCHANTMENTS);
        EnchantmentList merged = targetList == null ? EnchantmentList.EMPTY : targetList;
        for (var entry : sourceList.enchantments().entrySet()) {
            RegistryKey<Enchantment> key = entry.getKey();
            Enchants.EnchantmentDef def = Enchants.def(key.key().value());
            int currentLevel = merged.level(key);
            if (def == null || !Enchants.isCurse(def) || currentLevel == 0) {
                merged = merged.with(key, Math.max(currentLevel, entry.getValue()));
            }
        }
        EnchantmentList finalMerged = merged;
        return target.with(b -> b.set(DataComponents.ENCHANTMENTS, finalMerged));
    }

    /** GrindstoneMenu.removeNonCursesFrom: strip non-curses, transmute an emptied enchanted book to a plain book,
     * REPAIR_COST climbs by the (post-strip, curses-only) enchantment count via the anvil's doubling formula. */
    private static ItemStack removeNonCurses(ItemStack item) {
        EnchantmentList list = item.get(DataComponents.ENCHANTMENTS);
        EnchantmentList kept = EnchantmentList.EMPTY;
        if (list != null) {
            for (var entry : list.enchantments().entrySet()) {
                Enchants.EnchantmentDef def = Enchants.def(entry.getKey().key().value());
                if (def != null && Enchants.isCurse(def)) kept = kept.with(entry.getKey(), entry.getValue());
            }
        }
        EnchantmentList finalKept = kept;
        ItemStack result = item.with(b -> b.set(DataComponents.ENCHANTMENTS, finalKept));
        if (result.material() == Material.ENCHANTED_BOOK && kept.enchantments().isEmpty()) {
            result = result.withMaterial(Material.BOOK);
        }
        int repairCost = 0;
        for (int i = 0; i < kept.enchantments().size(); i++) repairCost = repairCost * 2 + 1;
        int finalRepairCost = repairCost;
        result = result.with(b -> b.set(DataComponents.REPAIR_COST, finalRepairCost));
        return result;
    }

    private static boolean hasAnyEnchantments(ItemStack item) {
        EnchantmentList list = item.get(DataComponents.ENCHANTMENTS);
        return list != null && !list.enchantments().isEmpty();
    }

    private static int damageOf(ItemStack item) {
        Integer v = item.get(DataComponents.DAMAGE);
        return v == null ? 0 : v;
    }

    /** GrindstoneResultSlot.getExperienceAmount: sum of both items' non-curse enchant XP values, half-to-full random. */
    private static int experienceAmount(ItemStack a, ItemStack b) {
        int amount = experienceFromItem(a) + experienceFromItem(b);
        if (amount <= 0) return 0;
        int half = (int) Math.ceil(amount / 2.0);
        return half + RANDOM.nextInt(half);
    }

    private static int experienceFromItem(ItemStack item) {
        EnchantmentList list = item.get(DataComponents.ENCHANTMENTS);
        if (list == null) return 0;
        int amount = 0;
        for (var entry : list.enchantments().entrySet()) {
            Enchants.EnchantmentDef def = Enchants.def(entry.getKey().key().value());
            if (def != null && !Enchants.isCurse(def)) amount += Enchants.xpValue(def, entry.getValue());
        }
        return amount;
    }
}
