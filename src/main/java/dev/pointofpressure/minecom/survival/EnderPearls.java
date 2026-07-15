package dev.pointofpressure.minecom.survival;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityProjectile;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithBlockEvent;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithEntityEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Thrown ender pearls, decompile-verified against {@code EnderpearlItem}/
 * {@code ThrownEnderpearl} (26.2, freshly decompiled — no cached copy existed before this
 * pass). Thrown at 1.5 shoot units (this project's established {@code 32/3} shoot-unit
 * conversion, same idiom as {@link ThrownPotions}' 0.5); lands on the first block or entity it
 * touches — fluids don't block flight in the decompile (no water-specific gate exists at all,
 * contrary to a common player myth that landing in water suppresses the teleport). On landing,
 * if the thrower is still alive and in the same instance the pearl is in (see
 * {@link #isAllowedToTeleport} for the not-sleeping guard this project can't check yet): they
 * teleport to the landing spot (keeping their own look direction — real vanilla's
 * {@code TeleportTransition} unions {@code ROTATION}/{@code DELTA} as relative, only position
 * is absolute), take 5 {@code DamageType.ENDER_PEARL} damage, and roll a 5% chance (outside
 * Peaceful — this project has no {@code doMobSpawning}-style gamerule store, so vanilla's
 * {@code isSpawningMonsters()} collapses to that one check) to spawn an endermite at the
 * landing spot. The damage bypassing armor and knockback needs no special-casing here — both
 * are bundled data tags ({@code bypasses_armor}/{@code no_knockback} already include
 * {@code minecraft:ender_pearl}) that {@code Combat}'s existing generic mitigation pipeline
 * already reads.
 *
 * <p>Not modeled: the zero-damage "hurt" call real vanilla fires on a direct entity hit (purely
 * an animation/invulnerability-timer nicety with no other gameplay effect), the 32 landing
 * particles (client visual), stasis-chamber chunk-ticket behavior (not scoped in AUDIT.md —
 * this project's chunk loading has no equivalent to vanilla's per-pearl force-load ticket
 * system, {@code ServerPlayer.registerAndUpdateEnderPearlTicket}), and cross-dimension throws
 * (a projectile's instance can't change mid-flight here the way a portal crossing could in
 * real vanilla, so {@code isAllowedToTeleportOwner}'s dimension/portal branch collapses to a
 * plain same-instance check).
 */
public final class EnderPearls {
    private EnderPearls() {}

    private static final float UNIT = 32f / 3.0f; // Bow.java's shoot()-unit conversion
    private static final float POWER = 1.5f; // EnderpearlItem.PROJECTILE_SHOOT_POWER
    private static final float ENDERMITE_CHANCE = 0.05f;
    private static final float TELEPORT_DAMAGE = 5f;

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemEvent.class, e -> {
            if (e.getItemStack().material() != Material.ENDER_PEARL) return;
            Player player = e.getPlayer();
            launch(player);
            if (player.getGameMode() != GameMode.CREATIVE) {
                player.setItemInHand(e.getHand(), e.getItemStack().consume(1));
            }
        });
        events.addListener(ProjectileCollideWithBlockEvent.class,
                e -> onHit(e.getEntity(), e.getCollisionPosition()));
        events.addListener(ProjectileCollideWithEntityEvent.class,
                e -> onHit(e.getEntity(), e.getTarget().getPosition()));
    }

    private static void launch(Player player) {
        Instance instance = player.getInstance();
        if (instance == null) return;
        Pos from = player.getPosition().add(0, player.getEyeHeight(), 0);
        EntityProjectile thrown = new EntityProjectile(player, EntityType.ENDER_PEARL);
        thrown.setInstance(instance, from);
        thrown.setVelocity(player.getPosition().direction().mul(POWER * UNIT));
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        player.playSound(Sound.sound(SoundEvent.ENTITY_ENDER_PEARL_THROW, Sound.Source.NEUTRAL, 0.5f,
                0.4f / (rng.nextFloat() * 0.4f + 0.8f)));
    }

    private static void onHit(Entity projectile, Point at) {
        if (projectile.getEntityType() != EntityType.ENDER_PEARL || projectile.isRemoved()) return;
        Instance instance = projectile.getInstance();
        Entity shooter = projectile instanceof EntityProjectile ep ? ep.getShooter() : null;
        projectile.remove();
        if (instance == null || !(shooter instanceof Player owner) || !isAllowedToTeleport(owner, instance)) {
            return;
        }

        Pos landing = new Pos(at.x(), at.y(), at.z(), owner.getPosition().yaw(), owner.getPosition().pitch());
        owner.teleport(landing);
        Survival.resetFallTracking(owner);
        owner.damage(DamageType.ENDER_PEARL, TELEPORT_DAMAGE);
        instance.playSound(Sound.sound(SoundEvent.ENTITY_PLAYER_TELEPORT, Sound.Source.PLAYER, 1f, 1f),
                at.x(), at.y(), at.z());

        if (!dev.pointofpressure.minecom.Difficulty.isPeaceful()
                && ThreadLocalRandom.current().nextFloat() < ENDERMITE_CHANCE) {
            dev.pointofpressure.minecom.mobs.ai.VanillaMobs.endermite(instance, landing);
        }
    }

    /** ThrownEnderpearl.isAllowedToTeleportOwner, minus the cross-dimension/portal branch (see
     *  class javadoc) and the not-sleeping guard: this project's Beds.java doesn't track a
     *  sleeping pose/state at all (its sleep mechanic is a skip-night timer, not a player-state
     *  machine), so there's nothing to check against — not silently dropped, just genuinely
     *  inapplicable until a sleeping-state exists. */
    private static boolean isAllowedToTeleport(Player owner, Instance pearlInstance) {
        return owner.getInstance() == pearlInstance && !owner.isDead();
    }
}
