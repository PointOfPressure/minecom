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
import net.minestom.server.event.item.PlayerCancelItemUseEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;

/**
 * Tridents: melee stats (8 attack damage, -2.9 attack speed) already come for free
 * from the item's real ATTRIBUTE_MODIFIERS component (same generic path every other
 * weapon uses — see {@code Items.attackDamage}); this class adds the throw/riptide
 * mechanic, which {@code TridentItem.releaseUsing} (decompiled) models as: release
 * after >=10 ticks held (THROW_THRESHOLD_TIME) either throws a real projectile, or —
 * if Riptide is enchanted AND the player is in water/rain — launches the player
 * instead and does NOT throw (a Riptide trident literally cannot be thrown on dry
 * land at all: vanilla's outer release guard is
 * {@code !(riptide>0) || (inWaterOrRain && !isPassenger)}). Real vanilla's riptide
 * launch also starts a 20-tick spin-attack with damage immunity/AoE — bounded here
 * to just the directional push, same "real but not full-fidelity" line drawn for
 * sweep attack's missing attack-cooldown gate.
 */
public final class Trident {
    private Trident() {}

    public static final Tag<Float> DAMAGE = Tag.Float("minecom:trident_damage");
    public static final Tag<Integer> LOYALTY = Tag.Integer("minecom:trident_loyalty");
    public static final Tag<Integer> IMPALING = Tag.Integer("minecom:trident_impaling");
    public static final Tag<Boolean> CHANNELING = Tag.Boolean("minecom:trident_channeling");

    private static final int THROW_THRESHOLD_TIME = 10;

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerCancelItemUseEvent.class, e -> {
            if (e.getItemStack().material() != Material.TRIDENT) return;
            release(e.getPlayer(), e.getHand(), e.getItemStack(), (int) e.getUseDuration());
        });
    }

    private static void release(Player player, PlayerHand hand, ItemStack trident, int ticksHeld) {
        if (ticksHeld < THROW_THRESHOLD_TIME) return;

        int riptideLevel = Enchants.level(trident, "riptide");
        float riptideStrength = riptideLevel > 0 ? 1.5f + 0.75f * (riptideLevel - 1) : 0f;
        boolean wet = isInWater(player) || WeatherCycle.isRaining(player.getInstance());
        if (riptideStrength > 0f && !wet) return; // riptide tridents can't be thrown on dry land

        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (riptideStrength > 0f) {
            if (!creative) player.setItemInHand(hand, Items.damageItem(player, trident, 1));
            riptideLaunch(player, riptideStrength);
            return;
        }

        throwTrident(player, trident);
        if (!creative) player.setItemInHand(hand, ItemStack.AIR);
    }

    private static void riptideLaunch(Player player, float strength) {
        Vec dir = player.getPosition().direction();
        // vanilla's Entity.push adds directly to blocks/tick deltaMovement; Minestom's
        // setVelocity is blocks/second, so *20 is the direct unit conversion (not a
        // heuristic, unlike the arrow-speed ratio Bow/Crossbow use for a different API).
        player.setVelocity(player.getVelocity().add(dir.mul(strength * 20)));
    }

    private static void throwTrident(Player player, ItemStack trident) {
        Pos from = player.getPosition().add(0, player.getEyeHeight(), 0);
        Vec dir = player.getPosition().direction();
        EntityProjectile thrown = new EntityProjectile(player, EntityType.TRIDENT);
        thrown.setInstance(player.getInstance(), from);
        // TridentItem.PROJECTILE_SHOOT_POWER = 2.5 (vanilla shoot() units); same 32/3.0
        // conversion Bow.java established for translating those into this project's
        // EntityProjectile velocity units.
        float speed = 2.5f * (32f / 3.0f);
        thrown.setVelocity(dir.mul(speed));
        thrown.setTag(DAMAGE, 8f);
        int loyalty = Enchants.level(trident, "loyalty");
        if (loyalty > 0) thrown.setTag(LOYALTY, loyalty);
        int impaling = Enchants.level(trident, "impaling");
        if (impaling > 0) thrown.setTag(IMPALING, impaling);
        if (Enchants.level(trident, "channeling") > 0) thrown.setTag(CHANNELING, true);
    }

    /**
     * Loyalty: pulls the thrown trident back to its owner and returns it to their
     * inventory once close. Bounded vs. real vanilla: starts on any entity hit (not
     * also on sticking into a block/ground, which this project's arrows don't model
     * either) and homes at a flat speed rather than vanilla's accelerating pull.
     */
    public static void startReturn(EntityProjectile trident, Player owner, int loyalty) {
        // Deferred by a tick rather than called synchronously: this fires from deep inside the
        // trident's OWN tick()/collision handling, and mutating its velocity reentrantly during
        // that same tick is avoided in favor of a clean next-tick start.
        trident.scheduler().buildTask(() -> returnTick(trident, owner, loyalty))
                .delay(TaskSchedule.tick(1)).schedule();
    }

    private static void returnTick(EntityProjectile trident, Player owner, int loyalty) {
        if (trident.isRemoved()) return;
        if (owner.isRemoved() || owner.isDead()) {
            trident.remove();
            return;
        }
        Pos ownerPos = owner.getPosition().add(0, owner.getEyeHeight(), 0);
        Vec toOwner = new Vec(ownerPos.x() - trident.getPosition().x(),
                ownerPos.y() - trident.getPosition().y(), ownerPos.z() - trident.getPosition().z());
        if (toOwner.length() < 1.5) {
            trident.remove();
            if (owner.getGameMode() != GameMode.CREATIVE) {
                owner.getInventory().addItemStack(ItemStack.of(Material.TRIDENT));
            }
            return;
        }
        trident.setVelocity(toOwner.normalize().mul(10 + 10 * loyalty));
        trident.scheduler().buildTask(() -> returnTick(trident, owner, loyalty))
                .delay(TaskSchedule.tick(1)).schedule();
    }

    private static boolean isInWater(Player player) {
        Block b = player.getInstance().getBlock(player.getPosition());
        return b.compare(Block.WATER) || b.compare(Block.BUBBLE_COLUMN);
    }
}
