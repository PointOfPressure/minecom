package dev.pointofpressure.minecom.survival;

import dev.pointofpressure.minecom.data.Enchants;
import dev.pointofpressure.minecom.data.Items;
import net.minestom.server.component.DataComponents;
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
import net.minestom.server.item.component.PotionContents;
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
 * Tipped/spectral arrows load the same as a plain one (Bow.isArrowFamily) — since the
 * real arrow is consumed at load() time but fired at shoot() time (possibly ticks
 * later), its material/potion identity rides the crossbow ItemStack's own tags
 * (CHARGED_SPECTRAL/CHARGED_POTION) in between; multishot's extra copies all carry
 * whatever the single loaded arrow was, matching real vanilla (multishot doesn't
 * consume 3 arrows, just fires the one loaded arrow three times).
 */
public final class Crossbow {
    private Crossbow() {}

    public static final Tag<Boolean> CHARGED = Tag.Boolean("minecom:crossbow_charged");
    public static final Tag<Integer> CHARGE_COUNT = Tag.Integer("minecom:crossbow_charge_count");
    /** Remaining pierce hits on a fired arrow (Piercing enchant); read by Combat.projectileHit. */
    public static final Tag<Integer> PIERCE = Tag.Integer("minecom:arrow_pierce");
    /** Which arrow-family material got loaded (and its potion, if tipped) — captured at
     *  load() time since that's when the real arrow is consumed, and read back at
     *  shoot() time (which may run ticks later) to build the right projectile. */
    private static final Tag<Boolean> CHARGED_SPECTRAL = Tag.Boolean("minecom:crossbow_charged_spectral");
    private static final Tag<String> CHARGED_POTION = Tag.String("minecom:crossbow_charged_potion");

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
        ItemStack ammo = ItemStack.AIR;
        if (!creative) {
            ammo = consumeArrow(player);
            if (ammo.isAir()) return; // arrow could've vanished mid-charge
        }

        int multishot = Enchants.level(crossbow, "multishot");
        int count = multishot > 0 ? 3 : 1;
        ItemStack updated = crossbow.withTag(CHARGED, true).withTag(CHARGE_COUNT, count);
        if (ammo.material() == Material.SPECTRAL_ARROW) {
            updated = updated.withTag(CHARGED_SPECTRAL, true);
        } else if (ammo.material() == Material.TIPPED_ARROW) {
            PotionContents contents = ammo.get(DataComponents.POTION_CONTENTS);
            if (contents != null && contents.potion() != null) {
                updated = updated.withTag(CHARGED_POTION, contents.potion().key().value());
            }
        }
        player.setItemInHand(hand, updated);
    }

    private static void shoot(Player player, PlayerHand hand, ItemStack crossbow) {
        int count = crossbow.getTag(CHARGE_COUNT) != null ? crossbow.getTag(CHARGE_COUNT) : 1;
        int pierceLevel = Enchants.level(crossbow, "piercing");
        boolean spectral = Boolean.TRUE.equals(crossbow.getTag(CHARGED_SPECTRAL));
        String potion = crossbow.getTag(CHARGED_POTION);

        Pos from = player.getPosition().add(0, player.getEyeHeight(), 0);
        Vec dir = player.getPosition().direction();
        // CrossbowItem.ARROW_POWER = 3.15 (vanilla shoot() units); Bow.java's established
        // conversion to Minestom velocity units is *32 at BowItem's full-draw power*3.0,
        // i.e. a 32/3.0 scale factor — applied here to the crossbow's fixed power.
        float speed = 3.15f * (32f / 3.0f);
        float damage = 6f; // crossbow always fires at the bow's full-draw equivalent (power=1.0 -> 2+4*1)

        float maxAngleDeg = count > 1 ? 10f : 0f; // multishot.json: projectile_spread +10 per level (max_level 1)
        // multishot triples the SINGLE loaded arrow (real vanilla doesn't consume 3) — every
        // shot below carries the same loaded potion/spectral identity.
        EntityType arrowType = spectral ? EntityType.SPECTRAL_ARROW : EntityType.ARROW;
        for (int i = 0; i < count; i++) {
            float angle = count == 1 ? 0f : (i - (count - 1) / 2f) * maxAngleDeg;
            Vec shotDir = rotateAroundUp(dir, angle);
            EntityProjectile arrow = new EntityProjectile(player, arrowType);
            arrow.setInstance(player.getInstance(), from);
            arrow.setVelocity(shotDir.mul(speed));
            arrow.setTag(Bow.DAMAGE, damage);
            if (pierceLevel > 0) arrow.setTag(PIERCE, pierceLevel);
            if (potion != null) arrow.setTag(Bow.POTION, potion);
        }

        ItemStack cleared = crossbow.withTag(CHARGED, false).withTag(CHARGE_COUNT, 0)
                .withTag(CHARGED_SPECTRAL, false).withTag(CHARGED_POTION, null);
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

    /** ProjectileWeaponItem.getHeldProjectile checks the offhand before the inventory;
     *  any arrow-family item (plain/tipped/spectral) is valid ammo, same as Bow.java. */
    private static boolean hasArrow(Player player) {
        if (Bow.isArrowFamily(player.getItemInOffHand().material())) return true;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (Bow.isArrowFamily(inv.getItemStack(i).material())) return true;
        }
        return false;
    }

    /**
     * Finds and removes 1 arrow-family item, returning the stack it came from (a single
     * copy, pre-decrement) or AIR if none. Offhand first (decompile-verified against
     * ProjectileWeaponItem.getHeldProjectile, same as Bow.java) — previously only ever
     * scanned the general inventory, so an arrow held in the offhand was neither
     * detected by hasArrow() nor consumed by this.
     */
    private static ItemStack consumeArrow(Player player) {
        ItemStack offhand = player.getItemInOffHand();
        if (Bow.isArrowFamily(offhand.material())) {
            player.setItemInOffHand(offhand.amount() <= 1 ? ItemStack.AIR : offhand.withAmount(offhand.amount() - 1));
            return offhand;
        }
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItemStack(i);
            if (!Bow.isArrowFamily(stack.material())) continue;
            inv.setItemStack(i, stack.amount() <= 1 ? ItemStack.AIR : stack.withAmount(stack.amount() - 1));
            return stack;
        }
        return ItemStack.AIR;
    }
}
