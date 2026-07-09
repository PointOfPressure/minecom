package dev.pointofpressure.minecom.survival;

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
import net.minestom.server.tag.Tag;

/**
 * Player-fired bows: previously only mobs (skeletons) could shoot arrows — there was no
 * way for the PLAYER to draw and release a bow at all. BowItem.releaseUsing (decompiled):
 * charge fraction {@code pow = clamp(((t/20)^2 + 2*(t/20))/3, 0, 1)} where t is ticks
 * held, minimum 0.1 to loose a shot; velocity and damage scale with that fraction.
 * Arrow damage/knockback/ignite are stashed on the projectile entity as tags and read
 * back by {@code Combat.projectileHit} on impact (mob-fired arrows have no tags and
 * keep their existing flat random damage).
 */
public final class Bow {
    private Bow() {}

    public static final Tag<Float> DAMAGE = Tag.Float("minecom:arrow_damage");
    public static final Tag<Integer> PUNCH = Tag.Integer("minecom:arrow_punch");
    public static final Tag<Boolean> FLAME = Tag.Boolean("minecom:arrow_flame");

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
        if (!creative && !infinity && !consumeArrow(player)) return;

        Pos from = player.getPosition().add(0, player.getEyeHeight(), 0);
        Vec dir = player.getPosition().direction();
        EntityProjectile arrow = new EntityProjectile(player, EntityType.ARROW);
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

        if (!creative) player.setItemInHand(hand, dev.pointofpressure.minecom.data.Items.damageItem(player, bow, 1));
    }

    /** Finds and removes 1 arrow from the player's inventory; true if one was found. */
    private static boolean consumeArrow(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItemStack(i);
            if (stack.material() != Material.ARROW) continue;
            inv.setItemStack(i, stack.amount() <= 1 ? ItemStack.AIR : stack.withAmount(stack.amount() - 1));
            return true;
        }
        return false;
    }
}
