package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.TickPipeline;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Boats: using a boat item on water spawns the matching boat, right-clicking it seats
 * the player, and the boat bobs at the water surface (buoyancy) with water friction.
 * Rowing/steering is client-driven vehicle input; here we handle placement, riding,
 * and flotation.
 */
public final class Boats {

    /** Public: reused by Redstone.java's dispenser behavior (DispenserBehavior placing
     *  a boat item onto adjacent water is a real vanilla dispenser behavior). */
    public static final Map<Material, EntityType> BOATS = Map.ofEntries(
            Map.entry(Material.OAK_BOAT, EntityType.OAK_BOAT),
            Map.entry(Material.BIRCH_BOAT, EntityType.BIRCH_BOAT),
            Map.entry(Material.SPRUCE_BOAT, EntityType.SPRUCE_BOAT),
            Map.entry(Material.JUNGLE_BOAT, EntityType.JUNGLE_BOAT),
            Map.entry(Material.ACACIA_BOAT, EntityType.ACACIA_BOAT),
            Map.entry(Material.DARK_OAK_BOAT, EntityType.DARK_OAK_BOAT),
            Map.entry(Material.MANGROVE_BOAT, EntityType.MANGROVE_BOAT),
            Map.entry(Material.CHERRY_BOAT, EntityType.CHERRY_BOAT),
            Map.entry(Material.BAMBOO_RAFT, EntityType.BAMBOO_RAFT));

    /** Chest boats: same 9 wood variants, each with a real 27-slot Container
     *  (AbstractChestBoat implements ContainerEntity, decompile-verified). */
    private static final Map<Material, EntityType> CHEST_BOATS = Map.ofEntries(
            Map.entry(Material.OAK_CHEST_BOAT, EntityType.OAK_CHEST_BOAT),
            Map.entry(Material.BIRCH_CHEST_BOAT, EntityType.BIRCH_CHEST_BOAT),
            Map.entry(Material.SPRUCE_CHEST_BOAT, EntityType.SPRUCE_CHEST_BOAT),
            Map.entry(Material.JUNGLE_CHEST_BOAT, EntityType.JUNGLE_CHEST_BOAT),
            Map.entry(Material.ACACIA_CHEST_BOAT, EntityType.ACACIA_CHEST_BOAT),
            Map.entry(Material.DARK_OAK_CHEST_BOAT, EntityType.DARK_OAK_CHEST_BOAT),
            Map.entry(Material.MANGROVE_CHEST_BOAT, EntityType.MANGROVE_CHEST_BOAT),
            Map.entry(Material.CHERRY_CHEST_BOAT, EntityType.CHERRY_CHEST_BOAT),
            Map.entry(Material.BAMBOO_CHEST_RAFT, EntityType.BAMBOO_CHEST_RAFT));
    private static final Set<EntityType> CHEST_BOAT_TYPES = Set.copyOf(CHEST_BOATS.values());

    private static final Set<EntityType> BOAT_TYPES;
    private static final Map<EntityType, Material> BOAT_ITEMS = new HashMap<>();
    private static final Map<java.util.UUID, net.minestom.server.inventory.Inventory> CHEST_BOAT_INV = new HashMap<>();
    static {
        java.util.Set<EntityType> all = new java.util.HashSet<>(BOATS.values());
        all.addAll(CHEST_BOATS.values());
        BOAT_TYPES = Set.copyOf(all);
        for (var e : BOATS.entrySet()) BOAT_ITEMS.put(e.getValue(), e.getKey());
        for (var e : CHEST_BOATS.entrySet()) BOAT_ITEMS.put(e.getValue(), e.getKey());
    }

