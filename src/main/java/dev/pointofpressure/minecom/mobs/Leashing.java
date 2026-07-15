package dev.pointofpressure.minecom.mobs;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leads, verified against decompiled Leashable/LeashFenceKnotEntity (26.2), with one
 * deliberate engine-level gap: Minestom's animal entity metadata has no "leash
 * holder" field at all (only {@code LeashKnotMeta.IS_LEASH_HOLDER}, a marker on the
 * knot itself) — grep-confirmed against the decompiled Minestom sources, not this
 * project's own choice — so nothing here can make the client render the tether line
 * to a leashed mob. The gameplay mechanics are still real: right-clicking an
 * unleashed mob with a lead attaches it to the player (consuming the lead);
 * right-clicking a fence re-homes every mob currently leashed to that player within
 * 10 blocks onto a {@code LeashFenceKnotEntity} at that post (spawned once per
 * block, reused on repeat clicks — {@code LeashFenceKnotEntity.getOrCreateKnot});
 * right-clicking a leashed mob with an empty hand detaches it and drops a lead item.
 * Distance handling is a simplified port of Leashable's real constants
 * (LEASH_TOO_FAR_DIST=12: pull toward the holder; MAXIMUM_ALLOWED_LEASHED_DIST=16:
 * snap, dropping a lead at the mob) as a plain velocity impulse rather than the full
 * spring/wrench/angular-momentum physics real vanilla 26.2 uses for the visual rope
 * — another instance of this project's established "simplify the physics, keep the
 * gameplay outcome" precedent (see HappyGhastMob's own leash note). A short denylist
 * excludes boss-scale mobs vanilla itself refuses to leash.
 */
public final class Leashing {
    private Leashing() {}

    private static final Set<EntityType> UNLEASHABLE = Set.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN, EntityType.GIANT);

    private static final double TOO_FAR_DIST = 12.0;
    private static final double MAX_DIST = 16.0;
    private static final double FENCE_ATTACH_RADIUS = 10.0;

    private static final Map<EntityCreature, Entity> LEASHED = new ConcurrentHashMap<>();
    private static final Map<String, Entity> KNOTS = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerEntityInteractEvent.class, Leashing::interactMob);
        events.addListener(PlayerBlockInteractEvent.class, Leashing::interactFence);
        MinecraftServer.getSchedulerManager().buildTask(Leashing::tick).repeat(TaskSchedule.tick(1)).schedule();
    }

    private static void interactMob(PlayerEntityInteractEvent e) {
        if (e.getHand() != PlayerHand.MAIN) return;
        if (!(e.getTarget() instanceof EntityCreature mob) || mob.isDead()) return;
        Player player = e.getPlayer();
        ItemStack held = player.getItemInMainHand();

        if (LEASHED.containsKey(mob)) {
            if (held.isAir()) detach(mob);
            return;
        }
        if (held.material() == Material.LEAD && !UNLEASHABLE.contains(mob.getEntityType())) {
            LEASHED.put(mob, player);
            if (player.getGameMode() != GameMode.CREATIVE) {
                player.setItemInMainHand(held.consume(1));
            }
        }
    }

    private static void interactFence(PlayerBlockInteractEvent e) {
        String key = e.getBlock().key().value();
        if (!key.endsWith("_fence")) return;
        Player player = e.getPlayer();
        Point pos = e.getBlockPosition();

        boolean anyRehomed = false;
        for (var entry : LEASHED.entrySet()) {
            EntityCreature mob = entry.getKey();
            if (entry.getValue() != player || mob.isDead()) continue;
            if (mob.getPosition().distance(new Pos(pos.x() + 0.5, pos.y(), pos.z() + 0.5)) > FENCE_ATTACH_RADIUS) continue;
            entry.setValue(knotAt(e.getInstance(), pos));
            anyRehomed = true;
        }
        if (anyRehomed) e.setBlockingItemUse(true);
    }

    private static Entity knotAt(Instance instance, Point pos) {
        String key = posKey(pos);
        return KNOTS.computeIfAbsent(key, k -> {
            Entity knot = new Entity(EntityType.LEASH_KNOT);
            knot.setInstance(instance, new Pos(pos.x() + 0.5, pos.y() + 0.375, pos.z() + 0.5));
            return knot;
        });
    }

    private static void detach(EntityCreature mob) {
        LEASHED.remove(mob);
        dropLead(mob);
    }

    private static void dropLead(EntityCreature mob) {
        Instance instance = mob.getInstance();
        if (instance == null) return;
        ItemEntity item = new ItemEntity(ItemStack.of(Material.LEAD));
        item.setInstance(instance, mob.getPosition().add(0, 0.5, 0));
    }

    private static String posKey(Point p) {
        return p.blockX() + "," + p.blockY() + "," + p.blockZ();
    }

    // ------------------------------------------------------------------ tick

    private static void tick() {
        for (var it = LEASHED.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            EntityCreature mob = entry.getKey();
            Entity holder = entry.getValue();
            if (mob.isDead() || mob.isRemoved() || holder.isRemoved()) {
                it.remove();
                continue;
            }
            double dist = mob.getPosition().distance(holder.getPosition());
            if (dist > MAX_DIST) {
                it.remove();
                dropLead(mob);
                continue;
            }
            if (dist > TOO_FAR_DIST) {
                // Set (not accumulate) the horizontal pull each tick — an additive
                // impulse here would compound every tick the mob stays past the
                // threshold and run away to absurd speeds, unlike Riding/Steering's
                // ridden-velocity writes which are already re-derived fresh each tick.
                Vec toward = holder.getPosition().sub(mob.getPosition()).asVec();
                if (toward.length() > 0.01) {
                    double pullSpeed = Math.min(dist - TOO_FAR_DIST, 4.0) * 2.0;
                    Vec dir = toward.normalize();
                    Vec current = mob.getVelocity();
                    mob.setVelocity(new Vec(dir.x() * pullSpeed, current.y(), dir.z() * pullSpeed));
                }
            }
        }
    }

    public static boolean isLeashed(EntityCreature mob) {
        return LEASHED.containsKey(mob);
    }
}
