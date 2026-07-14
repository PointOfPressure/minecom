package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.data.Enchants;
import dev.pointofpressure.minecom.data.VanillaData;
import dev.pointofpressure.minecom.survival.Experience;
import net.kyori.adventure.text.Component;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerAnvilInputEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryProperty;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.registry.RegistryKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anvils: ported from decompiled AnvilMenu.createResult/onTake (26.2, cached under
 * vanilla-src/). Two independent operations, mutually exclusive per combine (real
 * vanilla checks raw-material repair FIRST and only falls back to same-item combine
 * if the addition isn't a valid repair material for the input):
 *
 * <ul>
 * <li><b>Raw-material repair</b> (e.g. iron ingots on an iron pickaxe): each material
 * item restores up to 1/4 max durability, price +1 per item consumed (item_repairable.json,
 * from the jar's item-component datagen report — Repairable.java), stops once full or
 * out of materials.
 * <li><b>Same-item combine</b>: durabilities add with a 12% bonus; enchantments merge
 * (equal levels bump one, higher level wins, capped at the enchantment's OWN real max
 * level — not a flat 5); price is anvil_cost (data-driven, per enchantment.json) times
 * the merged level, halved (min 1) when the addition is an enchanted book; incompatible
 * enchantments (exclusive_set clashes) are dropped with a +1 price penalty each rather
 * than blocking the whole combine, UNLESS every merged enchantment was incompatible.
 * </ul>
 *
 * Both branches add a rename (+1 price if the name differs from the current custom
 * name) and the "prior work penalty" tax: each item carries a REPAIR_COST component
 * added as a flat tax on top of price (but ONLY if price&gt;0 — a no-op click, e.g. two
 * pristine unenchanted items, costs and does nothing even if heavily taxed), and the
 * result's own REPAIR_COST becomes old*2+1 (0, 1, 3, 7, 15, 31, ...) unless the click
 * was rename-only. Total cost &gt;=40 refuses outside creative ("Too Expensive!") —
 * EXCEPT a rename-only edit, which is capped at a displayed 39 and never blocked.
 * Renaming uses Minestom's real (if undocumented outside listener/AnvilListener.java)
 * `PlayerAnvilInputEvent`, fired per keystroke from the client's anvil text field.
 *
 * <p>Deliberate simplifications: anvil block damage-on-use (12% chance per take to
 * crack toward chipped_anvil -&gt; damaged_anvil -&gt; destroyed) is not modeled — the anvil
 * itself never degrades. The rename-cost comparison uses the item's stored custom name
 * only (vanilla compares against the resolved default display name too, an edge case
 * only reachable by literally typing the item's own translated name). Books are
 * distinguished from enchanted books by Material identity, and enchantments always
 * live in DataComponents.ENCHANTMENTS (matching Enchants.java's convention elsewhere
 * in this codebase) rather than vanilla's separate STORED_ENCHANTMENTS for books.
 */
public final class Anvils {
    private Anvils() {}

    private static final int TOO_EXPENSIVE = 40;

    private static final class Session {
        final Player player;
        String pendingName;
        int cost;
        int repairItemCountCost;
        boolean onlyRenaming;

        Session(Player player) {
            this.player = player;
        }
    }

    public static final class AnvilInventory extends Inventory {
        AnvilInventory() {
            super(InventoryType.ANVIL, Component.text("Repair"));
        }

        void property(InventoryProperty property, int value) {
            sendProperty(property, (short) value);
        }
    }

    private static final Map<Inventory, Session> SESSIONS = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            String key = e.getBlock().key().value();
            if (!key.equals("anvil") && !key.equals("chipped_anvil") && !key.equals("damaged_anvil")) return;
            e.setBlockingItemUse(true);
            AnvilInventory inv = new AnvilInventory();
            SESSIONS.put(inv, new Session(e.getPlayer()));
            e.getPlayer().openInventory(inv);
        });
        events.addListener(net.minestom.server.event.inventory.InventoryItemChangeEvent.class, e -> {
            if (e.getSlot() != 0 && e.getSlot() != 1) return;
            if (e.getInventory() instanceof AnvilInventory inv && SESSIONS.containsKey(inv)) {
                recompute(inv);
            }
        });
        events.addListener(PlayerAnvilInputEvent.class, e -> {
            if (!(e.getInventory() instanceof AnvilInventory inv)) return;
            Session session = SESSIONS.get(inv);
            if (session == null) return;
            session.pendingName = e.getInput();
            recompute(inv);
        });
        events.addListener(InventoryPreClickEvent.class, e -> {
            if (!(e.getInventory() instanceof AnvilInventory inv) || !SESSIONS.containsKey(inv)) return;
            if (e.getSlot() != 2) return;
            e.setCancelled(true);
            take(inv, e.getPlayer());
        });
        events.addListener(InventoryCloseEvent.class, e -> {
            if (e.getInventory() instanceof AnvilInventory inv && SESSIONS.remove(inv) != null) {
                for (int slot = 0; slot <= 1; slot++) {
                    ItemStack stack = inv.getItemStack(slot);
                    if (!stack.isAir()) Containers.giveOrDrop(e.getPlayer(), stack);
                }
            }
        });
    }

    private static void recompute(AnvilInventory inv) {
        Session session = SESSIONS.get(inv);
        if (session == null) return;
        ItemStack input = inv.getItemStack(0);
        ItemStack addition = inv.getItemStack(1);
        boolean creative = session.player.getGameMode() == GameMode.CREATIVE;
        ComputeResult computed = compute(input, addition, session.pendingName, creative);
        session.cost = computed.cost();
        session.repairItemCountCost = computed.repairItemCountCost();
        session.onlyRenaming = computed.onlyRenaming();
        inv.setItemStack(2, computed.item());
        inv.property(InventoryProperty.ANVIL_REPAIR_COST, Math.max(0, session.cost));
    }

    /** AnvilMenu.mayPickup + onTake. */
    private static void take(AnvilInventory inv, Player player) {
        Session session = SESSIONS.get(inv);
        if (session == null) return;
        ItemStack result = inv.getItemStack(2);
        if (result.isAir() || session.cost <= 0) return;
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (!creative && player.getLevel() < session.cost) return;
        if (!inv.getCursorItem(player).isAir()) return;

        inv.setCursorItem(player, result);
        if (!creative) Experience.takeLevels(player, session.cost);

        ItemStack addition = inv.getItemStack(1);
        if (session.repairItemCountCost > 0) {
            if (!addition.isAir() && addition.amount() > session.repairItemCountCost) {
                inv.setItemStack(1, addition.consume(session.repairItemCountCost));
            } else {
                inv.setItemStack(1, ItemStack.AIR);
            }
        } else if (!session.onlyRenaming) {
            inv.setItemStack(1, ItemStack.AIR);
        }
        inv.setItemStack(0, ItemStack.AIR);
        inv.setItemStack(2, ItemStack.AIR);
        session.pendingName = null;
        session.cost = 0;
        session.repairItemCountCost = 0;
        session.onlyRenaming = false;
        inv.property(InventoryProperty.ANVIL_REPAIR_COST, 0);
    }

    public record ComputeResult(ItemStack item, int cost, int repairItemCountCost, boolean onlyRenaming) {}

    private static final ComputeResult EMPTY = new ComputeResult(ItemStack.AIR, 0, 0, false);

    /** AnvilMenu.createResult, ported. `pendingName` null = no rename requested this session. */
    public static ComputeResult compute(ItemStack input, ItemStack addition, String pendingName, boolean creative) {
        if (input.isAir()) return EMPTY;

        ItemStack result = input;
        int price = 0;
        long tax = repairCost(input) + repairCost(addition);
        int namingCost = 0;
        int repairItemCountCost = 0;

        if (!addition.isAir()) {
            boolean usingBook = addition.material() == Material.ENCHANTED_BOOK;
            String repairTag = VanillaData.itemRepairTag(input.material());
            Integer max = result.get(DataComponents.MAX_DAMAGE);
            boolean damageable = max != null && max > 0;

            if (damageable && repairTag != null && VanillaData.itemHasTag(addition.material(), repairTag)) {
                // raw-material repair (e.g. iron ingots on an iron pickaxe) — mutually exclusive
                // with same-item combine; takes priority whenever addition matches the tag.
                int damage = damageOf(result);
                int repairAmount = Math.min(damage, max / 4);
                if (repairAmount <= 0) return EMPTY;
                int count = 0;
                while (repairAmount > 0 && count < addition.amount()) {
                    damage -= repairAmount;
                    price++;
                    count++;
                    repairAmount = Math.min(damage, max / 4);
                }
                int finalDamage = damage;
                result = result.with(b -> b.set(DataComponents.DAMAGE, finalDamage));
                repairItemCountCost = count;
            } else {
                if (!usingBook && (result.material() != addition.material() || !damageable)) return EMPTY;

                if (damageable && !usingBook) {
                    int remaining1 = max - damageOf(input);
                    int remaining2 = max - damageOf(addition);
                    int additional = remaining2 + max * 12 / 100;
                    int resultDamage = Math.max(0, max - (remaining1 + additional));
                    if (resultDamage < damageOf(result)) {
                        result = result.with(b -> b.set(DataComponents.DAMAGE, resultDamage));
                        price += 2;
                    }
                }

                Map<RegistryKey<Enchantment>, Integer> enchantments = new LinkedHashMap<>();
                EnchantmentList currentList = result.get(DataComponents.ENCHANTMENTS);
                if (currentList != null) enchantments.putAll(currentList.enchantments());
                EnchantmentList addList = addition.get(DataComponents.ENCHANTMENTS);

                boolean anyCompatible = false;
                boolean anyIncompatible = false;
                if (addList != null) {
                    for (var entry : addList.enchantments().entrySet()) {
                        RegistryKey<Enchantment> key = entry.getKey();
                        int addLevel = entry.getValue();
                        int currentLevel = enchantments.getOrDefault(key, 0);
                        int level = currentLevel == addLevel ? addLevel + 1 : Math.max(addLevel, currentLevel);
                        Enchants.EnchantmentDef def = Enchants.def(key.key().value());

                        boolean compatible = def != null && VanillaData.itemHasTag(input.material(), def.supportedItems());
                        if (creative || input.material() == Material.ENCHANTED_BOOK) compatible = true;

                        for (var existing : enchantments.entrySet()) {
                            if (existing.getKey().equals(key)) continue;
                            Enchants.EnchantmentDef existingDef = Enchants.def(existing.getKey().key().value());
                            if (def == null || existingDef == null) continue;
                            if (!existing.getKey().equals(key) && !areCompatible(existingDef, def)) {
                                compatible = false;
                                price++;
                            }
                        }

                        if (!compatible) {
                            anyIncompatible = true;
                        } else {
                            anyCompatible = true;
                            int maxLevel = def != null ? def.maxLevel() : level;
                            if (level > maxLevel) level = maxLevel;
                            enchantments.put(key, level);
                            int fee = def != null ? def.anvilCost() : 1;
                            if (usingBook) fee = Math.max(1, fee / 2);
                            price += fee * level;
                            if (input.amount() > 1) price = 40;
                        }
                    }
                }
                if (anyIncompatible && !anyCompatible) return EMPTY;

                EnchantmentList newList = EnchantmentList.EMPTY;
                for (var e : enchantments.entrySet()) newList = newList.with(e.getKey(), e.getValue());
                EnchantmentList finalList = newList;
                result = result.with(b -> b.set(DataComponents.ENCHANTMENTS, finalList));
            }
        }

        if (pendingName != null && !pendingName.isBlank()) {
            String currentName = customNameOf(input);
            if (!pendingName.equals(currentName)) {
                namingCost = 1;
                price += namingCost;
                String finalName = pendingName;
                result = result.with(b -> b.set(DataComponents.CUSTOM_NAME, Component.text(finalName)));
            }
        } else if (input.get(DataComponents.CUSTOM_NAME) != null) {
            namingCost = 1;
            price += namingCost;
            result = result.with(b -> b.remove(DataComponents.CUSTOM_NAME));
        }

        int finalPrice = price <= 0 ? 0 : (int) Math.max(0, Math.min(Integer.MAX_VALUE, tax + price));
        if (price <= 0) result = ItemStack.AIR;

        boolean onlyRenaming = false;
        if (namingCost == price && namingCost > 0) {
            if (finalPrice >= TOO_EXPENSIVE) finalPrice = TOO_EXPENSIVE - 1;
            onlyRenaming = true;
        }
        if (finalPrice >= TOO_EXPENSIVE && !creative) result = ItemStack.AIR;

        if (!result.isAir()) {
            int baseCost = Math.max(repairCost(result), repairCost(addition));
            if (namingCost != price || namingCost == 0) baseCost = increasedRepairCost(baseCost);
            int finalBaseCost = baseCost;
            result = result.with(b -> b.set(DataComponents.REPAIR_COST, finalBaseCost));
        }
        return new ComputeResult(result, finalPrice, repairItemCountCost, onlyRenaming);
    }

    /** Enchantment.areCompatible: not itself, and not mutually exclusive either direction. */
    private static boolean areCompatible(Enchants.EnchantmentDef a, Enchants.EnchantmentDef b) {
        return !a.path().equals(b.path()) && !Enchants.exclusive(a, b);
    }

    private static int repairCost(ItemStack item) {
        if (item == null || item.isAir()) return 0;
        Integer v = item.get(DataComponents.REPAIR_COST);
        return v == null ? 0 : v;
    }

    private static int damageOf(ItemStack item) {
        Integer v = item.get(DataComponents.DAMAGE);
        return v == null ? 0 : v;
    }

    private static String customNameOf(ItemStack item) {
        var name = item.get(DataComponents.CUSTOM_NAME);
        return name == null ? null : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(name);
    }

    /** AnvilMenu.calculateIncreasedRepairCost: baseCost*2+1. */
    private static int increasedRepairCost(int baseCost) {
        return (int) Math.min(baseCost * 2L + 1L, Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------ test/back-compat helpers

    /** Same-item combine only (no rename, non-creative) — the pure function PlayTest exercises. */
    public static ItemStack combine(ItemStack base, ItemStack addition) {
        return compute(base, addition, null, false).item();
    }

    /** Raw combine price (tax + enchant/repair fees), independent of the 40+ "too expensive" cutoff. */
    public static int costOf(ItemStack base, ItemStack addition) {
        return compute(base, addition, null, false).cost();
    }
}
