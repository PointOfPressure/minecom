package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.StateAdapter;
import dev.pointofpressure.minecom.data.Recipes;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Interactive blocks: crafting tables, furnaces, chests (per-position inventories,
 * in-memory), and door/trapdoor/fence-gate toggling.
 */
public final class Containers {
    private Containers() {}

    /** Chest inventories keyed by "x,y,z"; exposed for persistence. */
    public static final Map<String, Inventory> CHESTS = new ConcurrentHashMap<>();

    /** Trapped-chest inventories, reverse-keyed by Inventory so a close event can find its
     *  block position — TrappedChestBlock.isSignalSource needs the live viewer count, which
     *  only changes on open/close, not on any block update Redstone.java would otherwise see. */
    private static final Map<Inventory, Point> TRAPPED_CHEST_POS = new ConcurrentHashMap<>();

    /** Structure-placed container positions ("x,y,z") awaiting their first-open loot roll,
     *  mapped to a chests/* loot table id path (RandomizableContainer/ContainerHelper's
     *  LootTable NBT field — real vanilla resolves this the same way, on first interact,
     *  not at generation time). */
    private static final Map<String, String> PENDING_LOOT = new ConcurrentHashMap<>();

    public static String posKey(Point p) {
        return p.blockX() + "," + p.blockY() + "," + p.blockZ();
    }

    /** Called by structure placement (worldgen) to arm a container's first-open loot roll. */
    public static void registerLoot(Point pos, String lootTablePath) {
        PENDING_LOOT.put(posKey(pos), lootTablePath);
    }

    /**
     * ChestBlockEntity/ContainerOpenersCounter's actionId-1 "viewer count" BlockActionPacket:
     * this is what actually drives the client-side lid-opening animation — {@code
     * player.openInventory} only manages the inventory window, it never touches the physical
     * block's visual state. Shared by chest/trapped_chest/ender_chest (real vanilla uses the
     * exact same mechanism for all three); barrel is different (a real "open" blockstate
     * property swap instead — see {@link #barrelToggle}). The open/close sound only plays on
     * the 0-&gt;1 / 1-&gt;0 transition, matching vanilla not re-triggering it for every
     * additional/departing viewer.
     */
    public static void chestAnimation(Instance instance, Point pos, int viewers, net.minestom.server.entity.Entity source) {
        sendChestAction(instance, pos, viewers);
        if (viewers == 1) {
            playChestSound(instance, pos, net.minestom.server.sound.SoundEvent.BLOCK_CHEST_OPEN);
            dev.pointofpressure.minecom.redstone.Vibrations.emit("container_open", pos, source);
        } else if (viewers == 0) {
            playChestSound(instance, pos, net.minestom.server.sound.SoundEvent.BLOCK_CHEST_CLOSE);
            dev.pointofpressure.minecom.redstone.Vibrations.emit("container_close", pos, source);
        }
    }

    /** Just the lid-animation packet, no sound — for callers (EnderChest) that play their own
     *  distinct open/close sound instead of the plain chest one. */
    public static void sendChestAction(Instance instance, Point pos, int viewers) {
        instance.sendGroupedPacket(new net.minestom.server.network.packet.server.play.BlockActionPacket(
                pos, (byte) 1, (byte) viewers, instance.getBlock(pos)));
    }

