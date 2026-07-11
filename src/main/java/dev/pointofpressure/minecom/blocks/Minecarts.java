package dev.pointofpressure.minecom.blocks;

import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecarts on rails. A minecart item used on a rail spawns a minecart (or one of its
 * storage/power variants); right-clicking a plain minecart seats the player; each tick
 * every cart follows the rail under it — straight and curved track redirect its
 * velocity, powered rails accelerate (or brake) it, and it coasts with friction. Chest
 * minecarts open a 27-slot inventory, hopper minecarts vacuum nearby dropped items,
 * furnace minecarts self-propel while fuelled with coal, and TNT minecarts explode when
 * they pass over a powered activator rail. Cart-to-cart collision is a refinement.
 */
public final class Minecarts {

    private static final double FRICTION = 0.98;
    private static final double BOOST = 0.06;      // powered-rail acceleration per tick
    private static final double MAX_SPEED = 0.4;
    /** Cart's along-track speed (blocks/tick); the cart is driven purely by teleport. */
    private static final Tag<Double> SPEED = Tag.Double("minecom:cart_speed");
    private static final Tag<Integer> FUEL = Tag.Integer("minecom:cart_fuel");
    private static final Tag<Integer> FUSE = Tag.Integer("minecom:cart_fuse");

    private static final Map<Material, EntityType> ITEM_TYPES = Map.of(
            Material.MINECART, EntityType.MINECART,
            Material.CHEST_MINECART, EntityType.CHEST_MINECART,
            Material.FURNACE_MINECART, EntityType.FURNACE_MINECART,
            Material.HOPPER_MINECART, EntityType.HOPPER_MINECART,
            Material.TNT_MINECART, EntityType.TNT_MINECART);
    private static final Set<EntityType> CART_TYPES = Set.copyOf(ITEM_TYPES.values());
    private static final Map<UUID, Inventory> CART_INV = new ConcurrentHashMap<>();

