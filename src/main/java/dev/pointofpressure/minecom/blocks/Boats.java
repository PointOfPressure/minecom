package dev.pointofpressure.minecom.blocks;

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

    private static final Map<Material, EntityType> BOATS = Map.ofEntries(
            Map.entry(Material.OAK_BOAT, EntityType.OAK_BOAT),
            Map.entry(Material.BIRCH_BOAT, EntityType.BIRCH_BOAT),
            Map.entry(Material.SPRUCE_BOAT, EntityType.SPRUCE_BOAT),
            Map.entry(Material.JUNGLE_BOAT, EntityType.JUNGLE_BOAT),
            Map.entry(Material.ACACIA_BOAT, EntityType.ACACIA_BOAT),
            Map.entry(Material.DARK_OAK_BOAT, EntityType.DARK_OAK_BOAT),
            Map.entry(Material.MANGROVE_BOAT, EntityType.MANGROVE_BOAT),
            Map.entry(Material.CHERRY_BOAT, EntityType.CHERRY_BOAT),
            Map.entry(Material.BAMBOO_RAFT, EntityType.BAMBOO_RAFT));

    private static final Set<EntityType> BOAT_TYPES = Set.copyOf(BOATS.values());
    private static final Map<EntityType, Material> BOAT_ITEMS = new HashMap<>();
    static {
        for (var e : BOATS.entrySet()) BOAT_ITEMS.put(e.getValue(), e.getKey());
    }

    private Boats() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemEvent.class, event -> {
            EntityType type = BOATS.get(event.getItemStack().material());
            if (type == null) return;
            Pos where = placement(event.getPlayer().getInstance(),
                    event.getPlayer().getPosition().add(0, event.getPlayer().getEyeHeight(), 0));
            if (where != null) spawn(event.getPlayer().getInstance(), type, where);
        });

        events.addListener(PlayerEntityInteractEvent.class, event -> {
            if (!BOAT_TYPES.contains(event.getTarget().getEntityType())) return;
            if (!event.getTarget().hasPassenger()) event.getTarget().addPassenger(event.getPlayer());
        });

        // attacking a boat breaks it and drops the matching boat item
        events.addListener(EntityAttackEvent.class, event -> {
            EntityType type = event.getTarget().getEntityType();
            if (!BOAT_TYPES.contains(type)) return;
            Pos at = event.getTarget().getPosition();
            Instance instance = event.getTarget().getInstance();
            for (Entity passenger : event.getTarget().getPassengers()) event.getTarget().removePassenger(passenger);
            event.getTarget().remove();
            Material item = BOAT_ITEMS.get(type);
            if (item != null && instance != null) {
                ItemEntity drop = new ItemEntity(ItemStack.of(item, 1));
                drop.setInstance(instance, at.add(0, 0.2, 0));
                drop.setPickupDelay(java.time.Duration.ofMillis(500));
            }
        });

        MinecraftServer.getSchedulerManager().buildTask(Boats::tickAll)
                .repeat(TaskSchedule.tick(1)).schedule();
    }

    public static Entity spawn(Instance instance, EntityType type, Pos pos) {
        Entity boat = new Entity(type);
        boat.setInstance(instance, pos);
        return boat;
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

    private static void tickAll() {
        for (Instance instance : MinecraftServer.getInstanceManager().getInstances()) {
            for (Entity e : instance.getEntities()) {
                if (BOAT_TYPES.contains(e.getEntityType())) buoyancy(instance, e);
            }
        }
    }

    /** Float the boat at the water surface; coast with water friction. */
    private static void buoyancy(Instance instance, Entity boat) {
        Pos p = boat.getPosition();
        int bx = p.blockX(), bz = p.blockZ();
        boolean inWater = instance.getBlock(bx, (int) Math.floor(p.y()), bz).compare(Block.WATER)
                || instance.getBlock(bx, (int) Math.floor(p.y() + 0.3), bz).compare(Block.WATER);
        if (!inWater) return;   // over land/air: Minestom gravity carries it down to water

        // top of the water column the boat sits in
        int sy = (int) Math.floor(p.y());
        while (instance.getBlock(bx, sy + 1, bz).compare(Block.WATER)) sy++;
        double surface = sy + 0.9;
        double ny = p.y() + (surface - p.y()) * 0.4;   // ease toward the surface

        Vec v = boat.getVelocity();
        boat.setVelocity(new Vec(v.x() * 0.9, 0, v.z() * 0.9));   // water friction, no vertical drift
        boat.teleport(new Pos(p.x(), ny, p.z(), p.yaw(), 0));
    }
}
