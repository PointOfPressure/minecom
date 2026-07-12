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

import java.util.HashMap;
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

    public static String posKey(Point p) {
        return p.blockX() + "," + p.blockY() + "," + p.blockZ();
    }

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, Containers::interact);
        events.addListener(InventoryCloseEvent.class, e -> {
            Point pos = TRAPPED_CHEST_POS.get(e.getInventory());
            if (pos != null) dev.pointofpressure.minecom.redstone.Redstone.neighborsChanged(pos);
        });
        Persist.register(persistence());
        Crafting.register(events);
        Furnaces.register(events);
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
            }
            case "chest" -> {
                e.setBlockingItemUse(true);
                player.openInventory(openMergeable(instance, pos, block, "chest", "Chest"));
            }
            case "barrel" -> {
                e.setBlockingItemUse(true);
                player.openInventory(CHESTS.computeIfAbsent(posKey(pos),
                        p -> new Inventory(InventoryType.CHEST_3_ROW, Component.text("Barrel"))));
            }
            case "trapped_chest" -> {
                e.setBlockingItemUse(true);
                Inventory inv = openMergeable(instance, pos, block, "trapped_chest", "Trapped Chest");
                TRAPPED_CHEST_POS.put(inv, pos);
                player.openInventory(inv);
                dev.pointofpressure.minecom.redstone.Redstone.neighborsChanged(pos);
            }
            case "ender_chest" -> {
                e.setBlockingItemUse(true);
                EnderChest.open(player, instance, pos);
            }
            default -> {
                if ((key.endsWith("_door") && !key.equals("iron_door"))) {
                    e.setBlockingItemUse(true);
                    toggleDoor(instance, pos, block);
                } else if ((key.endsWith("_trapdoor") && !key.equals("iron_trapdoor"))
                        || key.endsWith("_fence_gate")) {
                    e.setBlockingItemUse(true);
                    instance.setBlock(pos, toggled(block));
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
