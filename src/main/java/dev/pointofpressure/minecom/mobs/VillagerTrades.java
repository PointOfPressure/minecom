package dev.pointofpressure.minecom.mobs;

import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientSelectTradePacket;
import net.minestom.server.network.packet.server.play.TradeListPacket;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Villager trading via the vanilla merchant menu: right-clicking a villager opens the
 * trade GUI seeded with a small set of farmer offers; selecting a trade fills the
 * result slot when the player holds the inputs, and taking it consumes them. Multiple
 * professions, prices scaling with reputation, and restocking are refinements.
 */
public final class VillagerTrades {

    // package-private: VillagerConversion.discount rewrites a copy for the cured-villager
    // trade discount (Villager.updateSpecialPrices' reputation slice).
    record Offer(ItemStack input1, ItemStack input2, ItemStack result) {}

    /** Villager profession -> trade table (keyed by name; set on the entity at spawn). */
    public static final net.minestom.server.tag.Tag<String> PROFESSION =
            net.minestom.server.tag.Tag.String("minecom:profession");

    private static Offer buy(Material in, int amt, Material out, int outAmt) {
        return new Offer(ItemStack.of(in, amt), ItemStack.AIR, ItemStack.of(out, outAmt));
    }

    private static Offer buy2(Material in1, int amt1, Material in2, int amt2, Material out, int outAmt) {
        return new Offer(ItemStack.of(in1, amt1), ItemStack.of(in2, amt2), ItemStack.of(out, outAmt));
    }

    // Level-1 (tier 1) trades only, decompiled from the real VillagerTrades bootstrap —
    // randomized/enchanted variants (dyed leather, enchanted books/swords, tipped arrows)
    // are simplified to their plain base item, same simplification already used by the
    // pre-existing librarian/toolsmith enchanted-book/tool offers below.
    static final Map<String, List<Offer>> TABLES = Map.ofEntries(
            Map.entry("farmer", List.of(
                    buy(Material.WHEAT, 20, Material.EMERALD, 1), buy(Material.POTATO, 26, Material.EMERALD, 1),
                    buy(Material.EMERALD, 1, Material.BREAD, 6), buy(Material.EMERALD, 3, Material.APPLE, 4))),
            Map.entry("librarian", List.of(
                    buy(Material.PAPER, 24, Material.EMERALD, 1), buy(Material.BOOK, 4, Material.EMERALD, 1),
                    buy(Material.EMERALD, 9, Material.ENCHANTED_BOOK, 1), buy(Material.EMERALD, 5, Material.BOOKSHELF, 1))),
            Map.entry("cleric", List.of(
                    buy(Material.ROTTEN_FLESH, 32, Material.EMERALD, 1), buy(Material.GOLD_INGOT, 3, Material.EMERALD, 1),
                    buy(Material.EMERALD, 3, Material.ENDER_PEARL, 1), buy(Material.EMERALD, 2, Material.GLOWSTONE, 4))),
            Map.entry("toolsmith", List.of(
                    buy(Material.COAL, 15, Material.EMERALD, 1), buy(Material.IRON_INGOT, 4, Material.EMERALD, 1),
                    buy(Material.EMERALD, 8, Material.IRON_PICKAXE, 1), buy(Material.EMERALD, 6, Material.DIAMOND_HOE, 1))),
            Map.entry("fletcher", List.of(
                    buy(Material.STICK, 32, Material.EMERALD, 1), buy(Material.FLINT, 26, Material.EMERALD, 1),
                    buy(Material.EMERALD, 1, Material.ARROW, 16), buy(Material.EMERALD, 3, Material.BOW, 1))),
            Map.entry("armorer", List.of(
                    buy(Material.EMERALD, 7, Material.IRON_LEGGINGS, 1), buy(Material.EMERALD, 4, Material.IRON_BOOTS, 1),
                    buy(Material.EMERALD, 5, Material.IRON_HELMET, 1), buy(Material.EMERALD, 9, Material.IRON_CHESTPLATE, 1))),
            Map.entry("weaponsmith", List.of(
                    buy(Material.EMERALD, 3, Material.IRON_AXE, 1), buy(Material.EMERALD, 2, Material.IRON_SWORD, 1))),
            Map.entry("butcher", List.of(
                    buy(Material.CHICKEN, 14, Material.EMERALD, 1), buy(Material.PORKCHOP, 7, Material.EMERALD, 1),
                    buy(Material.RABBIT, 4, Material.EMERALD, 1), buy(Material.EMERALD, 1, Material.RABBIT_STEW, 1))),
            Map.entry("cartographer", List.of(
                    buy(Material.PAPER, 24, Material.EMERALD, 1), buy(Material.EMERALD, 7, Material.MAP, 1))),
            Map.entry("fisherman", List.of(
                    buy(Material.STRING, 20, Material.EMERALD, 1), buy(Material.COAL, 10, Material.EMERALD, 1),
                    buy2(Material.COD, 6, Material.EMERALD, 1, Material.COOKED_COD, 6), buy(Material.EMERALD, 3, Material.COD_BUCKET, 1))),
            Map.entry("leatherworker", List.of(
                    buy(Material.LEATHER, 6, Material.EMERALD, 1), buy(Material.EMERALD, 3, Material.LEATHER_LEGGINGS, 1),
                    buy(Material.EMERALD, 7, Material.LEATHER_CHESTPLATE, 1), buy(Material.EMERALD, 5, Material.LEATHER_HELMET, 1))),
            Map.entry("mason", List.of(
                    buy(Material.CLAY_BALL, 10, Material.EMERALD, 1), buy(Material.EMERALD, 1, Material.BRICK, 10))),
            Map.entry("shepherd", List.of(
                    buy(Material.EMERALD, 2, Material.SHEARS, 1), buy(Material.WHITE_WOOL, 18, Material.EMERALD, 1),
                    buy(Material.BROWN_WOOL, 18, Material.EMERALD, 1), buy(Material.GRAY_WOOL, 18, Material.EMERALD, 1),
                    buy(Material.BLACK_WOOL, 18, Material.EMERALD, 1))),
            Map.entry("wandering_trader", List.of(
                    buy(Material.EMERALD, 1, Material.RED_DYE, 3), buy(Material.EMERALD, 1, Material.SEA_PICKLE, 2),
                    buy(Material.EMERALD, 5, Material.PACKED_ICE, 1), buy(Material.EMERALD, 5, Material.PODZOL, 3))));

