package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.data.VanillaData;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.EntityStatuses;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.component.ConsumeEffect;
import net.minestom.server.item.component.DeathProtection;
import net.minestom.server.potion.CustomPotionEffect;
import net.minestom.server.potion.Potion;

/**
 * Totem of Undying, decompile-verified against {@code LivingEntity.checkTotemDeathProtection}
 * (26.2, freshly decompiled — {@code vanilla-src/net/minecraft/world/entity/LivingEntity.java}
 * lines 1379-1404) and the {@code DeathProtection}/{@code ConsumeEffect} data it reads (26.2,
 * {@code net.minecraft.world.item.component.DeathProtection.TOTEM_OF_UNDYING}): main hand then
 * off hand are checked (in that order) for an item carrying {@code DataComponents.DEATH_PROTECTION};
 * the first one found is consumed, the entity's effects are cleared, the component's own
 * {@code deathEffects} list is applied (real vanilla's default is
 * {@code ClearAllStatusEffectsConsumeEffect} + {@code ApplyStatusEffectsConsumeEffect(
 * [Regeneration II/900t, Absorption II/100t, Fire Resistance I/800t])} — read generically from
 * the item's own component here, not hardcoded, so a modded/renamed totem with different
 * {@code death_effects} data still behaves correctly), health is set to 1.0, and entity status
 * 35 is broadcast ({@code EntityStatuses.LivingEntity.PLAY_TOTEM_UNDYING_ANIMATION_SOUND} —
 * Minestom's own name for real vanilla's {@code broadcastEntityEvent(this, 35)}), which is what
 * drives the totem particle burst AND its sound client-side; nothing else needs to play a sound
 * server-side. Real vanilla's one hard gate: a damage type tagged {@code bypasses_invulnerability}
 * (e.g. {@code /kill}, out-of-world damage) skips totem protection entirely — same
 * {@code VanillaData.damageTypeHasTag} lookup {@code Combat.java} already uses for
 * {@code bypasses_armor}/{@code is_explosion}.
 *
 * <p>Registered on {@code EntityDamageEvent} AFTER {@code Combat.register} (see Bootstrap):
 * Combat's own listener is what applies armor/resistance/enchantment reduction to
 * {@code e.getDamage()}, and Minestom fires every registered listener for one event in
 * registration order before its own continuation applies the final amount to health — so
 * running after Combat means this sees the FULLY reduced damage, matching real vanilla's order
 * (armor/resistance apply in {@code hurtServer} before {@code actuallyHurt} ever reaches
 * {@code checkTotemDeathProtection}). Real vanilla lets health drop to/below zero and then heals
 * back to 1 inside the same synchronous call; Minestom's engine only applies
 * {@code EntityDamageEvent}'s final amount to health in ITS OWN post-listener continuation
 * (confirmed by reading {@code LivingEntity.damage}), so cancelling the event here and setting
 * health to 1 directly reaches the identical end state (health==1, no death) without ever letting
 * the transient negative-health state exist — an implementation-detail difference, not a
 * behavioral one. Lethality is predicted as {@code player.getHealth() <= amount}: this project
 * has no Absorption-effect/{@code additionalHearts} model yet (AUDIT.md's Potions.java entry —
 * "Absorption/golden apples?" is still open), so unlike real vanilla's absorption-first
 * subtraction there is nothing else to account for today; whichever lands the Absorption effect
 * will need to feed it into this same check.
 */
public final class Totems {
    private Totems() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(EntityDamageEvent.class, Totems::checkDeathProtection);
    }

    private static void checkDeathProtection(EntityDamageEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player player)) return;

        String typeId = e.getDamage().getType().key().asString();
        if (VanillaData.damageTypeHasTag(typeId, "bypasses_invulnerability")) return;

        float amount = e.getDamage().getAmount();
        if (amount <= 0 || player.getHealth() - amount > 0) return; // not a lethal hit

        ItemStack totem = null;
        PlayerHand totemHand = null;
        for (PlayerHand hand : PlayerHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.has(DataComponents.DEATH_PROTECTION)) {
                totem = stack;
                totemHand = hand;
                break;
            }
        }
        if (totem == null) return;

        e.setCancelled(true);
        player.setItemInHand(totemHand, totem.consume(1));

        DeathProtection protection = totem.get(DataComponents.DEATH_PROTECTION);
        for (ConsumeEffect effect : protection.deathEffects()) {
            applyDeathEffect(effect, player);
        }
        player.setHealth(1f);
        player.triggerStatus((byte) EntityStatuses.LivingEntity.PLAY_TOTEM_UNDYING_ANIMATION_SOUND);
    }

    /** ConsumeEffect.apply for the two effect kinds real vanilla's TOTEM_OF_UNDYING data uses;
     *  the other kinds (RemoveEffects/TeleportRandomly/PlaySound) are legal ConsumeEffect
     *  shapes but never appear in death-protection data, ignored defensively rather than
     *  silently mis-handled. */
    private static void applyDeathEffect(ConsumeEffect effect, Player player) {
        if (effect instanceof ConsumeEffect.ClearAllEffects) {
            player.clearEffects();
        } else if (effect instanceof ConsumeEffect.ApplyEffects apply) {
            for (CustomPotionEffect cpe : apply.effects()) {
                player.addEffect(new Potion(cpe.id(), (byte) cpe.amplifier(), cpe.duration()));
            }
        }
    }
}
