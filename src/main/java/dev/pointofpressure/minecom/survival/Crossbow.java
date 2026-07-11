package dev.pointofpressure.minecom.survival;

import dev.pointofpressure.minecom.data.Enchants;
import dev.pointofpressure.minecom.data.Items;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityProjectile;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.item.PlayerBeginItemUseEvent;
import net.minestom.server.event.item.PlayerFinishItemUseEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;

/**
 * Crossbows: a two-stage weapon, unlike the bow's continuous draw. CrossbowItem
 * (decompiled): right-click with an empty crossbow starts loading; the load
 * auto-completes after {@code getChargeDuration} ticks (base 1.25s, Quick Charge
 * -0.25s/level) EVEN WITHOUT RELEASING, storing the drawn projectile(s) in the
 * {@code charged_projectiles} data component. A second right-click while charged
 * fires immediately. We model the charged state as a custom item tag (Minestom has
 * no charged_projectiles component) and drive load-completion off
 * {@code PlayerBeginItemUseEvent#setItemUseDuration} + {@code PlayerFinishItemUseEvent}
 * (fires automatically once the set duration elapses while still held) /
 * {@code PlayerCancelItemUseEvent} (early release — no-op, matches vanilla).
 * Multishot/Piercing are crossbow-only enchants (bow's Power/Punch/Flame are NOT
 * crossbow-enchantable per data/minecraft/enchantment/*.json supported_items).
 */
public final class Crossbow {
    private Crossbow() {}

    public static final Tag<Boolean> CHARGED = Tag.Boolean("minecom:crossbow_charged");
    public static final Tag<Integer> CHARGE_COUNT = Tag.Integer("minecom:crossbow_charge_count");
    /** Remaining pierce hits on a fired arrow (Piercing enchant); read by Combat.projectileHit. */
    public static final Tag<Integer> PIERCE = Tag.Integer("minecom:arrow_pierce");

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemEvent.class, e -> {
            if (e.getItemStack().material() != Material.CROSSBOW) return;
            if (isCharged(e.getItemStack())) {
                e.setCancelled(true); // already charged: this click fires, doesn't start a new load
                shoot(e.getPlayer(), e.getHand(), e.getItemStack());
            }
        });

        events.addListener(PlayerBeginItemUseEvent.class, e -> {
            if (e.getItemStack().material() != Material.CROSSBOW) return;
            boolean creative = e.getPlayer().getGameMode() == GameMode.CREATIVE;
            if (!creative && !hasArrow(e.getPlayer())) {
                e.setCancelled(true);
                return;
            }
            int quickCharge = Enchants.level(e.getItemStack(), "quick_charge");
            float seconds = 1.25f - 0.25f * quickCharge; // enchantment/quick_charge.json: -0.25/level
            e.setItemUseDuration(Math.round(seconds * 20));
        });

        events.addListener(PlayerFinishItemUseEvent.class, e -> {
            if (e.getItemStack().material() != Material.CROSSBOW) return;
            load(e.getPlayer(), e.getHand(), e.getItemStack());
        });
    }

    private static boolean isCharged(ItemStack crossbow) {
        return Boolean.TRUE.equals(crossbow.getTag(CHARGED));
    }

    private static void load(Player player, PlayerHand hand, ItemStack crossbow) {
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (!creative && !consumeArrow(player)) return; // arrow could've vanished mid-charge

        int multishot = Enchants.level(crossbow, "multishot");
        int count = multishot > 0 ? 3 : 1;
        ItemStack updated = crossbow.withTag(CHARGED, true).withTag(CHARGE_COUNT, count);
        player.setItemInHand(hand, updated);
    }

    private static void shoot(Player player, PlayerHand hand, ItemStack crossbow) {
        int count = crossbow.getTag(CHARGE_COUNT) != null ? crossbow.getTag(CHARGE_COUNT) : 1;
        int pierceLevel = Enchants.level(crossbow, "piercing");

        Pos from = player.getPosition().add(0, player.getEyeHeight(), 0);
        Vec dir = player.getPosition().direction();
        // CrossbowItem.ARROW_POWER = 3.15 (vanilla shoot() units); Bow.java's established
        // conversion to Minestom velocity units is *32 at BowItem's full-draw power*3.0,
        // i.e. a 32/3.0 scale factor — applied here to the crossbow's fixed power.
        float speed = 3.15f * (32f / 3.0f);
        float damage = 6f; // crossbow always fires at the bow's full-draw equivalent (power=1.0 -> 2+4*1)

        float maxAngleDeg = count > 1 ? 10f : 0f; // multishot.json: projectile_spread +10 per level (max_level 1)
        for (int i = 0; i < count; i++) {
            float angle = count == 1 ? 0f : (i - (count - 1) / 2f) * maxAngleDeg;
            Vec shotDir = rotateAroundUp(dir, angle);
            EntityProjectile arrow = new EntityProjectile(player, EntityType.ARROW);
            arrow.setInstance(player.getInstance(), from);
            arrow.setVelocity(shotDir.mul(speed));
            arrow.setTag(Bow.DAMAGE, damage);
            if (pierceLevel > 0) arrow.setTag(PIERCE, pierceLevel);
        }

        ItemStack cleared = crossbow.withTag(CHARGED, false).withTag(CHARGE_COUNT, 0);
        if (!(player.getGameMode() == GameMode.CREATIVE)) {
            cleared = Items.damageItem(player, cleared, 1);
        }
        player.setItemInHand(hand, cleared);
    }

    /** Rotates a direction vector by {@code degrees} around the world Y axis (multishot fan). */
    private static Vec rotateAroundUp(Vec dir, float degrees) {
        if (degrees == 0f) return dir;
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        return new Vec(dir.x() * cos - dir.z() * sin, dir.y(), dir.x() * sin + dir.z() * cos);
    }

    /** ProjectileWeaponItem.getHeldProjectile checks the offhand before the inventory. */
    private static boolean hasArrow(Player player) {
        if (player.getItemInOffHand().material() == Material.ARROW) return true;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItemStack(i).material() == Material.ARROW) return true;
        }
        return false;
    }

    /**
     * Finds and removes 1 arrow; true if one was found. Offhand first (decompile-
     * verified against ProjectileWeaponItem.getHeldProjectile, same as Bow.java) —
     * previously only ever scanned the general inventory, so an arrow held in the
     * offhand was neither detected by hasArrow() nor consumed by this.
     */
    private static boolean consumeArrow(Player player) {
        ItemStack offhand = player.getItemInOffHand();
        if (offhand.material() == Material.ARROW) {
            player.setItemInOffHand(offhand.amount() <= 1 ? ItemStack.AIR : offhand.withAmount(offhand.amount() - 1));
            return true;
        }
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