    /** Job-site block -> profession, decompiled exactly from PoiTypes.bootstrap. */
    public static final Map<Block, String> JOB_SITE_BLOCKS = Map.ofEntries(
            Map.entry(Block.BLAST_FURNACE, "armorer"), Map.entry(Block.SMOKER, "butcher"),
            Map.entry(Block.CARTOGRAPHY_TABLE, "cartographer"), Map.entry(Block.BREWING_STAND, "cleric"),
            Map.entry(Block.COMPOSTER, "farmer"), Map.entry(Block.BARREL, "fisherman"),
            Map.entry(Block.FLETCHING_TABLE, "fletcher"), Map.entry(Block.CAULDRON, "leatherworker"),
            Map.entry(Block.WATER_CAULDRON, "leatherworker"), Map.entry(Block.LAVA_CAULDRON, "leatherworker"),
            Map.entry(Block.POWDER_SNOW_CAULDRON, "leatherworker"), Map.entry(Block.LECTERN, "librarian"),
            Map.entry(Block.STONECUTTER, "mason"), Map.entry(Block.LOOM, "shepherd"),
            Map.entry(Block.SMITHING_TABLE, "toolsmith"), Map.entry(Block.GRINDSTONE, "weaponsmith"));

    public static final String[] PROFESSION_NAMES = {
            "farmer", "librarian", "cleric", "toolsmith", "fletcher",
            "armorer", "weaponsmith", "butcher", "cartographer", "fisherman", "leatherworker", "mason", "shepherd"};

    private static final Map<UUID, Inventory> OPEN = new ConcurrentHashMap<>();
    private static final Map<UUID, List<Offer>> OPEN_OFFERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Offer> SELECTED = new ConcurrentHashMap<>();

