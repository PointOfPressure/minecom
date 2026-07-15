package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.data.VanillaData;
import dev.pointofpressure.minecom.mobs.ai.VBrain;
import dev.pointofpressure.minecom.mobs.ai.VanillaMobs;
import net.minestom.server.color.DyeColor;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.animal.tameable.CatMeta;
import net.minestom.server.entity.metadata.animal.tameable.TameableAnimalMeta;
import net.minestom.server.entity.metadata.animal.tameable.WolfMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.Food;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wolf and cat taming, sitting, collar dyeing, feeding, and wolf owner-defense AI.
 * Verified against decompiled TamableAnimal/Wolf/Cat (26.2): bone/fish taming is a
 * flat 1-in-3 roll per feed (Wolf.tryToTame / Cat.tryToTame — no growing "trust"
 * meter, contrary to a common Bedrock-influenced assumption), a tame wolf's max
 * health jumps 8 -&gt; 40 (Wolf.applyTamingSideEffects), and feeding heals
 * {@code healingFactor * FOOD.nutrition()} (Wolf.feed / TamableAnimal.feed) with a
 * flat fallback when an item carries no FOOD component. Owner-defense mirrors
 * Wolf.registerGoals' OwnerHurtByTargetGoal/OwnerHurtTargetGoal but as a direct
 * {@link VBrain#setTarget} call from Combat.java's two damage hooks rather than a
 * polling goal, since the trigger there is already an event. Cats have no
 * owner-defense goals in vanilla (Cat.registerGoals has none) and are never wired
 * into Combat. Sitting and follow-owner are goals in {@code mobs.ai.Goals}
 * (SitWhenOrdered, FollowOwner); this class owns interaction, ownership lookup, and
 * the tame/feed/dye state transitions. Not modeled (AUDIT.md): wolf body armor,
 * wolf/cat variant textures and sounds (client visuals), persistent-anger duration
 * (a provoked wild wolf simply keeps its Goals.HurtByTarget grudge forever rather
 * than calming down after 20-39s), wolf/cat breeding.
 */
public final class Taming {
    private Taming() {}

    private static final Map<Material, DyeColor> DYES = Map.ofEntries(
            Map.entry(Material.WHITE_DYE, DyeColor.WHITE), Map.entry(Material.ORANGE_DYE, DyeColor.ORANGE),
            Map.entry(Material.MAGENTA_DYE, DyeColor.MAGENTA), Map.entry(Material.LIGHT_BLUE_DYE, DyeColor.LIGHT_BLUE),
            Map.entry(Material.YELLOW_DYE, DyeColor.YELLOW), Map.entry(Material.LIME_DYE, DyeColor.LIME),
            Map.entry(Material.PINK_DYE, DyeColor.PINK), Map.entry(Material.GRAY_DYE, DyeColor.GRAY),
            Map.entry(Material.LIGHT_GRAY_DYE, DyeColor.LIGHT_GRAY), Map.entry(Material.CYAN_DYE, DyeColor.CYAN),
            Map.entry(Material.PURPLE_DYE, DyeColor.PURPLE), Map.entry(Material.BLUE_DYE, DyeColor.BLUE),
            Map.entry(Material.BROWN_DYE, DyeColor.BROWN), Map.entry(Material.GREEN_DYE, DyeColor.GREEN),
            Map.entry(Material.RED_DYE, DyeColor.RED), Map.entry(Material.BLACK_DYE, DyeColor.BLACK));

    private static final float WOLF_TAME_HEALTH = 40.0f;

    /**
     * Registered after Combat.register (see Bootstrap) so both damage listeners below
     * observe Combat's cancellation/mitigation decisions rather than racing them —
     * deliberately kept as separate listeners instead of edits inside Combat.java's
     * dense, already-tested damage pipeline.
     */
    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerEntityInteractEvent.class, Taming::interact);
        events.addListener(EntityDamageEvent.class, e -> {
            if (e.isCancelled() || !(e.getEntity() instanceof Player player)) return;
            if (e.getDamage().getAttacker() instanceof LivingEntity attacker) {
                ownerHurt(player, attacker);
            }
        });
        events.addListener(EntityAttackEvent.class, e -> {
            if (!(e.getEntity() instanceof Player player)) return;
            if (e.getTarget() instanceof LivingEntity target && !target.isDead()) {
                ownerAttacked(player, target);
            }
        });
    }

    private static void interact(PlayerEntityInteractEvent e) {
        if (e.getHand() != PlayerHand.MAIN) return;
        if (!(e.getTarget() instanceof EntityCreature mob) || mob.isDead()) return;
        if (mob.getEntityType() == EntityType.WOLF) wolfInteract(e.getPlayer(), mob);
        else if (mob.getEntityType() == EntityType.CAT) catInteract(e.getPlayer(), mob);
    }

    private static void wolfInteract(Player player, EntityCreature wolf) {
        if (!(wolf.getEntityMeta() instanceof WolfMeta meta)) return;
        ItemStack held = player.getItemInMainHand();
        if (meta.isTamed()) {
            boolean owned = player.getUuid().equals(meta.getOwner());
            if (VanillaData.itemHasTag(held.material(), "wolf_food") && wolf.getHealth() < wolf.getAttributeValue(Attribute.MAX_HEALTH)) {
                feed(wolf, player, held, 2.0f, 2.0f);
                return;
            }
            if (owned && VanillaData.itemHasTag(held.material(), "wolf_collar_dyes")) {
                DyeColor color = DYES.get(held.material());
                if (color != null && color != meta.getCollarColor()) {
                    meta.setCollarColor(color);
                    consume(player, held);
                }
                return;
            }
            if (owned) toggleSit(wolf, meta);
        } else if (held.material() == Material.BONE) {
            consume(player, held);
            tryTame(wolf, meta, player, WOLF_TAME_HEALTH);
        }
    }

    private static void catInteract(Player player, EntityCreature cat) {
        if (!(cat.getEntityMeta() instanceof CatMeta meta)) return;
        ItemStack held = player.getItemInMainHand();
        if (meta.isTamed()) {
            boolean owned = player.getUuid().equals(meta.getOwner());
            if (!owned) return;
            if (VanillaData.itemHasTag(held.material(), "cat_collar_dyes")) {
                DyeColor color = DYES.get(held.material());
                if (color != null && color != meta.getCollarColor()) {
                    meta.setCollarColor(color);
                    consume(player, held);
                }
                return;
            }
            if (VanillaData.itemHasTag(held.material(), "cat_food") && cat.getHealth() < cat.getAttributeValue(Attribute.MAX_HEALTH)) {
                feed(cat, player, held, 1.0f, 1.0f);
                return;
            }
            toggleSit(cat, meta);
        } else if (VanillaData.itemHasTag(held.material(), "cat_food")) {
            consume(player, held);
            tryTame(cat, meta, player, -1);
        }
    }

    /** Wolf.tryToTame / Cat.tryToTame: a flat 1-in-3 roll, no accumulating trust. */
    private static void tryTame(EntityCreature mob, TameableAnimalMeta meta, Player player, float tameHealth) {
        if (ThreadLocalRandom.current().nextInt(3) != 0) return;
        meta.setTamed(true);
        meta.setOwner(player.getUuid());
        meta.setSitting(true);
        if (tameHealth > 0) {
            mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(tameHealth);
            mob.setHealth(tameHealth);
        }
        VBrain brain = VanillaMobs.brainOf(mob);
        if (brain != null) { brain.stopNavigation(); brain.setTarget(null); }
    }

    private static void toggleSit(EntityCreature mob, TameableAnimalMeta meta) {
        boolean sitting = !meta.isSitting();
        meta.setSitting(sitting);
        if (sitting) {
            VBrain brain = VanillaMobs.brainOf(mob);
            if (brain != null) { brain.stopNavigation(); brain.setTarget(null); }
        }
    }

    /** TamableAnimal.feed: heal = healingFactor * FOOD.nutrition(), or defaultHeal with no FOOD component. */
    private static void feed(EntityCreature mob, Player player, ItemStack item, float healingFactor, float defaultHeal) {
        Food food = item.get(DataComponents.FOOD);
        float heal = food != null ? healingFactor * food.nutrition() : defaultHeal;
        mob.setHealth((float) Math.min(mob.getAttributeValue(Attribute.MAX_HEALTH), mob.getHealth() + heal));
        consume(player, item);
    }

    private static void consume(Player player, ItemStack item) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setItemInMainHand(item.consume(1));
        }
    }

    // ------------------------------------------------------------------ ownership

    /** Live owner lookup by stored UUID (owner isn't networked for anything but wolves/cats). */
    public static Player ownerOf(EntityCreature mob) {
        if (!(mob.getEntityMeta() instanceof TameableAnimalMeta meta)) return null;
        UUID id = meta.getOwner();
        if (id == null || mob.getInstance() == null) return null;
        for (Player p : mob.getInstance().getPlayers()) {
            if (p.getUuid().equals(id)) return p;
        }
        return null;
    }

    public static boolean isTamed(EntityCreature mob) {
        return mob.getEntityMeta() instanceof TameableAnimalMeta meta && meta.isTamed();
    }

    // ------------------------------------------------------------------ owner defense (wolves only)

    /**
     * OwnerHurtByTargetGoal: called from Combat.damaged when a player takes a hit —
     * every tamed wolf owned by that player within follow range retaliates against
     * the attacker.
     */
    public static void ownerHurt(Player owner, LivingEntity attacker) {
        alertWolves(owner, attacker);
    }

    /**
     * OwnerHurtTargetGoal: called from Combat.attack when a player lands a hit —
     * every tamed wolf owned by that player within follow range piles on too.
     */
    public static void ownerAttacked(Player owner, LivingEntity target) {
        alertWolves(owner, target);
    }

    private static void alertWolves(Player owner, LivingEntity target) {
        if (target == null || target.isDead() || target == owner || owner.getInstance() == null) return;
        double range = 16.0;
        for (var entity : owner.getInstance().getNearbyEntities(owner.getPosition(), range)) {
            if (!(entity instanceof EntityCreature wolf) || wolf.getEntityType() != EntityType.WOLF) continue;
            if (!(wolf.getEntityMeta() instanceof WolfMeta meta) || !meta.isTamed()) continue;
            if (!owner.getUuid().equals(meta.getOwner())) continue;
            if (!wolfWantsToAttack(target, owner)) continue;
            VBrain brain = VanillaMobs.brainOf(wolf);
            if (brain != null) brain.setTarget(target);
        }
    }

    /** Wolf.wantsToAttack, trimmed to what this project models: never the owner, never
     *  a creeper (they'd blow the wolf up), never another tame pet of the same owner. */
    private static boolean wolfWantsToAttack(LivingEntity target, Player owner) {
        if (target == owner || target.getEntityType() == EntityType.CREEPER) return false;
        if (target instanceof EntityCreature other && other.getEntityMeta() instanceof TameableAnimalMeta otherMeta
                && otherMeta.isTamed() && owner.getUuid().equals(otherMeta.getOwner())) {
            return false;
        }
        return true;
    }
}