    private Minecarts() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, event -> {
            EntityType type = ITEM_TYPES.get(event.getItemStack().material());
            if (type == null) return;
            Point pos = event.getPosition();
            Block rail = event.getInstance().getBlock(pos);
            if (!isRail(rail)) return;
            spawn(event.getInstance(), new Pos(pos.blockX() + 0.5, pos.blockY() + 0.1, pos.blockZ() + 0.5), type);
        });

        events.addListener(PlayerEntityInteractEvent.class, event -> {
            EntityType type = event.getTarget().getEntityType();
            if (type == EntityType.MINECART) {
                if (!event.getTarget().hasPassenger()) event.getTarget().addPassenger(event.getPlayer());
            } else if (type == EntityType.CHEST_MINECART) {
                openCartInventory(event.getPlayer(), event.getTarget(), InventoryType.CHEST_3_ROW, "Minecart with Chest");
            } else if (type == EntityType.HOPPER_MINECART) {
                openCartInventory(event.getPlayer(), event.getTarget(), InventoryType.HOPPER, "Minecart with Hopper");
            } else if (type == EntityType.FURNACE_MINECART) {
                ItemStack held = event.getPlayer().getItemInMainHand();
                if (held.material() == Material.COAL || held.material() == Material.CHARCOAL) {
                    int fuel = event.getTarget().hasTag(FUEL) ? event.getTarget().getTag(FUEL) : 0;
                    event.getTarget().setTag(FUEL, fuel + 3600);
                    event.getPlayer().setItemInMainHand(held.consume(1));
                }
            }
        });

        MinecraftServer.getSchedulerManager().buildTask(Minecarts::tickAll)
                .repeat(TaskSchedule.tick(1)).schedule();
    }

    private static void openCartInventory(net.minestom.server.entity.Player player, Entity cart,
                                          InventoryType invType, String title) {
        Inventory inv = CART_INV.computeIfAbsent(cart.getUuid(), id -> new Inventory(invType, Component.text(title)));
        player.openInventory(inv);
    }

    /**
     * A chest/hopper minecart's own Container inventory (real vanilla:
     * AbstractMinecartContainer implements Container the same as a block entity) —
     * public so Hoppers.java can push into / pull from one sitting on a rail next to
     * a stationary hopper, the same as any other container.
     */
    public static Inventory cartInventory(Entity cart) {
        InventoryType invType = cart.getEntityType() == EntityType.CHEST_MINECART
                ? InventoryType.CHEST_3_ROW : InventoryType.HOPPER;
        String title = cart.getEntityType() == EntityType.CHEST_MINECART
                ? "Minecart with Chest" : "Minecart with Hopper";
        return CART_INV.computeIfAbsent(cart.getUuid(), id -> new Inventory(invType, Component.text(title)));
    }

    /** A chest/hopper minecart entity currently occupying the given block, if any. */
    public static Entity containerCartAt(Instance instance, Point pos) {
        for (Entity e : instance.getEntities()) {
            EntityType t = e.getEntityType();
            if (t != EntityType.CHEST_MINECART && t != EntityType.HOPPER_MINECART) continue;
            Point p = e.getPosition();
            if (p.blockX() == pos.blockX() && p.blockY() == pos.blockY() && p.blockZ() == pos.blockZ()) return e;
        }
        return null;
    }

    public static Entity spawn(Instance instance, Pos pos) {
        return spawn(instance, pos, EntityType.MINECART);
    }

    public static Entity spawn(Instance instance, Pos pos, EntityType type) {
        Entity cart = new Entity(type);
        cart.setNoGravity(true);           // fully driven by the rail tick
        cart.setInstance(instance, pos);
        return cart;
    }

    private static void tickAll() {
        for (Instance instance : MinecraftServer.getInstanceManager().getInstances()) {
            for (Entity e : instance.getEntities()) {
                if (CART_TYPES.contains(e.getEntityType())) tick(instance, e);
            }
        }
    }

    private static void tick(Instance instance, Entity cart) {
        Pos p = cart.getPosition();
        double speed = cart.hasTag(SPEED) ? cart.getTag(SPEED) : 0.0;
        int[] rp = findRailPos(instance, p);   // {rx, ry, rz} across y-1..y+1 (slopes)

        // remember the last travel direction so a stationary cart still knows its axis
        double yr = Math.toRadians(p.yaw());
        Vec heading = new Vec(-Math.sin(yr), 0, Math.cos(yr));
        if (rp == null) {
            // ran off the end of the track: coast + fall until it settles
            double nx = p.x() + heading.x() * speed, nz = p.z() + heading.z() * speed;
            cart.teleport(new Pos(nx, p.y() - 0.2, nz, p.yaw(), 0));
            cart.setTag(SPEED, speed * FRICTION);
            return;
        }

        int rx = rp[0], ry = rp[1], rz = rp[2];
        Block rail = instance.getBlock(rx, ry, rz);
        String shape = rail.getProperty("shape");
        Vec dir = isCorner(shape) ? cornerExit(shape, heading) : railAxis(shape, heading);

        if (cart.getEntityType() == EntityType.TNT_MINECART) {
            if (!cart.hasTag(FUSE) && rail.key().value().equals("activator_rail")
                    && "true".equals(rail.getProperty("powered"))) {
                cart.setTag(FUSE, 80); // MinecartTNT.primeFuse: 80 ticks (4s), not instant
            }
            if (cart.hasTag(FUSE)) {
                int fuse = cart.getTag(FUSE) - 1;
                if (fuse <= 0) {
                    Point at = cart.getPosition();
                    cart.remove();
                    dev.pointofpressure.minecom.blocks.Explosions.explode(instance, at.add(0, 0.5, 0), 4f, 1.0, null);
                    return;
                }
                cart.setTag(FUSE, fuse);
            }
        }

        if (cart.getEntityType() == EntityType.FURNACE_MINECART) {
            int fuel = cart.hasTag(FUEL) ? cart.getTag(FUEL) : 0;
            if (fuel > 0) {
                speed = Math.min(MAX_SPEED, Math.max(speed, 0.05) + BOOST);
                cart.setTag(FUEL, fuel - 1);
            }
        } else if (cart.getEntityType() == EntityType.HOPPER_MINECART) {
            vacuum(instance, cart, p);
            // MinecartHopper implements the Hopper interface the same as a stationary
            // hopper block — it also sucks from a container directly above its own
            // position, not just loose item entities (vacuum() above). Previously this
            // codebase only modelled the loose-item-vacuum half.
            dev.pointofpressure.minecom.redstone.Hoppers.pull(p, cartInventory(cart));
        }

        if (isPowered(rail)) {
            speed = "true".equals(rail.getProperty("powered"))
                    ? Math.min(MAX_SPEED, speed + BOOST)
                    : Math.max(0, speed - 0.15);
        } else {
            speed *= FRICTION;
            if (speed < 0.01) speed = 0;
        }

        double nx = p.x() + dir.x() * speed, nz = p.z() + dir.z() * speed;
        // pin the off-axis coordinate to the rail centreline
        if (Math.abs(dir.x()) > Math.abs(dir.z())) nz = rz + 0.5; else nx = rx + 0.5;
        double ny = isAscending(shape) ? rampY(shape, nx, nz, rx, rz, ry) : ry + 0.1;

        // cart-to-cart collision: a cart cannot ride through another cart occupying its
        // destination — it queues up behind instead, matching vanilla's soft-collision
        // feel on shared track. Bounded: no lateral push/bounce, just a hard stop/slow.
        Entity blocking = findBlockingCart(instance, cart, nx, ny, nz);
        if (blocking != null) {
            double blockerSpeed = blocking.hasTag(SPEED) ? blocking.getTag(SPEED) : 0.0;
            if (blockerSpeed < speed) {
                nx = p.x();
                nz = p.z();
                ny = p.y();
                speed = blockerSpeed;
            }
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x(), dir.z()));
        cart.teleport(new Pos(nx, ny, nz, yaw, 0));
        cart.setTag(SPEED, speed);
    }

    private static final double CART_COLLISION_DIST = 0.9; // ~ AbstractMinecart's ~0.98-wide hitbox

    /** Finds another cart already occupying (or about to occupy) the given destination. */
    private static Entity findBlockingCart(Instance instance, Entity self, double x, double y, double z) {
        for (Entity e : instance.getEntities()) {
            if (e == self || !CART_TYPES.contains(e.getEntityType())) continue;
            Pos ep = e.getPosition();
            double dx = ep.x() - x, dy = ep.y() - y, dz = ep.z() - z;
            if (dx * dx + dy * dy + dz * dz < CART_COLLISION_DIST * CART_COLLISION_DIST) return e;
        }
        return null;
    }

    /** Hopper minecart: vacuum nearby dropped items into its 5-slot inventory. */
    private static void vacuum(Instance instance, Entity cart, Pos p) {
        Inventory inv = cartInventory(cart);
        for (Entity e : instance.getEntities()) {
            if (!(e instanceof ItemEntity item) || item.isRemoved()) continue;
            if (item.getPosition().distanceSquared(p) > 1.5 * 1.5) continue;
            ItemStack stack = item.getItemStack();
            for (int slot = 0; slot < inv.getSize(); slot++) {
                ItemStack existing = inv.getItemStack(slot);
                if (existing.isAir()) { inv.setItemStack(slot, stack); item.remove(); break; }
                if (existing.material() == stack.material() && existing.amount() + stack.amount() <= 64) {
                    inv.setItemStack(slot, existing.withAmount(existing.amount() + stack.amount()));
                    item.remove();
                    break;
                }
            }
        }
    }

    /** Locate the rail supporting the cart across y, y-1 (drops) and y+1 (climbing slopes). */
    private static int[] findRailPos(Instance instance, Pos p) {
        int x = (int) Math.floor(p.x()), z = (int) Math.floor(p.z()), y = (int) Math.floor(p.y());
        for (int dy : new int[]{0, -1, 1}) {
            if (isRail(instance.getBlock(x, y + dy, z))) return new int[]{x, y + dy, z};
        }
        return null;
    }

    private static boolean isAscending(String s) { return s != null && s.startsWith("ascending_"); }

    /** Cart Y on an ascending rail: ramps from the low edge (rail base) to the high edge (+1). */
    private static double rampY(String shape, double nx, double nz, int rx, int rz, int ry) {
        double f = switch (shape) {
            case "ascending_east" -> nx - rx;
            case "ascending_west" -> rx + 1 - nx;
            case "ascending_south" -> nz - rz;
            case "ascending_north" -> rz + 1 - nz;
            default -> 0;
        };
        return ry + 0.1 + Math.max(0, Math.min(1, f));
    }

    private static boolean isRail(Block b) {
        if (b == null) return false;
        String n = b.key().value();
        return n.equals("rail") || n.equals("powered_rail") || n.equals("detector_rail") || n.equals("activator_rail");
    }

    private static boolean isPowered(Block b) {
        // golden rails only boost while actually redstone-powered (Redstone rail propagation)
        return b.key().value().equals("powered_rail") && "true".equals(b.getProperty("powered"));
    }

    private static boolean isCorner(String shape) {
        return shape != null && (shape.equals("south_east") || shape.equals("south_west")
                || shape.equals("north_east") || shape.equals("north_west"));
    }

    /**
     * Exit direction out of a curved rail given the incoming heading. Corners connect
     * two edges; a cart entering from one exits the other (vanilla turn).
     */
    private static Vec cornerExit(String shape, Vec heading) {
        Vec e = new Vec(1, 0, 0), w = new Vec(-1, 0, 0), s = new Vec(0, 0, 1), n = new Vec(0, 0, -1);
        Vec[] conn = switch (shape) {
            case "south_east" -> new Vec[]{s, e};
            case "south_west" -> new Vec[]{s, w};
            case "north_east" -> new Vec[]{n, e};
            default -> new Vec[]{n, w}; // north_west
        };
        // already heading out one of the connected edges -> continue that way
        for (Vec c : conn) if (dot(heading, c) > 0.5) return c;
        // otherwise the cart entered through a connected edge -> exit the other
        for (int i = 0; i < 2; i++) if (dot(heading, conn[i].mul(-1)) > 0.5) return conn[1 - i];
        return conn[0];
    }

    private static double dot(Vec a, Vec b) { return a.x() * b.x() + a.z() * b.z(); }

    /** Unit direction along the rail for the given shape, oriented with current motion. */
    private static Vec railAxis(String shape, Vec vel) {
        Vec axis;
        if ("east_west".equals(shape) || "ascending_east".equals(shape) || "ascending_west".equals(shape)) {
            axis = new Vec(1, 0, 0);
        } else {
            axis = new Vec(0, 0, 1); // north_south, ascending_north/south, null
        }
        // orient along current motion
        double dot = axis.x() * vel.x() + axis.z() * vel.z();
        return dot < 0 ? axis.mul(-1) : axis;
    }
}