    private VillagerTrades() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerEntityInteractEvent.class, event -> {
            EntityType t = event.getTarget().getEntityType();
            if (t == EntityType.WANDERING_TRADER) {
                openTrading(event.getPlayer(), "wandering_trader", null);
                return;
            }
            if (t != EntityType.VILLAGER) return;
            String prof = event.getTarget().getTag(PROFESSION);
            openTrading(event.getPlayer(), prof != null ? prof : "farmer", event.getTarget());
        });

        // client picks a trade -> fill the output slot if the player has the inputs
        MinecraftServer.getPacketListenerManager().setListener(ClientSelectTradePacket.class, (packet, player) -> {
            Inventory inv = OPEN.get(player.getUuid());
            List<Offer> offers = OPEN_OFFERS.get(player.getUuid());
            if (inv == null || offers == null || packet.selectedSlot() < 0 || packet.selectedSlot() >= offers.size()) return;
            Offer offer = offers.get(packet.selectedSlot());
            SELECTED.put(player.getUuid(), offer);
            inv.setItemStack(0, offer.input1());
            inv.setItemStack(1, offer.input2());
            inv.setItemStack(2, hasInputs(player, offer) ? offer.result() : ItemStack.AIR);
        });

        // taking the result slot completes the trade
        events.addListener(InventoryPreClickEvent.class, event -> {
            if (!(event.getInventory() instanceof Inventory inv) || inv != OPEN.get(event.getPlayer().getUuid())) return;
            if (event.getSlot() != 2) return;
            Player player = event.getPlayer();
            Offer offer = SELECTED.get(player.getUuid());
            event.setCancelled(true);
            if (offer == null || !hasInputs(player, offer)) return;
            consume(player, offer.input1());
            consume(player, offer.input2());
            player.getInventory().addItemStack(offer.result());
            inv.setItemStack(2, hasInputs(player, offer) ? offer.result() : ItemStack.AIR);
        });
    }

    // one villager per job-site block, matching vanilla's single-ticket PoiType claim
    private static final Map<Instance, Set<Long>> CLAIMED_JOBSITES = new ConcurrentHashMap<>();

    /**
     * Scan around a jobless villager for the NEAREST unclaimed real vanilla job-site block
     * (JOB_SITE_BLOCKS, decompiled from PoiTypes.bootstrap) and claim it, assigning the matching
     * profession. No-op if the villager already has a profession, or no unclaimed job site is in
     * range. Real vanilla claims via pathfinding to a POI up to 48 blocks away over several
     * seconds (a brain "AcquirePoi" task); this approximates the claim as immediate (no walk
     * animation) but widened from an earlier 8-block radius to 24 and changed from
     * first-found-in-scan-order to nearest-by-distance, both closer to how real vanilla actually
     * selects a job site for a village-sized search area.
     */
    public static void assignProfession(Entity villager, Instance instance) {
        if (villager.getTag(PROFESSION) != null) return;
        int bx = villager.getPosition().blockX(), by = villager.getPosition().blockY(), bz = villager.getPosition().blockZ();
        Set<Long> claimed = CLAIMED_JOBSITES.computeIfAbsent(instance, i -> ConcurrentHashMap.newKeySet());
        int radius = 24;
        String bestProfession = null;
        long bestKey = 0;
        int bestDistSq = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq >= bestDistSq) continue;
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    String prof = JOB_SITE_BLOCKS.get(instance.getBlock(x, y, z));
                    if (prof == null) continue;
                    long key = packBlockPos(x, y, z);
                    if (claimed.contains(key)) continue;
                    bestProfession = prof;
                    bestKey = key;
                    bestDistSq = distSq;
                }
            }
        }
        if (bestProfession != null && claimed.add(bestKey)) {
            villager.setTag(PROFESSION, bestProfession);
        }
    }

    private static long packBlockPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static void openTrading(Player player, String profession, Entity villagerEntity) {
        List<Offer> offers = TABLES.getOrDefault(profession, TABLES.get("farmer"));
        if (villagerEntity != null) offers = VillagerConversion.discount(offers, villagerEntity, player);
        Inventory inv = new Inventory(InventoryType.MERCHANT, Component.text(cap(profession)));
        OPEN.put(player.getUuid(), inv);
        OPEN_OFFERS.put(player.getUuid(), offers);
        SELECTED.remove(player.getUuid());
        player.openInventory(inv);
        List<TradeListPacket.Trade> trades = offers.stream().map(o -> new TradeListPacket.Trade(
                new TradeListPacket.ItemCost(o.input1()),
                o.result(),
                o.input2().isAir() ? null : new TradeListPacket.ItemCost(o.input2()),
                false, 0, 16, 1, 0, 0.0f, 0)).toList();
        player.sendPacket(new TradeListPacket(inv.getWindowId(), trades, 1, 0, true, false));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean hasInputs(Player player, Offer offer) {
        return holds(player, offer.input1()) && holds(player, offer.input2());
    }

    private static boolean holds(Player player, ItemStack need) {
        if (need.isAir()) return true;
        int have = 0;
        for (ItemStack s : player.getInventory().getItemStacks()) {
            if (s.material() == need.material()) have += s.amount();
        }
        return have >= need.amount();
    }

    private static void consume(Player player, ItemStack need) {
        if (need.isAir()) return;
        int remaining = need.amount();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack s = inv.getItemStack(i);
            if (s.material() != need.material()) continue;
            int take = Math.min(remaining, s.amount());
            inv.setItemStack(i, s.amount() - take <= 0 ? ItemStack.AIR : s.withAmount(s.amount() - take));
            remaining -= take;
        }
    }
}