    private static void playChestSound(Instance instance, Point pos, net.minestom.server.sound.SoundEvent sound) {
        instance.playSound(net.kyori.adventure.sound.Sound.sound(
                        sound, net.kyori.adventure.sound.Sound.Source.BLOCK, 0.5f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }

    /** BarrelBlock: no BlockActionPacket at all in real vanilla — the "open" blockstate property
     *  itself swaps the model, driven by ContainerOpenersCounter the same 0-&lt;-&gt;1+ way. */
    public static void barrelToggle(Instance instance, Point pos, boolean open, net.minestom.server.entity.Entity source) {
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("barrel")) return;
        instance.setBlock(pos, block.withProperty("open", String.valueOf(open)));
        playChestSound(instance, pos, open
                ? net.minestom.server.sound.SoundEvent.BLOCK_BARREL_OPEN
                : net.minestom.server.sound.SoundEvent.BLOCK_BARREL_CLOSE);
        dev.pointofpressure.minecom.redstone.Vibrations.emit(
                open ? "container_open" : "container_close", pos, source);
    }

    /** Reverse lookup so a close event (which only knows the Inventory) can find the physical
     *  block position(s) to animate — a double chest animates both halves together. */
    private static final Map<Inventory, Point[]> ANIM_POS = new ConcurrentHashMap<>();

    private static void trackAnim(Inventory inv, Point... positions) {
        ANIM_POS.put(inv, positions);
    }

    /** Same reverse-lookup role as ANIM_POS, for barrels (blockstate toggle instead of a packet). */
    private static final Map<Inventory, Point> BARREL_POS = new ConcurrentHashMap<>();

    /** Roll a pending structure loot table into a freshly-opened container, if one is armed. */
    public static void rollPendingLoot(String key, Inventory inv) {
        String table = PENDING_LOOT.remove(key);
        if (table == null) return;
        List<ItemStack> loot = dev.pointofpressure.minecom.data.LootTables.chest(table);
        if (loot.isEmpty()) return;
        List<Integer> slots = new ArrayList<>(inv.getSize());
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots);
        for (int i = 0; i < loot.size() && i < slots.size(); i++) {
            inv.setItemStack(slots.get(i), loot.get(i));
        }
    }

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, Containers::interact);
        events.addListener(InventoryCloseEvent.class, e -> {
            Point pos = TRAPPED_CHEST_POS.get(e.getInventory());
            if (pos != null) dev.pointofpressure.minecom.redstone.Redstone.neighborsChanged(pos);
            // the closing player is still in this inventory's viewer set at listener time —
            // Player.closeInventory only calls removeViewer AFTER dispatching this event —
            // so exclude them by hand to get the count as it'll be once the close completes.
            int viewersAfterClose = (int) e.getInventory().getViewers().stream()
                    .filter(v -> v != e.getPlayer()).count();
            Point[] animPositions = ANIM_POS.get(e.getInventory());
            if (animPositions != null) {
                for (Point p : animPositions) {
                    chestAnimation(e.getPlayer().getInstance(), p, viewersAfterClose, e.getPlayer());
                }
            }
            Point barrelPos = BARREL_POS.get(e.getInventory());
            if (barrelPos != null && viewersAfterClose == 0) {
                barrelToggle(e.getPlayer().getInstance(), barrelPos, false, e.getPlayer());
            }
        });
        Persist.register(persistence());
        Persist.register(pendingLootPersistence());
        Crafting.register(events);
        Furnaces.register(events);
    }

    /** Pending structure-loot registrations survive a restart too (docs/PERSISTENCE.md), so a
     *  generated-but-never-opened chest doesn't quietly go empty forever across a server restart. */
    private static StateAdapter pendingLootPersistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "pending_loot";
            }

            @Override
            public void collect(Instance instance, BiConsumer<Point, JsonObject> out) {
                PENDING_LOOT.forEach((key, table) -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("table", table);
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance instance, Point pos, JsonObject data) {
                PENDING_LOOT.put(posKey(pos), data.get("table").getAsString());
            }

            @Override
            public void wipe() {
                PENDING_LOOT.clear();
            }
        };
    }

    /**
     * Chest persistence (docs/PERSISTENCE.md). Double chests share one
     * Inventory under both position keys: the first key met saves the items,
     * the partner saves a {"ref": "x,y,z"} entry resolved in finishRestore
     * (the halves can sit in different chunks, so resolution must wait until
     * every shard is in).
     */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            private final Map<String, String> pendingRefs = new ConcurrentHashMap<>();

            @Override
            public String kind() {
                return "chest";
            }

            @Override
            public void collect(Instance instance, BiConsumer<Point, JsonObject> out) {
                Map<Inventory, String> seen = new HashMap<>();
                CHESTS.forEach((key, inv) -> {
                    JsonObject o = new JsonObject();
                    String first = seen.putIfAbsent(inv, key);
                    if (first != null) {
                        o.addProperty("ref", first);
                    } else {
                        o.addProperty("size", inv.getSize());
                        o.add("items", Persist.writeItems(inv));
                    }
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance instance, Point pos, JsonObject data) {
                if (data.has("ref")) {
                    pendingRefs.put(posKey(pos), data.get("ref").getAsString());
                    return;
                }
                InventoryType type = data.get("size").getAsInt() > 27
                        ? InventoryType.CHEST_6_ROW : InventoryType.CHEST_3_ROW;
                Inventory inv = new Inventory(type, Component.text("Chest"));
                Persist.readItems(data.getAsJsonArray("items"), inv);
                CHESTS.put(posKey(pos), inv);
            }

            @Override
            public void finishRestore(Instance instance) {
                pendingRefs.forEach((key, ref) -> {
                    Inventory shared = CHESTS.get(ref);
                    if (shared != null) CHESTS.put(key, shared);
                });
                pendingRefs.clear();
            }

            @Override
            public void wipe() {
                CHESTS.clear();
            }
        };
    }

    private static void interact(PlayerBlockInteractEvent e) {
        if (e.getPlayer().isSneaking() && !e.getPlayer().getItemInMainHand().isAir()) return;
        Block block = e.getBlock();
        String key = block.key().value();
        Player player = e.getPlayer();
        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();

        switch (key) {
            case "crafting_table" -> {
                e.setBlockingItemUse(true);
                Crafting.open(player);
            }
            case "furnace", "blast_furnace", "smoker" -> {
                e.setBlockingItemUse(true);
                Furnaces.open(player, instance, pos, block);
            }
            case "hopper" -> {
                e.setBlockingItemUse(true);
                player.openInventory(dev.pointofpressure.minecom.redstone.Hoppers.inventory(pos));
                // hoppers have no lid to animate (always visually "open"), but real vanilla's
                // ContainerOpenersCounter still fires CONTAINER_OPEN for them same as any chest
                dev.pointofpressure.minecom.redstone.Vibrations.emit("container_open", pos, player);
            }
            case "chest" -> {
                e.setBlockingItemUse(true);
                Inventory inv = openMergeable(instance, pos, block, "chest", "Chest");
                rollPendingLoot(posKey(pos), inv);
                player.openInventory(inv);
                Point[] positions = mergeablePositions(pos, block);
                trackAnim(inv, positions);
                for (Point p : positions) chestAnimation(instance, p, inv.getViewers().size(), player);
            }
            case "barrel" -> {
                e.setBlockingItemUse(true);
                Inventory inv = CHESTS.computeIfAbsent(posKey(pos),
                        p -> new Inventory(InventoryType.CHEST_3_ROW, Component.text("Barrel")));
                rollPendingLoot(posKey(pos), inv);
                player.openInventory(inv);
                BARREL_POS.put(inv, pos);
                barrelToggle(instance, pos, true, player);
            }
            case "trapped_chest" -> {
                e.setBlockingItemUse(true);
                Inventory inv = openMergeable(instance, pos, block, "trapped_chest", "Trapped Chest");
                rollPendingLoot(posKey(pos), inv);
                TRAPPED_CHEST_POS.put(inv, pos);
                player.openInventory(inv);
                dev.pointofpressure.minecom.redstone.Redstone.neighborsChanged(pos);
                Point[] positions = mergeablePositions(pos, block);
                trackAnim(inv, positions);
                for (Point p : positions) chestAnimation(instance, p, inv.getViewers().size(), player);
            }
            case "ender_chest" -> {
                e.setBlockingItemUse(true);
                EnderChest.open(player, instance, pos);
            }
            default -> {
                if ((key.endsWith("_door") && !key.equals("iron_door"))) {
                    e.setBlockingItemUse(true);
                    toggleDoor(instance, pos, block);
                    // DoorBlock.setOpen: BLOCK_OPEN/BLOCK_CLOSE fire unconditionally on every
                    // toggle (no opener-count gating like containers)
                    dev.pointofpressure.minecom.redstone.Vibrations.emit(
                            "true".equals(instance.getBlock(pos).getProperty("open")) ? "block_open" : "block_close",
                            pos, player);
                } else if ((key.endsWith("_trapdoor") && !key.equals("iron_trapdoor"))
                        || key.endsWith("_fence_gate")) {
                    e.setBlockingItemUse(true);
                    boolean opening = !"true".equals(block.getProperty("open"));
                    instance.setBlock(pos, toggled(block));
                    dev.pointofpressure.minecom.redstone.Vibrations.emit(
                            opening ? "block_open" : "block_close", pos, player);
                }
            }
        }
    }

    /**
     * ChestBlock.getContainer/DoubleBlockCombiner (decompile-verified): a chest whose "type" is
     * left/right shares ONE 54-slot Inventory with its partner half — whichever half is opened
     * first creates it and stores it under BOTH positions' keys, so the other half's own open
     * (or a comparator reading either position) reuses the exact same merged inventory.
     */
    private static Inventory openMergeable(Instance instance, Point pos, Block block, String key, String title) {
        String type = block.getProperty("type");
        if (type == null || type.equals("single")) {
            return CHESTS.computeIfAbsent(posKey(pos),
                    p -> new Inventory(InventoryType.CHEST_3_ROW, Component.text(title)));
        }
        Point partnerPos = partnerPos(pos, block);
        Inventory inv = CHESTS.get(posKey(pos));
        if (inv == null) inv = CHESTS.get(posKey(partnerPos));
        if (inv == null) inv = new Inventory(InventoryType.CHEST_6_ROW, Component.text("Large " + title));
        CHESTS.put(posKey(pos), inv);
        CHESTS.put(posKey(partnerPos), inv);
        return inv;
    }

    /** The other half of a left/right chest pair (ChestBlock.getConnectedDirection). */
    private static Point partnerPos(Point pos, Block block) {
        String facing = block.getProperty("facing");
        String dir = "left".equals(block.getProperty("type")) ? Placement.clockwise(facing) : Placement.counterClockwise(facing);
        return Placement.offset(pos, dir);
    }

    /** Both halves of a double chest/trapped_chest (or just the one position for a single). */
    private static Point[] mergeablePositions(Point pos, Block block) {
        String type = block.getProperty("type");
        return (type == null || type.equals("single")) ? new Point[]{pos} : new Point[]{pos, partnerPos(pos, block)};
    }

    private static Block toggled(Block block) {
        boolean open = "true".equals(block.getProperty("open"));
        return block.withProperty("open", open ? "false" : "true");
    }

    private static void toggleDoor(Instance instance, Point pos, Block block) {
        instance.setBlock(pos, toggled(block));
        boolean upper = "upper".equals(block.getProperty("half"));
        Point otherPos = pos.add(0, upper ? -1 : 1, 0);
        Block other = instance.getBlock(otherPos);
        if (other.key().value().equals(block.key().value())) {
            instance.setBlock(otherPos, toggled(other));
        }
    }

    /** Called when a container block is broken: spill contents and forget state. */
    public static void onBlockRemoved(Instance instance, Point pos, Block block) {
        String key = block.key().value();
        if (key.equals("chest") || key.equals("barrel") || key.equals("trapped_chest")) {
            Inventory inv = CHESTS.remove(posKey(pos));
            String type = block.getProperty("type");
            if (type != null && !type.equals("single")) {
                Point partnerPos = partnerPos(pos, block);
                CHESTS.remove(posKey(partnerPos));
                Block partner = instance.getBlock(partnerPos);
                if (partner.key().value().equals(key)) {
                    instance.setBlock(partnerPos, partner.withProperty("type", "single"));
                }
            }
            if (inv != null) {
                TRAPPED_CHEST_POS.remove(inv);
                spill(instance, pos, inv);
            }
        } else if (key.equals("furnace") || key.equals("blast_furnace") || key.equals("smoker")) {
            Furnaces.remove(instance, pos);
        } else if (key.equals("dispenser") || key.equals("dropper")) {
            var inv = dev.pointofpressure.minecom.redstone.Redstone.DISPENSERS.remove(posKey(pos));
            if (inv != null) spill(instance, pos, inv);
        } else if (key.equals("hopper")) {
            dev.pointofpressure.minecom.redstone.Hoppers.remove(instance, pos);
        } else if (key.equals("brewing_stand")) {
            Brewing.onRemoved(instance, pos);
        } else if (key.equals("lectern")) {
            Lectern.onBlockRemoved(instance, pos);
        } else if (key.equals("decorated_pot")) {
            DecoratedPot.onBlockRemoved(instance, pos);
        } else if (key.equals("chiseled_bookshelf")) {
            ChiseledBookshelf.onBlockRemoved(instance, pos);
        }
    }

    public static void spill(Instance instance, Point pos, Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItemStack(i);
            if (!stack.isAir()) BlockRules.dropAt(instance, pos, stack);
            inv.setItemStack(i, ItemStack.AIR);
        }
    }

    /** Give to inventory or drop at the player's feet if full. */
    static void giveOrDrop(Player player, ItemStack stack) {
        if (stack.isAir()) return;
        if (!player.getInventory().addItemStack(stack)) {
            dev.pointofpressure.minecom.survival.Survival.dropItem(player, stack, true);
        }
    }

    static ItemStack stackOf(Material mat, int amount) {
        return amount <= 0 ? ItemStack.AIR : ItemStack.of(mat, amount);
    }
}
