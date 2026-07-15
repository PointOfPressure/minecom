package dev.pointofpressure.minecom.survival;

import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerStartFlyingWithElytraEvent;
import net.minestom.server.event.player.PlayerStopFlyingWithElytraEvent;
import net.minestom.server.event.player.PlayerTickEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.FireworkList;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Elytra gliding + firework boost (decompile-verified against {@code LivingEntity}'s
 * {@code canGlide}/{@code updateFallFlying}/{@code checkFallDistanceAccumulation} and
 * {@code FireworkRocketEntity}/{@code FireworkRocketItem}, 26.2). Minestom's raw
 * {@code ClientEntityActionPacket} handler sets {@code flyingWithElytra} unconditionally
 * and fires {@link PlayerStartFlyingWithElytraEvent} with none of real vanilla's gating —
 * this class supplies that gate, both at the moment of deploy and continuously every tick
 * (real vanilla re-checks {@code canGlide()} every tick too, not just at the start), plus
 * the per-tick durability wear and the firework boost impulse. The glide flight path itself
 * (pitch-to-speed conversion, lift, drag) is not simulated server-side at all: this project
 * targets real vanilla clients, which already run that exact physics locally and simply
 * report their resulting position — only the parts a client can't authoritatively decide by
 * itself (whether gliding is ALLOWED to start/continue, equipment wear, and the boost
 * velocity nudge, which vanilla itself applies server-side then syncs to the client) need a
 * server-side model.
 */
public final class Elytra {
    private Elytra() {}

    private static final Random RANDOM = new Random();
    private static final Map<UUID, Integer> GLIDE_TICKS = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerStartFlyingWithElytraEvent.class, e -> {
            if (!canGlide(e.getPlayer())) e.getPlayer().setFlyingWithElytra(false);
        });
        events.addListener(PlayerTickEvent.class, e -> tick(e.getPlayer()));
        events.addListener(PlayerUseItemEvent.class, Elytra::useFirework);
        events.addListener(PlayerDisconnectEvent.class, e -> GLIDE_TICKS.remove(e.getPlayer().getUuid()));
    }

    /** LivingEntity.canGlide (decompile-verified): airborne, not riding, no Levitation, and at
     *  least one equipped item carries the real {@code minecraft:glider} component and won't
     *  break on its next durability hit — only the elytra (chestplate slot) has that component
     *  in vanilla data, so this project checks the chestplate slot specifically rather than
     *  every slot. */
    private static boolean canGlide(Player p) {
        if (p.isOnGround() || p.getVehicle() != null) return false;
        if (Potions.effectLevel(p, PotionEffect.LEVITATION) > 0) return false;
        return canGlideUsing(p.getEquipment(EquipmentSlot.CHESTPLATE));
    }

    private static boolean canGlideUsing(ItemStack stack) {
        if (!stack.has(DataComponents.GLIDER)) return false;
        Integer max = stack.get(DataComponents.MAX_DAMAGE);
        if (max == null || max <= 0) return true;
        Integer dmg = stack.get(DataComponents.DAMAGE);
        return (dmg == null ? 0 : dmg) + 1 < max; // ItemStack.nextDamageWillBreak, inverted
    }

    private static void tick(Player p) {
        UUID id = p.getUuid();
        if (!p.isFlyingWithElytra()) {
            GLIDE_TICKS.remove(id);
            return;
        }
        // LivingEntity.updateFallFlying re-validates canGlide() every tick — Minestom itself
        // only auto-stops on ground contact, so a durability break or a mid-flight Levitation
        // effect needs this project's own enforcement.
        if (!canGlide(p)) {
            p.setFlyingWithElytra(false);
            GLIDE_TICKS.remove(id);
            net.minestom.server.event.EventDispatcher.call(new PlayerStopFlyingWithElytraEvent(p));
            return;
        }
        int ticks = GLIDE_TICKS.merge(id, 1, Integer::sum);
        // updateFallFlying: checked every 10 ticks, applied on every OTHER such mark — a flat
        // 1 durability off a random glide-capable slot every 20 ticks. Only the chestplate
        // qualifies here (see canGlideUsing), so it's the only slot that can be picked.
        if (ticks % 10 == 0 && (ticks / 10) % 2 == 0) {
            ItemStack chest = p.getEquipment(EquipmentSlot.CHESTPLATE);
            p.setEquipment(EquipmentSlot.CHESTPLATE,
                    dev.pointofpressure.minecom.data.Items.damageItem(p, chest, 1));
        }
    }

    /** FireworkRocketItem.use: PASS (no-op) unless the user is currently gliding. */
    private static void useFirework(PlayerUseItemEvent e) {
        if (e.getItemStack().material() != Material.FIREWORK_ROCKET) return;
        Player player = e.getPlayer();
        if (!player.isFlyingWithElytra()) return;

        FireworkList fireworks = e.getItemStack().get(DataComponents.FIREWORKS);
        int flightDuration = fireworks != null ? fireworks.flightDuration() : 0;
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setItemInHand(e.getHand(), e.getItemStack().consume(1));
        }
        // FireworkRocketEntity's ctor: lifetime = 10*flightCount + two independent small rolls,
        // flightCount = 1 + the item's flight_duration component.
        int lifetime = 10 * (1 + flightDuration) + RANDOM.nextInt(6) + RANDOM.nextInt(7);
        boost(player, lifetime);
    }

    /** FireworkRocketEntity.tick's attached-boost branch (decompile-verified): every tick,
     *  nudge velocity toward 1.5x the look direction (lerp factor 0.5) plus a flat 0.1x look
     *  addition, for the rocket's real lifetime. Real vanilla keeps the (now un-boosting)
     *  rocket entity attached until it explodes even if the player stops gliding early, purely
     *  so a star-firework can still detonate for its damage payload later — this project
     *  doesn't model exploding-firework damage at all (a plain flight-duration rocket, the
     *  actual point of a boost, never carries stars anyway), so the boost loop simply ends
     *  the moment gliding stops instead of tracking that tail state. */
    private static void boost(Player player, int ticksLeft) {
        if (ticksLeft <= 0 || player.isRemoved() || player.isDead() || !player.isFlyingWithElytra()) return;
        Vec look = player.getPosition().direction();
        Vec movement = player.getVelocity();
        player.setVelocity(movement.add(
                look.x() * 0.1 + (look.x() * 1.5 - movement.x()) * 0.5,
                look.y() * 0.1 + (look.y() * 1.5 - movement.y()) * 0.5,
                look.z() * 0.1 + (look.z() * 1.5 - movement.z()) * 0.5));
        player.scheduler().buildTask(() -> boost(player, ticksLeft - 1))
                .delay(TaskSchedule.tick(1)).schedule();
    }
}