    private Boats() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemEvent.class, event -> {
            Material mat = event.getItemStack().material();
            EntityType type = BOATS.get(mat);
            if (type == null) type = CHEST_BOATS.get(mat);
            if (type == null) return;
            Pos where = placement(event.getPlayer().getInstance(),
                    event.getPlayer().getPosition().add(0, event.getPlayer().getEyeHeight(), 0));
            if (where != null) spawn(event.getPlayer().getInstance(), type, where);
        });

        events.addListener(PlayerEntityInteractEvent.class, event -> {
            EntityType type = event.getTarget().getEntityType();
            if (!BOAT_TYPES.contains(type)) return;
            // AbstractChestBoat.interact (decompile-verified): riding wins UNLESS the
            // player is sneaking or the boat already has a rider, in which case it opens
            // the 27-slot container instead — the same "empty-handed mount, sneak to open
            // the storage" pattern real vanilla uses for horses-with-chests/chest minecarts.
            if (CHEST_BOAT_TYPES.contains(type)
                    && (event.getPlayer().isSneaking() || event.getTarget().hasPassenger())) {
                event.getPlayer().openInventory(chestInventory(event.getTarget()));
                return;
            }
            if (!event.getTarget().hasPassenger()) event.getTarget().addPassenger(event.getPlayer());
        });

        // attacking a boat breaks it and drops the matching boat item (+ spills a chest boat's contents)
        events.addListener(EntityAttackEvent.class, event -> {
            EntityType type = event.getTarget().getEntityType();
            if (!BOAT_TYPES.contains(type)) return;
            Pos at = event.getTarget().getPosition();
            Instance instance = event.getTarget().getInstance();
            // copy: removePassenger mutates the live passenger view mid-iteration
            for (Entity passenger : java.util.List.copyOf(event.getTarget().getPassengers())) {
                event.getTarget().removePassenger(passenger);
            }
            if (CHEST_BOAT_TYPES.contains(type) && instance != null) {
                var inv = CHEST_BOAT_INV.remove(event.getTarget().getUuid());
                if (inv != null) Containers.spill(instance, at, inv);
            }
            event.getTarget().remove();
            Material item = BOAT_ITEMS.get(type);
            if (item != null && instance != null) {
                ItemEntity drop = new ItemEntity(ItemStack.of(item, 1));
                drop.setInstance(instance, at.add(0, 0.2, 0));
                drop.setPickupDelay(java.time.Duration.ofMillis(500));
            }
        });

        TickPipeline.register(TickPipeline.ENTITIES, "boats", Boats::tickAll);
    }

    public static Entity spawn(Instance instance, EntityType type, Pos pos) {
        Entity boat = new Entity(type);
        boat.setInstance(instance, pos);
        return boat;
    }

    /** A chest boat's lazily-created 27-slot Container inventory. */
    private static net.minestom.server.inventory.Inventory chestInventory(Entity boat) {
        return CHEST_BOAT_INV.computeIfAbsent(boat.getUuid(), id -> new net.minestom.server.inventory.Inventory(
                net.minestom.server.inventory.InventoryType.CHEST_3_ROW, net.kyori.adventure.text.Component.text("Chest Boat")));
    }

    /** Short raycast from the eye: land the boat on the first water (or solid) hit. */
    private static Pos placement(Instance instance, Pos eye) {
        Vec dir = eye.direction();
        for (double d = 1.0; d <= 5.0; d += 0.5) {
            Pos pt = eye.add(dir.mul(d));
            Block b = instance.getBlock(pt);
            if (b.compare(Block.WATER)) {
                return new Pos(pt.blockX() + 0.5, pt.blockY() + 0.9, pt.blockZ() + 0.5, eye.yaw(), 0);
            }
            if (b.isSolid()) {
                return new Pos(pt.blockX() + 0.5, pt.blockY() + 1.0, pt.blockZ() + 0.5, eye.yaw(), 0);
            }
        }
        return null;
    }

    /** Whether the entity type is any boat/chest-boat/raft. For BubbleColumns. */
    public static boolean isBoat(net.minestom.server.entity.EntityType type) {
        return BOAT_TYPES.contains(type);
    }

    private static void tickAll() {
        for (Instance instance : MinecraftServer.getInstanceManager().getInstances()) {
            for (Entity e : instance.getEntities()) {
                if (BOAT_TYPES.contains(e.getEntityType())) buoyancy(instance, e);
            }
        }
    }

    /** Water or a bubble-column cell — vanilla's getFluidState treats both as water. */
    private static boolean waterLike(Block b) {
        return b.compare(Block.WATER) || b.key().value().equals("bubble_column");
    }

    /** Float the boat at the water surface; coast with water friction. */
    private static void buoyancy(Instance instance, Entity boat) {
        Pos p = boat.getPosition();
        int bx = p.blockX(), bz = p.blockZ();
        Block feet = instance.getBlock(bx, (int) Math.floor(p.y()), bz);
        boolean inWater = waterLike(feet)
                || waterLike(instance.getBlock(bx, (int) Math.floor(p.y() + 0.3), bz));
        if (!inWater) return;   // over land/air: Minestom gravity carries it down to water
        // a drag-down bubble column pulls the hull under instead of floating it
        // (AbstractBoat's sink path — gravity + BubbleColumns' 60gt timer take over);
        // push-up columns float the boat normally so it keeps top-cell contact
        // until BubbleColumns' launch fires
        if (feet.key().value().equals("bubble_column") && "true".equals(feet.getProperty("drag"))) {
            return;
        }

        // top of the water column the boat sits in
        int sy = (int) Math.floor(p.y());
        while (waterLike(instance.getBlock(bx, sy + 1, bz))) sy++;
        double surface = sy + 0.9;
        double ny = p.y() + (surface - p.y()) * 0.4;   // ease toward the surface

        Vec v = boat.getVelocity();
        boat.setVelocity(new Vec(v.x() * 0.9, 0, v.z() * 0.9));   // water friction, no vertical drift
        boat.teleport(new Pos(p.x(), ny, p.z(), p.yaw(), 0));
    }
}
