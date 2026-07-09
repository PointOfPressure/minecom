package dev.pointofpressure.minecom.data;

import net.kyori.adventure.text.Component;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryButtonClickEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.registry.RegistryKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enchantments: component helpers used across combat/loot/durability, plus the
 * enchanting table (bookshelf-scaled offers, lapis + level costs).
 */
public final class Enchants {
    private Enchants() {}

    private static final Random RANDOM = new Random();

    public static int level(ItemStack stack, RegistryKey<Enchantment> enchantment) {
        if (stack == null || stack.isAir()) return 0;
        EnchantmentList list = stack.get(DataComponents.ENCHANTMENTS);
        return list == null ? 0 : list.level(enchantment);
    }

    public static int level(ItemStack stack, String path) {
        RegistryKey<Enchantment> key = byName(path);
        return key == null ? 0 : level(stack, key);
    }

    public static ItemStack with(ItemStack stack, RegistryKey<Enchantment> enchantment, int level) {
        EnchantmentList list = stack.get(DataComponents.ENCHANTMENTS);
        EnchantmentList updated = (list == null ? EnchantmentList.EMPTY : list).with(enchantment, level);
        return stack.with(builder -> builder.set(DataComponents.ENCHANTMENTS, updated));
    }

    /** Every vanilla enchantment, mapped to Minestom's registry constant (was a 9-entry subset). */
    public static RegistryKey<Enchantment> byName(String path) {
        return switch (VanillaData.path(path)) {
            case "sharpness" -> Enchantment.SHARPNESS;
            case "efficiency" -> Enchantment.EFFICIENCY;
            case "fortune" -> Enchantment.FORTUNE;
            case "silk_touch" -> Enchantment.SILK_TOUCH;
            case "unbreaking" -> Enchantment.UNBREAKING;
            case "protection" -> Enchantment.PROTECTION;
            case "looting" -> Enchantment.LOOTING;
            case "knockback" -> Enchantment.KNOCKBACK;
            case "power" -> Enchantment.POWER;
            case "fire_aspect" -> Enchantment.FIRE_ASPECT;
            case "vanishing_curse" -> Enchantment.VANISHING_CURSE;
            case "depth_strider" -> Enchantment.DEPTH_STRIDER;
            case "impaling" -> Enchantment.IMPALING;
            case "wind_burst" -> Enchantment.WIND_BURST;
            case "bane_of_arthropods" -> Enchantment.BANE_OF_ARTHROPODS;
            case "binding_curse" -> Enchantment.BINDING_CURSE;
            case "punch" -> Enchantment.PUNCH;
            case "riptide" -> Enchantment.RIPTIDE;
            case "flame" -> Enchantment.FLAME;
            case "blast_protection" -> Enchantment.BLAST_PROTECTION;
            case "frost_walker" -> Enchantment.FROST_WALKER;
            case "sweeping_edge" -> Enchantment.SWEEPING_EDGE;
            case "loyalty" -> Enchantment.LOYALTY;
            case "respiration" -> Enchantment.RESPIRATION;
            case "quick_charge" -> Enchantment.QUICK_CHARGE;
            case "fire_protection" -> Enchantment.FIRE_PROTECTION;
            case "luck_of_the_sea" -> Enchantment.LUCK_OF_THE_SEA;
            case "soul_speed" -> Enchantment.SOUL_SPEED;
            case "density" -> Enchantment.DENSITY;
            case "channeling" -> Enchantment.CHANNELING;
            case "breach" -> Enchantment.BREACH;
            case "piercing" -> Enchantment.PIERCING;
            case "lunge" -> Enchantment.LUNGE;
            case "mending" -> Enchantment.MENDING;
            case "feather_falling" -> Enchantment.FEATHER_FALLING;
            case "smite" -> Enchantment.SMITE;
            case "projectile_protection" -> Enchantment.PROJECTILE_PROTECTION;
            case "infinity" -> Enchantment.INFINITY;
            case "thorns" -> Enchantment.THORNS;
            case "multishot" -> Enchantment.MULTISHOT;
            case "lure" -> Enchantment.LURE;
            case "aqua_affinity" -> Enchantment.AQUA_AFFINITY;
            case "swift_sneak" -> Enchantment.SWIFT_SNEAK;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ enchanting table

    private record Session(Point pos, int[] costs) {}

    private static final Map<Inventory, Session> SESSIONS = new ConcurrentHashMap<>();
    private static Instance instance;

    public static void register(GlobalEventHandler events, Instance overworld) {
        instance = overworld;
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().equals("enchanting_table")) return;
            e.setBlockingItemUse(true);
            open(e.getPlayer(), e.getBlockPosition());
        });
        events.addListener(InventoryButtonClickEvent.class, Enchants::buttonClick);
    }

    private static void open(Player player, Point pos) {
        Inventory inv = new Inventory(InventoryType.ENCHANTMENT, Component.text("Enchant"));
        int shelves = countBookshelves(pos);
        int base = RANDOM.nextInt(8) + 1 + shelves / 2 + RANDOM.nextInt(shelves + 1);
        int[] costs = {Math.max(base / 3, 1), base * 2 / 3 + 1, Math.max(base, shelves * 2)};
        for (int i = 0; i < 3; i++) costs[i] = Math.min(30, costs[i]);
        SESSIONS.put(inv, new Session(pos, costs));
        player.openInventory(inv);
    }

    private static int countBookshelves(Point pos) {
        int count = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    if (instance.getBlock(pos.add(dx, dy, dz)).key().value().equals("bookshelf")) count++;
                }
            }
        }
        return Math.min(15, count);
    }

    private static void buttonClick(InventoryButtonClickEvent e) {
        if (!(e.getInventory() instanceof Inventory inv)) return;
        Session session = SESSIONS.get(inv);
        if (session == null) return;
        int slot = e.getButtonId();
        if (slot < 0 || slot > 2) return;
        Player player = e.getPlayer();
        ItemStack item = inv.getItemStack(0);
        ItemStack lapis = inv.getItemStack(1);
        int lapisNeeded = slot + 1;
        int requirement = session.costs()[slot];

        if (item.isAir() || item.get(DataComponents.ENCHANTMENTS) != null
                && !item.get(DataComponents.ENCHANTMENTS).enchantments().isEmpty()) return;
        if (player.getLevel() < requirement) return;
        if (lapis.isAir() || lapis.material() != Material.LAPIS_LAZULI || lapis.amount() < lapisNeeded) return;

        ItemStack enchanted = applyRandomEnchants(item, requirement);
        if (enchanted == item) return;
        inv.setItemStack(0, enchanted);
        inv.setItemStack(1, lapis.consume(lapisNeeded));
        dev.pointofpressure.minecom.survival.Experience.takeLevels(player, lapisNeeded);
    }

    /** Pick 1-2 enchantments applicable to the item, levels scaled by the cost. */
    private static ItemStack applyRandomEnchants(ItemStack item, int cost) {
        record Option(RegistryKey<Enchantment> key, int max) {}
        String key = item.material().key().value();
        List<Option> pool = new ArrayList<>();
        if (key.endsWith("_sword")) {
            pool.add(new Option(Enchantment.SHARPNESS, 5));
            pool.add(new Option(Enchantment.LOOTING, 3));
            pool.add(new Option(Enchantment.KNOCKBACK, 2));
            pool.add(new Option(Enchantment.UNBREAKING, 3));
        } else if (key.endsWith("_pickaxe") || key.endsWith("_axe") || key.endsWith("_shovel")) {
            pool.add(new Option(Enchantment.EFFICIENCY, 5));
            pool.add(new Option(Enchantment.UNBREAKING, 3));
            pool.add(RANDOM.nextInt(4) == 0
                    ? new Option(Enchantment.SILK_TOUCH, 1)
                    : new Option(Enchantment.FORTUNE, 3));
        } else if (key.endsWith("_helmet") || key.endsWith("_chestplate")
                || key.endsWith("_leggings") || key.endsWith("_boots")) {
            pool.add(new Option(Enchantment.PROTECTION, 4));
            pool.add(new Option(Enchantment.UNBREAKING, 3));
        } else if (key.equals("bow")) {
            pool.add(new Option(Enchantment.POWER, 5));
            pool.add(new Option(Enchantment.UNBREAKING, 3));
        } else {
            return item;
        }
        Option first = pool.get(RANDOM.nextInt(pool.size()));
        int level = Math.max(1, Math.min(first.max(), cost * first.max() / 30 + (RANDOM.nextInt(2))));
        ItemStack result = with(item, first.key(), level);
        if (RANDOM.nextInt(100) < 30 && pool.size() > 1) {
            Option second = pool.get(RANDOM.nextInt(pool.size()));
            if (second != first) {
                result = with(result, second.key(),
                        Math.max(1, Math.min(second.max(), cost * second.max() / 30)));
            }
        }
        return result;
    }
}
