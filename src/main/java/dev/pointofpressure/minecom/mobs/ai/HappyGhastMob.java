package dev.pointofpressure.minecom.mobs.ai;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.animal.HappyGhastMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Happy Ghast, ported from decompiled HappyGhast.java (26.1.2): the passive
 * rideable flying mount. A harness (any dyed *_harness) right-clicked onto
 * an adult equips its BODY slot (shears take it back off); a harnessed
 * ghast right-clicked bare mounts up to 4 players through Minestom's native
 * passenger API. The FIRST passenger steers while harnessed and not on a
 * still-timeout: strafe from left/right, pitch-projected forward (backward
 * halves and reverses it), jump adds +0.5 up — all scaled 3.9 x
 * FLYING_SPEED(0.05) and integrated with vanilla's 0.91 flying friction;
 * yaw eases 8%/tick toward the rider's, pitch is the rider's halved. A
 * player standing on its 4x4 top turns it into a still platform (10gt
 * timeout, refreshed while they stand, 60gt spawn grace). Unridden it
 * drifts ghast-style and heals 1 HP/30s (1/s in rain). Sneaking dismounts.
 * Designed around the HANDOFF community gotchas: movement is pure velocity
 * (never the Navigator) while ridden. Not modeled (AUDIT.md): per-seat
 * passenger attachment offsets (all riders sit at the client's default
 * point), baby ghastling brain/scale, snowball tempt, leash quad-offsets,
 * natural spawning, home-restriction radius.
 */
public final class HappyGhastMob {

    private static final Map<Integer, HappyGhastMob> GHASTS = new ConcurrentHashMap<>();

    private static final int MAX_PASSENGERS = 4;
    private static final double FLYING_SPEED = 0.05;
    private static final double RIDDEN_INPUT_SCALE = 3.9 * FLYING_SPEED;
    private static final double FLYING_FRICTION = 0.91;
    private static final int MAX_STILL_TIMEOUT = 10;
    private static final int SPAWN_GRACE = 60;

    private final EntityCreature mob;
    private final VBrain brain;
    private Vec velocity = Vec.ZERO; // vanilla-style per-tick delta, friction-integrated
    private int stillTimeout;
    private long age;

    public static HappyGhastMob of(Entity entity) {
        return GHASTS.get(entity.getEntityId());
    }

