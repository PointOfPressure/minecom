package dev.pointofpressure.minecom.mobs;

import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.animal.AnimalMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pig + strider saddle riding, verified against decompiled Pig/Strider/
 * ItemBasedSteering (26.2). Unlike the horse family, saddling needs no taming (any
 * adult pig or strider takes a saddle), and the rider only steers by FACING — real
 * vanilla's {@code getRiddenInput} is a flat forward vector fed through the mob's
 * yaw, which itself snaps to the rider's yaw every tick (Pig/Strider.tickRidden's
 * {@code setRot}); there is no strafe, no reverse, no jump (neither implements
 * PlayerRideableJumping). Speed is {@code MOVEMENT_SPEED attribute * a flat
 * per-species factor * boostFactor} — pigs a flat 0.225, striders 0.55 in lava /
 * 0.35 out of it (Strider.getRiddenSpeed's isSuffocating check, approximated here as
 * "standing in a lava block"). Right-clicking the mount while riding it with the
 * matching stick (carrot-on-a-stick for pigs, warped-fungus-on-a-stick for striders)
 * rolls {@code ItemBasedSteering.boost}: a 140-980 tick window with
 * {@code 1 + 1.15*sin(elapsed/total*pi)} factor (peaks at 2.15x mid-window), refused
 * outright while already boosting (no restacking), and damages the stick by 1
 * durability. Sneaking dismounts, same convention as HappyGhastMob/Riding. Not
 * modeled: strider cold-shaking animation/slowdown off lava beyond the speed
 * factor (client visual + AUDIT.md-adjacent scope), pig lightning-to-zombified-
 * piglin conversion (unrelated to riding).
 */
public final class Steering {
    private Steering() {}

    private static final Set<Material> PIG_FOOD = Set.of(Material.CARROT, Material.POTATO, Material.BEETROOT);
    private static final Map<Integer, Integer> BOOST_TICKS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> BOOST_TOTAL = new ConcurrentHashMap<>();

    // Same tuned-feel precedent as mobs.Riding.RIDDEN_SPEED_SCALE (not a port of
    // vanilla's friction/drag travel() integration).
    private static final double RIDDEN_SPEED_SCALE = 43.0;

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerEntityInteractEvent.class, Steering::interact);
    }

    private static void interact(PlayerEntityInteractEvent e) {
        if (e.getHand() != PlayerHand.MAIN) return;
        if (!(e.getTarget() instanceof EntityCreature mob) || mob.isDead()) return;
        EntityType type = mob.getEntityType();
        if (type != EntityType.PIG && type != EntityType.STRIDER) return;
        Player player = e.getPlayer();
        ItemStack held = player.getItemInMainHand();
        Material boostItem = type == EntityType.PIG ? Material.CARROT_ON_A_STICK : Material.WARPED_FUNGUS_ON_A_STICK;
        boolean saddled = !mob.getEquipment(EquipmentSlot.SADDLE).isAir();

        if (mob.getPassengers().contains(player)) {
            if (held.material() == boostItem) boost(mob, player, held);
            return;
        }
        boolean baby = mob.getEntityMeta() instanceof AnimalMeta am && am.isBaby();
        if (baby || !mob.getPassengers().isEmpty()) return;

        boolean isFood = type == EntityType.PIG && PIG_FOOD.contains(held.material());
        if (!isFood && saddled && !player.isSneaking()) {
            mob.addPassenger(player);
            return;
        }
        if (held.material() == Material.SADDLE && !saddled) {
            mob.setEquipment(EquipmentSlot.SADDLE, ItemStack.of(Material.SADDLE));
            if (player.getGameMode() != GameMode.CREATIVE) {
                player.setItemInMainHand(held.consume(1));
            }
        }
    }

    /**
     * Test hook: arms a boost with an exact, non-random total (production always
     * randomizes via {@link #boost}). Real boost totals are 140-980 ticks, so a
     * short fixed-length PlayTest sampling window can otherwise land anywhere on
     * the {@code sin} ramp-up — including near-zero right after arming — making a
     * distance-moved comparison flaky through no fault of the boost math itself.
     */
    public static void testForceBoost(EntityCreature mob, int total) {
        BOOST_TOTAL.put(mob.getEntityId(), total);
        BOOST_TICKS.put(mob.getEntityId(), 0);
    }

    private static void boost(EntityCreature mob, Player player, ItemStack stick) {
        if (BOOST_TICKS.containsKey(mob.getEntityId())) return; // ItemBasedSteering.boost: no restacking
        BOOST_TOTAL.put(mob.getEntityId(), ThreadLocalRandom.current().nextInt(841) + 140);
        BOOST_TICKS.put(mob.getEntityId(), 0);
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setItemInMainHand(dev.pointofpressure.minecom.data.Items.damageItem(player, stick, 1));
        }
    }

    /** Called once per tick from the mob's own scheduler task (pig: VanillaMobs.animal's
     *  pig special-case; strider: VanillaMobs.strider). */
    public static void tick(EntityCreature mob) {
        Integer ticks = BOOST_TICKS.get(mob.getEntityId());
        if (ticks != null) {
            int total = BOOST_TOTAL.get(mob.getEntityId());
            if (ticks > total) {
                BOOST_TICKS.remove(mob.getEntityId());
                BOOST_TOTAL.remove(mob.getEntityId());
            } else {
                BOOST_TICKS.put(mob.getEntityId(), ticks + 1);
            }
        }

        Player rider = mob.getPassengers().stream()
                .filter(p -> p instanceof Player).map(p -> (Player) p).findFirst().orElse(null);
        if (rider == null) return;
        if (rider.inputs().shift()) {
            mob.removePassenger(rider);
            BOOST_TICKS.remove(mob.getEntityId());
            BOOST_TOTAL.remove(mob.getEntityId());
            return;
        }
        if (mob.getEquipment(EquipmentSlot.SADDLE).isAir()) return;

        float yaw = rider.getPosition().yaw();
        mob.setView(yaw, mob.getPosition().pitch());

        double speed = mob.getAttributeValue(Attribute.MOVEMENT_SPEED) * baseFactor(mob) * boostFactor(mob) * RIDDEN_SPEED_SCALE;
        double yawRad = Math.toRadians(yaw);
        Vec forward = new Vec(-Math.sin(yawRad), 0, Math.cos(yawRad)).mul(speed);
        Vec current = mob.getVelocity();
        mob.setVelocity(new Vec(forward.x(), current.y(), forward.z()));
    }

    private static double baseFactor(EntityCreature mob) {
        if (mob.getEntityType() == EntityType.PIG) return 0.225;
        boolean inLava = mob.getInstance() != null && mob.getInstance().getBlock(mob.getPosition()).compare(Block.LAVA);
        return inLava ? 0.55 : 0.35;
    }

    /** ItemBasedSteering.boostFactor. */
    private static double boostFactor(EntityCreature mob) {
        Integer ticks = BOOST_TICKS.get(mob.getEntityId());
        Integer total = BOOST_TOTAL.get(mob.getEntityId());
        if (ticks == null || total == null || total == 0) return 1.0;
        return 1.0 + 1.15 * Math.sin((double) ticks / total * Math.PI);
    }
}
