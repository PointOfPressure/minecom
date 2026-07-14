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

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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

    // ------------------------------------------------------------------ data-driven enchantment defs
    //
    // Parsed from the bundled data/minecraft/enchantment/*.json (26.2 made enchantments
    // data-driven; see EnchantmentDefinition/DataDrivenEnchantment in the decompiled
    // reference). Cost curves are base + (level-1)*perLevelAboveFirst
    // (EnchantmentDefinition.Cost#calculate), matching the "per_level_above_first"
    // field name and confirmed against decompiled EnchantmentHelper's offer-generation
    // callers, which pass the level being considered into Cost#calculate this way.

    public record Cost(int base, int perLevelAboveFirst) {
        static Cost of(JsonObject o) {
            return new Cost(o.get("base").getAsInt(), o.get("per_level_above_first").getAsInt());
        }

        public int at(int level) {
            return base + (level - 1) * perLevelAboveFirst;
        }
    }

    public record EnchantmentDef(
            String path, int weight, int anvilCost, int maxLevel,
            Cost minCost, Cost maxCost, String supportedItems, String primaryItems,
            String exclusiveSet, Set<String> slots) {
    }

    private static final Map<String, EnchantmentDef> DEFS = new LinkedHashMap<>();

    static void index() {
        for (var entry : VanillaData.enchantments.entrySet()) {
            String path = VanillaData.path(entry.getKey());
            JsonObject o = entry.getValue().getAsJsonObject();
            String supported = o.get("supported_items").getAsString();
            DEFS.put(path, new EnchantmentDef(
                    path,
                    o.get("weight").getAsInt(),
                    o.get("anvil_cost").getAsInt(),
                    o.get("max_level").getAsInt(),
                    Cost.of(o.getAsJsonObject("min_cost")),
                    Cost.of(o.getAsJsonObject("max_cost")),
                    supported,
                    o.has("primary_items") ? o.get("primary_items").getAsString() : supported,
                    o.has("exclusive_set") ? o.get("exclusive_set").getAsString() : null,
                    java.util.stream.StreamSupport.stream(o.getAsJsonArray("slots").spliterator(), false)
                            .map(com.google.gson.JsonElement::getAsString)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet())));
        }
    }

    public static EnchantmentDef def(String path) {
        return DEFS.get(VanillaData.path(path));
    }

    /** All 43 data-driven enchantment defs, insertion order (jar directory listing order). */
    public static java.util.Collection<EnchantmentDef> allDefs() {
        return DEFS.values();
    }

    /** enchantment/*.json "supported_items" tag membership (item can carry it at all, e.g. via anvil books). */
    public static boolean supportsItem(EnchantmentDef def, ItemStack item) {
        return VanillaData.itemHasTag(item.material(), def.supportedItems());
    }

    /** "primary_items" tag membership (item can roll it from the enchanting table / villager trades). */
    public static boolean isPrimaryItem(EnchantmentDef def, ItemStack item) {
        return VanillaData.itemHasTag(item.material(), def.primaryItems());
    }

    /** tags/enchantment/in_enchanting_table.json — the pool the table draws offers from. */
    public static boolean inEnchantingTable(EnchantmentDef def) {
        return VanillaData.enchantmentTag("in_enchanting_table").contains("minecraft:" + def.path());
    }

    /** tags/enchantment/curse.json — Grindstone.removeNonCursesFrom keeps these, Anvils/table skip refunding them. */
    public static boolean isCurse(EnchantmentDef def) {
        return VanillaData.enchantmentTag("curse").contains("minecraft:" + def.path());
    }

    /** Enchantment level -> table min-cost (used by GrindstoneMenu.getExperienceFromItem, NOT anvil_cost). */
    public static int xpValue(EnchantmentDef def, int level) {
        return def.minCost().at(level);
    }

    /** True if the two enchantments share an exclusive_set tag (mutually exclusive, e.g. Sharpness/Smite/Bane). */
    public static boolean exclusive(EnchantmentDef a, EnchantmentDef b) {
        if (a.exclusiveSet() == null || b.exclusiveSet() == null) return false;
        if (a.exclusiveSet().equals(b.exclusiveSet())) return true;
        return VanillaData.enchantmentTag(a.exclusiveSet()).contains("minecraft:" + b.path())
                || VanillaData.enchantmentTag(b.exclusiveSet()).contains("minecraft:" + a.path());
    }

    // ------------------------------------------------------------------ enchanting table
    //
    // Ported from decompiled EnchantmentHelper.getEnchantmentCost/selectEnchantment/
    // getAvailableEnchantmentResults, EnchantmentMenu.slotsChanged/getEnchantmentList/
    // clickMenuButton, Player.getEnchantmentSeed/onEnchantmentPerformed and
    // EnchantingTableBlock.BOOKSHELF_OFFSETS/isValidBookShelf (26.2, cached under
    // vanilla-src/). Enchantments themselves are data-driven (see EnchantmentDef
    // above); this section ports the offer-generation ALGORITHM, which is vanilla
    // Java behavior, not bundled data.
    //
    // Deliberate simplification: the client-visible "clue" (Standard Galactic
    // Alphabet hint icons, EnchantmentMenu.enchantClue/levelClue) is not sent — it's
    // a pure cosmetic preview recomputed identically from the same seed at click
    // time, so it has no gameplay effect and isn't observable by a headless test.
    // ENCHANTMENT_TABLE_SEED and the three level-requirement properties ARE sent
    // (real client UI needs them to show "8 / 16 / 24" and enable/disable buttons).
    // Max-stack-1 on the item slot (EnchantmentMenu's Slot override) also isn't
    // enforced — same class of simplification Anvils.java already documents.

    public static final class EnchantInventory extends Inventory {
        EnchantInventory() {
            super(InventoryType.ENCHANTMENT, Component.text("Enchant"));
        }

        void property(net.minestom.server.inventory.InventoryProperty property, int value) {
            sendProperty(property, (short) value);
        }
    }

    private record Session(Point pos, Player player, int[] costs) {
        Session(Point pos, Player player) {
            this(pos, player, new int[3]);
        }
    }

    record Offer(EnchantmentDef def, int level) {}

    private static final Map<Inventory, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SEEDS = new ConcurrentHashMap<>();
    private static final Random SEED_SOURCE = new Random();
    private static Instance instance;

    public static void register(GlobalEventHandler events, Instance overworld) {
        instance = overworld;
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().equals("enchanting_table")) return;
            e.setBlockingItemUse(true);
            open(e.getPlayer(), e.getBlockPosition());
        });
        events.addListener(net.minestom.server.event.inventory.InventoryItemChangeEvent.class, e -> {
            if (e.getSlot() != 0 || !(e.getInventory() instanceof EnchantInventory inv)) return;
            Session session = SESSIONS.get(inv);
            if (session != null) recomputeCosts(inv, session);
        });
        events.addListener(InventoryButtonClickEvent.class, Enchants::buttonClick);
        events.addListener(net.minestom.server.event.inventory.InventoryCloseEvent.class, e -> {
            if (!(e.getInventory() instanceof EnchantInventory inv)) return;
            Session session = SESSIONS.remove(inv);
            if (session == null) return;
            for (int slot = 0; slot <= 1; slot++) {
                ItemStack stack = inv.getItemStack(slot);
                if (!stack.isAir()) dev.pointofpressure.minecom.blocks.Containers.giveOrDrop(e.getPlayer(), stack);
            }
        });
    }

    private static void open(Player player, Point pos) {
        EnchantInventory inv = new EnchantInventory();
        SESSIONS.put(inv, new Session(pos, player));
        player.openInventory(inv);
        inv.property(net.minestom.server.inventory.InventoryProperty.ENCHANTMENT_TABLE_SEED, seed(player));
    }

    /** Player.getEnchantmentSeed: persisted, defaults to a fresh unpredictable value. */
    public static int seed(Player player) {
        return SEEDS.computeIfAbsent(player.getUuid(), u -> SEED_SOURCE.nextInt());
    }

    /** For Persist.java restore. */
    public static void setSeed(Player player, int seed) {
        SEEDS.put(player.getUuid(), seed);
    }

    /** Player.onEnchantmentPerformed's `this.enchantmentSeed = this.random.nextInt()`. */
    private static void reroll(Player player) {
        SEEDS.put(player.getUuid(), SEED_SOURCE.nextInt());
    }

    /** ItemStack.isEnchantable(): has ENCHANTABLE + carries an EMPTY enchantments component. */
    private static boolean isEnchantable(ItemStack item) {
        if (item == null || item.isAir() || enchantability(item) == null) return false;
        EnchantmentList list = item.get(DataComponents.ENCHANTMENTS);
        return list == null || list.enchantments().isEmpty();
    }

    /** minecraft:enchantable component value (item_enchantability.json); null = can't roll from the table at all. */
    public static Integer enchantability(ItemStack item) {
        if (item == null || item.isAir()) return null;
        var v = VanillaData.itemEnchantability.get(item.material().key().asString());
        return v == null ? null : v.getAsInt();
    }

    /** EnchantmentMenu.slotsChanged: recompute the 3 level-requirement costs from the item + bookshelf count. */
    private static void recomputeCosts(EnchantInventory inv, Session session) {
        int[] costs = session.costs();
        ItemStack item = inv.getItemStack(0);
        if (!isEnchantable(item)) {
            Arrays.fill(costs, 0);
        } else {
            int bookcases = Math.min(15, countBookshelves(session.pos()));
            int[] fresh = tableCosts(new Random(seed(session.player())), bookcases);
            System.arraycopy(fresh, 0, costs, 0, 3);
        }
        inv.property(net.minestom.server.inventory.InventoryProperty.ENCHANTMENT_TABLE_LEVEL_REQUIREMENT_TOP, costs[0]);
        inv.property(net.minestom.server.inventory.InventoryProperty.ENCHANTMENT_TABLE_LEVEL_REQUIREMENT_MIDDLE, costs[1]);
        inv.property(net.minestom.server.inventory.InventoryProperty.ENCHANTMENT_TABLE_LEVEL_REQUIREMENT_BOTTOM, costs[2]);
    }

    /**
     * EnchantmentHelper.getEnchantmentCost, called once per slot against a CONTINUING
     * random stream (not reseeded per slot — each call draws its own 2 nextInt values,
     * 6 total across the 3 slots). Package-visible: SelfTest asserts fixed-seed
     * determinism against this directly (no Instance/bookshelf lookup needed).
     */
    static int[] tableCosts(Random random, int bookcases) {
        int[] costs = new int[3];
        for (int slot = 0; slot < 3; slot++) {
            int selected = random.nextInt(8) + 1 + (bookcases >> 1) + random.nextInt(bookcases + 1);
            int cost = switch (slot) {
                case 0 -> Math.max(selected / 3, 1);
                case 1 -> selected * 2 / 3 + 1;
                default -> Math.max(selected, bookcases * 2);
            };
            costs[slot] = cost < slot + 1 ? 0 : cost;
        }
        return costs;
    }

    /** EnchantingTableBlock.BOOKSHELF_OFFSETS ring + isValidBookShelf's air-gap (transmitter) check. */
    private static int countBookshelves(Point pos) {
        int count = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    Block shelf = instance.getBlock(pos.add(dx, dy, dz));
                    Block mid = instance.getBlock(pos.add(dx / 2, dy, dz / 2));
                    if (VanillaData.blockHasTag(shelf, "enchantment_power_provider")
                            && VanillaData.blockHasTag(mid, "enchantment_power_transmitter")) {
                        count++;
                    }
                }
            }
        }
        return Math.min(15, count);
    }

    /** EnchantmentMenu.getEnchantmentList: reseed(seed+slot), select, then the book one-less-offer trim. */
    static List<Offer> getEnchantmentList(int seed, ItemStack item, int slot, int cost) {
        Random random = new Random((long) (seed + slot));
        List<Offer> list = new ArrayList<>(selectEnchantments(random, item, cost));
        if (item.material() == Material.BOOK && list.size() > 1) {
            list.remove(random.nextInt(list.size()));
        }
        return list;
    }

    /** EnchantmentHelper.selectEnchantment, ported bit-for-bit (same RNG call order/shape). */
    static List<Offer> selectEnchantments(Random random, ItemStack item, int enchantmentCost) {
        Integer enchantability = enchantability(item);
        if (enchantability == null) return List.of();
        int cost = enchantmentCost + 1
                + random.nextInt(enchantability / 4 + 1)
                + random.nextInt(enchantability / 4 + 1);
        float randomSpan = (random.nextFloat() + random.nextFloat() - 1.0f) * 0.15f;
        cost = Math.max(1, Math.round(cost + cost * randomSpan));

        List<Offer> candidates = availableEnchantmentResults(cost, item);
        List<Offer> results = new ArrayList<>();
        if (!candidates.isEmpty()) {
            weightedPick(random, candidates).ifPresent(results::add);
            while (random.nextInt(50) <= cost) {
                if (!results.isEmpty()) {
                    Offer last = results.get(results.size() - 1);
                    candidates.removeIf(c -> !compatible(last.def(), c.def()));
                }
                if (candidates.isEmpty()) break;
                weightedPick(random, candidates).ifPresent(results::add);
                cost /= 2;
            }
        }
        return results;
    }

    /** Enchantment.areCompatible: not itself, and not mutually exclusive either direction. */
    static boolean compatible(EnchantmentDef a, EnchantmentDef b) {
        return !a.path().equals(b.path()) && !exclusive(a, b);
    }

    /**
     * EnchantmentHelper.getAvailableEnchantmentResults: candidates drawn from the
     * in_enchanting_table tag, primary-item-gated (books bypass), one entry per
     * enchantment at its HIGHEST level whose [minCost,maxCost] window contains cost.
     */
    static List<Offer> availableEnchantmentResults(int cost, ItemStack item) {
        boolean isBook = item.material() == Material.BOOK;
        List<Offer> out = new ArrayList<>();
        for (EnchantmentDef def : allDefs()) {
            if (!inEnchantingTable(def)) continue;
            if (!isBook && !isPrimaryItem(def, item)) continue;
            for (int level = def.maxLevel(); level >= 1; level--) {
                int min = def.minCost().at(level);
                int max = def.maxCost().at(level);
                if (cost >= min && cost <= max) {
                    out.add(new Offer(def, level));
                    break;
                }
            }
        }
        return out;
    }

    /** WeightedRandom.getRandomItem: nextInt(totalWeight), then subtract-walk. */
    static Optional<Offer> weightedPick(Random random, List<Offer> candidates) {
        int total = 0;
        for (Offer o : candidates) total += o.def().weight();
        if (total <= 0) return Optional.empty();
        int selection = random.nextInt(total);
        for (Offer o : candidates) {
            selection -= o.def().weight();
            if (selection < 0) return Optional.of(o);
        }
        return Optional.empty();
    }

    /** EnchantmentMenu.clickMenuButton, ported: lapis cost == XP LEVELS deducted, buttonId+1 — NOT the displayed cost. */
    private static void buttonClick(InventoryButtonClickEvent e) {
        if (!(e.getInventory() instanceof EnchantInventory inv)) return;
        Session session = SESSIONS.get(inv);
        if (session == null) return;
        int slot = e.getButtonId();
        if (slot < 0 || slot > 2) return;
        Player player = e.getPlayer();
        ItemStack item = inv.getItemStack(0);
        ItemStack lapis = inv.getItemStack(1);
        int lapisCost = slot + 1;
        int displayedCost = session.costs()[slot];
        boolean creative = player.getGameMode() == net.minestom.server.entity.GameMode.CREATIVE;

        if (!creative && (lapis.isAir() || lapis.material() != Material.LAPIS_LAZULI || lapis.amount() < lapisCost)) return;
        if (displayedCost <= 0 || item.isAir()) return;
        if (!creative && (player.getLevel() < lapisCost || player.getLevel() < displayedCost)) return;

        List<Offer> offers = getEnchantmentList(seed(player), item, slot, displayedCost);
        if (offers.isEmpty()) return;

        ItemStack result = item;
        if (item.material() == Material.BOOK) {
            result = ItemStack.of(Material.ENCHANTED_BOOK);
            inv.setItemStack(0, result);
        }
        if (!creative) dev.pointofpressure.minecom.survival.Experience.takeLevels(player, lapisCost);
        reroll(player);
        for (Offer o : offers) {
            RegistryKey<Enchantment> key = byName(o.def().path());
            if (key != null) result = with(result, key, o.level());
        }
        inv.setItemStack(0, result);
        if (!creative) {
            ItemStack consumed = lapis.consume(lapisCost);
            inv.setItemStack(1, consumed.amount() <= 0 ? ItemStack.AIR : consumed);
        }
        recomputeCosts(inv, session);
        inv.property(net.minestom.server.inventory.InventoryProperty.ENCHANTMENT_TABLE_SEED, seed(player));
    }
}