    /** HappyGhast.createAttributes. */
    public static EntityCreature spawn(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.HAPPY_GHAST);
        VBrain brain = VanillaMobs.brain(mob, 0.05, 16, 0, 20, 0);
        mob.setNoGravity(true);
        new HappyGhastMob(mob, brain);
        mob.setInstance(instance, pos);
        return mob;
    }

    private HappyGhastMob(EntityCreature mob, VBrain brain) {
        this.mob = mob;
        this.brain = brain;
        GHASTS.put(mob.getEntityId(), this);
        mob.scheduler().buildTask(this::tick).repeat(TaskSchedule.tick(1)).schedule();
    }

    /** Harness equip/strip and mounting, from HappyGhast.mobInteract. */
    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerEntityInteractEvent.class, e -> {
            if (e.getTarget().getEntityType() != EntityType.HAPPY_GHAST) return;
            HappyGhastMob ghast = of(e.getTarget());
            if (ghast == null) return;
            ghast.interact(e.getPlayer(), e.getPlayer().getItemInHand(e.getHand()), e.getHand());
        });
    }

    private void interact(Player player, ItemStack held, net.minestom.server.entity.PlayerHand hand) {
        boolean harnessed = !mob.getEquipment(EquipmentSlot.BODY).isAir();
        String key = held.material().key().value();
        if (!harnessed && key.endsWith("_harness")) {
            mob.setEquipment(EquipmentSlot.BODY, ItemStack.of(held.material()));
            if (player.getGameMode() != net.minestom.server.entity.GameMode.CREATIVE) {
                player.setItemInHand(hand, held.consume(1));
            }
            sound(SoundEvent.ENTITY_HAPPY_GHAST_EQUIP);
            return;
        }
        if (harnessed && held.material() == Material.SHEARS && player.isSneaking()) {
            ItemStack harness = mob.getEquipment(EquipmentSlot.BODY);
            mob.setEquipment(EquipmentSlot.BODY, ItemStack.AIR);
            dev.pointofpressure.minecom.survival.Survival.dropItem(player, harness, true);
            sound(SoundEvent.ENTITY_HAPPY_GHAST_UNEQUIP);
            return;
        }
        if (harnessed && !player.isSneaking() && mob.getPassengers().size() < MAX_PASSENGERS
                && player.getVehicle() == null) {
            if (mob.getPassengers().isEmpty()) sound(SoundEvent.ENTITY_HAPPY_GHAST_HARNESS_GOGGLES_DOWN);
            mob.addPassenger(player);
        }
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (mob.isRemoved() || mob.isDead() || mob.getInstance() == null) {
            GHASTS.remove(mob.getEntityId());
            return;
        }
        age++;
        tickStillTimeout();
        tickDismounts();
        continuousHeal();

        Player controller = controllingPassenger();
        if (controller != null) {
            tickRidden(controller);
        } else {
            velocity = velocity.mul(FLYING_FRICTION);
            // HappyGhastFloatGoal: gentle ghast-style drift when idle
            if (stillTimeout <= 0 && brain.random.nextInt(140) == 0) {
                double a = brain.random.nextDouble() * Math.PI * 2;
                mob.setVelocity(new Vec(Math.cos(a) * 1.2, (brain.random.nextDouble() - 0.5) * 0.8, Math.sin(a) * 1.2));
            } else if (stillTimeout > 0) {
                mob.setVelocity(Vec.ZERO);
            }
        }
    }

    /** getControllingPassenger: first passenger, harness on, not held still. */
    private Player controllingPassenger() {
        if (mob.getEquipment(EquipmentSlot.BODY).isAir() || stillTimeout > 0) return null;
        for (Entity passenger : mob.getPassengers()) {
            if (passenger instanceof Player player) return player;
        }
        return null;
    }

    /** getRiddenInput + tickRidden + travelFlying, folded into velocity space. */
    private void tickRidden(Player controller) {
        var inputs = controller.inputs();
        double strafe = (inputs.left() ? 1 : 0) - (inputs.right() ? 1 : 0);
        double zza = (inputs.forward() ? 1 : 0) - (inputs.backward() ? 1 : 0);
        double forward = 0, up = 0;
        float pitchRad = (float) Math.toRadians(controller.getPosition().pitch());
        if (zza != 0) {
            double forwardLook = Math.cos(pitchRad);
            double upLook = -Math.sin(pitchRad);
            if (zza < 0) {
                forwardLook *= -0.5;
                upLook *= -0.5;
            }
            forward = forwardLook;
            up = upLook;
        }
        if (inputs.jump()) up += 0.5;

        // rotate: yaw eases 8% toward the rider, pitch is the rider's halved
        float riderYaw = controller.getPosition().yaw();
        float yaw = mob.getPosition().yaw();
        float diff = wrapDegrees(riderYaw - yaw);
        yaw += diff * 0.08f;
        mob.setView(yaw, controller.getPosition().pitch() * 0.5f);

        // local (strafe, up, forward) -> world, vanilla travelFlying integration
        double yawRad = Math.toRadians(yaw);
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        Vec accel = new Vec(
                (strafe * cos - forward * sin) * RIDDEN_INPUT_SCALE,
                up * RIDDEN_INPUT_SCALE,
                (forward * cos + strafe * sin) * RIDDEN_INPUT_SCALE);
        velocity = velocity.mul(FLYING_FRICTION).add(accel);
        mob.setVelocity(velocity.mul(20)); // per-tick delta -> blocks/second
    }

    /** HappyGhast.tick + scanPlayerAboveGhast: a player on top holds it still. */
    private void tickStillTimeout() {
        boolean playerAbove = false;
        double top = mob.getPosition().y() + 4.0; // adult ghast box is 4x4x4
        for (Player player : mob.getInstance().getPlayers()) {
            if (player.getVehicle() == mob) continue;
            double dy = player.getPosition().y() - top;
            if (dy < -0.2 || dy > 1.0) continue;
            if (Math.abs(player.getPosition().x() - mob.getPosition().x()) <= 2.5
                    && Math.abs(player.getPosition().z() - mob.getPosition().z()) <= 2.5) {
                playerAbove = true;
                break;
            }
        }
        if (playerAbove) {
            stillTimeout = MAX_STILL_TIMEOUT;
        } else if (stillTimeout > 0 && age > SPAWN_GRACE) {
            stillTimeout--;
        }
        if (mob.getEntityMeta() instanceof HappyGhastMeta meta) {
            meta.setStaysStill(stillTimeout > 0);
        }
    }

    private void tickDismounts() {
        for (Entity passenger : java.util.List.copyOf(mob.getPassengers())) {
            if (passenger instanceof Player player && player.inputs().shift()) {
                mob.removePassenger(player);
                player.teleport(mob.getPosition().withView(player.getPosition().yaw(),
                        player.getPosition().pitch()));
                if (mob.getPassengers().isEmpty()) sound(SoundEvent.ENTITY_HAPPY_GHAST_HARNESS_GOGGLES_UP);
            }
        }
    }

    /** continuousHeal: 1 HP per 600gt, per 20gt while rained on (clouds not modeled). */
    private void continuousHeal() {
        if (mob.getHealth() >= (float) mob.getAttributeValue(Attribute.MAX_HEALTH)) return;
        boolean fast = dev.pointofpressure.minecom.survival.WeatherCycle.isRaining(mob.getInstance());
        if (age % (fast ? 20 : 600) == 0) {
            mob.setHealth(Math.min((float) mob.getAttributeValue(Attribute.MAX_HEALTH),
                    mob.getHealth() + 1f));
        }
    }

    // ------------------------------------------------------------------ hooks

    /** Playtest hook. */
    public boolean isStill() {
        return stillTimeout > 0;
    }

    public EntityCreature entity() {
        return mob;
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private void sound(SoundEvent event) {
        Instance in = mob.getInstance();
        if (in == null) return;
        Pos at = mob.getPosition();
        in.playSound(Sound.sound(event, Sound.Source.NEUTRAL, 1f, 1f), at.x(), at.y(), at.z());
    }
}
