package dev.pointofpressure.minecom.survival;

import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityProjectile;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.item.PlayerCancelItemUseEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.tag.Tag;

/**
 * Player-fired bows: previously only mobs (skeletons) could shoot arrows — there was no
 * way for the PLAYER to draw and release a bow at all. BowItem.releaseUsing (decompiled):
 * charge fraction {@code pow = clamp(((t/20)^2 + 2*(t/20))/3, 0, 1)} where t is ticks
 * held, minimum 0.1 to loose a shot; velocity and damage scale with that fraction.
 * Arrow damage/knockback/ignite are stashed on the projectile entity as tags and read
 * back by {@code Combat.projectileHit} on impact (mob-fired arrows have no tags and
 * keep their existing flat random damage). Tipped/spectral arrows (ArrowItem/
 * TippedArrowItem/SpectralArrowItem, 26.2 decompile-verified) are nocked the same way
 * as a plain arrow — real vanilla's {@code ItemTags.ARROWS} covers all three — and
 * carry their potion/spectral identity onto the fired projectile via {@link #POTION}
 * (read back by {@code Combat.projectileHit}).
 */
public final class Bow {
    private Bow() {}

    public static final Tag<Float> DAMAGE = Tag.Float("minecom:arrow_damage");
    public static final Tag<Integer> PUNCH = Tag.Integer("minecom:arrow_punch");
    public static final Tag<Boolean> FLAME = Tag.Boolean("minecom:arrow_flame");
    /** Tipped arrow's carried potion type key (e.g. "minecraft:slowness"); absent for a
     *  plain or spectral arrow. Read by Combat.projectileHit, applied at real vanilla's
     *  bundled tipped_arrow item default potion_duration_scale of 0.125 (1/8 duration). */
    public static final Tag<String> POTION = Tag.String("minecom:arrow_potion");

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerCancelItemUseEvent.class, e -> {
            if (e.getItemStack().material() != Material.BOW) return;
            shoot(e.getPlayer(), e.getHand(), e.getItemStack(), (int) e.getUseDuration());
        });
    }

    private static void shoot(Player player, PlayerHand hand, ItemStack bow, int ticksHeld) {
        float t = ticksHeld / 20f;
        float power = Math.min(1f, (t * t + t * 2f) / 3f);
        if (power < 0.1f) return; // BowItem.releaseUsing: too short a draw, no shot

        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        boolean infinity = dev.pointofpressure.minecom.data.Enchants.level(bow, "infinity") > 0;
        ItemStack ammo = ItemStack.AIR;
        if (!creative && !infinity) {
            ammo = consumeArrow(player);
            if (ammo.isAir()) return;
        }
        // Creative/Infinity: real vanilla still nocks whichever arrow type is present
        // (without consuming it) to pick what's fired; this project's pre-existing
        // simplification always fires a plain arrow in that case instead, unchanged here.

        Pos from = player.getPosition().add(0, player.getEyeHeight(), 0);
        Vec dir = player.getPosition().direction();
        EntityType arrowType = ammo.material() == Material.SPECTRAL_ARROW
                ? EntityType.SPECTRAL_ARROW : EntityType.ARROW;
        EntityProjectile arrow = new EntityProjectile(player, arrowType);
        arrow.setInstance(player.getInstance(), from);
        arrow.setVelocity(dir.mul(power * 32));

        int powerLevel = dev.pointofpressure.minecom.data.Enchants.level(bow, "power");
        int punch = dev.pointofpressure.minecom.data.Enchants.level(bow, "punch");
        int flame = dev.pointofpressure.minecom.data.Enchants.level(bow, "flame");
        float damage = 2f + 4f * power;
        if (powerLevel > 0) damage += damage * 0.5f * powerLevel;
        arrow.setTag(DAMAGE, damage);
        if (punch > 0) arrow.setTag(PUNCH, punch);
        if (flame > 0) arrow.setTag(FLAME, true);
        if (ammo.material() == Material.TIPPED_ARROW) {
            PotionContents contents = ammo.get(DataComponents.POTION_CONTENTS);
            if (contents != null && contents.potion() != null) {
                arrow.setTag(POTION, contents.potion().key().value());
            }
        }

        if (!creative) player.setItemInHand(hand, dev.pointofpressure.minecom.data.Items.damageItem(player, bow, 1));
    }

    /** ArrowItem/TippedArrowItem/SpectralArrowItem all satisfy real vanilla's
     *  {@code ItemTags.ARROWS} — any of the three counts as valid bow/crossbow ammo. */
    static boolean isArrowFamily(Material m) {
        return m == Material.ARROW || m == Material.TIPPED_ARROW || m == Material.SPECTRAL_ARROW;
    }

    /**
     * Finds and removes 1 arrow-family item, returning the stack it came from (a single
     * copy, pre-decrement) or AIR if none. ProjectileWeaponItem.getHeldProjectile
     * (decompile-verified) checks the offhand first, then falls back to a plain
     * inventory scan — previously this only ever scanned the general inventory, so an
     * arrow held in the offhand was invisible to it (and, worse, NOT what got consumed
     * even when a bow-in-mainhand/arrows-in-offhand player also happened to have arrows
     * elsewhere in their inventory).
     */
    private static ItemStack consumeArrow(Player player) {
        ItemStack offhand = player.getItemInOffHand();
        if (isArrowFamily(offhand.material())) {
            player.setItemInOffHand(offhand.amount() <= 1 ? ItemStack.AIR : offhand.withAmount(offhand.amount() - 1));
            return offhand;
        }
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItemStack(i);
            if (!isArrowFamily(stack.material())) continue;
            inv.setItemStack(i, stack.amount() <= 1 ? ItemStack.AIR : stack.withAmount(stack.amount() - 1));
            return stack;
        }
        return ItemStack.AIR;
    }
}
